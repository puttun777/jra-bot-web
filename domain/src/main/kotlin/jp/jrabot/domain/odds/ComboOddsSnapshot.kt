package jp.jrabot.domain.odds

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.UpsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant

/**
 * 組み合わせ（ワイド・三連複）のキーの正規化。**馬番を昇順・2 桁ゼロ埋め**にして `-` で結ぶ（例: `03-11`、`01-05-12`）。
 * 逆順や重複した馬番は別の買い目にならないよう、正規化できなければ null（補正しない）。
 */
object ComboKeys {
    fun wide(
        a: Int,
        b: Int,
    ): String? = join(listOf(a, b))

    fun sanrenpuku(
        a: Int,
        b: Int,
        c: Int,
    ): String? = join(listOf(a, b, c))

    private fun join(numbers: List<Int>): String? {
        if (numbers.any { it !in 1..99 } || numbers.toSet().size != numbers.size) return null
        return numbers.sorted().joinToString("-") { "%02d".format(it) }
    }
}

/**
 * 組み合わせオッズ 1 行。[oddsHigh] はワイドの上限（ワイドは範囲で公表される。三連複は null）。[odds] はワイドでは**下限**
 * （旧実装の EV 計算が低い方を使う: ComboOdds）。0 より大きい有限値だけ。
 */
data class ComboOddsRow(
    val comboKey: String,
    val odds: Odds,
    val oddsHigh: Odds? = null,
    val popularity: Int? = null,
) {
    init {
        require(oddsHigh == null || oddsHigh.value >= odds.value) { "ワイドの上限は下限以上" }
        require(popularity == null || popularity >= 0) { "人気順位は 0 以上" }
    }
}

/** `combo_odds_snapshots` の自然キー `(tenant, race, market, collectedAt, comboKey)` の束。市場はワイド・三連複だけ。 */
data class ComboOddsSnapshot(
    override val tenantId: TenantId,
    val raceId: RaceId,
    val market: Market,
    val collectedAt: Instant,
    val rows: List<ComboOddsRow>,
    val source: String? = null,
) : TenantScoped {
    init {
        require(market == Market.WIDE || market == Market.SANRENPUKU) { "組み合わせ市場はワイド・三連複だけ: $market" }
        require(rows.isNotEmpty()) { "行が空のスナップショットは作れない" }
        require(rows.map { it.comboKey }.toSet().size == rows.size) { "組み合わせが重複している" }
    }
}

interface ComboOddsSnapshotWriter {
    /** 自然キーの upsert。冪等（同じ内容の再送は [UpsertOutcome.UNCHANGED]）。 */
    fun upsert(snapshot: ComboOddsSnapshot): UpsertOutcome
}

interface ComboOddsSnapshotReader {
    fun history(
        tenantId: TenantId,
        raceId: RaceId,
        market: Market,
    ): List<ComboOddsSnapshot>
}

interface ComboOddsSnapshotRepository :
    ComboOddsSnapshotReader,
    ComboOddsSnapshotWriter
