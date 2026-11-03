using System.Text;
using System.Text.Json.Nodes;
using JraAgent.Core;

namespace JraAgent.Interop;

/// <summary>
/// Kotlin ingest との結合試験用ドライバ（旧 agent/interop/driver.py の移植）。
/// エージェントの本物の部品（署名クライアント・SQLite キュー・Sender・状態機械・HttpTransport）を、実際の HTTP で Kotlin の ingest に接続する。
/// トークンは標準入力から受け取る（引数・環境変数・ログに出さない）。レコードは合成の正規化 JSON（サーバのローカル専用パーサ）。結果は集計 JSON を標準出力へ。
/// 使い方: <c>jraagent-interop &lt;base_url&gt; &lt;agent_id&gt; normal|stopped</c>
/// </summary>
internal static class Program
{
    private const string Ts = "20261031200000";

    /// <summary>要求はサーバへ届くが、応答を受け取る前に切断された状態を 1 回だけ作る。</summary>
    private sealed class LoseAckOnce(ITransport inner) : ITransport
    {
        public bool Armed { get; set; }

        public TransportResponse Request(string method, string url, IReadOnlyDictionary<string, string> headers, byte[]? body, double timeoutSeconds)
        {
            var resp = inner.Request(method, url, headers, body, timeoutSeconds);
            if (Armed && url.EndsWith("/v1/agent/ingest", StringComparison.Ordinal))
            {
                Armed = false;
                throw new TransportException("CONNECTION");
            }
            return resp;
        }
    }

    private static RawRecord Nys(string date) =>
        new("NYS", Encoding.UTF8.GetBytes(new JsonObject { ["date"] = date, ["venue"] = "05", ["kai"] = 3, ["nichime"] = 1, ["status"] = "PLANNED", ["replacedBy"] = null }.ToJsonString()));

    private static string Wire(SendStep s) => s.ToString().ToUpperInvariant();

    private static int Main(string[] args)
    {
        if (args.Length != 3)
        {
            Console.Error.WriteLine("usage: jraagent-interop <base_url> <agent_id> normal|stopped");
            return 2;
        }
        var (baseUrl, agentId, phase) = (args[0], args[1], args[2]);
        var token = Console.In.ReadLine()?.Trim() ?? "";
        var clock = new SystemClock();
        var output = new JsonObject { ["phase"] = phase };
        var dir = Path.Combine(Path.GetTempPath(), "jraagent-interop-" + Guid.NewGuid().ToString("N"));
        try
        {
            using var q = new OutboxQueue(Path.Combine(dir, "q.sqlite3"), clock, new QueueConfig(MinFreeBytes: 0));
            var modes = new ModeMachine(clock);
            using var http = new HttpTransport();
            var transport = new LoseAckOnce(http);
            var client = new SignedClient(baseUrl, new RequestSigner(agentId, token), transport, clock);
            var api = new AgentApi(client, modes);
            modes.OnBeacon(new BeaconResult(BeaconStatus.Valid, new Beacon("NORMAL", 1, "k", DateTimeOffset.UtcNow, null))); // ビーコンは Mock（実 GCP には接続しない）
            output["probe_mode"] = api.ProbeMode().Mode;
            var sender = new Sender(q, client, modes, new SenderConfig(SendsPerSecond: 10.0), clock, "fp");
            if (phase == "stopped")
            {
                var bid = Envelope.Uuid7();
                q.Enqueue(bid, "R", "0B12", Envelope.Build(agentId, bid, "0B12", clock.Now, [Nys("20261101")], true), 1, true);
                var step = sender.SendNext();
                output["step"] = Wire(step.Step);
                output["mode"] = WireNames.Of(modes.Evaluate().Mode);
                var queued = new JsonObject();
                foreach (var (k, v) in q.Stats().ByStatus) queued[k] = v;
                output["queued"] = queued;
                Console.Out.WriteLine(output.ToJsonString());
                return 0;
            }
            // ---- normal: plan / heartbeat / 取得セッション 3 バッチ（最後の ACK を失う）
            var planRes = api.GetPlan();
            using (var doc = planRes.Json())
            {
                var plan = doc is null ? Plan.IdlePlan : PlanParser.Parse(doc.RootElement);
                output["plan_status"] = planRes.Classified.Status;
                output["plan_items"] = plan.Items.Count;
                output["plan_idle"] = !plan.Usable(clock.Now);
            }
            var hb = new JsonObject { ["agentVersion"] = SignedClient.Version, ["queueDepth"] = 0, ["mode"] = "NORMAL", ["ackedTs"] = new JsonObject { ["RACE.2"] = Ts } };
            output["heartbeat"] = api.PostHeartbeat(Encoding.UTF8.GetBytes(hb.ToJsonString())).Classified.Status;
            var fid = Envelope.Uuid7();
            q.BeginSession(fid, "YSCH:2", "YSCH", 2, "20261030000000", Ts, "20261101", "20261130");
            for (var i = 0; i < 3; i++)
            {
                var s = q.Session(fid) ?? throw new InvalidOperationException("session");
                var bid = Envelope.Uuid7();
                var eof = i == 2;
                var payload = Envelope.Build(agentId, bid, "YSCH", clock.Now, [Nys($"202611{i + 1:00}")], eof, 2, "20261030000000", Ts, new FetchInfo(fid, (int)s.NextSeq, "20261101", "20261130"));
                q.Enqueue(bid, "S", "YSCH", payload, 1, eof, fid, lastFileTimestamp: Ts);
            }
            var steps = new JsonArray { Wire(sender.SendNext().Step), Wire(sender.SendNext().Step) };
            transport.Armed = true; // 最後（EOF）のバッチ: サーバは保存するが ACK が届かない
            steps.Add(Wire(sender.SendNext().Step));
            output["acked_ts_after_lost_ack"] = q.GetCheckpoint("YSCH:2").AckedTs;
            modes.OnServer(new Classified(Outcome.Ok, 200));
            output["retry_scheduled"] = q.NextEligibleAt() is not null;
            q.ExecRawForInterop("UPDATE batch SET next_try_at = 0"); // 再送時刻まで待つ代わりに、キューの待機を解除して同じバッチを再送する
            var r = sender.SendNext();
            steps.Add(Wire(r.Step));
            output["steps"] = steps;
            output["final_detail"] = r.Detail;
            output["acked_ts"] = q.GetCheckpoint("YSCH:2").AckedTs;
            output["session_state"] = q.Session(fid)?.State;
            output["problems"] = new JsonArray([.. q.Verify().Select(p => (JsonNode)p)]);
            output["all_acked"] = q.Batches().All(b => b.Status == "ACKED") && r.Step == SendStep.Acked;
            output["mode"] = modes.Evaluate().Mode == AgentMode.NormalOk;
        }
        finally
        {
            Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
            if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
        }
        Console.Out.WriteLine(output.ToJsonString());
        return 0;
    }
}
