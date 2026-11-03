using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Runtime.Versioning;
using Microsoft.Win32.SafeHandles;

namespace JraAgent.Windows;

/// <summary>
/// Windows のジョブオブジェクト（JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE。旧 winapi.KillOnCloseJob の移植）。
/// エージェントが落ちたとき、ジョブに入れたワーカー（と、その子プロセス）が OS によって確実に終了する
/// （COM ワーカーが孤児として残り、JV-Link を掴んだままにならない）。<see cref="Dispose"/> でも終了する。
/// </summary>
[SupportedOSPlatform("windows")]
public sealed partial class KillOnCloseJob : IDisposable
{
    private const uint JobObjectLimitKillOnJobClose = 0x2000;
    private const int JobObjectExtendedLimitInformation = 9;

    [StructLayout(LayoutKind.Sequential)]
    private struct BasicLimitInformation
    {
        public long PerProcessUserTimeLimit;
        public long PerJobUserTimeLimit;
        public uint LimitFlags;
        public nuint MinimumWorkingSetSize;
        public nuint MaximumWorkingSetSize;
        public uint ActiveProcessLimit;
        public nuint Affinity;
        public uint PriorityClass;
        public uint SchedulingClass;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct IoCounters
    {
        public ulong ReadOperationCount;
        public ulong WriteOperationCount;
        public ulong OtherOperationCount;
        public ulong ReadTransferCount;
        public ulong WriteTransferCount;
        public ulong OtherTransferCount;
    }

    [StructLayout(LayoutKind.Sequential)]
    private struct ExtendedLimitInformation
    {
        public BasicLimitInformation BasicLimitInformation;
        public IoCounters IoInfo;
        public nuint ProcessMemoryLimit;
        public nuint JobMemoryLimit;
        public nuint PeakProcessMemoryUsed;
        public nuint PeakJobMemoryUsed;
    }

    [LibraryImport("kernel32.dll", EntryPoint = "CreateJobObjectW", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    private static partial SafeFileHandle CreateJobObject(nint attributes, string? name);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool SetInformationJobObject(SafeFileHandle job, int infoClass, ref ExtendedLimitInformation info, uint length);

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static partial bool AssignProcessToJobObject(SafeFileHandle job, nint process);

    private readonly SafeFileHandle handle;

    public KillOnCloseJob()
    {
        handle = CreateJobObject(0, null);
        if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastPInvokeError(), "ジョブオブジェクトを作れない");
        var info = new ExtendedLimitInformation { BasicLimitInformation = new BasicLimitInformation { LimitFlags = JobObjectLimitKillOnJobClose } };
        if (!SetInformationJobObject(handle, JobObjectExtendedLimitInformation, ref info, (uint)Marshal.SizeOf<ExtendedLimitInformation>()))
        {
            var err = Marshal.GetLastPInvokeError();
            handle.Dispose();
            throw new Win32Exception(err, "ジョブオブジェクトを設定できない");
        }
    }

    /// <summary>プロセスをジョブに入れる。入れられなければ false（呼び出し側はワーカーを使わない）。</summary>
    public bool Assign(Process p) => AssignProcessToJobObject(handle, p.Handle);

    public void Dispose() => handle.Dispose();
}
