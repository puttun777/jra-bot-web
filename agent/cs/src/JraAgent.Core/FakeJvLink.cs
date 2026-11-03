using System.Text.Json;
using System.Text.Json.Nodes;

namespace JraAgent.Core;

/// <summary>
/// Fake の台本（旧 fake.py の FakeScript。JSON の形も同じ: レコードは hex 文字列）。
/// <c>HangAfter</c>: n レコード読んだ後の read で止まる。<c>CrashAfter</c>: 同じくプロセスごと終了する。実データは使わない（合成のバイト列）。
/// </summary>
public sealed record FakeScript
{
    public IReadOnlyList<IReadOnlyList<byte[]>> Files { get; init; } = [];

    public string LastFileTimestamp { get; init; } = "20261031200000";

    public int OpenCode { get; init; }

    public int InitCode { get; init; }

    public int CloseCode { get; init; }

    public int? ReadErrorAfter { get; init; }

    public int ReadErrorCode { get; init; } = -203;

    public int DownloadingPolls { get; init; }

    public int? HangAfter { get; init; }

    public bool HangInOpen { get; init; }

    public int? CrashAfter { get; init; }

    public IReadOnlyList<byte[]> RealtimeRecords { get; init; } = [];

    private static int? OptInt(JsonObject o, string k) => o[k] is JsonValue v && v.TryGetValue<int>(out var n) ? n : null;

    public static FakeScript FromJson(string text)
    {
        var o = JsonNode.Parse(text) as JsonObject ?? throw new FormatException("台本は JSON のオブジェクト");
        static byte[] Hex(JsonNode? n) => Convert.FromHexString(n?.GetValue<string>() ?? throw new FormatException("hex"));
        return new FakeScript
        {
            Files = [.. (o["files"] as JsonArray ?? []).Select(f => (IReadOnlyList<byte[]>)[.. (f as JsonArray ?? []).Select(Hex)])],
            RealtimeRecords = [.. (o["realtime_records"] as JsonArray ?? []).Select(Hex)],
            LastFileTimestamp = o["last_file_timestamp"]?.GetValue<string>() ?? "20261031200000",
            OpenCode = OptInt(o, "open_code") ?? 0,
            InitCode = OptInt(o, "init_code") ?? 0,
            CloseCode = OptInt(o, "close_code") ?? 0,
            ReadErrorAfter = OptInt(o, "read_error_after"),
            ReadErrorCode = OptInt(o, "read_error_code") ?? -203,
            DownloadingPolls = OptInt(o, "downloading_polls") ?? 0,
            HangAfter = OptInt(o, "hang_after"),
            HangInOpen = o["hang_in_open"]?.GetValue<bool>() ?? false,
            CrashAfter = OptInt(o, "crash_after"),
        };
    }

    public string ToJson()
    {
        var o = new JsonObject
        {
            ["files"] = new JsonArray([.. Files.Select(f => (JsonNode)new JsonArray([.. f.Select(r => (JsonNode)Convert.ToHexStringLower(r))]))]),
            ["realtime_records"] = new JsonArray([.. RealtimeRecords.Select(r => (JsonNode)Convert.ToHexStringLower(r))]),
            ["last_file_timestamp"] = LastFileTimestamp,
            ["open_code"] = OpenCode,
            ["init_code"] = InitCode,
            ["close_code"] = CloseCode,
            ["read_error_after"] = ReadErrorAfter,
            ["read_error_code"] = ReadErrorCode,
            ["downloading_polls"] = DownloadingPolls,
            ["hang_after"] = HangAfter,
            ["hang_in_open"] = HangInOpen,
            ["crash_after"] = CrashAfter,
        };
        return o.ToJsonString();
    }
}

/// <summary>Fake の JV-Link（Linux の CI と開発用。旧 fake.py の移植）。決めたレコード列を返し、ハング・クラッシュ・エラーを注入できる。</summary>
public sealed class FakeJvLink(FakeScript script) : IJvLink
{
    private readonly Queue<byte[]?> queue = new(); // null = ファイルの切替
    private int read;
    private int downloadingLeft;

    public bool IsOpen { get; private set; }

    public bool Inited { get; private set; }

    public int Opens { get; private set; }

    public int Init(string sid)
    {
        Inited = script.InitCode >= 0;
        return script.InitCode;
    }

    private void Begin()
    {
        if (IsOpen) throw new InvalidOperationException("OPEN_WHILE_OPEN"); // 同時に複数の open を実行しない
        IsOpen = true;
        Opens++;
        read = 0;
        downloadingLeft = script.DownloadingPolls;
    }

    public OpenResult OpenStored(string dataspec, string fromtime, int option)
    {
        Begin();
        if (script.HangInOpen) Thread.Sleep(Timeout.Infinite);
        if (script.OpenCode < 0)
        {
            IsOpen = false;
            return new OpenResult(script.OpenCode);
        }
        queue.Clear();
        for (var i = 0; i < script.Files.Count; i++)
        {
            foreach (var r in script.Files[i]) queue.Enqueue(r);
            if (i < script.Files.Count - 1) queue.Enqueue(null);
        }
        return new OpenResult(script.OpenCode, script.Files.Sum(f => f.Count), script.Files.Count, script.LastFileTimestamp);
    }

    public OpenResult OpenRealtime(string dataspec, string key)
    {
        Begin();
        if (script.OpenCode < 0)
        {
            IsOpen = false;
            return new OpenResult(script.OpenCode);
        }
        queue.Clear();
        foreach (var r in script.RealtimeRecords) queue.Enqueue(r);
        return new OpenResult(script.OpenCode, queue.Count, 0, null);
    }

    public ReadResult Read()
    {
        if (!IsOpen) return new ReadResult(ReadStatus.Error, Code: -203);
        if (downloadingLeft > 0)
        {
            downloadingLeft--;
            return new ReadResult(ReadStatus.Downloading, Code: -3);
        }
        if (script.CrashAfter is { } c && read >= c) Environment.Exit(139); // COM が落ちた場合の模擬（プロセスごと消える）
        if (script.HangAfter is { } h && read >= h) Thread.Sleep(Timeout.Infinite); // COM が戻らない場合の模擬
        if (script.ReadErrorAfter is { } e && read >= e) return new ReadResult(ReadStatus.Error, Code: script.ReadErrorCode);
        if (queue.Count == 0) return new ReadResult(ReadStatus.Eof, Code: 0);
        var data = queue.Dequeue();
        if (data is null) return new ReadResult(ReadStatus.FileSwitch, Code: -1);
        read++;
        return new ReadResult(ReadStatus.Record, data, data.Length, "FAKE.jvd");
    }

    public int Close()
    {
        IsOpen = false;
        queue.Clear();
        return script.CloseCode;
    }
}
