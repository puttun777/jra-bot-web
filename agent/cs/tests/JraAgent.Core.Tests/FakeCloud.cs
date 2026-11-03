using System.Globalization;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;

namespace JraAgent.Core.Tests;

/// <summary>
/// 試験用の「サーバ + ビーコン」（旧 tests/fake_server.py の移植）。サーバ契約（署名・冪等・取得セッションの連番・カーソル・503+X-Ops-Mode）を、
/// エージェントの実装を使わずに再現する。本物の Kotlin ingest との結合は :ingest の AgentInteropIntegrationTest が行う。
/// </summary>
public sealed class FakeCloud(FakeClock clock, string token, BeaconSigner signer) : ITransport
{
    public const string BeaconUrl = "https://beacon.example/mode.json";
    public const string Base = "https://ingest.example";

    private readonly HashSet<string> nonces = [];
    private readonly Dictionary<string, (long Next, string Status, string? Last)> fetches = [];

    public BeaconSigner Signer => signer;

    public string Mode { get; set; } = "NORMAL";

    public long BeaconSeq { get; private set; } = 1;

    public bool BeaconReachable { get; set; } = true;

    public bool IngestReachable { get; set; } = true;

    public string Plan { get; set; } = "{\"planId\": \"p1\", \"validUntil\": null, \"items\": [], \"idle\": true}";

    public bool LoseNextAck { get; set; }

    public Dictionary<string, JsonObject> Batches { get; } = [];

    public List<(string Method, string Uri)> Requests { get; } = [];

    public Dictionary<string, string> Cursors { get; } = [];

    public int Heartbeats { get; private set; }

    public JsonObject? StaleBeaconDoc { get; set; }

    public void SetMode(string mode)
    {
        Mode = mode;
        BeaconSeq++;
    }

    private static TransportResponse J(int status, string json, string date, params (string, string)[] h)
    {
        var headers = new Dictionary<string, string>(StringComparer.Ordinal) { ["date"] = date };
        foreach (var (k, v) in h) headers[k] = v;
        return new TransportResponse(status, headers, Encoding.UTF8.GetBytes(json));
    }

    private bool Auth(string method, string uri, IReadOnlyDictionary<string, string> headers, byte[] body)
    {
        var key = HMACSHA256.HashData(Encoding.UTF8.GetBytes(token), "jra-bot/agent-sign/v1"u8);
        var ts = long.Parse(headers["X-Timestamp"], CultureInfo.InvariantCulture);
        var nonce = headers["X-Nonce"];
        if (Math.Abs(clock.Now.ToUnixTimeSeconds() - ts) > 300 || nonces.Contains(nonce)) return false;
        var expect = Convert.ToHexStringLower(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes($"{method}|{uri}|{ts}|{nonce}|{Convert.ToHexStringLower(SHA256.HashData(body))}")));
        if (expect != headers["X-Signature"]) return false;
        nonces.Add(nonce);
        return true;
    }

    public TransportResponse Request(string method, string url, IReadOnlyDictionary<string, string> headers, byte[]? body, double timeoutSeconds)
    {
        var date = clock.Now.ToString("r", CultureInfo.InvariantCulture);
        if (url == BeaconUrl)
        {
            if (!BeaconReachable) throw new TransportException("CONNECTION");
            if (StaleBeaconDoc is not null) return new TransportResponse(200, new Dictionary<string, string> { ["date"] = date }, BeaconSigner.Bytes(StaleBeaconDoc));
            return new TransportResponse(200, new Dictionary<string, string> { ["date"] = date }, BeaconSigner.Bytes(signer.Doc(Mode, BeaconSeq, clock.Now.ToString("yyyy-MM-dd'T'HH:mm:ss'Z'", CultureInfo.InvariantCulture))));
        }
        Assert.StartsWith(Base, url, StringComparison.Ordinal);
        var uri = url[Base.Length..];
        Requests.Add((method, uri.Split('?')[0]));
        if (!IngestReachable) throw new TransportException("TIMEOUT");
        if (uri == "/v1/agent/mode") return J(200, $"{{\"mode\": \"{Mode}\", \"seq\": {BeaconSeq}}}", date); // /mode は NORMAL 以外でも 200 で名乗る
        if (Mode != "NORMAL") return J(503, "{\"error\": \"OPS_MODE_NOT_NORMAL\"}", date, ("x-ops-mode", Mode));
        var raw = body ?? [];
        if (!Auth(method, uri, headers, raw)) return J(401, "{\"error\": \"UNAUTHORIZED\"}", date);
        switch (uri)
        {
            case "/v1/agent/plan":
                return J(200, Plan, date);
            case "/v1/agent/heartbeat":
                Heartbeats++;
                using (var hb = JsonDocument.Parse(raw)) Assert.Equal(JsonValueKind.Object, hb.RootElement.ValueKind);
                return J(200, "{\"serverTime\": \"x\"}", date);
            case "/v1/agent/ingest":
                return Ingest(headers, raw, date);
            default:
                return J(404, "{\"error\": \"NOT_FOUND\"}", date);
        }
    }

    private static string Ack(int accepted, int duplicates, bool duplicate, JsonNode? fetch, JsonNode? cursor) =>
        new JsonObject
        {
            ["accepted"] = accepted, ["duplicates"] = duplicates, ["quarantined"] = 0, ["unsupported"] = 0, ["duplicate"] = duplicate,
            ["ackTs"] = "t", ["serverTime"] = "t", ["fetch"] = fetch, ["cursor"] = cursor,
        }.ToJsonString();

    private TransportResponse Ingest(IReadOnlyDictionary<string, string> headers, byte[] raw, string date)
    {
        var data = raw;
        if (headers.TryGetValue("Content-Encoding", out var enc) && enc == "gzip")
        {
            using var gz = new GZipStream(new MemoryStream(raw), CompressionMode.Decompress);
            using var o = new MemoryStream();
            gz.CopyTo(o);
            data = o.ToArray();
        }
        var env = JsonNode.Parse(data)!.AsObject();
        var bid = env["batchId"]!.GetValue<string>();
        var nrec = env["records"]!.AsArray().Count;
        if (Batches.ContainsKey(bid)) return J(200, Ack(0, nrec, true, null, null), date);
        JsonNode? progress = null;
        JsonNode? cursor = null;
        if (env["fetch"] is JsonObject fetch)
        {
            var fid = fetch["id"]!.GetValue<string>();
            var f = fetches.TryGetValue(fid, out var cur) ? cur : (0, "IN_PROGRESS", null);
            var seq = fetch["seq"]!.GetValue<long>();
            if (seq > f.Next) return J(409, "{\"error\": \"FETCH_SEQ_AHEAD\"}", date, ("retry-after", "5"));
            f.Next = seq + 1;
            f.Last = env["lastFileTimestamp"]?.GetValue<string>() ?? f.Last;
            if (env["eof"]!.GetValue<bool>())
            {
                f.Status = "SUCCEEDED";
                var key = env["dataspec"]!.GetValue<string>() + (env["option"] is JsonValue ov ? ":" + ov.GetValue<int>().ToString(CultureInfo.InvariantCulture) : "");
                var origin = fetch["origin"]?.GetValue<string>() ?? "LIVE";
                if (f.Last is not null && origin == "LIVE" && string.CompareOrdinal(f.Last, Cursors.GetValueOrDefault(key, "")) > 0)
                {
                    Cursors[key] = f.Last;
                    cursor = new JsonObject { ["dataType"] = key, ["lastFileTimestamp"] = f.Last };
                }
            }
            fetches[fid] = f;
            progress = new JsonObject { ["id"] = fid, ["status"] = f.Status, ["nextSeq"] = f.Next };
        }
        Batches[bid] = env;
        if (LoseNextAck)
        {
            LoseNextAck = false;
            throw new TransportException("CONNECTION"); // サーバは保存済み。ACK 直前に切断
        }
        return J(200, Ack(nrec, 0, false, progress, cursor), date);
    }
}
