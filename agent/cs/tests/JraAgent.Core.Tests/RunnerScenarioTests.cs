using System.Globalization;
using System.Text;

namespace JraAgent.Core.Tests;

/// <summary>
/// 通し（Fake サーバ + Fake ビーコン + Fake JV-Link の子プロセス。旧 test_runner_scenarios.py の移植）:
/// 通常取得 → STOPPING → STOPPED → PC 再起動 → NORMAL 復帰 → 再送 → 取得再開。
/// </summary>
public sealed class RunnerScenarioTests : IDisposable
{
    private const string Token = "jrat1_scenario-token-0123456789";
    private const string PlanJson = "{\"planId\": \"p1\", \"validUntil\": null, \"idle\": false, \"items\": [{\"dataspec\": \"RACE\", \"option\": 2, \"interval\": 3600}]}";
    private const string IdlePlan = "{\"planId\": \"p0\", \"validUntil\": null, \"items\": [], \"idle\": true}";
    private static readonly byte[][] Recs = [.. Enumerable.Range(0, 4).Select(i => Encoding.ASCII.GetBytes($"RA{i:0000}-synthetic"))];
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-scn-" + Guid.NewGuid().ToString("N"));
    private readonly FakeClock clock = new();
    private readonly FakeCloud cloud;
    private readonly List<Agent> agents = [];

    public RunnerScenarioTests()
    {
        Directory.CreateDirectory(dir);
        cloud = new FakeCloud(clock, Token, new BeaconSigner()) { Plan = PlanJson };
    }

    public void Dispose()
    {
        foreach (var a in agents) a.Dispose();
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        Directory.Delete(dir, recursive: true);
    }

    /// <summary>1 回の起動（= 1 プロセス）に相当。作り直すと PC 再起動後の起動を模擬する（キューの DB ファイルは同じ）。</summary>
    private sealed class Agent : IDisposable
    {
        private bool disposed;

        public Agent(string dir, FakeCloud cloud, FakeClock clock, FakeScript script, double callTimeout)
        {
            Queue = new OutboxQueue(Path.Combine(dir, "q.sqlite3"), clock, new QueueConfig(MinFreeBytes: 0, MaxBatchRecords: 3), _ => 1_000_000_000_000);
            Recovered = Queue.Recover();
            var sp = Path.Combine(dir, "script.json");
            File.WriteAllText(sp, script.ToJson());
            Sup = new WorkerSupervisor(WorkerTests.WorkerArgv(sp), new WorkerConfig(CallTimeoutSeconds: callTimeout, MaxRestarts: 10), clock);
            Modes = new ModeMachine(clock); // 起動直後は UNKNOWN
            var client = new SignedClient(FakeCloud.Base, new RequestSigner("ag_test", Token), cloud, clock);
            var api = new AgentApi(client, Modes);
            var guard = new BeaconGuard(Queue.GetState, Queue.SetState);
            var beacon = new BeaconClient(FakeCloud.BeaconUrl, [cloud.Signer.Public], cloud, clock, guard);
            var sender = new Sender(Queue, client, Modes, new SenderConfig(), clock, "fp", rand: () => 0.5);
            var fetcher = new Fetcher("ag_test", Queue, Sup, clock, SpecRegistry.AllConfirmedForTests(), new QueueConfig(MinFreeBytes: 0, MaxBatchRecords: 3), () => Modes.MayFetch);
            Runner = new AgentRunner(Queue, Modes, beacon, api, client, sender, fetcher, clock, rand: () => 0.0);
            Clock = clock;
        }

        public OutboxQueue Queue { get; }

        public (int SendingRequeued, int SessionsInterrupted) Recovered { get; }

        public WorkerSupervisor Sup { get; }

        public ModeMachine Modes { get; }

        public AgentRunner Runner { get; }

        private FakeClock Clock { get; }

        public List<TickReport> Run(int ticks, double step = 6.0)
        {
            var output = new List<TickReport>();
            for (var i = 0; i < ticks; i++)
            {
                output.Add(Runner.Tick());
                Clock.Advance(step);
            }
            return output;
        }

        public void Dispose()
        {
            if (disposed) return;
            disposed = true;
            Sup.Stop();
            Queue.Dispose();
        }
    }

    private Agent NewAgent(FakeScript? script = null, double callTimeout = 5.0)
    {
        var a = new Agent(dir, cloud, clock, script ?? new FakeScript { Files = [Recs] }, callTimeout);
        agents.Add(a);
        return a;
    }

    private BatchRow Realtime(OutboxQueue q)
    {
        var batchId = Envelope.Uuid7();
        var payload = Envelope.Build("ag_test", batchId, "0B12", clock.Now, [new RawRecord("O1", "O10001-synthetic"u8.ToArray())], true);
        return q.Enqueue(batchId, "R", "0B12", payload, 1, true, observedAt: clock.Now.ToUnixTimeMilliseconds() / 1000.0);
    }

    private int IngestRequests() => cloud.Requests.Count(r => r.Uri == "/v1/agent/ingest");

    [Fact]
    public void Normal_flow_fetch_send_ack_and_cursor()
    {
        var a = NewAgent();
        Assert.Equal(AgentMode.Unknown, a.Modes.Evaluate().Mode); // 起動直後は UNKNOWN から
        a.Run(9);
        Assert.Equal(AgentMode.NormalOk, a.Modes.Evaluate().Mode);
        Assert.True(cloud.Heartbeats >= 1);
        Assert.Contains(("GET", "/v1/agent/plan"), cloud.Requests);
        Assert.Equal(2, cloud.Batches.Count);
        Assert.Equal(4, cloud.Batches.Values.Sum(b => b["records"]!.AsArray().Count));
        Assert.Equal("20261031200000", a.Queue.GetCheckpoint("RACE:2").AckedTs); // サーバの cursor で前進
        Assert.Equal("20261031200000", cloud.Cursors["RACE:2"]);
        Assert.Empty(a.Queue.Verify());
        Assert.Equal(2, a.Queue.Stats().ByStatus.GetValueOrDefault("ACKED"));
    }

    [Fact]
    public void Idle_plan_fetches_nothing()
    {
        cloud.Plan = IdlePlan;
        var a = NewAgent();
        a.Run(8);
        Assert.Empty(cloud.Batches);
        Assert.False(a.Sup.Started); // ワーカーも起動しない
    }

    [Fact]
    public void Stopping_detected_stop_at_boundary_keep_queue_then_reboot_and_resume()
    {
        var a = NewAgent();
        a.Run(9);
        Assert.Equal(AgentMode.NormalOk, a.Modes.Evaluate().Mode);
        // 速報が溜まった状態で STOPPING → STOPPED
        for (var i = 0; i < 3; i++) Realtime(a.Queue);
        cloud.SetMode("STOPPING");
        var sentBefore = cloud.Batches.Count;
        a.Runner.Tick(); // 次の周期で ingest/ビーコンが STOPPING を知らせる
        a.Run(3);
        cloud.SetMode("STOPPED");
        a.Run(3);
        Assert.Equal(AgentMode.StoppedConfirmed, a.Modes.Evaluate().Mode);
        Assert.False(a.Modes.MayFetch);
        Assert.False(a.Modes.MaySend);
        Assert.InRange(cloud.Batches.Count, sentBefore, sentBefore + 1); // STOPPED になってからは 1 つも増えない
        var frozen = cloud.Batches.Count;
        var pending = a.Queue.Stats().ByStatus.GetValueOrDefault("QUEUED");
        a.Run(5);
        Assert.Equal(frozen, cloud.Batches.Count);
        a.Dispose();

        // ---- PC 再起動: STOPPED のまま自動起動。送信せず待機（古い NORMAL を引きずらない）
        var b = NewAgent();
        Assert.Equal(AgentMode.Unknown, b.Modes.Evaluate().Mode); // 起動直後は UNKNOWN
        Assert.Equal(0, b.Recovered.SendingRequeued);
        b.Run(6);
        Assert.Equal(AgentMode.StoppedConfirmed, b.Modes.Evaluate().Mode);
        Assert.Equal(frozen, cloud.Batches.Count);
        Assert.True(b.Queue.Stats().ByStatus.GetValueOrDefault("QUEUED") >= pending); // キューは保持
        var n = IngestRequests();
        b.Run(10);
        Assert.Equal(n, IngestRequests()); // 送信を試みない

        // ---- NORMAL に戻る → 状態再判定 → 未送信キューを再送 → 新規取得再開
        cloud.SetMode("NORMAL");
        clock.Advance(3600); // STOPPED の確認間隔（60 分）
        b.Run(30);
        Assert.Equal(AgentMode.NormalOk, b.Modes.Evaluate().Mode);
        Assert.Equal(0, b.Queue.Stats().ByStatus.GetValueOrDefault("QUEUED"));
        Assert.Equal(0, b.Queue.Stats().ByStatus.GetValueOrDefault("RETRY_WAIT"));
        Assert.Contains(cloud.Batches.Values, x => x["class"]!.GetValue<string>() == "R"); // 溜めた速報が届いた
        Assert.Empty(b.Queue.Verify());
    }

    [Fact]
    public void Stopped_beacon_blocks_even_if_server_would_accept()
    {
        cloud.SetMode("STOPPED");
        var a = NewAgent();
        Realtime(a.Queue);
        a.Run(6);
        Assert.Equal(AgentMode.StoppedConfirmed, a.Modes.Evaluate().Mode);
        Assert.Equal(0, IngestRequests());
        Assert.False(a.Modes.MayFetch);
        Assert.False(a.Sup.Started); // JVOpen を呼ばない（ワーカーも起動しない）
    }

    [Fact]
    public void Unknown_when_nothing_reachable_then_recovers()
    {
        cloud.Plan = IdlePlan;
        cloud.BeaconReachable = false;
        cloud.IngestReachable = false;
        var a = NewAgent();
        Realtime(a.Queue);
        a.Run(5, step: 60.0);
        Assert.Equal(AgentMode.Unknown, a.Modes.Evaluate().Mode);
        Assert.Equal(1, a.Queue.Stats().ByStatus.GetValueOrDefault("QUEUED"));
        Assert.False(a.Modes.MaySend);
        cloud.BeaconReachable = true;
        cloud.IngestReachable = true;
        clock.Advance(4000);
        a.Run(30);
        Assert.Equal(AgentMode.NormalOk, a.Modes.Evaluate().Mode);
        Assert.Equal(1, a.Queue.Stats().ByStatus.GetValueOrDefault("ACKED"));
    }

    [Fact]
    public void Replayed_old_normal_beacon_after_stop_is_rejected()
    {
        var a = NewAgent();
        a.Run(6);
        var old = cloud.Signer.Doc("NORMAL", cloud.BeaconSeq, clock.Now.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture));
        cloud.SetMode("STOPPED");
        a.Run(2, step: 3700.0);
        Assert.Equal(AgentMode.StoppedConfirmed, a.Modes.Evaluate().Mode);
        cloud.StaleBeaconDoc = old; // 攻撃者/キャッシュが古い NORMAL（署名は有効）を返す
        a.Run(2, step: 3700.0);
        Assert.NotEqual(AgentMode.NormalOk, a.Modes.Evaluate().Mode);
        Assert.Contains("BEACON_INVALID", a.Queue.Alerts().Keys);
    }

    [Fact]
    public void Disconnect_right_before_ack_resends_same_batch_and_cursor_via_duplicate()
    {
        var a = NewAgent(new FakeScript { Files = [Recs[..2]] });
        a.Run(4);
        cloud.LoseNextAck = true; // 最初のバッチは保存されるが ACK が届かない
        a.Run(12);
        Assert.NotEmpty(a.Queue.Batches());
        Assert.All(a.Queue.Batches(), b => Assert.Equal("ACKED", b.Status)); // 同じ batchId の再送は二重登録にならない（サーバが重複で吸収）
        Assert.Equal("20261031200000", a.Queue.GetCheckpoint("RACE:2").AckedTs);
    }

    [Fact]
    public void Worker_hang_during_scenario_recovers_next_cycle()
    {
        var a = NewAgent(new FakeScript { Files = [Recs], HangAfter = 2 }, callTimeout: 1.0);
        a.Run(9);
        Assert.True(a.Sup.Timeouts >= 1);
        Assert.Null(a.Queue.GetCheckpoint("RACE:2").AckedTs);
        Assert.Null(a.Queue.GetCheckpoint("RACE:2").FetchedTs);
        Assert.Equal(AgentMode.NormalOk, a.Modes.Evaluate().Mode); // ワーカーの問題でモードや送信は止まらない
    }

    [Fact]
    public void Heartbeat_body_has_only_contract_keys()
    {
        var a = NewAgent();
        a.Run(9);
        using var doc = System.Text.Json.JsonDocument.Parse(a.Runner.HeartbeatBody(AgentMode.NormalOk));
        string[] allowed = ["agentVersion", "clockSkewMs", "queueDepth", "oldestQueuedAt", "ackedTs", "lastJvResult", "diskFreeMb", "lastError", "mode"];
        Assert.All(doc.RootElement.EnumerateObject(), p => Assert.Contains(p.Name, allowed));
        Assert.Equal("NORMAL", doc.RootElement.GetProperty("mode").GetString());
        Assert.Equal("20261031200000", doc.RootElement.GetProperty("ackedTs").GetProperty("RACE.2").GetString());
    }
}
