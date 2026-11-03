<#
.SYNOPSIS
  PoC の事前確認（読み取りだけ。何も変更しない）: .NET ランタイムの版、jraagent.exe のビット数、JV-Link の COM 登録、SDK（JV-Link DLL）の版。
.EXAMPLE
  .\check-env.ps1 -AgentExe C:\jraagent\app\jraagent.exe
#>
[CmdletBinding()]
param([string]$AgentExe = '')
$ErrorActionPreference = 'Continue'
$dotnet = Get-Command dotnet -ErrorAction SilentlyContinue
if ($dotnet) { & dotnet --list-runtimes | Select-String 'Microsoft.NETCore.App 10\.' } else { Write-Host 'dotnet が見つかりません（.NET 10 ランタイムが必要。自己完結の配布物なら不要）' }
Write-Host ("OS: {0} / 64bit OS: {1}" -f [System.Environment]::OSVersion.VersionString, [System.Environment]::Is64BitOperatingSystem)
if ($AgentExe -and (Test-Path $AgentExe)) { Write-Host ("jraagent: {0}" -f (Get-Item $AgentExe).VersionInfo.FileVersion) }
foreach ($k in 'HKCR:\JVDTLab.JVLink', 'HKCR:\CLSID\{2AB1774D-0C41-11D7-916F-0003479BEB3F}') {
  if (-not (Get-PSDrive HKCR -ErrorAction SilentlyContinue)) { New-PSDrive -Name HKCR -PSProvider Registry -Root HKEY_CLASSES_ROOT | Out-Null }
  Write-Host ("COM 登録 {0}: {1}" -f $k, (Test-Path $k))
}
$dll = (Get-ItemProperty 'HKCR:\CLSID\{2AB1774D-0C41-11D7-916F-0003479BEB3F}\InprocServer32' -ErrorAction SilentlyContinue).'(default)'
if ($dll -and (Test-Path $dll)) { Write-Host ("JV-Link DLL 版: {0}" -f (Get-Item $dll).VersionInfo.FileVersion) } else { Write-Host 'JV-Link DLL: 見つからない' }
