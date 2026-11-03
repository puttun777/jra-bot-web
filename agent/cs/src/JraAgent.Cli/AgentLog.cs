using System.Globalization;
using System.Text;
using JraAgent.Core;

namespace JraAgent.Cli;

/// <summary>
/// エージェントのログ（<c>&lt;data_dir&gt;/logs/agent-yyyyMMdd.log</c>。日次で切り替え、保持日数を過ぎたものを消す）。
/// すべての行を <see cref="Redact.Text"/> に通す（最後の砦。呼び出し側が秘密を渡さないことが前提）。例外はメッセージを書かず型名だけ。
/// </summary>
internal sealed class AgentLog(string dir, int retentionDays, bool verbose = false) : IDisposable
{
    private readonly Lock gate = new();
    private StreamWriter? writer;
    private string? day;

    private void Rotate(DateTimeOffset now)
    {
        var d = now.ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        if (d == day && writer is not null) return;
        writer?.Dispose();
        Directory.CreateDirectory(dir);
        writer = new StreamWriter(new FileStream(Path.Combine(dir, $"agent-{d}.log"), FileMode.Append, FileAccess.Write, FileShare.Read), new UTF8Encoding(false)) { AutoFlush = true };
        day = d;
        var cutoff = now.AddDays(-retentionDays).ToString("yyyyMMdd", CultureInfo.InvariantCulture);
        foreach (var f in Directory.GetFiles(dir, "agent-*.log"))
        {
            var name = Path.GetFileNameWithoutExtension(f);
            if (name.Length == 14 && string.CompareOrdinal(name[6..], cutoff) < 0)
            {
                try
                {
                    File.Delete(f);
                }
                catch (IOException)
                {
                    // 使用中なら次の切り替えで消す
                }
            }
        }
    }

    private void Write(string level, string message)
    {
        lock (gate)
        {
            var now = DateTimeOffset.UtcNow;
            Rotate(now);
            writer?.WriteLine($"{Times.IsoZ(now)} {level} {Redact.Text(message)}");
        }
    }

    public void Info(string message) => Write("INFO", message);

    public void Debug(string message)
    {
        if (verbose) Write("DEBUG", message);
    }

    public void Error(string message) => Write("ERROR", message);

    public void Dispose()
    {
        lock (gate)
        {
            writer?.Dispose();
            writer = null;
        }
    }
}
