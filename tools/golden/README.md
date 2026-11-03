# tools/golden — ゴールデンフィクスチャ（旧 Python との比較の基準）

旧 Python 版 JRA Bot（`puttun777/jra-bot`）を**固定コミット** `f7dbf69fd8940121782870de902e8c449e6ca149` で実行し、**合成入力**に対する計算結果を JSON フィクスチャとして固定したもの。Kotlin に移植したロジックを同じ入力で実行して比べる基準。

- **旧実装は絶対的な正解ではない。** フィクスチャは「旧実装が実際に返した値」（E1〜E16 などの欠陥候補の挙動を含む）。新仕様・legacy モード・v2 の期待値とは区別し、旧フィクスチャを新仕様に合わせて書き換えない（`docs/MIGRATION_MATRIX.md` §7.5/§8）。
- **合成データだけ。** 実 JV-Data・実 SQLite・実モデル・実 config・トークン・Webhook は使わない。ここでの一致は実際の競馬データでの正確性や収益性を示さない。

## 中身

| パス | 内容 |
|---|---|
| `fixtures/*.json` | 35 ケース / 1,048 呼び出し。Python の `json.dumps(sort_keys=True, indent=2, ensure_ascii=False)` の形式（バイト単位で検査する） |
| `fixtures/manifest.json` | 各フィクスチャの sha256、生成元のリポジトリ・コミット |
| `catalog.json` | 対象一覧（MIGRATION_MATRIX §7.5 の GD01〜GD11・ML_DESIGN §7.3 の G1〜G8。status は implemented / partial / planned）と、Kotlin 移植の対応表（`kotlin_port`: 比較するケースと、未対応のケースとその理由） |

## 検証と比較（Kotlin だけ。D-258）

```bash
./gradlew :golden-runner:run --args="check --fixtures $PWD/tools/golden/fixtures --catalog $PWD/tools/golden/catalog.json [--report report.json]"
./gradlew :golden-runner:run --args="verify --fixtures $PWD/tools/golden/fixtures --catalog $PWD/tools/golden/catalog.json"   # 静的検証だけ
```

- `verify`: 来歴（固定コミット・リポジトリ）・manifest の sha256・必須メタデータ・`data_classification=synthetic`・入力の禁止パターン（URL・メール・長いトークン・DB/モデルのファイル名・JV-Link/JV-Data の記述・秘密鍵）・正準形式・比較規則（abs_tol は 0〜1e-6 の float）・カタログとの整合。フィクスチャは書き換えない。
- `check`: verify → `:domain` の計算で全ケースを実行 → 各フィクスチャの `comparison_rules` で比較（型は厳密・キーの集合・配列の長さと順序。非有限のタグ `{"$float": …}` はタグどうしの完全一致だけ）。**比べるのは Kotlin が『対応』と申告したケースだけ**で、未対応は成功に数えない。申告と `catalog.json` の対応表が食い違えば形式不正（終了コード 2）。終了コード: 0 = 対応ケースがすべて一致 / 1 = 不適合・差分 / 2 = 形式不正。
- CI では `build` ジョブがこれを実行し、要約を Step summary に出す。

## 旧 Python の生成器を削除したことで失われた検証（`docs/LANGUAGE_MIGRATION.md` §5）

- 以前は CI のたびに、固定コミットの旧実装から**再生成してフィクスチャと一致**することを確かめていた（`golden tests (Python 3.12)` ジョブ）。これは無くなった。フィクスチャは固定コミット・sha256 つきで凍結されており、Kotlin の検証はその来歴・hash・合成データであること・形式を確かめる。
- ほかに、削除した Python の試験が見ていた次の検証も無くなった: シナリオ定義とフィクスチャの入力の一致（`test_inputs_match_scenario_definitions` ほか）、旧実装を読み取り専用で扱うガード（legacy guard）、numpy の版を固定した再生成の許容差。いずれも『再生成』の一部で、凍結したフィクスチャの来歴・hash の検証で代える。
- フィクスチャを**作り直す・ケースを足す必要が出たら**、本人の PC で一回限りの手順で行う: 削除コミット（`git log --diff-filter=D -1 --format=%H -- tools/golden/generate.py`）の**親**（`<削除コミット>^`）の `tools/golden` を取り出し（`git checkout <削除コミット>^ -- tools/golden`）、参照元の固定コミットを使って生成し、その差分を PR に出す（通常開発・CI の必須経路ではない）。**Kotlin や TypeScript で書き直した生成器の出力を『旧 Python 由来の証拠』とは呼ばない。**

## 新しいケースの追加

既存のフィクスチャは書き換えない。追加は上の一回限りの手順で旧実装から生成し、`catalog.json` の該当項目の `case_ids` と `kotlin_port` を更新する。
