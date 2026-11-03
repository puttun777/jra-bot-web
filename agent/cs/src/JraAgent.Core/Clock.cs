using System.Globalization;

namespace JraAgent.Core;

/// <summary>時計の抽象。試験では偽の時計で時間を進め、実際には待たない。</summary>
public interface IClock
{
    /// <summary>UTC の現在時刻。</summary>
    DateTimeOffset Now { get; }

    /// <summary>単調増加の秒（経過時間の測定用）。</summary>
    double Monotonic { get; }

    void Sleep(double seconds);
}

public sealed class SystemClock : IClock
{
    private static readonly long Start = System.Diagnostics.Stopwatch.GetTimestamp();

    public DateTimeOffset Now => DateTimeOffset.UtcNow;

    /// <summary>
    /// プロセス内の起点からの経過秒（0 以上）。起点を先に読んでから現在の時刻を取る
    /// （以前は <c>GetTimestamp() - Start</c> の順で、型の初期化が後から走ると初回の値が負になり、ワーカーの監視が最初の起動を『隔離中』と誤判定した）。
    /// </summary>
    public double Monotonic => System.Diagnostics.Stopwatch.GetElapsedTime(Start).TotalSeconds;

    public void Sleep(double seconds)
    {
        if (seconds > 0) Thread.Sleep(TimeSpan.FromSeconds(seconds));
    }
}

public static class Times
{
    public static long ToUnix(DateTimeOffset t) => t.ToUnixTimeSeconds();

    /// <summary>封筒の <c>observedAt</c> の形（UTC・Z・ミリ秒）。</summary>
    public static string IsoZ(DateTimeOffset t) => t.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", CultureInfo.InvariantCulture);
}
