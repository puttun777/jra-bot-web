# 画面と API の契約の見本（合成データ）

ダッシュボードの画面（`dashboard/src/main/ts`）が受け取る `/api/v1` の応答の見本。実データではない。

- TypeScript の試験（`dashboard/src/test/ts/contract.test.ts`）: 見本を `schema.ts` の検査に通す。
- Kotlin の統合試験（`ReadAdminApiMySqlIntegrationTest`）: 実際の応答と、見本の形（オブジェクトのキーの集合と、値が null でないときの JSON の型）を比べる。

サーバの DTO の項目を変えたら、ここの見本と `schema.ts` を同時に直す（どちらかを忘れると試験が失敗する）。
