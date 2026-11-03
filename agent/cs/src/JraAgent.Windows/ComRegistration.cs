using System.Runtime.Versioning;
using Microsoft.Win32;

namespace JraAgent.Windows;

/// <summary>JV-Link の COM 登録の有無（レジストリの読み取りだけ。JV-Link の API は呼ばない。ProgID/CLSID は参照元実装の使用実態で公式未確認）。</summary>
[SupportedOSPlatform("windows")]
public static class ComRegistration
{
    public static (IReadOnlyDictionary<string, bool> ProgIds, bool ClsidRegistered) Check()
    {
        var progIds = new Dictionary<string, bool>(StringComparer.Ordinal);
        foreach (var p in ComJvLink.ProgIdCandidates)
        {
            using var k = Registry.ClassesRoot.OpenSubKey(p);
            progIds[p] = k is not null;
        }
        using var c = Registry.ClassesRoot.OpenSubKey($@"CLSID\{ComJvLink.ClsidCandidate}");
        return (progIds, c is not null);
    }
}
