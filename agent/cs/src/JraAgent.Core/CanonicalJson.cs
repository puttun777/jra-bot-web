using System.Globalization;
using System.Text;
using System.Text.Json;

namespace JraAgent.Core;

/// <summary>
/// 署名対象の JSON（旧 Python の <c>json.dumps(sort_keys=True, separators=(",", ":"), ensure_ascii=False)</c> と同じバイト列）。
/// 値は文字列・整数・null・真偽・入れ子のオブジェクト/配列だけ（ビーコン・更新 manifest）。浮動小数は扱わない（署名の対象に入れない）。
/// 文字列は <c>"</c> <c>\</c> と制御文字だけをエスケープし（<c>\n</c> などの短縮形、他は <c>\u00xx</c>）、非 ASCII はそのまま。キーはコードポイント順。
/// </summary>
public static class CanonicalJson
{
    public static byte[] Encode(JsonElement value) => Encoding.UTF8.GetBytes(Write(value, new StringBuilder()).ToString());

    /// <summary>オブジェクトから <paramref name="skipKey"/> を除いて直列化する。</summary>
    public static byte[] EncodeWithout(JsonElement obj, string skipKey)
    {
        if (obj.ValueKind != JsonValueKind.Object) throw new VerificationException("BAD_SHAPE");
        var sb = new StringBuilder("{");
        var first = true;
        foreach (var p in obj.EnumerateObject().Where(p => p.Name != skipKey).OrderBy(p => p.Name, CodePointComparer.Instance))
        {
            if (!first) sb.Append(',');
            first = false;
            WriteString(p.Name, sb);
            sb.Append(':');
            Write(p.Value, sb);
        }
        return Encoding.UTF8.GetBytes(sb.Append('}').ToString());
    }

    private static StringBuilder Write(JsonElement v, StringBuilder sb)
    {
        switch (v.ValueKind)
        {
            case JsonValueKind.Null:
                sb.Append("null");
                break;
            case JsonValueKind.True:
                sb.Append("true");
                break;
            case JsonValueKind.False:
                sb.Append("false");
                break;
            case JsonValueKind.String:
                WriteString(v.GetString() ?? "", sb);
                break;
            case JsonValueKind.Number:
                // 整数トークンだけ（小数点・指数を含むものは Python では float になり、署名の対象の形が変わる）
                var raw = v.GetRawText();
                if (!System.Numerics.BigInteger.TryParse(raw, NumberStyles.AllowLeadingSign, CultureInfo.InvariantCulture, out var n)) throw new VerificationException("BAD_NUMBER");
                sb.Append(n.ToString(CultureInfo.InvariantCulture));
                break;
            case JsonValueKind.Array:
                sb.Append('[');
                var i = 0;
                foreach (var x in v.EnumerateArray())
                {
                    if (i++ > 0) sb.Append(',');
                    Write(x, sb);
                }
                sb.Append(']');
                break;
            case JsonValueKind.Object:
                sb.Append('{');
                var first = true;
                foreach (var p in v.EnumerateObject().OrderBy(p => p.Name, CodePointComparer.Instance))
                {
                    if (!first) sb.Append(',');
                    first = false;
                    WriteString(p.Name, sb);
                    sb.Append(':');
                    Write(p.Value, sb);
                }
                sb.Append('}');
                break;
            default:
                throw new VerificationException("BAD_SHAPE");
        }
        return sb;
    }

    private static void WriteString(string s, StringBuilder sb)
    {
        sb.Append('"');
        foreach (var ch in s)
        {
            switch (ch)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                case '\b': sb.Append("\\b"); break;
                case '\f': sb.Append("\\f"); break;
                default:
                    if (ch < 0x20) sb.Append("\\u").Append(((int)ch).ToString("x4", CultureInfo.InvariantCulture));
                    else sb.Append(ch);
                    break;
            }
        }
        sb.Append('"');
    }

    /// <summary>Python の str の比較（コードポイント順）。</summary>
    public sealed class CodePointComparer : IComparer<string>
    {
        public static readonly CodePointComparer Instance = new();

        public int Compare(string? x, string? y)
        {
            if (x is null || y is null) return string.CompareOrdinal(x, y);
            int i = 0, j = 0;
            while (i < x.Length && j < y.Length)
            {
                var a = char.ConvertToUtf32(x, i);
                var b = char.ConvertToUtf32(y, j);
                if (a != b) return a.CompareTo(b);
                i += char.IsSurrogatePair(x, i) ? 2 : 1;
                j += char.IsSurrogatePair(y, j) ? 2 : 1;
            }
            return (x.Length - i).CompareTo(y.Length - j);
        }
    }
}
