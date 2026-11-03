using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace JraAgent.Core.Tests;

/// <summary>
/// 署名の既知テストベクトル（OpenSSL で独立に計算。Kotlin サーバの AgentSigning も同じファイルを検査する）と、
/// サーバ側認証の独立な再現（旧 agent/tests/test_signing.py の移植）。
/// </summary>
public sealed partial class SigningTests
{
    [GeneratedRegex("^[A-Za-z0-9_-]{16,64}$")]
    private static partial Regex NonceShape();

    [GeneratedRegex("^[0-9a-f]{64}$")]
    private static partial Regex SigShape();

    public static TheoryData<string> CaseNames()
    {
        using var doc = Vectors.Load("signing_vectors.json");
        var data = new TheoryData<string>();
        foreach (var c in doc.RootElement.GetProperty("cases").EnumerateArray()) data.Add(c.GetProperty("name").GetString()!);
        return data;
    }

    [Fact]
    public void Vector_file_has_cases()
    {
        Assert.True(CaseNames().Count >= 3);
    }

    [Theory]
    [MemberData(nameof(CaseNames))]
    public void Known_vectors(string name)
    {
        using var doc = Vectors.Load("signing_vectors.json");
        var c = doc.RootElement.GetProperty("cases").EnumerateArray().Single(x => x.GetProperty("name").GetString() == name);
        string S(string k) => c.GetProperty(k).ValueKind == JsonValueKind.Number ? c.GetProperty(k).GetRawText() : c.GetProperty(k).GetString()!;
        var body = Convert.FromHexString(S("bodyHex"));
        var token = S("token");
        var ts = long.Parse(S("timestamp"), System.Globalization.CultureInfo.InvariantCulture);
        Assert.Equal(S("signingKeyHex"), Convert.ToHexStringLower(Signing.DeriveSigningKey(token)));
        Assert.Equal(S("bodySha256"), Signing.Sha256Hex(body));
        var text = Signing.Canonical(S("method"), S("requestUri"), ts, S("nonce"), Signing.Sha256Hex(body));
        Assert.Equal(S("canonical"), text);
        Assert.Equal(S("signature"), Signing.Sign(Signing.DeriveSigningKey(token), text));
        var signer = new RequestSigner("ag_test", token, () => S("nonce"));
        Assert.Equal(S("signature"), signer.SignRequest(S("method"), S("requestUri"), body, ts).Signature);
    }

    [Fact]
    public void Signing_key_derivation_label_is_fixed()
    {
        using var doc = Vectors.Load("signing_vectors.json");
        Assert.Equal("jra-bot/agent-sign/v1", doc.RootElement.GetProperty("derive_label").GetString());
        Assert.Equal(HMACSHA256.HashData("t"u8, "jra-bot/agent-sign/v1"u8), Signing.DeriveSigningKey("t"));
    }

    [Fact]
    public void Nonce_is_fresh_and_valid_shape()
    {
        var nonces = Enumerable.Range(0, 2000).Select(_ => Signing.NewNonce()).ToHashSet();
        Assert.Equal(2000, nonces.Count);
        Assert.All(nonces, n => Assert.Matches(NonceShape(), n));
    }

    [Fact]
    public void Headers_do_not_leak_in_ToString()
    {
        var signer = new RequestSigner("ag_test", "jrat1_secret-token-value");
        var h = signer.SignRequest("GET", "/v1/agent/plan", [], 1);
        Assert.DoesNotContain(h.Signature, h.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain(h.Nonce, h.ToString(), StringComparison.Ordinal);
        Assert.DoesNotContain("secret-token-value", signer.ToString(), StringComparison.Ordinal);
    }

    [Fact]
    public void Base64Url_roundtrip_and_rejects_padding_or_foreign_chars()
    {
        var data = RandomNumberGenerator.GetBytes(33);
        Assert.Equal(data, Signing.Base64UrlDecode(Signing.Base64Url(data)));
        Assert.Throws<FormatException>(() => Signing.Base64UrlDecode("ab+/"));
        Assert.Throws<FormatException>(() => Signing.Base64UrlDecode("abc="));
    }

    /// <summary>サーバ側の認証を、エージェントの実装を使わずに書き直したもの（契約 §3.3 の文面から）。</summary>
    private sealed class IndependentServer(string token, long now)
    {
        private readonly byte[] key = HMACSHA256.HashData(Encoding.UTF8.GetBytes(token), "jra-bot/agent-sign/v1"u8);
        private readonly HashSet<string> seen = [];

        public long Now { get; set; } = now;

        public bool Check(string method, string uri, IReadOnlyDictionary<string, string> headers, byte[] body)
        {
            var ts = long.Parse(headers["X-Timestamp"], System.Globalization.CultureInfo.InvariantCulture);
            var nonce = headers["X-Nonce"];
            var sig = headers["X-Signature"];
            if (Math.Abs(Now - ts) > 300 || !NonceShape().IsMatch(nonce) || !SigShape().IsMatch(sig)) return false;
            var expect = Convert.ToHexStringLower(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes($"{method}|{uri}|{ts}|{nonce}|{Convert.ToHexStringLower(SHA256.HashData(body))}")));
            if (!CryptographicOperations.FixedTimeEquals(Encoding.ASCII.GetBytes(expect), Encoding.ASCII.GetBytes(sig)) || !seen.Add(nonce)) return false;
            return true;
        }
    }

    private static (SignedClient C, ScriptedTransport T, FakeClock Clock, IndependentServer Srv) Client(string token = "jrat1_abc")
    {
        var clock = new FakeClock();
        var t = new ScriptedTransport { Default = ScriptedTransport.Resp(200, "{}"u8.ToArray()) };
        var srv = new IndependentServer(token, Times.ToUnix(clock.Now));
        return (new SignedClient("https://ingest.example", new RequestSigner("ag_test", token), t, clock, gzipMinBytes: 512), t, clock, srv);
    }

    private static byte[] Big(int n) => Encoding.UTF8.GetBytes("{\"x\": \"" + new string('y', n) + "\"}");

    [Fact]
    public void Client_signature_matches_independent_server_with_gzip_and_without()
    {
        var (c, t, _, srv) = Client();
        var small = "{\"a\":1}"u8.ToArray();
        var big = Big(5000);
        c.PostJson("/v1/agent/heartbeat", small);
        c.PostJson("/v1/agent/ingest", big);
        c.Get("/v1/agent/plan?since=1");
        var (c1, c2, c3) = (t.Sent[0], t.Sent[1], t.Sent[2]);
        Assert.DoesNotContain(c1.Headers.Keys, k => k.Equals("content-encoding", StringComparison.OrdinalIgnoreCase));
        Assert.Equal(small, c1.Body);
        Assert.Equal("gzip", c2.Headers["Content-Encoding"]);
        Assert.Equal(SignedClient.GzipDeterministic(big), c2.Body);
        Assert.NotEqual(big, c2.Body);
        Assert.True(srv.Check("POST", "/v1/agent/heartbeat", c1.Headers, c1.Body!));
        // gzip: 圧縮後のバイト列をハッシュしている（展開後の本文では検証に失敗する）
        Assert.True(srv.Check("POST", "/v1/agent/ingest", c2.Headers, c2.Body!));
        Assert.Null(c3.Body);
        Assert.True(srv.Check("GET", "/v1/agent/plan?since=1", c3.Headers, []));
        Assert.Equal("https://ingest.example/v1/agent/plan?since=1", c3.Url);
    }

    [Fact]
    public void Gzip_is_deterministic_and_decompresses_to_input()
    {
        var big = Big(4000);
        var a = SignedClient.GzipDeterministic(big);
        Assert.Equal(a, SignedClient.GzipDeterministic(big));
        Assert.Equal(new byte[] { 0x1f, 0x8b, 8 }, a[..3]);
        Assert.Equal(new byte[4], a[4..8]); // MTIME = 0
        using var gz = new System.IO.Compression.GZipStream(new MemoryStream(a), System.IO.Compression.CompressionMode.Decompress);
        using var o = new MemoryStream();
        gz.CopyTo(o);
        Assert.Equal(big, o.ToArray());
    }

    [Fact]
    public void Signature_does_not_verify_for_uncompressed_body_of_gzip_request()
    {
        var (c, t, _, srv) = Client();
        var big = Big(5000);
        c.PostJson("/v1/agent/ingest", big);
        Assert.False(srv.Check("POST", "/v1/agent/ingest", t.Sent[0].Headers, big));
    }

    [Fact]
    public void Every_request_gets_a_new_nonce_and_resend_body_is_identical()
    {
        var (c, t, clock, _) = Client();
        var payload = Big(3000);
        for (var i = 0; i < 3; i++)
        {
            c.PostJson("/v1/agent/ingest", payload);
            clock.Advance(1);
        }
        Assert.Equal(3, t.Sent.Select(x => x.Headers["X-Nonce"]).Distinct().Count());
        Assert.Single(t.Sent.Select(x => Convert.ToHexString(x.Body!)).Distinct()); // 再送の本文（gzip 後）は毎回同じバイト列
        Assert.Equal(3, t.Sent.Select(x => x.Headers["X-Signature"]).Distinct().Count());
    }

    [Theory]
    [InlineData(0, true)]
    [InlineData(299, true)]
    [InlineData(300, true)]
    [InlineData(301, false)]
    [InlineData(-300, true)]
    [InlineData(-301, false)]
    public void Timestamp_boundary_against_server_window(int offset, bool ok)
    {
        var (c, t, clock, srv) = Client();
        srv.Now = Times.ToUnix(clock.Now) + offset;
        c.Get("/v1/agent/mode");
        Assert.Equal(ok, srv.Check("GET", "/v1/agent/mode", t.Sent[0].Headers, []));
    }

    [Fact]
    public void Replayed_nonce_is_rejected_by_server()
    {
        var (c, t, _, srv) = Client();
        c.Get("/v1/agent/mode");
        Assert.True(srv.Check("GET", "/v1/agent/mode", t.Sent[0].Headers, []));
        Assert.False(srv.Check("GET", "/v1/agent/mode", t.Sent[0].Headers, []));
    }

    [Fact]
    public void Wrong_token_does_not_verify()
    {
        var (c, t, _, _) = Client("jrat1_right");
        c.Get("/v1/agent/mode");
        var other = new IndependentServer("jrat1_wrong", Times.ToUnix(new FakeClock().Now));
        Assert.False(other.Check("GET", "/v1/agent/mode", t.Sent[0].Headers, []));
    }

    [Fact]
    public void Transport_failure_is_classified_unreachable_without_throwing()
    {
        var clock = new FakeClock();
        var t = new ScriptedTransport();
        t.Push(new TransportException("TIMEOUT"));
        var c = new SignedClient("https://ingest.example", new RequestSigner("ag_test", "jrat1_abc"), t, clock);
        var r = c.Get("/v1/agent/mode");
        Assert.Equal(Outcome.Unreachable, r.Classified.Outcome);
        Assert.Equal("TIMEOUT", r.Classified.Code);
        Assert.Null(r.Response);
    }
}

public sealed class ClockAndTransportTests
{
    [Fact]
    public void System_clock_monotonic_is_never_negative_on_first_use()
    {
        // 型の初期化の順序で初回が負になった不具合の回帰試験（新しい監視側が最初の起動を『隔離中』と誤判定した）
        var c = new SystemClock();
        var a = c.Monotonic;
        var b = c.Monotonic;
        Assert.True(a >= 0);
        Assert.True(b >= a);
    }

    [Theory]
    [InlineData("https://ingest.example/v1", true)]
    [InlineData("http://127.0.0.1:8080/x", true)]
    [InlineData("http://localhost/x", true)]
    [InlineData("http://[::1]:9/x", true)]
    [InlineData("http://127.0.0.1.evil.example/x", false)] // 旧版の前方一致では通っていた
    [InlineData("http://localhost.evil.example/x", false)]
    [InlineData("http://127.0.0.1@evil.example/x", false)]
    [InlineData("https://user:pw@ingest.example/", false)]
    [InlineData("http://ingest.example/", false)]
    [InlineData("ftp://127.0.0.1/", false)]
    [InlineData("not a url", false)]
    public void Only_https_or_exact_loopback_http_is_allowed(string url, bool ok) => Assert.Equal(ok, HttpTransport.IsAllowedUrl(url));
}
