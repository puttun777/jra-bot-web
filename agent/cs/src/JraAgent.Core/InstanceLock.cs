namespace JraAgent.Core;

public sealed class AlreadyRunningException(string message) : AgentException(message);

/// <summary>
/// 多重起動の防止（データフォルダの排他ロック。旧 lock.py の移植）。FileShare.None で開いたままにする
/// （Windows は共有モード、Linux は .NET が flock で排他する）。OS のロックなので、プロセスが落ちれば OS が解放する。
/// </summary>
public sealed class InstanceLock(string path) : IDisposable
{
    private FileStream? fh;

    public void Acquire()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path)) ?? ".");
        try
        {
            fh = new FileStream(path, FileMode.OpenOrCreate, FileAccess.ReadWrite, FileShare.None);
        }
        catch (IOException)
        {
            throw new AlreadyRunningException("別のエージェントが同じデータフォルダで動作中");
        }
        fh.SetLength(0);
        fh.Write(System.Text.Encoding.ASCII.GetBytes(Environment.ProcessId.ToString(System.Globalization.CultureInfo.InvariantCulture)));
        fh.Flush();
    }

    public void Release()
    {
        fh?.Dispose();
        fh = null;
    }

    public void Dispose() => Release();
}
