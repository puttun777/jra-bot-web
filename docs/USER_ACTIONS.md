# USER_ACTIONS（本人だけが行う作業・承認と、その手順）
最終更新: 2026-11-01。ここに無い開発作業は Claude が進める。**秘密（キー・Webhook・パスワード）や実データ（レコード・DB ダンプ）は貼らない**。返すのは『成否・件数・エラーの種類』だけでよい。

## A. 承認が要るもの（返事は一言でよい）
| # | 内容 | 判断材料 | 承認しないとどうなるか |
|---|---|---|---|
| A1 | V6（任意） | `docs/V6_PROPOSAL.md`（ENUM 追加 1 件・GRANT 変更なし・INSTANT） | レース単位の状態履歴は『導出表示』のまま。機能は動く |
| A2 | 本番 DB への V5 の適用 | `docs/OPERATIONS.md`（infra）§5。deploy の `apply_pending_migrations` | 本番の DB が V4 のまま = 新しいアプリは起動しない |
| A3 | retention の削除の有効化（`RETENTION_EXECUTE=true`） | D-239。対象は組オッズ14日・生レコード30日・予定履歴/実行記録365日。既定はドライラン（件数だけ） | 古い記録が残り続ける（容量だけの問題） |
| A4 | ML の品質ゲートの閾値・モデルの有効化 | `docs/ML_DESIGN.md`。閾値は未承認 | ML は無効（市場確率のみ）のまま |
| A5 | GCP の課金リソースの作成（`terraform apply`） | infra `docs/OPERATIONS.md` §0〜§2、`docs/CLOUD_COSTS.md` §10（通常 ≈ ¥8,550/月、全停止 ≈ ¥1,200/月の推定） | 本番が存在しない |
| A6 | Public 化（任意） | `docs/PUBLIC_READINESS.md`・D-180。**Visibility を変えられるのは本人だけ** | 非公開のまま |
| A7 | 実データのクラウド投入（COMP-4 の回答後） | `docs/DATA_SOURCE_COMPLIANCE.md` §0 の Q1〜Q3 を JRA-VAN に問い合わせ | **実データはクラウドに入れない**（ローカルでの検証のみ） |

## B. 実機・外部での作業
### B1. Windows と JV-Link の実機 PoC（R4）
前提: 64bit の Windows・JV-Link（利用キー設定済み）・Python 64bit。リポジトリを取得し `agent/` で依存を入れる（`agent/requirements.txt`）。
```
python -m poc.poc_real g1 --allow-real-jvlink --out g1.json
python -m poc.poc_real g2 --allow-real-jvlink --dataspec RACE --option 1 --fromtime 20261001000000 --max-records 500 --out g2.json
python -m poc.poc_real g3-realtime --allow-real-jvlink --dataspec 0B12 --key 20261101 --out g3rt.json
```
- 実行前に『これから呼ぶ JV-Link の API 一覧』が出る。確認して `--yes`。ネットワークには何も送らない（集計 JSON だけが残る）。
- **返してほしいもの**: 各 JSON の `passed`/件数/エラー種別（レコードの中身は不要）。期待: g1 = COM 生成と JVInit 成功、g2 = 500 件前後の読取りと終了コード、g3 = リアルタイムの接続可否。
- 失敗したら、画面のエラー行（秘密を含まないもの）だけ。

### B2. JV-Data の仕様（R5）
JV-Link 仕様書 Ver.4.9 の該当ページ（RA/SE/O1〜O6/HR などの項目位置）。`docs/JVLINK_DESIGN.md` の 12 項目のうち、手元の資料で答えられるものから。**推測でパーサは書かない**。

### B3. 旧 SQLite・設定・モデルの確認（T1.20/T1.21）
`docs/UNRESOLVED.md` の D-2/D-3 にある SELECT 2 本の**行数だけ**、旧 `config.yaml` の運用値（差分だけ可）、legacy/v2 の方針。

### B4. GCP の準備（A5 の前）
infra `docs/OPERATIONS.md` §0 の U1〜U8（プロジェクト・tfstate バケット・GitHub Environments/変数・OAuth クライアント・Secret・予算アラート）。**手順は infra の OPERATIONS.md に順番どおり書いてある**。

### B5. Discord（任意）
通知用とエラー用の Webhook を作り、Secret Manager（`discord-webhook` / `discord-error-webhook`）に入れる（標準入力から。履歴に残さない）。最初の通知は `docs/OPERATIONS.md`（web）の確認手順で、合成データの 1 件を送って受信を確認。

## C. 最終確認チェックリスト（本人が見る）
1. GitHub の main の CI（`ci gate`）が緑。web・infra とも。
2. `docs/COMPLETION.md` の C が 0 件で、B の項目が上の A/B に対応している。
3. 合成データでの通し試験（`docs/TEST_RESULTS.md`）が成功している。
4. （A5 の後）`ops-start` → `ops-status` が全行 ✅、ダッシュボードの『概要』『運用』が表示できる。`ops-stop` で DB が停止し、`ops-status` が STOPPED で ✅。
5. （A7 の後）実データ 1 日分をローカルで取り込み、旧 Python との差が `docs/MIGRATION_MATRIX.md` の意図した差異（E1〜E17）だけであること。
