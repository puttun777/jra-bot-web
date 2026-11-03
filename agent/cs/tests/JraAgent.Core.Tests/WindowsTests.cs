using System.Diagnostics;
using System.Text.Json.Nodes;
using JraAgent.Windows;

namespace JraAgent.Core.Tests;

/// <summary>
/// Windows 固有部分（SDK・JV-Link 不要。旧 test_winapi_windows.py の移植）。Windows 以外では skip。
/// CI の Windows ジョブでは『実行された件数』を検査し、skip を成功に数えない。
/// </summary>
public sealed class WindowsTests : IDisposable
{
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-win-" + Guid.NewGuid().ToString("N"));

    public WindowsTests() => Directory.CreateDirectory(dir);

    public void Dispose() => Directory.Delete(dir, recursive: true);

    [Fact]
    public void Dpapi_roundtrip_and_entropy_binding()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only");
        if (!OperatingSystem.IsWindows()) return;
        var p = new DpapiProtector();
        var blob = p.Protect("jrat1_dpapi-secret-value"u8.ToArray(), "e1"u8.ToArray());
        Assert.DoesNotContain("dpapi-secret-value", System.Text.Encoding.Latin1.GetString(blob), StringComparison.Ordinal);
        Assert.Equal("jrat1_dpapi-secret-value"u8.ToArray(), p.Unprotect(blob, "e1"u8.ToArray()));
        Assert.Throws<SecretException>(() => OperatingSystem.IsWindows() ? p.Unprotect(blob, "other-entropy"u8.ToArray()) : []);
        var store = new CredentialStore(new ProtectedFileSecretStore(dir, p));
        store.Save(new Credential("ag_main", "jrat1_dpapi-secret-value", null));
        Assert.All(Directory.GetFiles(dir), f => Assert.DoesNotContain("dpapi-secret-value", System.Text.Encoding.Latin1.GetString(File.ReadAllBytes(f)), StringComparison.Ordinal));
        Assert.Equal("jrat1_dpapi-secret-value", store.Load()?.Token);
    }

    [Fact]
    public void Job_object_kills_assigned_child_when_closed()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only");
        if (!OperatingSystem.IsWindows()) return;
        using var job = new KillOnCloseJob();
        using var p = Process.Start(new ProcessStartInfo("powershell.exe", ["-NoProfile", "-Command", "Start-Sleep -Seconds 60"]) { UseShellExecute = false, CreateNoWindow = true })!;
        Assert.True(job.Assign(p));
        var sw = Stopwatch.StartNew();
        job.Dispose(); // ジョブを閉じると OS が中のプロセスを終了する（エージェントが落ちた場合と同じ）
        Assert.True(p.WaitForExit(30_000));
        Assert.True(sw.Elapsed.TotalSeconds < 30);
    }

    [Fact]
    public void Worker_hang_recovery_with_job_object()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only");
        if (!OperatingSystem.IsWindows()) return;
        using var job = new KillOnCloseJob();
        var script = Path.Combine(dir, "s.json");
        File.WriteAllText(script, new FakeScript { Files = [["RA0001"u8.ToArray()]], HangAfter = 0 }.ToJson());
        var sup = new WorkerSupervisor(WorkerTests.WorkerArgv(script), new WorkerConfig(CallTimeoutSeconds: 2.0), new FakeClock(), p => Assert.True(OperatingSystem.IsWindows() && job.Assign(p)));
        try
        {
            sup.Call("open_stored", new JsonObject { ["dataspec"] = "RACE", ["fromtime"] = new string('0', 14), ["option"] = 1 });
            Assert.Throws<WorkerTimeoutException>(() => sup.Call("read_batch", new JsonObject { ["max_records"] = 5, ["max_bytes"] = 1000 }));
            Assert.True(sup.Call("ping")["pong"]!.GetValue<bool>());
        }
        finally
        {
            sup.Stop();
        }
    }

    [Fact]
    public void Com_registration_check_reads_registry_without_calling_jvlink()
    {
        Assert.SkipUnless(OperatingSystem.IsWindows(), "Windows only");
        if (!OperatingSystem.IsWindows()) return;
        var (progIds, _) = ComRegistration.Check(); // CI の Windows には JV-Link が無い = 未登録として返る（例外にならない）
        Assert.Equal(ComJvLink.ProgIdCandidates, progIds.Keys);
    }
}
