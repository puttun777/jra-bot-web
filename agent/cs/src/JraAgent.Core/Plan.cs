using System.Globalization;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace JraAgent.Core;

/// <param name="Interval">秒。同じ項目の実行間隔の下限。</param>
public sealed record PlanItem(string Dataspec, string? Key, int? Option, DateTimeOffset? NotBefore, double Interval)
{
    public bool Realtime => Envelope.RealtimeDataspecs.Contains(Dataspec);

    public string Ident => $"{Dataspec}:{(Option is { } o ? o.ToString(CultureInfo.InvariantCulture) : "-")}:{Key ?? "-"}";
}

public sealed record Plan(string PlanId, DateTimeOffset? ValidUntil, IReadOnlyList<PlanItem> Items, bool Idle)
{
    public static readonly Plan IdlePlan = new("none", null, [], true);

    public bool Usable(DateTimeOffset now) => !Idle && Items.Count > 0 && (ValidUntil is null || now < ValidUntil);
}

/// <summary>
/// 取得計画（GET /v1/agent/plan、CONTRACTS §3.2。旧 plan.py の移植）の解釈。
/// <c>{planId, validUntil, items:[{dataspec, key, option, notBefore, interval}], idle}</c>。<b>計画が無い・期限切れ・不正・idle なら、安全に何もしない</b>。
/// 未確認の JV-Link 仕様に依存する項目は、エージェント側でも <see cref="SpecRegistry"/> のゲートで拒否する（fetcher）。
/// </summary>
public static partial class PlanParser
{
    public const int MaxItems = 32;

    [GeneratedRegex("^[0-9A-Za-z]{1,32}$")]
    private static partial Regex KeyRe();

    [GeneratedRegex(@"[+-]\d{2}:\d{2}$")]
    private static partial Regex OffsetRe();

    private static readonly HashSet<string> TopKeys = ["planId", "validUntil", "items", "idle"];
    private static readonly HashSet<string> ItemKeys = ["dataspec", "key", "option", "notBefore", "interval"];

    private sealed class Invalid : Exception;

    private static DateTimeOffset? Time(JsonElement? v)
    {
        if (v is null || v.Value.ValueKind == JsonValueKind.Null) return null;
        if (v.Value.ValueKind != JsonValueKind.String) throw new Invalid();
        var s = v.Value.GetString() ?? throw new Invalid();
        // タイムゾーンの無い時刻は受け付けない（Z か ±hh:mm が必要）
        if (!(s.EndsWith('Z') || OffsetRe().IsMatch(s))) throw new Invalid();
        if (!DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var t)) throw new Invalid();
        return t.ToUniversalTime();
    }

    private static JsonElement? Get(JsonElement o, string k) => o.TryGetProperty(k, out var v) ? v : null;

    /// <summary>不正な計画は丸ごと破棄して IDLE（一部だけ採用しない）。</summary>
    public static Plan Parse(JsonElement doc)
    {
        try
        {
            if (doc.ValueKind != JsonValueKind.Object || doc.EnumerateObject().Any(p => !TopKeys.Contains(p.Name))) return Plan.IdlePlan;
            var pid = Get(doc, "planId");
            var itemsRaw = Get(doc, "items");
            var idleRaw = Get(doc, "idle");
            if (pid is not { ValueKind: JsonValueKind.String } p || p.GetString() is not { Length: >= 1 and <= 64 } planId) return Plan.IdlePlan;
            if (itemsRaw is { } ir && (ir.ValueKind != JsonValueKind.Array || ir.GetArrayLength() > MaxItems)) return Plan.IdlePlan;
            if (idleRaw is { } id && id.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return Plan.IdlePlan;
            var items = new List<PlanItem>();
            foreach (var it in itemsRaw?.EnumerateArray() ?? default)
            {
                if (it.ValueKind != JsonValueKind.Object || it.EnumerateObject().Any(x => !ItemKeys.Contains(x.Name))) return Plan.IdlePlan;
                var dsRaw = Get(it, "dataspec");
                if (dsRaw is not { ValueKind: JsonValueKind.String } dse || dse.GetString() is not { } ds) return Plan.IdlePlan;
                var realtime = Envelope.RealtimeDataspecs.Contains(ds);
                if (!realtime && !Envelope.StoredDataspecs.Contains(ds)) return Plan.IdlePlan;
                string? key = null;
                if (Get(it, "key") is { } k && k.ValueKind != JsonValueKind.Null)
                {
                    if (k.ValueKind != JsonValueKind.String || !KeyRe().IsMatch(k.GetString() ?? "")) return Plan.IdlePlan;
                    key = k.GetString();
                }
                int? opt = null;
                if (Get(it, "option") is { } o && o.ValueKind != JsonValueKind.Null)
                {
                    if (o.ValueKind != JsonValueKind.Number || !o.TryGetInt32(out var n) || n is < 0 or > 9) return Plan.IdlePlan;
                    opt = n;
                }
                double interval = 300;
                if (Get(it, "interval") is { } iv)
                {
                    if (iv.ValueKind != JsonValueKind.Number || !iv.TryGetDouble(out interval) || interval is < 30 or > 86400) return Plan.IdlePlan;
                }
                if (realtime && (key is null || opt is not null)) return Plan.IdlePlan; // 速報は key が要り、option は無い
                if (!realtime && opt is null) return Plan.IdlePlan;
                items.Add(new PlanItem(ds, key, opt, Time(Get(it, "notBefore")), interval));
            }
            return new Plan(planId, Time(Get(doc, "validUntil")), items, idleRaw is { ValueKind: JsonValueKind.True });
        }
        catch (Invalid)
        {
            return Plan.IdlePlan;
        }
    }
}

/// <summary>いつ何を実行するか。同じ項目を重複して起動しない（実行中の印）。実行の記録はプロセス内（再起動後は interval を待たずに再開してよい: 取得は冪等）。</summary>
public sealed class PlanScheduler
{
    private readonly Dictionary<string, double> lastRun = new(StringComparer.Ordinal);
    private readonly HashSet<string> running = new(StringComparer.Ordinal);

    public IReadOnlyList<PlanItem> Due(Plan plan, DateTimeOffset now, double mono)
    {
        if (!plan.Usable(now)) return [];
        var output = new List<PlanItem>();
        foreach (var it in plan.Items)
        {
            if (running.Contains(it.Ident)) continue;
            if (it.NotBefore is { } nb && now < nb) continue;
            if (lastRun.TryGetValue(it.Ident, out var last) && mono - last < it.Interval) continue;
            output.Add(it);
        }
        return output;
    }

    public bool Start(PlanItem item, double mono)
    {
        if (!running.Add(item.Ident)) return false; // 重複起動を防ぐ
        lastRun[item.Ident] = mono;
        return true;
    }

    public void Finish(PlanItem item) => running.Remove(item.Ident);
}
