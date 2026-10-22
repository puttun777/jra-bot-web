package jp.jrabot.dbmigration

/** :db-migration の入口（独立した実行単位）。Flyway と `--mode=info|validate|migrate` は T2.3 で実装する。 */
object DbMigrationMain {
    const val NAME = "db-migration"
}

fun main() {
    println("${DbMigrationMain.NAME}: not implemented yet (T2.3)")
}
