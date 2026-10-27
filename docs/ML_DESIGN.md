# ML_DESIGN（T1.9 MLの学習・推論方式の設計）

**2026-10-24 更新（Stage 3-F1 / T3.6-A）**: Kotlin の `FeatureBuilder`・確率パイプライン・モデル JSON/署名・レジストリ読取りを実装した。実装結果と、学習側（Stage 3-F2）への引き継ぎ契約は **§11**。§1〜§10 は設計時（T1.9/T1.10）の記述で、実装との対応は §11 に書く。

調査日: 2026-10-22。範囲: 設計のみ。参照元 `jra-bot`（main f7dbf69）の `src/jraev/ml/`、`ev/decision.py`、`ev/probability.py`、`scripts/{train,retrain_models,tune_params,build_dataset,evaluate_model}.py`、`models/*.json`（同梱の 2 ファイル）を精読。**学習済み `*.pkl`・データセット・実 DB はリポジトリに無いため未確認**（実物は利用者の PC）。実データ・モデルは Git に含めない（DATA_SOURCE_COMPLIANCE §5）。

## 1. 現行 ML の実態

### 1.1 モデル
- **単一クラス `WinProbabilityModel`**（numpy のみ）: ロジスティック回帰。`p_raw = sigmoid(clip(X·w + b, ±50))`。
- 属性: `weights`、`bias`、`calib_a/calib_b`（Platt）、`market_blend_alpha`、`feature_names`。**3 種（legacy / calibration / alpha）はすべて同じクラス**で、学習データ（特徴量列）が違うだけ。保存は **pickle**。
- 学習: ミニバッチ勾配降下（`lr=0.1`、`epochs=800`、`l2=1e-4`、`batch_size=2048`）、`class_weight="balanced"`、任意の `sample_weight`（直近重み: 半減期 90/180/240 日）。`np.random.shuffle` は**グローバル RNG**（`train_model` では `np.random.seed(42)` を設定、`scripts/train.py` 経路は未設定）。**特徴量の標準化は無い**（odds は最大 ~1000、`odds×popularity` は最大 ~2×10⁴）。
- 後処理 `_postprocess_prob`: ① p を [1e-6, 1-1e-6] にクリップ → ② Platt: `z=logit(p); p=sigmoid(a·z+b)` → ③ 市場確率 `p_mkt=1/max(odds,0.1)` との **logit 空間ブレンド** `z = α·z_model + (1-α)·z_mkt`（`α=market_blend_alpha`、1.0 ならモデルのみ、0.0 なら市場のみ）。

### 1.2 特徴量（`ml/features.py`）
| 種別 | 次元 | 内容 |
|---|---|---|
| calibration/legacy（`build_feature_vector`） | **40** = 5 + 19 + 16 | base: `odds, popularity, log_odds=ln(max(odds,0.1)), inv_popularity=1/max(pop,1), odds×popularity`（odds≤0 は 100.0 に置換）／extra 19: `has_horse_master, has_past_runs, age_years, past_n, past_win_rate, past_place_rate, past_avg_finish, style_hint_code, distance_place_rate, surface_place_rate, condition_place_rate, days_since_last_run, distance_km, is_turf, is_dirt, is_obstacle, tenko_cd, baba_cd, course_kubun_cd`／pedigree hash 16 |
| alpha（`build_feature_vector_alpha`） | **35** = 19 + 16 | odds/人気を除いたもの |
- **ハッシュ特徴量（再現可能・決定的）**: 祖先名（`pedigree_json` の `name`、最大 8 件）に対し `bucket = int.from_bytes(md5(name)[:4], "little") % 16`、`sign = +1 if md5("sign:"+name)[0] % 2 == 0 else -1`、`vec[bucket] += sign`。MD5 は Kotlin `MessageDigest` で同一結果。
- **時刻依存**: `age_years = (datetime.now() - 生年月日).days/365.25`（**推論時刻/学習データ生成時刻**に依存。レース日基準ではない）。`days_since_last_run` はレース日基準。
- 過去走: `horse_past_runs` から `ORDER BY date DESC, race_id DESC LIMIT 5`（`finish>0`）。**対象レース日より前に限定する条件が無い**（§2 H3）。
- 欠損: DB/馬が無ければ 0 埋め（`has_*` フラグで区別）。

### 1.3 推論経路（`ev/decision.py` `evaluate_odds`）
1. `model_type`（config、コード既定 `legacy`、example は `auto`）。`auto`: calibration の pkl があれば `calibration`、無ければ alpha、無ければ legacy。
2. モデル読込（`predict.get_model`: mtime キャッシュ、失敗時 None）。**`market_blend_alpha` を config 値（既定 0.25）で実行時に上書き**。
3. 特徴量名ガード: `model.feature_names != get_feature_names()`（40 名）なら `ValueError`。
4. `alpha`: **`build_feature_vector(odds=0, popularity=0)`（40 次元）を作ってからモデルの重み次元へスライス/ゼロ埋め**（`_align_feature_matrix`）。`calibration`: 40 次元そのまま。
5. 各モデルの `predict_proba_from_features`（= 重み → sigmoid → `_postprocess_prob`）→ **レース内正規化**（合計 1、各馬 ±10% 補正制限 → 再正規化: `_normalize_with_correction`）。
6. `ensemble`: 馬ごとに `coverage=0.5·has_master+0.3·has_past+0.2·min(1,past_n/5)`、`β_eff=β·coverage`。オッズ有りかつ calib あり: `p=(1-β_eff)·calib+β_eff·alpha`（alpha 無しなら calib のみ）。それ以外は alpha（なければ calib）。β: config 数値、または `auto`=`ensemble_params_latest.json` の `best_params.ensemble_beta`（`calibration_available=false`→1.0、生成後 14 日超→0.25、β<0.05→0.25、ファイル無し/例外→0.25）。結果を再度正規化。
7. **2 回目の市場ブレンド**（decision 側、`market_blend_alpha` 既定 0.25）→ 単勝確率上限クリップ（`win_prob_clip_max` 既定 0.35、`enable_prob_clip`）→ **再正規化**。
8. **例外はすべて握り潰し**（`except Exception: normalized_probs=None`）→ 市場確率（`estimate_prob_normalized`、1/odds を正規化し ±10% 補正）へフォールバック。

### 1.4 学習・運用スクリプト
| スクリプト | 役割 |
|---|---|
| `build_dataset.py` / `dataset_builder.py` | DB → 特徴量 → `train.parquet`/`valid.parquet`（時系列 8:2 分割）。calibration は `odds_snapshots` 必須（発走時刻以前の最新スナップショット）。alpha は odds 無し特徴量。品質チェック（`has_horse_master` 割合、定数特徴量など） |
| `train.py` | `train_calibration_model` / `train_alpha_model`（同クラス）。メトリクス（accuracy/precision/recall/F1/AUC/LogLoss/Brier）、pickle 保存 |
| `retrain_models.py`（636 行） | 一括: 期間算出 → データセット → 学習 → 品質・劣化チェック（`_check_metrics_degradation`）→ タイムスタンプ付きモデル → `*_latest.pkl` を**原子的置換** → `metrics_latest.json` |
| `tune_params.py` | `ensemble_beta`（0〜0.8, 0.05 刻み）× 半減期（90/180/240）のグリッド探索 → `ensemble_params_latest.json` |
| `evaluate_model.py` | 単勝のキャリブレーション評価（Brier/LogLoss/信頼度曲線）→ `model_metrics` |
| `run_daemon.py`（常駐内） | `auto_retrain`: 新規結果 ≥10 件 or 24 時間ごとに**legacy のみ**非同期再学習（F12） |

## 2. 重要な疑義（コード精読 + 同梱メトリクスからの推定。**実物での検証が必要**）
| # | 疑義 | 根拠 | 影響 | 検証方法（利用者の実環境） |
|---|---|---|---|---|
| **H1** | **現行の本番推論は市場確率にサイレントフォールバックしている可能性が高い** | ① 同梱 `metrics_latest.json`: `calibration_skipped=true`（`odds_snapshots` が 0 件）、alpha のみ学習・**特徴量 35 次元**。② `retrain_models.py` は alpha の `feature_names = get_feature_names_alpha()`（35 名）を設定。③ `decision.py` は期待名を `get_feature_names()`（40 名）と比較し不一致で `ValueError("alpha model feature_names mismatch")`。④ 外側の `except Exception` が握り潰し、`normalized_probs=None` → 市場フォールバック（`prob_source` は "market:fallback(ml_disabled)" と**誤表示**される） | 「ML の EV」と思われていた通知が実は市場確率ベースだった可能性。移行時の「旧新比較」の基準が不明確になる | `signals.prob_source` / `race_evaluations.model_type_used` の分布を集計 |
| **H2** | **alpha の特徴量ミスアライン**（H1 を回避する場合。`scripts/train.py` 経由で学習した alpha は `feature_names` に 40 名を設定するためガードを通過する） | 推論は 40 次元ベクトルを重み次元 35 へ**先頭からスライス**（`_align_feature_matrix`）。alpha の学習列は `[extra19, ped16]` だが、推論列は `[odds, pop, log_odds, inv_pop, inter, extra19 の先頭 30…]` → **5 列ずれる** | 仮に動いていても出力は無意味 | ダミー入力で alpha 重みに通した結果を比較 |
| **H3** | **ラベルリーク（学習時）** | 過去走集計 `get_horse_feature_summary` が `horse_past_runs` の直近 5 走を**対象レース日で絞らず**取得。`horse_past_runs` は同レース自体の SE も含み得る（`PRIMARY KEY (ketto_num, race_id)`）→ `past_win_rate/past_place_rate/past_avg_finish` に**正解が漏れる**。同梱 alpha メトリクス（AUC 0.913、recall 0.97、precision 0.25）は単勝予測としては不自然に高い | 学習メトリクスが過大。実運用精度は大幅に低い可能性 | 学習時点以前の行のみで特徴量を再計算して AUC を比較 |
| **H4** | **二重の市場ブレンド** | モデル内 `_postprocess_prob`（α=0.25 に実行時上書き）→ 正規化 → decision 側でもう一度 α=0.25。**モデル寄与は logit 重みで 0.25×0.25≈0.0625**、市場 0.9375 | 「モデルの影響が小さすぎる」状態。閾値・EV 設計が市場支配の前提で調整されている可能性 | 設計意図の確認（ユーザー） |
| **H5** | **確率校正の欠如（alpha）** | `class_weight="balanced"` で学習した確率は事前確率が歪む。Platt は `train_model`（legacy）のみで `scripts/train.py`/`retrain_models` の alpha/calibration では未適用。同梱メトリクス Brier **0.143** は一定予測（勝率 ≈7% ⇒ Brier ≈0.065）より**悪い** | 正規化・ブレンドで緩和されるが、単体のモデル確率としては不適切 | 校正曲線（`evaluate_model.py`）で確認 |
| H6 | 特徴量未標準化 + GD | odds×popularity は桁が大きく、lr=0.1・800 epoch の収束が不安定になり得る。`feature_1, 11〜14` が定数（分散 0）と品質警告に記載 | 重みの不安定さ | 学習曲線/重みの確認 |
| H7 | 非決定性 | `np.random.shuffle`（グローバル）+ `train.py` 経路でシード未設定 | 同一データで結果が再現しない | — |
| H8 | `age_years` が `now()` 基準 | 学習データ生成日と推論日で値がずれる（リーク/スキュー） | 小 | — |
| H9 | `has_horse_master` が低い（11%） | 同梱品質警告。馬マスタ（UM）の取り込み不足 | 特徴量の多くが 0 埋め | 取込状況の確認 |
| H10 | `prob_source` ラベルが事実と不一致（ensemble 判定にループ末尾の変数を使用）、Kelly の確率ソース係数 `m` が "ml_features/ml_model/market" 以外（"ml:alpha" 等）では常に 0.8 | decision.py / bet_strategy.py | ログ/DB 上の確率ソースが信頼できない | — |
**結論**: 旧 ML の「予測結果」を移行の正解とみなせない。**移行の互換性は「推論の算術」（与えられた重み・特徴量→確率）で保証し、モデル品質（学習側）は修正版として再設計**する（D-033）。旧 pkl は互換性検証用の入力に限る。

## 3. 方式の比較（A/B/C）

| 観点 | **A 学習=Python / 推論=Kotlin** | B 学習・推論とも Python | C 学習・推論とも Kotlin |
|---|---|---|---|
| 推論の置き場 | bot（JVM）内 | Python サイドカー or 別サービス（プロセス間通信） | bot 内 |
| 実装規模（推論） | 小（ロジスティック回帰 + Platt + ブレンドは数十行。ハッシュは MD5） | 小（既存流用） | 小 |
| 実装規模（学習） | 既存 Python を整理・修正（H1〜H9 の是正） | 同左 | 大（データ処理・評価・再現性を再実装、ライブラリ選定） |
| 運用コスト | **低**: 常駐は JVM のみ。学習は必要時のみ Job | 高: bot に Python ランタイム/RPC、2 言語の常時運用、イメージ肥大・コールドスタート増 | 低 |
| 学習/推論の分離 | ◎（成果物 JSON が契約） | ○ | ○ |
| 特徴量の一貫性（学習・推論スキュー） | 要設計（後述: 特徴量は Kotlin で生成して学習に渡す） | ◎（同一コード）が、DB 読込を Python に持つ | ◎ |
| テスト容易性 | ◎（ゴールデン: Python 参照実装 ↔ Kotlin） | ○ | ○（参照が無くなる） |
| 将来の再学習・実験 | ◎（Python エコシステム） | ◎ | △ |
| 障害影響 | 推論は JVM 内で完結（外部依存なし） | Python 障害が判定に波及 | 推論と学習の障害が同一 |
**採用案: A**（D-034）。モデルが**線形（ロジスティック回帰）**で推論が極めて単純なため Kotlin 実装の費用が小さく、Python を実行経路から外せる。学習は Python のまま（`pandas`/`numpy`/`scikit-learn` が使える。参照元の「32bit で sklearn 不可」制約は消える）。C は将来の選択肢として残す（再学習ロジックが固まり Python の価値が薄れたら再評価）。B は運用コスト・障害面で不利。

## 4. 学習と特徴量の設計（A の詳細）

### 4.1 特徴量は一箇所で生成（スキュー防止・リーク防止）
- **特徴量生成は Kotlin（`:domain` の `FeatureBuilder`）に一本化**する。推論は bot が呼び、学習用データは Kotlin の **`app --task=export-dataset`** が同じコードで出力する（学習側で再実装しない）。
- **時点整合（point-in-time）**: 特徴量はすべて「評価時点 `asOf`（発走前の評価時刻）」以前の情報だけで計算。過去走は `race_date < 対象レース日`（同日以降を除外）、`age_years` は**レース日基準**、オッズは `asOf` 以前の最新スナップショット。これで H3・H8 を構造的に解消。
- エクスポート形式: **gzip 圧縮 CSV（行=馬×評価時点、列=`feature_schema` の順、ラベル、race_id, tenant_id, asOf, `sample_weight` 用の日付）**。Kotlin から Parquet を書くライブラリは持ち込まない（依存最小化）。Python は `pandas.read_csv`。データは**テナントごと・私的バケット（日本リージョン）**に出力し Git に置かない（保持期間は規約回答後、既定案 90 日）。
- 欠損の扱い（0 埋め + `has_*`）は現行互換を既定とし、`feature_schema` に明記。
- **スキーマ版管理**: `feature_schema = {version, names[], pedigree_dim, hash_algo:"md5-le32-mod16", missing_policy}`、`feature_schema_hash = sha256(canonical json)`。モデルは学習時のハッシュを保持し、推論時に一致しなければロードを拒否。

### 4.2 学習ジョブ（Python、必要時のみ）
| 項目 | 設計 |
|---|---|
| 実行形態 | **Cloud Run Job 相当の独立コンテナ `ml-train`**（Python イメージ。bot とは別）。トリガ=手動（管理者）/ 低頻度の定期（初期は無効、運用モードが NORMAL の時のみ）。**常駐しない**（現行の daemon 内 `auto_retrain` は廃止 = F12） |
| 入力 | データセットエクスポート（§4.1）、学習設定（期間、半減期、シード、ハイパーパラメータ）。DB へ直接つながない（テナント境界・費用・STOPPED 時の制御を容易にするため。エクスポートは別 Job） |
| 再現性 | 乱数は `numpy.random.default_rng(seed)`（シャッフルも）。データセットの sha256、コード git SHA、ライブラリ版を成果物メタに記録。**学習結果のビット同一は保証しない**（BLAS 差）が、重みの許容差を検証 |
| 学習法（v2 案） | 特徴量標準化（`mean/std` をモデルに保存）+ L2 付きロジスティック回帰（収束が決定的なソルバ）。**`class_weight` は使わず**、時系列ホールドアウトで Platt（または等張）校正。互換モード（legacy 再現）は GD + 既存ハイパーパラメータで、互換検証用にのみ使用 |
| 評価 | 時系列分割（walk-forward）。LogLoss/Brier/AUC/校正傾き・切片/信頼度曲線、**市場確率ベースライン（1/odds 正規化）との比較**、直前モデルとの比較。リーク検査（特徴量の `asOf` > 発走時刻をアサート）、定数特徴量/低分散警告、`has_horse_master` 割合警告 |
| 品質ゲート | 最小サンプル数、メトリクスが市場ベースライン/旧モデルより悪化なら **REJECTED**（昇格させない）。不合格でも成果物とレポートは残す（原因調査用） |
| 失敗時 | 非 0 終了、レジストリは変更しない（旧モデル継続）、アラート（Discord エラー Webhook）。リトライしない（`max-retries=0`、終了コードは CONTRACTS §9。T1.10 で変更）。部分成果物は一時パスのみで、sha256 検証後に確定登録（原子性） |
| ensemble の β | `tune` の結果を**モデルセット（calibration+alpha+β）の成果物に同梱**（`ensemble_params_latest.json` の外部ファイル依存と 14 日失効ロジックは廃止。β は成果物の一部としてバージョン管理） |
| 費用 | 1 回 10〜20 分・2 vCPU 程度を想定。費用は無料枠に依存せず見積もる（infra CLOUD_COSTS §10。1 回あたりは小額）。通常時は 0 円。データセット/成果物の保管は数十 MB |

## 5. モデルファイル形式（非 pickle）

### 5.1 比較
| 形式 | 長所 | 短所 | 評価 |
|---|---|---|---|
| **JSON** | 人が読める・差分が取れる・Kotlin/Python 双方で標準対応・任意コード実行なし・版管理容易。重みは数十個なので容量問題なし | 浮動小数は十進表記（往復で厳密一致するよう最短表現で出力） | **採用** |
| NPZ | numpy ネイティブ・バイナリで厳密 | Kotlin で読むには zip+npy パーサが必要。メタ情報に別ファイルが要る。`allow_pickle=False` 必須 | 不採用 |
| ONNX | 標準・将来の非線形モデルに有利 | JVM に ONNX Runtime（JNI）の依存。線形 1 層には過剰 | 将来の選択肢 |
| Protobuf/PMML | 型付け/標準 | 追加ツール・依存 | 不採用 |
| pickle/joblib | — | 任意コード実行（ロード時）、バージョン依存 | **禁止**（D-008） |

### 5.2 JSON スキーマ（`model_schema_version: 1`）
```json
{
  "model_schema_version": 1,
  "model_id": "01J…(UUIDv7)",
  "tenant_id": 1,
  "kind": "calibration | alpha | legacy_compat",
  "algorithm": "logistic_regression",
  "feature_schema": { "version": 1, "hash": "sha256:…", "names": ["odds", "…"], "pedigree_dim": 16, "hash_algo": "md5-le32-mod16", "missing_policy": "zero_with_flags" },
  "preprocessing": null,
  "weights": [0.0, …],
  "bias": 0.0,
  "calibration": { "type": "platt", "a": 1.0, "b": 0.0 },
  "market_blend": { "alpha_default": 0.25 },
  "ensemble": null,
  "training": { "from": "2025-01-17", "to": "2026-01-17", "n_train": 0, "n_valid": 0, "seed": 42, "lr": 0.1, "epochs": 800, "l2": 1e-4, "class_weight": "balanced|none", "recency_halflife_days": 180, "dataset_sha256": "…", "git_sha": "…", "libs": {"numpy": "…"} },
  "metrics": { "logloss": 0.0, "brier": 0.0, "auc": 0.0, "n": 0, "baseline_market_logloss": 0.0 },
  "created_at": "2026-11-01T00:00:00Z",
  "producer": "ml-train/1.0.0",
  "sha256": "…(本ファイルから sha256 フィールド自体を除いた正規化 JSON のハッシュ)"
}
```
- `preprocessing`（v2）: `{ "type": "standardize", "mean": [...], "std": [...] }`。legacy 互換モデルでは `null`。
- `ensemble`（ensemble セット）: `{ "members": {"calibration":"model_id","alpha":"model_id"}, "beta": 0.25, "coverage_weights": [0.5,0.3,0.2] }`。
- 実数は JSON 数値（Python は `repr`、Kotlin は `Double` の最短表現）で往復一致。**NaN/Infinity を禁止**（検証で拒否）。
- 署名: 学習ジョブがモデル JSON 全体に Ed25519 署名（`.sig`）を付け、bot が検証（バケット書換え対策、低コスト）。鍵は Secret Manager（infra 側）。

### 5.3 pickle からの変換（移行用・一回限り）
- 変換ツール（Python、利用者ローカルで実行。**リポジトリに pkl も出力 JSON も入れない**）。`pickle.load` は任意コード実行のため**自分の pkl だけ**を、ネットワーク無しの環境で読む。
- 出力: `kind=legacy_compat`、`weights/bias/calib_a/calib_b/market_blend_alpha/feature_names` を JSON 化し、`feature_names` から `feature_schema` を構築。**次元不一致（H1/H2 の疑い）を変換時に検出して警告**。
- 検証: ランダム特徴量 N 件を旧クラス（参照元）と Kotlin 実装の両方に通し、差が許容内か確認（§8）。旧 5 次元モデル（`weights` 長 5）も `legacy_compat` で扱う（パディング/スライスはしない。次元が特徴量スキーマと違えば**ロード拒否**）。

## 6. モデル管理（レジストリ・切替・整合性）
```sql
CREATE TABLE ml_model (
  model_id        CHAR(36) PRIMARY KEY,
  tenant_id       BIGINT NOT NULL,                  -- モデルは契約者のデータ由来 → テナント単位
  kind            ENUM('calibration','alpha','legacy_compat','ensemble_set') NOT NULL,
  schema_version  INT NOT NULL,
  feature_schema_hash CHAR(71) NOT NULL,
  artifact_uri    VARCHAR(500) NOT NULL,            -- 私的バケット（クラウド依存は URI のみ）
  sha256          CHAR(64) NOT NULL,
  status          ENUM('CANDIDATE','ACTIVE','RETIRED','REJECTED') NOT NULL,
  active_flag     TINYINT NULL,                     -- ACTIVE のとき 1、それ以外 NULL（UNIQUE で有効モデルを kind ごとに 1 つに制限）
  trained_from    DATE NULL, trained_to DATE NULL, n_train INT NULL,
  metrics_json    JSON NULL,
  created_at      DATETIME(6) NOT NULL,
  activated_at    DATETIME(6) NULL, activated_by VARCHAR(100) NULL,
  previous_model_id CHAR(36) NULL,
  UNIQUE KEY uq_active (tenant_id, kind, active_flag)   -- 有効は kind ごとに 1 つ(active_flag=1, 他は NULL)
);
```
| 要件 | 設計 |
|---|---|
| バージョン管理 | `model_id`（UUIDv7）+ 成果物の sha256。レジストリで状態遷移 CANDIDATE → ACTIVE → RETIRED（REJECTED は品質ゲート不合格） |
| 切替 | **管理者の昇格操作**（または将来の自動昇格）で `ACTIVE` を 1 つに更新（トランザクション）。直前モデルへ**ロールバック**可（`previous_model_id`）。操作は監査ログ |
| 切替時の整合性 | **bot-run は開始時に使用モデル（model_id 群）を固定**し、ラン中は切り替えない。使用した `model_id` を `race_evaluations` に保存（現行の `model_path_used` を置換）。ensemble は**セット単位で原子的に切替**（calibration と alpha と β を別々に更新しない） |
| 読込・キャッシュ | 起動時にレジストリから ACTIVE を取得 → 成果物取得 → **sha256/署名/スキーマ版/`feature_schema_hash`/重み長を検証**してメモリに保持（不変オブジェクト）。mtime 監視の自動再読込は行わない（run 単位で固定） |
| 不整合の検出 | ①スキーマ版が未対応、②`feature_schema_hash` 不一致、③重み長 ≠ 特徴量数、④NaN/Inf、⑤sha256/署名不一致 → **ロード拒否**（パディング/スライスで合わせない） |
| 推論失敗時のフォールバック（**サイレント禁止**） | 順序: ensemble → calibration 単体 → alpha 単体 → legacy_compat → **市場確率のみ**。各段階の発動は `fallback_reason`（コード）を `race_evaluations` と構造化ログに記録し、**ML 有効設定で市場確率のみになったらアラート**（1 日 1 回 + 解消まで）。`prob_source` は事実に基づく正確なラベルにする |
| モデルのテナント分離 | モデルは契約者のデータ由来のため `tenant_id` ごとに学習・保管・使用（他テナントのモデルを使わない）。共有する場合は規約確認が必要（DATA_SOURCE_COMPLIANCE #9） |

## 7. Kotlin 推論の設計

### 7.1 パイプライン（段階を明示し設定で切替）
```kotlin
interface ProbabilityPipeline {  // 純関数。副作用・時刻・IO なし
  fun predict(race: RaceSnapshot, horses: List<HorseFeatures>, cfg: ProbabilityConfig): ProbabilityResult
}
data class ProbabilityResult(val probs: List<Double>, val source: ProbSource, val fallbackReason: FallbackReason?, val meta: Map<String,Any>)
```
段階（`pipeline_version = "legacy" | "v2"`）:
1. **特徴量**: `FeatureBuilder`（as-of 方式）→ ベクトル（`feature_schema` の順）。
2. **前処理**: `preprocessing` があれば `(x-mean)/std`。
3. **線形**: `z = dot(w,x)+b`、`clip(z,±50)`、`p=1/(1+exp(-z))`。
4. **後処理（モデル内）**: `p` を [1e-6,1-1e-6] → Platt（`a·logit(p)+b`、`z` クリップ ±50）→ [1e-6,1-1e-6]。**モデル内の市場ブレンド**は `legacy` モードのみ（α = config）。
5. **レース内正規化**（`normalize_with_correction`: 合計 1 → 各馬 ±`correction_limit`(0.10) → 再正規化。raw≤0 は norm をそのまま）。
6. **ensemble**（セットの場合）: `coverage`・`β_eff` で合成 → 再正規化。
7. **市場ブレンド**（decision 側）: `z=α·logit(p)+(1-α)·logit(p_mkt)`（p が (1e-6,1-1e-6) 外なら z_model=0）→ 上限クリップ（`win_prob_clip_max`）→ **再正規化**。
- **`pipeline_version=legacy`**: 参照元の挙動を**そのまま再現**（二重ブレンド、実行時上書き、順序、丸めなし）。ゴールデンテストの基準。
- **`pipeline_version=v2`**: ブレンドは**1 回**（モデル内ブレンドを無効化し decision 側のみ、または明示的な重み）、フォールバックは明示、`prob_source` は正確。**既定は legacy 互換の設定値で開始し、v2 への切替は旧新比較と閾値の再検討（EV/ratio 閾値は二重ブレンド前提で調整されている可能性: H4）の後にユーザー判断**。

### 7.2 数値の扱い
- すべて `Double`（IEEE754 倍精度）。`exp/log` は `kotlin.math`（`java.lang.Math`）を使用。**`StrictMath` への切替は不要**（差は ≤ 1ulp 程度）。
- dot の総和順序は特徴量順の単純な逐次加算。numpy の BLAS 加算順と異なるため**差は 1e-15 相対程度**。許容を明示: **確率 p は絶対誤差 ≤ 1e-9、logit z は 相対 1e-12 かつ絶対 1e-9**。
- 丸め処理は**推論内では行わない**（表示層のみ）。`np.clip` と `coerceIn` は同値。`np.log(p/(1-p))` と `ln(p/(1-p))` は同じ式を使う（`log1p` 等に置換しない）。
- ハッシュ特徴: `md5(utf8(name))[0..3]` をリトルエンディアン**符号なし** 32bit として `mod dim`（Kotlin は `Long` と `and 0xFFFFFFFFL`）。符号は `md5("sign:"+name)[0] and 1 == 0`。名前は DB の文字列を**正規化せずそのまま**（全角/半角の差は同一視しない。`NFC/NFKC` は適用しない: 参照元に合わせる）。`errors="ignore"` 相当（不正サロゲートの除去）はテストで確認。
- NaN/Inf が出たら例外（ロード拒否・サイレント置換はしない）。

### 7.3 ゴールデンテスト（第3段階）
| # | 目的 | 入力 → 期待値の出所 | 許容 |
|---|---|---|---|
| G1 | 線形+Platt+市場ブレンド | 合成の重み/特徴量（シード固定、境界値: odds=0/0.1/1.0/999、popularity=0/1/18、z=±50 付近）→ **参照元の `WinProbabilityModel` を固定コミット（f7dbf69）で実行した出力**を JSON フィクスチャ化 | p: 1e-9 |
| G2 | ハッシュ特徴 | 合成の祖先名リスト（全角・かな・漢字・空・重複）→ 参照元の `_hash_bucket/_hash_sign` の出力 | 完全一致 |
| G3 | 正規化・補正 | 合成 odds 列 → `estimate_prob_normalized`/`_normalize_with_correction` の出力（頭数 1、全 0、極端に偏った場合を含む） | 1e-12 |
| G4 | ensemble | calib/alpha/coverage/β の組合せ全パターン（odds 無し、calib 無し、alpha 無し、両方無し） | 1e-9 |
| G5 | decision 側ブレンド+クリップ+再正規化 | 参照元 `evaluate_odds` の確率部分を抽出して比較 | 1e-9 |
| G6 | 特徴量ベクトル | 合成の馬/過去走/レース条件（SQLite に合成データを入れ参照元の `build_feature_vector` で生成）→ Kotlin `FeatureBuilder`（**age は参照元が now 基準のため、固定時刻を注入して比較**。as-of 版は別テスト） | 1e-12 |
| G7 | 失敗系 | 次元不一致、NaN、スキーマハッシュ不一致、署名不一致、モデル無し → ロード拒否/フォールバック理由が期待通り | — |
- フィクスチャは**合成データのみ**（実データ・実 pkl 不使用）。生成スクリプト `tools/golden/gen_ml_fixtures.py` は参照元のパスを引数に取り、JSON のみをコミットする（参照元のコードを複製しない: D-004）。
- 追加: 性質テスト（p∈[0,1]、正規化後の和=1、`α=1` でモデルのみ/`α=0` で市場のみ、単調性）。

### 7.4 学習と推論の独立デプロイ
- 契約は**モデル JSON スキーマ**（`model_schema_version`）とレジストリ。bot は版 N と N-1 を読める（後方互換）。学習ジョブは版 N を出力。
- `ml-train` と `bot` は互いに依存しない（共有は JSON スキーマと `feature_schema` 定義のみ。`feature_schema` の正は Kotlin 側の `FeatureBuilder` 定義から**生成**してエクスポートに同梱）。
- クラウド依存: 成果物の保管先は URI（オブジェクトストレージ）で抽象化（`ModelStore` ポート）。

## 8. 既存モデルとの整合・移行手順
1. **現状把握（利用者の実環境、最優先）**: `SELECT prob_source, COUNT(*) FROM signals GROUP BY 1` と `SELECT model_type_used, COUNT(*) FROM race_evaluations GROUP BY 1`（および直近のログ）で、実際に ML が使われていたか（H1）を確認。結果を DECISIONS に追記。
2. 変換ツールで `*.pkl` → JSON（`legacy_compat`）。次元不一致を警告。
3. ゴールデン（G1〜G7）で Kotlin 推論の算術一致を確認。
4. 旧新比較（過去の `race_evaluations`/`signals` の記録値と Kotlin の `legacy` パイプラインの出力を比較）。H1 が真なら「旧=市場確率フォールバック」が正で、新実装はそれと一致することを確認した上で **ML を有効化するかを別判断**。
5. 学習ジョブ v2 で再学習（リーク是正済み）→ 品質ゲート → CANDIDATE → 管理者が昇格。
6. 並行稼働期間は新旧とも**通知しない/比較ログのみ**のシャドーモードを推奨（T6）。

## 9. 決定案・未確認
| ID | 内容 | 状態 |
|---|---|---|
| D-033 | 移行の互換性は「推論の算術」で保証。旧モデルの予測品質は正解とみなさない（H1〜H5）。学習は修正版（リーク是正・時点整合）で再設計 | 案 |
| D-034 | **方式 A**: 学習=Python（独立 Job）、推論=Kotlin（bot 内）。特徴量生成は Kotlin に一本化しデータセットをエクスポート | 案（採用推奨） |
| D-035 | モデル形式=JSON（スキーマ版・署名・sha256）。NPZ/ONNX は不採用/将来。pickle 禁止 | 案 |
| D-036 | モデルレジストリ（`ml_model`、テナント単位、run 開始時に固定、ensemble はセットで原子切替、フォールバックは明示・サイレント禁止） | 案 |
| D-037 | 確率パイプラインは `legacy`（挙動再現）と `v2`（単回ブレンド・明示）を設定で切替。v2 への切替はユーザー判断 | 案 |
| 未確認 | 実 pkl の中身、H1 の実態（prob_source 分布）、`feature_1,11〜14` が定数の理由、`has_horse_master` が低い理由、α=0.25 二重適用が意図か | 利用者の実環境で確認（T1.20） |

## 10. T1.10 での確定事項（2026-10-22）
- **ml-train の DB 権限は `ml_model` の SELECT/INSERT のみ**（DB ユーザー `mltrain`）。学習データは GCS のデータセット（`export-dataset` 出力）だけから読み、DB の業務テーブルに触れない。ACTIVE への昇格は dashboard の管理者操作（CONTRACTS §5.1・§8.2, D12）。
- **確率パイプラインの本番初期値は `legacy`**。`v2` は新設計の目標だが**高精度とは未証明**。旧新比較（G-ML-2）・バックテスト（T3.9, G-ML-3）・閾値再検討の後、ユーザー承認で切替える。**自動切替はしない**（D-037）。
- 欠陥候補との対応（H1/H2/H3/H4/H10, E1/E5）と各項目の承認要否は `MIGRATION_MATRIX.md` §8。
- 検証ゲート: G-ML-1（実データで H1 の実証: `prob_source` 分布, T1.20）、G-ML-2（実データのリプレイで legacy と旧記録値の一致, ローカルのみ）、G-ML-3（v2 vs legacy のバックテスト）。

## 11. T3.6-A の実装結果と、学習側（Stage 3-F2）への引き継ぎ契約（2026-10-24）

**範囲**: Kotlin による特徴量生成・モデル検証・推論・ゴールデン比較（T3.6 の前半）。Python の本格的な再学習・`ml-train` イメージ・`export-dataset` タスクの配線・pkl 変換の実行・bot-run への統合は含まない（T3.6-B / T3.8 / 利用者のローカル操作）。判断は DECISIONS D-152〜D-164、進捗は PROGRESS の Stage 3-F1。

### 11.1 実装したクラス（`:domain` の `jp.jrabot.domain.ml`）
| パッケージ | 主なクラス | 役割 |
|---|---|---|
| `json` | `StrictJson`・`CanonicalJson`・`JsonValue` | 重複キー/NaN/孤立サロゲート/先頭ゼロを拒否する厳密パーサと、Python と同一バイトの正準 JSON（§11.3） |
| `features` | `FeatureSchema`・`FeatureNames`・`PedigreeHash` | 40/35/5 次元のスキーマと hash、血統ハッシュ（md5-le32-mod16） |
| `features` | `LegacyFeatureBuilder` / `PointInTimeFeatureBuilder` / `FeatureSource` / `InMemoryFeatureSource` / `LegacyRows` | 旧互換（G6 の比較対象）と point-in-time（新方式）。入力は `FeatureSource` ポート（`:persistence` は後続で実装） |
| `model` | `LogisticScoring` / `ModelDocument` / `ModelLoader` / `ModelTrust` / `VerifiedModel` | `X·w+b`・clip・sigmoid・Platt・市場ブレンド、モデル JSON の検証（署名 → 正準形 → sha256 → スキーマ → 次元）、Ed25519 |
| `pipeline` | `LegacyProbabilityPipeline` / `V2ProbabilityPipeline` / `FallbackReason` / `ProbSource` | 確率パイプライン（§11.5）と理由コード |
| `registry` `store` | `ModelRegistryReader` / `ModelRunPin` / `ModelSession` / `LocalFileModelStore` / `InMemoryModelRegistry` | レジストリ読取り・bot-run 開始時の固定・検証つき読込み・ローカルの合成ストア |
| `dataset` | `DatasetExporter` / `DatasetSource` | 学習用データセットの書出し契約（§11.7） |
| （直下） | `MlInference` | 固定済みモデルで馬ごとの確率を計算（特徴量の作成 → 検証済みモデル → パイプライン） |
`:persistence`: `MlModelRegistryRepository`（`ml_model` の SELECT のみ）。`:golden-runner`: 旧 `WinProbabilityModel` の互換モジュール `GoldenMl`（**次元の切詰め/ゼロ埋めはここだけ**）。Python: `tools/ml`（正準 JSON・署名・データセット契約の参照実装とテスト用成果物）。

### 11.2 feature_schema の JSON 契約（Python は再実装せずに読む）
```json
{"version":1,"names":["…"],"pedigree_dim":16,"hash_algo":"md5-le32-mod16","missing_policy":"zero_with_flags","hash":"sha256:<hex64>"}
```
- `hash` = `"sha256:"` + SHA-256(正準 JSON(`hash` を除いたオブジェクト))。名前の順序が 1 つでも変わると変わる。alpha（35 次元: 追加 19 + 血統 16）と calibration（40 次元: 市場 5 + 追加 19 + 血統 16）は**別のスキーマ・別の hash**。旧 5 次元モデル用に `MARKET_5`（`pedigree_dim=0`、`hash_algo="none"`）。
- **正本は Kotlin の `FeatureSchema`**。`domain/src/test/resources/ml/feature_schema.{calibration40,alpha35,market5}.json` に正準 JSON で書き出し（`FeatureSchemaFixtureTest` が Kotlin の定義と一致を検査）、Python（`tools/ml`）が**名前の一覧を持たずに** hash を独立に再計算して検証する。学習側はこのファイル（またはデータセットの manifest に同梱された `feature_schema`）を読み、列の順序をこれに従わせる。
- 暗黙の補正をしない: モデルの `feature_schema` がこのランタイムの作れるスキーマ（上の 3 つ）で、かつ `kind` が許すもの（calibration=40、alpha=35、legacy_compat=5 または 40）でなければロード拒否。

### 11.3 正準 JSON・sha256・署名（D-154・D-155・D-156）
- 正準 JSON: UTF-8（BOM なし）、空白なし、キーはコードポイント順、文字列は `"`・`\`・制御文字だけをエスケープ、**整数と浮動小数は別**（`1` と `1.0` は別の正準形）、浮動小数は Python の `repr(float)`、NaN/Infinity/重複キー拒否。Python の `json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=False, allow_nan=False)` と同一バイト（`tools/ml/canonical.py`）。**浮動小数のフィールドは必ず小数点か指数をつけて書く**（学習側の出力で `weights` の `0` を整数で書くと別のハッシュになる）。
- モデル JSON（§5.2）: `model_schema_version`=1、`model_id`、`tenant_id`、`kind`（`calibration|alpha|legacy_compat|ensemble_set`）、`algorithm`（`logistic_regression` / `ensemble_set`）、`feature_schema`、`preprocessing`（`null` または `standardize`: `mean`・`std`（正の有限））、`weights`、`bias`、`calibration`（`null` または `platt`: `a`・`b`）、`market_blend.alpha_default`、`ensemble`（`ensemble_set` のみ: `members.{calibration,alpha}`・`beta`∈[0,1]・`coverage_weights`（3 要素の非負））、`training`、`metrics`、`created_at`（ISO-8601 UTC）、`producer`、`sha256`。未知のキーは拒否。`sha256` = `sha256` を除いた正準 JSON の SHA-256（小文字 16 進 64 桁）。
- 署名: Ed25519（RFC 8032 pure）、`.sig` は生の 64 バイト、**対象はモデル JSON ファイルのバイト列そのもの（= 正準 JSON）**。検証鍵 `MODEL_VERIFY_PUBKEY` は生 32 バイトの Base64（カンマ区切りで複数 = ローテーション）。ml-train の秘密鍵（`MODEL_SIGNING_KEY`）は Secret Manager だけ。
- 読込み順序（fail closed。`ModelLoader`）と理由コード:
| 順 | 検査 | 理由コード |
|---|---|---|
| 1 | 署名が無い/64 バイトでない/どの鍵でも不一致（**JSON の解析より前**） | `MODEL_SIGNATURE_INVALID` |
| 2 | 厳密 JSON・正準形・必須/未知のキー・型・NaN/Infinity | `MODEL_INVALID` |
| 3 | `model_schema_version` が未対応 | `MODEL_SCHEMA_MISMATCH` |
| 4 | `sha256` が内容・レジストリの記録と不一致 | `MODEL_HASH_INVALID` |
| 5 | `feature_schema` の hash が内容と不一致/種別が許さない/レジストリの記録と不一致 | `FEATURE_SCHEMA_MISMATCH` |
| 6 | 重み・前処理の長さがスキーマと違う、前処理の値が不正 | `INVALID_MODEL_DIMENSION` |
| 7 | `tenant_id`・`model_id`・`kind` の取り違え | `MODEL_INVALID` |
他の理由コード: `MODEL_NOT_FOUND`（成果物が無い）・`MODEL_NOT_ACTIVE`（ACTIVE 行が無い）・`MODEL_STORAGE_ERROR`（保存先の I/O）・`MODEL_PREDICTION_FAILED`（推論中の例外/非有限）・`ML_DISABLED`・`MARKET_FALLBACK`（最終的に市場確率のみ）。MIGRATION_MATRIX §8 E1 の名称との対応は `FallbackReason` の KDoc。
- 推論に使えるのは `VerifiedModel` だけ（非公開コンストラクタ。検証前の `ModelDocument`・`ModelArtifact` からは推論できない）。拒否の詳細メッセージに重み・署名・鍵を含めない。

### 11.4 特徴量: 旧互換と point-in-time（D-157・D-159）
| 観点 | `LegacyFeatureBuilder`（旧互換。学習に使わない） | `PointInTimeFeatureBuilder`（新方式） |
|---|---|---|
| 過去走（H3） | 日付で絞らない（対象レース自身・未来を含み得る）。直近 5 走（`finish>0`、日付降順 → race_id 降順） | `run_date < レース日`（当日を含まない）。供給元が絞り損ねても二重に除外してフラグ |
| `age_years`（H8） | 引数の `now` 基準（ゴールデンでは固定時刻） | **レース日**基準 |
| `days_since_last_run` | レース日（race_id 先頭 8 桁）− 直近走。負は 0 | レース日 − 直近走（常に正） |
| オッズ | 呼び出し側の値（`≤0` は 100.0） | `ts <= asOf` の最新だけ。無ければ 100.0 と `ODDS_MISSING` |
| 発走後の asOf | 検査なし | `LeakageException`（発走時刻不明も拒否） |
| 馬マスタ（累積値） | そのまま | `updatedAt > asOf` なら着回数の表・脚質傾向を 0 にして `MASTER_NEWER_THAN_AS_OF`（`TRUST_LATEST` で無効化可） |
共通の式（`FeatureMath`）: base 5 = `[odds, popularity, ln(max(odds,0.1)), 1/max(pop,1), odds×pop]`（`odds≤0` は 100.0 に置換してから）、追加 19、血統 16（祖先名の先頭 8 件、`vec[bucket] += sign`、正規化しない・重複もそのまま）。**限界**: 馬マスタは最新版 1 行しか持たないため、学習用データでは多くの馬で累積値が 0 になる（時点ごとのマスタ履歴の保管は未実装 = UNRESOLVED）。旧 `_sum_chaku_table` の癖（要素が 3 未満の行は `top3 += 全行の top2 の累計`）も式として共有している（D-159）。

### 11.5 確率パイプライン: legacy と v2（D-158）
| 観点 | `legacy`（本番の初期値・旧互換） | `v2`（新仕様。**未承認・自動切替なし**） |
|---|---|---|
| モデル内の市場ブレンド | 実行時に α（既定 0.25）を上書きして適用（H4） | なし（入力が内部ブレンド済みなら拒否） |
| 判定側のブレンド | 適用（2 回目） | 適用（これが唯一の 1 回） |
| 市場確率のみ（フォールバック） | 市場確率 → 判定側ブレンド（旧どおり） | 市場確率そのまま（ブレンド・クリップなし） |
| フォールバック | ロードできたモデルのスキーマ不一致/推論失敗は ML 全体を市場確率へ（旧の例外）。無かったモデルは無いものとして続行 | ensemble → calibration → alpha → legacy_compat → 市場確率の段階的。外した段階をすべて記録 |
| 出どころ | 旧 `prob_source` 文字列（`legacyLabel`。Kelly の確率ソース係数 H10 が文字列の一致で決まるため数値を変えない）＋馬ごとの正確な `ProbSource`（E2） | 正確な `ProbSource` |
| alpha の特徴量 | 35 次元スキーマで作る（暗黙のスライス/ゼロ埋めはしない）。旧実装の切詰め（H2）は互換検証専用の `GoldenMl` にだけある | 同じ |
実測（合成モデル・合成レース）: 同じ calibration モデルでも legacy と v2 は市場確率からの L1 距離が 0.5882 と 0.5908、最大絶対差 0.0138（legacy はモデルの寄与が ≈0.0625、v2 は 0.25）。**これは v2 が高精度であることを示さない**（実データ・バックテスト前）。

### 11.6 レジストリ・固定・保存先（D-160）
`ModelRegistryReader.find/active`（読取りのみ。昇格は Stage 4）→ `ModelRunPin.capture`（run の開始時に各種別の ACTIVE を 1 回だけ固定）→ `ModelSession`（固定した ID の成果物を `ModelStore` から読み、レジストリの sha256・feature_schema_hash・kind と照合して `ModelLoader` で検証。結果は run の間不変）。ensemble セットのメンバーはレジストリに記録があり REJECTED でないこと。CANDIDATE は使われず、市場確率に落ちて `MODEL_NOT_ACTIVE` が残る（自動昇格しない）。`:persistence` の `MlModelRegistryRepository` は bot の SELECT のみで足りる（`mltrain` は `ml_model` の SELECT/INSERT のみ）。

### 11.7 学習用データセットの出力契約（D-161。Stage 3-F2 の入力）
- ファイル: `{dataset_id}.csv.gz`（gzip CSV）+ manifest（canonical JSON + 改行 1 つ）。置き場は CONTRACTS §5.1（`MODEL_STORE_URI/{tenant_id}/datasets/`）。manifest の保管場所の拡張（`DatasetStore` への追加）は T3.6-B。
- CSV: UTF-8・LF・ヘッダあり。列 = `tenant_id,race_id,umaban,race_date,as_of` + 特徴量（`feature_schema.names` の順）+ `label`（1 着 = 1、他 = 0。取消・除外・結果なしは出力しない）+ `split`（`train` / `valid`）。浮動小数は Python の `repr`（`pandas.read_csv` でそのまま読める）。行は (レース日, race_id, 馬番) の昇順。
- `split`: Kotlin がレース日の昇順に分ける（先頭の約 80% の**日**が train、残りが valid。同じ日のレースは分けない）。**Python は再分割しない**（walk-forward などは valid の中でだけ行う）。
- `as_of` = 発走時刻 − 10 分（manifest の `as_of_policy.lead_seconds`）。オッズは `as_of` 以前の最新だけ。calibration は `as_of` 以前のオッズが無い行を出力しない（旧 `dataset_builder` と同じ）。
- manifest の主な項目: `dataset_schema_version`・`dataset_id`・`tenant_id`・`feature_schema`（同梱）・`columns`・`csv.{sha256,bytes}`（**非圧縮 CSV** の SHA-256。gzip のバイト列は zlib の版で変わるので正本にしない）・`rows`・`range`・`label`・`as_of_policy`・`split`（件数・`valid_starts_at`）・`builder.{mode:"point_in_time", legacy_compat_used:false}`・`quality.{has_horse_master_ratio, has_past_runs_ratio, constant_features, flags, skipped}`・`sha256`（自己ハッシュ）。出力しなかった行は理由別に `quality.skipped` へ（黙って捨てない）。
- `sample_weight`（Python が計算）: `w_i = 0.5 ** ((ref − race_date_i).days / halflife)`、`ref` = train の最大 `race_date`、`halflife` ∈ {90, 180, 240}（旧 `retrain_models.py` と同じ候補）。計算後に train の平均 1 へ正規化（旧 `fit` と同じ）。
- 検証: `python3 -m tools.ml.dataset_contract --csv-gz X.csv.gz --manifest X.manifest.json`（manifest の自己ハッシュ・`feature_schema.hash` の再計算・列の構成と順序・CSV の SHA-256・行の昇順・数値の有限性・label が 0/1・valid が train より後の日・as_of（JST）がレース日と一致・`legacy_compat_used=false`）。**学習側は検証に失敗したデータセットで学習しない。**

### 11.8 学習ジョブ（`ml-train`、Stage 3-F2）の入出力と品質ゲートの仕様案
入力: `--tenant-id`・`--dataset-id`・学習設定 JSON（`seed`・`lr`・`epochs`・`l2`・`class_weight`（v2 は `none`）・`recency_halflife_days`・`kind`）。DB の業務テーブルには触れない。手順: ①§11.7 の検証 → ②学習（v2 案: 特徴量標準化 + L2 + 決定的なソルバ。`class_weight` なし。valid で Platt 校正。互換モード `legacy_compat` の再学習は旧 GD + 旧ハイパーパラメータで、互換検証にだけ使う）→ ③評価（valid の LogLoss/Brier/AUC/校正傾き・切片、**市場確率ベースライン（1/odds の正規化）**との比較）→ ④モデル JSON を §11.3 の正準形で書く（`training.dataset_sha256` = manifest の `csv.sha256`、`git_sha`、`libs`）→ ⑤Ed25519 署名（`.sig`）→ ⑥`ml_model` に `CANDIDATE`（ゲート不合格は `REJECTED`）を INSERT。成果物は一時パスに書き、sha256 を確認してから確定する（原子性）。
品質ゲート（**暫定案。数値は F2 で実データなしに決めず、ユーザー承認後に確定**）: 最小サンプル数（valid の勝ち馬数）、valid の LogLoss が市場ベースライン以下、校正傾きが [0.8, 1.2]、定数特徴量の割合の上限、`has_horse_master_ratio` の下限（警告）、直前の ACTIVE より悪化していない。不合格でも成果物とレポートは残す。**ACTIVE への昇格はしない**（Stage 4 の管理者操作）。pickle は読まない・出力しない。

### 11.9 旧 pkl → JSON 変換ツールの仕様（T1.20。**実行は利用者のローカル操作。クラウドで pkl を読まない**）
- 入力: 利用者の PC にある**自分の** `*.pkl`（ファイル名・サイズ・更新日は UNRESOLVED D-2 の手順 4 で記録済みのもの）。ネットワークなしの環境で実行。リポジトリ・クラウドに pkl も出力 JSON も置かない。
- 読み方: `pickle.load` を直接使わず、**許可リスト式の `Unpickler`**（`jraev.ml.model.WinProbabilityModel`・numpy の配列の再構成に必要なクラスだけを解決でき、他のグローバルは拒否）で読む。旧 `jraev` を import せず、同名のクラスを空の入れ物として許可リストに渡す。
- 出力: `kind=legacy_compat`・`feature_schema`（`feature_names` から構築: 5 名 → `MARKET_5`、旧 40 名 → `CALIBRATION_40`、35 名 → **`ALPHA_35` に一致すれば alpha**。それ以外は変換を中止）・`weights`・`bias`・`calibration`（`calib_a`/`calib_b` が両方あるときだけ platt）・`market_blend.alpha_default`。**次元不一致（H1/H2 の疑い）は警告して中止**（パディング/スライスで合わせない）。正準 JSON で書き、ローカルの鍵で署名。
- 検証: ランダムな特徴量 N 件を、旧クラス（参照元 f7dbf69）と Kotlin 実装の両方に通して差が許容内か確認（G-ML-2）。旧実装の出力は『旧挙動の記録』で、新仕様の正解ではない。
- 状態: **仕様のみ。実装・実行は未実施**（実 pkl が無いため）。

### 11.10 H1〜H10 の扱い
| # | 旧互換（`legacy`） | 新方式 |
|---|---|---|
| H1 | 再現（feature_names 不一致 → ML 全体を市場確率へ）。ただし理由コードとアラート対象フラグ（`isMarketFallback`）を必ず残す | v2: 段階的フォールバック + 理由 |
| H2 | 互換検証専用の `GoldenMl` が切詰め/ゼロ埋めを再現（G1/G3/G4）。**本番のロードは拒否** | alpha は 35 次元スキーマで作る |
| H3 | `LegacyFeatureBuilder` が再現（G6） | `PointInTimeFeatureBuilder` が構造的に解消 |
| H4 | 再現（二重ブレンド）。未承認のため変えない | v2 は 1 回 |
| H5 | 校正の欠如は学習側の問題。推論は Platt を持つモデルに適用 | F2 で valid の Platt/等張 |
| H8 | 引数の `now` 基準で再現（ゴールデンは固定時刻） | レース日基準 |
| H10 | `legacyLabel` を旧文字列のまま保ち Kelly 係数は 0.8 のまま（数値不変）。正確な `ProbSource` を別に持つ | 正確なラベル。Kelly 係数の enum 化（`kellyMultiplierBySourceEnum`）は未承認 |
H6・H7・H9 は学習側/データ側（F2・T1.20）。

### 11.11 検証結果（T3.6-A）
- Kotlin と旧 Python のゴールデン: 26 ケース / 880 呼び出し中 **25 ケース / 874 呼び出しが全一致、最大絶対誤差 1.066e-14**（G5-BLEND-002、許容 1e-9）。未対応は `GD10-DEFAULTS-001`（旧関数の既定値。T1.20）だけ。G6 の特徴量は誤差 0（許容 1e-12）。
- 新規 5 ケース: `G6-FEATURES-001`（特徴量）・`G3-ML-NORM-002`（単体モデル → 正規化・補正）・`G4-ENSEMBLE-002`・`G5-BLEND-002`・`G7-LEGACY-FAIL-001`（旧実装側の失敗時の挙動）。G1（`model_extract_features` の 7 呼び出しを更新: D-153）と G2 は Kotlin で比較。
- 新しいロード契約の失敗系（署名/ハッシュ/スキーマ/次元/NaN）は旧実装に対応物が無い新設計の仕様なので、ゴールデン（旧出力）ではなく Kotlin 単体テスト（`ModelLoaderTest`・`MlEndToEndTest`）と `tools/ml` の署名つき合成成果物（決定的に再生成可能）で検証した。

## 12. T3.6-B の実装結果（ml-train。2026-10-24。判断 D-165〜D-179）
**合成データのみで検証。実データでの学習・精度・品質閾値の妥当性は未検証。ML は本番利用可能ではない。**
- **構成**: Kotlin `bot --task=export-dataset`（`DatasetExporter` → `DatasetPublisher`: gzip CSV → manifest〔確定の印〕）→ `DatasetStore` → Python `ml-train train`（`ml/src/ml_train`）→ `ModelStore`（`.sig` → `.json`〔確定の印〕）→ `ml_model` に CANDIDATE / REJECTED で登録（`mltrain` DB ユーザーは `ml_model` の SELECT/INSERT のみ。ACTIVE への昇格・既存行の更新/削除・他テナントの操作は構造的にできない）。
- **学習（D-165）**: 標準化 + L2 ロジスティック回帰（lbfgs。決定的）+ Platt 校正 + 直近重み（半減期 90/180/240 日を平均 1 に正規化）。`class_weight` は使わない（H5）。**評価の分割（D-167）**: train で学習 → valid の日付順前半で Platt → 後半で指標・ゲート。
- **品質ゲート（D-168）**: 既定値を持たない。設定が無い／check が 0 個／市場ベースラインが無いのに比較を要求 → REJECTED（fail closed）。市場ベースラインを捏造しない。数値閾値は未承認（UNRESOLVED D-13）。テストは明示したテスト用閾値のみ。
- **モデル JSON（D-169）**: §11.3 の契約（正準 JSON・sha256・Ed25519〔JSON のバイト列に対する署名。`.sig` は raw 64 バイト〕）。Python の検証は Kotlin `ModelLoader` と同じ順序・理由コード。`model_id` は決定的（再実行は冪等）。REJECTED の登録は exit 0（D-170）。
- **点時点安全（D-166）**: SQL 自体が時間を制限（過去走 `run_date < レース日`、オッズ `observed_at <= asOf`、馬マスタ履歴 `known_at <= asOf`）。1 着の結果行が無いレースは出力しない（`RESULT_INCOMPLETE`）。
- **旧 pkl 変換（D-175）**: 許可リスト式 Unpickler（任意の pickle を安全にするものではない）+ opcode 走査 + サイズ上限。次元不一致は中止。実 pkl の変換は利用者のローカルのみ・未実施。
- **保管（D-171・D-172）**: ml-train は `file://`（GCS ボリュームのマウント）のみ。GCS アダプタは Kotlin 側（偽クライアントで検証。実 GCS は未接続）。
- **検証**: pytest 128 件・ruff・mypy strict、`MlTrainInteropIntegrationTest`（Kotlin 出力 → Python 学習 → Kotlin `ModelLoader` 検証）。ローカルは JDK 21・MySQL 8.0.46 であり、JDK 25 / MySQL 8.4・9.7 の合否ではない（CI が正: D-056）。


## 13. T3.7: 通知の理由文（G-17 ⑥）
`format_reason_from_features` は `jp.jrabot.domain.ml.features.ReasonFormatter` に移植。材料 `HorseReasonFacts` は 2 通りで作る: ①`LegacyFeatureBuilder.reasonFacts`（旧互換。H3 を含み、ゴールデン G8-REASON-006 の比較専用）②`PointInTimeFeatureBuilder.reasonFacts`（asOf 以前だけ。`LeakageException` の検査は `build` と同じ。累積値〔適性・脚質〕が asOf より後に更新されていたら出さない＝0% などの架空の値を出さない）。馬名は呼び出し側（出走表）が渡す。旧の適性率が 100% を超え得る癖（D-159）は共有の式のまま表示に出る（G8-REASON-006 の『距離=147%』）。実データの通知は COMP-4 まで送らない。

## 14. T3.8: bot-run への ML の接続（2026-10-27。判断 D-199。合成データのみ）
- **固定**: bot-run の開始時（リース取得後・RUNNING の前）に 1 回だけ `ModelRunPin.capture` → `ModelSession`（保管先から読み、**署名・sha256・feature_schema_hash・次元をレジストリの記録と照合**）→ `MlInference`（`MlRunStarter`）。run の途中でレジストリの ACTIVE が替わっても固定したモデルを使う。`BOT_RUN_ML_MODEL_TYPE=off` なら読み込まない。パイプラインは **legacy 固定**（v2 は設定項目すら無い）。
- **判定への配線**: `RaceEvaluator` が point-in-time の特徴量（`PointInTimeFeatureBuilder`。`asOf = 評価時刻`、発走時刻以降は `LeakageException` → `MODEL_PREDICTION_FAILED` のフォールバック。過去走はレース日より前だけ、馬マスタは `horseAsOf`）で `MlInference.probabilities` を呼び、`ProbabilityResult.probs` を `MlProbabilityInput` として `WinDecisionEvaluator` に渡す。確率源のラベルは legacy の旧ラベル（H10）。EV・Kelly・賭け額・買い目・丸めは変更なし（ゴールデン 31 ケース / 988 呼び出し一致を維持）。
- **記録**: `race_evaluations.model_type_used`（`calibration` など、ML が使えなかったら `market`）、`model_id`（固定した ID。市場確率のみなら NULL）、`fallback_reason`（`FallbackReason` の名前。ML 無効は `ML_DISABLED`）。
- **フォールバックの検知**: ML を有効にしたのに市場確率だけになったら（H1 のサイレントフォールバックの再発防止）、運用者へ run あたり 1 回エラー通知する（理由つき）。署名不正・成果物なし・ACTIVE なし（CANDIDATE のみ）・レジストリ/保管先の障害・特徴量の元データ欠損をテストで確認。
- **しないこと（未承認）**: モデルの ACTIVE への昇格、品質閾値の設定、v2 の有効化、実 pkl の変換、実データでの学習。**合成テストの成功は予測精度の証明ではない**（F-5・G-ML-1〜3 は実データで）。
- **履歴の追記（G-26）**: `horse_master_history` は取込側がいつ版を足すか未実装。履歴が貯まるまで過去分の PIT は不完全。

