<#
.SYNOPSIS
  PoC の事前確認（読み取りだけ。何も変更しない）: Python の版/ビット数、pywin32、JV-Link の COM 登録、SDK の版。
#>
$ErrorActionPreference = 'Continue'
$py = Get-Command python -ErrorAction SilentlyContinue
if ($py) { & python -c "import sys,struct,platform;print('python',platform.python_version(),struct.calcsize('P')*8,'bit')" } else { Write-Host 'python が見つかりません' }
& python -c "import importlib.util as u;print('pywin32', bool(u.find_spec('win32com')))" 2>$null
foreach ($k in 'HKCR:\JVDTLab.JVLink', 'HKCR:\CLSID\{2AB1774D-0C41-11D7-916F-0003479BEB3F}') {
  if (-not (Get-PSDrive HKCR -ErrorAction SilentlyContinue)) { New-PSDrive -Name HKCR -PSProvider Registry -Root HKEY_CLASSES_ROOT | Out-Null }
  Write-Host ("COM 登録 {0}: {1}" -f $k, (Test-Path $k))
}
$dll = (Get-ItemProperty 'HKCR:\CLSID\{2AB1774D-0C41-11D7-916F-0003479BEB3F}\InprocServer32' -ErrorAction SilentlyContinue).'(default)'
if ($dll -and (Test-Path $dll)) { Write-Host ("JV-Link DLL 版: {0}" -f (Get-Item $dll).VersionInfo.FileVersion) } else { Write-Host 'JV-Link DLL: 見つからない' }
