package jp.jrabot.domain.botrun

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonFormatException
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.race.RaceId
import java.time.Instant

/**
 * レース 1 つの監視の段階（RACE_CALENDAR_DESIGN §6.3 の checkpoint）。**DB の `race_schedule` と現在時刻から毎サイクル再導出する**ので、
 * checkpoint の段階は『ヒント』（再開時の突合・報告用）で、メモリや checkpoint に依存して判断を変えない（参照元の `result_fetched` 問題の解消）。
 *
 * T3.8 の範囲は発走まで。結果・払戻・決済の段階（RESULT_PARTIAL / SETTLED）は T3.9。
 */
enum class RaceStage {
    /** 監視の開始前（最初の発走の watchStart 前）。 */
    PENDING,

    /** 監視中（発走前でオッズを評価する）。 */
    MONITORING,

    /** 発走時刻を過ぎた。監視は終わった（結果の反映は T3.9）。 */
    OFF,

    /** 中止（元の予定または手動）。 */
    CANCELLED,

    /** 発走時刻が不明。**評価も通知もしない**（時刻を補完しない）。 */
    UNKNOWN_TIME,

    /** 確認が古い場など、信頼できない予定のため監視の対象にしない。 */
    UNTRUSTED,

    /** 発走後。着順がまだ無い（結果待ち。T3.9）。DB から毎サイクル導出するヒント。 */
    RESULT_PENDING,

    /** 着順はあるが、単勝の確定払戻がまだ無い（払戻待ち）。 */
    RESULT_PARTIAL,

    /** 着順と単勝の確定払戻がそろい、成績を計算できる。 */
    SETTLED,
}

/**
 * レース 1 つ分の checkpoint。[firedMinutes] は評価・通知を**発火済み**のタイミング（[SnapshotPolicy.minutesBeforePost] の値。再起動しても同じタイミングで
 * 二度通知しない）、[lastObservedAt] は最後に評価した単勝オッズの観測時刻（診断用）。
 */
data class RaceCheckpoint(
    val stage: RaceStage,
    val lastObservedAt: Instant? = null,
    val firedMinutes: Set<Double> = emptySet(),
)

/**
 * `bot_run.checkpoint`（JSON オブジェクト）の中身。[cycle] はこの run の評価サイクルの通し番号、[lastCycleAt] は最後にサイクルを回した時刻。
 * 再開（INTERRUPTED・リース奪取）では前の run の checkpoint を [parse] で読む。壊れていたら null（再開のヒントを使わず、DB から導出する）。
 */
data class RunCheckpoint(
    val cycle: Long,
    val lastCycleAt: Instant?,
    val races: Map<RaceId, RaceCheckpoint>,
    /**
     * この checkpoint を書いた run の通知の出どころ（[jp.jrabot.domain.notify.NotificationOrigin] の名前）。null は記録が無い旧い checkpoint。
     * LIVE 以外（BACKFILL など）の run の『発火済み』は実際には通知していないので、LIVE の run は引き継がない（レビュー M1）。
     */
    val origin: String? = null,
    /** 全レースが発走した後の『結果待ち』に入っている（再起動した run が、評価の判定をやり直さず結果待ちを続けるため）。 */
    val settling: Boolean = false,
) {
    init {
        require(cycle >= 0) { "cycle は 0 以上" }
    }

    fun toJson(): String {
        val raceObjects =
            JsonObject(
                races.entries.sortedBy { it.key }.associate { (id, c) ->
                    val fields = linkedMapOf<String, JsonValue>("stage" to JsonString(c.stage.name))
                    c.lastObservedAt?.let { fields["last_observed_at"] = JsonString(it.toString()) }
                    if (c.firedMinutes.isNotEmpty()) fields["fired"] = JsonArray(c.firedMinutes.sorted().map { JsonFloat(it) })
                    id.value to JsonObject(fields)
                },
            )
        val fields = linkedMapOf<String, JsonValue>("v" to JsonInt(VERSION.toLong()), "cycle" to JsonInt(cycle), "races" to raceObjects)
        lastCycleAt?.let { fields["last_cycle_at"] = JsonString(it.toString()) }
        origin?.let { fields["origin"] = JsonString(it) }
        if (settling) fields["settling"] = JsonBool(true)
        return CanonicalJson.encode(JsonObject(fields))
    }

    companion object {
        const val VERSION = 1

        val EMPTY = RunCheckpoint(0, null, emptyMap())

        /** 読めない・版が違う・形が違うものは null（例外にしない: 再開のヒントが壊れていても run を止めない）。 */
        fun parse(text: String?): RunCheckpoint? {
            if (text.isNullOrBlank()) return null
            return try {
                val root = StrictJson.parse(text) as? JsonObject ?: return null
                if ((root["v"] as? JsonInt)?.value != VERSION.toLong()) return null
                val cycle = (root["cycle"] as? JsonInt)?.value ?: return null
                val last = (root["last_cycle_at"] as? JsonString)?.value?.let { Instant.parse(it) }
                val races = LinkedHashMap<RaceId, RaceCheckpoint>()
                for ((key, v) in (root["races"] as? JsonObject ?: return null).fields) {
                    val id = RaceId.parseOrNull(key) ?: return null
                    val o = v as? JsonObject ?: return null
                    val stage = RaceStage.entries.firstOrNull { it.name == (o["stage"] as? JsonString)?.value } ?: return null
                    val fired =
                        (o["fired"] as? JsonArray)
                            ?.items
                            ?.map {
                                when (it) {
                                    is JsonFloat -> it.value
                                    is JsonInt -> it.value.toDouble()
                                    else -> return null
                                }
                            }?.toSet() ?: emptySet()
                    races[id] = RaceCheckpoint(stage, (o["last_observed_at"] as? JsonString)?.value?.let { Instant.parse(it) }, fired)
                }
                RunCheckpoint(cycle, last, races, (root["origin"] as? JsonString)?.value, (root["settling"] as? JsonBool)?.value == true)
            } catch (_: JsonFormatException) {
                null
            } catch (_: RuntimeException) {
                null
            }
        }
    }
}
