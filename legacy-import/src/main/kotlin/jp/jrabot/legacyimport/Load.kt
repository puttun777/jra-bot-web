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
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPInputStream

/** 表ごとの読込みの結果（読み込んだ行数と DB の件数）。 */
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
 * 読込み（DATA_MIGRATION_DESIGN §5.2 P3・§5.3。D-275）: transform の出力を、Flyway（V1〜V5）を適用した MySQL に読み込む。
 *
 * - **利用者の PC の MySQL だけ**（接続先がループバックでなければ拒否: COMP-4。クラウドへの投入は回答待ち）。
 * - 新しい実行では、そのテナントの対象表が空であることを確かめる（既存の行と混ぜない）。
 * - チャンクごとに 1 トランザクション。状態ファイル（`--state`）に完了したチャンクを記録し、再実行は続きから進む。
 *   書き込み中に止まったチャンク（`in_progress`）だけは一意キーの重複を無視して入れ直す（同じ内容なので冪等）。それ以外で重複すれば失敗する。
 * - 親子の参照（`*_legacy_id`）は、読み込んだ親の新しい ID に副問合せで張り替える（親が先に読み込まれる順）。
 * - 隔離した行は `import_quarantine`（`migration_run` = transform の manifest の sha256）に入れる（旧表ごとに削除→挿入で冪等）。
 * - 最後に表ごとの件数を transform の manifest と照らす（§6.1 の件数の検証）。
 */
class Load(
    private val transformDir: Path,
    private val statePath: Path,
) {
    private val manifestBytes = Files.readAllBytes(transformDir.resolve(Transform.MANIFEST))
    private val manifestSha = CanonicalJson.hex(MessageDigest.getInstance("SHA-256").digest(manifestBytes))
    private val manifest = StrictJson.parse(String(manifestBytes, Charsets.UTF_8)) as? JsonObject ?: throw InputRejected("transform の manifest の形式が不正")
    private val tenantId = (manifest["tenant_id"] as? JsonInt)?.value ?: throw InputRejected("transform の manifest に tenant_id が無い")

    fun run(
        c: Connection,
        dbTarget: String,
    ): LoadResult {
        require((manifest["format"] as? JsonString)?.value == Transform.FORMAT) { "transform の manifest の版が違う" }
        val tables = (manifest["tables"] as? JsonArray)?.items?.map { it as JsonObject } ?: throw InputRejected("transform の manifest に表が無い")
        val qFiles = (manifest["quarantine_files"] as? JsonArray)?.items?.map { it as JsonObject } ?: emptyList()
        c.autoCommit = false
        c.createStatement().use {
            // 黙って切り詰め・変換させない（値の検査は transform で済んでいるが、DB の側でも厳格に）
            it.execute(
                "SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,ONLY_FULL_GROUP_BY,NO_ENGINE_SUBSTITUTION'",
            )
            it.execute("SET SESSION time_zone = '+00:00'")
        }
        c.commit()
        checkTenant(c)
        for (t in tables) checkColumns(c, str(t, "target"), strings(t, "columns"))
        var state: State =
            readState()?.also {
                if (it.transformSha256 != manifestSha || it.tenantId != tenantId || it.target != dbTarget) {
                    throw InputRejected("状態ファイルが別の transform・テナント・接続先のもの。別の --state を指定する")
                }
            } ?: run {
                for (t in tables) requireEmpty(c, str(t, "target"))
                requireEmptyQuarantine(c)
                State(manifestSha, tenantId, dbTarget, emptyList(), null).also(::writeState)
            }
        for (t in tables) {
            val target = str(t, "target")
            val columns = strings(t, "columns")
            for (ch in (t["chunks"] as JsonArray).items.map { it as JsonObject }) {
                val file = str(ch, "file")
                if (file in state.done) continue
                val retry = state.inProgress == file
                state = state.copy(inProgress = file).also(::writeState)
                loadChunk(c, target, columns, ch, ignoreDuplicates = retry)
                state = state.copy(done = state.done + file, inProgress = null).also(::writeState)
            }
        }
        for (q in qFiles) {
            val file = str(q, "file")
            if (file in state.done) continue
            state = state.copy(inProgress = file).also(::writeState)
            loadQuarantine(c, q)
            state = state.copy(done = state.done + file, inProgress = null).also(::writeState)
        }
        val loaded =
            tables.map { t ->
                LoadedTable(str(t, "target"), long(t, "rows"), count(c, "SELECT COUNT(*) FROM `${ident(str(t, "target"))}` WHERE tenant_id = ?", tenantId))
            }
        val qExpected = qFiles.sumOf { long(it, "rows") }
        val qActual = count(c, "SELECT COUNT(*) FROM import_quarantine WHERE tenant_id = ? AND migration_run = ?", tenantId, manifestSha)
        c.commit()
        return LoadResult(loaded, qExpected, qActual)
    }

    private fun loadChunk(
        c: Connection,
        target: String,
        columns: List<String>,
        ch: JsonObject,
        ignoreDuplicates: Boolean,
    ) {
        val (sql, params) = insertSql(target, columns, ignoreDuplicates)
        try {
            c.prepareStatement(sql).use { ps ->
                var n = 0
                readRows(ch) { row ->
                    if (row.size != columns.size) throw InputRejected("列の数が manifest と違う: ${str(ch, "file")}")
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
            c.commit()
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        }
    }

    private sealed interface Param {
        data class Col(
            val index: Int,
        ) : Param

        data object Tenant : Param
    }

    /** INSERT 文と、`?` に入れる値の並び。`*_legacy_id` は親の新しい ID への副問合せにする。 */
    private fun insertSql(
        target: String,
        columns: List<String>,
        ignoreDuplicates: Boolean,
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
        val first = names.first()
        val tail = if (ignoreDuplicates) " ON DUPLICATE KEY UPDATE $first = $first" else ""
        return "INSERT INTO `${ident(target)}` (${names.joinToString(", ")}) VALUES (${values.joinToString(", ")})$tail" to params
    }

    private fun loadQuarantine(
        c: Connection,
        q: JsonObject,
    ) {
        val createdAt = OUT.format(Instant.now().atOffset(ZoneOffset.UTC))
        val file = str(q, "file")
        val source = file.removePrefix("quarantine/").removeSuffix(".ndjson.gz")
        try {
            c.prepareStatement("DELETE FROM import_quarantine WHERE tenant_id = ? AND migration_run = ? AND source_table = ?").use {
                it.setLong(1, tenantId)
                it.setString(2, manifestSha)
                it.setString(3, source)
                it.executeUpdate()
            }
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
                        ps.setString(6, str(o, "reason_detail").take(500))
                        ps.setString(7, CanonicalJson.encode(o["payload"] ?: JsonNull))
                        ps.setString(8, createdAt)
                        ps.addBatch()
                        if (++n % BATCH == 0) ps.executeBatch()
                    }
                    ps.executeBatch()
                }
            c.commit()
        } catch (e: Exception) {
            runCatching { c.rollback() }
            throw e
        }
    }

    private fun readRows(
        ch: JsonObject,
        onRow: (List<JsonValue>) -> Unit,
    ) = readLines(transformDir, str(ch, "file"), long(ch, "rows"), str(ch, "sha256")) { v ->
        onRow((v as? JsonArray)?.items ?: throw InputRejected("行が配列ではない: ${str(ch, "file")}"))
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
            throw InputRejected("接続先の $target に、このテナントの行が既にある（新しい実行は空の DB に読み込む）")
        }
    }

    private fun requireEmptyQuarantine(c: Connection) {
        if (count(c, "SELECT COUNT(*) FROM import_quarantine WHERE tenant_id = ? AND migration_run = ?", tenantId, manifestSha) > 0) {
            throw InputRejected("接続先に、この transform の隔離の行が既にある（状態ファイルを失った再実行は受け付けない）")
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

    /** 状態ファイル（完了したチャンクの一覧）。transform の manifest・テナント・接続先と結びつける。 */
    data class State(
        val transformSha256: String,
        val tenantId: Long,
        val target: String,
        val done: List<String>,
        val inProgress: String?,
    )

    private fun readState(): State? {
        if (!Files.exists(statePath)) return null
        val o = StrictJson.parse(Files.readString(statePath)) as? JsonObject ?: throw InputRejected("状態ファイルの形式が不正")
        if ((o["format"] as? JsonString)?.value != STATE_FORMAT) throw InputRejected("状態ファイルの版が違う")
        return State(
            str(o, "transform_sha256"),
            long(o, "tenant_id"),
            str(o, "target"),
            strings(o, "done"),
            (o["in_progress"] as? JsonString)?.value,
        )
    }

    private fun writeState(s: State) {
        val json =
            JsonObject.of(
                "format" to JsonString(STATE_FORMAT),
                "transform_sha256" to JsonString(s.transformSha256),
                "tenant_id" to JsonInt(s.tenantId),
                "target" to JsonString(s.target),
                "done" to JsonArray(s.done.map { JsonString(it) }),
                "in_progress" to (s.inProgress?.let { JsonString(it) } ?: JsonNull),
            )
        val tmp = statePath.resolveSibling(statePath.fileName.toString() + ".tmp")
        Files.writeString(
            tmp,
            CanonicalJson.encode(json) + "\n",
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
            StandardOpenOption.SYNC,
        )
        Files.move(tmp, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private data class ParentRef(
        val column: String,
        val parent: String,
    )

    companion object {
        const val STATE_FORMAT = "legacy-import load-state v1"
        const val PASSWORD_ENV = "LEGACY_IMPORT_DB_PASSWORD"
        private const val BATCH = 1000
        private val OUT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSSSSS")

        /** transform の `*_legacy_id` → 新表の列と親の表（親の新しい ID に張り替える）。 */
        private val PARENT_REFS =
            mapOf(
                ("ticket_evaluations" to "race_evaluation_legacy_id") to ParentRef("race_evaluation_id", "race_evaluations"),
                ("bets" to "signal_legacy_id") to ParentRef("signal_id", "signals"),
                ("bet_legs" to "bet_legacy_id") to ParentRef("bet_id", "bets"),
            )

        /** 接続先がループバック（利用者の PC）か。`jdbc:mysql://host[:port]/...` の host を見る。 */
        fun isLoopback(jdbcUrl: String): Boolean {
            val m = Regex("""^jdbc:mysql://(\[[^\]]+]|[^/:?]+)(:\d+)?(/.*)?$""").matchEntire(jdbcUrl) ?: return false
            return m.groupValues[1].lowercase() in setOf("localhost", "127.0.0.1", "[::1]")
        }

        /** 状態ファイルに残す接続先（資格情報・パラメータを除く）。 */
        fun targetOf(jdbcUrl: String): String = jdbcUrl.substringBefore('?')

        internal fun ident(s: String): String = s.also { require(LegacySchema.IDENT.matches(it)) { "識別子の形式が不正" } }

        private fun str(
            o: JsonObject,
            k: String,
        ): String = (o[k] as? JsonString)?.value ?: throw InputRejected("manifest・状態の $k が文字列ではない")

        private fun long(
            o: JsonObject,
            k: String,
        ): Long = (o[k] as? JsonInt)?.value ?: throw InputRejected("manifest・状態の $k が整数ではない")

        private fun strings(
            o: JsonObject,
            k: String,
        ): List<String> = (o[k] as? JsonArray)?.items?.map { (it as? JsonString)?.value ?: throw InputRejected("$k の形式が不正") } ?: throw InputRejected("$k が無い")

        /** transform の出力（gzip の NDJSON）を、行数と展開後の sha256 を照らしながら読む。 */
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
            // 先に全体の sha256 を照らしてから読む（書き換えられたチャンクの一部を DB に入れない）
            DigestInputStream(GZIPInputStream(Files.newInputStream(p)), md).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
            if (CanonicalJson.hex(md.digest()) != sha256) throw InputRejected("transform のファイルが manifest と違う: $file")
            var n = 0L
            BufferedReader(GZIPInputStream(Files.newInputStream(p)).reader(Charsets.UTF_8)).use { r ->
                r.lineSequence().forEach { line ->
                    onLine(StrictJson.parse(line))
                    n++
                }
            }
            if (n != rows) throw InputRejected("transform のファイルの行数が manifest と違う: $file")
        }
    }
}
