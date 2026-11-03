using System.Collections.Concurrent;
using System.Diagnostics;
using System.Globalization;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace JraAgent.Core;

/// <summary>ワーカーが要求を処理できなかった（固定の短いコードだけを持つ。値・秘密は含めない）。</summary>
public sealed class WorkerOpException(string code) : AgentException(code)
{
    public string Code { get; } = code;
}

/// <summary>
/// COM ワーカー（子プロセス）の側（旧 worker_main.py の移植）。JV-Link の COM は<b>このプロセスの中だけ</b>で初期化・使用・終了する。
/// 親（<see cref="WorkerSupervisor"/>）との通信は stdin/stdout の JSON 行（1 行 1 メッセージ）。バイト列は Base64。
/// トークンなどの秘密は受け取らない（受け取るのは dataspec・fromtime・option・key・sid だけ）。
/// </summary>
public static class WorkerServer
{
    public const int MaxLine = 4 * 1024 * 1024;

    /// <summary>レコードを最大 maxRecords 件 / maxBytes バイトまで読む。ファイル切替・EOF・エラーで返す（切替は安全な区切り）。</summary>
    public static JsonObject ReadBatch(IJvLink api, int maxRecords, int maxBytes, int maxPolls, double pollSleep, Action<double>? sleep = null)
    {
        var records = new JsonArray();
        long total = 0;
        var polls = 0;
        var names = new HashSet<string>(StringComparer.Ordinal);
        JsonObject Result(string status, int code) => new() { ["status"] = status, ["code"] = code, ["records"] = records, ["files"] = names.Count };
        while (true)
        {
            var r = api.Read();
            if (!string.IsNullOrEmpty(r.Filename)) names.Add(r.Filename);
            switch (r.Status)
            {
                case ReadStatus.Record:
                    var data = r.Data ?? [];
                    records.Add(Convert.ToBase64String(data));
                    total += data.Length;
                    if (records.Count >= maxRecords || total >= maxBytes) return Result("MORE", r.Code);
                    continue;
                case ReadStatus.Downloading:
                    polls++;
                    if (polls >= maxPolls) return Result("DOWNLOADING", r.Code);
                    (sleep ?? (s => Thread.Sleep(TimeSpan.FromSeconds(s))))(pollSleep);
                    continue;
                case ReadStatus.FileSwitch:
                    return Result("FILE_SWITCH", r.Code);
                case ReadStatus.Eof:
                    return Result("EOF", r.Code);
                default:
                    return Result("ERROR", r.Code);
            }
        }
    }

    private static string Str(JsonObject a, string k) => a[k] is JsonValue v && v.TryGetValue<string>(out var s) ? s : throw new WorkerOpException("BAD_ARGS");

    private static int Int(JsonObject a, string k, int? def = null)
    {
        if (a[k] is JsonValue v && v.TryGetValue<int>(out var n)) return n;
        return def ?? throw new WorkerOpException("BAD_ARGS");
    }

    private static double Num(JsonObject a, string k, double def) => a[k] is JsonValue v && v.TryGetValue<double>(out var d) ? d : def;

    public static JsonObject Handle(IJvLink? api, string op, JsonObject a, ref bool opened)
    {
        if (op == "ping") return new JsonObject { ["pong"] = true };
        if (api is null) throw new WorkerOpException("NO_BACKEND");
        switch (op)
        {
            case "init":
                return new JsonObject { ["code"] = api.Init(Str(a, "sid")) };
            case "open_stored" or "open_realtime":
                {
                    if (opened) throw new WorkerOpException("OPEN_WHILE_OPEN"); // 同時に複数の JVOpen を実行しない
                    var o = op == "open_stored" ? api.OpenStored(Str(a, "dataspec"), Str(a, "fromtime"), Int(a, "option")) : api.OpenRealtime(Str(a, "dataspec"), Str(a, "key"));
                    opened = o.Ok;
                    return new JsonObject { ["code"] = o.Code, ["read_count"] = o.ReadCount, ["download_count"] = o.DownloadCount, ["last_file_timestamp"] = o.LastFileTimestamp };
                }
            case "read_batch":
                if (!opened) throw new WorkerOpException("NOT_OPEN");
                return ReadBatch(api, Int(a, "max_records"), Int(a, "max_bytes"), Int(a, "max_polls", 20), Num(a, "poll_sleep", 0.5));
            case "close":
                opened = false;
                return new JsonObject { ["code"] = api.Close() };
            default:
                throw new WorkerOpException("UNKNOWN_OP");
        }
    }

    /// <summary>1 行（改行まで）を最大 <see cref="MaxLine"/> バイトまで読む。null は入力の終わり。長すぎる行は残りを捨てて空行として返す（不正な要求として応答する）。</summary>
    public static byte[]? ReadLine(Stream input)
    {
        var buf = new MemoryStream();
        var tooLong = false;
        while (true)
        {
            var b = input.ReadByte();
            if (b < 0) return buf.Length == 0 && !tooLong ? null : tooLong ? [] : buf.ToArray();
            if (b == '\n') return tooLong ? [] : buf.ToArray();
            if (buf.Length >= MaxLine) tooLong = true;
            else buf.WriteByte((byte)b);
        }
    }

    private static void Respond(Stream output, JsonObject msg)
    {
        output.Write(Encoding.UTF8.GetBytes(msg.ToJsonString() + "\n"));
        output.Flush();
    }

    /// <summary>要求を処理し続ける。親が閉じたら（入力の終わり）・shutdown で戻る。</summary>
    public static void Serve(IJvLink? api, Stream input, Stream output)
    {
        var opened = false;
        while (true)
        {
            var line = ReadLine(input);
            if (line is null) return; // 親が閉じた
            JsonNode? id = null;
            try
            {
                var req = JsonNode.Parse(line) as JsonObject ?? throw new WorkerOpException("BAD_REQUEST");
                id = req["id"]?.DeepClone();
                var op = req["op"] is JsonValue ov && ov.TryGetValue<string>(out var s) ? s : throw new WorkerOpException("BAD_REQUEST");
                if (op == "shutdown")
                {
                    Respond(output, new JsonObject { ["id"] = id, ["ok"] = true, ["result"] = new JsonObject() });
                    return;
                }
                var args = req["args"] as JsonObject ?? [];
                Respond(output, new JsonObject { ["id"] = id, ["ok"] = true, ["result"] = Handle(api, op, args, ref opened) });
            }
            catch (WorkerOpException e)
            {
                Respond(output, new JsonObject { ["id"] = id, ["ok"] = false, ["error"] = e.Code });
            }
            catch (Exception e) when (e is JsonException or FormatException or InvalidOperationException or ArgumentException or System.Runtime.InteropServices.COMException or System.Reflection.TargetInvocationException)
            {
                Respond(output, new JsonObject { ["id"] = id, ["ok"] = false, ["error"] = e.GetType().Name }); // 例外の型名だけ（メッセージに値が入り得る）
            }
        }
    }
}

public class WorkerException(string kind) : AgentException(kind)
{
    public string Kind { get; } = kind;
}

public sealed class WorkerTimeoutException(string kind) : WorkerException(kind);

public sealed class WorkerCrashedException(string kind) : WorkerException(kind);

public sealed class WorkerQuarantinedException(string kind) : WorkerException(kind);

/// <summary>ワーカーが処理に失敗した（固定のコードか例外の型名だけを持つ）。</summary>
public sealed class WorkerRemoteException(string kind) : WorkerException(kind);

/// <summary>ワーカーへの呼出し（試験では偽物に差し替える）。</summary>
public interface IWorker
{
    JsonObject Call(string op, JsonObject? args = null, double? timeoutSeconds = null);
}

/// <summary>
/// COM ワーカーの監視（ウォッチドッグ。旧 supervisor.py の移植）。
/// <list type="bullet">
/// <item>呼出しごとにタイムアウトを設ける。応答が無ければワーカーを強制終了し（プロセスツリーごと）、呼出し元には <see cref="WorkerTimeoutException"/>。</item>
/// <item>ワーカーが落ちた（EOF・終了コード）場合は <see cref="WorkerCrashedException"/>。どちらでも取得は完了扱いにしない。</item>
/// <item>再起動は呼出しの前に遅延して行う。restart_window の間に max_restarts 回を超えたら、restart_cooldown の間は起動しない（無限再起動ループの防止）。</item>
/// <item>同時に呼び出せるのは 1 つ（ロック）。open 中の open はワーカー側でも拒否される。</item>
/// </list>
/// ウォッチドッグの待ち時間は実時間（FakeClock では進まない）。再起動の回数の窓は <see cref="IClock.Monotonic"/>。
/// </summary>
public sealed class WorkerSupervisor(IReadOnlyList<string> argv, WorkerConfig cfg, IClock clock, Action<Process>? onSpawn = null) : IWorker, IDisposable
{
    private sealed class Handle : IDisposable
    {
        private readonly Thread pump;

        public Handle(Process proc)
        {
            Proc = proc;
            pump = new Thread(Pump) { IsBackground = true, Name = "worker-stdout" };
            pump.Start();
        }

        public Process Proc { get; }

        public BlockingCollection<string?> Lines { get; } = [];

        private void Pump()
        {
            try
            {
                string? line;
                while ((line = Proc.StandardOutput.ReadLine()) is not null) Lines.Add(line);
            }
            catch (Exception e) when (e is IOException or ObjectDisposedException or InvalidOperationException)
            {
                // パイプが閉じた
            }
            try
            {
                Lines.Add(null); // EOF = ワーカーが終了した
            }
            catch (InvalidOperationException)
            {
                // 破棄済み
            }
        }

        public void Send(JsonObject msg)
        {
            Proc.StandardInput.Write(msg.ToJsonString() + "\n");
            Proc.StandardInput.Flush();
        }

        public void Dispose() => Lines.Dispose();
    }

    private readonly Lock gate = new();
    private readonly Queue<double> starts = new();
    private Handle? h;
    private long nextId;
    private double quarantineUntil;

    public int Restarts { get; private set; }

    public int Timeouts { get; private set; }

    public int Crashes { get; private set; }

    /// <summary>引数の配列のまま起動する（シェルを使わない）。stdin/stdout は JSON 行のプロトコル専用、stderr は捨てる（ログに生データを残さない）。</summary>
    private Process Spawn()
    {
        var psi = new ProcessStartInfo(argv[0])
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = new UTF8Encoding(false),
            StandardInputEncoding = new UTF8Encoding(false),
        };
        foreach (var a in argv.Skip(1)) psi.ArgumentList.Add(a);
        var p = Process.Start(psi) ?? throw new WorkerCrashedException("SPAWN_FAILED");
        p.ErrorDataReceived += (_, _) => { };
        p.BeginErrorReadLine();
        onSpawn?.Invoke(p); // Windows: ジョブオブジェクトに入れる（親が落ちても OS がワーカーを終了する）
        return p;
    }

    private static bool Alive(Process p)
    {
        try
        {
            return !p.HasExited;
        }
        catch (InvalidOperationException)
        {
            return false;
        }
    }

    private Handle Ensure()
    {
        if (h is not null && Alive(h.Proc)) return h;
        if (h is not null) Discard();
        var now = clock.Monotonic;
        if (now < quarantineUntil) throw new WorkerQuarantinedException("RESTART_COOLDOWN");
        while (starts.Count > 0 && now - starts.Peek() > cfg.RestartWindowSeconds) starts.Dequeue();
        if (starts.Count >= cfg.MaxRestarts)
        {
            quarantineUntil = now + cfg.RestartCooldownSeconds;
            throw new WorkerQuarantinedException("RESTART_LIMIT"); // 無限再起動ループを防ぐ
        }
        starts.Enqueue(now);
        if (starts.Count > 1) Restarts++;
        Process p;
        try
        {
            p = Spawn();
        }
        catch (Exception e) when (e is System.ComponentModel.Win32Exception or InvalidOperationException or IOException)
        {
            throw new WorkerCrashedException("SPAWN_FAILED");
        }
        h = new Handle(p);
        try
        {
            Roundtrip(h, "ping", [], cfg.StartupTimeoutSeconds);
        }
        catch (WorkerException)
        {
            Discard();
            throw;
        }
        return h;
    }

    /// <summary>ワーカーとその子を終了し、必ず待つ（ゾンビ・ハンドルを残さない）。</summary>
    private void Discard()
    {
        var old = h;
        h = null;
        if (old is null) return;
        try
        {
            if (Alive(old.Proc)) old.Proc.Kill(entireProcessTree: true);
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception or NotSupportedException)
        {
            // すでに終了している
        }
        try
        {
            old.Proc.WaitForExit(10_000);
        }
        catch (Exception e) when (e is InvalidOperationException or System.ComponentModel.Win32Exception)
        {
            // 待てない（すでに破棄）
        }
        old.Proc.Dispose();
    }

    public void Stop()
    {
        lock (gate)
        {
            if (h is not null && Alive(h.Proc))
            {
                try
                {
                    Roundtrip(h, "shutdown", [], 3.0);
                }
                catch (WorkerException)
                {
                    // 応答しなければ強制終了する
                }
            }
            Discard();
        }
    }

    public void Dispose() => Stop();

    public JsonObject Call(string op, JsonObject? args = null, double? timeoutSeconds = null)
    {
        lock (gate)
        {
            var handle = Ensure();
            try
            {
                return Roundtrip(handle, op, args ?? [], timeoutSeconds ?? cfg.CallTimeoutSeconds);
            }
            catch (WorkerTimeoutException)
            {
                Timeouts++;
                Discard(); // 無応答 = 強制終了（COM が戻らない）
                throw;
            }
            catch (WorkerCrashedException)
            {
                Crashes++;
                Discard();
                throw;
            }
        }
    }

    private JsonObject Roundtrip(Handle handle, string op, JsonObject args, double timeout)
    {
        var rid = ++nextId;
        try
        {
            handle.Send(new JsonObject { ["id"] = rid, ["op"] = op, ["args"] = args });
        }
        catch (Exception e) when (e is IOException or ObjectDisposedException or InvalidOperationException)
        {
            throw new WorkerCrashedException("PIPE_BROKEN");
        }
        if (!handle.Lines.TryTake(out var line, TimeSpan.FromSeconds(timeout))) throw new WorkerTimeoutException(op);
        if (line is null)
        {
            string code;
            try
            {
                code = handle.Proc.WaitForExit(2000) ? handle.Proc.ExitCode.ToString(CultureInfo.InvariantCulture) : "None";
            }
            catch (InvalidOperationException)
            {
                code = "None";
            }
            throw new WorkerCrashedException($"EXIT_{code}");
        }
        JsonObject msg;
        try
        {
            // JSON でも辞書でない行（配列・数値）は不正なプロトコル
            msg = JsonNode.Parse(line) as JsonObject ?? throw new WorkerCrashedException("BAD_PROTOCOL");
        }
        catch (JsonException)
        {
            throw new WorkerCrashedException("BAD_PROTOCOL");
        }
        if (msg["id"] is not JsonValue idv || !idv.TryGetValue<long>(out var got) || got != rid) throw new WorkerCrashedException("BAD_PROTOCOL");
        if (msg["ok"] is not JsonValue okv || !okv.TryGetValue<bool>(out var ok) || !ok)
            throw new WorkerRemoteException(msg["error"] is JsonValue ev && ev.TryGetValue<string>(out var err) ? err : "ERROR");
        return msg["result"] is JsonObject res ? (JsonObject)res.DeepClone() : [];
    }

    public static IReadOnlyList<byte[]> DecodeRecords(JsonObject result) =>
        result["records"] is JsonArray a ? [.. a.Select(r => Convert.FromBase64String(r?.GetValue<string>() ?? throw new FormatException("records")))] : [];
}
