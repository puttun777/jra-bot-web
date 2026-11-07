package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.io.BufferedReader
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.ZoneId
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** 旧の 1 行（extract の列名 → 値）。期待するスキーマの列が旧 DB に無ければ（起動時の列）NULL。 */
class Src(
    private val index: Map<String, Int>,
    val values: List<JsonValue>,
) {
    operator fun get(col: String): JsonValue = index[col]?.let { values[it] } ?: JsonNull
}

/** 変換の結果の 1 表分。[quarantined] は理由のコード → 件数、[adjusted] は「列: 扱い」→ 件数（NULL にした・既定値にした）。 */
data class TransformedTable(
    val source: String,
    val target: String,
    val columns: List<String>,
    val sourceRows: Long,
    val rows: Long,
    val quarantined: Map<String, Long>,
    val adjusted: Map<String, Long>,
    val chunks: List<OutChunk>,
)

data class OutChunk(
    val file: String,
    val rows: Long,
    val sha256: String,
)

data class TransformManifest(
    val extractManifestSha256: String,
    val inputSha256: String,
    val tenantId: Long,
    val naiveZone: String,
    val lenientJson: Boolean,
    val tables: List<TransformedTable>,
    val quarantineFiles: List<OutChunk>,
)

/**
 * 変換（DATA_MIGRATION_DESIGN §5.2 P2・§5.6。D-274）: extract の出力を読み、新しいスキーマ（Flyway V1〜V5）の列の値に変換して
 * `<新表>/<番号>.ndjson.gz` に、変換できない行を `quarantine/<旧表>.ndjson.gz` に書く。DB には触れない（load は P3）。
 *
 * - 各表で `元の行数 = 変換した行数 + 隔離した行数` を manifest に記録する（§6.1 の恒等式）。
 * - 親（races・race_evaluations・bets）が無い・隔離された子は隔離する（`orphan`。§5.4 の既定。`--stub-parents` は後続）。
 * - AUTOINCREMENT の表は旧 ID を `legacy_id` に、親の参照は `*_legacy_id` に持つ（新しい ID への張り替えは promote）。
 * - 旧の `signals.sent = 0` は `SEND_UNKNOWN` にする（`UNSENT` にすると新しい bot が古い通知を送るため）。
 */
class Transform(
    private val tenantId: Long,
    naiveZone: ZoneId,
    private val lenientJson: Boolean,
) {
    private val cv = Convert(naiveZone)
    private val races = HashSet<String>()
    private val signals = HashSet<Long>()
    private val bets = HashSet<Long>()
    private val evaluations = HashSet<Long>()

    /** 列の扱い（NULL にした・既定値にした）を数える。表ごとに作り直す。 */
    private var adjusted = LinkedHashMap<String, Long>()

    /** 変換中の行の扱い。行を変換できたときだけ [adjusted] に足す（隔離した行の分は数えない）。 */
    private val pending = mutableListOf<String>()

    private fun adjust(what: String) {
        pending += what
    }

    private fun json(
        col: String,
        v: JsonValue,
    ): String? =
        try {
            cv.json(col, v)
        } catch (e: Reject) {
            if (!lenientJson) throw e
            adjust("$col: 読めない JSON を NULL")
            null
        }

    /** 0/1 の列。NULL なら既定値にして adjusted に数える（レビュー 中: 黙って既定値にしない）。 */
    private fun flag(
        col: String,
        v: JsonValue,
        default: Long,
    ): Long =
        cv.long(col, v, 0L..1L) ?: run {
            adjust("$col: NULL を既定の $default")
            default
        }

    private fun raceRef(v: JsonValue): String {
        val id = cv.requiredText("race_id", v, 12, ascii = true)
        if (id !in races) throw Reject("orphan", "race_id: races に無い（隔離を含む）")
        return id
    }

    private fun positiveOrNull(
        col: String,
        v: JsonValue,
    ): Double? {
        val d = cv.double(col, v) ?: return null
        if (d > 0) return d
        adjust("$col: 0 以下を NULL")
        return null
    }

    private fun positive(
        col: String,
        v: JsonValue,
    ): Double {
        val d = cv.requiredDouble(col, v)
        if (d <= 0) throw Reject("out_of_range", "$col: 0 以下")
        return d
    }

    private fun nonNegative(
        col: String,
        v: JsonValue,
    ): Double? {
        val d = cv.double(col, v) ?: return null
        if (d < 0) throw Reject("out_of_range", "$col: 負")
        return d
    }

    private fun created(s: Src) = cv.requiredTime("created_at", s["created_at"])

    private fun mappings(): List<Mapping> =
        listOf(
            Mapping(
                "races",
                "races",
                listOf(
                    "race_id",
                    "race_date",
                    "venue_code",
                    "race_no",
                    "kai",
                    "nichime",
                    "post_time",
                    "post_time_unknown",
                    "name",
                    "distance_m",
                    "track_type_cd",
                    "course_kubun_cd",
                    "tenko_cd",
                    "shiba_baba_cd",
                    "dirt_baba_cd",
                    "created_at",
                    "updated_at",
                ),
            ) { s ->
                val id = cv.requiredText("race_id", s["race_id"], 12, ascii = true)
                // ドメインの RaceId と同じ検査（1900 年以降・暦にある日付・既知の場・番号 1 以上。CHECK ck_races_race_id_parts も満たす: レビュー 中）
                if (jp.jrabot.domain.race.RaceId
                        .parseOrNull(id) == null
                ) {
                    throw Reject("race_id", "race_id: 形式・日付・場・番号が不正")
                }
                val date = cv.date("date", s["date"]) ?: throw Reject("null", "date: 必須の値が無い")
                if (date.replace("-", "") != id.substring(0, 8)) throw Reject("race_id_mismatch", "date: race_id の日付と違う")
                val venue = id.substring(8, 10)
                if (venue !in Convert.VENUES) throw Reject("venue", "race_id: 未知の場コード")
                cv.text("track_cd", s["track_cd"], 2, ascii = true)?.let {
                    if (it.padStart(2, '0') !=
                        venue
                    ) {
                        throw Reject("race_id_mismatch", "track_cd: race_id の場と違う")
                    }
                }
                val no = id.substring(10, 12).toLong()
                if (no !in 1L..99L) throw Reject("race_id", "race_id: レース番号が 1..99 の外")
                if (cv.requiredLong("race_no", s["race_no"], 1L..99L) != no) throw Reject("race_id_mismatch", "race_no: race_id の番号と違う")
                val post = cv.postTime(s["post_time"])
                if (post == null) adjust("post_time: 不明（00:00・範囲外・形式外）")
                val distance =
                    cv.long("distance_m", s["distance_m"], 0L..65535L).let {
                        if (it == 0L) {
                            adjust("distance_m: 0 を NULL")
                            null
                        } else {
                            it
                        }
                    }
                val created = created(s)
                listOf(
                    id,
                    date,
                    venue,
                    no,
                    null,
                    null,
                    post,
                    if (post == null) 1L else 0L,
                    cv.text("name", s["name"], 100),
                    distance,
                    cv.text("track_type_cd", s["track_type_cd"], 2, true),
                    cv.text("course_kubun_cd", s["course_kubun_cd"], 2, true),
                    cv.text("tenko_cd", s["tenko_cd"], 2, true),
                    cv.text("shiba_baba_cd", s["shiba_baba_cd"], 2, true),
                    cv.text("dirt_baba_cd", s["dirt_baba_cd"], 2, true),
                    created,
                    created,
                )
            },
            Mapping(
                "horses",
                "horses",
                listOf(
                    "ketto_num",
                    "bamei",
                    "sex_cd",
                    "birth_date",
                    "tozai_cd",
                    "trainer_code",
                    "owner_code",
                    "breeder_code",
                    "pedigree_json",
                    "style_tendency_json",
                    "chaku_by_surface_json",
                    "chaku_by_condition_json",
                    "chaku_by_distance_json",
                    "race_count",
                    "created_at",
                    "updated_at",
                ),
            ) { s ->
                val updated = cv.requiredTime("updated_at", s["updated_at"])
                listOf(
                    cv.requiredText("ketto_num", s["ketto_num"], 10, true),
                    cv.text("bamei", s["bamei"], 100),
                    cv.text("sex_cd", s["sex_cd"], 2, true),
                    cv.dateOrNull("birth_date", s["birth_date"]) { adjust("$it: 不正な日付を NULL") },
                    cv.text("tozai_cd", s["tozai_cd"], 2, true),
                    cv.text("trainer_code", s["trainer_code"], 16, true),
                    cv.text("owner_code", s["owner_code"], 16, true),
                    cv.text("breeder_code", s["breeder_code"], 16, true),
                    json("pedigree_json", s["pedigree_json"]),
                    json("style_tendency_json", s["style_tendency_json"]),
                    json("chaku_by_surface_json", s["chaku_by_surface_json"]),
                    json("chaku_by_condition_json", s["chaku_by_condition_json"]),
                    json("chaku_by_distance_json", s["chaku_by_distance_json"]),
                    cv.long("race_count", s["race_count"], 0L..4_294_967_295L),
                    updated,
                    updated,
                )
            },
            Mapping(
                "race_horses",
                "race_horses",
                listOf("race_id", "umaban", "ketto_num", "bamei", "jockey_code", "trainer_code", "created_at", "updated_at"),
            ) { s ->
                val created = created(s)
                listOf(
                    raceRef(s["race_id"]),
                    cv.requiredLong("umaban", s["umaban"], 1L..99L),
                    cv.text("ketto_num", s["ketto_num"], 10, true),
                    cv.text("bamei", s["bamei"], 100),
                    cv.text("jockey_code", s["jockey_code"], 16, true),
                    cv.text("trainer_code", s["trainer_code"], 16, true),
                    created,
                    created,
                )
            },
            Mapping(
                "horse_past_runs",
                "horse_past_runs",
                listOf(
                    "ketto_num",
                    "race_id",
                    "run_date",
                    "venue_code",
                    "race_no",
                    "umaban",
                    "finish",
                    "odds",
                    "popularity",
                    "kyakusitu_kubun",
                    "corner1",
                    "corner2",
                    "corner3",
                    "corner4",
                    "time_str",
                    "time_diff_str",
                    "created_at",
                    "updated_at",
                ),
            ) { s ->
                // 過去走は races への外部キーを持たない（取込み前のレースも含む）。race_id の形だけ確かめる
                val id = cv.requiredText("race_id", s["race_id"], 12, true)
                if (!Convert.RACE_ID.matches(id)) throw Reject("race_id", "race_id: 12 桁の数字ではない")
                val created = created(s)
                listOf(
                    cv.requiredText("ketto_num", s["ketto_num"], 10, true),
                    id,
                    cv.dateOrNull("date", s["date"]) { adjust("$it: 不正な日付を NULL") },
                    cv.text("track_cd", s["track_cd"], 2, true),
                    cv.long("race_no", s["race_no"], 0L..255L),
                    cv.long("umaban", s["umaban"], 0L..255L),
                    cv.long("finish", s["finish"], 0L..65535L),
                    positiveOrNull("odds", s["odds"]),
                    cv.long("popularity", s["popularity"], 0L..65535L),
                    cv.text("kyakusitu_kubun", s["kyakusitu_kubun"], 2, true),
                    cv.long("corner1", s["corner1"], 0L..255L),
                    cv.long("corner2", s["corner2"], 0L..255L),
                    cv.long("corner3", s["corner3"], 0L..255L),
                    cv.long("corner4", s["corner4"], 0L..255L),
                    cv.text("time_str", s["time_str"], 16, true),
                    cv.text("time_diff_str", s["time_diff_str"], 16, true),
                    created,
                    created,
                )
            },
            Mapping("odds_snapshots", "odds_snapshots", listOf("race_id", "market", "observed_at", "umaban", "odds", "popularity")) { s ->
                listOf(
                    raceRef(s["race_id"]),
                    cv.market("market", s["market"]),
                    cv.requiredTime("ts", s["ts"]),
                    cv.requiredLong("umaban", s["umaban"], 1L..99L),
                    positive("odds", s["odds"]),
                    cv.long("popularity", s["popularity"], 0L..65535L),
                )
            },
            Mapping(
                "combo_odds_snapshots",
                "combo_odds_snapshots",
                listOf("race_id", "market", "collected_at", "combo_key", "odds", "popularity", "source", "rt_key", "created_at"),
            ) { s ->
                listOf(
                    raceRef(s["race_id"]),
                    cv.market("market", s["market"]),
                    cv.requiredTime("collected_at", s["collected_at"]),
                    cv.requiredText("combo_key", s["combo_key"], 16, true),
                    positive("odds", s["odds"]),
                    cv.long("popularity", s["popularity"], 0L..65535L),
                    cv.text("source", s["source"], 16, true),
                    cv.text("rt_key", s["rt_key"], 32, true),
                    created(s),
                )
            },
            Mapping("results", "results", listOf("race_id", "umaban", "finish", "payout", "created_at", "updated_at")) { s ->
                val created = created(s)
                listOf(
                    raceRef(s["race_id"]),
                    cv.requiredLong("umaban", s["umaban"], 1L..99L),
                    cv.long("finish", s["finish"], 0L..65535L),
                    nonNegative("payout", s["payout"]),
                    created,
                    created,
                )
            },
            Mapping("payouts", "payouts", listOf("race_id", "market", "combo_key", "payout", "is_provisional", "created_at", "updated_at")) { s ->
                val created = created(s)
                listOf(
                    raceRef(s["race_id"]),
                    cv.market("market", s["market"]),
                    cv.requiredText("key", s["key"], 16, true),
                    nonNegative("payout", s["payout"]) ?: throw Reject("null", "payout: 必須の値が無い"),
                    flag("is_provisional", s["is_provisional"], 0L),
                    created,
                    created,
                )
            },
            Mapping(
                "signals",
                "signals",
                listOf(
                    "legacy_id",
                    "race_id",
                    "market",
                    "umaban",
                    "observed_at",
                    "odds",
                    "prob",
                    "ev",
                    "is_paper",
                    "stake",
                    "expected_profit",
                    "skip_reason",
                    "kelly_raw",
                    "kelly_adj",
                    "bet_before_clip",
                    "clipped",
                    "mkt",
                    "mkt_adj",
                    "ratio",
                    "expected_return",
                    "suggested_bet",
                    "prob_source",
                    "reason",
                    "notify_state",
                    "notify_updated_at",
                    "created_at",
                ),
            ) { s ->
                val id = cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE)
                val prob = cv.requiredDouble("prob", s["prob"])
                if (prob !in 0.0..1.0) throw Reject("out_of_range", "prob: 0..1 の外")
                val stake =
                    nonNegative("stake", s["stake"]) ?: run {
                        adjust("stake: NULL を既定の 1.0")
                        1.0
                    }
                val sent = flag("sent", s["sent"], 0L)
                val row =
                    listOf(
                        id,
                        raceRef(s["race_id"]),
                        cv.market("market", s["market"]),
                        cv.requiredLong("umaban", s["umaban"], 1L..99L),
                        cv.requiredTime("ts", s["ts"]),
                        positive("odds", s["odds"]),
                        prob,
                        cv.requiredDouble("ev", s["ev"]),
                        flag("is_paper", s["is_paper"], 1L),
                        stake,
                        cv.double("expected_profit", s["expected_profit"]),
                        cv.text("skip_reason", s["skip_reason"], 200),
                        cv.double("kelly_raw", s["kelly_raw"]),
                        cv.double("kelly_adj", s["kelly_adj"]),
                        cv.double("bet_before_clip", s["bet_before_clip"]),
                        flag("clipped", s["clipped"], 0L),
                        cv.double("mkt", s["mkt"]),
                        cv.double("mkt_adj", s["mkt_adj"]),
                        cv.double("ratio", s["ratio"]),
                        cv.double("expected_return", s["expected_return"]),
                        cv.double("suggested_bet", s["suggested_bet"]),
                        cv.text("prob_source", s["prob_source"], 100),
                        cv.text("reason", s["reason"], 500),
                        if (sent == 1L) "SENT" else "SEND_UNKNOWN",
                        null,
                        created(s),
                    )
                if (sent != 1L) adjust("sent: 0 を SEND_UNKNOWN（再送しない）")
                row
            },
            Mapping(
                "race_evaluations",
                "race_evaluations",
                listOf(
                    "legacy_id",
                    "race_id",
                    "snapshot_at",
                    "model_type_used",
                    "model_path_used",
                    "model_id",
                    "fallback_reason",
                    "bet_types",
                    "priority_mode",
                    "ev_threshold",
                    "win_odds_max",
                    "win_prob_min",
                    "win_ratio_min",
                    "best_candidate_json",
                    "best_ev",
                    "rejection_summary_json",
                    "signal_sent",
                    "settled_at",
                    "hit",
                    "payout_used",
                    "is_provisional",
                    "settle_status",
                    "best_win_hint_json",
                    "created_at",
                ),
            ) { s ->
                val id = cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE)
                val row =
                    listOf(
                        id,
                        raceRef(s["race_id"]),
                        cv.requiredTime("snapshot_at", s["snapshot_at"]),
                        cv.text("model_type_used", s["model_type_used"], 32, true),
                        cv.text("model_path_used", s["model_path_used"], 500),
                        null,
                        null,
                        json("bet_types", s["bet_types"]),
                        cv.text("priority_mode", s["priority_mode"], 16, true),
                        cv.double("ev_threshold", s["ev_threshold"]),
                        cv.double("win_odds_max", s["win_odds_max"]),
                        cv.double("win_prob_min", s["win_prob_min"]),
                        cv.double("win_ratio_min", s["win_ratio_min"]),
                        json("best_candidate_json", s["best_candidate_json"]),
                        cv.double("best_ev", s["best_ev"]),
                        json("rejection_summary_json", s["rejection_summary_json"]),
                        flag("signal_sent", s["signal_sent"], 0L),
                        cv.time("settled_at", s["settled_at"]),
                        cv.flag("hit", s["hit"], null),
                        cv.double("payout_used", s["payout_used"]),
                        flag("is_provisional", s["is_provisional"], 0L),
                        cv.text("settle_status", s["settle_status"], 16, true),
                        json("best_win_hint_json", s["best_win_hint_json"]),
                        created(s),
                    )
                row
            },
            Mapping(
                "ticket_evaluations",
                "ticket_evaluations",
                listOf(
                    "legacy_id",
                    "race_evaluation_legacy_id",
                    "race_id",
                    "snapshot_at",
                    "bet_type",
                    "combo_key",
                    "umaban",
                    "odds",
                    "p_model",
                    "p_mkt",
                    "p_final",
                    "ratio",
                    "ev",
                    "stake",
                    "rank_no",
                    "reject_reason",
                    "accepted",
                    "created_at",
                ),
            ) { s ->
                val parent = cv.requiredLong("race_evaluation_id", s["race_evaluation_id"], 1L..Long.MAX_VALUE)
                if (parent !in evaluations) throw Reject("orphan", "race_evaluation_id: race_evaluations に無い（隔離を含む）")
                listOf(
                    cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE),
                    parent,
                    raceRef(s["race_id"]),
                    cv.requiredTime("snapshot_at", s["snapshot_at"]),
                    cv.requiredText("bet_type", s["bet_type"], 16),
                    cv.text("combo_key", s["combo_key"], 16, true),
                    cv.long("umaban", s["umaban"], 0L..255L), // 新表は TINYINT UNSIGNED（CHECK なし）
                    positive("odds", s["odds"]),
                    cv.double("p_model", s["p_model"]),
                    cv.double("p_mkt", s["p_mkt"]),
                    cv.double("p_final", s["p_final"]),
                    cv.double("ratio", s["ratio"]),
                    cv.double("ev", s["ev"]),
                    cv.double("stake", s["stake"]),
                    cv.long("rank", s["rank"], Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()),
                    cv.text("reject_reason", s["reject_reason"], 200),
                    flag("accepted", s["accepted"], 0L),
                    created(s),
                )
            },
            Mapping(
                "bets",
                "bets",
                listOf(
                    "legacy_id",
                    "race_id",
                    "placed_at",
                    "market",
                    "stake",
                    "source",
                    "note",
                    "selection",
                    "odds_at_bet",
                    "prob_used_for_bet",
                    "ev",
                    "expected_profit",
                    "signal_legacy_id",
                    "status",
                    "settled_at",
                    "payout",
                    "profit",
                    "skip_reason",
                    "created_at",
                ),
            ) { s ->
                val id = cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE)
                val source = cv.requiredText("source", s["source"], 16, true)
                if (source !in setOf("paper", "manual")) throw Reject("enum", "source: paper・manual 以外")
                // 旧には note（作成時の列）と notes（起動時の列）の 2 つがある。新は note だけなので、両方あれば改行でつなぐ
                val notes = listOfNotNull(cv.text("note", s["note"], 500), cv.text("notes", s["notes"], 500)).distinct()
                if (notes.size > 1) adjust("note: note と notes をつないだ")
                val note = notes.joinToString("\n").ifEmpty { null }
                if (note != null && note.codePointCount(0, note.length) > 500) throw Reject("too_long", "note: 500 文字を超える")
                val signal =
                    cv.long("signal_id", s["signal_id"], 1L..Long.MAX_VALUE)?.let {
                        if (it in signals) {
                            it
                        } else {
                            adjust("signal_id: signals に無い（隔離を含む）ので NULL")
                            null
                        }
                    }
                val row =
                    listOf(
                        id,
                        raceRef(s["race_id"]),
                        cv.requiredTime("ts", s["ts"]),
                        cv.market("market", s["market"]),
                        nonNegative("stake", s["stake"]) ?: throw Reject("null", "stake: 必須の値が無い"),
                        source,
                        note,
                        cv.text("selection", s["selection"], 32, true),
                        positiveOrNull("odds_at_bet", s["odds_at_bet"]),
                        cv.double("prob_used_for_bet", s["prob_used_for_bet"]),
                        cv.double("ev", s["ev"]),
                        cv.double("expected_profit", s["expected_profit"]),
                        signal,
                        cv.text("status", s["status"], 16, true),
                        cv.time("settled_at", s["settled_at"]),
                        cv.double("payout", s["payout"]),
                        cv.double("profit", s["profit"]),
                        cv.text("skip_reason", s["skip_reason"], 200),
                        created(s),
                    )
                row
            },
            Mapping(
                "bet_legs",
                "bet_legs",
                listOf("legacy_id", "bet_legacy_id", "umaban", "odds_at_bet", "prob_at_bet", "ev_at_bet", "created_at"),
            ) { s ->
                val parent = cv.requiredLong("bet_id", s["bet_id"], 1L..Long.MAX_VALUE)
                if (parent !in bets) throw Reject("orphan", "bet_id: bets に無い（隔離を含む）")
                listOf(
                    cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE),
                    parent,
                    cv.requiredLong("umaban", s["umaban"], 1L..99L),
                    positive("odds_at_bet", s["odds_at_bet"]),
                    cv.double("prob_at_bet", s["prob_at_bet"]),
                    cv.double("ev_at_bet", s["ev_at_bet"]),
                    created(s),
                )
            },
            Mapping(
                "model_metrics",
                "model_metrics",
                listOf("legacy_id", "date_from", "date_to", "market", "sample_count", "brier", "logloss", "calibration_json", "created_at"),
            ) { s ->
                listOf(
                    cv.requiredLong("id", s["id"], 1L..Long.MAX_VALUE),
                    cv.date("date_from", s["date_from"]),
                    cv.date("date_to", s["date_to"]),
                    cv.market("market", s["market"]),
                    cv.requiredLong("count", s["count"], 0L..Int.MAX_VALUE.toLong()),
                    cv.double("brier", s["brier"]),
                    cv.double("logloss", s["logloss"]),
                    json("calibration_json", s["calibration_json"]),
                    created(s),
                )
            },
            Mapping("jvlink_timestamps", "ingest_cursor", listOf("data_type", "last_file_timestamp", "updated_at")) { s ->
                listOf(
                    cv.requiredText("data_type", s["data_type"], 32, true),
                    cv.requiredText("lastfiletimestamp", s["lastfiletimestamp"], 14, true),
                    cv.requiredTime("updated_at", s["updated_at"]),
                )
            },
        )

    /**
     * [keys]: 新表の主キー・一意キーの列（tenant_id を除く）。重複は `duplicate` で隔離する（MySQL の ascii_bin は末尾の空白を無視するので、
     * 文字列は末尾の空白を除いて比べる: レビュー 中）。[partition]: extract の並び（旧の主キーの先頭の列）と同じ順に並ぶ新表の列。
     * 指定すると、その値が変わるごとに重複の記憶を捨てる（大きな表でメモリを抑える）。[accept]: 行を受け入れたとき（子の参照の判定用）。
     */
    private class Mapping(
        val source: String,
        val target: String,
        val columns: List<String>,
        val keys: List<String> = emptyList(),
        val partition: String? = null,
        val accept: (List<Any?>) -> Unit = {},
        val map: (Src) -> List<Any?>,
    ) {
        fun with(
            keys: List<String>,
            partition: String? = null,
            accept: (List<Any?>) -> Unit = {},
        ) = Mapping(source, target, columns, keys, partition, accept, map)
    }

    /**
     * 表ごとの一意キー・並び・受け入れ（子の参照の判定に使う親は、全列を変換でき重複でもない行だけ）。
     * legacy_id だけが一意キーの表は、旧の INTEGER PRIMARY KEY で一意なので照らさない（メモリを使わない: 再レビュー 低）。
     */
    private fun spec(m: Mapping): Mapping =
        when (m.source) {
            "races" -> m.with(listOf("race_id"), accept = { races += it[0] as String })
            "horses" -> m.with(listOf("ketto_num"))
            "race_horses" -> m.with(listOf("race_id", "umaban"), "race_id")
            "horse_past_runs" -> m.with(listOf("ketto_num", "race_id"), "ketto_num")
            "odds_snapshots" -> m.with(listOf("race_id", "market", "observed_at", "umaban"), "race_id")
            "combo_odds_snapshots" -> m.with(listOf("race_id", "market", "collected_at", "combo_key"), "race_id")
            "results" -> m.with(listOf("race_id", "umaban"), "race_id")
            "payouts" -> m.with(listOf("race_id", "market", "combo_key"), "race_id")
            "signals" -> m.with(listOf("race_id", "market", "umaban", "observed_at"), accept = { signals += it[0] as Long })
            "race_evaluations" -> m.with(emptyList(), accept = { evaluations += it[0] as Long })
            "ticket_evaluations" -> m.with(emptyList())
            "bets" -> m.with(emptyList(), accept = { bets += it[0] as Long })
            "bet_legs" -> m.with(emptyList())
            "model_metrics" -> m.with(emptyList())
            "jvlink_timestamps" -> m.with(listOf("data_type"))
            else -> error("一意キーの指定が無い: ${m.source}")
        }

    /** 一意キーの重複の検出（[Mapping.partition] ごと。並びが崩れていたらツールの失敗）。 */
    private class Dedup(
        private val keyIdx: List<Int>,
        private val partIdx: Int?,
    ) {
        private val seen = HashSet<String>()
        private val closed = HashSet<String>()
        private var current: String? = null

        /** 重複なら false。 */
        fun add(row: List<Any?>): Boolean {
            if (keyIdx.isEmpty()) return true
            if (partIdx != null) {
                val p = norm(row[partIdx])
                if (p != current) {
                    check(p !in closed) { "extract の並びが想定と違う（重複を検出できない）" }
                    current?.let { closed += it }
                    current = p
                    seen.clear()
                }
            }
            return seen.add(keyIdx.joinToString("\u0000") { norm(row[it]) })
        }

        private fun norm(v: Any?): String =
            when (v) {
                null -> "\u0001null"
                is String -> v.trimEnd(' ')
                else -> v.toString()
            }
    }

    fun run(
        extractDir: Path,
        outDir: Path,
    ): TransformManifest {
        val manifestBytes = Files.readAllBytes(extractDir.resolve(Extract.MANIFEST))
        val extract = Extract.fromJson(StrictJson.parse(String(manifestBytes, Charsets.UTF_8)))
        checkOutDir(outDir)
        Files.createDirectories(outDir.resolve("quarantine"))
        val byTable = extract.tables.associateBy { it.table }
        val mappings = mappings().map(::spec)
        require(mappings.map { it.source }.toSet() == LegacySchema.expected.keys) { "変換の表が期待するスキーマと違う" }
        val quarantineFiles = mutableListOf<OutChunk>()
        val tables =
            mappings.map { m ->
                adjusted = LinkedHashMap()
                val t = byTable[m.source] ?: throw InputRejected("extract に表が無い: ${m.source}")
                val index = t.columns.withIndex().associate { (i, c) -> c to i }
                val quarantined = LinkedHashMap<String, Long>()
                var rows = 0L
                var sourceRows = 0L
                val writer = ChunkedWriter(outDir, m.target, extract.chunkRows)
                val dedup =
                    Dedup(
                        m.keys.map { k ->
                            m.columns.indexOf(k).also { check(it >= 0) { "キーの列が無い: $k" } }
                        },
                        m.partition?.let { m.columns.indexOf(it) },
                    )
                val qFile = "quarantine/${m.source}.ndjson.gz"
                val q = HashedGzip(outDir.resolve(qFile))
                try {
                    for (ch in t.chunks) {
                        readChunk(extractDir, ch) { values ->
                            sourceRows++
                            if (values.size != t.columns.size) throw InputRejected("列の数が manifest と違う: ${ch.file}")
                            val src = Src(index, values)
                            pending.clear()
                            try {
                                val out = m.map(src)
                                check(out.size == m.columns.size) { "変換の列の数が違う: ${m.source}" }
                                if (!dedup.add(out)) throw Reject("duplicate", "新表の一意キーが重複する（末尾の空白・時刻の形の違いを含む）")
                                m.accept(out)
                                writer.write(JsonArray(listOf(JsonInt(tenantId)) + out.map(::toJson)))
                                rows++
                                for (a in pending) adjusted.merge(a, 1L, Long::plus)
                            } catch (e: Reject) {
                                quarantined.merge(e.code, 1L, Long::plus)
                                q.write(quarantineLine(m.source, t, src, e))
                            }
                        }
                    }
                } finally {
                    writer.close()
                    q.close()
                }
                if (sourceRows != t.rows) throw InputRejected("extract の行数が manifest と違う: ${m.source}")
                quarantineFiles += OutChunk(qFile, q.rows, q.sha256())
                TransformedTable(m.source, m.target, listOf("tenant_id") + m.columns, sourceRows, rows, quarantined, adjusted, writer.chunks)
            }
        val manifest =
            TransformManifest(
                CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes)),
                extract.inputSha256,
                tenantId,
                cv.zoneId(),
                lenientJson,
                tables,
                quarantineFiles,
            )
        val tmp = outDir.resolve("$MANIFEST.tmp")
        Files.writeString(tmp, CanonicalJson.encode(toJson(manifest)) + "\n", StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SYNC)
        Files.move(tmp, outDir.resolve(MANIFEST), StandardCopyOption.ATOMIC_MOVE)
        return manifest
    }

    private fun quarantineLine(
        source: String,
        t: TableEntry,
        src: Src,
        e: Reject,
    ): JsonValue =
        JsonObject.of(
            "source_table" to JsonString(source),
            "source_key" to JsonString(sourceKey(JsonArray(t.primaryKey.map { src[it] }))),
            "reason_code" to JsonString(e.code),
            "reason_detail" to JsonString(e.detail),
            "payload" to JsonObject(t.columns.withIndex().associate { (i, c) -> c to src.values[i] }),
        )

    /** `import_quarantine.source_key`（VARCHAR(255)）に入る主キーの表現。長ければ sha256（レビュー 低）。 */
    private fun sourceKey(key: JsonValue): String {
        val s = CanonicalJson.encode(key)
        return if (s.codePointCount(0, s.length) <= 255) s else "sha256:" + CanonicalJson.sha256Hex(key)
    }

    /** extract のチャンクを読み、展開後の sha256 を manifest と照らす（食い違えば失敗）。 */
    private fun readChunk(
        extractDir: Path,
        ch: ChunkEntry,
        onRow: (List<JsonValue>) -> Unit,
    ) {
        val root = extractDir.toRealPath()
        val p = root.resolve(ch.file).normalize()
        if (!p.startsWith(root) || !Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw InputRejected("extract のチャンクが無い: ${ch.file}")
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        BufferedReader(DigestInputStream(GZIPInputStream(Files.newInputStream(p)), md).reader(Charsets.UTF_8)).use { r ->
            r.lineSequence().forEach { line ->
                val v = StrictJson.parse(line) as? JsonArray ?: throw InputRejected("extract の行が配列ではない: ${ch.file}")
                onRow(v.items)
                n++
            }
        }
        if (n != ch.rows || CanonicalJson.hex(md.digest()) != ch.sha256) throw InputRejected("extract のチャンクが manifest と違う: ${ch.file}")
    }

    private fun checkOutDir(outDir: Path) {
        if (!Files.exists(outDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (!Files.isDirectory(outDir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw InputRejected("出力先がディレクトリではない")
        Files.list(outDir).use { if (it.findFirst().isPresent) throw InputRejected("出力先が空ではない（前回の出力を上書きしない）") }
    }

    /** 展開後の内容の sha256 を数えながら書く gzip。 */
    private class HashedGzip(
        p: Path,
    ) {
        private val md = MessageDigest.getInstance("SHA-256")
        private val out: OutputStream =
            DigestOutputStream(GZIPOutputStream(Files.newOutputStream(p, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)), md)
        var rows = 0L
        private var closed = false

        fun write(v: JsonValue) {
            out.write((CanonicalJson.encode(v) + "\n").toByteArray(Charsets.UTF_8))
            rows++
        }

        fun close() {
            if (!closed) out.close()
            closed = true
        }

        fun sha256(): String = CanonicalJson.hex(md.digest())
    }

    private class ChunkedWriter(
        private val outDir: Path,
        private val table: String,
        private val chunkRows: Int,
    ) {
        val chunks = mutableListOf<OutChunk>()
        private var current: HashedGzip? = null
        private var currentFile = ""

        fun write(v: JsonValue) {
            val w =
                current ?: run {
                    currentFile = Extract.chunkFile(table, chunks.size)
                    Files.createDirectories(outDir.resolve(table))
                    HashedGzip(outDir.resolve(currentFile)).also { current = it }
                }
            w.write(v)
            if (w.rows == chunkRows.toLong()) flush()
        }

        private fun flush() {
            val w = current ?: return
            current = null
            w.close()
            chunks += OutChunk(currentFile, w.rows, w.sha256())
        }

        fun close() = flush()
    }

    companion object {
        /** 新表 → 出力する列（tenant_id を含む。load が manifest を照らす正本: D-275 のレビュー 低）。 */
        internal fun outputColumns(): Map<String, List<String>> =
            Transform(1, java.time.ZoneOffset.UTC, lenientJson = false).mappings().associate { it.target to listOf("tenant_id") + it.columns }

        const val MANIFEST = "transform-manifest.json"
        const val FORMAT = "legacy-import transform v1"

        internal fun toJson(v: Any?): JsonValue =
            when (v) {
                null -> JsonNull
                is String -> JsonString(v)
                is Long -> JsonInt(v)
                is Int -> JsonInt(v.toLong())
                is Double -> JsonFloat(v)
                else -> throw IllegalStateException("変換の値の型が想定外: ${v.javaClass.simpleName}")
            }

        fun toJson(m: TransformManifest): JsonObject =
            JsonObject.of(
                "format" to JsonString(FORMAT),
                "extract_manifest_sha256" to JsonString(m.extractManifestSha256),
                "input_sha256" to JsonString(m.inputSha256),
                "tenant_id" to JsonInt(m.tenantId),
                "naive_zone" to JsonString(m.naiveZone),
                "lenient_json" to
                    jp.jrabot.domain.ml.json
                        .JsonBool(m.lenientJson),
                "tables" to
                    JsonArray(
                        m.tables.map { t ->
                            JsonObject.of(
                                "source" to JsonString(t.source),
                                "target" to JsonString(t.target),
                                "columns" to JsonArray(t.columns.map { JsonString(it) }),
                                "source_rows" to JsonInt(t.sourceRows),
                                "rows" to JsonInt(t.rows),
                                "quarantined" to JsonObject(t.quarantined.mapValues { JsonInt(it.value) }),
                                "adjusted" to JsonObject(t.adjusted.mapValues { JsonInt(it.value) }),
                                "chunks" to JsonArray(t.chunks.map(::chunkJson)),
                            )
                        },
                    ),
                "quarantine_files" to JsonArray(m.quarantineFiles.map(::chunkJson)),
            )

        private fun chunkJson(c: OutChunk): JsonValue = JsonObject.of("file" to JsonString(c.file), "rows" to JsonInt(c.rows), "sha256" to JsonString(c.sha256))
    }
}
