# jra-agent（Windows 取得エージェント / T3.5）

JV-Link（Windows COM）から取得したレコードを、ローカルの SQLite WAL キューに溜め、署名付き HTTPS で ingest へ送る薄い Python エージェント。**本人 1 名の私的利用専用**（`docs/REQUIREMENTS.md` §0）。Gradle には組み込まない独立プロジェクト。

> **状態（T3.5 完了時点）**: Mock で検証済みの機能と、**実機未検証**の機能を分けて書く。実 JV-Link は未確認の仕様が多く、**公式仕様の確認が記録されるまで実取得はしない**（`jvlink/spec.py` のゲート）。P-1（署名方式）・P-2（取得セッション）は**ユーザーの承認待ち**。

## 構成
| パッケージ | 責務 |
|---|---|
| `cli.py` | `verify-config` `verify-queue` `status` `diagnose` `run` `set-credential` `clear-credential` `clear-alert` `beacon-reset` `update` |
| `config.py` | TOML 設定の検証（HTTPS 強制・秘密を置けない・ビーコン検証鍵が必須。署名検証を省略する設定はない） |
| `signing.py` `client.py` `transport.py` `responses.py` | P-1 の署名・gzip・時計ずれ・応答の分類（CONTRACTS §3.6） |
| `envelope.py` | ingest 封筒（UUIDv7 の batchId、cp932 のバイト列をそのまま Base64、P-2 の `fetch`） |
| `queue/` | SQLite WAL の outbox・取得セッション・チェックポイント（`fetched_ts`/`queued_ts`/`acked_ts`） |
| `sender.py` `backoff.py` | 1 バッチずつ送信、409/429/503/401/403、指数バックオフ + ジッタ、上限つき再試行 |
| `beacon.py` `modes.py` | Ed25519 ビーコン検証、UNKNOWN / NORMAL_OK / STOPPED_CONFIRMED |
| `jvlink/` | `interfaces`（抽象）・`fake`（CI）・`com_adapter`（Windows 実機）・`worker_main`（別プロセス）・`supervisor`（ウォッチドッグ）・`spec`（仕様ゲート） |
| `fetcher.py` `plan.py` `runner.py` | 取得（セッション・安全な区切りで停止）、取得計画、全体の 1 周と復帰手順 |
| `secrets_store.py` `winapi.py` | DPAPI（ctypes）による資格情報の保護、ジョブオブジェクト、プロセス終了 |
| `update.py` | 署名付き manifest の検証・ステージング・切替・ロールバック（自動適用なし） |
| `lock.py` `diagnostics.py` `redact.py` | 多重起動の防止、診断（集計のみ）、ログの秘密マスク |
| `poc/` `interop/` `scripts/` | 実機 PoC、Kotlin ingest との結合ドライバ、タスクスケジューラの登録/解除（ドライランが既定） |

## 開発
```
cd agent
python -m venv .venv && .venv/Scripts/activate   # Linux は .venv/bin/activate
pip install -r requirements-dev.txt && pip install --no-deps -e .
ruff check . && ruff format --check . && mypy && pytest
```
Windows 実機では `pip install -r requirements-windows.txt`（pywin32 312）。実行時の依存は `cryptography` だけ。

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
資格情報は `type token.txt | python -m jra_agent.cli --config agent.toml set-credential --stdin --expires-at 2027-01-30T00:00:00+09:00`（DPAPI 保護。画面・ログ・引数に出さない）。

## 安全上の約束
- NORMAL が確認できない限り、取得も送信もしない（起動直後は UNKNOWN）。STOPPED/UNKNOWN 中はビーコン確認だけ。キューは保持する。
- 再送は**同じ batchId・同じ内容**。無限に再試行しない（上限で DEAD）。DEAD は成功ではない。
- `acked_ts` はサーバが返した `cursor` でだけ前進し、後戻りしない。BACKFILL では動かさない。
- ログ・例外にトークン・署名・nonce・レコードの内容を出さない。
