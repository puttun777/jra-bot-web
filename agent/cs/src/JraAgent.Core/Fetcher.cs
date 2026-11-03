using System.Globalization;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace JraAgent.Core;

public enum FetchResult
{
    Completed, // EOF まで読み、全バッチをキューに入れた（取得済み。完了の確認はサーバの cursor）
    StoppedSafe, // モードが変わったため安全な区切りで止めた
    Aborted,
    Skipped, // ゲート・重複・NORMAL でない
    NoData,
}

public sealed record FetchReport(FetchResult Result, string? Reason = null, int Records = 0, int Batches = 0, int? OpenCode = null);

/// <summary>
/// 取得の実行（旧 fetcher.py の移植）。JV-Link ワーカーを通して読み、バッチにしてキューへ入れる。送信はしない（<see cref="Sender"/> の責務）。
/// <list type="bullet">
/// <item>仕様ゲート: 公式仕様で未確認の項目に依存する取得は開始しない（<see cref="SpecRegistry.Require"/>）。</item>
/// <item>同じ種別の取得が実行中（READING のセッションがある）なら、新しく始めない（重複起動の防止）。</item>
/// <item>取得の途中で NORMAL でなくなったら、現在の安全な区切り（ファイル切替/バッチ境界）で止める。読み込んだ分はキューに残す。取得は完了にしない。</item>
/// <item>ワーカーのタイムアウト・クラッシュ・キュー満杯・不正なレコードは、セッションを中止（acked_ts・fetched_ts は動かさない）。</item>
/// <item>ログに生データを出さない（件数・バイト長・コードだけ）。</item>
/// </list>
/// </summary>
public sealed partial class Fetcher(
    string agentId,
    OutboxQueue queue,
    IWorker worker,
    IClock clock,
    SpecRegistry spec,
    QueueConfig qcfg,
    Func<bool> mayFetch,
    string sid = "UNKNOWN")
{
    /// <summary>acked_ts から重なりを持たせて読み直す（JVLINK_DESIGN §5.2。重複はサーバが吸収）。</summary>
    public static readonly TimeSpan FromtimeMargin = TimeSpan.FromHours(6);

    private bool inited;

    [GeneratedRegex("^[A-Z0-9]{2}$")]
    private static partial Regex RecIdRe();

    [GeneratedRegex("^[0-9]{14}$")]
    private static partial Regex Ts14Re();

    [GeneratedRegex("^[0-9]{16}$")]
    private static partial Regex Range16Re();

    /// <summary>直近の JV-Link の戻り値（診断用。生データは含めない）。</summary>
    public Dictionary<string, int> LastJv { get; } = new(StringComparer.Ordinal);

    /// <summary>サーバの IngestCursors.dataType と同じ。</summary>
    public static string CursorKey(string dataspec, int? option) => option is { } o ? $"{dataspec}:{o.ToString(CultureInfo.InvariantCulture)}" : dataspec;

    /// <summary>次回の fromtime。acked_ts があれば acked_ts - margin、無ければ計画の key（14 桁）または全期間を表す 0 埋め（参照元実装の使用実態）。</summary>
    public static string FromtimeFrom(string? ackedTs, string? seed)
    {
        if (!string.IsNullOrEmpty(ackedTs))
        {
            var t = DateTime.ParseExact(ackedTs, "yyyyMMddHHmmss", CultureInfo.InvariantCulture) - FromtimeMargin;
            return t.ToString("yyyyMMddHHmmss", CultureInfo.InvariantCulture);
        }
        return seed is not null && Ts14Re().IsMatch(seed) ? seed : "00000000000000";
    }

    /// <summary>
    /// YSCH（開催日程）の対象範囲。計画の key が 16 桁（yyyyMMddyyyyMMdd = targetFrom + targetTo）のときだけ申告する。
    /// 申告が無い取得は、サーバが『予定なしを確認した』事実にしない（fail closed。CONTRACTS §3.4）。
    /// </summary>
    public static (string? From, string? To) TargetRange(PlanItem item) =>
        item.Dataspec == "YSCH" && item.Key is { } k && Range16Re().IsMatch(k) ? (k[..8], k[8..]) : (null, null);

    // ------------------------------------------------------------ 共通
    private static int Code(JsonObject o, string key = "code") =>
        o[key] is JsonValue v && v.TryGetValue<int>(out var n) ? n : throw new WorkerCrashedException("BAD_PROTOCOL");

    private void Init()
    {
        if (inited) return;
        var code = Code(worker.Call("init", new JsonObject { ["sid"] = sid }));
        LastJv["init"] = code;
        if (code < 0) throw new WorkerException($"INIT_{code}");
        inited = true;
    }

    /// <summary>
    /// 失敗した tick の後始末: ワーカーを使った後（JVInit 済み）だけ、ワーカー側の open を閉じる（閉じられなければ次回は JVInit からやり直す）。
    /// 使っていなければ何もしない（close を呼ぶとワーカーが起動され、仕様ゲートで止めている間に COM が生成されてしまう: 再レビュー 中）。
    /// </summary>
    public void ResetAfterFailure()
    {
        if (inited) CloseQuietly();
    }

    private void CloseQuietly()
    {
        try
        {
            LastJv["close"] = Code(worker.Call("close"));
        }
        catch (WorkerException)
        {
            inited = false; // ワーカーが作り直されたら JVInit からやり直す
        }
    }

    private JsonObject ReadBatch() => worker.Call("read_batch", new JsonObject
    {
        ["max_records"] = qcfg.MaxBatchRecords,
        ["max_bytes"] = qcfg.MaxBatchRawBytes,
        ["max_polls"] = 10,
        ["poll_sleep"] = 0.5,
    });

    private static string Status(JsonObject r) => r["status"] is JsonValue v && v.TryGetValue<string>(out var s) ? s : throw new WorkerCrashedException("BAD_PROTOCOL");

    private static IReadOnlyList<byte[]> Records(JsonObject r)
    {
        try
        {
            return WorkerSupervisor.DecodeRecords(r);
        }
        catch (Exception e) when (e is FormatException or InvalidOperationException)
        {
            throw new WorkerCrashedException("BAD_PROTOCOL");
        }
    }

    private static string? RecId(byte[] data)
    {
        if (data.Length < 2) return null;
        var rid = System.Text.Encoding.ASCII.GetString(data, 0, 2);
        return RecIdRe().IsMatch(rid) ? rid : null;
    }

    private FetchReport? Gate(string[] items)
    {
        try
        {
            spec.Require(items);
        }
        catch (SpecUnconfirmedException e)
        {
            queue.RaiseAlert("SPEC_UNCONFIRMED", "公式仕様未確認の項目があるため、実取得を開始しない");
            return new FetchReport(FetchResult.Skipped, "SPEC_UNCONFIRMED:" + string.Join(",", e.Items.Take(3)));
        }
        return mayFetch() ? null : new FetchReport(FetchResult.Skipped, "MODE_NOT_NORMAL");
    }

    // ------------------------------------------------------------ 蓄積系
    public FetchReport FetchStored(PlanItem item, long? backfillId = null)
    {
        if (Gate(SpecRegistry.FetchStoredItems) is { } skip) return skip;
        var key = CursorKey(item.Dataspec, item.Option);
        if (queue.HasOpenSession(key)) return new FetchReport(FetchResult.Skipped, "ALREADY_FETCHING");
        var option = item.Option ?? throw new ArgumentException("蓄積系の計画には option が必要");
        var origin = backfillId is not null ? "BACKFILL" : "LIVE";
        var fromtime = FromtimeFrom(origin == "LIVE" ? queue.GetCheckpoint(key).AckedTs : null, item.Key);
        string? fid = null;
        try
        {
            Init();
            var o = worker.Call("open_stored", new JsonObject { ["dataspec"] = item.Dataspec, ["fromtime"] = fromtime, ["option"] = option });
            var openCode = Code(o);
            LastJv["open"] = openCode;
            LastJv["read_count"] = o["read_count"] is JsonValue rc && rc.TryGetValue<int>(out var n) ? n : 0;
            if (openCode < 0) return new FetchReport(FetchResult.Aborted, $"OPEN_{openCode}", OpenCode: openCode);
            var lastTs = o["last_file_timestamp"] is JsonValue lv && lv.TryGetValue<string>(out var lts) ? lts : null;
            fid = Envelope.Uuid7(clock.Now.ToUnixTimeMilliseconds());
            var (tFrom, tTo) = TargetRange(item);
            queue.BeginSession(fid, key, item.Dataspec, option, fromtime, lastTs, tFrom, tTo, origin, backfillId);
            return ReadLoop(item, fid, fromtime, lastTs, openCode);
        }
        catch (WorkerException e)
        {
            inited = false;
            if (fid is not null) queue.AbortSession(fid, $"WORKER_{e.Kind}");
            return new FetchReport(FetchResult.Aborted, $"WORKER_{e.Kind}");
        }
        catch (QueueFullException e)
        {
            if (fid is not null) queue.AbortSession(fid, $"QUEUE_{e.Message}");
            CloseQuietly();
            queue.RaiseAlert("QUEUE_FULL", $"キューに入れられない（{e.Message}）。取得を停止");
            return new FetchReport(FetchResult.Aborted, $"QUEUE_{e.Message}");
        }
    }

    private FetchReport ReadLoop(PlanItem item, string fid, string fromtime, string? lastTs, int openCode)
    {
        var pending = new List<RawRecord>();
        long pendingBytes = 0;
        int total = 0, batches = 0;

        void Flush(bool eof)
        {
            if (pending.Count == 0 && !eof) return;
            var s = queue.Session(fid) ?? throw new QueueException("取得セッションが消えた");
            var bid = Envelope.Uuid7(clock.Now.ToUnixTimeMilliseconds());
            var payload = Envelope.Build(
                agentId, bid, item.Dataspec, clock.Now, pending, eof, item.Option, fromtime, lastTs,
                new FetchInfo(fid, checked((int)s.NextSeq), s.TargetFrom, s.TargetTo, s.Origin, s.BackfillId));
            queue.Enqueue(bid, "S", item.Dataspec, payload, pending.Count, eof, fid, lastFileTimestamp: lastTs);
            batches++;
            pending = [];
            pendingBytes = 0;
        }

        while (true)
        {
            var r = ReadBatch();
            LastJv["read"] = r["code"] is JsonValue cv && cv.TryGetValue<int>(out var rc) ? rc : 0;
            var status = Status(r);
            foreach (var data in Records(r))
            {
                if (RecId(data) is not { } rid)
                {
                    queue.AbortSession(fid, "BAD_RECORD_ID"); // 勝手に捨てない。取得を中止して知らせる
                    queue.RaiseAlert("BAD_RECORD", "レコード種別 ID が不正。取得を中止した（件数・内容は記録しない）");
                    CloseQuietly();
                    return new FetchReport(FetchResult.Aborted, "BAD_RECORD_ID", total, batches, openCode);
                }
                if (pending.Count >= qcfg.MaxBatchRecords || pendingBytes + data.Length > qcfg.MaxBatchRawBytes) Flush(false);
                pending.Add(new RawRecord(rid, data));
                pendingBytes += data.Length;
                total++;
            }
            switch (status)
            {
                case "ERROR":
                    queue.AbortSession(fid, $"READ_{LastJv["read"]}");
                    CloseQuietly();
                    return new FetchReport(FetchResult.Aborted, $"READ_{LastJv["read"]}", total, batches, openCode);
                case "EOF":
                    Flush(true); // EOF のバッチ: ここで初めて fetched/queued が動く
                    CloseQuietly();
                    return new FetchReport(FetchResult.Completed, null, total, batches, openCode);
                case "FILE_SWITCH" or "MORE" or "DOWNLOADING" when !mayFetch():
                    Flush(false);
                    queue.AbortSession(fid, "MODE_CHANGED", "INTERRUPTED"); // 現在の安全な区切りで止める。完了にしない
                    CloseQuietly();
                    return new FetchReport(FetchResult.StoppedSafe, "MODE_CHANGED", total, batches, openCode);
                case "FILE_SWITCH":
                    Flush(false);
                    break;
                case "MORE" or "DOWNLOADING":
                    break;
                default:
                    throw new WorkerCrashedException("BAD_PROTOCOL"); // 想定外の状態を EOF と取り違えない
            }
        }
    }

    // ------------------------------------------------------------ 速報系
    public FetchReport FetchRealtime(PlanItem item)
    {
        if (Gate(SpecRegistry.FetchRealtimeItems) is { } skip) return skip;
        var key = item.Key ?? throw new ArgumentException("速報の計画には key が必要");
        var observed = clock.Now; // 観測時刻 = JVRTOpen を呼んだ時刻（UTC）
        int total = 0, batches = 0;
        try
        {
            Init();
            var o = worker.Call("open_realtime", new JsonObject { ["dataspec"] = item.Dataspec, ["key"] = key });
            var openCode = Code(o);
            LastJv["open"] = openCode;
            if (openCode < 0) return new FetchReport(FetchResult.Aborted, $"OPEN_{openCode}", OpenCode: openCode);
            while (true)
            {
                var r = ReadBatch();
                var status = Status(r);
                var recs = new List<RawRecord>();
                foreach (var data in Records(r))
                {
                    if (RecId(data) is not { } rid)
                    {
                        CloseQuietly();
                        queue.RaiseAlert("BAD_RECORD", "レコード種別 ID が不正。速報の取得を中止した");
                        return new FetchReport(FetchResult.Aborted, "BAD_RECORD_ID", total, batches);
                    }
                    recs.Add(new RawRecord(rid, data));
                }
                if (status is not ("ERROR" or "EOF" or "MORE" or "FILE_SWITCH" or "DOWNLOADING")) throw new WorkerCrashedException("BAD_PROTOCOL");
                if (recs.Count > 0)
                {
                    var bid = Envelope.Uuid7(clock.Now.ToUnixTimeMilliseconds());
                    var payload = Envelope.Build(agentId, bid, item.Dataspec, observed, recs, status == "EOF");
                    queue.Enqueue(bid, Envelope.BatchClassOf(item.Dataspec), item.Dataspec, payload, recs.Count, status == "EOF", observedAt: observed.ToUnixTimeMilliseconds() / 1000.0);
                    total += recs.Count;
                    batches++;
                }
                if (status == "ERROR")
                {
                    CloseQuietly();
                    return new FetchReport(FetchResult.Aborted, $"READ_{(r["code"] is JsonValue cv && cv.TryGetValue<int>(out var c) ? c : 0)}", total, batches);
                }
                if (status == "EOF")
                {
                    CloseQuietly();
                    return new FetchReport(total > 0 ? FetchResult.Completed : FetchResult.NoData, null, total, batches);
                }
                if (!mayFetch())
                {
                    CloseQuietly();
                    return new FetchReport(FetchResult.StoppedSafe, "MODE_CHANGED", total, batches);
                }
            }
        }
        catch (WorkerException e)
        {
            inited = false;
            return new FetchReport(FetchResult.Aborted, $"WORKER_{e.Kind}", total, batches);
        }
        catch (QueueFullException e)
        {
            CloseQuietly();
            queue.RaiseAlert("QUEUE_FULL", $"キューに入れられない（{e.Message}）。取得を停止");
            return new FetchReport(FetchResult.Aborted, $"QUEUE_{e.Message}", total, batches);
        }
    }
}
