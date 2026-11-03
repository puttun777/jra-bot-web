using System.Diagnostics;
using System.Globalization;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using JraAgent.Core;
using JraAgent.Windows;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

namespace JraAgent.Cli;

/// <summary>PoC の集計（長さ・コード・時間）。内容（バイト列そのもの）は受け取っても保持しない（旧 poc_common.Aggregate）。</summary>
internal sealed class Aggregate
{
    private readonly List<int> lengths = [];
    private readonly SortedDictionary<string, int> byCode = new(StringComparer.Ordinal);
    private readonly SortedDictionary<string, int> byRecordId = new(StringComparer.Ordinal); // 先頭 2 文字の種別 ID（RA・SE など）だけ
    private readonly List<double> durationsMs = [];

    public int Count => lengths.Count;

    public void AddRecord(byte[] data)
    {
        lengths.Add(data.Length);
        var rid = data.Length >= 2 ? Encoding.ASCII.GetString(data, 0, 2) : "";
        byRecordId[rid] = byRecordId.GetValueOrDefault(rid) + 1;
    }

    public void AddCode(string code) => byCode[code] = byCode.GetValueOrDefault(code) + 1;

    public void AddDuration(TimeSpan t) => durationsMs.Add(t.TotalMilliseconds);

    public JsonObject Summary()
    {
        static JsonObject Map(SortedDictionary<string, int> d)
        {
            var o = new JsonObject();
            foreach (var (k, v) in d) o[k] = v;
            return o;
        }
        var output = new JsonObject { ["records"] = Count, ["total_bytes"] = lengths.Sum(x => (long)x), ["codes"] = Map(byCode), ["record_ids"] = Map(byRecordId) };
        if (lengths.Count > 0)
        {
            output["length"] = new JsonObject { ["min"] = lengths.Min(), ["max"] = lengths.Max(), ["mean"] = Math.Round(lengths.Average(), 1) };
            var buckets = new SortedDictionary<string, int>(StringComparer.Ordinal);
            foreach (var n in lengths)
            {
                var b = new long[] { 64, 256, 1024, 4096, 16384, 65536, 1_000_000_000 }.First(x => n <= x);
                var key = $"<= {b}";
                buckets[key] = buckets.GetValueOrDefault(key) + 1;
            }
            output["length_buckets"] = Map(buckets);
        }
        if (durationsMs.Count > 0)
        {
            var s = durationsMs.Order().ToList();
            output["duration_ms"] = new JsonObject { ["count"] = s.Count, ["p50"] = Math.Round(s[s.Count / 2], 1), ["max"] = Math.Round(s[^1], 1), ["total"] = Math.Round(s.Sum(), 1) };
        }
        return output;
    }
}

/// <param name="Status">PASS / FAIL / NOT_RUN / MOCK_PASS（Mock の合格 = 実機の合格ではない）。</param>
internal sealed record GateResult(string Gate, string Status, string Reason = "", JsonObject? Metrics = null)
{
    public JsonObject ToJson() => new() { ["gate"] = Gate, ["status"] = Status, ["reason"] = Reason, ["metrics"] = Metrics ?? [] };
}

/// <summary>
/// Windows 実機 PoC（T3.0: G-AGENT-1〜6。旧 agent/poc の移植。手順は agent/POC.md）。
/// <b>集計だけ</b>を出力し、レコードの内容・利用キー・トークンは保存・表示しない。PoC はネットワークに何も送らない（送信先はローカルの Mock だけ）。
/// <list type="bullet">
/// <item><c>poc mock</c>: Mock（Fake の JV-Link を子プロセスで）による G-AGENT-2〜5 の事前検証（どの OS でも動く）。</item>
/// <item><c>poc g1|g2|g3-realtime --allow-real-jvlink</c>: 実 JV-Link（Windows だけ。呼ぶ API の一覧を表示し、--yes が無ければ確認を求める）。</item>
/// <item><c>poc sleep --duration 1800</c>: スリープ/休止からの復帰（Mock ビーコン。JV-Link・ingest を使わない）。</item>
/// </list>
/// </summary>
internal static class Poc
{
    private static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    private static readonly Dictionary<string, string[]> Calls = new(StringComparer.Ordinal)
    {
        ["g1"] = ["COM 生成 (ProgID)", "JVInit(sid)"],
        ["g2"] = ["COM 生成", "JVInit(sid)", "JVOpen(dataspec, fromtime, option, ref 0, ref 0, ref '')", "JVRead(ref '', ref 110000, ref '') を最大 N 回", "JVClose()"],
        ["g3-realtime"] = ["COM 生成", "JVInit(sid)", "JVRTOpen(dataspec, key)", "JVRead(ref '', ref 110000, ref '') を EOF まで", "JVClose()"],
    };

    public static int Run(string[] argv)
    {
        if (argv.Length == 0) throw new UsageException("poc mock|g1|g2|g3-realtime|sleep");
        var rest = argv[1..];
        return argv[0] switch
        {
            "mock" => Mock(Args.Parse(rest, new HashSet<string> { "--out" }, new HashSet<string>())),
            "g1" or "g2" or "g3-realtime" => Real(argv[0], Args.Parse(rest, new HashSet<string> { "--out", "--sid", "--dataspec", "--option", "--fromtime", "--key", "--max-records" }, new HashSet<string> { "--allow-real-jvlink", "--yes" })),
            "sleep" => Sleep(Args.Parse(rest, new HashSet<string> { "--out", "--duration" }, new HashSet<string>())),
            "crash-send" when rest.Length == 2 => CrashSend(rest[0], rest[1]),
            _ => throw new UsageException("poc mock|g1|g2|g3-realtime|sleep"),
        };
    }

    private static JsonObject Report(string? outPath, string mode, IEnumerable<GateResult> results, JsonObject? extra = null)
    {
        var doc = new JsonObject
        {
            ["poc"] = "jra-agent T3.0",
            ["mode"] = mode,
            ["environment"] = Diagnostics.EnvironmentInfo(),
            ["gates"] = new JsonArray([.. results.Select(r => (JsonNode)r.ToJson())]),
            ["note"] = "集計のみ。レコードの内容・利用キー・トークンを含まない。Mock の合格は実機の合格ではない。",
        };
        foreach (var (k, v) in extra ?? []) doc[k] = v?.DeepClone();
        var text = doc.ToJsonString(Pretty);
        if (outPath is not null)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(outPath)) ?? ".");
            File.WriteAllText(outPath, text + "\n", new UTF8Encoding(false));
        }
        Console.Out.WriteLine(text);
        return doc;
    }

    // ------------------------------------------------------------------ Mock
    /// <summary>全バイト値を含む合成レコード（cp932 の欠損・変換の検査用）。実データではない。</summary>
    private static readonly byte[][] Synthetic = [.. Enumerable.Range(0, 20).Select(_ => (byte[])[0x52, 0x41, .. Enumerable.Repeat(Enumerable.Range(1, 255).Select(b => (byte)b), 3).SelectMany(x => x), 0x93, 0xfa, 0x96, 0x7b, 0x8c, 0xea])];

    private static WorkerSupervisor FakeWorker(FakeScript script, string tmp, double callTimeout = 5.0)
    {
        var p = Path.Combine(tmp, "script.json");
        File.WriteAllText(p, script.ToJson());
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("実行ファイルの場所が分からない");
        return new WorkerSupervisor([exe, "worker", "--backend", "fake", "--script", p], new WorkerConfig(CallTimeoutSeconds: callTimeout, MaxRestarts: 10), new SystemClock());
    }

    private static JsonObject Open(string dataspec = "RACE", int option = 1) => new() { ["dataspec"] = dataspec, ["fromtime"] = "00000000000000", ["option"] = option };

    private static GateResult G2Bytes(string tmp)
    {
        var sup = FakeWorker(new FakeScript { Files = [Synthetic[..10], Synthetic[10..]] }, tmp);
        var agg = new Aggregate();
        var got = new List<byte[]>();
        try
        {
            sup.Call("open_stored", Open());
            while (true)
            {
                var sw = Stopwatch.StartNew();
                var r = sup.Call("read_batch", new JsonObject { ["max_records"] = 7, ["max_bytes"] = 100_000, ["max_polls"] = 3, ["poll_sleep"] = 0.0 });
                agg.AddDuration(sw.Elapsed);
                var status = r["status"]?.GetValue<string>() ?? "";
                agg.AddCode(status);
                got.AddRange(WorkerSupervisor.DecodeRecords(r));
                if (status == "EOF") break;
            }
            foreach (var g in got) agg.AddRecord(g);
        }
        finally
        {
            sup.Stop();
        }
        var ok = got.Count == Synthetic.Length && got.Zip(Synthetic).All(x => x.First.AsSpan().SequenceEqual(x.Second));
        var m = agg.Summary();
        m["byte_exact"] = ok;
        return new GateResult("G-AGENT-2", ok ? "MOCK_PASS" : "FAIL", ok ? "" : "バイト列が一致しない", m);
    }

    private static GateResult G3Kinds(string tmp)
    {
        var agg = new Aggregate();
        var sup = FakeWorker(new FakeScript { Files = [Synthetic[..3]], RealtimeRecords = Synthetic[3..5] }, tmp);
        IReadOnlyList<byte[]> stored, rt;
        try
        {
            sup.Call("open_stored", Open(option: 2));
            stored = WorkerSupervisor.DecodeRecords(sup.Call("read_batch", new JsonObject { ["max_records"] = 99, ["max_bytes"] = 1_000_000 }));
            sup.Call("close");
            sup.Call("open_realtime", new JsonObject { ["dataspec"] = "0B12", ["key"] = "20261101" });
            rt = WorkerSupervisor.DecodeRecords(sup.Call("read_batch", new JsonObject { ["max_records"] = 99, ["max_bytes"] = 1_000_000 }));
            foreach (var d in stored.Concat(rt)) agg.AddRecord(d);
        }
        finally
        {
            sup.Stop();
        }
        var ok = stored.Count == 3 && rt.Count == 2;
        var m = agg.Summary();
        m["stored"] = stored.Count;
        m["realtime"] = rt.Count;
        return new GateResult("G-AGENT-3", ok ? "MOCK_PASS" : "FAIL", "", m);
    }

    private static GateResult G4Hang(string tmp)
    {
        var sup = FakeWorker(new FakeScript { Files = [Synthetic], HangAfter = 3 }, tmp, 1.5);
        bool hung = false, recovered;
        TimeSpan detect, recover;
        try
        {
            sup.Call("open_stored", Open());
            var sw = Stopwatch.StartNew();
            try
            {
                sup.Call("read_batch", new JsonObject { ["max_records"] = 99, ["max_bytes"] = 1_000_000 });
            }
            catch (WorkerTimeoutException)
            {
                hung = true;
            }
            detect = sw.Elapsed;
            sw.Restart();
            recovered = sup.Call("open_stored", Open())["code"]?.GetValue<int>() == 0;
            recover = sw.Elapsed;
        }
        finally
        {
            sup.Stop();
        }
        var ok = hung && recovered;
        return new GateResult("G-AGENT-4", ok ? "MOCK_PASS" : "FAIL", "", new JsonObject
        {
            ["hang_detected"] = hung,
            ["detect_seconds"] = Math.Round(detect.TotalSeconds, 2),
            ["recovered"] = recovered,
            ["recover_seconds"] = Math.Round(recover.TotalSeconds, 2),
            ["timeouts"] = sup.Timeouts,
        });
    }

    /// <summary>127.0.0.1 の空いているポートで待ち受ける Mock の ingest（POST の本文の batchId を記録し、ACK を返す）。</summary>
    private sealed class MockIngest : IDisposable
    {
        private readonly HttpListener listener = new();
        private readonly Thread thread;

        public MockIngest()
        {
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            Port = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            listener.Prefixes.Add($"http://127.0.0.1:{Port}/");
            listener.Start();
            thread = new Thread(Loop) { IsBackground = true };
            thread.Start();
        }

        public int Port { get; }

        public HashSet<string> Seen { get; } = [];

        private void Loop()
        {
            while (listener.IsListening)
            {
                HttpListenerContext ctx;
                try
                {
                    ctx = listener.GetContext();
                }
                catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or InvalidOperationException)
                {
                    return;
                }
                using var ms = new MemoryStream();
                ctx.Request.InputStream.CopyTo(ms);
                var body = ms.ToArray();
                if (ctx.Request.Headers["Content-Encoding"] == "gzip")
                {
                    using var gz = new System.IO.Compression.GZipStream(new MemoryStream(body), System.IO.Compression.CompressionMode.Decompress);
                    using var o = new MemoryStream();
                    gz.CopyTo(o);
                    body = o.ToArray();
                }
                var bid = JsonNode.Parse(body)?["batchId"]?.GetValue<string>() ?? "";
                bool dup;
                lock (Seen) dup = !Seen.Add(bid);
                var output = Encoding.UTF8.GetBytes(new JsonObject
                {
                    ["accepted"] = dup ? 0 : 1, ["duplicates"] = dup ? 1 : 0, ["quarantined"] = 0, ["unsupported"] = 0, ["duplicate"] = dup,
                    ["ackTs"] = "t", ["serverTime"] = "t", ["fetch"] = null, ["cursor"] = null,
                }.ToJsonString());
                ctx.Response.ContentType = "application/json";
                ctx.Response.ContentLength64 = output.Length;
                ctx.Response.OutputStream.Write(output);
                ctx.Response.Close();
            }
        }

        public void Dispose()
        {
            listener.Stop();
            listener.Close();
        }
    }

    private static TransportResponse Post(string url, byte[] payload)
    {
        using var t = new HttpTransport();
        return t.Request("POST", url, new Dictionary<string, string> { ["Content-Type"] = "application/json" }, payload, 10);
    }

    /// <summary>G-AGENT-5 の子プロセス: 1 件 claim して送り、ACK を記録する前に強制終了する（送信は届いたが ACK を記録していない状態を作る）。</summary>
    private static int CrashSend(string db, string url)
    {
        var q = new OutboxQueue(db, new SystemClock(), new QueueConfig(MinFreeBytes: 0));
        var b = q.ClaimNext() ?? throw new InvalidOperationException("送るバッチが無い");
        Post(url, b.Payload);
        Environment.Exit(9);
        return 9;
    }

    /// <summary>SQLite WAL キュー: 送信中に強制終了 → 再起動 → 同じ batchId で再送され、重複登録されない。</summary>
    private static GateResult G5Queue(string tmp)
    {
        using var srv = new MockIngest();
        var url = $"http://127.0.0.1:{srv.Port}/";
        var db = Path.Combine(tmp, "poc-queue.sqlite3");
        var clock = new SystemClock();
        using (var q = new OutboxQueue(db, clock, new QueueConfig(MinFreeBytes: 0)))
        {
            for (var i = 0; i < 5; i++)
            {
                var bid = Envelope.Uuid7();
                var payload = Envelope.Build("ag_poc", bid, "0B12", clock.Now, [new RawRecord("O1", [0x4f, 0x31, .. Enumerable.Repeat((byte)(i + 1), 20)])], true);
                q.Enqueue(bid, "R", "0B12", payload, 1, true);
            }
        }
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("実行ファイルの場所が分からない");
        using (var child = Process.Start(new ProcessStartInfo(exe, ["poc", "crash-send", db, url]) { UseShellExecute = false, RedirectStandardError = true }) ?? throw new InvalidOperationException("子プロセスを起動できない"))
        {
            child.StandardError.ReadToEnd();
            child.WaitForExit(60_000);
        }
        using var q2 = new OutboxQueue(db, clock, new QueueConfig(MinFreeBytes: 0));
        var rec = q2.Recover();
        var acked = 0;
        while (q2.ClaimNext() is { } b)
        {
            using var r = JsonDocument.Parse(Post(url, b.Payload).Body);
            q2.MarkAcked(b.BatchId, new AckInfo(r.RootElement.GetProperty("accepted").GetInt32(), Duplicate: r.RootElement.GetProperty("duplicate").GetBoolean()));
            acked++;
        }
        var problems = q2.Verify();
        var ok = rec.SendingRequeued == 1 && acked == 5 && srv.Seen.Count == 5 && problems.Count == 0;
        return new GateResult("G-AGENT-5", ok ? "MOCK_PASS" : "FAIL", "", new JsonObject
        {
            ["requeued_after_crash"] = rec.SendingRequeued,
            ["acked"] = acked,
            ["unique_batches_on_server"] = srv.Seen.Count,
            ["problems"] = new JsonArray([.. problems.Select(p => (JsonNode)p)]),
            ["journal_mode"] = "wal", // OutboxQueue は WAL にできなければ開けない
        });
    }

    private static int Mock(Args a)
    {
        var results = new List<GateResult> { new("G-AGENT-1", "NOT_RUN", "COM の生成は実機でだけ確認できる（poc g1）") };
        var tmp = Path.Combine(Path.GetTempPath(), "jra-poc-" + Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(tmp);
        try
        {
            foreach (var (name, fn) in new (string, Func<string, GateResult>)[] { ("G-AGENT-2", G2Bytes), ("G-AGENT-3", G3Kinds), ("G-AGENT-4", G4Hang), ("G-AGENT-5", G5Queue) })
            {
                try
                {
                    results.Add(fn(tmp));
                }
                catch (Exception e) when (e is AgentException or IOException or JsonException or InvalidOperationException or KeyNotFoundException or HttpListenerException)
                {
                    results.Add(new GateResult(name, "FAIL", $"例外 {e.GetType().Name}"));
                }
            }
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            try
            {
                Directory.Delete(tmp, recursive: true);
            }
            catch (IOException)
            {
                // 一時ディレクトリの後始末の失敗は結果に影響しない
            }
        }
        results.Add(new GateResult("G-AGENT-6", "NOT_RUN", "タスクスケジューラ/スリープ復帰は実機だけ（poc sleep と POC.md）"));
        Report(a.Get("--out"), "mock", results);
        return results.All(r => r.Status is "MOCK_PASS" or "NOT_RUN") ? 0 : 1;
    }

    // ------------------------------------------------------------------ 実機
    private static bool Confirm(string gate, Args a)
    {
        if (!a.Has("--allow-real-jvlink"))
        {
            Console.Error.WriteLine("実 JV-Link を呼ぶには --allow-real-jvlink が必要です。何も実行しません。");
            return false;
        }
        Console.Error.WriteLine("これから呼ぶ JV-Link の API:\n" + string.Concat(Calls[gate].Select(c => $"  - {c}\n")));
        if (a.Has("--yes")) return true;
        Console.Error.Write("実行しますか? [y/N] ");
        return string.Equals(Console.In.ReadLine()?.Trim(), "y", StringComparison.OrdinalIgnoreCase);
    }

    private static readonly Encoding Cp932Strict = CreateCp932();

    private static Encoding CreateCp932()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        return Encoding.GetEncoding(932, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
    }

    private static bool Cp932Roundtrip(byte[] data)
    {
        try
        {
            return Cp932Strict.GetBytes(Cp932Strict.GetString(data)).AsSpan().SequenceEqual(data);
        }
        catch (Exception e) when (e is DecoderFallbackException or EncoderFallbackException)
        {
            return false;
        }
    }

    private static int Real(string gate, Args a)
    {
        if (!OperatingSystem.IsWindows())
        {
            Console.Error.WriteLine("実機 PoC は Windows でだけ動きます（Mock は poc mock）。");
            return 2;
        }
        if (!Confirm(gate, a)) return 2;
        GateResult res = GateResult(gate, a);
        Report(a.Get("--out"), "real", [res], new JsonObject { ["codes_note"] = ReadCodes.UnconfirmedNotice });
        return res.Status == "PASS" ? 0 : 1;
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static GateResult GateResult(string gate, Args a)
    {
        GateResult? res = null;
        // COM は STA のスレッドで生成・使用・解放する（ワーカーと同じ）
        var t = new Thread(() => res = RealGate(gate, a));
        t.SetApartmentState(ApartmentState.STA);
        t.Start();
        t.Join();
        return res ?? new GateResult(gate, "FAIL", "結果が無い");
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static GateResult RealGate(string gate, Args a)
    {
        var sid = a.Get("--sid") ?? "UNKNOWN"; // JVInit に渡すソフトウェア ID（利用キーではない）
        var dataspec = a.Get("--dataspec") ?? "RACE";
        var option = int.Parse(a.Get("--option") ?? "1", CultureInfo.InvariantCulture);
        var maxRecords = int.Parse(a.Get("--max-records") ?? "200", CultureInfo.InvariantCulture);
        ComJvLink com;
        try
        {
            com = ComJvLink.Create();
        }
        catch (WorkerOpException e)
        {
            return new GateResult("G-AGENT-1", "FAIL", $"COM を生成できない: {e.Code}", new JsonObject { ["hint"] = "64bit の JV-Link が入っているか、COM 登録を確認する（diagnose の com）" });
        }
        using (com)
        {
            try
            {
                return gate switch
                {
                    "g1" => G1(com, sid),
                    "g2" => G2(com, sid, dataspec, a.Get("--fromtime") ?? "00000000000000", option, maxRecords),
                    _ => G3(com, sid, a.Get("--dataspec") ?? "0B12", a.Get("--key") ?? "", maxRecords),
                };
            }
            catch (Exception e) when (e is WorkerOpException or COMException or System.Reflection.TargetInvocationException or MissingMethodException)
            {
                return new GateResult(gate switch { "g1" => "G-AGENT-1", "g2" => "G-AGENT-2", _ => "G-AGENT-3" }, "FAIL", $"例外 {e.GetType().Name}{(e is WorkerOpException w ? ":" + w.Code : "")}");
            }
        }
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static GateResult G1(ComJvLink com, string sid)
    {
        var code = com.Init(sid);
        return new GateResult("G-AGENT-1", code == 0 ? "PASS" : "FAIL", code == 0 ? "" : "JVInit が 0 以外", new JsonObject { ["jvinit_code"] = code });
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static GateResult G2(ComJvLink com, string sid, string dataspec, string fromtime, int option, int maxRecords)
    {
        var agg = new Aggregate();
        int ok = 0, ng = 0;
        var init = com.Init(sid);
        var swOpen = Stopwatch.StartNew();
        var o = com.OpenStored(dataspec, fromtime, option);
        var openSeconds = swOpen.Elapsed.TotalSeconds;
        agg.AddCode($"open:{o.Code}");
        if (!o.Ok)
        {
            var mf = agg.Summary();
            mf["jvinit"] = init;
            mf["open_code"] = o.Code;
            return new GateResult("G-AGENT-2", "FAIL", "JVOpen が失敗", mf);
        }
        var reads = 0;
        var sw = Stopwatch.StartNew();
        while (reads < maxRecords)
        {
            var t = Stopwatch.StartNew();
            var r = com.Read();
            agg.AddDuration(t.Elapsed);
            agg.AddCode($"read:{WireNames.Of(r.Status)}:{(r.Status != ReadStatus.Record ? r.Code.ToString(CultureInfo.InvariantCulture) : "n")}");
            if (r.Status == ReadStatus.Record && r.Data is { } d)
            {
                reads++;
                agg.AddRecord(d);
                if (Cp932Roundtrip(d)) ok++;
                else ng++;
            }
            else if (r.Status == ReadStatus.Downloading)
            {
                Thread.Sleep(500);
            }
            else if (r.Status is ReadStatus.Eof or ReadStatus.Error)
            {
                break;
            }
        }
        com.Close();
        var pass = ng == 0 && agg.Count > 0;
        var m = agg.Summary();
        m["jvinit"] = init;
        m["open_code"] = o.Code;
        m["read_count_reported"] = o.ReadCount;
        m["open_seconds"] = Math.Round(openSeconds, 2);
        m["elapsed"] = Math.Round(sw.Elapsed.TotalSeconds, 2);
        m["roundtrip_ok"] = ok;
        m["roundtrip_ng"] = ng;
        return new GateResult("G-AGENT-2", pass ? "PASS" : "FAIL", pass ? "" : "レコードが 0 件、または cp932 の往復が一致しない", m);
    }

    [System.Runtime.Versioning.SupportedOSPlatform("windows")]
    private static GateResult G3(ComJvLink com, string sid, string dataspec, string key, int maxRecords)
    {
        var agg = new Aggregate();
        com.Init(sid);
        var o = com.OpenRealtime(dataspec, key);
        agg.AddCode($"rtopen:{o.Code}");
        if (!o.Ok)
        {
            var mf = agg.Summary();
            mf["hint"] = "公式の検証ツールで同条件を確認し、コードと条件を報告";
            return new GateResult("G-AGENT-3", "FAIL", $"JVRTOpen が {o.Code}", mf);
        }
        for (var i = 0; i < maxRecords; i++)
        {
            var r = com.Read();
            agg.AddCode($"read:{WireNames.Of(r.Status)}");
            if (r.Status == ReadStatus.Record && r.Data is { } d) agg.AddRecord(d);
            else if (r.Status is ReadStatus.Eof or ReadStatus.Error) break;
            else if (r.Status == ReadStatus.Downloading) Thread.Sleep(500);
        }
        com.Close();
        return new GateResult("G-AGENT-3", agg.Count > 0 ? "PASS" : "FAIL", agg.Count > 0 ? "" : "速報が 0 件", agg.Summary());
    }

    // ------------------------------------------------------------------ スリープ
    /// <summary>実行時に生成した使い捨て鍵で署名した NORMAL のビーコンを 127.0.0.1 で配る（PoC 専用。本番の設定は HTTPS を要求する）。</summary>
    private sealed class MockBeacon : IDisposable
    {
        private readonly HttpListener listener = new();
        private readonly Ed25519PrivateKeyParameters key;

        public MockBeacon()
        {
            var g = new Ed25519KeyPairGenerator();
            g.Init(new Ed25519KeyGenerationParameters(new SecureRandom()));
            key = (Ed25519PrivateKeyParameters)g.GenerateKeyPair().Private;
            var l = new TcpListener(IPAddress.Loopback, 0);
            l.Start();
            Port = ((IPEndPoint)l.LocalEndpoint).Port;
            l.Stop();
            listener.Prefixes.Add($"http://127.0.0.1:{Port}/");
            listener.Start();
            new Thread(Loop) { IsBackground = true }.Start();
        }

        public int Port { get; }

        public BeaconKey Public => new("poc", key.GeneratePublicKey().GetEncoded());

        private byte[] Doc()
        {
            var body = new JsonObject { ["schema_version"] = 1, ["mode"] = "NORMAL", ["seq"] = 1, ["updatedAt"] = DateTimeOffset.UtcNow.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture), ["validUntil"] = null, ["kid"] = "poc" };
            using var d = JsonDocument.Parse(body.ToJsonString());
            var msg = CanonicalJson.Encode(d.RootElement);
            var s = new Ed25519Signer();
            s.Init(true, key);
            s.BlockUpdate(msg, 0, msg.Length);
            body["sig"] = Signing.Base64Url(s.GenerateSignature());
            return Encoding.UTF8.GetBytes(body.ToJsonString());
        }

        private void Loop()
        {
            while (listener.IsListening)
            {
                try
                {
                    var ctx = listener.GetContext();
                    var output = Doc();
                    ctx.Response.ContentLength64 = output.Length;
                    ctx.Response.OutputStream.Write(output);
                    ctx.Response.Close();
                }
                catch (Exception e) when (e is HttpListenerException or ObjectDisposedException or InvalidOperationException)
                {
                    return;
                }
            }
        }

        public void Dispose()
        {
            listener.Stop();
            listener.Close();
        }
    }

    private static int Sleep(Args a)
    {
        var duration = double.Parse(a.Get("--duration") ?? "1800", CultureInfo.InvariantCulture);
        using var srv = new MockBeacon();
        using var transport = new HttpTransport();
        var clock = new SystemClock();
        var client = new BeaconClient($"http://127.0.0.1:{srv.Port}/", [srv.Public], transport, clock, new BeaconGuard(_ => null, (_, _) => { }));
        var machine = new ModeMachine(clock);
        var sleeps = new JsonArray();
        var last = DateTimeOffset.UtcNow;
        var end = last.AddSeconds(duration);
        DateTimeOffset? pendingResume = null;
        while (DateTimeOffset.UtcNow < end)
        {
            Thread.Sleep(5000);
            var now = DateTimeOffset.UtcNow;
            if ((now - last).TotalSeconds > 60)
            {
                // 5 秒のはずが 60 秒以上空いた = スリープした。起動直後と同じ UNKNOWN から（復帰直後に古い状態を使わない）
                sleeps.Add(new JsonObject { ["gap_seconds"] = Math.Round((now - last).TotalSeconds, 1) });
                machine = new ModeMachine(clock);
                pendingResume = now;
            }
            last = now;
            machine.OnBeacon(client.Fetch());
            machine.OnServer(new Classified(Outcome.Ok, 200));
            if (pendingResume is { } pr && machine.Evaluate().Mode == AgentMode.NormalOk)
            {
                sleeps[^1]!["normal_ok_after_seconds"] = Math.Round((DateTimeOffset.UtcNow - pr).TotalSeconds, 1);
                pendingResume = null;
            }
        }
        var ok = sleeps.Count > 0 && sleeps.All(s => s?["normal_ok_after_seconds"] is JsonValue v && v.GetValue<double>() <= 300);
        var res = new GateResult("G-AGENT-6", ok ? "PASS" : sleeps.Count == 0 ? "NOT_RUN" : "FAIL", ok ? "" : sleeps.Count == 0 ? "スリープが発生しなかった" : "復帰が 300 秒を超えた", new JsonObject { ["sleeps"] = sleeps });
        Report(a.Get("--out"), "real-sleep", [res]);
        return ok ? 0 : 1;
    }
}
