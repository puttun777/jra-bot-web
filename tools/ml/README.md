# tools/ml — モデル成果物・データセット契約の Python 参照実装（T3.6-A）

Kotlin（`:domain` の `jp.jrabot.domain.ml.*`）が**唯一の定義**で、ここは**独立な検証**と、Stage 3-F2 の学習 Job が使う小さな部品。実データ・実 pkl・本番の鍵は使わない（`pickle` を読む部品も無い）。

| ファイル | 役割 |
|---|---|
| `canonical.py` | 正準 JSON（`json.dumps(sort_keys=True, separators=(",",":"), ensure_ascii=False, allow_nan=False)` と Kotlin の `CanonicalJson` が同一バイト）、`feature_schema` の hash、モデルの `sha256`、重複キー/NaN を拒否する読込み |
| `signing.py` | Ed25519 の署名・検証（`.sig` は生の 64 バイト、対象はモデル JSON ファイルのバイト列そのもの）。**テスト専用の決定的な鍵**（秘密ではない。本番の秘密鍵は Secret Manager の `MODEL_SIGNING_KEY`） |
| `make_test_artifacts.py` | `domain/src/test/resources/ml` の合成モデル（正常 5 種 + 署名は有効だが中身が不正なもの）・署名・期待値を決定的に生成/検証（`--check`） |
| `dataset_contract.py` | 学習用データセット（gzip CSV + manifest）の契約検証。**学習前に必ず実行**（ML_DESIGN §11.7） |
| `run_tests.py` / `tests/` | unittest ランナー（0 件・スキップを失敗にする） |

```bash
pip install -r tools/ml/requirements.txt                       # cryptography だけ
python3 -m tools.ml.run_tests --min-tests 20 --max-skipped 0
python3 -m tools.ml.make_test_artifacts --out domain/src/test/resources/ml --check
python3 -m tools.ml.dataset_contract --csv-gz X.csv.gz --manifest X.manifest.json [--feature-schema feature_schema.json]
```
- `feature_schema.*.json` は Kotlin が書く: `ML_UPDATE_FIXTURES=1 ./gradlew :domain:test --tests '*FeatureSchemaFixtureTest'`（更新後、環境変数なしでもう一度実行）。その後 `make_test_artifacts` で成果物を再生成する。
- 合成データセットの更新: `ML_UPDATE_FIXTURES=1 ./gradlew :domain:test --tests '*DatasetExporterTest'`。
- 浮動小数のフィールドは**必ず小数点か指数をつけて書く**（`0` と `0.0` は別の正準形 = 別のハッシュ）。

## 旧 pkl → JSON 変換（T1.20。**このリポジトリには実装しない。利用者のローカル操作**）
仕様は `docs/ML_DESIGN.md` §11.9。pkl は GitHub・クラウドに上げない。`pickle.load` は任意コード実行になるため、許可リスト式の `Unpickler` で自分の pkl だけを、ネットワークなしの環境で読む。
