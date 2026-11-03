# Windows 実機 PoC 手順（T3.0: G-AGENT-1〜6。C# 版）

**目的**: JVLINK_DESIGN §11 のゲートを本人の Windows PC で確認する。**この手順は未実施**（実機でしか確認できない。実施前に T3.0 を完了扱いにしない）。
**守ること**: 実データ・ログ全文・利用キー・トークンを GitHub / PR / チャット / GCP に送らない。持ち帰るのは**集計 JSON**（件数・バイト長・エラーコード・所要時間・版数）だけ。PoC はネットワークに何も送らない（送信先はローカルの Mock のみ）。

## 0. 事前準備（利用者）
1. JRA-VAN SDK **5.0.0（64bit）**を入手し JV-Link(64bit) をインストール。利用キーは**公式の設定ソフト**で設定する（このリポジトリのスクリプトは扱わない）。
2. **公式の JV-Link インターフェース仕様書**で、PoC が呼ぶ API（JVInit / JVOpen / JVRTOpen / JVRead / JVClose）の引数・戻り値を確認する。確認できた項目は `docs/JVLINK_DESIGN.md` §12.2 に「仕様書名・版・節」だけを報告する（PDF は貼らない）。
3. `jraagent.exe` を用意する（`docs/USER_ACTIONS.md` の手順。リポジトリから `dotnet publish agent/cs/src/JraAgent.Cli -c Release -r win-x64 --self-contained -o C:\jraagent\app`）。
4. 環境の確認（読み取りのみ）: `powershell -File scripts\check-env.ps1 -AgentExe C:\jraagent\app\jraagent.exe` — .NET・OS のビット数、COM 登録、JV-Link DLL の版。

## 1. Mock による事前検証（実 JV-Link なし。どの PC でも）
```
jraagent.exe poc mock --out result-mock.json
```
G-AGENT-2〜5 が `MOCK_PASS`、G-AGENT-1/6 が `NOT_RUN` になる。**Mock の合格は実機の合格ではない。**

## 2. 実機の確認（各コマンドは API の一覧を表示し、確認を求める。`--allow-real-jvlink` が無ければ何も実行しない）
| ゲート | コマンド | 合格条件 | 失敗時の代替 |
|---|---|---|---|
| G-AGENT-1 COM 生成 | `jraagent.exe poc g1 --allow-real-jvlink --out g1.json` | `jvinit_code == 0`（64bit のプロセスから 64bit COM を生成） | 32bit 版 JV-Link の場合は `-r win-x86` で発行した 32bit 版で同じ確認 |
| G-AGENT-2 バイト列 | `jraagent.exe poc g2 --allow-real-jvlink --dataspec RACE --option 1 --fromtime <14桁> --max-records 500 --out g2.json` | `roundtrip_ng == 0`（cp932 の往復一致）、`records > 0`、`length` が仕様書のレコード長と一致 | JVGets への切替（**仕様書で確認してから**。現状は実装しない） |
| G-AGENT-3 蓄積系/速報系 | g2 を `--option 1` と `2`、`jraagent.exe poc g3-realtime --allow-real-jvlink --dataspec 0B12 --key <yyyyMMdd> --out g3rt.json` | それぞれ EOF まで到達し件数を記録。`JVRTOpen` が負の値ならコードと条件を報告（公式の検証ツール(64bit)との比較） | dataspec を絞る。仕様書で再確認 |
| G-AGENT-4 ハング回復 | `jraagent.exe poc mock`（G-AGENT-4）＋ CI の Windows ジョブ（ジョブオブジェクト付きのワーカーのハング回復） | ワーカーの無応答を検知（既定 120 秒）→ 強制終了 → 再起動して再 open できる | 監視方式の見直し |
| G-AGENT-5 キュー | `jraagent.exe poc mock`（G-AGENT-5。Windows のファイルシステムで） | 送信中に強制終了 → 再起動 → 同じ batchId で再送、重複なし、WAL | キュー設計の見直し |
| G-AGENT-6 スリープ | `scripts\install-task.ps1 -AgentExe ... -Config ...`（ドライラン）で内容を確認 → `-Apply` → ログオン/スリープ/復帰。`jraagent.exe poc sleep --duration 1800 --out sleep.json` の間にスリープ | 復帰後 300 秒以内に UNKNOWN → NORMAL_OK（Mock ビーコン） | 起動方式の見直し |

## 3. 結果の持ち帰り
`result-*.json` / `g*.json` / `sleep.json` の**中身（集計のみ）**をセッションに貼る。判定は各ファイルの `gates[].status`（`PASS`/`FAIL`/`NOT_RUN`/`MOCK_PASS`）。貼る前に、`records` 以外にレコードの内容が無いことを目視する。
