using System.Runtime.Versioning;
using System.Security.Cryptography;
using JraAgent.Core;

namespace JraAgent.Windows;

/// <summary>
/// DPAPI（CryptProtectData / CryptUnprotectData。現在のユーザーのスコープ。LOCAL_MACHINE は使わない。旧 winapi.DpapiProtector の移植）。
/// ユーザーのログオン資格情報に結びつくので、別ユーザーや別 PC では復号できない。旧版と同じ entropy で保護するので、旧版が保存した資格情報も読める。
/// </summary>
[SupportedOSPlatform("windows")]
public sealed class DpapiProtector : IProtector
{
    public byte[] Protect(byte[] plain, byte[] entropy)
    {
        try
        {
            return ProtectedData.Protect(plain, entropy, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException)
        {
            throw new SecretException("DPAPI の呼出しに失敗");
        }
    }

    public byte[] Unprotect(byte[] blob, byte[] entropy)
    {
        try
        {
            return ProtectedData.Unprotect(blob, entropy, DataProtectionScope.CurrentUser);
        }
        catch (CryptographicException)
        {
            throw new SecretException("DPAPI で復号できない");
        }
    }
}
