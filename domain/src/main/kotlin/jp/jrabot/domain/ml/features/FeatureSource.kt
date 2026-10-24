package jp.jrabot.domain.ml.features

import jp.jrabot.domain.tenant.TenantId
import java.time.Instant
import java.time.LocalDate

/**
 * 特徴量の入力（読取りポート）。`:domain` は Doma・MySQL に依存しないので、`:persistence` が実装し、テストと合成データは [InMemoryFeatureSource] を使う。
 * すべてテナントを引数に取る（他テナントの馬・過去走を読ませない: D-139）。
 */
interface FeatureSource {
    /** レース条件。無ければ null（旧実装は 0 / 空文字の条件になる）。[raceId] は 12 桁。 */
    fun raceConditions(
        tenantId: TenantId,
        raceId: String,
    ): RaceConditions?

    /** 出走馬（馬番 → 血統登録番号）。無ければ null。 */
    fun entry(
        tenantId: TenantId,
        raceId: String,
        umaban: Int,
    ): EntryHorse?

    fun horse(
        tenantId: TenantId,
        kettoNum: String,
    ): HorseMaster?

    /**
     * [asOf] 時点の馬マスタ（累積値は [asOf] 時点で判明していた版。UNRESOLVED G-17 ⑤・D-166）。**新方式（point-in-time）はこちらだけを使う**。
     * 既定は最新の 1 行（[horse]）で、履歴を持たない合成の供給元向け。履歴を持つ供給元（DB）は [HorseMasterAtTime.resolve] の規則で返す
     * （時点を特定できない累積値は空にして、現在値を過去の値として使わない）。
     */
    fun horseAsOf(
        tenantId: TenantId,
        kettoNum: String,
        asOf: Instant,
    ): HorseMaster? = horse(tenantId, kettoNum)

    /**
     * 過去走（着順が 1 以上のものだけ）を **日付の降順 → race_id の降順**で最大 [limit] 件。
     * [before] が null でなければ `run_date < before`（**その日を含まない**）のものだけ。日付の無い行は [before] 指定時は返さない。
     * [before] = null は旧互換モード（上限なし。H3 のラベルリークを含み得る）専用。
     */
    fun pastRuns(
        tenantId: TenantId,
        kettoNum: String,
        before: LocalDate?,
        limit: Int,
    ): List<PastRun>
}

/** レース条件。コードは前後の空白を除いた文字列で、無ければ空文字（旧実装と同じ）。[postTime] は発走時刻（point-in-time の検査用。旧互換では使わない）。 */
data class RaceConditions(
    val raceDate: LocalDate?,
    val postTime: Instant?,
    val distanceM: Int,
    val trackTypeCd: String,
    val courseKubunCd: String,
    val tenkoCd: String,
    val shibaBabaCd: String,
    val dirtBabaCd: String,
) {
    companion object {
        val EMPTY = RaceConditions(null, null, 0, "", "", "", "", "")
    }
}

data class EntryHorse(
    val kettoNum: String,
)

/**
 * 着回数の表（UM の距離別・馬場別・状態別。旧 `chaku_by_*_json`）。各行は最大 6 個の整数で、空の行は含めない。
 * 3 着内率 = `top3 / total`（旧 `_sum_chaku_table` のとおり、先頭 3 列を「1〜3 着」とみなす）。
 */
class ChakuTable(
    rows: List<List<Long>>,
) {
    val rows: List<List<Long>> = rows.map { it.take(6).toList() }.filter { it.isNotEmpty() }

    /**
     * 旧 `(top3 / total) if total > 0 else 0.0`。**旧 `_sum_chaku_table` の癖をそのまま再現する**: 行の要素が 3 未満のとき、
     * その行の `top3` に足すのは『その行の top2』ではなく**ここまでの全行の top2 の累計**（`top3 += top2`）。このため率が 1 を超えることがある
     * （ゴールデン G6 の合成データで確認）。新方式でも式は共有する（学習・推論の一貫性が優先で、式の修正は未承認の数値変更）。
     */
    fun placeRate(): Double {
        var total = 0L
        var top2 = 0L
        var top3 = 0L
        for (vals in rows) {
            total += vals.sum()
            top2 += if (vals.size > 1) vals[0] + vals[1] else vals[0]
            top3 += if (vals.size > 2) vals[0] + vals[1] + vals[2] else top2
        }
        return if (total > 0) top3.toDouble() / total.toDouble() else 0.0
    }

    companion object {
        val EMPTY = ChakuTable(emptyList())
    }
}

/**
 * 馬マスタ（UM）。[updatedAt] はこの行が最後に更新された時刻。**着回数の表と脚質傾向は累積値なので、[updatedAt] がレースより後だと
 * そのレースの結果を含み得る**（point-in-time で扱う: [PointInTimeFeatureBuilder]）。血統・生年月日は不変とみなす。
 */
data class HorseMaster(
    val kettoNum: String,
    val birthDate: LocalDate?,
    val styleHint: String,
    val distanceTable: ChakuTable,
    val surfaceTable: ChakuTable,
    val conditionTable: ChakuTable,
    /** 祖先名（空でないものを先頭から。最大 [PedigreeHash.MAX_NAMES] 件まで使う）。 */
    val pedigreeNames: List<String>,
    val updatedAt: Instant,
)

data class PastRun(
    val raceId: String,
    val runDate: LocalDate?,
    val finish: Int,
)

/** 馬マスタの累積値の 1 つの版（`horse_master_history` の 1 行）。[knownAt] はこの内容が判明していたと言える最も早い時刻（観測の時刻）。 */
data class HorseCumulative(
    val knownAt: Instant,
    val styleHint: String,
    val distanceTable: ChakuTable,
    val surfaceTable: ChakuTable,
    val conditionTable: ChakuTable,
)

/**
 * 馬マスタの時点の解決（累積値だけが対象。血統・生年月日は不変とみなす）。**履歴が無い時点の値を推測せず、現在値で補わない**:
 * 1. 現在の行の最終更新が [asOf] 以前なら、それ以降に変更が無いので現在の行が [asOf] 時点の状態。
 * 2. [history]（`known_at <= asOf` の最新の版。呼び出し側が時刻で絞る）があれば、その累積値。1 と 2 が両方あれば新しい方（同時刻は現在の行）。
 * 3. どちらも無ければ累積値を空にする（`updatedAt` は現在の行のまま = [asOf] より後なので、[PointInTimeFeatureBuilder] が
 *    [FeatureFlag.MASTER_NEWER_THAN_AS_OF] を付ける）。馬マスタが無ければ null。
 */
object HorseMasterAtTime {
    fun resolve(
        current: HorseMaster?,
        history: HorseCumulative?,
        asOf: Instant,
    ): HorseMaster? {
        if (current == null) return null
        val usableHistory = history?.takeIf { !it.knownAt.isAfter(asOf) }
        val currentValid = !current.updatedAt.isAfter(asOf)
        if (currentValid && (usableHistory == null || !usableHistory.knownAt.isAfter(current.updatedAt))) return current
        if (usableHistory != null) {
            return current.copy(
                styleHint = usableHistory.styleHint,
                distanceTable = usableHistory.distanceTable,
                surfaceTable = usableHistory.surfaceTable,
                conditionTable = usableHistory.conditionTable,
                updatedAt = usableHistory.knownAt,
            )
        }
        return current.copy(styleHint = "", distanceTable = ChakuTable.EMPTY, surfaceTable = ChakuTable.EMPTY, conditionTable = ChakuTable.EMPTY)
    }
}
