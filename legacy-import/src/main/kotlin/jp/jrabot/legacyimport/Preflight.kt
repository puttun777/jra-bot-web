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

/** 子の行の親が無い件数（期待するスキーマの外部キーごと）。 */
data class OrphanCount(
    val table: String,
    val column: String,
    val parent: String,
    val count: Long,
)

/** 入力を受け付けない（終了コード 2）。理由だけで、パスや中身は含めない。 */
class InputRejected(
    message: String,
) : RuntimeException(message)

data class PreflightReport(
    val sha256: String,
    val sizeBytes: Long,
    /** ファイルの見出し（バイト 18・19）が WAL のモード。 */
    val walMode: Boolean,
    val integrity: String,
    val missingTables: List<String>,
    val extraTables: List<String>,
    /** 表 → 期待にあって実ファイルに無い列（起動時に足される列だけなら WARN、それ以外は NG）。 */
    val missingColumns: Map<String, List<String>>,
    /** 表 → 実ファイルにあって期待に無い列。 */
    val extraColumns: Map<String, List<String>>,
    val rowCounts: Map<String, Long>,
    val times: List<FormatCounts>,
    val dates: List<FormatCounts>,
    val raceIds: List<FormatCounts>,
    val json: List<FormatCounts>,
    val orphans: List<OrphanCount>,
    /**
     * 主キーが期待と違う表（NG）。抽出の並び・重複の扱い（例: 旧 ID の一意性）は期待する主キーを前提にする（D-275 のレビュー 低）。
     */
    val primaryKeyMismatch: List<String> = emptyList(),
) {
    /** 期待する列のうち、起動時に足される列以外が無い（抽出できない）。 */
    val missingRequiredColumns: Map<String, List<String>>
        get() =
            missingColumns
                .mapValues { (t, cols) -> cols - LegacySchema.dynamicColumns[t].orEmpty() }
                .filterValues { it.isNotEmpty() }

    /** 判定の理由（人が読む。件数と表・列の名前だけ）。 */
    val warnings: List<String>
        get() =
            buildList {
                if (walMode) add("WAL のモードのファイル（旧アプリを止めてから、または sqlite3 の .backup で作ったコピーでなければ、未反映の変更が欠ける）")
                for ((t, cols) in missingColumns - missingRequiredColumns.keys) add("$t に起動時に足される列が無い: ${cols.joinToString()}")
                for (t in extraTables) {
                    val n = rowCounts[t]
                    // 名前の形式が不正な表は数えない（SQL に埋め込まない）。行の有無が分からないので必ず知らせる
                    if (n == null) {
                        add("期待に無い表（名前の形式が不正で行数不明。移行の対象外）: ${t.length} 文字の表名")
                    } else if (n > 0) {
                        add("期待に無い表に行がある（移行の対象外）: $t")
                    }
                }
                for ((t, cols) in extraColumns) if ((rowCounts[t] ?: 0) > 0) add("期待に無い列（移行の対象外）: $t.${cols.joinToString()}")
                for (o in orphans) if (o.count > 0) add("孤立: ${o.table}.${o.column} → ${o.parent} が無い ${o.count} 行")
                for (f in times) for (k in listOf("other", "with_offset")) if ((f.counts[k] ?: 0) > 0) add("時刻の形式（$k）: ${f.table}.${f.column} ${f.counts[k]} 行")
                for (f in dates + raceIds + json) if ((f.counts["invalid"] ?: 0) > 0) add("不正な値: ${f.table}.${f.column} ${f.counts["invalid"]} 行")
            }

    /** NG: 整合性の失敗・期待する表が無い・抽出に要る列が無い。WARN: [warnings] がある。OK: それ以外。 */
    val verdict: String
        get() =
            when {
                integrity != "ok" || missingTables.isNotEmpty() || missingRequiredColumns.isNotEmpty() || primaryKeyMismatch.isNotEmpty() -> "NG"
                warnings.isNotEmpty() -> "WARN"
                else -> "OK"
            }
}

/**
 * 移行の事前検査（DATA_MIGRATION_DESIGN §5.2 P0。D-271）: 旧 SQLite の**コピー**を読取り専用で開き、書き換えずに次を数える。
 * 値そのもの（レース ID・馬名・JSON の中身など）はレポートに出さず、件数だけを出す（実データの断片を残さない）。
 *
 * **WAL**: 旧アプリは WAL のモードで書く。`-wal` に未反映の変更が残ったままのコピーは受け付けない（sha256 が検査した内容を表さず、読取り専用では
 * 取り込めないため）。旧アプリを止めてから、または `sqlite3 <db> ".backup <copy>"` で作った 1 ファイルのコピーを渡す。`-wal`・`-shm` が無いときは
 * `immutable=1` で開き、横に何も作らない（D-271 のレビュー 高）。
 */
object Preflight {
    fun run(sqlite: Path): PreflightReport {
        checkInput(sqlite)
        val sha = sha256(sqlite)
        val size = Files.size(sqlite)
        val wal = isWalMode(sqlite)
        return open(sqlite).use { c -> inspect(c, sha, size, wal) }
    }

    /** ファイルがあり、横に中身のある -wal・-shm・-journal が無いこと（無ければ [InputRejected]）。 */
    internal fun checkInput(sqlite: Path) {
        if (!Files.isRegularFile(sqlite)) throw InputRejected("SQLite のファイルが無い")
        for (suffix in listOf("-wal", "-shm", "-journal")) {
            val side = sqlite.resolveSibling(sqlite.fileName.toString() + suffix)
            if (Files.exists(side) && Files.size(side) > 0) {
                throw InputRejected(
                    "横に $suffix がある（未反映の変更が残っている）。旧アプリを止めてから、または sqlite3 の .backup で 1 ファイルのコピーを作って渡す",
                )
            }
        }
    }

    /**
     * 読取り専用・不変（`immutable=1`: ロックも `-shm` も使わない）で開く。パスは URI として正しくエンコードする
     * （`%`・`#`・空白を含むパスで別のファイルを開かない。Windows のパスも `toUri` が file:/// にする）。
     */
    internal fun open(sqlite: Path): Connection {
        val cfg = SQLiteConfig().apply { setReadOnly(true) }
        val uri = sqlite.toAbsolutePath().toUri().toASCIIString() + "?mode=ro&immutable=1"
        return cfg.createConnection("jdbc:sqlite:$uri")
    }

    /** SQLite の見出しのバイト 18（書込みの版）・19（読込みの版）が 2 なら WAL。 */
    private fun isWalMode(p: Path): Boolean =
        Files.newInputStream(p).use { input ->
            val head = input.readNBytes(20)
            head.size == 20 && (head[18].toInt() == 2 || head[19].toInt() == 2)
        }

    private fun inspect(
        c: Connection,
        sha: String,
        size: Long,
        wal: Boolean,
    ): PreflightReport {
        val integrity = scalarString(c, "PRAGMA integrity_check") ?: "unknown"
        val expected = LegacySchema.expected
        val allTables = LegacySchema.tables(c)
        // 識別子として扱えない名前の表は、列を読まずに余分な表として報告する（黙って落とさない）
        val actualTables = allTables.filter { LegacySchema.IDENT.matches(it) }
        val actual = actualTables.associateWith { LegacySchema.columns(c, it) }
        // SQLite の名前は大文字小文字を区別しないので、比較は小文字で
        val byLower = actual.keys.associateBy { it.lowercase() }
        val common = expected.keys.filter { it.lowercase() in byLower }.sorted()

        fun actualCols(t: String) = actual.getValue(byLower.getValue(t.lowercase()))
        val missingColumns =
            common
                .associateWith { t ->
                    val have = actualCols(t).map { it.lowercase() }.toSet()
                    expected.getValue(t).filter { it.lowercase() !in have }
                }.filterValues { it.isNotEmpty() }
        val extraColumns =
            common
                .associateWith { t ->
                    val want = expected.getValue(t).map { it.lowercase() }.toSet()
                    actualCols(t).filter { it.lowercase() !in want }
                }.filterValues { it.isNotEmpty() }
        val rowCounts = actualTables.sorted().associateWith { t -> scalarLong(c, "SELECT COUNT(*) FROM \"$t\"") }

        val times = mutableListOf<FormatCounts>()
        val dates = mutableListOf<FormatCounts>()
        val raceIds = mutableListOf<FormatCounts>()
        val json = mutableListOf<FormatCounts>()
        for (t in common) {
            val tableName = byLower.getValue(t.lowercase())
            for (col in actualCols(t).filter { LegacySchema.IDENT.matches(it) }) {
                val lc = col.lowercase()
                when {
                    lc == "ts" || lc.endsWith("_at") -> times += counts(c, tableName, col, TIME_CASES)
                    lc == "date" || lc == "birth_date" || lc == "date_from" || lc == "date_to" -> dates += counts(c, tableName, col, DATE_CASES)
                    lc == "race_id" -> raceIds += counts(c, tableName, col, RACE_ID_CASES)
                    lc.endsWith("_json") || (t == "race_evaluations" && lc == "bet_types") -> json += counts(c, tableName, col, JSON_CASES)
                }
            }
        }
        val orphans =
            LegacySchema.foreignKeys
                .filter { fk ->
                    fk.table.lowercase() in byLower && fk.parent.lowercase() in byLower &&
                        actualCols(fk.table).any { it.equals(fk.column, true) } && actualCols(fk.parent).any { it.equals(fk.parentColumn, true) }
                }.map { fk ->
                    OrphanCount(
                        fk.table,
                        fk.column,
                        fk.parent,
                        orphanCount(c, byLower.getValue(fk.table.lowercase()), fk.column, byLower.getValue(fk.parent.lowercase()), fk.parentColumn),
                    )
                }
        val expectedLower = expected.keys.map { it.lowercase() }.toSet()
        return PreflightReport(
            sha256 = sha,
            sizeBytes = size,
            walMode = wal,
            integrity = integrity,
            missingTables = expected.keys.filter { it.lowercase() !in byLower }.sorted(),
            extraTables = allTables.filter { it.lowercase() !in expectedLower }.sorted(),
            missingColumns = missingColumns,
            extraColumns = extraColumns,
            rowCounts = rowCounts,
            times = times,
            dates = dates,
            raceIds = raceIds,
            json = json,
            orphans = orphans,
            primaryKeyMismatch =
                common.filter { t ->
                    val table = byLower.getValue(t.lowercase())
                    !LegacySchema.IDENT.matches(table) ||
                        LegacySchema.primaryKeyOf(c, table).map { it.lowercase() } != LegacySchema.primaryKeys.getValue(t).map { it.lowercase() }
                },
        )
    }

    private const val DATE_TIME = "[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9][0-9]:[0-9][0-9]:[0-9][0-9]"

    /** `%c` の 20 文字目以降が「.数字 1 個以上」で終わる（`n` 文字の接尾辞の前まで）。 */
    private fun fraction(suffixLen: Int): String =
        "(substr(%c, 20, 1) = '.' AND length(%c) > ${20 + suffixLen} AND substr(%c, 21, length(%c) - ${20 + suffixLen}) NOT GLOB '*[^0-9]*')"

    /** 分類名 → SQL の条件（`%c` を列に置き換える）。上から順に最初に当たった分類に数える。 */
    private val TIME_CASES =
        listOf(
            "null" to "%c IS NULL",
            // アプリの datetime.now().isoformat()（naive・Windows のローカル時刻 = 通常 JST）。秒の小数は数字だけ（レビュー 中: 末尾の任意の文字を許していた）
            "iso_t_naive" to "substr(%c, 1, 19) GLOB '$DATE_TIME' AND (length(%c) = 19 OR ${fraction(0)})",
            // SQLite の CURRENT_TIMESTAMP（UTC。小数なし）
            "space_utc" to "%c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9] [0-9][0-9]:[0-9][0-9]:[0-9][0-9]'",
            // タイムゾーン付き（想定外。解釈は本人が決めるので WARN）。+hh:mm または Z、その前に数字だけの小数は任意
            "with_offset" to
                "substr(%c, 1, 19) GLOB '$DATE_TIME' AND (" +
                "(substr(%c, -6) GLOB '[+-][0-9][0-9]:[0-9][0-9]' AND (length(%c) = 25 OR ${fraction(6)})) OR " +
                "(substr(%c, -1) = 'Z' AND (length(%c) = 20 OR ${fraction(1)})))",
            "other" to "1",
        )

    /** 月 01〜12・日 01〜31 の範囲まで見る（暦の妥当性は移行の変換で確かめる）。 */
    private const val MD8 = "CAST(substr(%c, 5, 2) AS INTEGER) BETWEEN 1 AND 12 AND CAST(substr(%c, 7, 2) AS INTEGER) BETWEEN 1 AND 31"
    private const val MD10 = "CAST(substr(%c, 6, 2) AS INTEGER) BETWEEN 1 AND 12 AND CAST(substr(%c, 9, 2) AS INTEGER) BETWEEN 1 AND 31"
    private val DATE_CASES =
        listOf(
            "null" to "%c IS NULL",
            // 数値として保存された 20261024 も、文字列としては同じ形なので yyyymmdd に数える（移行の変換で受け付ける）
            "yyyymmdd" to "length(%c) = 8 AND %c GLOB '[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]' AND $MD8",
            "yyyy_mm_dd" to "length(%c) = 10 AND %c GLOB '[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]' AND $MD10",
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
            "null" to "%c IS NULL",
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
    ): Long {
        require(listOf(table, column, parent, parentKey).all { LegacySchema.IDENT.matches(it) }) { "識別子の形式が不正" }
        return scalarLong(
            c,
            "SELECT COUNT(*) FROM \"$table\" ch WHERE ch.\"$column\" IS NOT NULL AND NOT EXISTS " +
                "(SELECT 1 FROM \"$parent\" p WHERE p.\"$parentKey\" = ch.\"$column\")",
        )
    }

    private fun scalarLong(
        c: Connection,
        sql: String,
    ): Long = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getLong(1) else 0 } }

    private fun scalarString(
        c: Connection,
        sql: String,
    ): String? = c.createStatement().use { s -> s.executeQuery(sql).use { rs -> if (rs.next()) rs.getString(1) else null } }

    internal fun sha256(p: Path): String {
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
