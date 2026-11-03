using System.Text;
using System.Text.Json;

namespace JraAgent.Core.Tests;

/// <summary>取得 → キュー（Fake JV-Link を別プロセスのワーカー経由で。旧 test_fetcher.py の移植）。EOF・中断・ゲート・重複起動・取得セッションの seq。</summary>
public sealed class FetcherTests : IDisposable
{
    // cp932 の 2 バイト文字（0x93 0xFA）を含む合成データ
    private static readonly byte[][] Recs = [.. Enumerable.Range(0, 7).Select(i => (byte[])[.. Encoding.ASCII.GetBytes($"RA{i:0000}"), .. Enumerable.Repeat(new byte[] { 0x93, 0xFA }, 5).SelectMany(x => x)])];
    private static readonly PlanItem Item = new("RACE", null, 2, null, 300.0);
    private static readonly PlanItem Rt = new("0B12", "20261101", null, null, 60.0);
    private readonly QueueFixture fx = new();
    private readonly List<WorkerSupervisor> sups = [];

    public void Dispose()
    {
        foreach (var s in sups) s.Stop();
        fx.Dispose();
    }

    private OutboxQueue Q => fx.Queue;

    private (Fetcher F, WorkerSupervisor Sup) Rig(FakeScript script, Func<bool>? mayFetch = null, double callTimeout = 5.0, SpecRegistry? spec = null)
    {
        var p = Path.Combine(fx.Dir, $"s{sups.Count}.json");
        Directory.CreateDirectory(fx.Dir);
        File.WriteAllText(p, script.ToJson());
        var sup = new WorkerSupervisor(WorkerTests.WorkerArgv(p), new WorkerConfig(CallTimeoutSeconds: callTimeout, MaxRestarts: 10), new FakeClock());
        sups.Add(sup);
        var f = new Fetcher("ag_test", Q, sup, fx.Clock, spec ?? SpecRegistry.AllConfirmedForTests(), new QueueConfig(MaxBatchRecords: 3, MinFreeBytes: 0), mayFetch ?? (() => true));
        return (f, sup);
    }

    private static JsonDocument Doc(BatchRow b) => JsonDocument.Parse(b.Payload);

    [Fact]
    public void Fetch_reads_all_records_into_contiguous_fetch_batches()
    {
        var (f, _) = Rig(new FakeScript { Files = [Recs[..4], Recs[4..]] });
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Completed, 7), (rep.Result, rep.Records));
        var batches = Q.Batches();
        Assert.Equal(Enumerable.Range(0, batches.Count).Select(i => (long?)i), batches.Select(b => b.FetchSeq)); // fetch.seq は 0 から連続
        var docs = batches.Select(Doc).ToList();
        try
        {
            Assert.Equal([.. Enumerable.Repeat(false, batches.Count - 1), true], docs.Select(d => d.RootElement.GetProperty("eof").GetBoolean()));
            Assert.Equal(7, docs.Sum(d => d.RootElement.GetProperty("records").GetArrayLength()));
            Assert.All(docs, d => Assert.True(d.RootElement.GetProperty("records").GetArrayLength() <= 3));
            Assert.All(docs, d => Assert.Equal(batches[0].FetchId, d.RootElement.GetProperty("fetch").GetProperty("id").GetString()));
            Assert.All(docs, d => Assert.Equal("20261031200000", d.RootElement.GetProperty("lastFileTimestamp").GetString()));
            Assert.All(docs, d => Assert.False(d.RootElement.TryGetProperty("tenantId", out _)));
            Assert.Equal((2, "RACE"), (docs[0].RootElement.GetProperty("option").GetInt32(), docs[0].RootElement.GetProperty("dataspec").GetString()));
            var got = docs.SelectMany(d => d.RootElement.GetProperty("records").EnumerateArray().Select(r => Convert.FromBase64String(r.GetProperty("bytesB64").GetString()!))).ToList();
            Assert.Equal(Recs, got); // cp932 のバイト列が欠けずに順番どおり
        }
        finally
        {
            foreach (var d in docs) d.Dispose();
        }
        var cp = Q.GetCheckpoint("RACE:2");
        Assert.Equal(("20261031200000", "20261031200000", (string?)null), (cp.FetchedTs, cp.QueuedTs, cp.AckedTs));
        Assert.Empty(Q.Verify());
    }

    [Fact]
    public void Unconfirmed_spec_blocks_real_fetch_by_default()
    {
        var (f, sup) = Rig(new FakeScript { Files = [Recs] }, spec: SpecRegistry.Default); // 本番の既定: 公式仕様の確認項目が 0 件
        var rep = f.FetchStored(Item);
        Assert.Equal(FetchResult.Skipped, rep.Result);
        Assert.StartsWith("SPEC_UNCONFIRMED", rep.Reason, StringComparison.Ordinal);
        Assert.Equal(FetchResult.Skipped, f.FetchRealtime(Rt).Result);
        Assert.Empty(Q.Batches());
        Assert.Contains("SPEC_UNCONFIRMED", Q.Alerts().Keys);
        Assert.False(sup.Started); // ワーカーも起動しない
        Assert.All(SpecRegistry.Default.Summary().Values, v => Assert.Equal("UNCONFIRMED", v));
    }

    [Fact]
    public void Not_normal_mode_does_not_start_fetch()
    {
        var (f, sup) = Rig(new FakeScript { Files = [Recs] }, () => false);
        Assert.Equal("MODE_NOT_NORMAL", f.FetchStored(Item).Reason);
        Assert.Empty(Q.Batches());
        Assert.False(sup.Started);
    }

    [Fact]
    public void Duplicate_fetch_is_prevented()
    {
        var (f, _) = Rig(new FakeScript { Files = [Recs] });
        Q.BeginSession("019a0000-0000-7000-8000-0000000000aa", "RACE:2", "RACE", 2, null, null);
        Assert.Equal("ALREADY_FETCHING", f.FetchStored(Item).Reason);
    }

    [Fact]
    public void Stop_mid_fetch_at_safe_boundary_keeps_queue_and_does_not_complete()
    {
        var n = 0;
        var (f, _) = Rig(new FakeScript { Files = [Recs[..3], Recs[3..]] }, () => ++n <= 2); // 開始時と 1 回目の読み込み後は NORMAL、その後 STOPPING を検知
        var rep = f.FetchStored(Item);
        Assert.Equal(FetchResult.StoppedSafe, rep.Result);
        var cp = Q.GetCheckpoint("RACE:2");
        Assert.Null(cp.FetchedTs); // 取得済みにしない
        Assert.Null(cp.AckedTs);
        Assert.Equal("INTERRUPTED", Q.Sessions()[0].State);
        Assert.NotEmpty(Q.Batches()); // 読み込んだ分は失わない
        Assert.False(Q.HasOpenSession("RACE:2"));
    }

    [Fact]
    public void Hang_aborts_session_without_advancing_checkpoints_and_keeps_queued_data()
    {
        var (f, sup) = Rig(new FakeScript { Files = [Recs], HangAfter = 5 }, callTimeout: 1.0);
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Aborted, "WORKER_read_batch"), (rep.Result, rep.Reason));
        var cp = Q.GetCheckpoint("RACE:2");
        Assert.Equal((null, null, null), (cp.FetchedTs, cp.QueuedTs, cp.AckedTs)); // 強制終了を取得完了と扱わない
        Assert.Equal("ABORTED", Q.Sessions()[0].State);
        // 次の周期で新しいセッションを開始できる（ワーカーは作り直される）。Fake はまた 5 件目でハングするので再び中止になるが、再起動は成功する
        Assert.Equal(FetchResult.Aborted, f.FetchStored(Item).Result);
        Assert.Equal(2, sup.Timeouts);
        Assert.True(sup.Restarts >= 1);
    }

    [Fact]
    public void Read_error_aborts()
    {
        var (f, _) = Rig(new FakeScript { Files = [Recs], ReadErrorAfter = 2, ReadErrorCode = -402 });
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Aborted, "READ_-402"), (rep.Result, rep.Reason));
        Assert.Null(Q.GetCheckpoint("RACE:2").FetchedTs);
    }

    [Fact]
    public void Open_failure_creates_no_session()
    {
        var (f, _) = Rig(new FakeScript { OpenCode = -111 });
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Aborted, (int?)-111), (rep.Result, rep.OpenCode));
        Assert.Empty(Q.Sessions());
    }

    [Fact]
    public void Init_failure_aborts_without_session()
    {
        var (f, _) = Rig(new FakeScript { InitCode = -1, Files = [Recs] });
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Aborted, "WORKER_INIT_-1"), (rep.Result, rep.Reason));
        Assert.Empty(Q.Sessions());
    }

    [Fact]
    public void Empty_result_still_sends_eof_batch_so_completion_is_confirmed_by_server()
    {
        var (f, _) = Rig(new FakeScript { Files = [] });
        Assert.Equal(FetchResult.Completed, f.FetchStored(Item).Result);
        var b = Assert.Single(Q.Batches());
        using var d = Doc(b);
        Assert.Equal(0, d.RootElement.GetProperty("records").GetArrayLength());
        Assert.True(d.RootElement.GetProperty("eof").GetBoolean());
    }

    [Fact]
    public void Queue_full_aborts_without_dropping_stored()
    {
        var (f, _) = Rig(new FakeScript { Files = [Recs] });
        Q.Cfg = new QueueConfig(MaxBytes: 1500, MinFreeBytes: 0, MaxBatchRecords: 3);
        var rep = f.FetchStored(Item);
        Assert.Equal(FetchResult.Aborted, rep.Result);
        Assert.StartsWith("QUEUE_", rep.Reason, StringComparison.Ordinal);
        Assert.Contains("QUEUE_FULL", Q.Alerts().Keys);
        Assert.Equal("ABORTED", Q.Sessions()[0].State);
        Assert.All(Q.Batches(), b => Assert.True(b.Status is "QUEUED" or "DEAD"));
    }

    [Fact]
    public void Bad_record_id_aborts_instead_of_dropping()
    {
        var (f, _) = Rig(new FakeScript { Files = [["RA0001"u8.ToArray(), [0x00, 0x01, .. "bad"u8.ToArray()]]] });
        var rep = f.FetchStored(Item);
        Assert.Equal((FetchResult.Aborted, "BAD_RECORD_ID"), (rep.Result, rep.Reason));
        Assert.Contains("BAD_RECORD", Q.Alerts().Keys);
    }

    [Fact]
    public void Realtime_batches_are_class_r_without_fetch_and_with_observed_at()
    {
        var (f, _) = Rig(new FakeScript { RealtimeRecords = ["O10001"u8.ToArray(), "O10002"u8.ToArray()] });
        var rep = f.FetchRealtime(Rt);
        Assert.Equal((FetchResult.Completed, 2), (rep.Result, rep.Records));
        var b = Assert.Single(Q.Batches());
        using var d = Doc(b);
        Assert.Equal("R", b.Cls);
        Assert.Null(b.FetchId);
        Assert.False(d.RootElement.TryGetProperty("fetch", out _));
        Assert.Equal("R", d.RootElement.GetProperty("class").GetString());
        Assert.EndsWith("Z", d.RootElement.GetProperty("observedAt").GetString(), StringComparison.Ordinal);
        var (empty, _) = Rig(new FakeScript { RealtimeRecords = [] });
        Assert.Equal(FetchResult.NoData, empty.FetchRealtime(Rt).Result);
    }

    [Fact]
    public void Fromtime_uses_acked_ts_minus_margin()
    {
        Assert.Equal("20261031140000", Fetcher.FromtimeFrom("20261031200000", null));
        Assert.Equal("20261001000000", Fetcher.FromtimeFrom(null, "20261001000000"));
        Assert.Equal("00000000000000", Fetcher.FromtimeFrom(null, "bad"));
        Assert.Equal("RACE:2", Fetcher.CursorKey("RACE", 2));
        Assert.Equal("0B12", Fetcher.CursorKey("0B12", null));
        Assert.Equal(("20261101", "20261130"), Fetcher.TargetRange(new PlanItem("YSCH", "2026110120261130", 1, null, 300)));
        Assert.Equal((null, null), Fetcher.TargetRange(new PlanItem("YSCH", "20261101", 1, null, 300)));
        Assert.Equal((null, null), Fetcher.TargetRange(new PlanItem("RACE", "2026110120261130", 1, null, 300)));
    }
}
