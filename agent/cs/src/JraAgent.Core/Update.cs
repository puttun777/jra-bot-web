using System.Globalization;
using System.IO.Compression;
using System.Security.Cryptography;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;

namespace JraAgent.Core;

public sealed record UpdateManifest(string Version, string Platform, string Sha256, long Size, string Url, DateTimeOffset IssuedAt);

/// <summary>
/// 署名付き更新 manifest の検証と、更新物の段階的な導入（ステージング → 切替 → ロールバック。旧 update.py の移植）。
/// <list type="bullet">
/// <item>manifest は Ed25519 署名（update.keys の公開鍵 = kid 付き最大 2 本。秘密鍵は CI/リリース作業者の手元にあり、このリポジトリにもサーバにも無い）。</item>
/// <item>検証: 署名 → 形式 → バージョンが現在より新しい（巻き戻り・同一は拒否）→ プラットフォーム → HTTPS の URL → 更新物の SHA-256 とサイズ。</item>
/// <item>未署名の実行ファイルを自動でインストールしない。<see cref="UpdateInstaller.Stage"/> はハッシュを確認して別ディレクトリへ置くだけ。切替は利用者の明示操作。</item>
/// </list>
/// </summary>
public static partial class Updates
{
    public const long MaxArtifactBytes = 200L * 1024 * 1024;

    private static readonly HashSet<string> Keys = ["schema_version", "version", "platform", "sha256", "size", "url", "issuedAt", "kid", "sig"];

    [GeneratedRegex("^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)$")]
    private static partial Regex SemVer();

    [GeneratedRegex("^[0-9a-f]{64}$")]
    private static partial Regex Sha256Re();

    [GeneratedRegex("[+-][0-9]{2}:[0-9]{2}$")]
    private static partial Regex OffsetRe();

    public static (long, long, long) ParseVersion(string text)
    {
        var m = SemVer().Match(text);
        if (!m.Success) throw new VerificationException("BAD_VERSION");
        try
        {
            return (long.Parse(m.Groups[1].Value, CultureInfo.InvariantCulture), long.Parse(m.Groups[2].Value, CultureInfo.InvariantCulture), long.Parse(m.Groups[3].Value, CultureInfo.InvariantCulture));
        }
        catch (OverflowException)
        {
            throw new VerificationException("BAD_VERSION");
        }
    }

    public static int CompareVersions(string a, string b) => ParseVersion(a).CompareTo(ParseVersion(b));

    public static UpdateManifest VerifyManifest(byte[] json, IReadOnlyList<BeaconKey> keys, string currentVersion, string platform = "win-x64")
    {
        JsonDocument doc;
        try
        {
            doc = JsonDocument.Parse(json);
        }
        catch (JsonException)
        {
            throw new VerificationException("BAD_SHAPE");
        }
        using (doc)
        {
            var root = doc.RootElement;
            if (root.ValueKind != JsonValueKind.Object) throw new VerificationException("BAD_SHAPE");
            var names = root.EnumerateObject().Select(p => p.Name).ToList();
            if (names.Count != names.Distinct(StringComparer.Ordinal).Count() || !Keys.SetEquals(names)) throw new VerificationException("BAD_SHAPE");
            var kid = root.GetProperty("kid");
            var sig = root.GetProperty("sig");
            var key = kid.ValueKind == JsonValueKind.String ? keys.FirstOrDefault(k => k.Kid == kid.GetString()) : null;
            if (key is null || sig.ValueKind != JsonValueKind.String) throw new VerificationException("UNKNOWN_KID");
            try
            {
                if (!BeaconVerifier.VerifyEd25519(key.Raw, CanonicalJson.EncodeWithout(root, "sig"), Signing.Base64UrlDecode(sig.GetString() ?? "")))
                    throw new VerificationException("BAD_SIGNATURE"); // 偽 manifest
            }
            catch (FormatException)
            {
                throw new VerificationException("BAD_SIGNATURE");
            }
            catch (VerificationException)
            {
                throw new VerificationException("BAD_SIGNATURE");
            }
            // ここから先は署名済みの内容
            if (root.GetProperty("schema_version") is not { ValueKind: JsonValueKind.Number } sv || sv.GetRawText() != "1") throw new VerificationException("BAD_SCHEMA_VERSION");
            var version = root.GetProperty("version");
            if (version.ValueKind != JsonValueKind.String || CompareVersions(version.GetString() ?? "", currentVersion) <= 0) throw new VerificationException("NOT_NEWER"); // 巻き戻り・同一は拒否
            var plat = root.GetProperty("platform");
            if (plat.ValueKind != JsonValueKind.String || plat.GetString() != platform) throw new VerificationException("WRONG_PLATFORM");
            var sha = root.GetProperty("sha256");
            if (sha.ValueKind != JsonValueKind.String || !Sha256Re().IsMatch(sha.GetString() ?? "")) throw new VerificationException("BAD_SHA256");
            var size = root.GetProperty("size");
            if (size.ValueKind != JsonValueKind.Number || !long.TryParse(size.GetRawText(), NumberStyles.None, CultureInfo.InvariantCulture, out var n) || n <= 0 || n > MaxArtifactBytes)
                throw new VerificationException("BAD_SIZE");
            var url = root.GetProperty("url");
            if (url.ValueKind != JsonValueKind.String || !Uri.TryCreate(url.GetString(), UriKind.Absolute, out var u) || u.Scheme != Uri.UriSchemeHttps || !string.IsNullOrEmpty(u.UserInfo))
                throw new VerificationException("BAD_URL");
            var issued = root.GetProperty("issuedAt");
            var s = issued.ValueKind == JsonValueKind.String ? issued.GetString() ?? "" : "";
            if (!(s.EndsWith('Z') || OffsetRe().IsMatch(s)) || !DateTimeOffset.TryParse(s, CultureInfo.InvariantCulture, DateTimeStyles.None, out var when))
                throw new VerificationException("BAD_ISSUED_AT");
            return new UpdateManifest(version.GetString() ?? "", platform, sha.GetString() ?? "", n, url.GetString() ?? "", when.ToUniversalTime());
        }
    }

    public static void VerifyArtifact(byte[] data, UpdateManifest m)
    {
        if (data.Length != m.Size) throw new VerificationException("SIZE_MISMATCH");
        if (Convert.ToHexStringLower(SHA256.HashData(data)) != m.Sha256) throw new VerificationException("HASH_MISMATCH");
    }
}

public sealed record InstallState(string? Current, string? Previous, string? Staged);

/// <summary>展開の上限（zip bomb 対策。署名・ハッシュ検証済みの成果物でも、展開前に宣言サイズを確認し、展開中は実際に書いた量でも止める）。</summary>
public sealed record UpdateLimits(
    long MaxUnpackedBytes = 1024L * 1024 * 1024,
    long MaxUnpackedFileBytes = 512L * 1024 * 1024,
    int MaxEntries = 5000,
    double MaxCompressionRatio = 200); // 宣言サイズ / 圧縮サイズ（0 バイトの圧縮は除く）

/// <summary><c>&lt;root&gt;/versions/&lt;ver&gt;/</c>（展開先）と <c>&lt;root&gt;/current.json</c>（現在・直前の版）。起動スクリプトが current.json の指す版を使う。</summary>
public sealed class UpdateInstaller(string root, UpdateLimits? limits = null)
{
    private readonly UpdateLimits lim = limits ?? new UpdateLimits();

    private string StatePath => Path.Combine(root, "current.json");

    private string Versions => Path.Combine(root, "versions");

    public InstallState State()
    {
        try
        {
            var o = JsonNode.Parse(File.ReadAllText(StatePath)) as JsonObject;
            static string? S(JsonObject? o, string k) => o?[k] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;
            return new InstallState(S(o, "current"), S(o, "previous"), S(o, "staged"));
        }
        catch (Exception e) when (e is IOException or UnauthorizedAccessException or JsonException)
        {
            return new InstallState(null, null, null);
        }
    }

    private void Write(InstallState st)
    {
        Directory.CreateDirectory(root);
        var tmp = StatePath + ".tmp";
        File.WriteAllText(tmp, new JsonObject { ["current"] = st.Current, ["previous"] = st.Previous, ["staged"] = st.Staged }.ToJsonString());
        File.Move(tmp, StatePath, overwrite: true); // 原子的な置換（途中で落ちても壊れない）
    }

    /// <summary>ハッシュ・サイズを確認し、zip を別ディレクトリへ安全に展開する（パストラバーサル・絶対パス・リンク・特殊ファイル・zip bomb を拒否）。現在の版は触らない。</summary>
    public string Stage(byte[] data, UpdateManifest m)
    {
        Updates.VerifyArtifact(data, m);
        Updates.ParseVersion(m.Version); // 版はディレクトリ名に使う（検証済みの semver だけ）
        var dest = Path.Combine(Versions, m.Version);
        if (Directory.Exists(dest)) throw new VerificationException("ALREADY_STAGED");
        var tmp = Path.Combine(Versions, $".{m.Version}.tmp");
        if (Directory.Exists(tmp)) Directory.Delete(tmp, recursive: true);
        Directory.CreateDirectory(tmp);
        var tmpFull = Path.GetFullPath(tmp) + Path.DirectorySeparatorChar;
        try
        {
            using var z = new ZipArchive(new MemoryStream(data), ZipArchiveMode.Read);
            if (z.Entries.Count > lim.MaxEntries) throw new VerificationException("TOO_MANY_ENTRIES");
            long total = 0;
            var seen = new HashSet<string>(StringComparer.Ordinal);
            foreach (var e in z.Entries)
            {
                // Windows の区切り（\）・ドライブ指定（C:）・絶対パス・`..`・NUL をすべて拒否する（OS ごとのパスの解釈の差に頼らない）
                var name = e.FullName;
                var parts = name.Replace('\\', '/').Split('/');
                if (name.Length == 0 || name.StartsWith('/') || name.StartsWith('\\') || parts.Contains("..") || name.Contains(':', StringComparison.Ordinal) || name.Contains('\0', StringComparison.Ordinal))
                    throw new VerificationException("UNSAFE_PATH");
                var target = Path.GetFullPath(Path.Combine(tmp, name.Replace('\\', '/')));
                if (!target.StartsWith(tmpFull, StringComparison.Ordinal) && target + Path.DirectorySeparatorChar != tmpFull) throw new VerificationException("UNSAFE_PATH");
                var mode = (e.ExternalAttributes >> 16) & 0xF000; // Unix の st_mode の種別
                if (mode == 0xA000) throw new VerificationException("SYMLINK");
                if (mode is not (0 or 0x8000 or 0x4000)) throw new VerificationException("SPECIAL_FILE"); // 通常のファイル・ディレクトリ以外（デバイス・FIFO など）
                var key = string.Join("/", parts.Where(p => p.Length > 0)).ToLowerInvariant();
                if (!seen.Add(key)) throw new VerificationException("DUPLICATE_ENTRY"); // 同じパス（大文字小文字違いを含む）の重ね書きを許さない
                if (e.Length > lim.MaxUnpackedFileBytes) throw new VerificationException("ENTRY_TOO_LARGE");
                if (e.CompressedLength > 0 && (double)e.Length / e.CompressedLength > lim.MaxCompressionRatio) throw new VerificationException("COMPRESSION_RATIO");
                total += e.Length;
                if (total > lim.MaxUnpackedBytes) throw new VerificationException("UNPACKED_TOO_LARGE");
            }
            long written = 0;
            foreach (var e in z.Entries)
            {
                var target = Path.GetFullPath(Path.Combine(tmp, e.FullName.Replace('\\', '/')));
                if (e.FullName.EndsWith('/') || e.FullName.EndsWith('\\'))
                {
                    Directory.CreateDirectory(target);
                    continue;
                }
                Directory.CreateDirectory(Path.GetDirectoryName(target) ?? tmp);
                using var src = e.Open();
                using var dst = new FileStream(target, FileMode.CreateNew, FileAccess.Write);
                var buf = new byte[81920];
                long entryBytes = 0;
                int r;
                while ((r = src.Read(buf, 0, buf.Length)) > 0)
                {
                    entryBytes += r;
                    written += r;
                    // 宣言サイズを偽った zip でも、実際に書いた量で止める
                    if (entryBytes > e.Length || written > lim.MaxUnpackedBytes) throw new VerificationException("UNPACKED_TOO_LARGE");
                    dst.Write(buf, 0, r);
                }
            }
        }
        catch (InvalidDataException)
        {
            Directory.Delete(tmp, recursive: true);
            throw new VerificationException("BAD_ZIP");
        }
        catch (VerificationException)
        {
            Directory.Delete(tmp, recursive: true);
            throw;
        }
        Directory.Move(tmp, dest);
        Write(State() with { Staged = m.Version });
        return dest;
    }

    /// <summary>利用者の明示操作。ステージ済みの版へ切り替える（直前の版を残す）。</summary>
    public void Activate(string version)
    {
        var st = State();
        if (st.Staged != version || !Directory.Exists(Path.Combine(Versions, version))) throw new VerificationException("NOT_STAGED");
        if (st.Current is not null && Updates.CompareVersions(version, st.Current) <= 0) throw new VerificationException("NOT_NEWER");
        Write(new InstallState(version, st.Current, null));
    }

    public string Rollback()
    {
        var st = State();
        if (st.Previous is null || !Directory.Exists(Path.Combine(Versions, st.Previous))) throw new VerificationException("NO_PREVIOUS");
        Write(new InstallState(st.Previous, null, null));
        return st.Previous;
    }
}
