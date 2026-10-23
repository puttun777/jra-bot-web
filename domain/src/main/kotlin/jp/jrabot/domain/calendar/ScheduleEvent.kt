package jp.jrabot.domain.calendar

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate

/** イベントが `race_id` を持つべきか。種類と対象の食い違い（日単位の種類にレースを付ける等）を拒否するための規則。 */
enum class RaceIdRule { REQUIRED, FORBIDDEN, OPTIONAL }

/**
 * `race_schedule_event.event_type`（V2 の ENUM と同じ 15 種）。**実際に起きたことだけ**を記録する（成功の事実を先回りして書かない）。
 *
 * T3.3 が生成する種類: ADDED・DELETED・CANCELLED・TIME_CHANGE・REVOKED（レース予定の更新）、DAY_STATUS・POSTPONED（開催日の状態）、
 * MANUAL_OVERRIDE・MANUAL_REVOKE（手動補正）、STALE・FETCH_FAILURE（鮮度切れ・取得失敗の追記。生成元は取込側 T3.4 と reconcile）。
 * SCRATCH・JOCKEY_CHANGE・COURSE_CHANGE・WEATHER は 0B14 のスナップショット差分（T3.4 以降）で、型だけを用意する。
 * レース状態の時間・成績による進行（MONITORING 以降）に対応する種類は DDL に無い（bot-run が必要とする時に T3.8 で判断）。
 */
enum class ScheduleEventType(
    val raceIdRule: RaceIdRule,
) {
    ADDED(RaceIdRule.REQUIRED),
    DELETED(RaceIdRule.REQUIRED),
    CANCELLED(RaceIdRule.REQUIRED),
    TIME_CHANGE(RaceIdRule.REQUIRED),
    SCRATCH(RaceIdRule.REQUIRED),
    JOCKEY_CHANGE(RaceIdRule.REQUIRED),
    COURSE_CHANGE(RaceIdRule.REQUIRED),
    WEATHER(RaceIdRule.OPTIONAL),
    DAY_STATUS(RaceIdRule.FORBIDDEN),
    POSTPONED(RaceIdRule.FORBIDDEN),
    REVOKED(RaceIdRule.REQUIRED),
    MANUAL_OVERRIDE(RaceIdRule.OPTIONAL),
    MANUAL_REVOKE(RaceIdRule.OPTIONAL),
    FETCH_FAILURE(RaceIdRule.FORBIDDEN),
    STALE(RaceIdRule.FORBIDDEN),
}

/** `race_schedule_event.source`。DB の値は [dbValue]（`0B15`・`0B14` は識別子にできないため名前を変えている）。 */
enum class ScheduleEventSource(
    val dbValue: String,
) {
    YSCH("YSCH"),
    RACE("RACE"),
    B15("0B15"),
    B14("0B14"),
    MANUAL("MANUAL"),
    SYSTEM("SYSTEM"),
    ;

    companion object {
        fun fromDbValue(value: String): ScheduleEventSource =
            entries.firstOrNull { it.dbValue == value } ?: throw IllegalStateException("race_schedule_event.source の値 '$value' がドメインに無い（DDL とドメインの食い違い）")
    }
}

/**
 * 開催予定の変更履歴の 1 行（追記専用）。[oldValue]・[newValue] は JSON 文字列（[EventJson.obj] で作る。null は「値なし」）。
 *
 * 冪等性: `(tenantId, dedupHash)` が一意。[create] は種類・対象・値・[dedupGroup]（取込のバッチや版の識別子）から決定的にハッシュを作るので、
 * 同じ変化を再度記録しても 1 行のまま（取得時刻・受信時刻はハッシュに含めない）。
 */
data class ScheduleEvent(
    override val tenantId: TenantId,
    val id: Long?,
    val raceId: RaceId?,
    val raceDate: LocalDate,
    val type: ScheduleEventType,
    val oldValue: String?,
    val newValue: String?,
    val source: ScheduleEventSource,
    val observedAt: Instant,
    val receivedAt: Instant,
    val batchId: String?,
    val dedupHash: String,
) : TenantScoped {
    init {
        when (type.raceIdRule) {
            RaceIdRule.REQUIRED -> require(raceId != null) { "$type は raceId が必須" }
            RaceIdRule.FORBIDDEN -> require(raceId == null) { "$type は日単位のイベントで raceId を持てない" }
            RaceIdRule.OPTIONAL -> Unit
        }
        require(raceId == null || raceId.date == raceDate) { "raceDate が raceId の日付と一致しない" }
        require(batchId == null || UUID_FORM.matches(batchId)) { "batchId は小文字の UUID 形式" }
        require(HASH_FORM.matches(dedupHash)) { "dedupHash は小文字 16 進 64 桁" }
        require(id == null || id >= 1) { "id は 1 以上" }
    }

    companion object {
        private val UUID_FORM = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val HASH_FORM = Regex("[0-9a-f]{64}")

        fun create(
            tenantId: TenantId,
            raceId: RaceId?,
            raceDate: LocalDate,
            type: ScheduleEventType,
            oldValue: String?,
            newValue: String?,
            source: ScheduleEventSource,
            observedAt: Instant,
            receivedAt: Instant,
            dedupGroup: String,
            batchId: String? = null,
        ): ScheduleEvent {
            require(dedupGroup.isNotEmpty()) { "dedupGroup は空にできない" }
            val hash = hashOf(tenantId, raceId, raceDate, type, oldValue, newValue, source, dedupGroup)
            return ScheduleEvent(tenantId, null, raceId, raceDate, type, oldValue, newValue, source, observedAt, receivedAt, batchId, hash)
        }

        private fun hashOf(
            tenantId: TenantId,
            raceId: RaceId?,
            raceDate: LocalDate,
            type: ScheduleEventType,
            oldValue: String?,
            newValue: String?,
            source: ScheduleEventSource,
            group: String,
        ): String {
            val parts =
                listOf(tenantId.value.toString(), raceId?.value ?: "-", raceDate.toString(), type.name, source.dbValue, oldValue ?: "-", newValue ?: "-", group)
            val digest = MessageDigest.getInstance("SHA-256").digest(parts.joinToString("\u001f").toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * イベントの値に使う JSON を作る。キーは昇順、値は文字列・真偽値・整数・null だけ（正規形なのでハッシュが安定する）。
 * 値の文字列は制御文字・引用符・バックスラッシュを escape する。
 */
object EventJson {
    fun obj(vararg entries: Pair<String, Any?>): String {
        require(entries.map { it.first }.toSet().size == entries.size) { "キーが重複している" }
        return entries.sortedBy { it.first }.joinToString(",", "{", "}") { (k, v) -> "${quote(k)}:${value(v)}" }
    }

    private fun value(v: Any?): String =
        when (v) {
            null -> "null"
            is String -> quote(v)
            is Boolean -> v.toString()
            is Int, is Long -> v.toString()
            else -> throw IllegalArgumentException("イベントの値に使えない型: ${v.javaClass.simpleName}")
        }

    private fun quote(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
