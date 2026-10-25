# ml/ — ML 学習 Job（`ml-train`、T3.6-B）

Python の学習 Job。**必要時だけ実行する独立した Job**で、bot とは別にデプロイ・実行する（通常稼働中に常駐しない）。特徴量は Kotlin の `export-dataset` が作り、ここでは**再計算しない**。
**ACTIVE への昇格はしない**（`ml_model` に `CANDIDATE` / `REJECTED` を INSERT するだけ。DB ユーザー `mltrain` は SELECT/INSERT のみ）。**本番で使える状態ではない**（実データ未検証・品質ゲートの閾値は未承認・GCS 直接アクセス未対応）。

```
Kotlin export-dataset ──(gzip CSV + manifest)──▶ {MODEL_STORE_URI}/{tenant}/datasets/
ml-train train: ① tools/ml/dataset_contract で検証（失敗したら何もしない）→ ② 学習（標準化 + L2 + 直近重み）→ ③ Platt 校正 → ④ 評価・品質ゲート
                → ⑤ モデル JSON（正準・sha256）+ Ed25519 署名 → ⑥ models/・reports/ に保存 → ⑦ 読み戻して署名/sha256 を検証 → ⑧ ml_model に CANDIDATE（不合格は REJECTED）
```

## コマンド
```bash
python -m ml_train train --tenant-id 1 --dataset-id ID --config train.json [--gate gate.json]
python -m ml_train convert-pkl --pkl OLD.pkl --out-dir OUT --tenant-id 1 --model-id ID --schema-dir domain/src/test/resources/ml --confirm-own-local-file   # 利用者のローカル専用
```
学習設定・ゲート設定の形式、環境変数、終了コードは `docs/CONTRACTS.md` §5.1、`src/ml_train/config.py`、`src/ml_train/cli.py` の docstring を参照。**品質ゲートの数値は既定値を持たない**（設定が無ければ REJECTED）。

## 開発
```bash
python3 -m venv .venv && .venv/bin/pip install -r requirements-dev.txt
.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/mypy && .venv/bin/python -m pytest -q
```
依存は `requirements.txt`（推移的依存まで固定。`tools/golden` の numpy 2.5.3 とは独立）。テストは合成データのみ（実データ・実 DB・本番鍵は使わない。署名鍵はテストが実行時に生成）。
MySQL を使う結合テスト（Kotlin → Python → Kotlin）は `:bot` の `MlTrainInteropIntegrationTest`（環境変数 `ML_TRAIN_PYTHON` に、この依存を入れた Python を指定）。
