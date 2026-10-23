package jp.jrabot.dbmigration

import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.PrintStream
import kotlin.system.exitProcess

/** :db-migration の入口（独立した実行単位。アプリの起動時には実行しない: CONTRACTS §4.1）。 */
object DbMigrationMain {
    const val NAME = "db-migration"
}

fun main(args: Array<String>) {
    // 標準出力は JSON 専用。Flyway など他のコードが標準出力へ書いても混ざらないよう、System.out を標準エラーへ向ける。
    val jsonOut = PrintStream(FileOutputStream(FileDescriptor.out), false, Charsets.UTF_8)
    val diagnostics = PrintStream(FileOutputStream(FileDescriptor.err), true, Charsets.UTF_8)
    System.setOut(diagnostics)
    System.setErr(diagnostics)
    exitProcess(Cli().run(args.toList(), System.getenv(), jsonOut, diagnostics))
}
