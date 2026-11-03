using System.Text;
using System.Text.Json.Nodes;

namespace JraAgent.Core;

public sealed class TickReport(AgentMode mode, string reason)
{
    public AgentMode Mode { get; set; } = mode;

    public string Reason { get; set; } = reason;

    public int Sent { get; set; }

    public int Fetched { get; set; }

    public List<string> Actions { get; } = [];

    public double NextWake { get; set; } = 5.0;
}

/// <summary>
/// エージェントの 1 周（tick）と常駐ループ（旧 runner.py の移植）。
/// 方針: <b>NORMAL_OK のときだけ</b> plan 取得・取得・送信・heartbeat を行う。STOPPED_CONFIRMED / UNKNOWN では、モード確認（ビーコン + /mode）だけ。
/// STOPPED/UNKNOWN からの復帰は CONTRACTS §7.4 の順序: ① ジッタ（0〜120 秒）待機 → ② plan 取得 → ③ outbox を古い順に最大 2 バッチ/秒で再送 → ④ 空になったら通常取得。
/// 起動直後は必ず UNKNOWN（前回の状態を信用しない）。決定的に動くよう、時計・乱数・待機は注入できる。
/// </summary>
public sealed class AgentRunner(
    OutboxQueue queue,
    ModeMachine modes,
    BeaconClient beacon,
    AgentApi api,
    SignedClient client,
    Sender sender,
    Fetcher? fetcher,
    IClock clock,
    Func<double>? rand = null,
    Func<long?>? diskFreeMb = null,
    Action<string>? logError = null)
{
    public const double HeartbeatSeconds = 60.0;
    public const double PlanRefreshSeconds = 300.0;
    public const double BeaconNormalSeconds = 300.0;
    public const double ResumeJitterMax = 120.0;
    public const int SendBudgetPerTick = 20;

    private readonly Func<double> rand = rand ?? Random.Shared.NextDouble;
    private readonly Func<long?> diskFreeMb = diskFreeMb ?? (() => null);
    private readonly PlanScheduler sched = new();
    private Plan plan = Plan.IdlePlan;
    private double tBeacon = double.NegativeInfinity;
    private double tProbe = double.NegativeInfinity;
    private double tHb = double.NegativeInfinity;
    private double tPlan = double.NegativeInfinity;
    private int unknownPolls;
    private bool wasNormal;
    private double? resumeAt;
    private bool draining;

    public string? LastError { get; private set; }

    // ------------------------------------------------------------ 補助
    private bool Due(double last, double every) => clock.Monotonic - last >= every;

    private void PollBeacon()
    {
        var r = beacon.Fetch();
        tBeacon = clock.Monotonic;
        if (!string.IsNullOrEmpty(r.ServerDate)) client.Skew.Observe(new Dictionary<string, string> { ["date"] = r.ServerDate });
        modes.OnBeacon(r);
        if (r.Status == BeaconStatus.Invalid) queue.RaiseAlert("BEACON_INVALID", $"ビーコンを検証できない（{r.Reason}）。取得も送信も止めている");
        else if (r.Status == BeaconStatus.Valid) queue.ClearAlert("BEACON_INVALID");
    }

    /// <summary>heartbeat の本文（CONTRACTS §3.5。キーはサーバの Heartbeat と同じ。固定語彙と数値だけで、生データ・秘密を含めない）。</summary>
    public byte[] HeartbeatBody(AgentMode mode)
    {
        var st = queue.Stats();
        var body = new JsonObject
        {
            ["agentVersion"] = SignedClient.Version,
            ["queueDepth"] = st.ByStatus.GetValueOrDefault("QUEUED") + st.ByStatus.GetValueOrDefault("RETRY_WAIT") + st.ByStatus.GetValueOrDefault("SENDING"),
            ["mode"] = mode switch { AgentMode.NormalOk => "NORMAL", AgentMode.StoppedConfirmed => "STOPPED", _ => "UNKNOWN" },
        };
        if (client.Skew.LastSkewMs is { } skew) body["clockSkewMs"] = Math.Clamp(skew, -2_000_000_000L, 2_000_000_000L);
        if (st.OldestQueuedAt is { } old) body["oldestQueuedAt"] = Times.IsoZ(DateTimeOffset.FromUnixTimeMilliseconds((long)(old * 1000)));
        var acked = new JsonObject();
        foreach (var c in queue.Checkpoints())
        {
            if (!string.IsNullOrEmpty(c.AckedTs)) acked[c.CursorKey.Replace(':', '.')] = c.AckedTs;
        }
        if (acked.Count > 0) body["ackedTs"] = acked;
        if (fetcher is not null && fetcher.LastJv.Count > 0)
        {
            var jv = new JsonObject();
            foreach (var (k, v) in fetcher.LastJv) jv[k] = v;
            body["lastJvResult"] = jv;
        }
        if (diskFreeMb() is { } free) body["diskFreeMb"] = free;
        var alerts = queue.Alerts();
        if (alerts.Count > 0)
        {
            var names = string.Join(",", alerts.Keys.Order(StringComparer.Ordinal)); // 固定語彙の名前だけ
            body["lastError"] = names.Length <= 200 ? names : names[..200];
        }
        return Encoding.UTF8.GetBytes(body.ToJsonString());
    }

    // ------------------------------------------------------------ 1 周
    public TickReport Tick()
    {
        var view = modes.Evaluate();
        var rep = new TickReport(view.Mode, view.Reason);

        // ---- ビーコン（モードごとの間隔）
        var interval = view.Mode switch
        {
            AgentMode.NormalOk => BeaconNormalSeconds,
            AgentMode.StoppedConfirmed => Backoff.StoppedPollSeconds,
            _ => Backoff.SteppedDelay(Backoff.UnknownPollSteps, unknownPolls + 1, rand: rand),
        };
        if (Due(tBeacon, interval))
        {
            PollBeacon();
            rep.Actions.Add("beacon");
            unknownPolls = modes.Evaluate().Mode == AgentMode.Unknown ? unknownPolls + 1 : 0;
            view = modes.Evaluate();
        }

        // ---- UNKNOWN で NORMAL ビーコンが有効なら、サーバ（/mode）で確認
        if (view.Mode == AgentMode.Unknown && view.Reason == "AWAITING_SERVER_CONFIRMATION" && Due(tProbe, 5.0))
        {
            api.ProbeMode();
            tProbe = clock.Monotonic;
            rep.Actions.Add("probe");
            view = modes.Evaluate();
        }
        if (view.SendPaused && Due(tProbe, 30.0))
        {
            api.ProbeMode(); // 一時障害: 送信を止めたまま、軽い確認だけをバックオフ的に（30 秒おき）
            tProbe = clock.Monotonic;
            view = modes.Evaluate();
        }

        rep.Mode = view.Mode;
        rep.Reason = view.Reason;
        if (view.Mode != AgentMode.NormalOk)
        {
            if (wasNormal)
            {
                resumeAt = null;
                draining = false;
            }
            wasNormal = false;
            if (view.Mode == AgentMode.StoppedConfirmed) plan = Plan.IdlePlan;
            rep.NextWake = view.Mode == AgentMode.Unknown ? 30.0 : 300.0;
            return rep;
        }

        // ---- NORMAL_OK
        if (!wasNormal)
        {
            wasNormal = true;
            resumeAt = clock.Monotonic + (rand() * ResumeJitterMax); // ① ジッタ
            draining = true;
            tPlan = double.NegativeInfinity;
            rep.Actions.Add("resume");
        }
        if (resumeAt is { } ra)
        {
            if (clock.Monotonic < ra)
            {
                rep.NextWake = Math.Min(5.0, ra - clock.Monotonic);
                return rep;
            }
            resumeAt = null;
        }

        if (Due(tHb, HeartbeatSeconds) && !view.SendPaused)
        {
            api.PostHeartbeat(HeartbeatBody(view.Mode));
            tHb = clock.Monotonic;
            rep.Actions.Add("heartbeat");
        }
        if (Due(tPlan, PlanRefreshSeconds) && !view.SendPaused) // ② plan
        {
            var r = api.GetPlan();
            tPlan = clock.Monotonic;
            if (r.Ok)
            {
                using var doc = r.Json();
                plan = doc is null ? Plan.IdlePlan : PlanParser.Parse(doc.RootElement);
            }
            else
            {
                plan = Plan.IdlePlan; // 取得できない・不正 = idle（安全側）
            }
            rep.Actions.Add($"plan:{(plan.Usable(clock.Now) ? plan.Items.Count.ToString(System.Globalization.CultureInfo.InvariantCulture) : "idle")}");
        }

        // ---- ③ 再送（古い順・最大 2 バッチ/秒）
        for (var i = 0; i < SendBudgetPerTick; i++)
        {
            var step = sender.SendNext();
            if (step.Step == SendStep.Acked)
            {
                rep.Sent++;
                continue;
            }
            if (step.Step is SendStep.Retry or SendStep.Dead) continue;
            break;
        }
        var pending = queue.Stats().ByStatus;
        if (draining && pending.GetValueOrDefault("QUEUED") + pending.GetValueOrDefault("RETRY_WAIT") + pending.GetValueOrDefault("SENDING") == 0)
            draining = false; // ④ 空になった = 通常取得へ

        // ---- 取得（再送が空になってから。送信できる状態のときだけ）
        if (fetcher is not null && !draining && modes.MaySend)
        {
            foreach (var item in sched.Due(plan, clock.Now, clock.Monotonic))
            {
                if (!sched.Start(item, clock.Monotonic)) continue;
                FetchReport report;
                try
                {
                    report = item.Realtime ? fetcher.FetchRealtime(item) : fetcher.FetchStored(item);
                }
                finally
                {
                    sched.Finish(item);
                }
                rep.Actions.Add($"fetch:{item.Ident}:{WireNames.Of(report.Result)}");
                if (report.Result == FetchResult.Completed) rep.Fetched += report.Records;
                if (!modes.MayFetch) break;
            }
        }
        rep.NextWake = rep.Sent > 0 || rep.Fetched > 0 ? 1.0 : 5.0;
        return rep;
    }

    /// <summary>常駐。<paramref name="stop"/> が合図されるまで回す（待機は合図で中断する）。</summary>
    public void RunForever(CancellationToken stop)
    {
        queue.Recover();
        while (!stop.IsCancellationRequested)
        {
            double wait;
            try
            {
                wait = Math.Min(Tick().NextWake, 5.0);
            }
            catch (Exception e) when (e is AgentException or IOException or System.Text.Json.JsonException or InvalidOperationException)
            {
                LastError = e.GetType().Name;
                logError?.Invoke($"tick が失敗: {e.GetType().Name}"); // 例外のメッセージには値が入り得るので型名だけ
                wait = 5.0;
            }
            stop.WaitHandle.WaitOne(TimeSpan.FromSeconds(wait));
        }
    }
}
