package jp.jrabot.domain.ops

/**
 * 運用モード（CONTRACTS §5.2・§5.3）。
 *
 * [NORMAL]・[STARTING]・[STOPPING]・[STOPPED] は正本（GCS の `ops-state.json`）に存在する値。
 * [UNKNOWN] は正本には存在せず、アプリ内部で「状態を確認できない」ときだけの値（取得失敗・タイムアウト・JSON 不正・検証 NG・未知のスキーマ版など）。
 *
 * **fail closed**: 処理を始めてよいのは [NORMAL] だけ。[UNKNOWN] を [NORMAL] とみなす経路を作らない。
 */
enum class OpsMode {
    NORMAL,
    STARTING,
    STOPPING,
    STOPPED,
    UNKNOWN,
    ;

    /** 業務処理（取込・評価・通知・管理操作の書込）を始めてよいか。[NORMAL] だけ true。 */
    val allowsWork: Boolean get() = this == NORMAL

    /** 正本から読めた確定値か（[UNKNOWN] だけ false）。Job は確定した非 NORMAL なら exit 0、[UNKNOWN] なら exit 75（CONTRACTS §9）。 */
    val isConfirmed: Boolean get() = this != UNKNOWN

    companion object {
        private val sourceValues: Map<String, OpsMode> = listOf(NORMAL, STARTING, STOPPING, STOPPED).associateBy { it.name }

        /**
         * 正本の `mode` の文字列を変換する。4 つの値（大文字・完全一致）以外は null、`"UNKNOWN"` も null
         * （UNKNOWN は正本の値ではない）。null のとき呼び出し側は [UNKNOWN] として扱う。
         */
        fun fromSourceValueOrNull(raw: String?): OpsMode? = raw?.let { sourceValues[it] }

        /** 正本の文字列を変換し、読めなければ [UNKNOWN]。 */
        fun fromSourceValue(raw: String?): OpsMode = fromSourceValueOrNull(raw) ?: UNKNOWN
    }
}
