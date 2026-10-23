package jp.jrabot.domain.odds

import jp.jrabot.domain.race.HorseNumber
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant

/** 市場。参照元の `market` 列の値（`win` / `wide` / `sanrenpuku`）に対応する。 */
enum class Market(
    val code: String,
) {
    WIN("win"),
    WIDE("wide"),
    SANRENPUKU("sanrenpuku"),
    ;

    companion object {
        fun fromCodeOrNull(code: String): Market? = entries.firstOrNull { it.code == code }
    }
}

/**
 * 1 頭分のオッズ。[popularity] は人気順位。不明は null。参照元に `popularity == 0` のデータがある（GD09）ので 0 も値として保持する
 * （意味づけ・補正はしない）。オッズが無い馬（`0` は「データ無し」）はエントリに含めない。
 */
data class OddsEntry(
    val umaban: HorseNumber,
    val odds: Odds,
    val popularity: Int? = null,
) {
    init {
        require(popularity == null || popularity >= 0) { "人気順位は 0 以上: $popularity" }
    }
}

/**
 * ある時刻に観測した、レース 1 つ・市場 1 つ分のオッズ（`odds_snapshots` の自然キー `(tenant_id, race_id, observed_at, market, umaban)` の束）。
 * [observedAt] は UTC の瞬間（マイクロ秒より細かい精度は保存されない）。馬番の重複は不可。[entries] は読み取り専用として扱う。
 */
data class OddsSnapshot(
    override val tenantId: TenantId,
    val raceId: RaceId,
    val market: Market,
    val observedAt: Instant,
    val entries: List<OddsEntry>,
) : TenantScoped {
    init {
        require(entries.isNotEmpty()) { "エントリが空のスナップショットは作れない" }
        require(entries.map { it.umaban }.toSet().size == entries.size) { "馬番が重複している" }
    }
}
