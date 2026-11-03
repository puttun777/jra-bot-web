using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using JraAgent.Core;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

namespace JraAgent.Core.Tests;

/// <summary>試験用の時計。Sleep は時刻を進めるだけで待たない。</summary>
public sealed class FakeClock(DateTimeOffset? start = null) : IClock
{
    public DateTimeOffset Now { get; private set; } = start ?? new DateTimeOffset(2026, 11, 1, 0, 0, 0, TimeSpan.Zero);

    public double Monotonic { get; private set; } = 1000.0;

    public List<double> Slept { get; } = [];

    /// <summary>待機の後に呼ぶ処理（待機中に状態が変わる場面の再現）。</summary>
    public Action<double>? AfterSleep { get; set; }

    public void Sleep(double seconds)
    {
        Slept.Add(seconds);
        Advance(seconds);
        AfterSleep?.Invoke(seconds);
    }

    public void Advance(double seconds)
    {
        Now = Now.AddSeconds(seconds);
        Monotonic += seconds;
    }

    public void Set(DateTimeOffset when) => Now = when;
}

/// <summary>決められた応答（または例外）を順に返す偽の通信。送った要求を記録する。</summary>
public sealed class ScriptedTransport : ITransport
{
    private readonly Queue<object> script = new();

    public List<(string Method, string Url, IReadOnlyDictionary<string, string> Headers, byte[]? Body)> Sent { get; } = [];

    /// <summary>台本が尽きたときに返す応答（null なら試験の誤りとして例外）。</summary>
    public TransportResponse? Default { get; set; }

    public void Push(params object[] items)
    {
        foreach (var i in items) script.Enqueue(i);
    }

    public TransportResponse Request(string method, string url, IReadOnlyDictionary<string, string> headers, byte[]? body, double timeoutSeconds)
    {
        Sent.Add((method, url, headers, body));
        if (script.Count == 0 && Default is { } d) return d;
        var next = script.Dequeue();
        if (next is Exception e) throw e;
        return (TransportResponse)next;
    }

    public static TransportResponse Resp(int status, byte[]? body = null, Dictionary<string, string>? headers = null) =>
        new(status, headers ?? [], body ?? []);

    /// <summary>JSON の本文と（小文字の）ヘッダ。</summary>
    public static TransportResponse Json(int status, string json, params (string Name, string Value)[] headers) =>
        new(status, headers.ToDictionary(h => h.Name.ToLowerInvariant(), h => h.Value, StringComparer.Ordinal), Encoding.UTF8.GetBytes(json));

    public static string AckBody(bool duplicate = false, string? fetch = null, string? cursor = null, int accepted = 1) =>
        $"{{\"accepted\": {accepted}, \"duplicates\": 0, \"quarantined\": 0, \"unsupported\": 0, \"duplicate\": {(duplicate ? "true" : "false")}, "
        + $"\"ackTs\": \"2026-11-01T00:00:00Z\", \"serverTime\": \"2026-11-01T00:00:00Z\", \"fetch\": {fetch ?? "null"}, \"cursor\": {cursor ?? "null"}}}";
}

/// <summary>試験用ビーコン: 実行時に生成した Ed25519 鍵で署名する（実 GCP のビーコン・実鍵は使わない）。</summary>
public sealed class BeaconSigner
{
    private readonly Ed25519PrivateKeyParameters key;

    public BeaconSigner(string kid = "beacon-test-a")
    {
        Kid = kid;
        var g = new Ed25519KeyPairGenerator();
        g.Init(new Ed25519KeyGenerationParameters(new SecureRandom()));
        key = (Ed25519PrivateKeyParameters)g.GenerateKeyPair().Private;
    }

    public string Kid { get; }

    public BeaconKey Public => new(Kid, key.GeneratePublicKey().GetEncoded());

    public JsonObject Doc(string mode = "NORMAL", long seq = 1, string updatedAt = "2026-11-01T00:00:00Z", string? validUntil = null, string? kid = null)
    {
        var body = new JsonObject
        {
            ["schema_version"] = 1,
            ["mode"] = mode,
            ["seq"] = seq,
            ["updatedAt"] = updatedAt,
            ["validUntil"] = validUntil,
            ["kid"] = kid ?? Kid,
        };
        using var parsed = JsonDocument.Parse(body.ToJsonString());
        var msg = CanonicalJson.Encode(parsed.RootElement);
        var s = new Ed25519Signer();
        s.Init(true, key);
        s.BlockUpdate(msg, 0, msg.Length);
        body["sig"] = Signing.Base64Url(s.GenerateSignature());
        return body;
    }

    public static byte[] Bytes(JsonObject doc) => Encoding.UTF8.GetBytes(doc.ToJsonString());
}

/// <summary>キューの代わりの状態の保存（ビーコンの seq）。</summary>
public sealed class MemoryState
{
    private readonly Dictionary<string, string> values = [];

    public string? Get(string k) => values.GetValueOrDefault(k);

    public void Set(string k, string v) => values[k] = v;
}

public static class Vectors
{
    public static JsonDocument Load(string name) => JsonDocument.Parse(File.ReadAllBytes(Path.Combine(AppContext.BaseDirectory, "vectors", name)));
}

/// <summary>キューの試験の共通部品（旧 tests/helpers.py・conftest.py）。</summary>
public sealed class QueueFixture : IDisposable
{
    public const string Ts = "20261031200000";

    public QueueFixture()
    {
        Dir = Path.Combine(Path.GetTempPath(), "jraagent-test-" + Guid.NewGuid().ToString("N"));
        QPath = Path.Combine(Dir, "q", "queue.sqlite3");
        Clock = new FakeClock();
        Queue = Open();
    }

    public string Dir { get; }

    public string QPath { get; }

    public FakeClock Clock { get; }

    public OutboxQueue Queue { get; set; }

    public OutboxQueue Open(QueueConfig? cfg = null, long free = 1_000_000_000_000) =>
        new(QPath, Clock, cfg ?? new QueueConfig(MinFreeBytes: 0), _ => free);

    public void Dispose()
    {
        Queue.Dispose();
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        try
        {
            Directory.Delete(Dir, recursive: true);
        }
        catch (IOException)
        {
            // 一時ディレクトリの後始末の失敗は試験の結果に影響しない
        }
    }

    public static RawRecord Rec(int i = 0, string rid = "RA") => new(rid, Encoding.ASCII.GetBytes($"{rid}{i:0000}-synthetic"));

    public static string StartSession(OutboxQueue q, string key = "RACE:2", string? ts = Ts, string origin = "LIVE", string? fetchId = null)
    {
        var fid = fetchId ?? Envelope.Uuid7();
        var parts = key.Split(':');
        q.BeginSession(fid, key, parts[0], parts.Length > 1 ? long.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture) : null,
            "20261030000000", ts, "20261101", "20261130", origin, origin == "BACKFILL" ? 7 : null);
        return fid;
    }

    public BatchRow EnqueueStored(OutboxQueue q, string fid, int nRecords = 2, bool eof = false, string key = "RACE:2", string? ts = Ts)
    {
        var s = q.Session(fid) ?? throw new InvalidOperationException("session");
        var parts = key.Split(':');
        var batchId = Envelope.Uuid7();
        var payload = Envelope.Build(
            "ag_test", batchId, parts[0], Clock.Now, [.. Enumerable.Range(0, nRecords).Select(i => Rec(i))], eof,
            parts.Length > 1 ? int.Parse(parts[1], System.Globalization.CultureInfo.InvariantCulture) : null, "20261030000000", ts,
            new FetchInfo(fid, (int)s.NextSeq, "20261101", "20261130", s.Origin, s.BackfillId));
        return q.Enqueue(batchId, "S", parts[0], payload, nRecords, eof, fid, lastFileTimestamp: ts);
    }

    public BatchRow EnqueueRealtime(OutboxQueue q, string dataspec = "0B12", DateTimeOffset? observedAt = null)
    {
        var batchId = Envelope.Uuid7();
        var when = observedAt ?? Clock.Now;
        var payload = Envelope.Build("ag_test", batchId, dataspec, when, [Rec(1, "O1")], true);
        return q.Enqueue(batchId, "R", dataspec, payload, 1, true, observedAt: when.ToUnixTimeMilliseconds() / 1000.0);
    }
}
