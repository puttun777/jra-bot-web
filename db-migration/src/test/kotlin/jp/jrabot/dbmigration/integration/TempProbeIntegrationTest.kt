package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.Tag
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.fail

/** 一時的な診断（原因の特定後に削除する）: 自己参照の複合 FK が MySQL 9.7 で効かない理由を調べる。 */
@Tag("integration")
class TempProbeIntegrationTest {
    private fun h(n: Int) = n.toString().padStart(64, '0')

    private fun model(
        t: Int,
        id: String,
        prev: String?,
    ) = "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, status, created_at, previous_model_id) " +
        "VALUES ($t, '$id', 'alpha', 1, 'h', 'u', '${h(1)}', '${h(2)}', 'CANDIDATE', UTC_TIMESTAMP(6), ${prev?.let { "'$it'" } ?: "NULL"})"

    @Test
    fun probe() {
        val db = CliHarness.migratedDatabase()
        val out = StringBuilder("PROBE ${MySqlSupport.image} ${MySqlSupport.serverVersion()}\n")
        db.root().use { c ->
            c.run("INSERT INTO tenant (tenant_id, name, created_at) VALUES (1,'a',UTC_TIMESTAMP(6)),(2,'b',UTC_TIMESTAMP(6))")
            c.run(model(1, "m1", null))
            c.run(model(2, "m2", null))
            for ((label, sql) in listOf("cross" to model(2, "x1", "m1"), "nonexistent" to model(2, "x2", "nope"), "same" to model(2, "x3", "m2"), "cross2" to model(1, "x4", "m2"))) {
                val r =
                    try {
                        c.run(sql)
                        "OK"
                    } catch (e: SQLException) {
                        "ERR ${e.errorCode}"
                    }
                out.append("$label => $r\n")
            }
            c.createStatement().use { s ->
                s.executeQuery("SHOW CREATE TABLE ml_model").use { rs ->
                    rs.next()
                    out
                        .append(
                            rs
                                .getString(2)
                                .lines()
                                .filter { "KEY" in it || "CONSTRAINT" in it }
                                .joinToString("\n"),
                        ).append('\n')
                }
            }
            c.createStatement().use { s ->
                s.executeQuery("SELECT CONSTRAINT_NAME, ORDINAL_POSITION, COLUMN_NAME, REFERENCED_COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE WHERE TABLE_SCHEMA='${db.name}' AND TABLE_NAME='ml_model' AND REFERENCED_TABLE_NAME IS NOT NULL").use { rs ->
                    while (rs.next()) out.append("fk ${rs.getString(1)} ${rs.getInt(2)} ${rs.getString(3)} -> ${rs.getString(4)}\n")
                }
            }
        }
        fail(out.toString())
    }
}
