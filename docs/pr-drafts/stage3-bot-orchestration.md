# PR 下書き: Stage 3-H/I 準備 — T3.8 bot-run オーケストレーション・Flyway V5・保留の再適用

> **まだ PR は作成していない。** Private の GitHub Free では CI の残り無料枠・追加課金の停止設定を確認できず（billing API は 403）、PR 作成が CI を起動するため保留。条件が整ったら本文をそのまま使う。base: `main`、head: `claude/stage3-bot-orchestration`。

## 解決する問題と変更後の動作
- **bot-run（T3.8）**: 開催日の必要な時間帯だけ、リース＋checkpoint で評価 → DB へ確定（claim）→ Discord 通知まで行う。確定前に送らない。確定後・送信前に落ちたら再送しない（D-18/D-220）。評価機会の喪失は FAILED（exit 1。D-19）。
- **Flyway V5**: `race_evaluations` の新方式の行に UNIQUE、`race_timing_notice`（追記専用の通知スナップショット）と GRANT（bot は SELECT/INSERT のみ）。旧データの重複があれば何も変えずに失敗。
- **保留（PENDING）の再適用（D-227/D-229）**: 依存するレースが無い観測を保留にし、レースが届いたら取り込み時の観測時刻のまま再適用。キーセットで全件走査（総数上限なし）。**走査と再処理の保証を区別**し、再試行の契機を 3 つに明文化（次の同一レース観測／完了済みバッチの再送／同時バッチの保留のコミット後確認）。
- 長い処理中のリース喪失（D-225）、G-31（D-226/D-228）、T3.9 の設計（`docs/T39_DESIGN.md`。**実装なし**）。

## 主な変更
- `:domain` `botrun`・`notify`・`ingest`（`PendingReplayer` を抽出）、`:persistence`（V5 の Repository、保留の `listPending`/`settlePending`）、`:db-migration`（V5）、`:bot`（配線）、`:ingest`（`IngestConfiguration`）、`agent/`（COM ワーカーの堅牢化）、docs。
- 差分は 122 ファイル超（T3.8・監査・V5・追加レビューが PR/CI 保留の間に積み上がった）。コミットは順に読める: `d597085`〜`6cde341`（骨組み・配線・試験）→ `9a7df54`〜`0643469`（監査修正）→ `778f4d6`・`d337192`（V5）→ 追加レビュー（`5d7f3dd`〜`07c964c`）→ 本ブランチ最新（D-229）。履歴の書き換えによる分割は行っていない。

## 実行した検証（ローカル。合成データのみ。**CI は未実行**）
- 環境: JDK 21（`-PjdkVersion=21`）、Docker 上の **MySQL 8.4.13**（Testcontainers）。JDK 25・MySQL 9.7 は未実施。
- 結果は PROGRESS の最新節を参照（実行したコマンドと件数はそこが正本）。
- 回帰試験の妥当性: 旧 10,000 件上限を戻す変異で `PendingReplayerTest` の 25,000 件の試験が失敗、再送による再開・同時バッチ確認の変異で `IngestPipelineIntegrationTest` の 2 試験が失敗することを確認。

## 未検証・既知の制約
- GitHub Actions（JDK 25、MySQL 8.4/9.7、Docker イメージ、`ci gate`）は未実行。実 Discord・Cloud Run・Windows 実機・実 JV-Link は未検証。
- 失敗した保留行は、次のレース観測／バッチ再送が来なければ PENDING のまま残る（定期ジョブなし）。`ingest_raw` の索引は未追加（100 万行で 1 ページ約 0.5〜0.8 秒の単発実測。V6 候補・要承認）。
- V5 の本番 DB への適用は未承認・未実施。マージしても本番操作は発生しない（デプロイは別リポジトリで承認制）。
- 払戻の単位・同着・返還・取消（JV-Data 仕様）と COMP-4 は未解決。T3.9 と V6 はこれに依存。
- ブランチ保護は Free の Private では設定できないため、マージ前の `ci gate` 成功の確認は手動。

## 関連
`docs/DECISIONS.md` D-18〜D-19・D-218〜D-229、`docs/UNRESOLVED.md`、`docs/CONTRACTS.md` 0.4.15。
