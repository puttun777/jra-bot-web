using System.Runtime.InteropServices;
using System.Text.Json.Nodes;
using JraAgent.Core;
using JraAgent.Windows;

namespace JraAgent.Cli;

/// <summary>
/// 診断（<c>jraagent diagnose</c>。旧 diagnostics.py の移植）。<b>集計だけ</b>を出す（件数・版数・可否・コード）。
/// トークン・利用キー・レコードの内容・パスの中のユーザー名を出さない。JV-Link の API は一切呼ばない（COM の登録の有無をレジストリで調べるだけ）。
/// </summary>
internal static class Diagnostics
{
    public static JsonObject EnvironmentInfo() => new()
    {
        ["agent_version"] = SignedClient.Version,
        ["dotnet"] = RuntimeInformation.FrameworkDescription,
        ["process_bits"] = IntPtr.Size * 8,
        ["os"] = RuntimeInformation.OSDescription,
        ["machine"] = RuntimeInformation.OSArchitecture.ToString(),
        ["is_windows"] = OperatingSystem.IsWindows(),
    };

    public static JsonObject ComRegistrationInfo()
    {
        if (!OperatingSystem.IsWindows()) return new JsonObject { ["applicable"] = false };
        var (progIds, clsid) = ComRegistration.Check();
        var p = new JsonObject();
        foreach (var (k, v) in progIds) p[k] = v;
        return new JsonObject { ["applicable"] = true, ["progids"] = p, ["clsid_registered"] = clsid };
    }

    public static long? DiskFreeMb(string dataDir)
    {
        try
        {
            var dir = Directory.Exists(dataDir) ? dataDir : Path.GetDirectoryName(Path.GetFullPath(dataDir)) ?? dataDir;
            return new DriveInfo(Path.GetFullPath(dir)).AvailableFreeSpace / (1024 * 1024);
        }
        catch (Exception e) when (e is IOException or ArgumentException or UnauthorizedAccessException)
        {
            return null;
        }
    }

    public static JsonObject QueueReport(string path, IClock clock)
    {
        if (!File.Exists(path)) return new JsonObject { ["exists"] = false };
        using var q = new OutboxQueue(path, clock, readOnly: true);
        var st = q.Stats();
        var by = new JsonObject();
        foreach (var (k, v) in st.ByStatus.OrderBy(x => x.Key, StringComparer.Ordinal)) by[k] = v;
        var cps = new JsonObject();
        foreach (var c in q.Checkpoints()) cps[c.CursorKey] = new JsonObject { ["fetched"] = c.FetchedTs, ["queued"] = c.QueuedTs, ["acked"] = c.AckedTs };
        return new JsonObject
        {
            ["exists"] = true,
            ["problems"] = new JsonArray([.. q.Verify().Select(p => (JsonNode)p)]),
            ["by_status"] = by,
            ["pending_bytes"] = st.PendingBytes,
            ["dead"] = st.Dead,
            ["realtime_dropped"] = st.RealtimeDropped,
            ["alerts"] = new JsonArray([.. q.Alerts().Keys.Order(StringComparer.Ordinal).Select(k => (JsonNode)k)]),
            ["checkpoints"] = cps,
        };
    }

    public static JsonObject FullReport(AgentConfig cfg, CredentialStore creds, IClock clock, BeaconResult? beacon)
    {
        JsonObject credState;
        try
        {
            var cred = creds.Load();
            credState = new JsonObject { ["present"] = cred is not null };
            if (cred is not null)
            {
                credState["agent_id_matches_config"] = cred.AgentId == cfg.AgentId;
                credState["expires_at"] = cred.ExpiresAt?.ToString("O", System.Globalization.CultureInfo.InvariantCulture);
                credState["expired"] = cred.Expired(clock.Now);
            }
        }
        catch (SecretException e)
        {
            credState = new JsonObject { ["readable"] = false, ["error"] = e.GetType().Name };
        }
        var summary = SpecRegistry.Default.Summary();
        var items = new JsonObject();
        foreach (var (k, v) in summary.OrderBy(x => x.Key, StringComparer.Ordinal)) items[k] = v;
        var report = new JsonObject
        {
            ["environment"] = EnvironmentInfo(),
            ["config"] = new JsonObject
            {
                ["environment"] = cfg.Environment,
                ["jvlink_mode"] = cfg.JvlinkMode,
                ["https"] = cfg.BaseUrl.StartsWith("https://", StringComparison.Ordinal),
                ["beacon_keys"] = new JsonArray([.. cfg.Beacon.Keys.Select(k => (JsonNode)k.Kid)]),
            },
            ["credential"] = credState,
            ["disk_free_mb"] = DiskFreeMb(cfg.DataDir),
            ["queue"] = QueueReport(cfg.QueuePath, clock),
            ["com"] = ComRegistrationInfo(),
            ["jvlink_spec"] = new JsonObject { ["confirmed"] = summary.Values.Count(v => v == "CONFIRMED"), ["total"] = summary.Count, ["items"] = items },
        };
        if (beacon is not null)
        {
            var b = new JsonObject { ["status"] = beacon.Status.ToString().ToUpperInvariant(), ["reason"] = beacon.Reason };
            if (beacon.Beacon is { } bb)
            {
                b["mode"] = bb.Mode;
                b["seq"] = bb.Seq;
            }
            report["beacon"] = b;
        }
        return report;
    }
}
