namespace JraAgent.Core;

/// <summary>HTTP の応答（ヘッダのキーは小文字）。</summary>
public sealed record TransportResponse(int Status, IReadOnlyDictionary<string, string> Headers, byte[] Body);

/// <summary>HTTP 通信の下層（試験では偽物に差し替える）。リダイレクトは追わない（署名した要求を別の宛先へ送らない）。</summary>
public interface ITransport
{
    /// <summary>通信の失敗は <see cref="TransportException"/>（TIMEOUT / TLS / CONNECTION / INSECURE_URL）。HTTP のエラー状態は応答として返す。</summary>
    TransportResponse Request(string method, string url, IReadOnlyDictionary<string, string> headers, byte[]? body, double timeoutSeconds);
}

/// <summary>
/// <see cref="HttpClient"/> による実装。TLS は既定の検証（証明書・ホスト名）。リダイレクトは追わず 3xx をそのまま返す。応答本文は上限つきで読む。
/// 平文 HTTP は開発用のループバックだけ（設定の検証と二重に確認する）。
/// </summary>
public sealed class HttpTransport : ITransport, IDisposable
{
    public const int MaxResponseBytes = 1024 * 1024;
    private readonly HttpClient client = new(new SocketsHttpHandler { AllowAutoRedirect = false, AutomaticDecompression = System.Net.DecompressionMethods.None }) { Timeout = Timeout.InfiniteTimeSpan };

    public static bool IsAllowedUrl(string url) =>
        url.StartsWith("https://", StringComparison.Ordinal) || url.StartsWith("http://127.0.0.1", StringComparison.Ordinal)
        || url.StartsWith("http://localhost", StringComparison.Ordinal) || url.StartsWith("http://[::1]", StringComparison.Ordinal);

    public TransportResponse Request(string method, string url, IReadOnlyDictionary<string, string> headers, byte[]? body, double timeoutSeconds)
    {
        if (!IsAllowedUrl(url)) throw new TransportException("INSECURE_URL");
        using var req = new HttpRequestMessage(new HttpMethod(method), url);
        if (body is not null) req.Content = new ByteArrayContent(body);
        foreach (var (k, v) in headers)
        {
            if (k.StartsWith("Content-", StringComparison.OrdinalIgnoreCase))
            {
                req.Content ??= new ByteArrayContent([]);
                req.Content.Headers.TryAddWithoutValidation(k, v);
            }
            else
            {
                req.Headers.TryAddWithoutValidation(k, v);
            }
        }
        using var cts = new CancellationTokenSource(TimeSpan.FromSeconds(timeoutSeconds));
        try
        {
            using var resp = client.Send(req, HttpCompletionOption.ResponseHeadersRead, cts.Token);
            var h = new Dictionary<string, string>(StringComparer.Ordinal);
            foreach (var kv in resp.Headers.Concat(resp.Content.Headers)) h[kv.Key.ToLowerInvariant()] = string.Join(", ", kv.Value);
            using var stream = resp.Content.ReadAsStream(cts.Token);
            var buf = new byte[MaxResponseBytes];
            var n = 0;
            int r;
            while (n < buf.Length && (r = stream.Read(buf, n, buf.Length - n)) > 0) n += r;
            return new TransportResponse((int)resp.StatusCode, h, buf[..n]);
        }
        catch (OperationCanceledException)
        {
            throw new TransportException("TIMEOUT");
        }
        catch (HttpRequestException e) when (e.InnerException is System.Security.Authentication.AuthenticationException)
        {
            throw new TransportException("TLS");
        }
        catch (HttpRequestException)
        {
            throw new TransportException("CONNECTION");
        }
        catch (IOException)
        {
            throw new TransportException("CONNECTION");
        }
    }

    public void Dispose() => client.Dispose();
}
