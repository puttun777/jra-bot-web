# jraagent（Windows 取得エージェント / T3.5。C#/.NET 10。D-261）

JV-Link（Windows COM）から取得したレコードを、ローカルの SQLite WAL キューに溜め、署名付き HTTPS で ingest へ送る薄いエージェント。**本人 1 名の私的利用専用**（`docs/REQUIREMENTS.md` §0）。Gradle には組み込まない独立プロジェクト（`agent/cs`）。

> **状態**: Mock（Fake の JV-Link・偽サーバ・Kotlin ingest との結合試験）で検証済みの機能と、**実機未検証**の機能を分けて書く。実 JV-Link は未確認の仕様が多く、**公式仕様の確認が記録されるまで実取得はしない**（`SpecRegistry` のゲート）。旧 Python 版（`agent/src/jra_agent`）は 2026-11-03 に削除した（`docs/LANGUAGE_MIGRATION.md` §6）。

## 構成（`agent/cs`）
| プロジェクト / ファイル | 責務 |
|---|---|
| `src/JraAgent.Core` | OS に依存しない部分。`Signing`・`SignedClient`・`Transport`・`Responses`（署名・gzip・時計ずれ・応答の分類。CONTRACTS §3.6）、`Envelope`（UUIDv7 の batchId、cp932 のバイト列をそのまま Base64、取得セッションの `fetch`）、`OutboxQueue`（SQLite WAL の outbox・取得セッション・チェックポイント。旧版と同じスキーマ）、`Sender`（1 バッチずつ送信、409/429/503/401/403、指数バックオフ + ジッタ、上限つき再試行）、`Beacon`・`Modes`（Ed25519 ビーコン検証、UNKNOWN / NORMAL_OK / STOPPED_CONFIRMED）、`JvLink`・`FakeJvLink`・`Worker`（抽象・Fake・別プロセスのワーカーとウォッチドッグ・仕様ゲート）、`Fetcher`・`Plan`・`Runner`（取得・取得計画・1 周と復帰手順）、`Config`（TOML の検証）、`Secrets`（資格情報の保護の抽象）、`Update`（署名付き manifest・安全な展開・切替・ロールバック）、`InstanceLock` |
| `src/JraAgent.Windows` | Windows 固有の薄い層: `DpapiProtector`（資格情報の保護）、`KillOnCloseJob`（ジョブオブジェクト）、`ComJvLink`（JV-Link の COM。`dynamic` を使わず IDispatch を型付きで呼ぶ）、`ComRegistration`（レジストリの確認だけ） |
| `src/JraAgent.Cli` | 実行ファイル `jraagent.exe`: `verify-config` `verify-queue` `status` `diagnose` `run` `set-credential` `clear-credential` `clear-alert` `beacon-reset` `update`、COM ワーカー（`worker`）、実機 PoC（`poc`。`POC.md`） |
| `tools/JraAgent.Interop` | Kotlin ingest との結合試験のドライバ（`jraagent-interop`。配布物に含めない） |
| `tests/JraAgent.Core.Tests` | xunit v3。旧 Python の試験をすべて移植し、子プロセスのワーカー・偽サーバ・CLI・Windows 固有部分を検査する |
| `../tests/vectors` | 署名（OpenSSL で独立に計算）とビーコン（削除前の Python 実装で生成）の既知ベクトル。Kotlin・infra の試験も同じファイルを読む |
| `../scripts` | タスクスケジューラの登録/解除（ドライランが既定）、環境の確認 |

## 開発
```
cd agent/cs
dotnet restore JraAgent.slnx --locked-mode     # 版は Directory.Packages.props と packages.lock.json に固定
dotnet build JraAgent.slnx -c Release          # 警告はエラー（TreatWarningsAsErrors・latest-recommended の解析規則）
tests/JraAgent.Core.Tests/bin/Release/net10.0/JraAgent.Core.Tests     # Windows は .exe。-result-junit <file> で JUnit 形式
```
SDK は .NET 10（`global.json`。版の根拠は `docs/VERSIONS.md` §18）。Windows 以外では Windows 固有の 4 件が skip になる（CI の Windows ジョブで実行する）。配布は `dotnet publish src/JraAgent.Cli -c Release -r win-x64 --self-contained`（**64bit の JV-Link が前提**。SDK の DLL・仕様書・利用キーはリポジトリに含めない）。

## 設定（`agent.toml`。トークン・利用キーは書かない）
```toml
environment = "production"            # 開発は development（127.0.0.1 の http と fake を許す）
agent_id = "ag_main"
data_dir = "%LOCALAPPDATA%\\jraagent"
[server]
base_url = "https://ingest.example.run.app"
[beacon]
url = "https://storage.googleapis.com/<bucket>/mode.json"
keys = [{ kid = "beacon-2026a", public_key = "<Ed25519 公開鍵 base64url>" }]   # 1〜2 本
[jvlink]
mode = "com"        # 本番。公式仕様の確認が記録されるまで、取得計画があっても実取得は開始されない
```
資格情報は `type token.txt | jraagent.exe --config agent.toml set-credential --stdin --expires-at 2027-01-30T00:00:00+09:00`（DPAPI 保護。画面・ログ・引数に出さない）。旧 Python 版が保存した資格情報（`<data_dir>\secrets\credential.bin`）とキュー（`queue.sqlite3`）はそのまま読める（同じ entropy・同じスキーマ）。

## 安全上の約束
- NORMAL が確認できない限り、取得も送信もしない（起動直後は UNKNOWN）。STOPPED/UNKNOWN 中はビーコン確認だけ。キューは保持する。
- 再送は**同じ batchId・同じ内容**。無限に再試行しない（上限で DEAD）。DEAD は成功ではない。
- `acked_ts` はサーバが返した `cursor` でだけ前進し、後戻りしない。BACKFILL では動かさない。
- ログ・例外にトークン・署名・nonce・レコードの内容を出さない（ログは `Redact` を通す。例外は型名だけ）。
- COM は別プロセスのワーカー（STA）の中だけで使い、応答が無ければ強制終了して作り直す。Windows ではワーカーをジョブオブジェクトに入れ、エージェントが落ちても孤児を残さない。
