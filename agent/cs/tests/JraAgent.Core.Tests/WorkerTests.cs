using System.Diagnostics;
using System.Text;
using System.Text.Json.Nodes;

namespace JraAgent.Core.Tests;

/// <summary>
/// COM ワーカーの分離・ウォッチドッグ・回復（旧 test_worker.py の移植）。実際に子プロセス（jraagent worker --backend fake）を起動する。
/// COM の代わりに Fake。ハング・クラッシュを注入する。
/// </summary>
public sealed class WorkerTests : IDisposable
{
    private static readonly byte[][] Rec = [.. Enumerable.Range(0, 5).Select(i => Encoding.ASCII.GetBytes($"RA{i:000}" + new string('x', 20)))];
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-worker-" + Guid.NewGuid().ToString("N"));
    private readonly List<WorkerSupervisor> made = [];

    public WorkerTests() => Directory.CreateDirectory(dir);

    public void Dispose()
    {
        foreach (var s in made) s.Stop();
        Directory.Delete(dir, recursive: true);
    }

    /// <summary>試験の出力に置かれた jraagent（CLI）の実行ファイル。</summary>
    public static string AgentExe => Path.Combine(AppContext.BaseDirectory, OperatingSystem.IsWindows() ? "jraagent.exe" : "jraagent");

    public static IReadOnlyList<string> WorkerArgv(string scriptPath) => [AgentExe, "worker", "--backend", "fake", "--script", scriptPath];

    private WorkerSupervisor Make(FakeScript script, double call = 2.0, double startup = 15.0, int maxRestarts = 5, FakeClock? clock = null)
    {
        var p = Path.Combine(dir, "script.json");
        File.WriteAllText(p, script.ToJson());
        var s = new WorkerSupervisor(WorkerArgv(p), new WorkerConfig(call, startup, maxRestarts, 3600.0, 60.0), clock ?? new FakeClock());
        made.Add(s);
        return s;
    }

    private static JsonObject OpenArgs() => new() { ["dataspec"] = "RACE", ["fromtime"] = new string('0', 14), ["option"] = 1 };

    private static int Code(JsonObject o) => o["code"]!.GetValue<int>();

    [Fact]
    public void Reads_records_in_child_process_and_ends_at_eof()
    {
        var sup = Make(new FakeScript { Files = [Rec[..3], Rec[3..]] });
        Assert.Equal(0, Code(sup.Call("init", new JsonObject { ["sid"] = "UNKNOWN" })));
        var o = sup.Call("open_stored", new JsonObject { ["dataspec"] = "RACE", ["fromtime"] = "20261030000000", ["option"] = 2 });
        Assert.Equal((0, "20261031200000", 5), (Code(o), o["last_file_timestamp"]!.GetValue<string>(), o["read_count"]!.GetValue<int>()));
        var got = new List<byte[]>();
        var statuses = new List<string>();
        while (true)
        {
            var r = sup.Call("read_batch", new JsonObject { ["max_records"] = 2, ["max_bytes"] = 10_000, ["max_polls"] = 3, ["poll_sleep"] = 0.0 });
            got.AddRange(WorkerSupervisor.DecodeRecords(r));
            statuses.Add(r["status"]!.GetValue<string>());
            if (statuses[^1] == "EOF") break;
        }
        Assert.Equal(Rec, got); // バイト列が欠けずに順番どおり
        Assert.Contains("FILE_SWITCH", statuses); // ファイル切替で区切れる
        Assert.Equal(0, Code(sup.Call("close")));
    }

    [Fact]
    public void Child_process_is_a_different_process_and_stop_terminates_it()
    {
        var sup = Make(new FakeScript());
        sup.Call("ping");
        using var child = Process.GetProcessById(sup.Pid ?? throw new InvalidOperationException("no worker"));
        Assert.NotEqual(Environment.ProcessId, child.Id);
        sup.Stop();
        Assert.True(child.WaitForExit(5000));
    }

    [Fact]
    public void Hung_worker_is_killed_and_replaced_without_hanging_the_caller()
    {
        var sup = Make(new FakeScript { Files = [Rec], HangAfter = 2 }, call: 1.0);
        sup.Call("init", new JsonObject { ["sid"] = "x" });
        sup.Call("open_stored", OpenArgs());
        using var first = Process.GetProcessById(sup.Pid ?? throw new InvalidOperationException("no worker"));
        var sw = Stopwatch.StartNew();
        Assert.Throws<WorkerTimeoutException>(() => sup.Call("read_batch", new JsonObject { ["max_records"] = 100, ["max_bytes"] = 10_000 })); // 3 件目の read で戻らない
        Assert.True(sw.Elapsed.TotalSeconds < 6.0);
        Assert.True(first.WaitForExit(5000)); // ハングしたワーカーは強制終了された
        // 回復: 新しいワーカーで、最初から開き直せる（直前の open 状態は引き継がない）
        Assert.Equal(0, Code(sup.Call("open_stored", OpenArgs())));
        Assert.Equal((1, 1), (sup.Timeouts, sup.Restarts));
        Assert.NotEqual(first.Id, sup.Pid);
    }

    [Fact]
    public void Hang_during_open_is_detected()
    {
        var sup = Make(new FakeScript { HangInOpen = true }, call: 1.0);
        sup.Call("init", new JsonObject { ["sid"] = "x" });
        Assert.Throws<WorkerTimeoutException>(() => sup.Call("open_stored", OpenArgs()));
    }

    [Fact]
    public void Crashed_worker_is_reported_and_restarted()
    {
        var sup = Make(new FakeScript { Files = [Rec], CrashAfter = 1 });
        sup.Call("open_stored", OpenArgs());
        var e = Assert.Throws<WorkerCrashedException>(() => sup.Call("read_batch", new JsonObject { ["max_records"] = 100, ["max_bytes"] = 10_000 }));
        Assert.Equal("EXIT_139", e.Kind);
        Assert.True(sup.Call("ping")["pong"]!.GetValue<bool>());
        Assert.Equal(1, sup.Crashes);
    }

    [Fact]
    public void No_double_open_in_worker()
    {
        var sup = Make(new FakeScript { Files = [Rec] });
        sup.Call("open_stored", OpenArgs());
        var e = Assert.Throws<WorkerRemoteException>(() => sup.Call("open_stored", OpenArgs()));
        Assert.Equal("OPEN_WHILE_OPEN", e.Kind); // 同時に複数の JVOpen を実行しない
        sup.Call("close");
        Assert.Equal(0, Code(sup.Call("open_stored", OpenArgs())));
    }

    [Fact]
    public void Read_before_open_and_unknown_op_are_remote_errors()
    {
        var sup = Make(new FakeScript());
        Assert.Equal("NOT_OPEN", Assert.Throws<WorkerRemoteException>(() => sup.Call("read_batch", new JsonObject { ["max_records"] = 1, ["max_bytes"] = 1 })).Kind);
        Assert.Equal("UNKNOWN_OP", Assert.Throws<WorkerRemoteException>(() => sup.Call("format_disk")).Kind);
        Assert.Equal("BAD_ARGS", Assert.Throws<WorkerRemoteException>(() => sup.Call("init")).Kind);
    }

    [Fact]
    public void Restart_loop_is_limited_then_quarantined()
    {
        var clock = new FakeClock();
        var sup = Make(new FakeScript { Files = [Rec], CrashAfter = 0 }, maxRestarts: 3, clock: clock);
        var outcomes = new List<char>();
        for (var i = 0; i < 6; i++)
        {
            try
            {
                sup.Call("open_stored", OpenArgs());
                sup.Call("read_batch", new JsonObject { ["max_records"] = 10, ["max_bytes"] = 10_000 });
            }
            catch (WorkerQuarantinedException)
            {
                outcomes.Add('Q');
            }
            catch (WorkerCrashedException)
            {
                outcomes.Add('C');
            }
        }
        Assert.Equal("CCC", new string([.. outcomes.Take(3)])); // 3 回で再起動を止める（無限再起動ループにしない）
        Assert.Contains('Q', outcomes);
        Assert.Throws<WorkerQuarantinedException>(() => sup.Call("ping"));
        clock.Advance(3601 + 61); // クールダウンと窓が過ぎたら、再び起動を試みる
        Assert.True(sup.Call("ping")["pong"]!.GetValue<bool>());
    }

    [Fact]
    public void Downloading_is_reported_so_watchdog_sees_progress()
    {
        var sup = Make(new FakeScript { Files = [Rec], DownloadingPolls = 5 });
        sup.Call("open_stored", OpenArgs());
        var r = sup.Call("read_batch", new JsonObject { ["max_records"] = 10, ["max_bytes"] = 10_000, ["max_polls"] = 2, ["poll_sleep"] = 0.0 });
        Assert.Equal("DOWNLOADING", r["status"]!.GetValue<string>());
        Assert.Empty(r["records"]!.AsArray());
    }

    [Fact]
    public void Non_object_json_line_from_worker_is_a_protocol_error()
    {
        // ワーカーが JSON でも辞書でない行（配列など）を返しても、WorkerCrashed（BAD_PROTOCOL）として扱い、ワーカーを捨てる
        if (OperatingSystem.IsWindows()) return; // sh を使う偽ワーカー（Windows 版は Windows ジョブで確認する）
        var s = new WorkerSupervisor(["/bin/sh", "-c", "while read -r l; do echo '[]'; done"], new WorkerConfig(2.0, 5.0, 5, 3600.0, 60.0), new FakeClock());
        made.Add(s);
        Assert.Equal("BAD_PROTOCOL", Assert.Throws<WorkerCrashedException>(() => s.Call("ping")).Kind);
    }

    [Fact]
    public void Response_with_another_id_is_a_protocol_error()
    {
        if (OperatingSystem.IsWindows()) return;
        var s = new WorkerSupervisor(["/bin/sh", "-c", "while read -r l; do echo '{\"id\":999,\"ok\":true,\"result\":{}}'; done"], new WorkerConfig(2.0, 5.0, 5, 3600.0, 60.0), new FakeClock());
        made.Add(s);
        Assert.Equal("BAD_PROTOCOL", Assert.Throws<WorkerCrashedException>(() => s.Call("ping")).Kind);
    }

    [Fact]
    public void Missing_executable_is_reported_as_crash_not_unhandled()
    {
        var s = new WorkerSupervisor([Path.Combine(dir, "no-such-exe")], new WorkerConfig(2.0, 5.0, 5, 3600.0, 60.0), new FakeClock());
        made.Add(s);
        Assert.Equal("SPAWN_FAILED", Assert.Throws<WorkerCrashedException>(() => s.Call("ping")).Kind);
    }

    [Fact]
    public void Serve_handles_long_and_malformed_lines_without_crashing()
    {
        var input = new MemoryStream(Encoding.UTF8.GetBytes(new string('x', WorkerServer.MaxLine + 10) + "\nnot json\n{\"id\":1,\"op\":\"ping\"}\n"));
        var output = new MemoryStream();
        WorkerServer.Serve(new FakeJvLink(new FakeScript()), input, output);
        var lines = Encoding.UTF8.GetString(output.ToArray()).TrimEnd('\n').Split('\n');
        Assert.Equal(3, lines.Length);
        Assert.Contains("\"ok\":false", lines[0], StringComparison.Ordinal);
        Assert.Contains("\"ok\":false", lines[1], StringComparison.Ordinal);
        Assert.Equal("{\"id\":1,\"ok\":true,\"result\":{\"pong\":true}}", lines[2]);
    }

    [Fact]
    public void Fake_script_json_roundtrip_matches_the_python_format()
    {
        var s = new FakeScript { Files = [[[0x52, 0x41]]], HangAfter = 3, RealtimeRecords = [[0x4f, 0x31]] };
        var back = FakeScript.FromJson(s.ToJson());
        Assert.Equal("5241", Convert.ToHexStringLower(back.Files[0][0]));
        Assert.Equal(3, back.HangAfter);
        Assert.Null(back.CrashAfter);
        // 旧 Python の to_json の出力（キーは同じ）も読める
        var py = FakeScript.FromJson("{\"last_file_timestamp\": \"20261031200000\", \"open_code\": 0, \"init_code\": 0, \"close_code\": 0, \"read_error_after\": null, \"read_error_code\": -203, \"downloading_polls\": 0, \"hang_after\": null, \"hang_in_open\": false, \"crash_after\": 1, \"files\": [[\"5241\"]], \"realtime_records\": []}");
        Assert.Equal(1, py.CrashAfter);
    }
}
