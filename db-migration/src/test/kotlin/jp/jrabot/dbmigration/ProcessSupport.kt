package jp.jrabot.dbmigration

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

class ProcessResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
)

/** 子プロセス（このテストと同じクラスパス）で CLI を起動する。終了コードと標準出力を実プロセスで確かめるために使う。 */
object ProcessSupport {
    fun launch(
        args: List<String>,
        env: Map<String, String>,
    ): ProcessResult {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val command = listOf(java, "-cp", System.getProperty("java.class.path"), "jp.jrabot.dbmigration.DbMigrationMainKt") + args
        val builder = ProcessBuilder(command)
        builder.environment().apply {
            clear()
            put("PATH", System.getenv("PATH") ?: "")
            put("LC_ALL", "C.UTF-8")
            putAll(env)
        }
        val out = File.createTempFile("dbm-out", ".txt")
        val err = File.createTempFile("dbm-err", ".txt")
        try {
            val process = builder.redirectOutput(out).redirectError(err).start()
            assertTrue(process.waitFor(120, TimeUnit.SECONDS), "CLI が 120 秒で終わらない")
            return ProcessResult(process.exitValue(), out.readText(), err.readText())
        } finally {
            out.delete()
            err.delete()
        }
    }
}
