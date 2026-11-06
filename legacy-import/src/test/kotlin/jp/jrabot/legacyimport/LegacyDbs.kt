package jp.jrabot.legacyimport

import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager

/** 試験用の合成の旧 SQLite（期待値と同じリソースのスキーマ。実データは使わない）。 */
object LegacyDbs {
    fun create(
        p: Path,
        seed: (Connection) -> Unit = {},
    ): Path {
        DriverManager.getConnection("jdbc:sqlite:$p").use { c ->
            for (file in listOf("schema.sql", "migration_add_bets.sql")) {
                val sql = checkNotNull(javaClass.getResource("/legacy-schema/$file")).readText()
                for (stmt in LegacySchema.statements(sql)) c.createStatement().use { it.executeUpdate(stmt) }
            }
            for (line in checkNotNull(javaClass.getResource("/legacy-schema/dynamic-columns.tsv")).readText().lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val (t, col, type) = line.split('\t')
                if (col !in LegacySchema.columns(c, t)) c.createStatement().use { it.executeUpdate("ALTER TABLE $t ADD COLUMN $col $type") }
            }
            seed(c)
        }
        return p
    }

    fun Connection.exec(sql: String) = createStatement().use { it.executeUpdate(sql) }

    fun sha(p: Path) =
        MessageDigest
            .getInstance("SHA-256")
            .digest(
                java.nio.file.Files
                    .readAllBytes(p),
            ).joinToString("") { "%02x".format(it) }
}
