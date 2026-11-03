using System.IO.Compression;
using System.Text.Json;

namespace JraAgent.Core;

/// <summary>サーバの <c>Date</c> ヘッダとの差（ミリ秒、正 = ローカルが進んでいる）。応答の遅延を含む概算で、警告と 401 の原因の絞り込みにだけ使う。</summary>
public sealed class SkewMonitor(IClock clock)
{
    public const long WarnMs = 30_000;
    public const long AuthSuspectMs = 240_000; // サーバの許容は ±300 秒

    public long? LastSkewMs { get; private set; }

    public void Observe(IReadOnlyDictionary<string, string> headers)
    {
        if (!headers.TryGetValue("date", out var raw) || string.IsNullOrEmpty(raw)) return;
        if (!DateTimeOffset.TryParseExact(raw, "r", System.Globalization.CultureInfo.InvariantCulture, System.Globalization.DateTimeStyles.AssumeUniversal, out var server)) return;
        LastSkewMs = (long)(clock.Now - server).TotalMilliseconds;
    }

    public bool Suspicious => LastSkewMs is { } s && Math.Abs(s) >= AuthSuspectMs;

    public bool Warn => LastSkewMs is { } s && Math.Abs(s) >= WarnMs;
}

public sealed record ApiResult(Classified Classified, TransportResponse? Response)
{
    public bool Ok => Classified.Outcome == Outcome.Ok;

    /// <summary>本文の JSON（解析できなければ null）。返した文書は呼び出し側が破棄する。</summary>
    public JsonDocument? Json()
    {
        if (Response is null) return null;
        try
        {
            return JsonDocument.Parse(Response.Body);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}

/// <summary>
/// 署名付き HTTP クライアント（旧 client.py の移植）。要求ごとに新しい nonce とタイムスタンプで署名し、応答を分類する。
/// 本文は送信するバイト列（gzip なら圧縮後）をハッシュする。gzip は決定的（ヘッダの時刻 0）なので、同じバッチの再送は毎回同じ本文バイト列になる。
/// 401/403 では自動で再試行しない（呼び出し側が送信を止める）。
/// </summary>
public sealed class SignedClient(string baseUrl, RequestSigner signer, ITransport transport, IClock clock, double timeoutSeconds = 30.0, int gzipMinBytes = 512)
{
    public const string Version = "1.0.0";
    private readonly string baseUrl = baseUrl.TrimEnd('/');

    public SkewMonitor Skew { get; } = new(clock);

    /// <summary>決定的な gzip（.NET の GZipStream はヘッダの MTIME を 0 で書く）。</summary>
    public static byte[] GzipDeterministic(byte[] data)
    {
        using var ms = new MemoryStream();
        using (var gz = new GZipStream(ms, CompressionLevel.Optimal, leaveOpen: true)) gz.Write(data);
        return ms.ToArray();
    }

    private ApiResult Send(string method, string requestUri, byte[] body, Dictionary<string, string> contentHeaders)
    {
        var signed = signer.SignRequest(method, requestUri, body, Times.ToUnix(clock.Now));
        var headers = new Dictionary<string, string>(StringComparer.Ordinal) { ["User-Agent"] = $"jra-agent/{Version}", ["Accept"] = "application/json" };
        foreach (var (k, v) in contentHeaders) headers[k] = v;
        foreach (var (k, v) in signed.AsDictionary()) headers[k] = v;
        TransportResponse resp;
        try
        {
            resp = transport.Request(method, baseUrl + requestUri, headers, method != "GET" || body.Length > 0 ? body : null, timeoutSeconds);
        }
        catch (TransportException e)
        {
            return new ApiResult(new Classified(Outcome.Unreachable, null, e.Kind), null);
        }
        Skew.Observe(resp.Headers);
        return new ApiResult(Responses.Classify(resp), resp);
    }

    public ApiResult Get(string requestUri) => Send("GET", requestUri, [], []);

    /// <summary><paramref name="payload"/> は JSON のバイト列。大きければ gzip する（<c>Content-Encoding: gzip</c>）。</summary>
    public ApiResult PostJson(string requestUri, byte[] payload) =>
        payload.Length >= gzipMinBytes
            ? Send("POST", requestUri, GzipDeterministic(payload), new() { ["Content-Type"] = "application/json", ["Content-Encoding"] = "gzip" })
            : Send("POST", requestUri, payload, new() { ["Content-Type"] = "application/json" });
}
