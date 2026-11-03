namespace JraAgent.Core;

/// <summary>JV-Link の読み取り結果の中立な状態（エージェント内部の語彙。数値の戻り値との対応は <see cref="ReadCodes"/>）。</summary>
public enum ReadStatus
{
    Record, // 1 レコード読めた
    FileSwitch, // ファイルの切替（安全な区切り）
    Downloading, // サーバからダウンロード中（まだ読めない）
    Eof, // 全部読み終えた
    Error, // エラー（Code に数値）
}

/// <param name="Code">0 以上が成功（仕様は未確認: ComJvLink が解釈する）。</param>
/// <param name="LastFileTimestamp">yyyyMMddHHmmss（次回の fromtime の元）。</param>
public sealed record OpenResult(int Code, int ReadCount = 0, int DownloadCount = 0, string? LastFileTimestamp = null)
{
    public bool Ok => Code >= 0;
}

/// <param name="Data">cp932 のバイト列（Record のときだけ）。</param>
public sealed record ReadResult(ReadStatus Status, byte[]? Data = null, int Code = 0, string? Filename = null);

/// <summary>
/// 1 つの JV-Link（COM）インスタンスの操作。同時に開けるのは 1 つだけ（open 中の open は拒否する）。
/// COM の実装（JraAgent.Windows の ComJvLink）と Fake（<see cref="FakeJvLink"/>）が同じ型を実装する。
/// </summary>
public interface IJvLink
{
    int Init(string sid);

    OpenResult OpenStored(string dataspec, string fromtime, int option);

    OpenResult OpenRealtime(string dataspec, string key);

    ReadResult Read();

    int Close();
}

/// <summary>
/// JV-Link の数値の戻り値 → 中立な状態の写像。<b>公式仕様で未確認</b>（<see cref="SpecRegistry"/> の JVREAD_RETURN）。
/// ここにある対応は参照元実装が使っている解釈で、二次情報。0 超 = 読めたバイト数、-1 = ファイル切替、-3 = ダウンロード中、0 = EOF、その他の負数 = エラー。
/// 確認が取れるまで、本番の取得はこの写像を使う経路を実行しない（ゲート）。
/// </summary>
public static class ReadCodes
{
    public const string UnconfirmedNotice = "戻り値の解釈は参照元実装に基づく二次情報（公式仕様未確認）";

    public static ReadResult Interpret(int code, byte[]? data, string? filename) => code switch
    {
        > 0 when data is null => new ReadResult(ReadStatus.Error, Code: -9001, Filename: filename), // バイト列を取り出せなかった
        > 0 => new ReadResult(ReadStatus.Record, data, code, filename),
        0 => new ReadResult(ReadStatus.Eof, Code: 0, Filename: filename),
        -1 => new ReadResult(ReadStatus.FileSwitch, Code: -1, Filename: filename),
        -3 => new ReadResult(ReadStatus.Downloading, Code: -3, Filename: filename),
        _ => new ReadResult(ReadStatus.Error, Code: code, Filename: filename),
    };
}

public enum SpecStatus
{
    Unconfirmed,
    Confirmed,
}

/// <summary>公式仕様で確認できていない項目に依存する処理を実行しようとした。</summary>
public sealed class SpecUnconfirmedException(IReadOnlyList<string> items) : AgentException("公式仕様未確認の項目: " + string.Join(", ", items))
{
    public IReadOnlyList<string> Items { get; } = items;
}

/// <param name="Source">確認済みなら「仕様書名 版 節/ページ」。</param>
public sealed record SpecItem(string Id, string Title, SpecStatus Status = SpecStatus.Unconfirmed, string? Source = null);

/// <summary>
/// JV-Link 仕様の確認状況（T1.17 の 12 項目。docs/JVLINK_DESIGN.md §12.2。旧 spec.py の移植）。
/// <b>公式仕様書で確認できた項目だけが Confirmed</b>。現在は 0 件。<see cref="Require"/> は未確認の項目が 1 つでもあれば拒否するので、
/// 本番の取得（<c>jvlink.mode = "com"</c>）は確認が記録されるまで開始されない。確認が取れたら、仕様書名・版・節/ページを Source に書いて
/// Confirmed に変える（コード変更 + レビュー）。環境変数や設定ファイルで迂回する手段は作らない。
/// </summary>
public sealed class SpecRegistry
{
    // JVLINK_DESIGN §12.2 の 12 項目 + 取得に必要な周辺（すべて未確認）。参照元実装の使用実態は二次情報で、確認には数えない
    private static readonly SpecItem[] Items =
    [
        new("JVINIT", "JVInit の引数（ソフトウェア ID）と戻り値"),
        new("JVOPEN_OPTION", "JVOpen の option の値と意味（T1.17 #1）"),
        new("JVOPEN_RETURN", "JVOpen の戻り値（readcount・downloadcount・lastfiletimestamp・エラーコード）"),
        new("JVRTOPEN_KEY", "JVRTOpen の key 形式と dataspec（T1.17 #2）"),
        new("JVREAD_RETURN", "JVRead の戻り値（-1 ファイル切替・-3 ダウンロード中・0 EOF・-402/-403）とバッファ（T1.17 #3）"),
        new("JVGETS_BYTES", "JVGets（バイト取得）の利用可否と COM での型（T1.17 #4）"),
        new("JVCLOSE", "JVClose の戻り値と、読み途中で呼んだときの挙動"),
        new("DATA_KUBUN", "データ区分 0・9 の意味と順位（T1.17 #8）"),
        new("TIMESTAMP_RULES", "取得日時・作成日時の規則（T1.17 #9）"),
        new("RT_RETENTION", "速報データの保持期間（T1.17 #10）"),
        new("RECORD_LAYOUT", "レコード長とバイト位置・文字コード（T1.17 #12）"),
        new("DATASPEC_LIST", "dataspec の公式一覧（RACE・YSCH・RCVN・RCOV・0B11〜0B35）"),
    ];

    // 取得の種類ごとに依存する項目
    public static readonly string[] FetchStoredItems = ["JVINIT", "JVOPEN_OPTION", "JVOPEN_RETURN", "JVREAD_RETURN", "JVCLOSE", "DATASPEC_LIST", "TIMESTAMP_RULES"];
    public static readonly string[] FetchRealtimeItems = ["JVINIT", "JVRTOPEN_KEY", "JVREAD_RETURN", "JVCLOSE", "DATASPEC_LIST"];
    public static readonly string[] ReadBytesItems = ["JVGETS_BYTES"]; // JVGets を使う場合。JVRead の文字列を cp932 に再エンコードする方式は RECORD_LAYOUT の確認も要る

    private readonly Dictionary<string, SpecItem> items;

    public SpecRegistry(IEnumerable<SpecItem>? items = null) => this.items = (items ?? Items).ToDictionary(i => i.Id, StringComparer.Ordinal);

    /// <summary>本番の表（すべて未確認）。</summary>
    public static SpecRegistry Default { get; } = new();

    public SpecStatus Status(string id) => items[id].Status;

    public IReadOnlyList<string> Unconfirmed(IEnumerable<string> ids) => [.. ids.Where(i => items[i].Status != SpecStatus.Confirmed)];

    public void Require(params string[] ids)
    {
        var missing = Unconfirmed(ids);
        if (missing.Count > 0) throw new SpecUnconfirmedException(missing);
    }

    public IReadOnlyDictionary<string, string> Summary() => items.Values.ToDictionary(i => i.Id, i => i.Status == SpecStatus.Confirmed ? "CONFIRMED" : "UNCONFIRMED", StringComparer.Ordinal);

    /// <summary><b>試験専用</b>（internal。本番のアセンブリの外からは呼べない）: Fake の JV-Link で取得の流れを検証するための、全項目が確認済みの仮の表。本番の経路では使わない。</summary>
    internal static SpecRegistry AllConfirmedForTests() => new(Items.Select(i => i with { Status = SpecStatus.Confirmed, Source = "test-only" }));
}
