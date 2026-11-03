using System.Diagnostics;
using System.Text;
using System.Text.Json;

namespace JraAgent.Core.Tests;

/// <summary>CLI（verify-config / verify-queue / status / diagnose / set-credential / update）と PoC の Mock（旧 test_cli_poc.py の移植）。実際に jraagent を子プロセスで起動する。</summary>
public sealed class CliTests : IDisposable
{
    private static readonly string Key = Signing.Base64Url(Enumerable.Range(0, 32).Select(i => (byte)i).ToArray());
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-cli-" + Guid.NewGuid().ToString("N"));
    private readonly string cfg;

    public CliTests()
    {
        Directory.CreateDirectory(dir);
        cfg = Path.Combine(dir, "agent.toml");
        var data = Path.Combine(dir, "data").Replace('\\', '/');
        File.WriteAllText(cfg,
            $"environment = \"development\"\nagent_id = \"ag_dev\"\ndata_dir = \"{data}\"\n[server]\nbase_url = \"http://127.0.0.1:18080\"\n"
            + $"[beacon]\nurl = \"http://127.0.0.1:18081/b.json\"\nkeys = [{{kid = \"dev-a\", public_key = \"{Key}\"}}]\n[jvlink]\nmode = \"fake\"\n");
    }

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        Directory.Delete(dir, recursive: true);
    }

    private static (int Rc, string Out, string Err) Run(string? stdin, params string[] args)
    {
        var psi = new ProcessStartInfo(WorkerTests.AgentExe, args)
        {
            UseShellExecute = false,
            RedirectStandardInput = true,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        using var p = Process.Start(psi)!;
        if (stdin is not null) p.StandardInput.Write(stdin);
        p.StandardInput.Close();
        var outTask = p.StandardOutput.ReadToEndAsync();
        var errTask = p.StandardError.ReadToEndAsync();
        Assert.True(p.WaitForExit(120_000), "jraagent が終わらない");
        return (p.ExitCode, outTask.Result, errTask.Result);
    }

    private (int Rc, string Out, string Err) Cli(params string[] args) => Run(null, ["--config", cfg, .. args]);

    [Fact]
    public void Verify_config_ok_and_rejects_bad()
    {
        var (rc, output, _) = Cli("verify-config");
        Assert.Equal(0, rc);
        using (var d = JsonDocument.Parse(output)) Assert.True(d.RootElement.GetProperty("ok").GetBoolean());
        var bad = Path.Combine(dir, "bad.toml");
        File.WriteAllText(bad, File.ReadAllText(cfg).Replace("[server]", "[server]\ntoken = \"jrat1_secret0123456789\"", StringComparison.Ordinal));
        var r = Run(null, "--config", bad, "verify-config");
        Assert.Equal(2, r.Rc);
        Assert.DoesNotContain("jrat1_secret", r.Out + r.Err, StringComparison.Ordinal);
    }

    [Fact]
    public void Usage_errors_exit_2()
    {
        Assert.Equal(2, Run(null).Rc);
        Assert.Equal(2, Cli("no-such-command").Rc);
        Assert.Equal(2, Cli("run", "--bogus").Rc);
        Assert.Equal(2, Run(null, "--config", Path.Combine(dir, "missing.toml"), "status").Rc);
    }

    [Fact]
    public void Verify_queue_and_status_and_clear_alert()
    {
        var (rc, output, _) = Cli("verify-queue");
        Assert.Equal(1, rc); // キューが無い
        Assert.Equal("{\n  \"exists\": false\n}", output.Trim().ReplaceLineEndings("\n"));
        var clock = new SystemClock();
        using (var q = new OutboxQueue(Path.Combine(dir, "data", "queue.sqlite3"), clock, new QueueConfig(MinFreeBytes: 0), _ => 1_000_000_000_000))
        {
            var bid = Envelope.Uuid7();
            q.Enqueue(bid, "R", "0B12", Envelope.Build("ag_dev", bid, "0B12", clock.Now, [new RawRecord("O1", "O10001"u8.ToArray())], true), 1, true);
            q.RaiseAlert("X", "detail");
        }
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        (rc, output, _) = Cli("verify-queue");
        Assert.Equal(0, rc);
        using (var d = JsonDocument.Parse(output))
        {
            Assert.Equal(1, d.RootElement.GetProperty("by_status").GetProperty("QUEUED").GetInt32());
            Assert.Equal(0, d.RootElement.GetProperty("problems").GetArrayLength());
        }
        (rc, output, _) = Cli("status");
        Assert.Equal(0, rc);
        using (var d = JsonDocument.Parse(output)) Assert.Equal("X", d.RootElement.GetProperty("queue").GetProperty("alerts")[0].GetString());
        (_, output, _) = Cli("clear-alert", "X");
        using (var d = JsonDocument.Parse(output)) Assert.True(d.RootElement.GetProperty("cleared").GetBoolean());
    }

    [Fact]
    public void Set_credential_never_echoes_token_and_diagnose_has_no_secrets()
    {
        var r = Run("jrat1_cli-secret-token-0123456789\n", "--config", cfg, "set-credential", "--stdin");
        Assert.Equal(0, r.Rc);
        Assert.DoesNotContain("cli-secret-token", r.Out + r.Err, StringComparison.Ordinal);
        var (rc, output, err) = Cli("diagnose");
        Assert.Equal(0, rc);
        Assert.DoesNotContain("cli-secret-token", output + err, StringComparison.Ordinal);
        using var d = JsonDocument.Parse(output);
        Assert.Equal(0, d.RootElement.GetProperty("jvlink_spec").GetProperty("confirmed").GetInt32()); // 公式仕様の確認項目は現在 0 件
        Assert.True(d.RootElement.GetProperty("jvlink_spec").GetProperty("total").GetInt32() >= 10);
        Assert.True(d.RootElement.GetProperty("environment").GetProperty("process_bits").GetInt32() is 32 or 64);
        Assert.Equal(OperatingSystem.IsWindows(), d.RootElement.GetProperty("com").GetProperty("applicable").GetBoolean());
        Assert.Equal(2, Run("bad\n", "--config", cfg, "set-credential", "--stdin").Rc);
        Assert.Equal(2, Run("jrat1_cli-secret-token-0123456789\n", "--config", cfg, "set-credential", "--stdin", "--expires-at", "2026-12-01T00:00:00").Rc); // タイムゾーンが無い
    }

    [Fact]
    public void Run_without_credential_exits_3()
    {
        var (rc, output, err) = Cli("run");
        Assert.Equal(3, rc);
        Assert.Empty(output);
        Assert.Contains("資格情報", err, StringComparison.Ordinal);
    }

    [Fact]
    public void Beacon_reset_requires_confirm()
    {
        Assert.Equal(2, Cli("beacon-reset").Rc);
        Assert.Equal(0, Cli("beacon-reset", "--confirm").Rc);
    }

    [Fact]
    public void Update_status_and_rollback_without_previous()
    {
        var root = Path.Combine(dir, "install");
        var (rc, output, _) = Cli("update", "status", "--install-root", root);
        Assert.Equal(0, rc);
        using (var d = JsonDocument.Parse(output)) Assert.Equal(JsonValueKind.Null, d.RootElement.GetProperty("current").ValueKind);
        var r = Cli("update", "rollback", "--install-root", root);
        Assert.Equal(2, r.Rc);
        Assert.Contains("NO_PREVIOUS", r.Err, StringComparison.Ordinal);
    }

    [Fact]
    public void Poc_mock_passes_all_runnable_gates_and_contains_no_record_contents()
    {
        var outPath = Path.Combine(dir, "mock.json");
        var (rc, _, _) = Run(null, "poc", "mock", "--out", outPath);
        Assert.Equal(0, rc);
        var text = File.ReadAllText(outPath);
        using var d = JsonDocument.Parse(text);
        var st = d.RootElement.GetProperty("gates").EnumerateArray().ToDictionary(g => g.GetProperty("gate").GetString()!, g => g.GetProperty("status").GetString());
        Assert.Equal(new Dictionary<string, string?>
        {
            ["G-AGENT-1"] = "NOT_RUN", ["G-AGENT-2"] = "MOCK_PASS", ["G-AGENT-3"] = "MOCK_PASS", ["G-AGENT-4"] = "MOCK_PASS", ["G-AGENT-5"] = "MOCK_PASS", ["G-AGENT-6"] = "NOT_RUN",
        }, st);
        Assert.True(d.RootElement.GetProperty("gates")[1].GetProperty("metrics").GetProperty("byte_exact").GetBoolean());
        Assert.Equal(1, d.RootElement.GetProperty("gates")[4].GetProperty("metrics").GetProperty("requeued_after_crash").GetInt32());
        Assert.DoesNotContain("bytesB64", text, StringComparison.Ordinal); // 内容を持たない
    }

    [Fact]
    public void Poc_real_refuses_without_explicit_flag()
    {
        var (rc, _, err) = Run(null, "poc", "g1");
        Assert.Equal(2, rc); // --allow-real-jvlink が無い（Windows 以外でも 2）
        Assert.NotEmpty(err);
    }

    [Fact]
    public void Worker_com_backend_is_refused_off_windows()
    {
        Assert.SkipWhen(OperatingSystem.IsWindows(), "Windows 以外での拒否だけを見る");
        Assert.Equal(2, Run(null, "worker", "--backend", "com").Rc);
    }
}
