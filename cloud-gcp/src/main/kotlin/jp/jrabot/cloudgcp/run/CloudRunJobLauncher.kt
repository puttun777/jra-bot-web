package jp.jrabot.cloudgcp.run

import com.google.auth.oauth2.GoogleCredentials
import jp.jrabot.domain.bot.JobLaunchRequest
import jp.jrabot.domain.bot.JobLauncher
import jp.jrabot.domain.bot.LaunchResult
import jp.jrabot.domain.bot.LaunchableJob
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration

/**
 * Cloud Run Jobs の起動（REST v2 `projects.locations.jobs.run`）。calendar-check が bot-run などを起動する本番の実装（R2）。
 *
 * - **Job の定義（イメージ・環境変数・Secret・SA）は変えない**。引数だけを上書きする（`--task=<job>`、`--tenant-id=<n>`、依頼の [JobLaunchRequest.params] を `--キー=値`）。
 *   上書きは定義の args を**丸ごと置き換える**ので、`--task` と `--tenant-id` は必ずここで付ける（テナント境界）。
 * - 呼び出し側のサービスアカウントには、対象の Job への `roles/run.jobsExecutorWithOverrides` が必要（infra で Job 単位に付与。プロジェクト全体には付けない）。
 * - 認証は ADC のアクセストークン（長期キーなし）。トークンやレスポンス本文を **ログ・理由に出さない**（理由は固定語彙 + HTTP ステータス）。
 * - 失敗の分類: 通信の失敗・タイムアウト・429・5xx は一時的（[LaunchResult.Failed.transient] = true。次の定期起動で再試行）、400/401/403/404 などは設定の誤りで一時的でない。
 *   **冪等ではない**（呼ぶたびに実行が増え得る。二重実行は起動された側のリースが防ぐ: D-026）。完了待ち・結果の取得はしない。
 * - ML_TRAIN は引数の形式が違う（Python の CLI）ので、この起動器では扱わない（呼ばれたら一時的でない失敗）。
 */
class CloudRunJobLauncher(
    private val projectId: String,
    private val region: String,
    private val jobNames: Map<LaunchableJob, String>,
    private val tokenProvider: () -> String,
    private val baseUrl: String = "https://run.googleapis.com",
    private val timeout: Duration = Duration.ofSeconds(15),
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(timeout).build(),
    private val log: Logger = LoggerFactory.getLogger(CloudRunJobLauncher::class.java),
) : JobLauncher {
    init {
        require(Regex("[a-z][a-z0-9-]{4,28}[a-z0-9]").matches(projectId)) { "projectId の形式が不正" }
        require(Regex("[a-z]+-[a-z]+[0-9]+").matches(region)) { "region の形式が不正" }
        require(jobNames.values.all { Regex("[a-z][a-z0-9-]{0,48}[a-z0-9]").matches(it) }) { "Job 名の形式が不正" }
    }

    private val mapper: JsonMapper = JsonMapper.builder().build()

    override fun launch(request: JobLaunchRequest): LaunchResult {
        val name = jobNames[request.job] ?: return LaunchResult.Failed("JOB_NOT_CONFIGURED", transient = false)
        if (request.job == LaunchableJob.ML_TRAIN) return LaunchResult.Failed("JOB_UNSUPPORTED", transient = false)
        val args = listOf("--task=${request.job.cliName}", "--tenant-id=${request.tenantId.value}") + request.params.map { (k, v) -> "--$k=$v" }
        val body =
            mapper.writeValueAsString(
                mapOf("overrides" to mapOf("taskCount" to 1, "containerOverrides" to listOf(mapOf("args" to args)))),
            )
        val token =
            try {
                tokenProvider()
            } catch (e: Exception) {
                log.error("CLOUD_RUN_LAUNCH token-unavailable job={} error={}", request.job.cliName, e.javaClass.simpleName)
                return LaunchResult.Failed("TOKEN_UNAVAILABLE", transient = true)
            }
        val uri = URI.create("$baseUrl/v2/projects/$projectId/locations/$region/jobs/$name:run")
        val httpRequest =
            HttpRequest
                .newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val response =
            try {
                http.send(httpRequest, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                return LaunchResult.Failed("LAUNCH_TIMEOUT", transient = true)
            } catch (e: IOException) {
                log.error("CLOUD_RUN_LAUNCH io-error job={} error={}", request.job.cliName, e.javaClass.simpleName)
                return LaunchResult.Failed("LAUNCH_IO_ERROR", transient = true)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return LaunchResult.Failed("LAUNCH_INTERRUPTED", transient = true)
            }
        val status = response.statusCode()
        if (status in 200..299) return LaunchResult.Launched(executionId(response.body()))
        log.error("CLOUD_RUN_LAUNCH rejected job={} status={}", request.job.cliName, status)
        val transient = status == 429 || status in 500..599
        return LaunchResult.Failed("LAUNCH_HTTP_$status", transient)
    }

    /** 応答は長時間操作。`metadata.name`（実行の名前）があればその末尾、無ければ操作名の末尾。どちらも無ければ不明（起動自体は受け付けられている）。 */
    private fun executionId(body: String): String =
        try {
            val root = mapper.readTree(body)
            val name =
                root
                    .path("metadata")
                    .path("name")
                    .asString("")
                    .ifEmpty { root.path("name").asString("") }
            name.substringAfterLast('/').ifEmpty { "unknown" }
        } catch (_: Exception) {
            "unknown"
        }

    companion object {
        /** 本番: Application Default Credentials（Cloud Run のランタイム SA）で `cloud-platform` スコープのトークンを取る。 */
        fun adcTokenProvider(): () -> String {
            val credentials = GoogleCredentials.getApplicationDefault().createScoped("https://www.googleapis.com/auth/cloud-platform")
            return {
                credentials.refreshIfExpired()
                checkNotNull(credentials.accessToken) { "アクセストークンが無い" }.tokenValue
            }
        }
    }
}
