package jp.jrabot.dbmigration.integration

import org.junit.jupiter.api.Tag
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.fail

/** 一時的な診断（原因の特定後に削除する）: 自己参照 FK の挙動を最小の表で MySQL 8.4 / 9.7 を比べる。 */
@Tag("integration")
class TempProbeIntegrationTest {
    @Test
    fun probe() {
        val db = MySqlSupport.newDatabase()
        val out = StringBuilder("PROBE ${MySqlSupport.image} ${MySqlSupport.serverVersion()}\n")
        db.root().use { c ->
            fun attempt(
                label: String,
                sql: String,
            ) {
                val r =
                    try {
                        c.run(sql)
                        "OK"
                    } catch (e: SQLException) {
                        "ERR ${e.errorCode}"
                    }
                out.append("$label => $r\n")
            }
            attempt("ddl", "CREATE TABLE s1 (a INT NOT NULL PRIMARY KEY, p INT NULL, KEY ix (p), CONSTRAINT f1 FOREIGN KEY (p) REFERENCES s1 (a)) ENGINE=InnoDB")
            attempt("single self FK, missing parent", "INSERT INTO s1 VALUES (1, 99)")
            attempt("ddl", "CREATE TABLE s2 (a INT NOT NULL, b INT NOT NULL, p INT NULL, PRIMARY KEY (a, b), KEY ix (a, p), CONSTRAINT f2 FOREIGN KEY (a, p) REFERENCES s2 (a, b)) ENGINE=InnoDB")
            attempt("composite self FK (a,p)->(a,b), missing parent", "INSERT INTO s2 VALUES (1, 1, 99)")
            attempt("ddl", "CREATE TABLE s3 (a INT NOT NULL, b INT NOT NULL, p INT NULL, PRIMARY KEY (a, b), UNIQUE KEY uq_ba (b, a), KEY ix (p, a), CONSTRAINT f3 FOREIGN KEY (p, a) REFERENCES s3 (b, a)) ENGINE=InnoDB")
            attempt("composite self FK to other unique key", "INSERT INTO s3 VALUES (1, 1, 99)")
            attempt("ddl", "CREATE TABLE s4 (a INT NOT NULL, b INT NOT NULL, p INT NULL, PRIMARY KEY (a, b)) ENGINE=InnoDB")
            attempt("ddl", "ALTER TABLE s4 ADD CONSTRAINT f4 FOREIGN KEY (a, p) REFERENCES s4 (a, b)")
            attempt("composite self FK added by ALTER", "INSERT INTO s4 VALUES (1, 1, 99)")
            attempt("ddl", "CREATE TABLE p5 (a INT NOT NULL, b INT NOT NULL, PRIMARY KEY (a, b)) ENGINE=InnoDB")
            attempt("ddl", "CREATE TABLE c5 (a INT NOT NULL, p INT NULL, KEY ix (a, p), CONSTRAINT f5 FOREIGN KEY (a, p) REFERENCES p5 (a, b)) ENGINE=InnoDB")
            attempt("composite FK to other table, missing parent", "INSERT INTO c5 VALUES (1, 99)")
            attempt("single self FK, UPDATE to missing parent", "INSERT INTO s1 VALUES (2, NULL)")
            attempt("  then update", "UPDATE s1 SET p = 98 WHERE a = 2")
            attempt("single self FK, delete parent", "DELETE FROM s1 WHERE a = 2")
            c.createStatement().use { s ->
                s.executeQuery("SELECT @@foreign_key_checks, @@sql_mode").use { rs ->
                    rs.next()
                    out.append("fk_checks=${rs.getString(1)} sql_mode=${rs.getString(2)}\n")
                }
            }
        }
        fail(out.toString())
    }
}
