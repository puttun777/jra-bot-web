using System.Diagnostics;
using System.IO.Compression;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Text.Json.Nodes;

namespace JraAgent.Core.Tests;

/// <summary>L5 の独立レビュー（Opus）の指摘の回帰試験（D-262）。</summary>
public sealed class ReviewRegressionTests : IDisposable
{
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-rr-" + Guid.NewGuid().ToString("N"));

    public ReviewRegressionTests() => Directory.CreateDirectory(dir);

    public void Dispose()
    {
        Microsoft.Data.Sqlite.SqliteConnection.ClearAllPools();
        Directory.Delete(dir, recursive: true);
    }

    // ---- 中1: 本文の読み取りにもタイムアウト
    [Fact]
    public void Response_body_read_times_out_when_the_server_stalls_after_headers()
    {
        var listener = new TcpListener(IPAddress.Loopback, 0);
        listener.Start();
        var port = ((IPEndPoint)listener.LocalEndpoint).Port;
        using var release = new ManualResetEventSlim();
        var server = new Thread(() =>
        {
            using var c = listener.AcceptTcpClient();
            var s = c.GetStream();
            var buf = new byte[4096];
            s.ReadExactly(buf, 0, 1); // 要求が届くまで待つ
            s.Write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\nContent-Type: application/json\r\n\r\nabc"u8);
            s.Flush();
            release.Wait(TimeSpan.FromSeconds(20)); // 本文の残りを送らずに止まる
        }) { IsBackground = true };
        server.Start();
        try
        {
            using var t = new HttpTransport();
            var sw = Stopwatch.StartNew();
            var e = Assert.Throws<TransportException>(() => t.Request("GET", $"http://127.0.0.1:{port}/", new Dictionary<string, string>(), null, 1.0));
            Assert.Equal("TIMEOUT", e.Kind);
            Assert.True(sw.Elapsed.TotalSeconds < 8, $"{sw.Elapsed.TotalSeconds}s");
        }
        finally
        {
            release.Set();
            listener.Stop();
        }
    }

    // ---- 中2: ビーコン・設定の例外を INVALID / 設定の誤りにする
    [Fact]
    public void Lone_surrogate_in_unsigned_fields_is_invalid_not_an_exception()
    {
        var signer = new BeaconSigner();
        var json = Encoding.UTF8.GetBytes("{\"schema_version\":1,\"mode\":\"NORMAL\",\"seq\":1,\"updatedAt\":\"2026-11-01T00:00:00Z\",\"validUntil\":null,\"kid\":\"\\ud800\",\"sig\":\"AAAA\"}");
        var e = Assert.Throws<VerificationException>(() => BeaconVerifier.Verify(json, [signer.Public], DateTimeOffset.UtcNow));
        Assert.Equal("BAD_SHAPE", e.Reason);
        var t = new ScriptedTransport { Default = new TransportResponse(200, new Dictionary<string, string>(), json) };
        var r = new BeaconClient("https://b.example/m.json", [signer.Public], t, new FakeClock(), new BeaconGuard(_ => null, (_, _) => { })).Fetch();
        Assert.Equal(BeaconStatus.Invalid, r.Status);
        Assert.Equal("BAD_SHAPE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(json, [signer.Public], "0.1.0")).Reason);
    }

    /// <summary>Ed25519 の点として復元できない 32 バイト（BouncyCastle が拒否するもの）を探す。</summary>
    private static byte[] InvalidPoint()
    {
        for (var b = 0; b < 256; b++)
        {
            var k = Enumerable.Repeat((byte)b, 32).ToArray();
            try
            {
                _ = new Org.BouncyCastle.Crypto.Parameters.Ed25519PublicKeyParameters(k, 0);
            }
            catch (ArgumentException)
            {
                return k;
            }
        }
        throw new InvalidOperationException("不正な点が見つからない");
    }

    [Fact]
    public void Public_key_that_is_not_a_curve_point_is_rejected_by_config_and_never_throws_in_verify()
    {
        var bad = InvalidPoint();
        Assert.False(BeaconVerifier.VerifyEd25519(bad, "m"u8.ToArray(), new byte[64]));
        var toml = $"environment = \"production\"\nagent_id = \"ag_main\"\ndata_dir = \"C:/x\"\n[server]\nbase_url = \"https://i.example\"\n"
            + $"[beacon]\nurl = \"https://b.example/m.json\"\nkeys = [{{ kid = \"k\", public_key = \"{Signing.Base64Url(bad)}\" }}]\n[jvlink]\nmode = \"com\"\n";
        Assert.Throws<ConfigException>(() => ConfigLoader.ParseText(toml));
    }

    // ---- 試験の穴: INVALID の後の UNREACHABLE で古い NORMAL を使わない
    [Fact]
    public void Invalid_beacon_then_unreachable_does_not_fall_back_to_the_previous_normal()
    {
        var clock = new FakeClock();
        var m = new ModeMachine(clock);
        m.OnBeacon(new BeaconResult(BeaconStatus.Valid, new Beacon("NORMAL", 5, "k", clock.Now, null)));
        m.OnServer(new Classified(Outcome.Ok, 200));
        Assert.Equal(AgentMode.NormalOk, m.Evaluate().Mode);
        m.OnBeacon(new BeaconResult(BeaconStatus.Invalid, Reason: "BAD_SIGNATURE"));
        Assert.NotEqual(AgentMode.NormalOk, m.Evaluate().Mode);
        clock.Advance(60);
        m.OnBeacon(new BeaconResult(BeaconStatus.Unreachable, Reason: "TIMEOUT"));
        m.OnServer(new Classified(Outcome.Ok, 200));
        Assert.NotEqual(AgentMode.NormalOk, m.Evaluate().Mode);
        Assert.False(m.MaySend);
    }

    // ---- 試験の穴: 本体が失敗したトランザクションは巻き戻り、次の書込みが通る
    [Fact]
    public void Failed_transaction_is_rolled_back_and_the_next_write_succeeds()
    {
        using var fx = new QueueFixture();
        var b = fx.EnqueueRealtime(fx.Queue);
        Assert.Throws<QueueException>(() => fx.Queue.Enqueue(b.BatchId, "R", "0B12", "{}"u8.ToArray(), 1, true));
        var c = fx.EnqueueRealtime(fx.Queue); // ROLLBACK が無いとトランザクションが開いたままで BEGIN IMMEDIATE が失敗する
        Assert.Equal(2, fx.Queue.Batches().Count);
        Assert.NotEqual(b.BatchId, c.BatchId);
    }

    // ---- 試験の穴: 無応答のワーカーは孫プロセスまで終了する（Linux）
    [Fact]
    public void Hung_worker_is_killed_with_its_children()
    {
        Assert.SkipWhen(OperatingSystem.IsWindows(), "sh を使う偽ワーカー（Windows はジョブオブジェクトの試験）");
        var pidFile = Path.Combine(dir, "child.pid");
        var script = $"sleep 600 & echo $! > '{pidFile}'; read l; echo '{{\"id\":1,\"ok\":true,\"result\":{{\"pong\":true}}}}'; sleep 600";
        var sup = new WorkerSupervisor(["/bin/sh", "-c", script], new WorkerConfig(CallTimeoutSeconds: 1.0, StartupTimeoutSeconds: 5.0), new FakeClock());
        try
        {
            Assert.Throws<WorkerTimeoutException>(() => sup.Call("init", new JsonObject { ["sid"] = "x" })); // ping は通り、init で止まる
            var child = int.Parse(File.ReadAllText(pidFile).Trim(), System.Globalization.CultureInfo.InvariantCulture);
            var deadline = DateTime.UtcNow.AddSeconds(10);
            while (DateTime.UtcNow < deadline && Directory.Exists($"/proc/{child}") && !File.ReadAllText($"/proc/{child}/stat").Contains(") Z ", StringComparison.Ordinal)) Thread.Sleep(100);
            Assert.True(!Directory.Exists($"/proc/{child}") || File.ReadAllText($"/proc/{child}/stat").Contains(") Z ", StringComparison.Ordinal), "孫プロセスが残っている");
        }
        finally
        {
            sup.Stop();
        }
    }

    // ---- 試験の穴: ロックを持つプロセスが死ぬと解放される（Linux。flock(1) で別プロセスに持たせる）
    [Fact]
    public void Lock_is_released_when_the_holder_process_dies()
    {
        Assert.SkipWhen(OperatingSystem.IsWindows() || !File.Exists("/usr/bin/flock"), "flock(1) が要る（Windows はファイルの共有モード）");
        var path = Path.Combine(dir, "agent.lock");
        File.WriteAllText(path, "");
        using var holder = Process.Start(new ProcessStartInfo("/usr/bin/flock", ["-x", path, "sleep", "600"]) { UseShellExecute = false })!;
        var deadline = DateTime.UtcNow.AddSeconds(10);
        var blocked = false;
        while (DateTime.UtcNow < deadline)
        {
            try
            {
                using var probe = new InstanceLock(path);
                probe.Acquire();
                Thread.Sleep(100); // まだ相手が取れていない
            }
            catch (AlreadyRunningException)
            {
                blocked = true;
                break;
            }
        }
        Assert.True(blocked, "別プロセスのロックが見えない");
        holder.Kill(entireProcessTree: true);
        holder.WaitForExit(10_000);
        // 強制終了しても OS がロックを解放する（クラッシュ後に起動できなくならない）。子の sleep が消えるまでの短い間だけ待つ
        var acquired = false;
        for (var end = DateTime.UtcNow.AddSeconds(10); !acquired && DateTime.UtcNow < end;)
        {
            try
            {
                using var again = new InstanceLock(path);
                again.Acquire();
                acquired = true;
            }
            catch (AlreadyRunningException)
            {
                Thread.Sleep(100);
            }
        }
        Assert.True(acquired, "保持者が死んでもロックが解放されない");
    }

    // ---- 低: 壊れた認証停止の記録は停止のまま
    [Fact]
    public void Corrupt_auth_halt_record_keeps_sending_halted()
    {
        using var fx = new QueueFixture();
        fx.Queue.SetState(Sender.AuthHaltKey, "{not json");
        var clock = fx.Clock;
        var client = new SignedClient("https://ingest.example", new RequestSigner("ag", "jrat1_tok"), new ScriptedTransport(), clock);
        var s = new Sender(fx.Queue, client, new ModeMachine(clock), new SenderConfig(), clock, "fp");
        Assert.Equal("AUTH_STATE_CORRUPT", s.AuthHalted());
        fx.Queue.SetState(Sender.AuthHaltKey, "{\"reason\":\"AUTH_401\"}");
        Assert.Equal("AUTH_STATE_CORRUPT", s.AuthHalted());
        fx.Queue.SetState(Sender.AuthHaltKey, "");
        Assert.Null(s.AuthHalted());
    }

    // ---- 低: 展開の衝突・Windows で同じ名前になるパス
    private static byte[] Zip(params string[] names)
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var n in names)
            {
                using var s = z.CreateEntry(n).Open();
                s.Write("x"u8);
            }
        }
        return ms.ToArray();
    }

    private static UpdateManifest Manifest(byte[] data) =>
        new("0.2.0", "win-x64", Convert.ToHexStringLower(System.Security.Cryptography.SHA256.HashData(data)), data.Length, "https://r.example/a.zip", DateTimeOffset.UtcNow);

    [Theory]
    [InlineData("EXTRACT_FAILED", "a", "a/b")] // ファイルとディレクトリが同じ名前
    [InlineData("UNSAFE_PATH", "./x")]
    [InlineData("UNSAFE_PATH", "a./x")] // Windows は末尾のドットを落とす
    [InlineData("UNSAFE_PATH", "a /x")] // 末尾の空白も同じ
    public void Stage_rejects_colliding_or_windows_ambiguous_entries_without_leftovers(string code, params string[] names)
    {
        var data = Zip(names);
        var inst = new UpdateInstaller(Path.Combine(dir, "install"));
        Assert.Equal(code, Assert.Throws<VerificationException>(() => inst.Stage(data, Manifest(data))).Reason);
        Assert.Empty(Directory.GetFileSystemEntries(Path.Combine(dir, "install", "versions")));
    }
}
