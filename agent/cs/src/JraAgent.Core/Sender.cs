using System.Text.Json;

namespace JraAgent.Core;

public enum SendStep
{
    Acked,
    Dead,
    Retry, // 同じバッチを後で再送する
    Deferred, // 待機（モード・認証）。試行に数えない
    Idle, // 送るものが無い
    Halted, // モード/認証/時計で送れない
}

public sealed record StepResult(SendStep Step, string? BatchId = null, string? Detail = null);

/// <summary>
/// キューのバッチを 1 つずつ送る（旧 sender.py の移植）。応答ごとの動作は CONTRACTS §3.6 / §7（<see cref="Responses"/> の表）。
/// <list type="bullet">
/// <item>再試行で新しい batchId を作らない: キューに保存した本文を、そのまま（同じ batchId・同じ内容）送る。</item>
/// <item>無限に再試行しない: 同じバッチの試行回数に上限があり、超えたら DEAD（取得セッションも中止）。指数バックオフ + ジッタ。</item>
/// <item>401/403 は自動で再試行しない（送信停止 + ローカルアラート。トークンが差し替えられたら再開）。</item>
/// <item>503 + X-Ops-Mode が NORMAL 以外 / モードが不明: 試行に数えず待機（状態機械が許可するまで claim しない）。</item>
/// <item>同時に送るのは 1 バッチだけ。送信間隔は設定の sends_per_second（復帰時は最大 2 バッチ/秒）。</item>
/// </list>
/// </summary>
public sealed class Sender(
    OutboxQueue queue,
    SignedClient client,
    ModeMachine modes,
    SenderConfig cfg,
    IClock clock,
    string credentialFingerprint,
    Backoff? backoff = null,
    Func<double>? rand = null,
    Action<string>? logError = null)
{
    public const string AuthHaltKey = "auth_halt";
    private readonly Backoff backoff = backoff ?? new Backoff();
    private readonly Func<double> rand = rand ?? Random.Shared.NextDouble;
    private readonly Action<string> logError = logError ?? (_ => { });
    private double? lastSend;
    private double slowUntil;

    // --------------------------------------------------------------- 認証・時計の停止
    public string? AuthHalted()
    {
        var raw = queue.GetState(AuthHaltKey);
        if (string.IsNullOrEmpty(raw)) return null;
        string? fp = null;
        string? reason = null;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            if (doc.RootElement.ValueKind == JsonValueKind.Object)
            {
                if (doc.RootElement.TryGetProperty("fp", out var f) && f.ValueKind == JsonValueKind.String) fp = f.GetString();
                if (doc.RootElement.TryGetProperty("reason", out var r) && r.ValueKind == JsonValueKind.String) reason = r.GetString();
            }
        }
        catch (JsonException)
        {
            // 壊れた記録は停止のまま扱う（安全側）。資格情報が差し替えられれば下で解除される
        }
        if (fp != credentialFingerprint)
        {
            queue.SetState(AuthHaltKey, ""); // 資格情報が差し替えられた = 利用者が再発行したトークン。再開する
            queue.ClearAlert("AUTH_FAILED");
            return null;
        }
        if (reason == "CLOCK_SKEW" && !client.Skew.Suspicious)
        {
            queue.SetState(AuthHaltKey, ""); // 時計のずれが解消した
            queue.ClearAlert("CLOCK_SKEW");
            return null;
        }
        return reason ?? "AUTH";
    }

    private void HaltAuth(int? status)
    {
        var reason = client.Skew.Suspicious ? "CLOCK_SKEW" : $"AUTH_{status}";
        queue.SetState(AuthHaltKey, JsonSerializer.Serialize(new Dictionary<string, string> { ["fp"] = credentialFingerprint, ["reason"] = reason }));
        queue.RaiseAlert(reason == "CLOCK_SKEW" ? "CLOCK_SKEW" : "AUTH_FAILED", $"HTTP {status}: 送信を停止（トークンの再発行または時計の修正が必要）");
        logError($"認証に失敗したため送信を停止: {reason}");
    }

    // --------------------------------------------------------------- 1 バッチ
    private StepResult? Blocked()
    {
        var halted = AuthHalted();
        if (halted is not null) return new StepResult(SendStep.Halted, Detail: halted);
        var view = modes.Evaluate();
        if (!modes.MaySend) return new StepResult(SendStep.Halted, Detail: $"{WireNames.Of(view.Mode)}:{view.Reason}");
        return null;
    }

    public StepResult SendNext()
    {
        if (Blocked() is { } b) return b;
        queue.ExpireRealtime();
        var wait = PacingWait();
        if (wait > 0)
        {
            clock.Sleep(wait);
            // 待っている間に運用モードが変わり得る（STOPPED / UNKNOWN / 認証停止）。送信の直前に必ず再確認する（claim の前なので巻き戻しは不要）
            if (Blocked() is { } b2) return b2;
        }
        var batch = queue.ClaimNext();
        if (batch is null) return new StepResult(SendStep.Idle);
        lastSend = clock.Monotonic;
        return Send(batch);
    }

    private double PacingWait()
    {
        var now = clock.Monotonic;
        var gap = 1.0 / cfg.SendsPerSecond;
        var wait = lastSend is { } l ? Math.Max(0.0, l + gap - now) : 0.0;
        return Math.Max(wait, slowUntil - now);
    }

    private StepResult Send(BatchRow batch)
    {
        var res = client.PostJson("/v1/agent/ingest", batch.Payload);
        var c = res.Classified;
        modes.OnServer(c);
        var bid = batch.BatchId;
        switch (c.Outcome)
        {
            case Outcome.Ok:
                {
                    using var doc = res.Json();
                    var ack = doc is null ? null : Ack.Parse(doc.RootElement);
                    if (ack is null)
                    {
                        // ACK の形が違う = 受理を確認できない。同じ batchId で再送
                        queue.RetryLater(bid, Delay(batch, null), "BAD_ACK", cfg.MaxAttempts);
                        return new StepResult(SendStep.Retry, bid, "BAD_ACK");
                    }
                    queue.MarkAcked(bid, ack);
                    return new StepResult(SendStep.Acked, bid, ack.Duplicate ? "DUPLICATE" : null);
                }
            case Outcome.Dead:
                queue.MarkDead(bid, c.Code ?? $"HTTP_{c.Status}");
                queue.RaiseAlert("DEAD_BATCH", $"不正なバッチを DEAD にした（{c.Code ?? c.Status?.ToString(System.Globalization.CultureInfo.InvariantCulture)}）。自動再送はしない");
                logError($"バッチが拒否された（DEAD）: {bid} code={c.Code}");
                return new StepResult(SendStep.Dead, bid, c.Code);
            case Outcome.Auth:
                queue.Defer(bid, $"AUTH_{c.Status}");
                HaltAuth(c.Status);
                return new StepResult(SendStep.Halted, bid, $"AUTH_{c.Status}");
            case Outcome.OpsNotNormal:
                queue.Defer(bid, "OPS_NOT_NORMAL");
                return new StepResult(SendStep.Deferred, bid, c.OpsMode);
            case Outcome.RetryConflict:
                return Retry(bid, Math.Min(c.RetryAfter ?? Delay(batch, null), cfg.MaxConflictWaitSeconds), $"409:{c.Code}");
            case Outcome.Throttled:
                {
                    var delay = c.RetryAfter ?? Delay(batch, null);
                    slowUntil = clock.Monotonic + delay; // 全体も減速する
                    return Retry(bid, delay, "429");
                }
            default:
                // TRANSIENT_DB / UNREACHABLE / SERVER_ERROR / OPS_UNKNOWN / UNEXPECTED: 同じバッチを保持してバックオフ
                if (c.Outcome == Outcome.Unexpected) queue.RaiseAlert("UNEXPECTED_RESPONSE", $"想定外の応答 HTTP {c.Status}");
                var detail = c.Status is { } s and not 0 ? s.ToString(System.Globalization.CultureInfo.InvariantCulture) : c.Code;
                return Retry(bid, Delay(batch, c.RetryAfter), $"{WireNames.Of(c.Outcome)}:{detail}");
        }
    }

    private double Delay(BatchRow batch, double? retryAfter)
    {
        var d = backoff.Delay(Math.Max(batch.Attempts, 1), rand);
        return retryAfter is { } r ? Math.Max(d, r) : d;
    }

    private StepResult Retry(string batchId, double delay, string outcome)
    {
        if (queue.RetryLater(batchId, delay, outcome, cfg.MaxAttempts) == "DEAD")
        {
            queue.RaiseAlert("RETRY_EXHAUSTED", "再試行の上限に達したバッチを DEAD にした");
            return new StepResult(SendStep.Dead, batchId, "RETRY_EXHAUSTED");
        }
        return new StepResult(SendStep.Retry, batchId, outcome);
    }
}

/// <summary>200 の本文から ACK を取り出す（サーバの IngestAck）。</summary>
public static class Ack
{
    private static int? Int(JsonElement o, string key) =>
        o.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.Number && v.TryGetInt32(out var n) ? n : null;

    private static string? Str(JsonElement o, string key) =>
        o.ValueKind == JsonValueKind.Object && o.TryGetProperty(key, out var v) && v.ValueKind == JsonValueKind.String ? v.GetString() : null;

    /// <summary>形が違えば null（ACK として扱わない = 再送して重複 ACK を待つ）。</summary>
    public static AckInfo? Parse(JsonElement doc)
    {
        if (doc.ValueKind != JsonValueKind.Object) return null;
        if (Int(doc, "accepted") is not { } accepted || Int(doc, "duplicates") is not { } duplicates) return null;
        if (!doc.TryGetProperty("duplicate", out var dup) || dup.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return null;
        string? fid = null, fstatus = null, ctype = null, cts = null;
        long? fnext = null;
        if (doc.TryGetProperty("fetch", out var fetch) && fetch.ValueKind == JsonValueKind.Object)
        {
            fid = Str(fetch, "id");
            fstatus = Str(fetch, "status");
            if (fetch.TryGetProperty("nextSeq", out var ns) && ns.ValueKind == JsonValueKind.Number && ns.TryGetInt64(out var n)) fnext = n;
        }
        if (doc.TryGetProperty("cursor", out var cursor) && cursor.ValueKind == JsonValueKind.Object)
        {
            ctype = Str(cursor, "dataType");
            cts = Str(cursor, "lastFileTimestamp");
        }
        return new AckInfo(accepted, duplicates, Int(doc, "quarantined") ?? 0, Int(doc, "unsupported") ?? 0, dup.GetBoolean(), fid, fstatus, fnext, ctype, cts);
    }
}
