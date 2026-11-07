package jp.jrabot.legacyimport

import java.sql.Connection
import java.sql.DriverManager

/** 外部キー（子の表・列 → 親の表・列）。期待するスキーマの FOREIGN KEY 句から得る。 */
data class ForeignKey(
    val table: String,
    val column: String,
    val parent: String,
    val parentColumn: String,
)

/**
 * 旧 SQLite の期待するスキーマ（D-271）。参照元 f7dbf69 の `schema.sql`・`migration_add_bets.sql` と、起動時に追加される列（`dynamic-columns.tsv`）を
 * メモリ上の SQLite に適用し、`PRAGMA table_info` で表と列を得る（列の一覧を手で写さない）。
 */
object LegacySchema {
    /** 表名 → 列名（定義順）。 */
    val expected: Map<String, List<String>> get() = model.tables

    /**
     * 期待するスキーマの外部キー（`PRAGMA foreign_key_list`）。孤立の検査はここに宣言された関係だけを見る
     * （手で規則を書かない。例えば horse_past_runs.race_id は races への外部キーではない: レビュー 中）。
     */
    val foreignKeys: List<ForeignKey> get() = model.foreignKeys

    /** 表名 → 主キーの列（主キー内の順）。抽出はこの順に並べる（D-273）。 */
    val primaryKeys: Map<String, List<String>> get() = model.primaryKeys

    /** 起動時に足される列（表 → 列）。これが無いだけの古い DB は WARN、それ以外の列が無ければ NG。 */
    val dynamicColumns: Map<String, Set<String>> by lazy {
        resource("dynamic-columns.tsv")
            .lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t') }
            .groupBy({ it[0] }, { it[1] })
            .mapValues { it.value.toSet() }
    }

    private class Model(
        val tables: Map<String, List<String>>,
        val foreignKeys: List<ForeignKey>,
        val primaryKeys: Map<String, List<String>>,
    )

    private val model: Model by lazy { load() }

    private fun resource(name: String): String = checkNotNull(LegacySchema::class.java.getResource("/legacy-schema/$name")) { "リソース $name が無い" }.readText()

    private fun load(): Model =
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            for (file in listOf("schema.sql", "migration_add_bets.sql")) {
                for (stmt in statements(resource(file))) c.createStatement().use { it.executeUpdate(stmt) }
            }
            for (line in resource("dynamic-columns.tsv").lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (table, column, type) = line.split('\t')
                if (column !in columns(c, table)) c.createStatement().use { it.executeUpdate("ALTER TABLE $table ADD COLUMN $column $type") }
            }
            val ts = tables(c)
            Model(ts.associateWith { columns(c, it) }, ts.flatMap { foreignKeys(c, it) }, ts.associateWith { primaryKey(c, it) })
        }

    private fun foreignKeys(
        c: Connection,
        table: String,
    ): List<ForeignKey> =
        c.createStatement().use { s ->
            s.executeQuery("PRAGMA foreign_key_list(\"$table\")").use { rs ->
                buildList { while (rs.next()) add(ForeignKey(table, rs.getString("from"), rs.getString("table"), rs.getString("to") ?: "rowid")) }
            }
        }

    /** 実ファイルの表の主キーの列（主キー内の順）。 */
    internal fun primaryKeyOf(
        c: Connection,
        table: String,
    ): List<String> = primaryKey(c, table.also { require(IDENT.matches(it)) { "表名の形式が不正" } })

    private fun primaryKey(
        c: Connection,
        table: String,
    ): List<String> =
        c
            .createStatement()
            .use { s ->
                s.executeQuery("PRAGMA table_info(\"$table\")").use { rs ->
                    buildList { while (rs.next()) if (rs.getInt("pk") > 0) add(rs.getInt("pk") to rs.getString("name")) }
                }
            }.sortedBy { it.first }
            .map { it.second }

    /** `;` で区切った文（行コメント `--` を除く）。旧スキーマの DDL には文字列中の `;` が無い。 */
    internal fun statements(sql: String): List<String> =
        sql
            .lines()
            .joinToString("\n") { it.substringBefore("--") }
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    internal fun tables(c: Connection): List<String> =
        c.createStatement().use { s ->
            s.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' ORDER BY name").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }

    internal fun columns(
        c: Connection,
        table: String,
    ): List<String> {
        require(IDENT.matches(table)) { "表名の形式が不正" }
        return c.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(\"$table\")").use { rs -> buildList { while (rs.next()) add(rs.getString("name")) } }
        }
    }

    /** SQL に埋め込む識別子の形式（表名・列名はスキーマから得るが、念のため検査する）。 */
    val IDENT = Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")
}
