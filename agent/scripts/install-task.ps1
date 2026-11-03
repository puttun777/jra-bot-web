<#
.SYNOPSIS
  JRA エージェントをタスクスケジューラに登録する（ログオン時起動・失敗時の再起動・多重起動防止）。
.DESCRIPTION
  既定は**ドライラン**（何も変更せず、登録する内容を表示するだけ）。実際に登録するには -Apply を付ける。
  - トリガー: 現在のユーザーのログオン時。実行は「ユーザーがログオンしている場合のみ」（COM と DPAPI のため対話セッションが要る）。昇格しない（最小権限、-RunLevel Limited）。
  - 多重起動: タスクは MultipleInstances=IgnoreNew。エージェント自身もデータフォルダのファイルロックで二重起動を拒否する。
  - 失敗時: 1 分後に最大 3 回再起動（無限に再起動しない。再起動してもワーカーの再起動上限は別にある）。
  - スリープ復帰: エージェントは起動直後 UNKNOWN から再判定する。スリープ中に止まらないよう「バッテリー駆動でも開始/継続」を許可し、スリープ復帰で再開する。
  - シャットダウン: タスクの停止は終了の合図（SIGTERM/CTRL_CLOSE 相当）で正常終了を要求する。STOPPED 中は送信せず待機し、再起動後も STOPPED のまま起動する。
  - ログ: <DataDir>\logs\agent-yyyyMMdd.log（日次、既定 30 日で削除）。レコードの内容は書かない。
  レジストリは変更しない。
.EXAMPLE
  .\install-task.ps1 -AgentExe C:\jraagent\app\jraagent.exe -Config C:\jraagent\agent.toml          # ドライラン
  .\install-task.ps1 -AgentExe C:\jraagent\app\jraagent.exe -Config C:\jraagent\agent.toml -Apply   # 登録
#>
[CmdletBinding()]
param(
  [Parameter(Mandatory)][string]$AgentExe,
  [Parameter(Mandatory)][string]$Config,
  [string]$TaskName = 'JraAgent',
  [switch]$Apply
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path $AgentExe)) { throw "AgentExe が見つかりません: $AgentExe" }
if (-not (Test-Path $Config)) { throw "Config が見つかりません: $Config" }
$user = "$env:USERDOMAIN\$env:USERNAME"
$action = New-ScheduledTaskAction -Execute $AgentExe -Argument "--config `"$Config`" run"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1) `
  -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)
$principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
Write-Host "タスク名: $TaskName / ユーザー: $user / 実行: $AgentExe --config $Config run"
if (-not $Apply) { Write-Host 'ドライランです。登録するには -Apply を付けてください。'; return }
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Settings $settings -Principal $principal -Force | Out-Null
Write-Host "登録しました。解除: .\uninstall-task.ps1 -Apply"
