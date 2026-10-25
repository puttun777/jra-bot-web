package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant

/**
 * 馬マスタの累積値の 1 つの版（`horse_master_history` の 1 行の書込み側）。JSON 列は文字列のまま（取込側が持つ JSON をそのまま保管する）。
 * [knownAt] は『この内容が判明していたと言える最も早い時刻』= **観測の時刻**（取込時刻など）で、データ自身の日付ではない。
 * 時点を特定できない版は追記しない（推測で過去の時刻を付けない）。
 */
data class HorseMasterVersion(
    val knownAt: Instant,
    /** 記録の出どころ（`ingest` など。`[a-z0-9_-]{1,32}`）。 */
    val source: String,
    val styleTendencyJson: String?,
    val chakuBySurfaceJson: String?,
    val chakuByConditionJson: String?,
    val chakuByDistanceJson: String?,
    val raceCount: Long?,
) {
    init {
        require(Regex("[a-z0-9_-]{1,32}").matches(source)) { "source の形式が不正" }
        require(raceCount == null || raceCount >= 0) { "race_count は 0 以上" }
    }
}

enum class HistoryAppendOutcome {
    CREATED,

    /** 同じ (馬, known_at) で内容も同じ版が既にある（再取込は冪等）。 */
    UNCHANGED,

    /** 同じ (馬, known_at) で**内容が異なる**版が既にある。履歴は追記専用なので上書きしない。 */
    CONFLICT,
}

/** 馬マスタ履歴の追記と読取り（追記専用: 更新・削除はしない）。すべてテナントが必須。 */
interface HorseMasterHistoryStore {
    /** 対応する馬マスタ（`horses`）の行が無ければ失敗する（FK）。 */
    fun append(
        tenantId: TenantId,
        kettoNum: String,
        version: HorseMasterVersion,
    ): HistoryAppendOutcome

    /** 版を known_at の昇順で。 */
    fun versions(
        tenantId: TenantId,
        kettoNum: String,
    ): List<HorseMasterVersion>
}
