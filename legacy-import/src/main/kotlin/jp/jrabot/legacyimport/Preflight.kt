package jp.jrabot.legacyimport

import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection

/** 1 列の値の形式の内訳（件数だけ。値そのものは持たない）。 */
data class FormatCounts(
    val table: String,
    val column: String,
    val counts: Map<String, Long>,
)

/** 子の行の親が無い件数（外部キーの孤立）。 */
data class OrphanCount(
    val table: String,
    val column: String,
    val parent: String,
    val count: Long,
)

data class PreflightReport(
    val sha256: String,
    val sizeBytes: Long,
    val integrity: String,
    val missingTables: List<String>,
    val extraTables: List<String>,
    /** 表 → 期待にあって実ファイルに無い列。 */
    val missingColumns: Map<String, List<String>>,
    /** 表 → 実ファイルにあって期待に無い列。 */
    val extraColumns: Map<String, List<String>>,
    val rowCounts: Map<String, Long>,
    val times: List<FormatCounts>,
    val dates: List<FormatCounts>,
    val raceIds: List<FormatCounts>,
    val json: List<FormatCounts>,
    val orphans: List<OrphanCount>,
) {
    /** NG: 開けたが整合性の検査に失敗、または期待する表が無い。WARN: 列の差・孤立・形式の不正がある。OK: それ以外。 */
    val verdict: String
        get() =
            when {
                integrity != "ok" || missingTables.isNotEmpty() -> "NG"
                missingColumns.isNotEmpty() || orphans.any { it.count > 0 } || problems() > 0 -> "WARN"
                else -> "OK"
            }

    private fun problems(): Long =
        times.sumOf { it.counts["other"] ?: 0 } +
            dates.sumOf { it.counts["invalid"] ?: 0 } +
            raceIds.sumOf { it.counts["invalid"] ?: 0 } +
            json.sumOf { it.counts["invalid"] ?: 0 }
}

/**
 * 移行の事前検査（DATA_MIGRATION_DESIGN §5.2 P0。D-271）: 旧 SQLite の**コピー**を読取り専用で開き、書き換えずに次を数える。
 * 値そのもの（レース ID・馬名・JSON の中身など）はレポートに出さず、件数だけを出す（実データの断片を残さない）。
 * - ファイルの sha256・大きさ、`PRAGMA integrity_check`
 * - 期待するスキーマ（[LegacySchema]）との表・列の差、表ごとの行数
 * - 時刻の列（`ts`・`*_at`）の形式: `T` 区切り（アプリの naive なローカル時刻 = 通常 JST）・空白区切り（`CURRENT_TIMESTAMP` = UTC）・それ以外・NULL
 * - 日付の列（`date`・`birth_date`・`date_from`・`date_to`）: YYYYMMDD・YYYY-MM-DD・それ以外・NULL
 * - `race_id` の形式（12 桁）と、`races` に親の無い子の行（孤立）。`ticket_evaluations.race_evaluation_id`・`bet_legs.bet_id` の孤立
 * - JSON の列（`*_json`）: `json_valid` で読めない件数
 */
object Preflight {
    fun run(sqlite: Path): PreflightReport {
        require(Files.isRegularFile(sqlite)) { "SQLite のファイルが無い" }
        val sha = sha256(sqlite)
        val size = Files.size(sqlite)
        val cfg = SQLiteConfig().apply { setReadOnly(true) }
        // 読取り専用（mode=ro）で開く。元のファイルを書き換えない（WAL の取り込みもしない: 利用者はコピーを渡す）
        return cfg.createConnection("jdbc:sqlite:file:${sqlite.toAbsolutePath()}?mode=ro").use { c -> inspect(c, sha, size) }
    }

    private fun inspect(
        c: Connection,
        sha: String,
        size: Long,
    ): PreflightReport {
        val integrity = scalarString(c, "PRAGMA integrity_check") ?: "unknown"
        val expected = LegacySchema.expected
        val actualTables = LegacySchema.tables(c).filter { LegacySchema.IDENT.matches(it) }
        val actual = actualTables.associateWith { LegacySchema.columns(c, it) }
        val common = expected.keys.intersect(actual.keys).sorted()
        val missingColumns = common.associateWith { t -> expected.getValue(t) - actual.getValue(t).toSet() }.filterValues { it.isNotEmpty() }
        val extraColumns = common.associateWith { t -> actual.getValue(t) - expected.getValue(t).toSet() }.filterValues { it.isNotEmpty() }
        val rowCounts = actualTables.sorted().associateWith { t -> scalarLong(c, "SELECT COUNT(*) FROM \"$t\"") }

        val times = mutableListOf<FormatCounts>()
        val dates = mutableListOf<FormatCounts>()
        val raceIds = mutableListOf<FormatCounts>()
        val json = mutableListOf<FormatCounts>()
        for (t in common) {
            for (col in actual.getValue(t).filter { LegacySchema.IDENT.matches(it) }) {
                when {
                    col == "ts" || col.endsWith("_at") -> times += counts(c, t, col, TIME_CASES)
                    col == "date" || col == "birth_date" || col == "date_from" || col == "date_to" -> dates += counts(c, t, col, DATE_CASES)
                    col == "race_id" -> raceIds += counts(c, t, col, RACE_ID_CASES)
                    col.endsWith("_json") -> json += counts(c, t, col, JSON_CASES)
                }
            }
        }
        val orphans = mutableListOf<OrphanCount>()
        if ("races" in actual && "race_id" in actual.getValue("races")) {
            for (t in common.filter { it != "races" && "race_id" in actual.getValue(it) }) {
                orphans += OrphanCount(t, "race_id", "races", orphanCount(c, t, "race_id", "races", "race_id"))
            }
        }
        for ((child, col, parent) in listOf(Triple("ticket_evaluations", "race_evaluation_id", "race_evaluations"), Triple("bet_legs", "bet_id", "bets"))) {
            if (child in actual && parent in actual && col in actual.getValue(child) && "id" in actual.getValue(parent)) {
                orphans += OrphanCount(child, col, parent, orphanCount(c, child, col, parent, "id"))
            }
        }
        return PreflightReport(
            sha256 = sha,
            sizeBytes = size,
            integrity = integrity,
            missingTables = (expected.keys - actual.keys).sorted(),
            extraTables = (actual.keys - expected.keys).sorted(),
            missingColumns = missingColumns,
            extraColumns = extraColumns,
            rowCounts = rowCounts,
            times = times,
            dates = dates,
            raceIds = raceIds,
            json = json,
            orphans = orphans,
        )
    }

    /** 分類名 → SQL の条件（`%c` を列に置き換える）。上から順に最初に当たった分類に数える。 */
    private val TIME_CASES =
        listOf(
            "null" to "%c IS NULL",
            // アプリの datetime.now().isoformat()（naive・Windows のローカル時刻 = 通常 JST）。秒の小数は任意
            "iso_t_naive" to "%c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]' OR " +
                "%c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9].[0-9]*'",
            // SQLite の CURRENT_TIMESTAMP（UTC）
            "space_utc" to "%c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]'",
            // タイムゾーン付き（想定外。解釈を本人が決める）
            "with_offset" to "%c GLOB '*T*[+-][0-9][0-9]:[0-9][0-9]' OR %c GLOB '*Z'",
            "other" to "1",
        )
    private val DATE_CASES =
        listOf(
            "null" to "%c IS NULL OR %c = ''",
            "yyyymmdd" to "%c GLOB '[0-9][0-9][0-9][0-9][0-1][0-9][0-3][0-9]' AND length(%c) = 8",
            "yyyy_mm_dd" to "%c GLOB '[0-9][0-9][0-9][0-9]-[0-1][0-9]-[0-3][0-9]' AND length(%c) = 10",
            "invalid" to "1",
        )
    private val RACE_ID_CASES =
        listOf(
            "null" to "%c IS NULL",
            "valid" to "typeof(%c) = 'text' AND length(%c) = 12 AND %c GLOB '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]'",
            "invalid" to "1",
        )
    private val JSON_CASES =
        listOf(
            "null" to "%c IS NULL OR %c = ''",
            "valid" to "json_valid(%c)",
            "invalid" to "1",
        )

    private fun counts(
        c: Connection,
        table: String,
        column: String,
        cases: List<Pair<String, String>>,
    ): FormatCounts {
        val col = "\"$column\""
        val expr = cases.joinToString(" ") { (name, cond) -> "WHEN ${cond.replace("%c", col)} THEN '$name'" }
        val m = LinkedHashMap<String, Long>()
        for ((name, _) in cases) m[name] = 0
        c.createStatement().use { s ->
            s.executeQuery("SELECT CASE $expr END AS k, COUNT(*) FROM \"$table\" GROUP BY k").use { rs ->
                while (rs.next()) m[rs.getString(1)] = rs.getLong(2)
            }
        }
        return FormatCounts(table, column, m)
    }

    private fun orphanCount(
        c: Connection,
        table: String,
        column: String,
        parent: String,
        parentKey: String,
    ): Long =
        scalarLong(
            c,
            "SELECT COUNT(*) FROM \"$table\" ch WHERE ch.\"$column\" IS NOT NULL AND NOT EXISTS " +
                "(SELECT 1 FROM \"$parent\" p WHERE p.\"$parentKey\" = ch.\"$column\")",
        )

    private fun scalarLong(
        c: Connection,
        sql: String,
    ): Long = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 } }

    private fun scalarString(
        c: Connection,
        sql: String,
    ): String? = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    private fun sha256(p: Path): String {
        val md = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(p).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
