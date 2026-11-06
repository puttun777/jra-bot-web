# 旧 SQLite のスキーマ（移行ツールの期待値）

`puttun777/jra-bot`（読取り専用の参照元）の**コミット f7dbf69** から写した、旧実装の SQLite のスキーマ定義。データは含まない。

| ファイル | 写し元 |
|---|---|
| `schema.sql` | `src/jraev/storage/schema.sql`（そのまま） |
| `migration_add_bets.sql` | `src/jraev/storage/migration_add_bets.sql`（そのまま） |
| `dynamic-columns.tsv` | `src/jraev/storage/db.py` の `_ensure_columns` と bets の列追加（起動時の `ALTER TABLE ADD COLUMN`）を表にしたもの |

`legacy-import preflight` は、これをメモリ上の SQLite に適用して期待する表・列を得て、実ファイル（利用者の PC のコピー）と比べる（DATA_MIGRATION_DESIGN §1・§5.2 P0。D-271）。
