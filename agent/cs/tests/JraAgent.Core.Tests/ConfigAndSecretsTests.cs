using System.Text;

namespace JraAgent.Core.Tests;

/// <summary>設定の検証（HTTPS 強制・秘密を置けない・署名検証を省略できない）と、秘密の保護（旧 test_config_secrets.py の移植）。</summary>
public sealed class ConfigAndSecretsTests : IDisposable
{
    private static readonly string Key = Signing.Base64Url(Enumerable.Range(0, 32).Select(i => (byte)i).ToArray());
    private readonly string dir = Path.Combine(Path.GetTempPath(), "jraagent-cfg-" + Guid.NewGuid().ToString("N"));

    public void Dispose()
    {
        if (Directory.Exists(dir)) Directory.Delete(dir, recursive: true);
    }

    /// <summary>最小の本番設定。<paramref name="over"/> で表ごと差し替える（TOML の断片）。</summary>
    private static string Base(Dictionary<string, string>? over = null, string extraTop = "")
    {
        var tables = new Dictionary<string, string>
        {
            ["server"] = "base_url = \"https://ingest.example.run.app\"",
            ["beacon"] = $"url = \"https://storage.example/beacon.json\"\nkeys = [{{ kid = \"beacon-2026a\", public_key = \"{Key}\" }}]",
            ["jvlink"] = "mode = \"com\"",
        };
        var top = new Dictionary<string, string>
        {
            ["environment"] = "\"production\"",
            ["agent_id"] = "\"ag_main\"",
            ["data_dir"] = "\"C:/jraagent\"",
        };
        foreach (var (k, v) in over ?? [])
        {
            if (top.ContainsKey(k)) top[k] = v;
            else tables[k] = v;
        }
        var sb = new StringBuilder();
        foreach (var (k, v) in top) sb.Append(k).Append(" = ").Append(v).Append('\n');
        sb.Append(extraTop);
        foreach (var (k, v) in tables) sb.Append('[').Append(k).Append("]\n").Append(v).Append('\n');
        return sb.ToString();
    }

    [Fact]
    public void Minimal_production_config_parses()
    {
        var c = ConfigLoader.ParseText(Base());
        Assert.True(c.Production);
        Assert.StartsWith("https://", c.BaseUrl, StringComparison.Ordinal);
        Assert.Equal("beacon-2026a", c.Beacon.Keys[0].Kid);
        Assert.Equal(Enumerable.Range(0, 32).Select(i => (byte)i), c.Beacon.Keys[0].Raw);
        Assert.False(c.Update.Enabled);
        Assert.Equal(new QueueConfig(), c.Queue);
        Assert.Equal(Path.Combine("C:/jraagent", "queue.sqlite3"), c.QueuePath);
    }

    [Fact]
    public void Table_array_form_of_keys_and_numeric_overrides_parse()
    {
        var c = ConfigLoader.ParseText(Base(new()
        {
            ["beacon"] = $"url = \"https://s.example/b.json\"\nmax_age_seconds = 3600\n[[beacon.keys]]\nkid = \"a\"\npublic_key = \"{Key}\"\n[[beacon.keys]]\nkid = \"b\"\npublic_key = \"{Key}\"",
            ["sender"] = "sends_per_second = 1\nmax_conflict_wait_seconds = 60.5",
            ["queue"] = "max_batch_records = 100",
        }));
        Assert.Equal(["a", "b"], c.Beacon.Keys.Select(k => k.Kid));
        Assert.Equal(3600, c.Beacon.MaxAgeSeconds);
        Assert.Equal(1.0, c.Sender.SendsPerSecond);
        Assert.Equal(60.5, c.Sender.MaxConflictWaitSeconds);
        Assert.Equal(100, c.Queue.MaxBatchRecords);
    }

    public static TheoryData<string> InvalidConfigs() =>
    [
        Base(new() { ["server"] = "base_url = \"http://ingest.example\"" }), // 本番の平文 HTTP
        Base(new() { ["server"] = "base_url = \"https://127.0.0.1:8080\"" }), // 本番のループバック
        Base(new() { ["server"] = "base_url = \"https://localhost/\"" }),
        Base(new() { ["server"] = "base_url = \"ftp://x\"" }),
        Base(new() { ["server"] = "base_url = \"https://user:pw@ingest.example\"" }),
        Base(new() { ["server"] = "base_url = 1" }),
        Base(new() { ["beacon"] = $"url = \"http://storage.example/b.json\"\nkeys = [{{ kid = \"k\", public_key = \"{Key}\" }}]" }),
        Base(new() { ["beacon"] = "url = \"https://storage.example/b.json\"\nkeys = []" }), // 検証鍵が無い = 検証を省略する設定は作れない
        Base(new() { ["beacon"] = "url = \"https://storage.example/b.json\"" }),
        Base(new() { ["beacon"] = $"url = \"https://s.example/b.json\"\nverify = false\nkeys = [{{ kid = \"k\", public_key = \"{Key}\" }}]" }),
        Base(new() { ["beacon"] = "url = \"https://s.example/b.json\"\nkeys = [{ kid = \"k\", public_key = \"AAAA\" }]" }),
        Base(new() { ["beacon"] = $"url = \"https://s.example/b.json\"\nkeys = [{{ kid = \"k\", public_key = \"{Key}\", extra = 1 }}]" }),
        Base(new() { ["beacon"] = $"url = \"https://s.example/b.json\"\nkeys = [{{ kid = \"k\", public_key = \"{Key}\" }}, {{ kid = \"k\", public_key = \"{Key}\" }}]" }),
        Base(new() { ["beacon"] = $"url = \"https://s.example/b.json\"\nkeys = [{{ kid = \"k k\", public_key = \"{Key}\" }}]" }),
        Base(new() { ["beacon"] = $"url = \"https://s.example/b.json\"\nkeys = [{{ kid = \"鍵\", public_key = \"{Key}\" }}]" }), // 非 ASCII の kid
        Base(new() { ["jvlink"] = "mode = \"fake\"" }), // 本番で Fake を黙って使わない
        Base(new() { ["jvlink"] = "mode = \"real\"" }),
        Base(new() { ["agent_id"] = "\"x\"" }),
        Base(new() { ["agent_id"] = "\"エージェント\"" }),
        Base(new() { ["environment"] = "\"staging\"" }),
        Base(new() { ["queue"] = "max_batch_records = 201" }),
        Base(new() { ["queue"] = "max_batch_records = 1.5" }),
        Base(new() { ["queue"] = "max_batch_records = true" }),
        Base(new() { ["sender"] = "sends_per_second = \"2\"" }),
        Base(new() { ["sender"] = "sends_per_second = nan" }),
        Base(new() { ["update"] = "enabled = true" }),
        Base(new() { ["update"] = "enabled = \"yes\"" }),
        Base(new() { ["log"] = "retention_days = 0" }),
        Base(extraTop: "unknown_key = 1\n"),
        Base(extraTop: "server = 1\n").Replace("[server]\nbase_url = \"https://ingest.example.run.app\"\n", "", StringComparison.Ordinal),
        "not = [valid toml",
    ];

    [Theory]
    [MemberData(nameof(InvalidConfigs))]
    public void Invalid_configs_are_rejected(string toml)
    {
        Assert.Throws<ConfigException>(() => ConfigLoader.ParseText(toml));
    }

    [Theory]
    [InlineData("token")]
    [InlineData("password")]
    [InlineData("secret")]
    [InlineData("api_key")]
    [InlineData("sid")]
    [InlineData("jvlink_key")]
    [InlineData("private_key")]
    [InlineData("TOKEN")]
    public void Secrets_cannot_be_put_in_config(string key)
    {
        var toml = Base(new() { ["server"] = $"base_url = \"https://ingest.example.run.app\"\n{key} = \"jrat1_abcdefabcdefabcdef\"" });
        var e = Assert.Throws<ConfigException>(() => ConfigLoader.ParseText(toml));
        Assert.DoesNotContain("jrat1_abcdef", e.Message, StringComparison.Ordinal); // 値は例外に出さない
    }

    [Fact]
    public void Development_allows_loopback_http_only()
    {
        var c = ConfigLoader.ParseText(Base(new() { ["environment"] = "\"development\"", ["server"] = "base_url = \"http://127.0.0.1:18080\"", ["jvlink"] = "mode = \"fake\"" }));
        Assert.False(c.Production);
        Assert.Throws<ConfigException>(() => ConfigLoader.ParseText(Base(new() { ["environment"] = "\"development\"", ["server"] = "base_url = \"http://ingest.example\"" })));
    }

    [Fact]
    public void Error_messages_do_not_contain_values()
    {
        var e = Assert.Throws<ConfigException>(() => ConfigLoader.ParseText(Base(new() { ["server"] = "base_url = \"http://user:SECRETPW@evil.example\"" })));
        Assert.DoesNotContain("SECRETPW", e.Message, StringComparison.Ordinal);
    }

    [Fact]
    public void Data_dir_expands_environment_variables()
    {
        Environment.SetEnvironmentVariable("JRAAGENT_TEST_DIR", "/tmp/expanded");
        var c = ConfigLoader.ParseText(Base(new() { ["data_dir"] = "\"%JRAAGENT_TEST_DIR%/agent\"" }));
        Assert.Equal("/tmp/expanded/agent", c.DataDir);
    }

    [Fact]
    public void Load_reports_missing_file_without_path_details()
    {
        Assert.Throws<ConfigException>(() => ConfigLoader.Load(Path.Combine(dir, "missing.toml")));
    }

    // ------------------------------------------------------------------ 秘密
    [Fact]
    public void Credential_roundtrip_revoke_and_rotate()
    {
        var store = new CredentialStore(new ProtectedFileSecretStore(Path.Combine(dir, "secrets"), new FakeProtector()));
        Assert.Null(store.Load());
        var exp = new DateTimeOffset(2027, 1, 30, 0, 0, 0, TimeSpan.Zero);
        store.Save(new Credential("ag_main", "jrat1_first-token-value", exp));
        var got = store.Load();
        Assert.NotNull(got);
        Assert.Equal(("jrat1_first-token-value", exp), (got.Token, got.ExpiresAt));
        var fp1 = got.Fingerprint();
        store.Save(new Credential("ag_main", "jrat1_second-token-value", exp)); // 更新（再発行されたトークンで上書き）
        Assert.NotEqual(fp1, store.Load()?.Fingerprint());
        Assert.True(store.RevokeLocal());
        Assert.Null(store.Load());
        Assert.False(store.RevokeLocal());
    }

    [Fact]
    public void Credential_document_matches_the_python_format()
    {
        var mem = new MemorySecretStore();
        new CredentialStore(mem).Save(new Credential("ag_main", "t", new DateTimeOffset(2027, 1, 30, 0, 0, 0, TimeSpan.Zero)));
        Assert.Equal("{\"v\":1,\"agent_id\":\"ag_main\",\"token\":\"t\",\"expires_at\":\"2027-01-30T00:00:00+00:00\"}", Encoding.UTF8.GetString(mem.Load(CredentialStore.Name)!));
        // 旧 Python 版が保存した形式（json.dumps の区切りは ", " と ": "）も読める
        mem.Save(CredentialStore.Name, "{\"v\": 1, \"agent_id\": \"ag_main\", \"token\": \"t2\", \"expires_at\": null}"u8.ToArray());
        Assert.Equal(new Credential("ag_main", "t2", null), new CredentialStore(mem).Load());
        mem.Save(CredentialStore.Name, "{\"v\": 1}"u8.ToArray());
        Assert.Throws<SecretException>(() => new CredentialStore(mem).Load());
    }

    [Fact]
    public void Plain_token_is_never_written_to_disk()
    {
        var d = Path.Combine(dir, "secrets");
        new CredentialStore(new ProtectedFileSecretStore(d, new FakeProtector())).Save(new Credential("ag_main", "jrat1_PLAINTEXT-MARKER-0123456789", null));
        var blobs = Directory.GetFiles(d);
        Assert.NotEmpty(blobs);
        Assert.All(blobs, b => Assert.DoesNotContain("PLAINTEXT-MARKER", Encoding.Latin1.GetString(File.ReadAllBytes(b)), StringComparison.Ordinal));
    }

    [Fact]
    public void Protected_blob_is_not_decryptable_by_another_protector_or_tampered()
    {
        var d = Path.Combine(dir, "secrets");
        new ProtectedFileSecretStore(d, new FakeProtector()).Save("credential", "secret"u8.ToArray());
        Assert.Throws<SecretException>(() => new ProtectedFileSecretStore(d, new FakeProtector()).Load("credential")); // 別のユーザー/PC（別の鍵）では復号できない
        var s = new ProtectedFileSecretStore(d, new FakeProtector());
        s.Save("credential", "secret"u8.ToArray());
        var f = Path.Combine(d, "credential.bin");
        var raw = File.ReadAllBytes(f);
        raw[^1] ^= 0x01;
        File.WriteAllBytes(f, raw);
        Assert.Throws<SecretException>(() => s.Load("credential"));
        File.WriteAllBytes(f, [1, 2, 3]);
        Assert.Throws<SecretException>(() => s.Load("credential"));
        Assert.Empty(Directory.GetFiles(d, ".tmp-*"));
    }

    [Fact]
    public void Blob_is_bound_to_the_secret_name()
    {
        var d = Path.Combine(dir, "secrets");
        var s = new ProtectedFileSecretStore(d, new FakeProtector());
        s.Save("a", "x"u8.ToArray());
        File.Copy(Path.Combine(d, "a.bin"), Path.Combine(d, "b.bin"));
        Assert.Throws<SecretException>(() => s.Load("b")); // entropy に名前を含めるので、別の名前へ移した BLOB は復号できない
    }

    [Theory]
    [InlineData("../evil")]
    [InlineData("a/b")]
    [InlineData("")]
    [InlineData("名前")]
    public void Secret_name_cannot_escape_directory(string name)
    {
        var s = new ProtectedFileSecretStore(dir, new FakeProtector());
        Assert.Throws<SecretException>(() => s.Save(name, "x"u8.ToArray()));
    }

    [Fact]
    public void Credential_ToString_and_expiry()
    {
        var c = new Credential("ag_main", "jrat1_very-secret-token", new DateTimeOffset(2026, 12, 1, 0, 0, 0, TimeSpan.Zero));
        Assert.DoesNotContain("very-secret-token", c.ToString(), StringComparison.Ordinal);
        Assert.False(c.Expired(new DateTimeOffset(2026, 11, 30, 0, 0, 0, TimeSpan.Zero)));
        Assert.True(c.Expired(new DateTimeOffset(2026, 12, 1, 0, 0, 0, TimeSpan.Zero)));
        Assert.False(new Credential("a", "t", null).Expired(DateTimeOffset.UtcNow.AddDays(9999)));
    }

    [Fact]
    public void Memory_store_basic()
    {
        var m = new MemorySecretStore();
        m.Save("a", "1"u8.ToArray());
        Assert.Equal("1"u8.ToArray(), m.Load("a"));
        Assert.True(m.Delete("a"));
        Assert.Null(m.Load("a"));
    }

    [Fact]
    public void Redaction()
    {
        var text = "token=jrat1_abcdefghijklmnop X-Signature: " + new string('a', 64) + " X-Nonce: abcdefghijkl bytesB64='" + string.Concat(Enumerable.Repeat("QUJD", 10)) + "'";
        var output = Redact.Text(text);
        Assert.DoesNotContain("abcdefghijklmnop", output, StringComparison.Ordinal);
        Assert.DoesNotContain(new string('a', 64), output, StringComparison.Ordinal);
        Assert.DoesNotContain("abcdefghijkl", output, StringComparison.Ordinal);
        Assert.DoesNotContain(string.Concat(Enumerable.Repeat("QUJD", 10)), output, StringComparison.Ordinal);
    }
}
