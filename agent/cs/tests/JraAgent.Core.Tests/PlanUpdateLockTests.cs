using System.Diagnostics;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using Org.BouncyCastle.Crypto.Generators;
using Org.BouncyCastle.Crypto.Parameters;
using Org.BouncyCastle.Crypto.Signers;
using Org.BouncyCastle.Security;

namespace JraAgent.Core.Tests;

/// <summary>取得計画の解釈、署名付き更新 manifest、多重起動の防止（旧 test_plan_update_lock.py の移植）。</summary>
public sealed class PlanUpdateLockTests : IDisposable
{
    private static readonly DateTimeOffset Now = new(2026, 11, 1, 0, 0, 0, TimeSpan.Zero);

    private const string Good = """
        {"planId": "p-1", "validUntil": "2026-11-01T06:00:00Z", "idle": false,
         "items": [
           {"dataspec": "RACE", "option": 2, "interval": 300, "notBefore": "2026-11-01T00:00:00Z"},
           {"dataspec": "0B12", "key": "20261101", "interval": 60}]}
        """;

    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-pul-" + Guid.NewGuid().ToString("N"));

    public PlanUpdateLockTests() => Directory.CreateDirectory(dir);

    public void Dispose() => Directory.Delete(dir, recursive: true);

    private static Plan P(string json)
    {
        using var d = JsonDocument.Parse(json);
        return PlanParser.Parse(d.RootElement);
    }

    // ------------------------------------------------------------------ 計画
    [Fact]
    public void Good_plan_parses_and_is_usable_until_valid_until()
    {
        var p = P(Good);
        Assert.Equal(("p-1", 2), (p.PlanId, p.Items.Count));
        Assert.True(p.Items[1].Realtime);
        Assert.False(p.Items[0].Realtime);
        Assert.True(p.Usable(Now));
        Assert.False(p.Usable(Now.AddHours(7)));
    }

    public static TheoryData<string> BadPlans() =>
    [
        "null", "[]", "\"x\"", "{\"planId\": \"p\", \"items\": [], \"idle\": false, \"extra\": 1}",
        "{\"planId\": \"\", \"items\": [], \"idle\": false}",
        "{\"planId\": 1, \"items\": [], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": null, \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [], \"idle\": \"no\"}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"NOPE\", \"option\": 1}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\"}], \"idle\": false}", // 蓄積系は option 必須
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"0B12\"}], \"idle\": false}", // 速報は key 必須
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"0B12\", \"key\": \"k\", \"option\": 1}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 99}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1.5}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": true}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"interval\": 1}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"interval\": null}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"key\": \"../x\"}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"notBefore\": \"2026-11-01T00:00:00\"}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"notBefore\": 5}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [{\"dataspec\": \"RACE\", \"option\": 1, \"bogus\": 1}], \"idle\": false}",
        "{\"planId\": \"p\", \"items\": [" + string.Join(",", Enumerable.Repeat("{\"dataspec\": \"RACE\", \"option\": 1}", 33)) + "], \"idle\": false}",
    ];

    [Theory]
    [MemberData(nameof(BadPlans))]
    public void Invalid_plan_becomes_idle_entirely(string json) => Assert.Same(Plan.IdlePlan, P(json));

    [Fact]
    public void Idle_flag_empty_and_expired_plans_do_nothing()
    {
        var s = new PlanScheduler();
        Assert.Empty(s.Due(Plan.IdlePlan, Now, 0.0));
        Assert.Empty(s.Due(P(Good.Replace("\"idle\": false", "\"idle\": true", StringComparison.Ordinal)), Now, 0.0));
        Assert.Empty(s.Due(P("{\"planId\": \"p\", \"items\": [], \"idle\": false}"), Now, 0.0));
        Assert.Empty(s.Due(P(Good), Now.AddDays(1), 0.0));
    }

    [Fact]
    public void Not_before_interval_and_duplicate_start()
    {
        var s = new PlanScheduler();
        var p = P(Good);
        Assert.Equal(["0B12"], s.Due(p, Now.AddMinutes(-1), 0.0).Select(i => i.Dataspec)); // RACE はまだ notBefore 前
        var due = s.Due(p, Now, 0.0);
        Assert.Equal(["RACE", "0B12"], due.Select(i => i.Dataspec));
        Assert.True(s.Start(due[0], 0.0));
        Assert.False(s.Start(due[0], 1.0)); // 同じ項目の重複起動は拒否
        Assert.Equal(["0B12"], s.Due(p, Now, 1.0).Select(i => i.Dataspec)); // 実行中の RACE は出ない
        s.Finish(due[0]);
        Assert.Equal("0B12", s.Due(p, Now, 10.0)[0].Dataspec); // RACE は interval(300) の間は出ない
        Assert.Contains("RACE", s.Due(p, Now, 301.0).Select(i => i.Dataspec));
    }

    // ------------------------------------------------------------------ 更新
    private sealed class Signer
    {
        private readonly Ed25519PrivateKeyParameters key;

        public Signer(string kid = "upd-a")
        {
            Kid = kid;
            var g = new Ed25519KeyPairGenerator();
            g.Init(new Ed25519KeyGenerationParameters(new SecureRandom()));
            key = (Ed25519PrivateKeyParameters)g.GenerateKeyPair().Private;
        }

        public string Kid { get; }

        public BeaconKey Public => new(Kid, key.GeneratePublicKey().GetEncoded());

        public JsonObject Manifest(byte[] data, string version = "0.2.0", Action<JsonObject>? over = null)
        {
            var body = new JsonObject
            {
                ["schema_version"] = 1, ["version"] = version, ["platform"] = "win-x64", ["sha256"] = Convert.ToHexStringLower(SHA256.HashData(data)),
                ["size"] = data.Length, ["url"] = "https://releases.example/agent.zip", ["issuedAt"] = "2026-11-01T00:00:00Z", ["kid"] = Kid,
            };
            over?.Invoke(body);
            using var d = JsonDocument.Parse(body.ToJsonString());
            var msg = CanonicalJson.Encode(d.RootElement);
            var s = new Ed25519Signer();
            s.Init(true, key);
            s.BlockUpdate(msg, 0, msg.Length);
            body["sig"] = Signing.Base64Url(s.GenerateSignature());
            return body;
        }
    }

    private static byte[] Bytes(JsonObject o) => System.Text.Encoding.UTF8.GetBytes(o.ToJsonString());

    private static byte[] Zip(Dictionary<string, byte[]> files, CompressionLevel level = CompressionLevel.NoCompression, int? unixMode = null)
    {
        using var ms = new MemoryStream();
        using (var z = new ZipArchive(ms, ZipArchiveMode.Create, leaveOpen: true))
        {
            foreach (var (n, d) in files)
            {
                var e = z.CreateEntry(n, level);
                if (unixMode is { } m) e.ExternalAttributes = m << 16;
                using var s = e.Open();
                s.Write(d);
            }
        }
        return ms.ToArray();
    }

    private static UpdateManifest Verify(Signer s, byte[] data, string version = "0.2.0", string current = "0.1.0") =>
        Updates.VerifyManifest(Bytes(s.Manifest(data, version)), [s.Public], current);

    [Fact]
    public void Valid_manifest_and_artifact()
    {
        var s = new Signer();
        var data = Zip(new() { ["agent.exe"] = "x"u8.ToArray() });
        var m = Verify(s, data);
        Assert.Equal("0.2.0", m.Version);
        Updates.VerifyArtifact(data, m);
        var flipped = (byte[])data.Clone();
        flipped[0] ^= 1;
        Assert.Equal("HASH_MISMATCH", Assert.Throws<VerificationException>(() => Updates.VerifyArtifact(flipped, m)).Reason);
        Assert.Equal("SIZE_MISMATCH", Assert.Throws<VerificationException>(() => Updates.VerifyArtifact([.. data, 1], m)).Reason);
    }

    [Theory]
    [InlineData("version", "\"0.1.0\"", "NOT_NEWER")] // 同一
    [InlineData("version", "\"0.0.9\"", "NOT_NEWER")] // 巻き戻り
    [InlineData("platform", "\"linux\"", "WRONG_PLATFORM")]
    [InlineData("url", "\"http://x.example/a.zip\"", "BAD_URL")]
    [InlineData("url", "\"https://u:p@x.example/a.zip\"", "BAD_URL")]
    [InlineData("sha256", "\"zz\"", "BAD_SHA256")]
    [InlineData("size", "0", "BAD_SIZE")]
    [InlineData("size", "10000000000", "BAD_SIZE")]
    [InlineData("version", "\"1.0\"", "BAD_VERSION")]
    [InlineData("issuedAt", "\"2026-11-01T00:00:00\"", "BAD_ISSUED_AT")]
    [InlineData("schema_version", "2", "BAD_SCHEMA_VERSION")]
    public void Manifest_rules(string key, string value, string reason)
    {
        var s = new Signer();
        var doc = s.Manifest(new byte[10], over: o => o[key] = JsonNode.Parse(value));
        Assert.Equal(reason, Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(doc), [s.Public], "0.1.0")).Reason);
    }

    [Fact]
    public void Forged_manifest_is_rejected()
    {
        var s = new Signer();
        var other = new Signer();
        var data = new byte[10];
        Assert.Equal("BAD_SIGNATURE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(other.Manifest(data)), [s.Public], "0.1.0")).Reason); // 別の鍵で署名
        var tampered = s.Manifest(data);
        tampered["sha256"] = new string('0', 64); // 署名後に差し替え
        Assert.Equal("BAD_SIGNATURE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(tampered), [s.Public], "0.1.0")).Reason);
        var unsigned = s.Manifest(data);
        unsigned.Remove("sig");
        Assert.Equal("BAD_SHAPE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(unsigned), [s.Public], "0.1.0")).Reason);
        var floatSize = s.Manifest(data);
        floatSize["size"] = 1.5; // 小数は正準形式（整数だけ）で署名できない = 署名の検証で拒否される
        Assert.Equal("BAD_SIGNATURE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(floatSize), [s.Public], "0.1.0")).Reason);
        var unknown = new Signer("upd-b").Manifest(data);
        Assert.Equal("UNKNOWN_KID", Assert.Throws<VerificationException>(() => Updates.VerifyManifest(Bytes(unknown), [s.Public], "0.1.0")).Reason);
        Assert.Equal("BAD_SHAPE", Assert.Throws<VerificationException>(() => Updates.VerifyManifest("{\"kid\": 1}"u8.ToArray(), [s.Public], "0.1.0")).Reason);
    }

    [Fact]
    public void Stage_activate_rollback_cycle_and_recovery_from_failed_update()
    {
        var s = new Signer();
        var inst = new UpdateInstaller(Path.Combine(dir, "install"));
        var d1 = Zip(new() { ["agent.exe"] = "v1"u8.ToArray(), ["lib/a.dll"] = "a"u8.ToArray() });
        var p = inst.Stage(d1, Verify(s, d1, "0.2.0"));
        Assert.Equal("v1"u8.ToArray(), File.ReadAllBytes(Path.Combine(p, "agent.exe")));
        Assert.Equal("a"u8.ToArray(), File.ReadAllBytes(Path.Combine(p, "lib", "a.dll")));
        Assert.Null(inst.State().Current);
        inst.Activate("0.2.0");
        var d2 = Zip(new() { ["agent.exe"] = "v2"u8.ToArray() });
        inst.Stage(d2, Verify(s, d2, "0.3.0", "0.2.0"));
        inst.Activate("0.3.0");
        Assert.Equal(new InstallState("0.3.0", "0.2.0", null), inst.State());
        Assert.Equal("0.2.0", inst.Rollback()); // 更新後に失敗 → 直前の版へ
        Assert.Equal("0.2.0", inst.State().Current);
        Assert.Equal("NO_PREVIOUS", Assert.Throws<VerificationException>(() => inst.Rollback()).Reason);
        Assert.Equal("NOT_STAGED", Assert.Throws<VerificationException>(() => inst.Activate("9.9.9")).Reason);
        Assert.Equal("ALREADY_STAGED", Assert.Throws<VerificationException>(() => inst.Stage(d2, Verify(s, d2, "0.3.0", "0.2.0"))).Reason);
    }

    private void StageExpect(byte[] data, string code, UpdateLimits? limits = null)
    {
        var s = new Signer();
        var inst = new UpdateInstaller(Path.Combine(dir, "install"), limits);
        var m = Verify(s, data);
        Assert.Equal(code, Assert.Throws<VerificationException>(() => inst.Stage(data, m)).Reason);
        Assert.Empty(Directory.GetFileSystemEntries(Path.Combine(dir, "install", "versions"))); // 何も展開されていない（残骸なし）
        Assert.False(File.Exists(Path.Combine(dir, "evil.exe")));
    }

    [Theory]
    [InlineData("../evil.exe")]
    [InlineData("/abs.exe")]
    [InlineData("a/../../evil")]
    [InlineData("..\\evil.exe")]
    [InlineData("C:\\evil.exe")]
    [InlineData("C:/evil.exe")]
    [InlineData("a\\..\\..\\evil")]
    [InlineData("\\abs.exe")]
    public void Stage_rejects_unsafe_paths(string name) => StageExpect(Zip(new() { [name] = "x"u8.ToArray() }), "UNSAFE_PATH");

    [Fact]
    public void Stage_rejects_hash_mismatch_without_leftovers()
    {
        var s = new Signer();
        var inst = new UpdateInstaller(Path.Combine(dir, "install"));
        var m = Verify(s, Zip(new() { ["a"] = "x"u8.ToArray() }));
        Assert.Equal("HASH_MISMATCH", Assert.Throws<VerificationException>(() => inst.Stage(Zip(new() { ["a"] = "y"u8.ToArray() }), m)).Reason);
        Assert.False(Directory.Exists(Path.Combine(dir, "install", "versions", "0.2.0")));
    }

    [Fact]
    public void Stage_rejects_not_a_zip() => StageExpect("not a zip at all"u8.ToArray(), "BAD_ZIP");

    [Fact]
    public void Stage_rejects_symlinks_and_special_files()
    {
        StageExpect(Zip(new() { ["link"] = "target"u8.ToArray() }, unixMode: 0xA1FF), "SYMLINK");
        StageExpect(Zip(new() { ["fifo"] = [] }, unixMode: 0x11B6), "SPECIAL_FILE");
    }

    [Fact]
    public void Stage_rejects_too_many_entries() =>
        StageExpect(Zip(Enumerable.Range(0, 11).ToDictionary(i => $"f{i}", _ => "x"u8.ToArray())), "TOO_MANY_ENTRIES", new UpdateLimits(MaxEntries: 10));

    [Fact]
    public void Stage_rejects_zip_bomb_by_declared_size_and_ratio()
    {
        StageExpect(Zip(new() { ["big"] = new byte[9_000] }, CompressionLevel.Optimal), "ENTRY_TOO_LARGE", new UpdateLimits(10_000, 8_000));
        StageExpect(Zip(Enumerable.Range(0, 3).ToDictionary(i => $"f{i}", _ => new byte[5_000]), CompressionLevel.Optimal), "UNPACKED_TOO_LARGE", new UpdateLimits(10_000, 8_000, MaxCompressionRatio: 10_000));
        StageExpect(Zip(new() { ["r"] = new byte[7_000] }, CompressionLevel.Optimal), "COMPRESSION_RATIO", new UpdateLimits(10_000, 8_000, MaxCompressionRatio: 5));
    }

    [Fact]
    public void Stage_rejects_duplicate_and_case_colliding_entries() =>
        StageExpect(Zip(new() { ["Agent.exe"] = "a"u8.ToArray(), ["agent.EXE"] = "b"u8.ToArray() }), "DUPLICATE_ENTRY"); // Windows では同じファイル

    [Fact]
    public void Version_ordering()
    {
        Assert.True(Updates.CompareVersions("1.10.0", "1.9.9") > 0);
        Assert.True(Updates.CompareVersions("0.2.0", "0.1.9") > 0);
        Assert.Equal("BAD_VERSION", Assert.Throws<VerificationException>(() => Updates.ParseVersion("01.0.0")).Reason);
        Assert.Equal("BAD_VERSION", Assert.Throws<VerificationException>(() => Updates.ParseVersion("99999999999999999999.0.0")).Reason);
    }

    // ------------------------------------------------------------------ 多重起動
    [Fact]
    public void Instance_lock_excludes_second_holder_and_is_released()
    {
        var path = Path.Combine(dir, "agent.lock");
        using (var first = new InstanceLock(path))
        {
            first.Acquire();
            Assert.Throws<AlreadyRunningException>(() => new InstanceLock(path).Acquire());
            if (!OperatingSystem.IsWindows() && File.Exists("/usr/bin/flock"))
            {
                // 別のプロセスからも排他が見える（.NET は Linux で flock を使う）
                using var p = Process.Start(new ProcessStartInfo("/usr/bin/flock", ["-n", path, "true"]) { UseShellExecute = false })!;
                p.WaitForExit();
                Assert.NotEqual(0, p.ExitCode);
            }
        }
        using var again = new InstanceLock(path);
        again.Acquire(); // 解放後は取れる
    }
}
