using Microsoft.Data.Sqlite;

namespace JraAgent.Core.Tests;

/// <summary>SQLite WAL キュー: 永続化・クラッシュ復旧・順序・上限・チェックポイント（旧 test_queue.py の移植）。DB ファイルを実際に開き直して再起動を模擬する。</summary>
public sealed class QueueTests : IDisposable
{
    private const string Ts = QueueFixture.Ts;
    private readonly QueueFixture fx = new();

    private OutboxQueue Q => fx.Queue;

    public void Dispose() => fx.Dispose();

    private OutboxQueue Reopen(QueueConfig? cfg = null)
    {
        fx.Queue.Dispose();
        fx.Queue = fx.Open(cfg);
        return fx.Queue;
    }

    private static AckInfo FinalAck(string fid, string? cursorTs = Ts, string key = "RACE:2", string status = "SUCCEEDED") =>
        new(Accepted: 1, FetchId: fid, FetchStatus: status, CursorType: cursorTs is null ? null : key, CursorTs: cursorTs);

    private string Start(string key = "RACE:2", string? ts = Ts, string origin = "LIVE") => QueueFixture.StartSession(Q, key, ts, origin);

    private BatchRow Stored(string fid, bool eof = false, string? ts = Ts) => fx.EnqueueStored(Q, fid, eof: eof, ts: ts);

    private BatchRow Realtime() => fx.EnqueueRealtime(Q);

    [Fact]
    public void Wal_mode_is_enabled_and_file_is_real()
    {
        using (var c = new SqliteConnection($"Data Source={fx.QPath};Pooling=False"))
        {
            c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = "PRAGMA journal_mode";
            Assert.Equal("wal", cmd.ExecuteScalar()?.ToString()?.ToLowerInvariant());
        }
        Assert.Empty(Q.Verify());
    }

    [Fact]
    public void Opening_a_queue_with_another_schema_version_fails()
    {
        Q.ExecRaw("UPDATE meta SET v = '2' WHERE k = 'schema_version'");
        Q.Dispose();
        Assert.Throws<QueueException>(() => fx.Open());
        fx.Queue = new OutboxQueue(fx.QPath, fx.Clock, readOnly: true);
    }

    [Fact]
    public void Batches_survive_reopen_and_keep_identical_bytes()
    {
        var fid = Start();
        var b0 = Stored(fid);
        var b1 = Stored(fid, eof: true);
        var q2 = Reopen();
        var got = q2.Batches();
        Assert.Equal([b0.BatchId, b1.BatchId], got.Select(b => b.BatchId));
        Assert.Equal(b0.Payload, got[0].Payload); // 再起動しても本文（= batchId と内容）が同じ
        Assert.Equal(b1.Payload, got[1].Payload);
        Assert.Equal([0L, 1L], got.Select(b => b.FetchSeq ?? -1));
        Assert.Empty(q2.Verify());
    }

    [Fact]
    public void Crash_while_sending_requeues_same_batch_without_loss()
    {
        Realtime();
        var sending = Q.ClaimNext();
        Assert.NotNull(sending);
        Assert.Equal("SENDING", sending.Status);
        var q2 = Reopen(); // 送信中にプロセスが落ちた
        Assert.Equal((1, 0), q2.Recover());
        var again = q2.ClaimNext();
        Assert.NotNull(again);
        Assert.Equal(sending.BatchId, again.BatchId);
        Assert.Equal(sending.Payload, again.Payload);
        Assert.Equal(2, again.Attempts); // 試行は数える（同じ batchId で再送）
    }

    [Fact]
    public void Only_one_batch_is_sending_at_a_time_and_order_is_preserved()
    {
        var ids = Enumerable.Range(0, 3).Select(_ => Realtime().BatchId).ToList();
        var first = Q.ClaimNext();
        Assert.Equal(ids[0], first?.BatchId);
        Assert.Null(Q.ClaimNext()); // 送信中がある間は次を出さない
        Q.MarkAcked(first!.BatchId, new AckInfo(Accepted: 1));
        Assert.Equal(ids[1], Q.ClaimNext()?.BatchId);
    }

    [Fact]
    public void Realtime_goes_before_stored_and_retry_order_is_kept()
    {
        var fid = Start();
        var stored = Stored(fid, eof: true);
        var rt = Realtime();
        var first = Q.ClaimNext();
        Assert.Equal(rt.BatchId, first?.BatchId);
        Q.RetryLater(first!.BatchId, 30.0, "TEST", 10);
        Assert.Equal(stored.BatchId, Q.ClaimNext()?.BatchId); // 速報は再試行待ち（30 秒後）。その間に蓄積系が送られる
    }

    [Fact]
    public void Fetch_batches_are_sent_strictly_in_seq_order_head_of_line()
    {
        var fid = Start();
        var b0 = Stored(fid);
        var b1 = Stored(fid, eof: true);
        var c0 = Q.ClaimNext();
        Assert.Equal(b0.BatchId, c0?.BatchId);
        Q.RetryLater(c0!.BatchId, 10.0, "409", 10);
        Assert.Null(Q.ClaimNext()); // b0 が ACKED になるまで b1 は送らない（先走りは 409 になる）
        fx.Clock.Advance(11);
        var again = Q.ClaimNext();
        Assert.Equal(b0.BatchId, again?.BatchId); // 同じ batchId で再送
        Q.MarkAcked(b0.BatchId, new AckInfo(Accepted: 1, FetchStatus: "IN_PROGRESS", FetchNextSeq: 1));
        Assert.Equal(b1.BatchId, Q.ClaimNext()?.BatchId);
        Assert.Equal(1L, b1.FetchSeq);
    }

    [Fact]
    public void Resend_keeps_batch_id_seq_and_payload()
    {
        var fid = Start();
        var b0 = Stored(fid, eof: true);
        for (var i = 0; i < 3; i++)
        {
            var c = Q.ClaimNext();
            Assert.NotNull(c);
            Assert.Equal((b0.BatchId, 0L), (c.BatchId, c.FetchSeq ?? -1));
            Assert.Equal(b0.Payload, c.Payload);
            Q.RetryLater(c.BatchId, 1.0, "NET", 10);
            fx.Clock.Advance(2);
        }
    }

    [Fact]
    public void Retry_limit_makes_dead_and_aborts_session()
    {
        var fid = Start();
        var b0 = Stored(fid);
        var b1 = Stored(fid, eof: true);
        for (var i = 0; i < 3; i++)
        {
            var c = Q.ClaimNext();
            Assert.NotNull(c);
            var state = Q.RetryLater(c.BatchId, 1.0, "NET", 3);
            fx.Clock.Advance(2);
            Assert.Equal(i == 2 ? "DEAD" : "RETRY_WAIT", state);
        }
        Assert.Equal("RETRY_EXHAUSTED", Q.Batch(b0.BatchId)?.DeadReason);
        Assert.Equal("SESSION_ABORTED", Q.Batch(b1.BatchId)?.DeadReason);
        Assert.Equal("ABORTED", Q.Session(fid)?.State);
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
        Assert.Null(Q.ClaimNext());
    }

    [Fact]
    public void Retry_of_a_batch_that_is_not_sending_is_rejected()
    {
        var b = Realtime();
        Assert.Throws<QueueException>(() => Q.RetryLater(b.BatchId, 1, "X", 3));
    }

    [Fact]
    public void Dead_is_not_success_and_does_not_advance_checkpoints()
    {
        var fid = Start();
        Stored(fid);
        var last = Stored(fid, eof: true);
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.MarkDead(c.BatchId, "INVALID_BASE64");
        Assert.Equal("ABORTED", Q.Session(fid)?.State);
        Assert.Equal("DEAD", Q.Batch(last.BatchId)?.Status);
        Assert.Equal(2, Q.Stats().Dead);
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
        Assert.Null(Q.ClaimNext());
        Assert.Throws<QueueException>(() => Q.MarkDead(c.BatchId, "AGAIN"));
    }

    [Fact]
    public void Defer_returns_the_batch_without_consuming_an_attempt()
    {
        Realtime();
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.Defer(c.BatchId, "MODE_STOPPED");
        var b = Q.Batch(c.BatchId);
        Assert.Equal(("QUEUED", 0, "MODE_STOPPED"), (b?.Status, b?.Attempts ?? -1, b?.LastOutcome));
        Assert.Equal(c.BatchId, Q.ClaimNext()?.BatchId);
    }

    // ---------------------------------------------------------------- チェックポイント
    [Fact]
    public void Checkpoints_fetched_queued_acked_have_distinct_meanings()
    {
        var fid = Start();
        Stored(fid); // EOF 未到達
        var cp = Q.GetCheckpoint("RACE:2");
        Assert.Equal((null, null, null), (cp.FetchedTs, cp.QueuedTs, cp.AckedTs)); // EOF 前は fetched も queued も動かない
        Assert.NotNull(cp.QueuedSeq);
        Stored(fid, eof: true);
        cp = Q.GetCheckpoint("RACE:2");
        Assert.Equal((Ts, Ts, null), (cp.FetchedTs, cp.QueuedTs, cp.AckedTs)); // 取得済み・投入済み。ACK はまだ
        Assert.Equal("FETCHED", Q.Session(fid)?.State);
    }

    [Fact]
    public void Acked_ts_advances_only_by_server_cursor_on_eof_batch()
    {
        var fid = Start();
        var b0 = Stored(fid);
        var b1 = Stored(fid, eof: true);
        var c0 = Q.ClaimNext();
        Assert.NotNull(c0);
        // 一部のバッチだけ ACK → 進まない（cursor が付いていても EOF バッチではないので進めない）
        Q.MarkAcked(c0.BatchId, FinalAck(fid, status: "IN_PROGRESS"));
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
        var c1 = Q.ClaimNext();
        Assert.Equal(b1.BatchId, c1?.BatchId);
        Q.MarkAcked(c1!.BatchId, FinalAck(fid));
        Assert.Equal(Ts, Q.GetCheckpoint("RACE:2").AckedTs);
        Assert.Equal("COMPLETED", Q.Session(fid)?.State);
        Assert.True(Q.Session(fid)?.CursorConfirmed);
        Assert.NotEqual(b0.BatchId, b1.BatchId);
        Assert.Empty(Q.Verify());
    }

    [Fact]
    public void Success_on_a_non_eof_batch_aborts_the_session_as_a_protocol_violation()
    {
        var fid = Start();
        Stored(fid);
        Stored(fid, eof: true);
        var c0 = Q.ClaimNext();
        Q.MarkAcked(c0!.BatchId, FinalAck(fid));
        Assert.Equal(("ABORTED", "PROTOCOL_UNEXPECTED_SUCCESS"), (Q.Session(fid)?.State, Q.Session(fid)?.Reason));
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
    }

    public static TheoryData<int> NonMatchingAcks() => [0, 1, 2, 3, 4, 5];

    private static AckInfo NonMatching(int i) => i switch
    {
        0 => new AckInfo(Accepted: 1, FetchStatus: "SUCCEEDED"), // cursor が無い（サーバが完了を確認していない）
        1 => new AckInfo(Accepted: 1, FetchStatus: "IN_PROGRESS", CursorType: "RACE:2", CursorTs: Ts), // fetch がまだ進行中
        2 => new AckInfo(Accepted: 1, FetchStatus: "SUCCEEDED", CursorType: "RACE:1", CursorTs: Ts), // 別の種別の cursor
        3 => new AckInfo(Accepted: 1, FetchStatus: "SUCCEEDED", CursorType: "RACE:2", CursorTs: "20261231000000"), // 自分の取得と一致しない cursor
        4 => new AckInfo(Accepted: 1, FetchStatus: "FAILED"), // サーバが取得を失敗にした
        _ => new AckInfo(Accepted: 1, Duplicate: true), // 重複 ACK（cursor 情報なし）
    };

    [Theory]
    [MemberData(nameof(NonMatchingAcks))]
    public void Acked_ts_does_not_advance_without_a_matching_server_cursor(int which)
    {
        var fid = Start();
        Stored(fid, eof: true);
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.MarkAcked(c.BatchId, NonMatching(which));
        Assert.Null(Q.GetCheckpoint("RACE:2").AckedTs);
    }

    [Fact]
    public void Acked_ts_never_moves_backwards()
    {
        foreach (var ts in new[] { "20261101120000", "20261031000000" }) // 新しい → 古い（古い ACK でカーソルを巻き戻さない）
        {
            var fid = Start(ts: ts);
            Stored(fid, eof: true, ts: ts);
            var c = Q.ClaimNext();
            Assert.NotNull(c);
            Q.MarkAcked(c.BatchId, FinalAck(fid, cursorTs: ts));
        }
        Assert.Equal("20261101120000", Q.GetCheckpoint("RACE:2").AckedTs);
    }

    [Fact]
    public void Backfill_never_moves_checkpoints()
    {
        var fid = Start(origin: "BACKFILL");
        Stored(fid, eof: true);
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.MarkAcked(c.BatchId, FinalAck(fid)); // サーバが cursor を返しても（返さないはずだが）通常取得のカーソルは動かさない
        var cp = Q.GetCheckpoint("RACE:2");
        Assert.Equal((null, null, null), (cp.FetchedTs, cp.QueuedTs, cp.AckedTs));
    }

    [Fact]
    public void Ack_is_idempotent_and_requires_sending_state()
    {
        var b = Realtime();
        Assert.Throws<QueueException>(() => Q.MarkAcked(b.BatchId, new AckInfo(Accepted: 1))); // claim 前の ACK は受け付けない
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.MarkAcked(c.BatchId, new AckInfo(Accepted: 1));
        Q.MarkAcked(c.BatchId, new AckInfo(Accepted: 1)); // 二重でも壊れない
        Assert.Equal("ACKED", Q.Batch(b.BatchId)?.Status);
        Assert.Empty(Q.Batch(b.BatchId)!.Payload);
        Assert.Throws<QueueException>(() => Q.MarkAcked("unknown", new AckInfo()));
    }

    [Fact]
    public void Disconnect_right_before_ack_then_resend_gets_duplicate_ack()
    {
        var fid = Start();
        var b = Stored(fid, eof: true);
        Assert.NotNull(Q.ClaimNext()); // サーバは保存済みだが ACK が届く前に切断・再起動
        var q2 = Reopen();
        q2.Recover();
        var again = q2.ClaimNext();
        Assert.NotNull(again);
        Assert.Equal((b.BatchId, 0L), (again.BatchId, again.FetchSeq ?? -1));
        Assert.Equal(b.Payload, again.Payload);
        q2.MarkAcked(again.BatchId, new AckInfo(0, 1, Duplicate: true, FetchId: fid, FetchStatus: "SUCCEEDED", CursorType: "RACE:2", CursorTs: Ts));
        Assert.Equal(Ts, q2.GetCheckpoint("RACE:2").AckedTs); // サーバが重複 ACK に cursor を付けた場合（D-144）は確定できる
    }

    [Fact]
    public void Interrupted_session_is_restored_and_not_completed()
    {
        var fid = Start();
        Stored(fid);
        Stored(fid);
        var q2 = Reopen(); // 取得の途中で再起動（JV-Link の読み位置は復元できない）
        Assert.Equal(1, q2.Recover().SessionsInterrupted);
        var s = q2.Session(fid);
        Assert.Equal(("INTERRUPTED", 2L), (s?.State, s?.NextSeq ?? -1));
        Assert.Null(q2.GetCheckpoint("RACE:2").FetchedTs); // EOF 未到達なので取得済みにしない
        Assert.False(q2.HasOpenSession("RACE:2"));
        Assert.Equal(0L, q2.ClaimNext()?.FetchSeq); // すでにキューにある分は連番のまま送られる
        var newFid = QueueFixture.StartSession(q2); // 新しい取得は新しいセッション
        Assert.NotEqual(fid, newFid);
        Assert.Empty(q2.Verify());
    }

    [Fact]
    public void Duplicate_open_session_is_detected()
    {
        Start();
        Assert.True(Q.HasOpenSession("RACE:2"));
        Assert.False(Q.HasOpenSession("RACE:1"));
    }

    // ---------------------------------------------------------------- 期限・上限・ディスク
    [Fact]
    public void Realtime_expires_but_stored_does_not()
    {
        var rt = Realtime();
        var fid = Start();
        var st = Stored(fid, eof: true);
        fx.Clock.Advance(new QueueConfig().RealtimeTtlSeconds + 1);
        Assert.Equal(1, Q.ExpireRealtime());
        Assert.Equal("EXPIRED", Q.Batch(rt.BatchId)?.DeadReason);
        Assert.Equal("QUEUED", Q.Batch(st.BatchId)?.Status);
    }

    [Fact]
    public void Size_cap_drops_oldest_realtime_but_never_stored()
    {
        const long cap = 6000;
        var q = Reopen(new QueueConfig(MaxBytes: 1024 * 1024, MinFreeBytes: 0));
        q.Cfg = new QueueConfig(MaxBytes: cap, MinFreeBytes: 0);
        var first = Realtime();
        while (q.Stats().PendingBytes + first.Payload.Length <= cap) Realtime();
        var before = q.Stats().ByStatus.GetValueOrDefault("QUEUED");
        Realtime(); // 溢れる → 一番古い速報が DEAD(OVERFLOW_DROPPED)
        Assert.Equal("OVERFLOW_DROPPED", q.Batch(first.BatchId)?.DeadReason);
        Assert.True(q.Stats().RealtimeDropped >= 1);
        Assert.True(q.Stats().ByStatus.GetValueOrDefault("QUEUED") <= before);
    }

    [Fact]
    public void Stored_is_not_dropped_when_full_it_stops()
    {
        var q = Reopen(new QueueConfig(MaxBytes: 1024 * 1024, MinFreeBytes: 0));
        var fid = Start();
        var first = Stored(fid);
        q.Cfg = new QueueConfig(MaxBytes: first.Payload.Length + 10, MinFreeBytes: 0);
        var e = Assert.Throws<QueueFullException>(() => Stored(fid));
        Assert.Equal("QUEUE_FULL", e.Message);
        Assert.Equal("QUEUED", q.Batch(first.BatchId)?.Status);
        Assert.Equal(1L, q.Session(fid)?.NextSeq); // 失敗した投入は何も書かない
    }

    [Fact]
    public void Low_disk_space_stops_safely_and_keeps_existing()
    {
        long free = 1_000_000_000_000;
        fx.Queue.Dispose();
        fx.Queue = new OutboxQueue(fx.QPath, fx.Clock, new QueueConfig(MinFreeBytes: 500L * 1024 * 1024), _ => free);
        var keep = Realtime();
        free = 100L * 1024 * 1024;
        var e = Assert.Throws<QueueFullException>(Realtime);
        Assert.Equal("DISK_LOW", e.Message);
        Assert.Equal("QUEUED", Q.Batch(keep.BatchId)?.Status);
        Assert.Equal(100L * 1024 * 1024, Q.Stats().DiskFreeBytes);
    }

    [Fact]
    public void Sqlite_disk_full_error_rolls_back_without_partial_rows()
    {
        var fid = Start();
        Stored(fid);
        long pages;
        using (var c = new SqliteConnection($"Data Source={fx.QPath};Pooling=False"))
        {
            c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = "PRAGMA page_count";
            pages = Convert.ToInt64(cmd.ExecuteScalar(), System.Globalization.CultureInfo.InvariantCulture);
        }
        Q.ExecRaw($"PRAGMA max_page_count = {pages}"); // これ以上ファイルを大きくできない = SQLITE_FULL
        const string id = "019a0000-0000-7000-8000-000000000001";
        var e = Assert.Throws<QueueFullException>(() => Q.Enqueue(id, "R", "0B12", new byte[200_000], 1, true));
        Assert.Equal("DISK_FULL", e.Message);
        Assert.Null(Q.Batch(id)); // 部分的な行を残さない
        Assert.Empty(Q.Verify());
    }

    [Fact]
    public void Unknown_class_or_session_combination_rejected()
    {
        Assert.Throws<QueueException>(() => Q.Enqueue("x", "Z", "RACE", "{}"u8.ToArray(), 1, true));
        var fid = Start();
        Assert.Throws<QueueException>(() => Q.Enqueue("y", "R", "0B12", "{}"u8.ToArray(), 1, true, fid));
    }

    [Fact]
    public void Duplicate_batch_id_is_rejected_without_changing_the_queue()
    {
        var b = Realtime();
        Assert.Throws<QueueException>(() => Q.Enqueue(b.BatchId, "R", "0B12", "{}"u8.ToArray(), 1, true));
        Assert.Single(Q.Batches());
    }

    [Fact]
    public void Enqueue_into_non_reading_session_is_rejected()
    {
        var fid = Start();
        Stored(fid, eof: true);
        Assert.Throws<QueueException>(() => Stored(fid)); // EOF 後に追加できない（サーバも FETCH_CLOSED で拒否する）
    }

    [Fact]
    public void Housekeeping_removes_old_acked_and_keeps_active()
    {
        var done = Realtime();
        var keep = Realtime();
        var c = Q.ClaimNext();
        Assert.NotNull(c);
        Q.MarkAcked(c.BatchId, new AckInfo(Accepted: 1));
        fx.Clock.Advance(TimeSpan.FromDays(2).TotalSeconds);
        Assert.Equal(1, Q.Housekeeping());
        Assert.Null(Q.Batch(done.BatchId));
        Assert.NotNull(Q.Batch(keep.BatchId));
    }

    [Fact]
    public void Verify_detects_problems()
    {
        var fid = Start();
        Stored(fid);
        Stored(fid, eof: true);
        Q.ExecRaw("UPDATE batch SET fetch_seq = 5 WHERE fetch_seq = 1");
        Assert.Contains("FETCH_SEQ_GAP", Q.Verify());
    }

    [Fact]
    public void Verify_detects_acked_ahead_of_fetched_and_multiple_sending()
    {
        Realtime();
        Realtime();
        Q.ExecRaw("UPDATE batch SET status = 'SENDING'");
        Q.ExecRaw("INSERT INTO checkpoint (cursor_key, fetched_ts, acked_ts, updated_at) VALUES ('RACE:2', '20261101000000', '20261102000000', 0)");
        Assert.Equal(["ACKED_AHEAD_OF_FETCHED", "MULTIPLE_SENDING"], Q.Verify());
    }

    [Fact]
    public void Read_only_connection_can_inspect_while_writer_is_open()
    {
        Realtime();
        using var ro = new OutboxQueue(fx.QPath, fx.Clock, readOnly: true);
        Assert.Equal(new Dictionary<string, int> { ["QUEUED"] = 1 }, ro.Stats().ByStatus);
        Assert.Empty(ro.Verify());
    }

    [Fact]
    public void State_and_alerts_roundtrip()
    {
        Assert.Null(Q.GetState("k"));
        Q.SetState("k", "1");
        Q.SetState("k", "2");
        Assert.Equal("2", Q.GetState("k"));
        Q.RaiseAlert("AUTH", new string('x', 300));
        Assert.Equal(200, Q.Alerts()["AUTH"].Length);
        Assert.True(Q.ClearAlert("AUTH"));
        Assert.False(Q.ClearAlert("AUTH"));
    }

    [Fact]
    public void Batch_ToString_does_not_contain_payload()
    {
        var b = Realtime();
        Assert.DoesNotContain("synthetic", b.ToString(), StringComparison.Ordinal);
    }
}
