# tools/golden — ゴールデンテスト基盤（T2.6）

旧 Python 版 JRA Bot（`puttun777/jra-bot`）を**固定コミット**で実行し、**合成入力**に対する計算結果を JSON フィクスチャとして保存する。Stage 3 で Kotlin に移植したロジックを、同じ入力で実行して比較するための基盤。

- **旧実装は絶対的な正解ではない。** フィクスチャは「旧実装が実際に返した値」（E1〜E15 などの欠陥候補の挙動も含む）。新仕様・legacy モード・v2 の期待値とは区別し、旧フィクスチャを新仕様に合わせて書き換えない（`docs/MIGRATION_MATRIX.md` §7.5/§8）。
- **合成データだけ。** 実 JV-Data・実 SQLite・実モデル・実 config・トークン・Webhook は使わない。ここでの一致は実際の競馬データでの正確性や収益性を示さない。

## 固定コミット

| 項目 | 値 |
|---|---|
| リポジトリ | `puttun777/jra-bot`（読み取り専用。変更・commit・push しない） |
| 短縮 SHA | `f7dbf69` |
| 完全 SHA | `f7dbf69fd8940121782870de902e8c449e6ca149`（`constants.py` の `PINNED_COMMIT`） |

`generate` はこの SHA 以外・未コミット変更のある参照元を**常に拒否**する（上書きオプションなし）。固定コミットを変える場合は `constants.py`・CI の `LEGACY_COMMIT`・本 README を更新し、理由を `docs/DECISIONS.md` に記録する。

## 環境構築

Python 3.12 系（`requires-python >=3.12,<3.14`。CI は 3.12。3.12.3 と 3.13 の手元では同一バイト。ただし CI のランナー間では浮動小数の最後の 1〜2 桁が変わり得るため、再生成の判定はバイト一致ではなく許容誤差内の一致: D-101）。依存は numpy 1 つだけ（`requirements.txt`、版は固定）。`verify` / `compare` / テスト（旧実装を使わないもの）は標準ライブラリのみ。

```bash
python3 -m venv .venv-golden && .venv-golden/bin/pip install -r tools/golden/requirements.txt
```

### numpy を更新するとき（固定版は 2.5.3。Dependabot の自動更新は対象外: D-103）

`requirements.txt` の `numpy==2.5.3`、`constants.py` の `PINNED_NUMPY`、フィクスチャ・manifest に記録された numpy の版（メタデータ）と sha256 は**一体**で、片方だけ上げると `golden tests` が失敗する（Dependabot PR #13 の事象）。更新は手動で、次をまとめて 1 つの PR にする。

1. 公式情報で版を確認して固定する（Alpha/Beta/RC は不可）。`requirements.txt` と `constants.py` の `PINNED_NUMPY` を同時に更新する。
2. 固定コミットの旧実装で `--update` により全フィクスチャを再生成し、`git diff` で値の差分を人が見る（許容誤差内の揺れか、挙動の変化か）。メタデータの numpy 版と manifest の sha256 も更新される。
3. 再生成テスト（`GOLDEN_LEGACY_PATH` 指定）・`generate --check`・`verify`・`docs/CI_AND_IMAGES.md` の golden ジョブが通ることを確認し、理由を `docs/DECISIONS.md` に記録する（旧 Python との一致が基準なので、Kotlin 側の期待値が変わる場合は Stage 3 の比較も見直す）。

## 参照元の取得

```bash
git clone https://github.com/puttun777/jra-bot.git /path/to/jra-bot   # 公開リポジトリ。認証不要
git -C /path/to/jra-bot checkout --detach f7dbf69fd8940121782870de902e8c449e6ca149
```
（URL は `puttun777`。）取得できない場合は別コミットで代用せず、失敗理由を記録して中断する。

## 生成・検証

リポジトリのルートで実行する。

```bash
# 再生成して、コミット済みと一致するか確認（書き込まない。CI はこれを使う。浮動小数は各ケースの許容誤差内、他は完全一致）
.venv-golden/bin/python -m tools.golden.generate --legacy-path /path/to/jra-bot --check
# 明示的な更新（fixtures/ を上書き。git diff を PR で確認すること。CI は絶対に実行しない）
.venv-golden/bin/python -m tools.golden.generate --legacy-path /path/to/jra-bot --update
# コミット済みフィクスチャの静的検証（旧実装不要。失敗は非 0、ファイルは書き換えない）
python3 -m tools.golden.verify
# 単体テスト（0 件・過少・スキップ過多は失敗）。旧実装を使う再生成テストは GOLDEN_LEGACY_PATH 指定時のみ実行
GOLDEN_LEGACY_PATH=/path/to/jra-bot .venv-golden/bin/python -m tools.golden.run_tests --min-tests 45 --max-skipped 0
```

生成は、空の一時ディレクトリを cwd にし、TZ=`Asia/Tokyo`・seed 固定・ネットワーク遮断・通知/トークン系の環境変数を除去した状態で旧関数を呼ぶ（`legacy.py`）。各ケースを 2 回実行して一致を確認する。対象関数は現在時刻・乱数を使わない（`datetime.now` を使う ensemble の経路は対象外）。

## 構成

| ファイル | 役割 |
|---|---|
| `scenarios.py` | 入力シナリオ（合成の call 列）。旧実装を呼ばない |
| `adapter.py` | call 名 → 旧関数の呼び出し（許可リスト式）。例外は型とメッセージを記録 |
| `legacy.py` | 固定コミット・クリーン検証、sandbox、必要モジュールだけ import |
| `jsonutil.py` | 正規化（NaN/Inf はタグ付けして検出可能に）と正準 JSON |
| `fixtures_io.py` / `generate.py` | フィクスチャ組み立て・保存・manifest（sha256） |
| `verify.py` | 静的検証 |
| `compare.py` | 期待値と実行結果の比較（Kotlin 用） |
| `catalog.py` | GD01〜GD10・G1〜G7 の登録と状況 |
| `fixtures/` | `<case_id>.json` と `manifest.json` |

## フィクスチャ形式（schema_version 1）

必須: `fixture_id` `case_id` `schema_version` `source_repository` `source_commit`（完全 SHA）`source_function` `input` `expected_output` `execution_parameters`（seed・fixed_time・timezone・libraries 等）`comparison_rules` `data_classification`（`synthetic`）`scenario_ids` `known_defects` `generator`。

- `input.calls[]` = `{call_id, function, args}`、`expected_output.results[]` = `{call_id, outcome}`。`outcome` は `{status:"ok", value}` または `{status:"exception", exception_type, message}`（旧実装の例外も期待値として記録。比較は型のみで message は `ignore` を明示）。
- 浮動小数は JSON の数値（Python の最短往復表現）。NaN/Infinity は期待値に入れない（`{"$float": ...}` のタグが現れたら verify が失敗）。
- `comparison_rules`: 既定 `abs_tol`（確率 1e-9、正規化 1e-12、ハッシュ・既定値は完全一致）+ パス別 `overrides`。型・キー集合・配列順序は常に厳密。

## Kotlin 出力との比較

**Stage 3-A（T3.1）で Kotlin 側のランナー `:golden-runner` を実装済み**（`:domain` の `jp.jrabot.domain.decision` を実行する。D-106・D-107）。

```bash
./gradlew :golden-runner:run --args="--fixtures $PWD/tools/golden/fixtures --out build/golden/kotlin_out.json --coverage build/golden/kotlin_coverage.json"
python3 -m tools.golden.kotlin_compare --actual build/golden/kotlin_out.json --coverage build/golden/kotlin_coverage.json [--report report.json]
```
- ランナーは**全呼び出しを実行できたケースだけ**を出力し、未対応のケースは理由つきで `coverage` に載せる（空で埋めて成功に見せない）。
- `kotlin_compare` は各フィクスチャ自身の `comparison_rules`（許容誤差・型・キー・順序）で比較し、**対応/未対応のケース数・呼び出し数を別に報告**、最大絶対誤差を出す。申告が `catalog.KOTLIN_PORT`（対応表）と違えば失敗（終了コード 2）。差分があれば 1。
- 現在（T3.2 完了時点）: 21 ケース/810 呼び出しのうち、**Kotlin が検証したのは 18 ケース/661 呼び出し**（全一致、最大絶対誤差 0、比較した浮動小数 62,903 個）。内訳: T3.1 の 10 ケース/380 呼び出し + T3.2 で対応した既存の `GD05-COMBO-001`（33 呼び出し）+ T3.2 で追加した 7 ケース/248 呼び出し。未対応は `G1-MODEL-001`・`G2-HASH-001`（T3.6）、`GD10-DEFAULTS-001`（T1.20）の 3 ケース/149 呼び出しで、**成功に数えない**。CI は `build` ジョブの中で実行する。

比較の一般形（`compare` 単体）:

1. Kotlin 側のランナーが `fixtures/*.json` の `input.calls` を読み、同じ call 名の関数を実行する。
2. 結果を `{"schema_version": 1, "results": {"<case_id>": {"results": [{"call_id": ..., "outcome": ...}]}}}` の形で JSON 出力する。
3. `python -m tools.golden.compare --actual kotlin_out.json [--case ID ...] [--report report.json]`。差分はケース ID・パス・種類・期待値・実際の値・許容誤差で表示し、`--report` に機械処理用 JSON を出す。終了コード: 0=全一致 / 1=差分 / 2=形式不正・**未提供のケースあり**（未実装を成功扱いにしない）。

`compare` / `kotlin_compare` の単体テストの「自己比較」は比較器自体の検査で、Kotlin の結果ではない。Kotlin の結果との比較は上の `:golden-runner` の実行が行う。

### 新しいケースの追加（既存フィクスチャを書き換えない）

`scenarios.py` にケースを足し、`generate --add-new` を使う。**存在しないケースのファイルだけを書き、既存ファイルは 1 バイトも変えない**（`--update` は全ファイルを書き直すので、浮動小数の最後の桁の揺れが既存の期待値の差分に混ざる）。manifest は既存の sha256 を保ったまま追記される。`catalog.KOTLIN_PORT` にも追加し（Kotlin が対応するなら supported、そうでなければ理由つきで unsupported）、`verify` と `generate --check` が通ることを確認する。

```bash
.venv-golden/bin/python -m tools.golden.generate --legacy-path /path/to/jra-bot --add-new
```

## 実装済みケース（旧実装から生成済み: 21 ケース / 810 呼び出し）

`GD01-EV-001` `GD01-KELLY-002`（odds=1.0 の ZeroDivisionError=E5 を含む）`GD02-PROB-001` `GD02-NORM-002` `G2-HASH-001` `G1-MODEL-001` `GD03-FILTER-001` `GD05-COMBO-001` `GD09-EVAL-001`（evaluate_odds 全体、ML 無効の市場フォールバック）`GD10-DEFAULTS-001`（関数既定値）。T3.1 で追加（`evaluate_odds` の単勝判定。D-108）: `GD03-SNAPSHOT-002`（急落・人気急上昇フィルタ）`GD04-WIN-HITRATE-003`（hit_rate モード）`GD08-SELECT-003`（選別・見送り・賭式絞り込み・総投資上限）`GD07-SIZING-004`（縮小係数・Kelly・ブレンド・頭数）。 T3.2 で追加（ワイド/三連複。D-119。`generate --add-new`、既存 14 ケースのフィクスチャは不変）: `GD05-PL-005`（`suggest_multiple_bet_strategies` を直接呼ぶ。Plackett–Luce の確率: 3/5/18 頭・全馬同確率・人気集中・極端に低い確率・確率 0/負/1 超・α の端・2 頭/1 頭）`GD05-FLOW-006`（流しの相手選び）`GD05-BOX-007`（BOX 候補・点数上限・重複した買い目）`GD05-REAL-008`（実オッズ あり/一部/なし/空/0.0・`require_real_odds_for_combo`・見送りエントリ）`GD05-FILTER-009`（縮小 → 上限 → 市場比上限 → 下限 → EV の境界）`GD07-COMBO-STAKE-010`（賭け額・`ticket_allocation`・min/max_bet・確率源の係数）`GD06-EVAL-011`（`evaluate_odds` 全体: best_only・総投資上限での tickets の縮小・hit_rate・見送りシグナル・18/3/2/1 頭）。実オッズは JSON ではタプルのキーを表せないので配列（ワイド `[[a, b, 下限, 上限], …]`、三連複 `[[a, b, c, オッズ], …]`。**キーは昇順**）で与え、`adapter.py` が旧実装の辞書に直す。

## 登録のみ・未実装（Stage 3 へ引き継ぎ）

`catalog.py` が正本（`verify` が整合を検査）。状況（T3.2 完了時点）: 登録 17 項目のうち **implemented=6（GD01・GD03・GD05・GD06・GD07・G1）/ partial=8（GD02・GD04・GD08・GD09・GD10・G2・G3・G5）/ planned=3（G4・G6・G7）**。partial は一部の関数のみ生成済みで、各項目の `remaining` に未実施の範囲を記載。主な未実施: ML 例外時のラベル（E1）、時間帯スケジュール・adaptive_relaxation（daemon 側）、賭式をまたぐ完全な同点の並び（合成入力では作れないので Kotlin の単体テストで固定）、合成 SQLite を使う特徴量ベクトル（G6）、ensemble（G4）。（ワイド/三連複の Plackett–Luce 経路 E8〜E11・hit_rate モード・比例縮小 E6 の旧挙動・`ticket_allocation` は生成済み。）

## トラブルシューティング

| 症状 | 対処 |
|---|---|
| `参照元のコミットが固定値と異なります` | 上記の `checkout --detach <完全 SHA>`。別コミットでの生成は不可 |
| `未コミットの変更があります` | 参照元で `git status` を確認し、変更を戻す（参照元は変更しない） |
| `numpy ... は固定版と異なります` | `pip install -r tools/golden/requirements.txt` の venv を使う |
| `--check` が差分を報告 | 旧実装・numpy・シナリオのいずれかが変わった。意図した変更なら `--update` → PR の差分で期待値の変更を確認 → `DECISIONS.md` に理由 |
| `verify` が sha256 不一致 | フィクスチャを手で編集した、または `--update` せずに編集した。手編集は禁止 |
| テストが `skipped` で失敗 | `GOLDEN_LEGACY_PATH` を設定 |
