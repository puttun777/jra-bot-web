using System.Reflection;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using System.Text;
using JraAgent.Core;

namespace JraAgent.Windows;

/// <summary>
/// 実 JV-Link（COM）のアダプタ（旧 com_adapter.py の移植）。<b>ワーカープロセスの中でだけ</b>使う（<c>jraagent worker --backend com</c>）。
/// <para>公式仕様で未確認の部分（<see cref="SpecRegistry"/>）は、ここに推測で実装しない。持つのは:</para>
/// <list type="bullet">
/// <item>COM オブジェクトの生成（ProgID は参照元実装の使用実態。公式未確認）と解放。</item>
/// <item>メソッド名・引数の並びは参照元実装で使われているもの。戻り値の解釈は <see cref="ReadCodes"/>（公式未確認の印つき）に分離。</item>
/// </list>
/// 遅延束縛は <c>dynamic</c> を使わず、IDispatch を <see cref="Type.InvokeMember(string, BindingFlags, Binder, object, object[], ParameterModifier[], System.Globalization.CultureInfo, string[])"/>
/// で呼ぶ（引数の型・参照渡しの位置をここで固定し、戻り値は検査してから変換する）。本番の取得経路は、仕様ゲートが通るまでワーカーを起動しない。
/// SDK の DLL・仕様書・利用キーはリポジトリに含めない（利用者の PC にインストールされたものを使う）。
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class ComJvLink : IJvLink, IDisposable
{
    /// <summary>参照元実装が試している ProgID（公式未確認。PoC の「COM 登録確認」で実際に存在するものを集計する）。</summary>
    public static readonly string[] ProgIdCandidates = ["JVDTLab.JVLink"];

    /// <summary>参照元実装の CLSID（レジストリの存在確認にだけ使う。公式未確認）。</summary>
    public const string ClsidCandidate = "{2AB1774D-0C41-11D7-916F-0003479BEB3F}";

    /// <summary>参照元実装の JVRead の呼出し形: JVRead("", 110000, "") → (ret, 文字列, ファイル名)。文字列は NUL 終端で、cp932 に再エンコードする。</summary>
    public const int ReadBuffer = 110_000;

    private static readonly Encoding Cp932 = CreateCp932();
    private object? com;
    private bool open;

    private ComJvLink(object com) => this.com = com;

    private static Encoding CreateCp932()
    {
        Encoding.RegisterProvider(CodePagesEncodingProvider.Instance);
        // 変換できない文字は例外にして、欠損を黙って隠さない（往復一致は PoC の G-AGENT-2 で検査する）
        return Encoding.GetEncoding(932, EncoderFallback.ExceptionFallback, DecoderFallback.ExceptionFallback);
    }

    /// <summary>COM を生成する（ワーカーの STA スレッドの中でだけ呼ぶ）。生成できなければ <see cref="WorkerOpException"/>（COM_UNAVAILABLE）。</summary>
    public static ComJvLink Create()
    {
        foreach (var progId in ProgIdCandidates)
        {
            var t = Type.GetTypeFromProgID(progId, throwOnError: false);
            if (t is null) continue;
            try
            {
                var o = Activator.CreateInstance(t);
                if (o is not null) return new ComJvLink(o);
            }
            catch (COMException)
            {
                // 次の候補を試す
            }
        }
        throw new WorkerOpException("COM_UNAVAILABLE");
    }

    private object Invoke(string name, object?[] args, bool[]? byRef = null)
    {
        var target = com ?? throw new ObjectDisposedException(nameof(ComJvLink));
        ParameterModifier[]? mods = null;
        if (byRef is not null)
        {
            var m = new ParameterModifier(args.Length);
            for (var i = 0; i < byRef.Length; i++) m[i] = byRef[i];
            mods = [m];
        }
        try
        {
            return target.GetType().InvokeMember(name, BindingFlags.InvokeMethod, null, target, args, mods, System.Globalization.CultureInfo.InvariantCulture, null)
                ?? throw new WorkerOpException("COM_NULL_RESULT");
        }
        catch (TargetInvocationException e) when (e.InnerException is COMException)
        {
            throw new WorkerOpException("COM_ERROR"); // HRESULT の詳細は出さない（固定のコードだけ）
        }
    }

    private static int ToInt(object v) => v switch
    {
        int i => i,
        short s => s,
        long l when l is >= int.MinValue and <= int.MaxValue => (int)l,
        _ => throw new WorkerOpException("COM_BAD_TYPE"),
    };

    private static string? ToStr(object? v) => v switch
    {
        null => null,
        string s => s,
        _ => throw new WorkerOpException("COM_BAD_TYPE"),
    };

    private static string Cut(string? s) => s is null ? "" : s.Split('\0', 2)[0];

    public int Init(string sid) => ToInt(Invoke("JVInit", [sid]));

    public OpenResult OpenStored(string dataspec, string fromtime, int option)
    {
        if (open) throw new WorkerOpException("OPEN_WHILE_OPEN");
        // 参照元実装の呼出し形（引数の意味は公式未確認）: JVOpen(dataspec, fromtime, option, ref readcount, ref downloadcount, ref lastfiletimestamp)
        object?[] args = [dataspec, fromtime, option, 0, 0, ""];
        var code = ToInt(Invoke("JVOpen", args, [false, false, false, true, true, true]));
        open = code >= 0;
        var last = Cut(ToStr(args[5]));
        return new OpenResult(code, args[3] is { } rc ? ToInt(rc) : 0, args[4] is { } dc ? ToInt(dc) : 0, last.Length == 0 ? null : last);
    }

    public OpenResult OpenRealtime(string dataspec, string key)
    {
        if (open) throw new WorkerOpException("OPEN_WHILE_OPEN");
        var code = ToInt(Invoke("JVRTOpen", [dataspec, key]));
        open = code >= 0;
        return new OpenResult(code);
    }

    /// <summary>
    /// JVRead（文字列で返る）。JVGets（バイト取得）の呼出し形は参照元実装に無く、公式仕様も未確認（JVGETS_BYTES）なので、ここには実装しない。
    /// 文字列 → cp932 の再エンコードは、変換できない文字があれば例外（COM_ENCODING）にする。公式のバイト扱いは未確認（JVGETS_BYTES / RECORD_LAYOUT）。
    /// </summary>
    public ReadResult Read()
    {
        object?[] args = ["", ReadBuffer, ""];
        var code = ToInt(Invoke("JVRead", args, [true, true, true]));
        var fname = Cut(ToStr(args[2]));
        byte[]? data = null;
        if (code > 0 && ToStr(args[0]) is { } text)
        {
            try
            {
                data = Cp932.GetBytes(Cut(text));
            }
            catch (EncoderFallbackException)
            {
                throw new WorkerOpException("COM_ENCODING");
            }
        }
        return ReadCodes.Interpret(code, data, fname.Length == 0 ? null : fname);
    }

    public int Close()
    {
        open = false;
        return com is null ? 0 : ToInt(Invoke("JVClose", []));
    }

    /// <summary>COM の解放もワーカーの中で行う。</summary>
    public void Dispose()
    {
        if (com is not null && Marshal.IsComObject(com)) Marshal.FinalReleaseComObject(com);
        com = null;
    }
}
