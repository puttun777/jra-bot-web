package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonBool
import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import jp.jrabot.domain.ml.json.JsonValue
import jp.jrabot.domain.ml.json.StrictJson
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.zip.GZIPInputStream

/** 表ごとの読込みの結果（transform の行数と DB の件数）。 */
data class LoadedTable(
    val target: String,
    val expected: Long,
    val actual: Long,
)

data class LoadResult(
    val tables: List<LoadedTable>,
    val quarantineExpected: Long,
    val quarantineActual: Long,
) {
    val ok: Boolean get() = tables.all { it.expected == it.actual } && quarantineExpected == quarantineActual
}

/**
 * 読込み（DATA_MIGRATION_DESIGN §5.2 P3・§5.3。D-275）: transform の出力を、Flyway（V1〜V5）を適用した利用者の PC の MySQL に読み込む。
 *
 * - **接続先は利用者の PC の MySQL だけ**（[checkUrl]: ループバック・許可したパラメータだけ。接続後に Cloud SQL なら拒否: COMP-4）。
 * - **再開は DB の台帳で決める**: 読み込んだチャンクを、データと同じトランザクションで台帳の表 [LEDGER] に記録する。再実行は台帳にある
 *   チャンクを飛ばすので、状態ファイルと DB が食い違うことが無い（D-275 のレビュー 中: 状態ファイルを信じて重複を黙って捨て得た）。
 *   一意キーの重複は常に失敗にする（黙って捨てない）。すべての検証に通ったら台帳の表を消す（Flyway の外の表を残さない）。
 * - 新しい実行（台帳にこの実行の行が無い）では、そのテナントの対象表と、この実行の隔離の行が空であることを確かめる。
 * - 表と列は transform の変換の表（[Transform.outputColumns]）と完全に一致するものだけ（manifest の書き換えで別の表に入れない）。
 * - 親子の参照（`*_legacy_id`）は、読み込んだ親の新しい ID に副問合せで張り替える（親が先に読み込まれる順）。
 * - 隔離した行は `import_quarantine`（`migration_run` = transform の manifest の sha256）へ。
 * - 最後に表ごとの件数を transform の manifest と照らす（§6.1 の件数の検証）。
 */
class Load(
    private val transformDir: Path,
) {
    private val manifestBytes = Files.readAllBytes(transformDir.resolve(Transform.MANIFEST))
    private val manifestSha = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes))
    private val manifest = StrictJson.parse(String(manifestBytes, Charsets.UTF_8)) as? JsonObject ?: throw InputRejected("transform の manifest の形式が不正")
    private val tenantId = (manifest["tenant_id"] as? JsonInt)?.value ?: throw InputRejected("transform の manifest に tenant_id が無い")

    fun run(c: Connection): LoadResult {
        if ((manifest["format"] as? JsonString)?.value != Transform.FORMAT) throw InputRejected("transform の manifest の版が違う")
        val tables =
            (manifest["tables"] as? JsonArray)?.items?.map { it as? JsonObject ?: throw InputRejected("manifest の表の形式が不正") }
                ?: throw InputRejected("transform の manifest に表が無い")
        val qFiles = (manifest["quarantine_files"] as? JsonArray)?.items?.map { it as? JsonObject ?: throw InputRejected("manifest の隔離の形式が不正") } ?: emptyList()
        checkAgainstTransform(tables)
        c.autoCommit = false
        c.createStatement().use {
            // 黙って切り詰め・変換させない（値の検査は transform で済んでいるが、DB の側でも厳格に）
            it.execute(
                "SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,ONLY_FULL_GROUP_BY,NO_ENGINE_SUBSTITUTION'",
            )
            it.execute("SET SESSION time_zone = '+00:00'")
        }
        c.commit()
        refuseCloudSql(c)
        checkTenant(c)
        for (t in tables) checkColumns(c, str(t, "target"), strings(t, "columns"))
        c.createStatement().use {
            it.execute(
                "CREATE TABLE IF NOT EXISTS $LEDGER (migration_run CHAR(64) CHARACTER SET ascii NOT NULL, file VARCHAR(255) CHARACTER SET ascii NOT NULL, " +
                    "tenant_id BIGINT NOT NULL, row_count BIGINT NOT NULL, loaded_at DATETIME(6) NOT NULL, PRIMARY KEY (migration_run, file)) ENGINE = InnoDB",
            )
        }
        c.commit()
        val done = ledger(c)
        if (done.isEmpty()) {
            if (count(c, "SELECT COUNT(*) FROM $LEDGER WHERE migration_run <> ?", manifestSha) > 0) {
                throw InputRejected("接続先に別の transform の読込みの途中の記録がある（その実行を終えるか、新しい DB に読み込む）")
            }
            for (t in tables) requireEmpty(c, str(t, "target"))
            requireEmptyQuarantine(c)
        }
        for (t in tables) {
            val target = str(t, "target")
            val columns = strings(t, "columns")
            val chunks = (t["chunks"] as? JsonArray)?.items?.map { it as? JsonObject ?: throw InputRejected("manifest のチャンクの形式が不正") } ?: emptyList()
            for (ch in chunks) {
                if (str(ch, "file") !in done) loadChunk(c, target, columns, ch)
            }
        }
        for (q in qFiles) {
            if (str(q, "file") !in done) loadQuarantine(c, q)
        }
        val loaded =
            tables.map { t ->
                LoadedTable(str(t, "target"), long(t, "rows"), count(c, "SELECT COUNT(*) FROM `${ident(str(t, "target"))}` WHERE tenant_id = ?", tenantId))
            }
        val qExpected = qFiles.sumOf { long(it, "rows") }
        val qActual = count(c, "SELECT COUNT(*) FROM import_quarantine WHERE tenant_id = ? AND migration_run = ?", tenantId, manifestSha)
        val result = LoadResult(loaded, qExpected, qActual)
        if (result.ok) {
            // すべて読み込み件数も一致したら台帳を片付ける（Flyway の外の表を DB に残さない）。食い違いがあれば調べられるように残す
            c.prepareStatement("DELETE FROM $LEDGER WHERE migration_run = ?").use {
                it.setString(1, manifestSha)
                it.executeUpdate()
            }
            c.commit()
            if (count(c, "SELECT COUNT(*) FROM $LEDGER") == 0L) c.createStatement().use { it.execute("DROP TABLE $LEDGER") }
        }
        c.commit()
        return result
    }

    /** manifest の表・列が transform の変換の表と完全に一致すること（書き換えられた manifest で別の表・列に入れない）。 */
    private fun checkAgainstTransform(tables: List<JsonObject>) {
        val expected = Transform.outputColumns()
        val seen = mutableSetOf<String>()
        for (t in tables) {
            val target = str(t, "target")
            val want = expected[target] ?: throw InputRejected("manifest に変換の表に無い表がある")
            if (!seen.add(target)) throw InputRejected("manifest に同じ表が 2 回ある: $target")
            if (strings(t, "columns") != want) throw InputRejected("manifest の列が変換の表と違う: $target")
        }
        if (seen != expected.keys) throw InputRejected("manifest の表の集合が変換の表と違う")
    }

    private fun loadChunk(
        c: Connection,
        target: String,
        columns: List<String>,
        ch: JsonObject,
    ) {
        val (sql, params) = insertSql(target, columns)
        val file = str(ch, "file")
        try {
            c.prepareStatement(sql).use { ps ->
                var n = 0
                readLines(transformDir, file, long(ch, "rows"), str(ch, "sha256")) { v ->
                    val row = (v as? JsonArray)?.items ?: throw InputRejected("行が配列ではない: $file")
                    if (row.size != columns.size) throw InputRejected("列の数が manifest と違う: $file")
                    var i = 1
                    for (p in params) {
                        when (p) {
                            is Param.Col -> bind(ps, i++, row[p.index])
                            is Param.Tenant -> ps.setLong(i++, tenantId)
                        }
                    }
                    ps.addBatch()
                    if (++n % BATCH == 0) ps.executeBatch()
                }
                ps.executeBatch()
            }
            markDone(c, file, long(ch, "rows"))
            c.commit()
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        }
    }

    /** 台帳に記録する（データと同じトランザクション）。 */
    private fun markDone(
        c: Connection,
        file: String,
        rows: Long,
    ) {
        c.prepareStatement("INSERT INTO $LEDGER (migration_run, file, tenant_id, row_count, loaded_at) VALUES (?, ?, ?, ?, ?)").use {
            it.setString(1, manifestSha)
            it.setString(2, file)
            it.setLong(3, tenantId)
            it.setLong(4, rows)
            it.setString(5, OUT.format(Instant.now().atOffset(ZoneOffset.UTC)))
            it.executeUpdate()
        }
    }

    private fun ledger(c: Connection): Set<String> =
        c.prepareStatement("SELECT file, tenant_id FROM $LEDGER WHERE migration_run = ?").use { ps ->
            ps.setString(1, manifestSha)
            ps.executeQuery().use { rs ->
                buildSet {
                    while (rs.next()) {
                        if (rs.getLong(2) != tenantId) throw InputRejected("台帳のテナントが transform と違う")
                        add(rs.getString(1))
                    }
                }
            }
        }

    private sealed interface Param {
        data class Col(
            val index: Int,
        ) : Param

        data object Tenant : Param
    }

    /** INSERT 文と、`?` に入れる値の並び。`*_legacy_id` は親の新しい ID への副問合せにする。重複は常に失敗（黙って捨てない）。 */
    private fun insertSql(
        target: String,
        columns: List<String>,
    ): Pair<String, List<Param>> {
        val names = mutableListOf<String>()
        val values = mutableListOf<String>()
        val params = mutableListOf<Param>()
        columns.forEachIndexed { i, col ->
            val ref = PARENT_REFS[target to col]
            if (ref == null) {
                names += "`${ident(col)}`"
                values += "?"
                params += Param.Col(i)
            } else {
                names += "`${ident(ref.column)}`"
                values += "(SELECT p.id FROM `${ident(ref.parent)}` p WHERE p.tenant_id = ? AND p.legacy_id = ?)"
                params += Param.Tenant
                params += Param.Col(i)
            }
        }
        return "INSERT INTO `${ident(target)}` (${names.joinToString(", ")}) VALUES (${values.joinToString(", ")})" to params
    }

    private fun loadQuarantine(
        c: Connection,
        q: JsonObject,
    ) {
        val createdAt = OUT.format(Instant.now().atOffset(ZoneOffset.UTC))
        val file = str(q, "file")
        val source = file.removePrefix("quarantine/").removeSuffix(".ndjson.gz")
        if (file != "quarantine/$source.ndjson.gz" || source !in LegacySchema.expected.keys) throw InputRejected("隔離のファイル名が不正")
        try {
            c
                .prepareStatement(
                    "INSERT INTO import_quarantine (tenant_id, migration_run, source_table, source_key, reason_code, reason_detail, payload, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { ps ->
                    var n = 0
                    readLines(transformDir, file, long(q, "rows"), str(q, "sha256")) { v ->
                        val o = v as? JsonObject ?: throw InputRejected("隔離の行の形式が不正: $file")
                        if (str(o, "source_table") != source) throw InputRejected("隔離の行の表が違う: $file")
                        ps.setLong(1, tenantId)
                        ps.setString(2, manifestSha)
                        ps.setString(3, source)
                        ps.setString(4, str(o, "source_key"))
                        ps.setString(5, str(o, "reason_code"))
                        ps.setString(6, takeCodePoints(str(o, "reason_detail"), 500))
                        ps.setString(7, CanonicalJson.encode(o["payload"] ?: JsonNull))
                        ps.setString(8, createdAt)
                        ps.addBatch()
                        if (++n % BATCH == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            markDone(c, file, long(q, "rows"))
            c.commit()
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        }
    }

    private fun bind(
        ps: PreparedStatement,
        i: Int,
        v: JsonValue,
    ) {
        when (v) {
            JsonNull -> ps.setNull(i, Types.NULL)
            is JsonString -> ps.setString(i, v.value)
            is JsonInt -> ps.setLong(i, v.value)
            is JsonFloat -> ps.setDouble(i, v.value)
            is JsonBool -> ps.setBoolean(i, v.value)
            else -> throw InputRejected("transform の値の型が想定外")
        }
    }

    /** Cloud SQL（の転送・トンネル越し）なら拒否する（COMP-4。接続後に DB の側で確かめる: D-275 のレビュー 中）。 */
    private fun refuseCloudSql(c: Connection) {
        val cloudSql = c.createStatement().use { s -> s.executeQuery("SHOW GLOBAL VARIABLES LIKE 'cloudsql%'").use { it.next() } }
        if (cloudSql) {
            throw InputRejected("接続先が Cloud SQL に見える（cloudsql の変数がある）。クラウドへの投入は COMP-4 の回答まで行わない")
        }
    }

    private fun checkTenant(c: Connection) {
        if (count(c, "SELECT COUNT(*) FROM tenant WHERE tenant_id = ?", tenantId) != 1L) throw InputRejected("テナントが無い（tenant_id は transform の --tenant-id）")
    }

    /** 対象の表に transform の列（親の参照は張り替え後の列）があること（Flyway を適用した DB か）。 */
    private fun checkColumns(
        c: Connection,
        target: String,
        columns: List<String>,
    ) {
        val have = mutableSetOf<String>()
        c.prepareStatement("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = ?").use { ps ->
            ps.setString(1, target)
            ps.executeQuery().use { rs -> while (rs.next()) have += rs.getString(1).lowercase() }
        }
        val want = columns.map { col -> PARENT_REFS[target to col]?.column ?: col }
        val missing = want.filter { it.lowercase() !in have }
        if (missing.isNotEmpty()) throw InputRejected("接続先の表 $target に列が無い（Flyway の V1〜V5 を適用した DB か確かめる）: ${missing.joinToString()}")
    }

    private fun requireEmpty(
        c: Connection,
        target: String,
    ) {
        if (count(c, "SELECT COUNT(*) FROM (SELECT 1 FROM `${ident(target)}` WHERE tenant_id = ? LIMIT 1) x", tenantId) > 0) {
            throw InputRejected("接続先の $target に、このテナントの行が既にある（新しい実行は空の DB に読み込む。読込み済みなら再実行は不要）")
        }
    }

    private fun requireEmptyQuarantine(c: Connection) {
        if (count(c, "SELECT COUNT(*) FROM import_quarantine WHERE tenant_id = ? AND migration_run = ?", tenantId, manifestSha) > 0) {
            throw InputRejected("接続先に、この transform の隔離の行が既にある（読込み済みなら再実行は不要）")
        }
    }

    private fun count(
        c: Connection,
        sql: String,
        vararg args: Any,
    ): Long =
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Long -> ps.setLong(i + 1, a)
                    else -> ps.setString(i + 1, a.toString())
                }
            }
            ps.executeQuery().use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private data class ParentRef(
        val column: String,
        val parent: String,
    )

    companion object {
        const val PASSWORD_ENV = "LEGACY_IMPORT_DB_PASSWORD"

        /** 読込みの台帳（Flyway の外。すべての検証に通ったら消す）。 */
        const val LEDGER = "legacy_import_chunk"
        private const val BATCH = 1000
        private val OUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")

        /** transform の `*_legacy_id` → 新表の列と親の表（親の新しい ID に張り替える）。 */
        private val PARENT_REFS =
            mapOf(
                ("ticket_evaluations" to "race_evaluation_legacy_id") to ParentRef("race_evaluation_id", "race_evaluations"),
                ("bets" to "signal_legacy_id") to ParentRef("signal_id", "signals"),
                ("bet_legs" to "bet_legacy_id") to ParentRef("bet_id", "bets"),
            )

        /** URL に付けてよいパラメータ（接続の経路を変えるもの・ファイルを読むもの等は拒否: D-275 のレビュー 中）。 */
        private val ALLOWED_PARAMS = setOf("sslmode", "usessl", "allowpublickeyretrieval", "connecttimeout", "sockettimeout", "characterencoding")

        /**
         * 接続先の URL を検査し、拒否の理由（null なら受け付ける）を返す。`jdbc:mysql://` に host が 1 つ、それがループバック
         * （localhost・127.0.0.1・[::1]）で、パラメータは [ALLOWED_PARAMS] だけ（SOCKS・名前付きパイプ・ソケットの差し替えで別のサーバへ行かない）。
         */
        fun checkUrl(jdbcUrl: String): String? {
            val m =
                Regex("""^jdbc:mysql://(\[[^\]]+]|[^/:?,@()\[\]]+)(:\d{1,5})?/([A-Za-z0-9_]+)(\?(.*))?$""").matchEntire(jdbcUrl)
                    ?: return "接続先の URL は jdbc:mysql://localhost:<port>/<db>[?sslMode=...] の形だけ"
            if (m.groupValues[1].lowercase() !in setOf("localhost", "127.0.0.1", "[::1]")) {
                return "接続先は利用者の PC の MySQL（localhost・127.0.0.1・[::1]）だけ。クラウドへの投入は COMP-4 の回答まで行わない"
            }
            val query = m.groupValues[5]
            if (query.isNotEmpty()) {
                for (p in query.split('&')) {
                    if (p.substringBefore('=').lowercase() !in ALLOWED_PARAMS) {
                        return "URL に使えないパラメータがある（許可: sslMode・useSSL・allowPublicKeyRetrieval・connectTimeout・socketTimeout・characterEncoding）"
                    }
                }
            }
            return null
        }

        /** 接続の属性。ローカルのファイルの読込み・逆シリアル化を明示的に無効にする。 */
        fun connectionProperties(
            user: String,
            password: String,
        ): Properties =
            Properties().apply {
                setProperty("user", user)
                setProperty("password", password)
                setProperty("allowLoadLocalInfile", "false")
                setProperty("allowUrlInLocalInfile", "false")
                setProperty("autoDeserialize", "false")
            }

        internal fun ident(s: String): String = s.also { require(LegacySchema.IDENT.matches(it)) { "識別子の形式が不正" } }

        private fun takeCodePoints(
            s: String,
            n: Int,
        ): String = if (s.codePointCount(0, s.length) <= n) s else s.substring(0, s.offsetByCodePoints(0, n))

        private fun str(
            o: JsonObject,
            k: String,
        ): String = (o[k] as? JsonString)?.value ?: throw InputRejected("manifest の $k が文字列ではない")

        private fun long(
            o: JsonObject,
            k: String,
        ): Long = (o[k] as? JsonInt)?.value ?: throw InputRejected("manifest の $k が整数ではない")

        private fun strings(
            o: JsonObject,
            k: String,
        ): List<String> = (o[k] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: throw InputRejected("$k の形式が不正") } ?: throw InputRejected("$k が無い")

        /**
         * transform の出力（gzip の NDJSON）を読み、展開後の sha256 と行数を最後に照らす（1 回の読込み。食い違えば例外で、
         * 呼び出し側のトランザクションは commit されずに戻る: D-275 のレビュー 低）。
         */
        internal fun readLines(
            dir: Path,
            file: String,
            rows: Long,
            sha256: String,
            onLine: (JsonValue) -> Unit,
        ) {
            val root = dir.toRealPath()
            val p = root.resolve(file).normalize()
            if (!p.startsWith(root) || !Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw InputRejected("transform のファイルが無い: $file")
            val md = MessageDigest.getInstance("SHA-256")
            var n = 0L
            BufferedReader(DigestInputStream(GZIPInputStream(Files.newInputStream(p)), md).reader(Charsets.UTF_8)).use { r ->
                r.lineSequence().forEach { line ->
                    onLine(StrictJson.parse(line))
                    n++
                }
            }
            if (n != rows || CanonicalJson.hex(md.digest()) != sha256) throw InputRejected("transform のファイルが manifest と違う: $file")
        }
    }
}
