<#
.SYNOPSIS
  タスクスケジューラの登録を解除する。既定はドライラン。-Apply で解除。キュー・ログ・資格情報（DPAPI）は消さない。
#>
[CmdletBinding()]
param([string]$TaskName = 'JraAgent', [switch]$Apply)
$ErrorActionPreference = 'Stop'
$t = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if (-not $t) { Write-Host "タスク $TaskName はありません"; return }
if (-not $Apply) { Write-Host "ドライラン: $TaskName を解除します（-Apply で実行）"; return }
Stop-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
Unregister-ScheduledTask -TaskName $TaskName -Confirm:$false
Write-Host "解除しました。データフォルダ（キュー・ログ・秘密）は残しています。"
