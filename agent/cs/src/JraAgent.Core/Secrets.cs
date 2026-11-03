using System.Globalization;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

namespace JraAgent.Core;

/// <summary>OS 提供の保護（Windows では DPAPI。JraAgent.Windows の DpapiProtector）の抽象。</summary>
public interface IProtector
{
    byte[] Protect(byte[] plain, byte[] entropy);

    /// <summary>復号できなければ <see cref="SecretException"/>。</summary>
    byte[] Unprotect(byte[] blob, byte[] entropy);
}

/// <summary>
/// 試験用。実行時に生成した鍵で AES-GCM する（ディスクに平文が残らないことの検査用）。DPAPI の代わりに本番では使わない
/// （本番の構成 <c>jvlink.mode = "com"</c> + Windows では選ばれない）。本番にハードコードされた鍵は無い。
/// </summary>
public sealed class FakeProtector : IProtector
{
    private readonly byte[] key = RandomNumberGenerator.GetBytes(32);

    public byte[] Protect(byte[] plain, byte[] entropy)
    {
        var nonce = RandomNumberGenerator.GetBytes(12);
        var cipher = new byte[plain.Length];
        var tag = new byte[16];
        using var aes = new AesGcm(key, 16);
        aes.Encrypt(nonce, plain, cipher, tag, entropy);
        return [.. nonce, .. cipher, .. tag];
    }

    public byte[] Unprotect(byte[] blob, byte[] entropy)
    {
        if (blob.Length < 28) throw new SecretException("復号できない");
        var plain = new byte[blob.Length - 28];
        try
        {
            using var aes = new AesGcm(key, 16);
            aes.Decrypt(blob.AsSpan(0, 12), blob.AsSpan(12, plain.Length), blob.AsSpan(blob.Length - 16), plain, entropy);
        }
        catch (CryptographicException)
        {
            throw new SecretException("復号できない");
        }
        return plain;
    }
}

public interface ISecretStore
{
    void Save(string name, byte[] data);

    byte[]? Load(string name);

    bool Delete(string name);
}

public sealed class MemorySecretStore : ISecretStore
{
    private readonly Dictionary<string, byte[]> items = new(StringComparer.Ordinal);

    public void Save(string name, byte[] data) => items[name] = [.. data];

    public byte[]? Load(string name) => items.TryGetValue(name, out var v) ? [.. v] : null;

    public bool Delete(string name) => items.Remove(name);
}

/// <summary>
/// <see cref="IProtector"/> で保護した BLOB をファイルに置く（旧 secrets_store.py と同じファイル名・entropy。旧版が保存した資格情報をそのまま読める）。
/// 書込みは一時ファイル + 置換（途中で落ちても壊れない）。
/// </summary>
public sealed class ProtectedFileSecretStore(string directory, IProtector protector) : ISecretStore
{
    private const string EntropyPrefix = "jra-agent/secret/v1|";

    private static byte[] Entropy(string name) => Encoding.UTF8.GetBytes(EntropyPrefix + name);

    private string PathOf(string name)
    {
        if (!ConfigLoader.IsIdent(name, 1, 64)) throw new SecretException("秘密の名前が不正");
        return Path.Combine(directory, name + ".bin");
    }

    public void Save(string name, byte[] data)
    {
        var path = PathOf(name);
        string? tmp = null;
        try
        {
            Directory.CreateDirectory(directory);
            var blob = protector.Protect(data, Entropy(name));
            tmp = Path.Combine(directory, ".tmp-" + Convert.ToHexStringLower(RandomNumberGenerator.GetBytes(8)));
            using (var f = new FileStream(tmp, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            {
                f.Write(blob);
                f.Flush(flushToDisk: true);
            }
            File.Move(tmp, path, overwrite: true);
            tmp = null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new SecretException("秘密を保存できない");
        }
        finally
        {
            if (tmp is not null)
            {
                try
                {
                    File.Delete(tmp);
                }
                catch (IOException)
                {
                    // 一時ファイルの削除に失敗しても、本体は置き換わっていないので安全
                }
            }
        }
    }

    public byte[]? Load(string name)
    {
        var path = PathOf(name);
        byte[] blob;
        try
        {
            blob = File.ReadAllBytes(path);
        }
        catch (Exception e) when (e is FileNotFoundException or DirectoryNotFoundException)
        {
            return null;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new SecretException("秘密を読めない");
        }
        return protector.Unprotect(blob, Entropy(name));
    }

    public bool Delete(string name)
    {
        var path = PathOf(name);
        try
        {
            if (!File.Exists(path)) return false;
            File.Delete(path);
            return true;
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException)
        {
            throw new SecretException("秘密を削除できない");
        }
    }
}

public sealed record Credential(string AgentId, string Token, DateTimeOffset? ExpiresAt)
{
    /// <summary>トークンを出さない。</summary>
    public override string ToString() => $"Credential(agent_id={AgentId}, expires_at={ExpiresAt?.ToString("O", CultureInfo.InvariantCulture)})";

    /// <summary>トークンが差し替えられたかの識別用（トークンから復元できない短い値）。</summary>
    public string Fingerprint() => Signing.Sha256Hex(Encoding.UTF8.GetBytes("jra-agent/credential-fp/v1|" + Token))[..16];

    public bool Expired(DateTimeOffset now) => ExpiresAt is { } e && now >= e;
}

/// <summary>トークンの保存・読出し・更新・失効。更新は新しいトークンで上書き（古い署名鍵は即座に使われなくなる）。</summary>
public sealed class CredentialStore(ISecretStore store)
{
    public const string Name = "credential";

    public void Save(Credential credential)
    {
        using var ms = new MemoryStream();
        using (var w = new Utf8JsonWriter(ms, new JsonWriterOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }))
        {
            w.WriteStartObject();
            w.WriteNumber("v", 1);
            w.WriteString("agent_id", credential.AgentId);
            w.WriteString("token", credential.Token);
            if (credential.ExpiresAt is { } e) w.WriteString("expires_at", e.ToUniversalTime().ToString("yyyy-MM-dd'T'HH:mm:ss.FFFFFFzzz", CultureInfo.InvariantCulture));
            else w.WriteNull("expires_at");
            w.WriteEndObject();
        }
        store.Save(Name, ms.ToArray());
    }

    public Credential? Load()
    {
        var raw = store.Load(Name);
        if (raw is null) return null;
        try
        {
            using var doc = JsonDocument.Parse(raw);
            var r = doc.RootElement;
            var exp = r.GetProperty("expires_at");
            DateTimeOffset? expires = exp.ValueKind == JsonValueKind.Null
                ? null
                : DateTimeOffset.Parse(exp.GetString() ?? throw new FormatException(), CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind);
            return new Credential(
                r.GetProperty("agent_id").GetString() ?? throw new FormatException(),
                r.GetProperty("token").GetString() ?? throw new FormatException(),
                expires);
        }
        catch (Exception e) when (e is JsonException or KeyNotFoundException or InvalidOperationException or FormatException)
        {
            throw new SecretException("保存された資格情報の形式が不正");
        }
    }

    /// <summary>端末から資格情報を消す（サーバ側の失効とは別。サーバでの失効は管理操作）。</summary>
    public bool RevokeLocal() => store.Delete(Name);
}
