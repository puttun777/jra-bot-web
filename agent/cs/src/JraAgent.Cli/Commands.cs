using System.Globalization;
using System.Runtime.InteropServices;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using JraAgent.Core;
using JraAgent.Windows;

namespace JraAgent.Cli;

/// <summary>解析した引数（<c>--name value</c> と <c>--flag</c> と位置引数）。未知のオプションは拒否する。</summary>
internal sealed class Args
{
    private readonly Dictionary<string, string?> opts = new(StringComparer.Ordinal);

    public List<string> Positional { get; } = [];

    public static Args Parse(IReadOnlyList<string> argv, IReadOnlySet<string> valued, IReadOnlySet<string> flags)
    {
        var a = new Args();
        for (var i = 0; i < argv.Count; i++)
        {
            var s = argv[i];
            if (s.StartsWith("--", StringComparison.Ordinal))
            {
                if (valued.Contains(s))
                {
                    if (i + 1 >= argv.Count) throw new UsageException($"{s} に値が必要");
                    a.opts[s] = argv[++i];
                }
                else if (flags.Contains(s))
                {
                    a.opts[s] = null;
                }
                else
                {
                    throw new UsageException($"未知のオプション {s}");
                }
            }
            else
            {
                a.Positional.Add(s);
            }
        }
        return a;
    }

    public bool Has(string name) => opts.ContainsKey(name);

    public string? Get(string name) => opts.GetValueOrDefault(name);

    public string Require(string name) => opts.GetValueOrDefault(name) ?? throw new UsageException($"{name} が必要");
}

internal sealed class UsageException(string message) : Exception(message);

/// <summary>コマンドの実装（旧 cli.py の移植）。標準出力には集計の JSON だけを出す（秘密・レコードの内容を出さない）。</summary>
internal static class Commands
{
    private static readonly JsonSerializerOptions Pretty = new() { WriteIndented = true, Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping };

    public static void Print(JsonNode node) => Console.Out.WriteLine(node.ToJsonString(Pretty));

    /// <summary>本番の秘密の保護は Windows の DPAPI だけ。Windows 以外は開発環境に限り、プロセス内の鍵で保護する（プロセスをまたいで復号できない）。</summary>
    public static ISecretStore SecretStore(AgentConfig cfg)
    {
        if (OperatingSystem.IsWindows()) return new ProtectedFileSecretStore(cfg.SecretPath, new DpapiProtector());
        if (cfg.Production) throw new SecretException("本番の秘密の保護は Windows の DPAPI だけ（Windows 以外では開発環境のみ）");
        return new ProtectedFileSecretStore(cfg.SecretPath, new FakeProtector());
    }

    public static int VerifyConfig(AgentConfig cfg)
    {
        Print(new JsonObject
        {
            ["ok"] = true,
            ["environment"] = cfg.Environment,
            ["https"] = cfg.BaseUrl.StartsWith("https://", StringComparison.Ordinal),
            ["beacon_keys"] = new JsonArray([.. cfg.Beacon.Keys.Select(k => (JsonNode)k.Kid)]),
            ["jvlink_mode"] = cfg.JvlinkMode,
        });
        return 0;
    }

    public static int VerifyQueue(AgentConfig cfg)
    {
        var rep = Diagnostics.QueueReport(cfg.QueuePath, new SystemClock());
        Print(rep);
        return rep["exists"]?.GetValue<bool>() == true && rep["problems"] is JsonArray { Count: 0 } ? 0 : 1;
    }

    public static int Status(AgentConfig cfg)
    {
        Print(new JsonObject { ["agent_version"] = SignedClient.Version, ["queue"] = Diagnostics.QueueReport(cfg.QueuePath, new SystemClock()) });
        return 0;
    }

    public static int Diagnose(AgentConfig cfg, Args a)
    {
        var clock = new SystemClock();
        BeaconResult? beacon = null;
        if (a.Has("--online"))
        {
            using var transport = new HttpTransport();
            OutboxQueue? q = File.Exists(cfg.QueuePath) ? new OutboxQueue(cfg.QueuePath, clock, readOnly: true) : null;
            try
            {
                var guard = new BeaconGuard(q is null ? _ => null : q.GetState, (_, _) => { }); // 診断では seq を永続しない
                beacon = new BeaconClient(cfg.Beacon.Url, cfg.Beacon.Keys, transport, clock, guard).Fetch();
            }
            finally
            {
                q?.Dispose();
            }
        }
        Print(Diagnostics.FullReport(cfg, new CredentialStore(SecretStore(cfg)), clock, beacon));
        return 0;
    }

    public static int SetCredential(AgentConfig cfg, Args a)
    {
        var token = (a.Has("--stdin") ? Console.In.ReadLine() : ReadHidden("トークン（表示されません）: "))?.Trim() ?? "";
        if (!token.StartsWith("jrat1_", StringComparison.Ordinal) || token.Length < 20)
        {
            Console.Error.WriteLine("トークンの形式が不正");
            return 2;
        }
        DateTimeOffset? expires = null;
        if (a.Get("--expires-at") is { } e)
        {
            var hasZone = e.EndsWith('Z') || (e.Length > 6 && e[^6] is '+' or '-' && e[^3] == ':');
            if (!hasZone || !DateTimeOffset.TryParse(e, CultureInfo.InvariantCulture, DateTimeStyles.None, out var t))
            {
                Console.Error.WriteLine("--expires-at は ISO 8601（タイムゾーン付き）");
                return 2;
            }
            expires = t;
        }
        new CredentialStore(SecretStore(cfg)).Save(new Credential(cfg.AgentId, token, expires));
        if (File.Exists(cfg.QueuePath))
        {
            // 資格情報を入れ直した = 認証の停止を解除して再開する（記録が壊れて指紋で判定できない場合も、ここで解除できる）
            using var q = new OutboxQueue(cfg.QueuePath, new SystemClock(), cfg.Queue);
            q.SetState(Sender.AuthHaltKey, "");
            q.ClearAlert("AUTH_FAILED");
        }
        Print(new JsonObject { ["ok"] = true, ["agent_id"] = cfg.AgentId });
        return 0;
    }

    private static string? ReadHidden(string prompt)
    {
        if (Console.IsInputRedirected) return Console.In.ReadLine();
        Console.Error.Write(prompt);
        var sb = new System.Text.StringBuilder();
        while (true)
        {
            var k = Console.ReadKey(intercept: true);
            if (k.Key == ConsoleKey.Enter) break;
            if (k.Key == ConsoleKey.Backspace)
            {
                if (sb.Length > 0) sb.Length--;
                continue;
            }
            sb.Append(k.KeyChar);
        }
        Console.Error.WriteLine();
        return sb.ToString();
    }

    public static int ClearCredential(AgentConfig cfg)
    {
        Print(new JsonObject { ["removed"] = new CredentialStore(SecretStore(cfg)).RevokeLocal() });
        return 0;
    }

    public static int ClearAlert(AgentConfig cfg, Args a)
    {
        if (a.Positional.Count != 1) throw new UsageException("clear-alert <name>");
        using var q = new OutboxQueue(cfg.QueuePath, new SystemClock(), cfg.Queue);
        Print(new JsonObject { ["cleared"] = q.ClearAlert(a.Positional[0]) });
        return 0;
    }

    public static int BeaconReset(AgentConfig cfg, Args a)
    {
        if (!a.Has("--confirm"))
        {
            Console.Error.WriteLine("ビーコンの seq の記録を消します。infra の ops-state を作り直したときだけ。--confirm を付けて再実行");
            return 2;
        }
        using var q = new OutboxQueue(cfg.QueuePath, new SystemClock(), cfg.Queue);
        new BeaconGuard(q.GetState, q.SetState).Reset();
        Print(new JsonObject { ["ok"] = true });
        return 0;
    }

    public static int Update(AgentConfig cfg, Args a)
    {
        if (a.Positional.Count != 1) throw new UsageException("update check|stage|activate|rollback|status --install-root <dir>");
        var inst = new UpdateInstaller(a.Require("--install-root"));
        switch (a.Positional[0])
        {
            case "status":
                var st = inst.State();
                Print(new JsonObject { ["current"] = st.Current, ["previous"] = st.Previous, ["staged"] = st.Staged });
                return 0;
            case "rollback":
                Print(new JsonObject { ["current"] = inst.Rollback() });
                return 0;
            case "activate":
                var v = a.Require("--version");
                inst.Activate(v);
                Print(new JsonObject { ["current"] = v });
                return 0;
            case "check" or "stage":
                var m = Updates.VerifyManifest(File.ReadAllBytes(a.Require("--manifest")), cfg.Update.Keys, SignedClient.Version);
                if (a.Positional[0] == "check")
                {
                    Print(new JsonObject { ["ok"] = true, ["version"] = m.Version, ["size"] = m.Size });
                    return 0;
                }
                var data = File.ReadAllBytes(a.Require("--artifact")); // ダウンロードは利用者が行う（自動で実行ファイルを取りに行かない）
                Print(new JsonObject { ["staged"] = inst.Stage(data, m), ["version"] = m.Version, ["next"] = "jraagent update activate --version を実行して切り替える" });
                return 0;
            default:
                throw new UsageException("update check|stage|activate|rollback|status");
        }
    }

    /// <summary>ワーカーの起動の引数（同じ実行ファイルの worker サブコマンド）と、Windows のジョブオブジェクトへの登録。</summary>
    public static WorkerSupervisor ComSupervisor(AgentConfig cfg, IClock clock, AgentLog log, out IDisposable? job)
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("実行ファイルの場所が分からない");
        Action<System.Diagnostics.Process>? onSpawn = null;
        job = null;
        if (OperatingSystem.IsWindows())
        {
            var j = new KillOnCloseJob();
            job = j;
            onSpawn = p =>
            {
                if (OperatingSystem.IsWindows() && !j.Assign(p))
                {
                    // ジョブに入れられないワーカーは孤児として残り得るので使わない
                    p.Kill(entireProcessTree: true);
                    p.WaitForExit(10_000);
                    p.Dispose();
                    log.Error("ワーカーをジョブオブジェクトに入れられない。起動を中止した");
                    throw new InvalidOperationException("JOB_ASSIGN_FAILED");
                }
            };
        }
        return new WorkerSupervisor([exe, "worker", "--backend", "com"], cfg.Worker, clock, onSpawn);
    }

    public static int Run(AgentConfig cfg, Args a)
    {
        using var log = new AgentLog(cfg.LogDir, cfg.LogRetentionDays, a.Has("--verbose"));
        var clock = new SystemClock();
        var cred = new CredentialStore(SecretStore(cfg)).Load();
        if (cred is null || cred.AgentId != cfg.AgentId)
        {
            log.Error("資格情報が無い、または agent_id が設定と違う（set-credential で保存する）");
            Console.Error.WriteLine("資格情報が無い、または agent_id が設定と違う（set-credential で保存する）");
            return 3;
        }
        if (cred.Expired(clock.Now))
        {
            log.Error("トークンの期限切れ。再発行して set-credential で差し替える");
            Console.Error.WriteLine("トークンの期限切れ。再発行して set-credential で差し替える");
            return 3;
        }
        using var instance = new InstanceLock(Path.Combine(cfg.DataDir, "agent.lock"));
        try
        {
            instance.Acquire();
        }
        catch (AlreadyRunningException)
        {
            log.Error("多重起動を検出。終了する");
            Console.Error.WriteLine("多重起動を検出。終了する");
            return 4;
        }
        using var stop = new CancellationTokenSource();
        using var sigInt = PosixSignalRegistration.Create(PosixSignal.SIGINT, c => { c.Cancel = true; stop.Cancel(); });
        using var sigTerm = PosixSignalRegistration.Create(PosixSignal.SIGTERM, c => { c.Cancel = true; stop.Cancel(); });
        using var sigQuit = PosixSignalRegistration.Create(PosixSignal.SIGQUIT, c => { c.Cancel = true; stop.Cancel(); });
        using var queue = new OutboxQueue(cfg.QueuePath, clock, cfg.Queue);
        using var transport = new HttpTransport();
        var modes = new ModeMachine(clock, cfg.Beacon.MaxAgeSeconds);
        var client = new SignedClient(cfg.BaseUrl, new RequestSigner(cred.AgentId, cred.Token), transport, clock, cfg.Sender.RequestTimeoutSeconds, cfg.Queue.GzipMinBytes);
        var api = new AgentApi(client, modes);
        var beacon = new BeaconClient(cfg.Beacon.Url, cfg.Beacon.Keys, transport, clock, new BeaconGuard(queue.GetState, queue.SetState));
        var sender = new Sender(queue, client, modes, cfg.Sender, clock, cred.Fingerprint(), logError: log.Error);
        WorkerSupervisor? sup = null;
        IDisposable? job = null;
        Fetcher? fetcher = null;
        if (cfg.JvlinkMode == "com")
        {
            sup = ComSupervisor(cfg, clock, log, out job);
            // 仕様ゲート: 公式仕様の確認が記録されるまで実取得はしない
            fetcher = new Fetcher(cred.AgentId, queue, sup, clock, SpecRegistry.Default, cfg.Queue, () => modes.MayFetch);
        }
        var runner = new AgentRunner(queue, modes, beacon, api, client, sender, fetcher, clock, diskFreeMb: () => Diagnostics.DiskFreeMb(cfg.DataDir), logError: log.Error);
        log.Info($"agent {SignedClient.Version} 起動 (jvlink={cfg.JvlinkMode})");
        try
        {
            runner.RunForever(stop.Token);
        }
        finally
        {
            sup?.Stop();
            job?.Dispose();
        }
        log.Info("agent 停止");
        return 0;
    }
}
