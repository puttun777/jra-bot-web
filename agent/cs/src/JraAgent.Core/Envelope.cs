using System.Buffers.Binary;
using System.Security.Cryptography;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;

namespace JraAgent.Core;

public sealed record FetchInfo(string FetchId, int Seq, string? TargetFrom = null, string? TargetTo = null, string Origin = "LIVE", long? BackfillId = null);

/// <summary>JV-Data のレコード（先頭 2〜4 文字の種別 ID と、cp932 のバイト列そのもの）。エージェントは解釈しない（サーバが解析する）。</summary>
public sealed record RawRecord(string RecordId, byte[] Data);

/// <summary>
/// ingest 封筒の組み立て（CONTRACTS §3.4。旧 envelope.py の移植）。サーバの <c>EnvelopeValidator</c> が受け付ける形だけを作る（未知のキーなし・<c>tenantId</c> なし）。
/// <c>batchId</c> は UUIDv7（小文字）。再送では同じ batchId・同じ内容（キューに保存した JSON のバイト列をそのまま使う）。
/// 蓄積系（S）は取得セッション <c>fetch</c>（P-2）を付ける。速報（R）には付けない。
/// </summary>
public static partial class Envelope
{
    public const int ProtocolVersion = 1;
    public static readonly IReadOnlySet<string> StoredDataspecs = new HashSet<string> { "RACE", "YSCH", "RCVN", "RCOV" };
    public static readonly IReadOnlySet<string> RealtimeDataspecs = new HashSet<string> { "0B11", "0B12", "0B14", "0B15", "0B30", "0B31", "0B33", "0B35" };

    [GeneratedRegex("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")]
    private static partial Regex UuidV7();

    [GeneratedRegex("^[A-Z0-9]{2,4}$")]
    private static partial Regex RecordId();

    [GeneratedRegex("^[0-9]{14}$")]
    private static partial Regex Ts14();

    /// <summary>RFC 9562 の UUIDv7（48bit のミリ秒 + 乱数）。時系列でソートできる。</summary>
    public static string Uuid7(long? nowMs = null, Func<int, byte[]>? rand = null)
    {
        var ms = nowMs ?? DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
        var b = new byte[16];
        Span<byte> t = stackalloc byte[8];
        BinaryPrimitives.WriteInt64BigEndian(t, ms);
        t[2..].CopyTo(b);
        (rand ?? RandomNumberGenerator.GetBytes)(10).CopyTo(b, 6);
        b[6] = (byte)(0x70 | (b[6] & 0x0F));
        b[8] = (byte)(0x80 | (b[8] & 0x3F));
        var h = Convert.ToHexStringLower(b);
        return $"{h[..8]}-{h[8..12]}-{h[12..16]}-{h[16..20]}-{h[20..]}";
    }

    public static string BatchClassOf(string dataspec) =>
        StoredDataspecs.Contains(dataspec) ? "S" : RealtimeDataspecs.Contains(dataspec) ? "R" : throw new ArgumentException("サーバが受け付けない dataspec");

    /// <summary>封筒の JSON（UTF-8、キー順固定、ASCII だけ）。検証はここでも行う（サーバで 422 になる前に、バグとして気づく）。</summary>
    public static byte[] Build(
        string agentId,
        string batchId,
        string dataspec,
        DateTimeOffset observedAt,
        IReadOnlyList<RawRecord> records,
        bool eof,
        int? option = null,
        string? fromtime = null,
        string? lastFileTimestamp = null,
        FetchInfo? fetch = null)
    {
        if (!UuidV7().IsMatch(batchId)) throw new ArgumentException("batch_id は小文字の UUIDv7");
        var cls = BatchClassOf(dataspec);
        if (fetch is not null && cls != "S") throw new ArgumentException("fetch は蓄積系（S）だけ");
        if (records.Count == 0 && !eof) throw new ArgumentException("EOF 以外の空バッチは作れない");
        if (option is < 0 or > 9) throw new ArgumentException("option は 0〜9");
        foreach (var ts in new[] { fromtime, lastFileTimestamp })
        {
            if (ts is not null && !Ts14().IsMatch(ts)) throw new ArgumentException("タイムスタンプは yyyyMMddHHmmss");
        }
        foreach (var r in records)
        {
            if (!RecordId().IsMatch(r.RecordId) || r.Data.Length == 0) throw new ArgumentException("レコード ID または本体が不正");
        }
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms, new JsonWriterOptions { Encoder = JavaScriptEncoder.Default, Indented = false }))
        {
            w.WriteStartObject();
            w.WriteNumber("protocol", ProtocolVersion);
            w.WriteString("agentId", agentId);
            w.WriteString("batchId", batchId);
            w.WriteString("class", cls);
            w.WriteString("observedAt", Times.IsoZ(observedAt));
            w.WriteString("dataspec", dataspec);
            if (option is { } o) w.WriteNumber("option", o);
            if (fromtime is not null) w.WriteString("fromtime", fromtime);
            if (lastFileTimestamp is not null) w.WriteString("lastFileTimestamp", lastFileTimestamp);
            w.WriteBoolean("eof", eof);
            if (fetch is not null)
            {
                w.WriteStartObject("fetch");
                w.WriteString("id", fetch.FetchId);
                w.WriteNumber("seq", fetch.Seq);
                w.WriteString("origin", fetch.Origin);
                if (fetch.TargetFrom is not null && fetch.TargetTo is not null)
                {
                    w.WriteString("targetFrom", fetch.TargetFrom);
                    w.WriteString("targetTo", fetch.TargetTo);
                }
                if (fetch.BackfillId is { } bid) w.WriteNumber("backfillId", bid);
                w.WriteEndObject();
            }
            w.WriteStartArray("records");
            foreach (var r in records)
            {
                w.WriteStartObject();
                w.WriteString("id", r.RecordId);
                w.WriteString("bytesB64", Convert.ToBase64String(r.Data));
                w.WriteString("sha256", Convert.ToHexStringLower(SHA256.HashData(r.Data)));
                w.WriteEndObject();
            }
            w.WriteEndArray();
            w.WriteEndObject();
        }
        return ms.ToArray();
    }
}
