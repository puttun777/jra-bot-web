using System.Security.Cryptography;
using System.Text;

namespace JraAgent.Core;

/// <summary>
/// リクエスト署名（CONTRACTS §3.3 / P-1。サーバの <c>AgentSigning</c> と同じ定義。既知のテストベクトル: agent/tests/vectors/signing_vectors.json）。
/// <list type="bullet">
/// <item>署名鍵 <c>K = HMAC-SHA256(token, "jra-bot/agent-sign/v1")</c></item>
/// <item><c>X-Signature = hex(HMAC-SHA256(K, "method|requestUri|timestamp|nonce|sha256hex(送信した本文のバイト列)"))</c>。requestUri はクエリを含む。本文は送信するバイト列そのもの（gzip なら圧縮後）</item>
/// </list>
/// トークン自体は送らず、保持もしない（鍵だけを持つ）。
/// </summary>
public static class Signing
{
    public const string DeriveLabel = "jra-bot/agent-sign/v1";

    public static byte[] DeriveSigningKey(string token) => HMACSHA256.HashData(Encoding.UTF8.GetBytes(token), Encoding.UTF8.GetBytes(DeriveLabel));

    public static string Canonical(string method, string requestUri, long timestamp, string nonce, string bodySha256Hex) =>
        $"{method}|{requestUri}|{timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture)}|{nonce}|{bodySha256Hex}";

    public static string Sha256Hex(ReadOnlySpan<byte> data) => Convert.ToHexStringLower(SHA256.HashData(data));

    public static string Sign(byte[] signingKey, string canonical) => Convert.ToHexStringLower(HMACSHA256.HashData(signingKey, Encoding.UTF8.GetBytes(canonical)));

    /// <summary>24 バイトの乱数を URL セーフ Base64（パディングなし）にした 32 文字。毎回新しく作る（再利用すると 401）。</summary>
    public static string NewNonce() => Base64Url(RandomNumberGenerator.GetBytes(24));

    public static string Base64Url(ReadOnlySpan<byte> data) => Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

    /// <summary>パディング無しの base64url だけを受け付ける（旧 Python の urlsafe_b64decode は字母外の文字を黙って捨てていたが、ここでは拒否する）。</summary>
    public static byte[] Base64UrlDecode(string text)
    {
        foreach (var ch in text)
        {
            if (!(ch is >= 'A' and <= 'Z' or >= 'a' and <= 'z' or >= '0' and <= '9' or '-' or '_')) throw new FormatException("base64url 以外の文字");
        }
        if (text.Length % 4 == 1) throw new FormatException("base64url の長さが不正");
        var s = text.Replace('-', '+').Replace('_', '/');
        s = s.PadRight(s.Length + ((4 - (s.Length % 4)) % 4), '=');
        return Convert.FromBase64String(s);
    }
}

public sealed record SignedHeaders(string AgentId, long Timestamp, string Nonce, string Signature)
{
    public IReadOnlyDictionary<string, string> AsDictionary() =>
        new Dictionary<string, string>
        {
            ["X-Agent-Id"] = AgentId,
            ["X-Timestamp"] = Timestamp.ToString(System.Globalization.CultureInfo.InvariantCulture),
            ["X-Nonce"] = Nonce,
            ["X-Signature"] = Signature,
        };

    /// <summary>署名・nonce をログに出さない。</summary>
    public override string ToString() => $"SignedHeaders(agent_id={AgentId}, timestamp={Timestamp})";
}

/// <summary>トークンから導いた署名鍵を保持して要求に署名する。トークン自体は保持しない。</summary>
public sealed class RequestSigner(string agentId, string token, Func<string>? nonceFactory = null)
{
    private readonly byte[] key = Signing.DeriveSigningKey(token);
    private readonly Func<string> nonceFactory = nonceFactory ?? Signing.NewNonce;

    public string AgentId { get; } = agentId;

    public SignedHeaders SignRequest(string method, string requestUri, ReadOnlySpan<byte> body, long timestamp)
    {
        var nonce = nonceFactory();
        var text = Signing.Canonical(method, requestUri, timestamp, nonce, Signing.Sha256Hex(body));
        return new SignedHeaders(AgentId, timestamp, nonce, Signing.Sign(key, text));
    }

    public override string ToString() => $"RequestSigner(agent_id={AgentId})";
}
