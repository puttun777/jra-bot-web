package jp.jrabot.domain.ml.pipeline

/**
 * 確率が市場確率に落ちた/モデルを外した理由（`race_evaluations.fallback_reason VARCHAR(64)`・構造化ログ・アラートに記録する）。
 * 旧実装は ML の例外を握り潰して理由なしで市場確率にしていた（H1 / E1）。**理由なしのフォールバックは作れない**。
 *
 * MIGRATION_MATRIX §8 E1 の理由名との対応: `MODEL_NOT_ACTIVE`=同名、`FEATURE_SCHEMA_MISMATCH`=同名、`SIGNATURE_INVALID`=[MODEL_SIGNATURE_INVALID]、
 * `LOAD_ERROR`=[MODEL_NOT_FOUND]・[MODEL_SCHEMA_MISMATCH]・[MODEL_HASH_INVALID]・[INVALID_MODEL_DIMENSION]・[MODEL_INVALID]、
 * `INFERENCE_ERROR`=[MODEL_PREDICTION_FAILED]。
 */
enum class FallbackReason(
    /** `legacy` パイプラインで、これが 1 つでも起きたら ML 全体を市場確率に落とすか（旧実装が例外にしていたもの）。false は『そのモデルは無かった』扱いで、残りで続ける。 */
    val abortsLegacyPipeline: Boolean,
) {
    /** ML 自体が無効の設定（`ml_cfg.enabled=false`）。 */
    ML_DISABLED(false),

    /** 指定された種別のモデルが取得できない（レジストリに無い・成果物が無い）。 */
    MODEL_NOT_FOUND(false),

    /** レジストリに ACTIVE の行が無い（候補のまま・退役済み）。 */
    MODEL_NOT_ACTIVE(false),

    /** `model_schema_version` が未対応。 */
    MODEL_SCHEMA_MISMATCH(false),

    /** 特徴量スキーマのハッシュが、このランタイムが作れるスキーマ/期待と一致しない。旧実装は `ValueError` で ML 全体を落とした。 */
    FEATURE_SCHEMA_MISMATCH(true),

    /** 署名が無い・不正（改ざんの疑い）。 */
    MODEL_SIGNATURE_INVALID(false),

    /** `sha256` が内容と一致しない（またはレジストリの記録と違う）。 */
    MODEL_HASH_INVALID(false),

    /** 重みの長さが特徴量スキーマと違う、NaN/Infinity を含む、など。パディング/スライスで合わせない。 */
    INVALID_MODEL_DIMENSION(false),

    /** 上記以外の形式不正（JSON が壊れている・必須項目がない・種別が不正）。 */
    MODEL_INVALID(false),

    /** 成果物の保存先の I/O に失敗した（到達不能・権限・タイムアウト）。一時的な障害の可能性がある（「無い」とは区別する）。 */
    MODEL_STORAGE_ERROR(false),

    /** 推論中に例外・非有限値が出た。旧実装は例外を握り潰して市場確率にした。 */
    MODEL_PREDICTION_FAILED(true),

    /** 最終的に市場確率だけを使った（上のいずれかの理由と一緒に記録する）。 */
    MARKET_FALLBACK(false),
}

/** 確率の出どころ。**実際は市場確率なのに ML 予測として表示しない**（旧 H10 の是正）。 */
enum class ProbSource {
    ML_LEGACY,
    ML_CALIBRATION,
    ML_ALPHA,
    ML_ENSEMBLE_CALIB_ALPHA,
    ML_ENSEMBLE_CALIB_ONLY,
    ML_ENSEMBLE_ALPHA_ONLY,
    MARKET_FALLBACK,

    /** 馬ごとに出どころが違う（レース単位の表示）。 */
    MIXED,
}

/** 設定で指定する推論の種別（旧 `model_type`）。`auto` は無い（レジストリの ACTIVE で決める）。 */
enum class ProbabilityModelType { LEGACY, CALIBRATION, ALPHA, ENSEMBLE }

/**
 * パイプラインの版。**本番の既定は [LEGACY]**。[V2] は新仕様（単回ブレンド・段階的フォールバック・正確なラベル）の独立実装だが、
 * **高精度とは未証明で、旧新比較・バックテスト・閾値の再検討とユーザー承認の後でなければ有効にしない。自動で切り替えない**（D-037、MIGRATION_MATRIX §8）。
 */
enum class ProbabilityPipelineVersion { LEGACY, V2 }

/** パイプラインの設定（解決済みの値）。 */
data class PipelineConfig(
    /** 市場ブレンド α（旧: 引数 > `ml.market_blend_alpha` > 0.25 を呼び出し側で解決）。 */
    val marketBlendAlpha: Double,
    /** ensemble の β（旧: 数値設定。`auto` は無く、ensemble セットの成果物が持つ値を渡す）。[0, 1] に収める。 */
    val ensembleBeta: Double,
    val enableProbClip: Boolean,
    /** 単勝確率の上限クリップ（null または 0 以下は無効）。 */
    val probClipMax: Double?,
) {
    init {
        require(marketBlendAlpha.isFinite() && ensembleBeta.isFinite()) { "α・β は有限な数" }
        if (probClipMax != null) require(probClipMax.isFinite()) { "probClipMax は有限な数" }
    }
}

/** 1 つのモデルの出力。 */
sealed interface RoleOutput {
    /**
     * 馬ごとの確率（モデル内の後処理まで）。[internalBlendAlpha] はモデル内で市場ブレンドを適用した α（適用していなければ null）。
     * - `legacy` パイプライン: 旧実装は実行時に各モデルへ α を上書きして内部ブレンドする（二重ブレンド H4）ので、その α。
     * - `v2` パイプライン: **null でなければならない**（二重ブレンドを入力で検出して拒否する）。
     */
    data class Scored(
        val probabilities: List<Double>,
        val internalBlendAlpha: Double?,
    ) : RoleOutput

    /** 使えない。[reason] と [detail]（秘密を含めない説明）を記録する。 */
    data class Unavailable(
        val reason: FallbackReason,
        val detail: String? = null,
    ) : RoleOutput {
        init {
            require(reason != FallbackReason.MARKET_FALLBACK && reason != FallbackReason.ML_DISABLED) { "モデル単位の理由として不適切: $reason" }
        }
    }
}

/** 各モデルの出力（null は『要求していない』）。 */
data class RawOutputs(
    val legacy: RoleOutput? = null,
    val calibration: RoleOutput? = null,
    val alpha: RoleOutput? = null,
)

/** フォールバックの記録（どの段階をどの理由で外したか）。 */
data class FallbackStep(
    val stage: String,
    val reason: FallbackReason,
    val detail: String? = null,
)

/**
 * 確率の結果。
 * - [probs]: 判定に使う最終確率（ブレンド・クリップ・再正規化後。合計 1）。
 * - [preBlend]: 判定側ブレンドの前（レース内正規化後）。
 * - [source]: レース単位の出どころ。馬ごとは [horseSources]。
 * - [legacyLabel]: 旧実装の `prob_source` 文字列（**Kelly の確率ソース係数 H10 が文字列の完全一致で決まるため、legacy では旧ラベルをそのまま保つ**）。
 *   v2 では正確なラベル（[source] の名前）。表示・集計には [source] と [fallbackReason] を使う。
 * - [fallbackReason]: ML を要求したのに外した（または一部を外した）最初の理由。ML を使えていれば null。ML 無効（[FallbackReason.ML_DISABLED]）も記録する。
 * - [steps]: 外した段階の全記録。
 * - [coverageScores] / [betaEffs]: 馬ごとの ensemble のカバレッジ（ensemble 以外は 1.0 / 設定の β）。旧 `coverage_score` / `beta_eff`。
 */
data class ProbabilityResult(
    val probs: List<Double>,
    val preBlend: List<Double>,
    val source: ProbSource,
    val horseSources: List<ProbSource>,
    val legacyLabel: String,
    val fallbackReason: FallbackReason?,
    val steps: List<FallbackStep>,
    val coverageScores: List<Double>,
    val betaEffs: List<Double>,
    val version: ProbabilityPipelineVersion,
) {
    /** ML を要求したのに市場確率だけになったか（アラート対象）。 */
    val isMarketFallback: Boolean get() = source == ProbSource.MARKET_FALLBACK
}
