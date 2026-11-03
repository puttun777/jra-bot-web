using System.Text.Json;

namespace JraAgent.Core;

public enum AgentMode
{
    Unknown,
    NormalOk,
    StoppedConfirmed,
}

public sealed record ModeView(AgentMode Mode, string Reason, bool SendPaused = false);

/// <summary>
/// エージェントの状態機械（CONTRACTS §7.3。旧 modes.py の移植）。UNKNOWN / NORMAL_OK / STOPPED_CONFIRMED。
/// <b>安全側の原則</b>: NORMAL が確認できない限り、取得も送信もしない。起動直後は必ず UNKNOWN。判定は観測の組合せから毎回導出する。
/// </summary>
public sealed class ModeMachine(IClock clock, double beaconMaxAgeSeconds = 6 * 3600)
{
    public const int UnknownAfterIngestFailures = 3;
    public const double TransientToUnknownSeconds = 30 * 60;
    public const double StoppedStaleSeconds = 6 * 3600;

    private BeaconStatus? beaconStatus;
    private string? beaconMode;
    private double? beaconValidUntil;
    private double? beaconOkAt;
    private string? beaconInvalidReason;
    private double? serverOkAt;
    private int ingestFailures;
    private double? ingestFailingSince;
    private double? hintNonNormalAt;
    private string? hintMode;

    public void OnBeacon(BeaconResult result)
    {
        var now = clock.Monotonic;
        beaconStatus = result.Status;
        if (result.Status == BeaconStatus.Valid && result.Beacon is { } b)
        {
            beaconMode = b.Mode;
            beaconOkAt = now;
            beaconInvalidReason = null;
            beaconValidUntil = b.ValidUntil is { } vu ? clock.Monotonic + (vu - clock.Now).TotalSeconds : null;
            if (!b.Normal)
            {
                serverOkAt = null; // 止まった後の復帰には、NORMAL ビーコンの後の新しい 200 が要る
            }
            else if (hintNonNormalAt is { } h && h <= now)
            {
                hintNonNormalAt = null; // より新しい有効な NORMAL ビーコン → 古い 503 の印は外す（サーバの 200 は別途必要）
                hintMode = null;
                serverOkAt = null;
            }
        }
        else if (result.Status == BeaconStatus.Invalid)
        {
            beaconInvalidReason = result.Reason;
            beaconMode = null; // 古い NORMAL を引きずらない
            serverOkAt = null;
        }
    }

    /// <summary>ingest / mode / plan / heartbeat の応答。</summary>
    public void OnServer(Classified c)
    {
        var now = clock.Monotonic;
        switch (c.Outcome)
        {
            case Outcome.Ok:
                serverOkAt = now;
                ingestFailures = 0;
                ingestFailingSince = null;
                hintNonNormalAt = null;
                hintMode = null;
                break;
            case Outcome.OpsNotNormal:
                hintNonNormalAt = now;
                hintMode = c.OpsMode;
                serverOkAt = null;
                ingestFailures = 0;
                ingestFailingSince = null;
                break;
            case Outcome.OpsUnknown or Outcome.Unreachable or Outcome.TransientDb or Outcome.ServerError:
                ingestFailures++;
                ingestFailingSince ??= now;
                break;
            default:
                // 400/409/429/401/403 などは「サーバは生きて応答した」= モードの判定材料にしない
                break;
        }
    }

    public ModeView Evaluate()
    {
        var now = clock.Monotonic;
        if (beaconStatus == BeaconStatus.Invalid) return new ModeView(AgentMode.Unknown, $"BEACON_INVALID:{beaconInvalidReason}");
        if (hintNonNormalAt is not null) return new ModeView(AgentMode.StoppedConfirmed, $"INGEST_503:{hintMode}");
        if (beaconMode is null || beaconOkAt is not { } okAt) return new ModeView(AgentMode.Unknown, "NO_BEACON");
        if (beaconMode != "NORMAL")
        {
            return now - okAt > StoppedStaleSeconds
                ? new ModeView(AgentMode.Unknown, "STOPPED_BEACON_STALE")
                : new ModeView(AgentMode.StoppedConfirmed, $"BEACON:{beaconMode}");
        }
        if (now - okAt > beaconMaxAgeSeconds) return new ModeView(AgentMode.Unknown, "BEACON_STALE");
        if (beaconValidUntil is { } vu && now > vu) return new ModeView(AgentMode.Unknown, "BEACON_EXPIRED");
        var beaconReachable = beaconStatus == BeaconStatus.Valid;
        if (ingestFailures >= UnknownAfterIngestFailures && !beaconReachable) return new ModeView(AgentMode.Unknown, "BEACON_UNREACHABLE_AND_INGEST_FAILING");
        if (ingestFailingSince is { } fs && now - fs >= TransientToUnknownSeconds) return new ModeView(AgentMode.Unknown, "INGEST_FAILING_30M");
        if (serverOkAt is not { } sok || now - sok > beaconMaxAgeSeconds) return new ModeView(AgentMode.Unknown, "AWAITING_SERVER_CONFIRMATION");
        if (ingestFailures > 0) return new ModeView(AgentMode.NormalOk, "INGEST_TRANSIENT", SendPaused: true);
        return new ModeView(AgentMode.NormalOk, "BEACON_NORMAL_AND_SERVER_OK");
    }

    public bool MayFetch => Evaluate().Mode == AgentMode.NormalOk;

    public bool MaySend
    {
        get
        {
            var v = Evaluate();
            return v.Mode == AgentMode.NormalOk && !v.SendPaused;
        }
    }
}

public sealed record ModeProbe(bool Reachable, string? Mode, ApiResult Result);

/// <summary>エージェント API（plan / mode / heartbeat / update）の呼出し。応答はモードの状態機械へ渡す（旧 api.py の移植）。</summary>
public sealed class AgentApi(SignedClient client, ModeMachine modes)
{
    /// <summary><c>GET /v1/agent/mode</c>。200 はサーバが応答したことを示すが、本文の mode が NORMAL のときだけ NORMAL の確認になる。</summary>
    public ModeProbe ProbeMode()
    {
        var r = client.Get("/v1/agent/mode");
        string? mode = null;
        if (r.Ok)
        {
            using var doc = r.Json();
            if (doc is not null && doc.RootElement.ValueKind == JsonValueKind.Object && doc.RootElement.TryGetProperty("mode", out var m) && m.ValueKind == JsonValueKind.String)
            {
                mode = m.GetString();
            }
            if (mode == "NORMAL") modes.OnServer(r.Classified);
            else if (mode is not null) modes.OnServer(new Classified(Outcome.OpsNotNormal, 200, null, null, mode));
        }
        else
        {
            modes.OnServer(r.Classified);
        }
        return new ModeProbe(r.Classified.Outcome != Outcome.Unreachable, mode, r);
    }

    public ApiResult GetPlan() => Observe(client.Get("/v1/agent/plan"));

    public ApiResult PostHeartbeat(byte[] reportJson) => Observe(client.PostJson("/v1/agent/heartbeat", reportJson));

    public ApiResult GetUpdate() => Observe(client.Get("/v1/agent/update"));

    private ApiResult Observe(ApiResult r)
    {
        modes.OnServer(r.Classified);
        return r;
    }
}
