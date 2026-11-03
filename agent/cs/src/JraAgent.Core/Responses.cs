using System.Globalization;
using System.Text.Json;

namespace JraAgent.Core;

/// <summary>ingest 応答の分類（CONTRACTS §3.6。旧 responses.py と同じ表）。</summary>
public enum Outcome
{
    Ok,
    Dead,
    Auth,
    RetryConflict,
    Throttled,
    OpsNotNormal,
    OpsUnknown,
    TransientDb,
    Unreachable,
    ServerError,
    Unexpected,
}

public sealed record Classified(Outcome Outcome, int? Status, string? Code = null, double? RetryAfter = null, string? OpsMode = null)
{
    /// <summary>取得セッション全体が無効になるコード（以降の連番も受け付けられない）。</summary>
    public bool FetchLevelDead => Outcome == Outcome.Dead && (Code ?? "").StartsWith("FETCH_", StringComparison.Ordinal);
}

public static class Responses
{
    private static readonly HashSet<string> NonNormalModes = ["STARTING", "STOPPING", "STOPPED"];

    /// <summary>本文の <c>{"error": "CODE"}</c>（英数字と <c>_</c>、64 文字以内）。それ以外は null。</summary>
    public static string? ParseErrorCode(byte[] body)
    {
        try
        {
            using var doc = JsonDocument.Parse(body);
            if (doc.RootElement.ValueKind != JsonValueKind.Object || !doc.RootElement.TryGetProperty("error", out var e) || e.ValueKind != JsonValueKind.String) return null;
            var code = e.GetString() ?? "";
            return code.Length is > 0 and <= 64 && code.All(c => c == '_' || char.IsAsciiLetterOrDigit(c) || char.IsLetterOrDigit(c)) && code.Replace("_", "", StringComparison.Ordinal).Length > 0 ? code : null;
        }
        catch (JsonException)
        {
            return null;
        }
    }

    /// <summary>秒数だけ（HTTP-date は使わない）。負・NaN は無視。上限 <paramref name="cap"/>。</summary>
    public static double? ParseRetryAfter(string? value, double cap = 900.0)
    {
        if (value is null) return null;
        if (!double.TryParse(value.Trim(), NumberStyles.Float, CultureInfo.InvariantCulture, out var s) || double.IsNaN(s) || s < 0) return null;
        return Math.Min(s, cap);
    }

    public static Classified Classify(TransportResponse resp)
    {
        var status = resp.Status;
        var code = status >= 400 ? ParseErrorCode(resp.Body) : null;
        resp.Headers.TryGetValue("retry-after", out var ra);
        var retryAfter = ParseRetryAfter(ra);
        resp.Headers.TryGetValue("x-ops-mode", out var om);
        var opsMode = string.IsNullOrWhiteSpace(om) ? null : om.Trim().ToUpperInvariant();
        return status switch
        {
            200 => new Classified(Outcome.Ok, status),
            400 or 413 or 422 => new Classified(Outcome.Dead, status, code),
            401 or 403 => new Classified(Outcome.Auth, status, code),
            409 => new Classified(Outcome.RetryConflict, status, code, retryAfter),
            429 => new Classified(Outcome.Throttled, status, code, retryAfter),
            503 when opsMode is not null && NonNormalModes.Contains(opsMode) => new Classified(Outcome.OpsNotNormal, status, code, retryAfter, opsMode),
            503 when opsMode == "NORMAL" => new Classified(Outcome.TransientDb, status, code, retryAfter, opsMode), // NORMAL なのに 503 → 一時障害
            503 when code == "UNAVAILABLE" && retryAfter is not null => new Classified(Outcome.TransientDb, status, code, retryAfter),
            503 => new Classified(Outcome.OpsUnknown, status, code, retryAfter), // OPS_MODE_UNKNOWN・ヘッダなしは区別できない = 安全側
            502 or 504 => new Classified(Outcome.OpsUnknown, status, code, retryAfter),
            >= 500 and < 600 => new Classified(Outcome.ServerError, status, code, retryAfter),
            _ => new Classified(Outcome.Unexpected, status, code),
        };
    }
}

/// <summary>指数バックオフ + ジッタ。上限つき（CONTRACTS §7 / JVLINK_DESIGN §5.4: 1s→2s→…最大 15 分、ジッタ ±20%）。</summary>
public sealed record Backoff(double Base = 1.0, double Factor = 2.0, double Cap = 900.0, double Jitter = 0.2)
{
    /// <summary><paramref name="attempt"/> は 1 から。<paramref name="rand"/> は 0〜1 の一様乱数。</summary>
    public double Delay(int attempt, Func<double>? rand = null)
    {
        ArgumentOutOfRangeException.ThrowIfLessThan(attempt, 1);
        var raw = Math.Min(Cap, Base * Math.Pow(Factor, attempt - 1));
        var spread = ((rand ?? Random.Shared.NextDouble)() * 2.0 - 1.0) * Jitter;
        return Math.Max(0.0, Math.Min(Cap, raw * (1.0 + spread)));
    }

    /// <summary>状態確認（UNKNOWN 中のビーコン確認）: 5 分→15 分→60 分で頭打ち（CONTRACTS §7.3）。</summary>
    public static readonly double[] UnknownPollSteps = [300.0, 900.0, 3600.0];

    public const double StoppedPollSeconds = 3600.0;

    public static double SteppedDelay(IReadOnlyList<double> steps, int attempt, double jitter = 0.2, Func<double>? rand = null)
    {
        var b = steps[Math.Min(Math.Max(attempt, 1), steps.Count) - 1];
        return b * (1.0 + ((rand ?? Random.Shared.NextDouble)() * 2.0 - 1.0) * jitter);
    }
}

/// <summary>ログに秘密（トークン・署名・nonce・生データ）を出さないための伏せ字（最後の砦。呼び出し側が秘密を渡さないことが前提）。</summary>
public static partial class Redact
{
    [System.Text.RegularExpressions.GeneratedRegex(@"jrat1_[A-Za-z0-9_\-]{8,}")]
    private static partial System.Text.RegularExpressions.Regex Token();

    [System.Text.RegularExpressions.GeneratedRegex(@"(X-Signature['""]?\s*[:=]\s*['""]?)[0-9a-fA-F]{16,}", System.Text.RegularExpressions.RegexOptions.IgnoreCase)]
    private static partial System.Text.RegularExpressions.Regex Sig();

    [System.Text.RegularExpressions.GeneratedRegex(@"(X-Nonce['""]?\s*[:=]\s*['""]?)[A-Za-z0-9_\-]{8,}", System.Text.RegularExpressions.RegexOptions.IgnoreCase)]
    private static partial System.Text.RegularExpressions.Regex Nonce();

    [System.Text.RegularExpressions.GeneratedRegex(@"(token['""]?\s*[:=]\s*['""]?)[A-Za-z0-9_\-+/=]{12,}", System.Text.RegularExpressions.RegexOptions.IgnoreCase)]
    private static partial System.Text.RegularExpressions.Regex TokenAssign();

    [System.Text.RegularExpressions.GeneratedRegex(@"(bytesB64['""]?\s*[:=]\s*['""]?)[A-Za-z0-9+/=]{16,}", System.Text.RegularExpressions.RegexOptions.IgnoreCase)]
    private static partial System.Text.RegularExpressions.Regex Bytes();

    public static string Text(string text)
    {
        text = Token().Replace(text, "jrat1_***");
        text = Sig().Replace(text, "$1***");
        text = Nonce().Replace(text, "$1***");
        text = TokenAssign().Replace(text, "$1***");
        return Bytes().Replace(text, "$1***");
    }
}
