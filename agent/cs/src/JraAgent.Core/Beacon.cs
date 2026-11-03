using System.Globalization;
using System.Text.Json;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;

namespace JraAgent.Core;

/// <summary>ビーコン検証鍵（<c>kid</c> と Ed25519 公開鍵の生 32 バイト）。</summary>
public sealed record BeaconKey(string Kid, byte[] Raw)
{
    public override string ToString() => $"BeaconKey({Kid})";
}

public sealed record Beacon(string Mode, long Seq, string Kid, DateTimeOffset UpdatedAt, DateTimeOffset? ValidUntil)
{
    public bool Normal => Mode == "NORMAL";
}

/// <summary>
/// モードビーコン（CONTRACTS §7.2）の検証。旧 Python の beacon.py の移植。
/// <list type="bullet">
/// <item><c>{schema_version, mode, seq, updatedAt, validUntil, kid, sig}</c> のちょうど 7 キー。重複キーは拒否</item>
/// <item><c>sig</c> は Ed25519（RFC 8032）。署名対象は <c>sig</c> を除いたフィールドをキーの辞書順・空白なしで直列化した JSON の UTF-8（<see cref="CanonicalJson"/>）。公開鍵は <c>kid</c> で選ぶ</item>
/// <item>検証を省略する設定は無い。失敗は <see cref="VerificationException"/>（理由は固定語彙）で、呼び出し側は UNKNOWN（取得も送信も止める）として扱う</item>
/// </list>
/// </summary>
public static class BeaconVerifier
{
    public static readonly IReadOnlySet<string> Modes = new HashSet<string> { "NORMAL", "STARTING", "STOPPING", "STOPPED" };
    private static readonly HashSet<string> Keys = ["schema_version", "mode", "seq", "updatedAt", "validUntil", "kid", "sig"];
    public static readonly TimeSpan FutureTolerance = TimeSpan.FromMinutes(10);
    public const int MaxBeaconBytes = 4096;

    public static bool VerifyEd25519(byte[] publicKey, byte[] message, byte[] signature)
    {
        if (publicKey.Length != 32 || signature.Length != 64) return false;
        var v = new Ed25519Signer();
        v.Init(false, new Ed25519PublicKeyParameters(publicKey, 0));
        v.BlockUpdate(message, 0, message.Length);
        return v.VerifySignature(signature);
    }

    private static DateTimeOffset ParseTime(JsonElement v, string reason)
    {
        if (v.ValueKind != JsonValueKind.String) throw new VerificationException(reason);
        var s = v.GetString() ?? "";
        // ISO 8601 でタイムゾーン（Z または ±hh:mm）が必須（Python の fromisoformat + tzinfo の検査と同じ）
        if (!(s.EndsWith('Z') || System.Text.RegularExpressions.Regex.IsMatch(s, "[+-][0-9]{2}:[0-9]{2}$"))) throw new VerificationException(reason);
        if (!DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var t)) throw new VerificationException(reason);
        return t.ToUniversalTime();
    }

    /// <summary>JSON の文書（UTF-8）を検証する。seq の単調性は <see cref="BeaconGuard"/> が永続した値と比べる。</summary>
    public static Beacon Verify(byte[] json, IReadOnlyList<BeaconKey> keys, DateTimeOffset now)
    {
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            throw new VerificationException("BAD_JSON");
        }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) throw new VerificationException("BAD_SHAPE");
            var props = root.EnumerateObject().ToList();
            var names = props.Select(p => p.Name).ToList();
            if (names.Count != names.Distinct(StringComparer.Ordinal).Count() || !Keys.SetEquals(names)) throw new VerificationException("BAD_SHAPE");
            var get = props.ToDictionary(p => p.Name, p => p.Value, StringComparer.Ordinal);
            var sv = get["schema_version"];
            if (sv.ValueKind != JsonValueKind.Number || sv.GetRawText() != "1") throw new VerificationException("BAD_SCHEMA_VERSION");
            var kid = get["kid"];
            var sig = get["sig"];
            if (kid.ValueKind != JsonValueKind.String || sig.ValueKind != JsonValueKind.String) throw new VerificationException("BAD_SHAPE");
            var key = keys.FirstOrDefault(k => k.Kid == kid.GetString()) ?? throw new VerificationException("UNKNOWN_KID");
            byte[] rawSig;
            byte[] message;
            try
            {
                rawSig = Signing.Base64UrlDecode(sig.GetString() ?? "");
                message = CanonicalJson.EncodeWithout(root, "sig");
            }
            catch (FormatException)
            {
                throw new VerificationException("BAD_SIGNATURE");
            }
            catch (VerificationException)
            {
                throw new VerificationException("BAD_SIGNATURE");
            }
            if (!VerifyEd25519(key.Raw, message, rawSig)) throw new VerificationException("BAD_SIGNATURE");
            // ここから先は署名済みの内容
            var mode = get["mode"];
            if (mode.ValueKind != JsonValueKind.String || !Modes.Contains(mode.GetString() ?? "")) throw new VerificationException("BAD_MODE");
            var seqEl = get["seq"];
            if (seqEl.ValueKind != JsonValueKind.Number || !long.TryParse(seqEl.GetRawText(), NumberStyles.None, CultureInfo.InvariantCulture, out var seq) || seq < 0)
            {
                throw new VerificationException("BAD_SEQ");
            }
            var updated = ParseTime(get["updatedAt"], "BAD_UPDATED_AT");
            DateTimeOffset? until = get["validUntil"].ValueKind == JsonValueKind.Null ? null : ParseTime(get["validUntil"], "BAD_VALID_UNTIL");
            if (updated > now + FutureTolerance) throw new VerificationException("UPDATED_IN_FUTURE");
            if (until is { } u && mode.GetString() == "NORMAL" && now > u) throw new VerificationException("EXPIRED");
            return new Beacon(mode.GetString() ?? "", seq, kid.GetString() ?? "", updated, until);
        }
    }
}

/// <summary>seq の巻き戻り拒否。最後に受け入れた (seq, mode) を永続し、再起動後も比較する（旧 BeaconGuard と同じ）。</summary>
public sealed class BeaconGuard(Func<string, string?> getState, Action<string, string> setState)
{
    public const string SeqKey = "beacon_seq";
    public const string ModeKey = "beacon_mode";

    public (long Seq, string Mode)? Last()
    {
        var s = getState(SeqKey);
        var m = getState(ModeKey);
        if (string.IsNullOrEmpty(s) || string.IsNullOrEmpty(m)) return null;
        return (long.Parse(s, CultureInfo.InvariantCulture), m);
    }

    public void CheckAndAccept(Beacon beacon)
    {
        if (Last() is { } last)
        {
            if (beacon.Seq < last.Seq) throw new VerificationException("SEQ_ROLLBACK");
            if (beacon.Seq == last.Seq && beacon.Mode != last.Mode) throw new VerificationException("SEQ_CONFLICT");
        }
        setState(SeqKey, beacon.Seq.ToString(CultureInfo.InvariantCulture));
        setState(ModeKey, beacon.Mode);
    }

    /// <summary>利用者の明示操作だけ（ops-state を作り直したときなど）。</summary>
    public void Reset()
    {
        setState(SeqKey, "");
        setState(ModeKey, "");
    }
}

public enum BeaconStatus
{
    Valid,
    Unreachable,
    Invalid,
}

public sealed record BeaconResult(BeaconStatus Status, Beacon? Beacon = null, string? Reason = null, string? ServerDate = null);

public sealed class BeaconClient(string url, IReadOnlyList<BeaconKey> keys, ITransport transport, IClock clock, BeaconGuard guard, double timeoutSeconds = 15.0)
{
    public BeaconResult Fetch()
    {
        TransportResponse resp;
        try
        {
            resp = transport.Request("GET", url, new Dictionary<string, string> { ["Accept"] = "application/json", ["Cache-Control"] = "no-cache" }, null, timeoutSeconds);
        }
        catch (TransportException e)
        {
            return new BeaconResult(BeaconStatus.Unreachable, Reason: e.Kind);
        }
        resp.Headers.TryGetValue("date", out var date);
        if (resp.Status != 200) return new BeaconResult(BeaconStatus.Unreachable, Reason: $"HTTP_{resp.Status}", ServerDate: date);
        if (resp.Body.Length > BeaconVerifier.MaxBeaconBytes) return new BeaconResult(BeaconStatus.Invalid, Reason: "TOO_LARGE", ServerDate: date);
        try
        {
            var b = BeaconVerifier.Verify(resp.Body, keys, clock.Now);
            guard.CheckAndAccept(b);
            return new BeaconResult(BeaconStatus.Valid, b, ServerDate: date);
        }
        catch (VerificationException e)
        {
            return new BeaconResult(BeaconStatus.Invalid, Reason: e.Reason, ServerDate: date);
        }
    }
}
