package jp.jrabot.bot

import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.util.UUID

/**
 * 1 台の EC2 の上での Job の起動（AWS 構成。calendar-check が bot-run を起動する: R2 の AWS 版。D-283）。
 *
 * コンテナから Docker を操作しない（Docker のソケットを渡さない）。代わりに、ホストと共有する **spool ディレクトリ**へ起動依頼の JSON を
 * 1 件 1 ファイルで置き、ホストの運用エージェント（infra の `jrabot-host`）がそれを読んで、許可された Job だけを決まった形で起動する。
 *
 * - 書き方: 一時ファイルに書いて同じディレクトリの中で原子的に改名する（読み手が書きかけを見ない）。名前は `<job>-<時刻>-<乱数>.json`。
 * - 内容: `{"schema_version":1,"job":"bot-run","tenant_id":1,"params":{"date":"…","trigger":"SCHEDULER"},"requested_at":"…Z"}`。
 *   秘密を入れない（[JobLaunchRequest.params] のキーは `[a-z][a-z0-9-]*`、値はホスト側でも許可リストで検査する）。
 * - 冪等ではない（Cloud Run Jobs 版と同じ）。二重起動は起動された bot-run のリース（D-026）とホスト側の「同じ Job が動いていれば起動しない」で防ぐ。
 * - 失敗の分類: 書けない（容量・権限）は一時的な失敗（次の定期起動で再試行）。ML_TRAIN は扱わない（一時的でない失敗）。
 */
class SpoolJobLauncher(
    private val dir: Path,
    private val clock: () -> Instant = Instant::now,
    private val log: Logger = LoggerFactory.getLogger(SpoolJobLauncher::class.java),
) : JobLauncher {
    override fun launch(request: JobLaunchRequest): LaunchResult {
        if (request.job == LaunchableJob.ML_TRAIN) return LaunchResult.Failed("JOB_UNSUPPORTED", transient = false)
        val id = "${request.job.cliName}-${clock().toEpochMilli()}-${UUID.randomUUID().toString().take(8)}"
        val body = json(request)
        return try {
            val tmp = Files.createTempFile(dir, ".tmp-", ".part")
            try {
                Files.write(tmp, body.toByteArray(Charsets.UTF_8), StandardOpenOption.TRUNCATE_EXISTING)
                try {
                    Files.move(tmp, dir.resolve("$id.json"), StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    return LaunchResult.Failed("SPOOL_NOT_ATOMIC", transient = false)
                }
            } finally {
                Files.deleteIfExists(tmp)
            }
            LaunchResult.Launched(id)
        } catch (e: IOException) {
            log.error("SPOOL_LAUNCH io-error job={} error={}", request.job.cliName, e.javaClass.simpleName)
            LaunchResult.Failed("SPOOL_IO_ERROR", transient = true)
        }
    }

    private fun json(request: JobLaunchRequest): String {
        val params =
            request.params.entries
                .sortedBy { it.key }
                .joinToString(",") { (k, v) -> "${quote(k)}:${quote(v)}" }
        return """{"schema_version":1,"job":${quote(
            request.job.cliName,
        )},"tenant_id":${request.tenantId.value},"params":{$params},"requested_at":${quote(clock().toString())}}"""
    }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when {
                    c == '"' -> append("\\\"")
                    c == '\\' -> append("\\\\")
                    c.code < 0x20 || c.code == 0x7f -> append("\\u%04x".format(c.code))
                    else -> append(c)
                }
            }
            append('"')
        }

    companion object {
        /** `JOB_SPOOL_DIR`: 既存のディレクトリの絶対パス（ホストと共有するボリューム）。不正なら [IllegalStateException]（終了コード 2。値は出さない）。 */
        fun validateDir(raw: String): Path {
            val p = runCatching { Path.of(raw.trim()) }.getOrNull()
            check(p != null && p.isAbsolute && p.normalize() == p) { "JOB_SPOOL_DIR は正規化された絶対パスにしてください" }
            return p
        }
    }
}
