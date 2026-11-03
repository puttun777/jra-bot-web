using System.IO.Compression;
using System.Text.Json;
using static JraAgent.Core.Tests.ScriptedTransport;

namespace JraAgent.Core.Tests;

/// <summary>
/// 応答ごとの送信動作（CONTRACTS §3.6。旧 test_sender.py の移植）。リトライで新しい batchId を作らない・無限リトライしない・401/403 で止まる・STOPPED/UNKNOWN で送らない。
/// </summary>
public sealed class SenderTests : IDisposable
{
    private const string Ts = QueueFixture.Ts;
    private readonly QueueFixture fx = new();
    private readonly ScriptedTransport transport = new();
    private readonly SignedClient client;
    private ModeMachine modes;
    private Sender sender;

    public SenderTests()
    {
        client = new SignedClient("https://ingest.example", new RequestSigner("ag_test", "jrat1_tok"), transport, fx.Clock);
        modes = NormalMachine(fx.Clock);
        sender = MakeSender(new SenderConfig(MaxAttempts: 4));
    }

    public void Dispose() => fx.Dispose();

    private OutboxQueue Q => fx.Queue;

    private FakeClock Clock => fx.Clock;

    private Sender MakeSender(SenderConfig cfg, string fp = "fp1") => new(Q, client, modes, cfg, Clock, fp, new Backoff(), () => 0.5);

    private static ModeMachine NormalMachine(FakeClock clock)
    {
        var m = new ModeMachine(clock);
        m.OnBeacon(new BeaconResult(BeaconStatus.Valid, new Beacon("NORMAL", 5, "k", clock.Now, null)));
        m.OnServer(new Classified(Outcome.Ok, 200));
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        return m;
    }

    private BatchRow Realtime() => fx.EnqueueRealtime(Q);

    private static string Hex(byte[]? b) => Convert.ToHexString(b ?? []);

    [Fact]
    public void Ok_acks_batch_and_resend_never_changes_batch_id()
    {
        var b = Realtime();
        transport.Push(new TransportException("TIMEOUT"), Json(503, "{\"error\": \"UNAVAILABLE\"}", ("Retry-After", "2")), Json(200, AckBody()));
        var results = new List<StepResult>();
        for (var i = 0; i < 3; i++)
        {
            results.Add(sender.SendNext());
            Clock.Advance(1000);
            modes.OnServer(new Classified(Outcome.Ok, 200)); // runner の mode 確認（200）で送信再開が許される
        }
        Assert.Equal([SendStep.Retry, SendStep.Retry, SendStep.Acked], results.Select(r => r.Step));
        var ids = transport.Sent.Select(c =>
        {
            var body = c.Body ?? [];
            if (c.Headers.TryGetValue("Content-Encoding", out var enc) && enc == "gzip")
            {
                using var gz = new GZipStream(new MemoryStream(body), CompressionMode.Decompress);
                using var o = new MemoryStream();
                gz.CopyTo(o);
                body = o.ToArray();
            }
            using var doc = JsonDocument.Parse(body);
            return doc.RootElement.GetProperty("batchId").GetString();
        }).ToHashSet();
        Assert.Equal([b.BatchId], ids); // 3 回とも同じ batchId・同じ内容
        Assert.Single(transport.Sent.Select(c => Hex(c.Body)).Distinct());
        Assert.Equal("ACKED", Q.Batch(b.BatchId)?.Status);
    }

    [Fact]
    public void Status_400_413_422_make_dead_and_stop_auto_resend()
    {
        foreach (var status in new[] { 400, 413, 422 })
        {
            var b = Realtime();
            transport.Push(Json(status, "{\"error\": \"INVALID_BASE64\"}"));
            var r = sender.SendNext();
            Assert.Equal((SendStep.Dead, "INVALID_BASE64"), (r.Step, r.Detail));
            Assert.Equal("DEAD", Q.Batch(b.BatchId)?.Status);
        }
        Assert.Equal(SendStep.Idle, sender.SendNext().Step); // DEAD は再送されない
        Assert.Contains("DEAD_BATCH", Q.Alerts().Keys);
        Assert.Equal(3, transport.Sent.Count);
    }

    [Fact]
    public void Dead_in_fetch_session_aborts_session_and_never_advances_cursor()
    {
        var fid = QueueFixture.StartSession(Q);
        fx.EnqueueStored(Q, fid);
        var last = fx.EnqueueStored(Q, fid, eof: true);
        transport.Push(Json(422, "{\"error\": \"FETCH_MISMATCH\"}"));
        Assert.Equal(SendStep.Dead, sender.SendNext().Step);
        Assert.Equal("ABORTED", Q.Session(fid)?.State);
        Assert.Equal("SESSION_ABORTED", Q.Batch(last.BatchId)?.DeadReason);
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
        Assert.Equal(SendStep.Idle, sender.SendNext().Step);
    }

    [Theory]
    [InlineData(401)]
    [InlineData(403)]
    public void Auth_failure_stops_sending_and_never_retries_automatically(int status)
    {
        Realtime();
        Realtime();
        transport.Push(Json(status, "{\"error\": \"UNAUTHORIZED\"}"));
        Assert.Equal(SendStep.Halted, sender.SendNext().Step);
        Assert.Contains("AUTH_FAILED", Q.Alerts().Keys);
        for (var i = 0; i < 5; i++)
        {
            Clock.Advance(3600);
            Assert.Equal(SendStep.Halted, sender.SendNext().Step);
        }
        Assert.Single(transport.Sent); // 自動で再試行しない
        Assert.Equal(2, Q.Stats().ByStatus.GetValueOrDefault("QUEUED")); // バッチは失わない（試行にも数えない）
    }

    [Fact]
    public void Auth_halt_clears_only_when_credential_is_replaced()
    {
        Realtime();
        transport.Push(Json(403, "{\"error\": \"UNAUTHORIZED\"}"));
        Assert.Equal(SendStep.Halted, sender.SendNext().Step);
        Assert.Equal("AUTH_403", MakeSender(new SenderConfig()).AuthHalted()); // 同じ資格情報では解除しない
        var fresh = MakeSender(new SenderConfig(), "fp-new-token");
        transport.Push(Json(200, AckBody()));
        Assert.Equal(SendStep.Acked, fresh.SendNext().Step); // トークン再発行（指紋が変わる）で再開
        Assert.DoesNotContain("AUTH_FAILED", Q.Alerts().Keys);
    }

    [Fact]
    public void Status_401_with_large_clock_skew_is_reported_as_clock_problem()
    {
        Realtime();
        var date = Clock.Now.AddMinutes(20).ToString("r", System.Globalization.CultureInfo.InvariantCulture); // サーバの時刻が 20 分進んでいる
        transport.Push(Json(401, "{\"error\": \"UNAUTHORIZED\"}", ("Date", date)));
        sender.SendNext();
        Assert.Contains("CLOCK_SKEW", Q.Alerts().Keys);
        Assert.DoesNotContain("AUTH_FAILED", Q.Alerts().Keys);
        Assert.True(client.Skew.Warn);
        Assert.True(client.Skew.Suspicious);
        Assert.Equal("CLOCK_SKEW", sender.AuthHalted());
    }

    [Fact]
    public void Status_409_waits_retry_after_and_resends_same_batch()
    {
        var fid = QueueFixture.StartSession(Q);
        var b0 = fx.EnqueueStored(Q, fid, eof: true);
        transport.Push(Json(409, "{\"error\": \"BATCH_IN_PROGRESS\"}", ("Retry-After", "5")), Json(200, AckBody()));
        Assert.Equal(SendStep.Retry, sender.SendNext().Step);
        var row = Q.Batch(b0.BatchId);
        Assert.Equal("RETRY_WAIT", row?.Status);
        Assert.Equal(5.0, row!.NextTryAt - (Clock.Now.ToUnixTimeMilliseconds() / 1000.0), 6);
        Assert.Equal(SendStep.Idle, sender.SendNext().Step); // まだ待つ
        Clock.Advance(5.1);
        Assert.Equal(SendStep.Acked, sender.SendNext().Step);
        Assert.Single(transport.Sent.Select(c => Hex(c.Body)).Distinct());
    }

    [Fact]
    public void Status_409_wait_is_capped()
    {
        sender = MakeSender(new SenderConfig(MaxConflictWaitSeconds: 30.0, MaxAttempts: 9));
        var b = Realtime();
        transport.Push(Json(409, "{\"error\": \"FETCH_SEQ_AHEAD\"}", ("Retry-After", "900")));
        sender.SendNext();
        Assert.Equal(30.0, Q.Batch(b.BatchId)!.NextTryAt - (Clock.Now.ToUnixTimeMilliseconds() / 1000.0), 6);
    }

    [Fact]
    public void Status_429_follows_retry_after_and_slows_everything()
    {
        var a = Realtime();
        var b = Realtime();
        transport.Push(Json(429, "{\"error\": \"RATE_LIMITED\"}", ("Retry-After", "60")), Json(200, AckBody()));
        Assert.Equal(SendStep.Retry, sender.SendNext().Step);
        var t0 = Clock.Monotonic;
        var next = sender.SendNext(); // 全体が減速している（Retry-After の 60 秒待ってから送る）
        Assert.Equal(SendStep.Acked, next.Step);
        Assert.True(Clock.Monotonic - t0 >= 59.0);
        // 待っている間に a の再送時刻にもなったので、順序（古い順）どおり a が先に再送される
        Assert.Equal(a.BatchId, next.BatchId);
        Assert.Equal("QUEUED", Q.Batch(b.BatchId)?.Status);
    }

    [Theory]
    [InlineData("STARTING")]
    [InlineData("STOPPING")]
    [InlineData("STOPPED")]
    public void Status_503_with_ops_mode_stops_send_and_fetch_without_consuming_attempts(string mode)
    {
        var b = Realtime();
        transport.Push(Json(503, "{\"error\": \"OPS_MODE_NOT_NORMAL\"}", ("X-Ops-Mode", mode)));
        Assert.Equal(SendStep.Deferred, sender.SendNext().Step);
        Assert.Equal(AgentMode.StoppedConfirmed, modes.Evaluate().Mode);
        Assert.False(modes.MayFetch);
        Assert.False(modes.MaySend);
        Assert.Equal(0, Q.Batch(b.BatchId)?.Attempts);
        for (var i = 0; i < 3; i++) Assert.Equal(SendStep.Halted, sender.SendNext().Step); // 以後は送らない（HTTP も呼ばない）
        Assert.Single(transport.Sent);
    }

    [Fact]
    public void Status_503_without_mode_header_is_unknown_and_safe()
    {
        var b = Realtime();
        transport.Push(Json(503, "{\"error\": \"OPS_MODE_UNKNOWN\"}"));
        Assert.Equal(SendStep.Retry, sender.SendNext().Step);
        Assert.True(modes.Evaluate().SendPaused); // 一時障害の扱い: 送信を止めてバックオフ
        Assert.Equal(SendStep.Halted, sender.SendNext().Step);
        Assert.Equal("RETRY_WAIT", Q.Batch(b.BatchId)?.Status);
    }

    public static TheoryData<int> TransientCases() => [0, 1, 502, 504];

    [Theory]
    [MemberData(nameof(TransientCases))]
    public void Timeouts_and_gateway_errors_keep_batch_and_retry_same(int which)
    {
        var b = Realtime();
        transport.Push(which switch
        {
            0 => new TransportException("TIMEOUT"),
            1 => new TransportException("CONNECTION"),
            _ => (object)Resp(which),
        });
        Assert.Equal(SendStep.Retry, sender.SendNext().Step);
        var row = Q.Batch(b.BatchId);
        Assert.Equal("RETRY_WAIT", row?.Status);
        Assert.NotEmpty(row!.Payload);
    }

    [Fact]
    public void Retries_are_bounded_then_dead()
    {
        var b = Realtime();
        transport.Default = Json(500, "{\"error\": \"INTERNAL\"}");
        var steps = new List<SendStep>();
        for (var i = 0; i < 10; i++)
        {
            modes.OnServer(new Classified(Outcome.Ok, 200)); // 送信側の判定は別途: 個別バッチの 5xx を繰り返す状況
            steps.Add(sender.SendNext().Step);
            Clock.Advance(1000);
            if (steps[^1] == SendStep.Dead) break;
        }
        Assert.Equal(SendStep.Dead, steps[^1]);
        Assert.Equal(4, transport.Sent.Count); // max_attempts=4 で打ち切る（無限リトライしない）
        Assert.Equal("RETRY_EXHAUSTED", Q.Batch(b.BatchId)?.DeadReason);
        Assert.Contains("RETRY_EXHAUSTED", Q.Alerts().Keys);
    }

    [Fact]
    public void Unknown_mode_blocks_send_and_fetch()
    {
        modes = new ModeMachine(Clock); // 起動直後 = UNKNOWN
        sender = new Sender(Q, client, modes, new SenderConfig(), Clock, "fp1");
        Realtime();
        Assert.Equal(SendStep.Halted, sender.SendNext().Step);
        Assert.False(modes.MayFetch);
        Assert.Empty(transport.Sent);
    }

    [Theory]
    [InlineData("{\"ok\": true}")]
    [InlineData("[]")]
    [InlineData("not json")]
    [InlineData("{\"accepted\": \"1\", \"duplicates\": 0, \"duplicate\": false}")]
    [InlineData("{\"accepted\": 1, \"duplicates\": 0, \"duplicate\": 1}")]
    public void Ack_with_bad_shape_is_not_treated_as_ack(string body)
    {
        var b = Realtime();
        transport.Push(Json(200, body));
        Assert.Equal(SendStep.Retry, sender.SendNext().Step);
        Assert.Equal("RETRY_WAIT", Q.Batch(b.BatchId)?.Status);
    }

    [Fact]
    public void Cursor_in_final_ack_advances_acked_ts_and_partial_does_not()
    {
        var fid = QueueFixture.StartSession(Q);
        fx.EnqueueStored(Q, fid);
        fx.EnqueueStored(Q, fid, eof: true);
        transport.Push(
            Json(200, AckBody(fetch: $"{{\"id\": \"{fid}\", \"status\": \"IN_PROGRESS\", \"nextSeq\": 1}}")),
            Json(200, AckBody(fetch: $"{{\"id\": \"{fid}\", \"status\": \"SUCCEEDED\", \"nextSeq\": 2}}", cursor: $"{{\"dataType\": \"RACE:2\", \"lastFileTimestamp\": \"{Ts}\"}}")));
        Assert.Equal(SendStep.Acked, sender.SendNext().Step);
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
        Clock.Advance(1);
        Assert.Equal(SendStep.Acked, sender.SendNext().Step);
        Assert.Equal(Ts, Q.GetCheckpoint("RACE:2").AckedTs);
    }

    [Fact]
    public void Pacing_limits_send_rate()
    {
        for (var i = 0; i < 3; i++) Realtime();
        transport.Default = Json(200, AckBody());
        var t0 = Clock.Monotonic;
        for (var i = 0; i < 3; i++) Assert.Equal(SendStep.Acked, sender.SendNext().Step);
        Assert.True(Clock.Monotonic - t0 >= 1.0); // 最大 2 バッチ/秒 → 3 バッチで 1 秒以上
    }

    [Fact]
    public void Parse_ack_variants()
    {
        using (var d = JsonDocument.Parse("null")) Assert.Null(Ack.Parse(d.RootElement));
        using (var d = JsonDocument.Parse("{\"accepted\": \"1\"}")) Assert.Null(Ack.Parse(d.RootElement));
        using var doc = JsonDocument.Parse(AckBody(duplicate: true, fetch: "{\"id\": \"f\", \"status\": \"SUCCEEDED\", \"nextSeq\": 3}", cursor: $"{{\"dataType\": \"RACE:2\", \"lastFileTimestamp\": \"{Ts}\"}}"));
        var a = Ack.Parse(doc.RootElement);
        Assert.NotNull(a);
        Assert.True(a.Duplicate);
        Assert.Equal(("f", "SUCCEEDED", 3L, "RACE:2", Ts), (a.FetchId, a.FetchStatus, a.FetchNextSeq ?? -1, a.CursorType, a.CursorTs));
    }

    [Fact]
    public void Mode_turns_stopped_during_pacing_wait_sends_nothing()
    {
        // 監査 C: ペーシングの待機中に運用モードが STOPPED になったら、待機後に再確認して送信しない（STOPPED 後の余分な POST を防ぐ）
        Realtime();
        transport.Push(Json(429, "{\"error\": \"RATE_LIMITED\"}", ("Retry-After", "30")));
        Assert.Equal(SendStep.Retry, sender.SendNext().Step); // 429 の後の減速
        Clock.AfterSleep = _ => modes.OnBeacon(new BeaconResult(BeaconStatus.Valid, new Beacon("STOPPED", 6, "k", Clock.Now, null)));
        var r = sender.SendNext();
        Assert.Equal(SendStep.Halted, r.Step);
        Assert.Single(transport.Sent);
        Assert.Equal(0, Q.Stats().ByStatus.GetValueOrDefault("SENDING")); // claim していない（バッチは QUEUED/RETRY_WAIT のまま）
    }

    [Fact]
    public void Wire_names_match_the_python_values()
    {
        Assert.Equal("TRANSIENT_DB", WireNames.Of(Outcome.TransientDb));
        Assert.Equal("OPS_NOT_NORMAL", WireNames.Of(Outcome.OpsNotNormal));
        Assert.Equal("STOPPED_CONFIRMED", WireNames.Of(AgentMode.StoppedConfirmed));
        Assert.Equal("OK", WireNames.Of(Outcome.Ok));
    }
}
