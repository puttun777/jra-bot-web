# CONTRACTS（web ⇔ infra 受け渡し契約・正本は本ファイル）

状態: **ドラフト（第0段階）**。第1段階で値を確定し、infra 側 `docs/CONTRACTS.md` は本ファイルの版を参照する。変更時は両リポジトリの PROGRESS に記録する。

## 1. 成果物（web → infra）
| 成果物 | 内容 | 識別 |
|---|---|---|
| コンテナイメージ `dashboard` | Spring Boot dashboard | Artifact Registry、タグ=git SHA（不変）+ semver。デプロイは **digest 指定** |
| コンテナイメージ `bot` | bot-run / bot-scheduler-check（同一イメージで引数切替も可、第1段階で決定） | 同上 |
| コンテナイメージ `db-migration` | Flyway 実行 Job | 同上。マイグレーション版と対応 |
| `release-manifest.json` | 各イメージ digest、アプリ版、Flyway 最新版、契約版、ビルド日時、git SHA | GitHub Release asset または Artifact Registry 上の artifact。infra はこれのみを入力とする |
| Windows エージェント配布物 | zip（秘密を含まない） | GitHub Release asset（Private）。設定/トークンは別途ユーザー配置 |

web のレジストリ公開先・infra 側の pull 権限は **推測せず**、第5段階でユーザー確認の上 Workload Identity で付与する（別 Private リポジトリへの権限は未確認として扱う）。

## 2. 環境変数/設定（infra → app）※名称は暫定
| 名前 | 供給元 | 用途 |
|---|---|---|
| `APP_ENV` | Terraform | local/prod |
| `DB_URL` `DB_USER` | Terraform | 接続先（秘密でない） |
| `DB_PASSWORD` | Secret Manager | DB パスワード |
| `DISCORD_WEBHOOK_URL` | Secret Manager | 通知 |
| `ALLOWED_USERS` / `ADMIN_USERS` | Terraform（メールの許可リスト） | 空なら **全拒否** |
| `IAP_AUDIENCE` | Terraform | IAP JWT 検証 |
| `OPS_MODE_SOURCE` | Terraform | 運用モード正本の参照先 |
| `TZ` | 固定 | `Asia/Tokyo` |

## 3. API 契約（案）
| API | 呼び出し元 | 認証 | 内容 |
|---|---|---|---|
| `GET /internal/ops/mode` | bot / agent / infra | 機械用 ID トークン | `{desired: NORMAL\|STOPPED, actual: {...}, updatedAt}` |
| `GET /internal/health` `/internal/ready` | infra | 機械用 | 正常性確認（起動手順の「正常性確認」ステップで使用） |
| `POST /internal/ingest/*` | jvlink-agent | 機械用（agent 専用主体） | STOPPED なら 503 + `Retry-After`、データを受理しない |
| `GET /api/**` | ブラウザ | IAP + 許可リスト | 一般/管理者ロール分離 |
| 管理系 `/api/admin/**` | ブラウザ | 管理者のみ | 手動補正・モード表示等 |

## 4. DB マイグレーション契約
- Flyway は `db-migration` イメージを Job として **デプロイごとに 1 回**。アプリ起動時 migrate は無効。
- 後方互換: 旧アプリ版が新スキーマで動く expand/contract 方式（ロールバック可能性のため）。破壊的変更は 2 リリースに分割。
- infra の deploy workflow は「migrate 成功 → dashboard/bot 更新」の順。

## 5. 運用モード契約
- 正本: infra 側の desired-state（例: GCS 上の状態ファイル or Secret/Parameter。第1段階で決定。DB 停止中も読める場所）。
- start 順: DB 復帰 → migrate 要否確認 → Web 復帰 → 正常性確認 → bot 制御再開（Scheduler 有効化）。
- stop 順: 新規実行抑止（mode=STOPPED）→ Web/ジョブ停止 → DB アクセス終了 → DB 停止。
- Terraform apply/デプロイは mode を変更しない。STOPPED 中はリソースを起動しない（`lifecycle`/変数で保証、infra 側で詳細化）。

## 6. 契約バージョン
`contract_version: 0.1.0-draft`。破壊的変更でメジャーを上げ、`release-manifest.json` に含める。
