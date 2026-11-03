using System.Globalization;
using Tomlyn;
using Tomlyn.Model;

namespace JraAgent.Core;

/// <summary>キューの上限など（旧 config.py の QueueConfig）。</summary>
public sealed record QueueConfig(
    long MaxBytes = 2L * 1024 * 1024 * 1024,
    long MinFreeBytes = 512L * 1024 * 1024,
    long RealtimeTtlSeconds = 6 * 24 * 3600, // サーバは速報を 7 日で拒否する（OBSERVED_AT_TOO_OLD）ので余裕を持たせる
    int MaxBatchRecords = 200,
    int MaxBatchRawBytes = 600_000,
    int GzipMinBytes = 512);

public sealed record SenderConfig(
    int MaxAttempts = 50,
    double MaxConflictWaitSeconds = 120.0,
    double SendsPerSecond = 2.0, // 復帰時の再送は最大 2 バッチ/秒（CONTRACTS §7.4）
    double RequestTimeoutSeconds = 30.0);

public sealed record WorkerConfig(
    double CallTimeoutSeconds = 120.0,
    double StartupTimeoutSeconds = 30.0,
    int MaxRestarts = 5,
    double RestartWindowSeconds = 3600.0,
    double RestartCooldownSeconds = 300.0);

public sealed record BeaconConfig(string Url, IReadOnlyList<BeaconKey> Keys, long MaxAgeSeconds = 6 * 3600);

/// <summary>既定は無効。本人の許可（<c>agent update --apply</c>）なしに適用しない。</summary>
public sealed record UpdateConfig(IReadOnlyList<BeaconKey> Keys, bool Enabled = false);

public sealed record AgentConfig(
    string Environment,
    string BaseUrl,
    string AgentId,
    string DataDir,
    BeaconConfig Beacon,
    QueueConfig Queue,
    SenderConfig Sender,
    WorkerConfig Worker,
    UpdateConfig Update,
    string JvlinkMode = "fake",
    int LogRetentionDays = 30)
{
    public bool Production => Environment == "production";

    public string QueuePath => Path.Combine(DataDir, "queue.sqlite3");

    public string LogDir => Path.Combine(DataDir, "logs");

    public string SecretPath => Path.Combine(DataDir, "secrets");
}

/// <summary>
/// 設定（TOML。旧 config.py の移植）。秘密（トークン・利用キー）は<b>置けない</b>: それらしいキーがあれば拒否する。
/// 値の誤りは項目名だけを報告し、値は例外に含めない。本番（<c>environment = "production"</c>）は HTTPS 以外・ループバックを拒否する。
/// 署名検証を省略する設定は存在しない（ビーコン・更新 manifest とも）。
/// </summary>
public static class ConfigLoader
{
    private static readonly HashSet<string> ForbiddenKeys =
        ["token", "password", "secret", "api_key", "apikey", "sid", "jvlink_key", "service_key", "private_key"];

    private static readonly HashSet<string> Loopback = ["127.0.0.1", "::1", "[::1]", "localhost"];

    /// <summary>識別子（agent_id・kid）は ASCII の英数字と <c>-</c> <c>_</c> だけ（旧 Python の isalnum は非 ASCII の文字も通していた）。</summary>
    public static bool IsIdent(string s, int min, int max) =>
        s.Length >= min && s.Length <= max && s.All(c => c is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_');

    public static BeaconKey ParsePublicKey(object? entry, string where)
    {
        if (entry is not TomlTable t || t.Count != 2 || !t.ContainsKey("kid") || !t.ContainsKey("public_key"))
            throw new ConfigException($"{where}: kid と public_key が必要");
        if (t["kid"] is not string kid || !IsIdent(kid, 1, 32)) throw new ConfigException($"{where}.kid の形式が不正");
        if (t["public_key"] is not string key) throw new ConfigException($"{where}.public_key は文字列");
        byte[] raw;
        try
        {
            raw = Signing.Base64UrlDecode(key.TrimEnd('='));
        }
        catch (FormatException)
        {
            throw new ConfigException($"{where}.public_key の Base64 が不正");
        }
        if (raw.Length != 32) throw new ConfigException($"{where}.public_key は Ed25519 の 32 バイト");
        return new BeaconKey(kid, raw);
    }

    public static string ValidateUrl(object? url, string where, string environment)
    {
        if (url is not string s) throw new ConfigException($"{where} は文字列");
        if (!Uri.TryCreate(s, UriKind.Absolute, out var u)) throw new ConfigException($"{where} は HTTPS（本番）である必要がある");
        if (!string.IsNullOrEmpty(u.UserInfo)) throw new ConfigException($"{where} に認証情報を含めない");
        var host = u.Host.ToLowerInvariant();
        if (u.Scheme == Uri.UriSchemeHttps && host.Length > 0)
        {
            if (environment == "production" && Loopback.Contains(host)) throw new ConfigException($"{where}: 本番でループバックは使えない");
            return s;
        }
        if (u.Scheme == Uri.UriSchemeHttp && Loopback.Contains(host) && environment == "development") return s; // 開発・結合テスト専用。本番では不可
        throw new ConfigException($"{where} は HTTPS（本番）である必要がある");
    }

    private static void RejectSecrets(object? node, string path = "")
    {
        switch (node)
        {
            case TomlTable t:
                foreach (var (k, v) in t)
                {
                    if (ForbiddenKeys.Contains(k.ToLowerInvariant()))
                        throw new ConfigException($"{path}{k}: 秘密は設定ファイルに置かない（DPAPI などの SecretStore に保存する）");
                    RejectSecrets(v, $"{path}{k}.");
                }
                break;
            case TomlTableArray ta:
                for (var i = 0; i < ta.Count; i++) RejectSecrets(ta[i], $"{path}[{i}].");
                break;
            case TomlArray a:
                for (var i = 0; i < a.Count; i++) RejectSecrets(a[i], $"{path}[{i}].");
                break;
        }
    }

    private static TomlTable Table(TomlTable doc, string key, HashSet<string> allowed)
    {
        if (!doc.TryGetValue(key, out var v)) return new TomlTable();
        if (v is not TomlTable t) throw new ConfigException($"{key} は表");
        Only(t, allowed, key);
        return t;
    }

    private static void Only(TomlTable t, HashSet<string> allowed, string where)
    {
        var unknown = t.Keys.Where(k => !allowed.Contains(k)).Order(StringComparer.Ordinal).Take(3).ToList();
        if (unknown.Count > 0) throw new ConfigException($"{where}: 未知の項目 [{string.Join(", ", unknown)}]");
    }

    private static long Int(TomlTable t, string key, long def, long lo, long hi, string where)
    {
        if (!t.TryGetValue(key, out var v)) v = def;
        if (v is not long n || n < lo || n > hi) throw new ConfigException($"{where}.{key} は {lo}〜{hi} の整数");
        return n;
    }

    private static double Float(TomlTable t, string key, double def, double lo, double hi, string where)
    {
        if (!t.TryGetValue(key, out var v)) v = def;
        double x = v switch
        {
            long n => n,
            double d => d,
            _ => throw new ConfigException($"{where}.{key} は {lo.ToString(CultureInfo.InvariantCulture)}〜{hi.ToString(CultureInfo.InvariantCulture)}"),
        };
        if (!(x >= lo && x <= hi)) throw new ConfigException($"{where}.{key} は {lo.ToString(CultureInfo.InvariantCulture)}〜{hi.ToString(CultureInfo.InvariantCulture)}");
        return x;
    }

    private static List<object?> List(object? v, string where)
    {
        return v switch
        {
            null => [],
            TomlArray a => [.. a],
            TomlTableArray ta => [.. ta],
            _ => throw new ConfigException($"{where} は配列"),
        };
    }

    /// <summary><c>~</c> と <c>%VAR%</c> を展開する。</summary>
    public static string ExpandPath(string text)
    {
        var s = System.Environment.ExpandEnvironmentVariables(text);
        if (s == "~" || s.StartsWith("~/", StringComparison.Ordinal) || s.StartsWith("~\\", StringComparison.Ordinal))
            s = System.Environment.GetFolderPath(System.Environment.SpecialFolder.UserProfile) + s[1..];
        return s;
    }

    public static AgentConfig Parse(TomlTable doc)
    {
        RejectSecrets(doc);
        Only(doc, ["environment", "agent_id", "data_dir", "server", "beacon", "queue", "sender", "worker", "update", "jvlink", "log"], "config");
        var env = doc.TryGetValue("environment", out var e) ? e : "production";
        if (env is not ("production" or "development")) throw new ConfigException("environment は production または development");
        var environment = (string)env;
        if (!doc.TryGetValue("agent_id", out var aid) || aid is not string agentId || !IsIdent(agentId, 3, 64)) throw new ConfigException("agent_id の形式が不正");
        if (!doc.TryGetValue("data_dir", out var dd) || dd is not string dataDir || dataDir.Length == 0) throw new ConfigException("data_dir が必要");

        var server = Table(doc, "server", ["base_url"]);
        var baseUrl = ValidateUrl(server.TryGetValue("base_url", out var bu) ? bu : null, "server.base_url", environment);

        var beacon = Table(doc, "beacon", ["url", "keys", "max_age_seconds"]);
        var keysRaw = List(beacon.TryGetValue("keys", out var kr) ? kr : null, "beacon.keys");
        if (keysRaw.Count is < 1 or > 2) throw new ConfigException("beacon.keys は 1〜2 本（kid 付きの公開鍵）。検証を省略する設定はない");
        var keys = keysRaw.Select((k, i) => ParsePublicKey(k, $"beacon.keys[{i}]")).ToList();
        if (keys.Select(k => k.Kid).Distinct(StringComparer.Ordinal).Count() != keys.Count) throw new ConfigException("beacon.keys の kid が重複");
        var beaconCfg = new BeaconConfig(
            ValidateUrl(beacon.TryGetValue("url", out var burl) ? burl : null, "beacon.url", environment),
            keys,
            Int(beacon, "max_age_seconds", 21600, 60, 7 * 24 * 3600, "beacon"));

        var d = new QueueConfig();
        var q = Table(doc, "queue", ["max_bytes", "min_free_bytes", "realtime_ttl_seconds", "max_batch_records", "max_batch_raw_bytes", "gzip_min_bytes"]);
        var queue = new QueueConfig(
            Int(q, "max_bytes", d.MaxBytes, 1024L * 1024, 64L * 1024 * 1024 * 1024, "queue"),
            Int(q, "min_free_bytes", d.MinFreeBytes, 0, 1024L * 1024 * 1024 * 1024, "queue"),
            Int(q, "realtime_ttl_seconds", d.RealtimeTtlSeconds, 60, (6 * 24 * 3600) + (12 * 3600), "queue"),
            (int)Int(q, "max_batch_records", d.MaxBatchRecords, 1, 200, "queue"),
            (int)Int(q, "max_batch_raw_bytes", d.MaxBatchRawBytes, 1024, 700_000, "queue"),
            (int)Int(q, "gzip_min_bytes", d.GzipMinBytes, 0, 1024 * 1024, "queue"));

        var sd = new SenderConfig();
        var s = Table(doc, "sender", ["max_attempts", "max_conflict_wait_seconds", "sends_per_second", "request_timeout_seconds"]);
        var sender = new SenderConfig(
            (int)Int(s, "max_attempts", sd.MaxAttempts, 1, 1000, "sender"),
            Float(s, "max_conflict_wait_seconds", sd.MaxConflictWaitSeconds, 1, 900, "sender"),
            Float(s, "sends_per_second", sd.SendsPerSecond, 0.1, 10, "sender"),
            Float(s, "request_timeout_seconds", sd.RequestTimeoutSeconds, 1, 300, "sender"));

        var wd = new WorkerConfig();
        var w = Table(doc, "worker", ["call_timeout_seconds", "startup_timeout_seconds", "max_restarts", "restart_window_seconds", "restart_cooldown_seconds"]);
        var worker = new WorkerConfig(
            Float(w, "call_timeout_seconds", wd.CallTimeoutSeconds, 1, 3600, "worker"),
            Float(w, "startup_timeout_seconds", wd.StartupTimeoutSeconds, 1, 600, "worker"),
            (int)Int(w, "max_restarts", wd.MaxRestarts, 1, 100, "worker"),
            Float(w, "restart_window_seconds", wd.RestartWindowSeconds, 10, 86400, "worker"),
            Float(w, "restart_cooldown_seconds", wd.RestartCooldownSeconds, 1, 86400, "worker"));

        var u = Table(doc, "update", ["keys", "enabled"]);
        var ukeys = List(u.TryGetValue("keys", out var uk) ? uk : null, "update.keys").Select((k, i) => ParsePublicKey(k, $"update.keys[{i}]")).ToList();
        var enabledRaw = u.TryGetValue("enabled", out var en) ? en : false;
        if (enabledRaw is not bool enabled) throw new ConfigException("update.enabled は真偽値");
        if (enabled && ukeys.Count == 0) throw new ConfigException("update.enabled には update.keys（署名検証用の公開鍵）が必要");

        var jv = Table(doc, "jvlink", ["mode"]);
        var mode = jv.TryGetValue("mode", out var m) ? m : "fake";
        if (mode is not ("fake" or "com")) throw new ConfigException("jvlink.mode は fake または com");
        if (environment == "production" && (string)mode == "fake")
        {
            // 本番の設定で Fake を黙って使わない（取得したつもりで何も取らない事故を防ぐ）。com にするには利用者の明示が要る
            throw new ConfigException("production では jvlink.mode = \"com\" を明示する（fake は development だけ）");
        }
        var log = Table(doc, "log", ["retention_days"]);
        return new AgentConfig(
            environment,
            baseUrl,
            agentId,
            ExpandPath(dataDir),
            beaconCfg,
            queue,
            sender,
            worker,
            new UpdateConfig(ukeys, enabled),
            (string)mode,
            (int)Int(log, "retention_days", 30, 1, 365, "log"));
    }

    public static AgentConfig ParseText(string toml)
    {
        TomlTable doc;
        try
        {
            doc = TomlSerializer.Deserialize<TomlTable>(toml) ?? throw new ConfigException("設定ファイルの TOML が不正");
        }
        catch (TomlException)
        {
            throw new ConfigException("設定ファイルの TOML が不正");
        }
        return Parse(doc);
    }

    public static AgentConfig Load(string path)
    {
        string text;
        try
        {
            text = File.ReadAllText(path, new System.Text.UTF8Encoding(false, true));
        }
        catch (Exception ex) when (ex is IOException or UnauthorizedAccessException or System.Text.DecoderFallbackException)
        {
            throw new ConfigException("設定ファイルを読めない");
        }
        return ParseText(text);
    }
}
