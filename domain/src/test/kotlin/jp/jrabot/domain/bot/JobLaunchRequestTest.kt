package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JobLaunchRequestTest {
    private val tenant = TenantId.of(1)

    @Test
    fun `Job 名は CLI の --task と一致する`() {
        assertEquals(
            listOf("bot-run", "calendar-check", "reconcile", "retention", "export-dataset", "backtest", "ml-train"),
            LaunchableJob.entries.map { it.cliName },
        )
    }

    @Test
    fun `テナントを必ず持ち 引数は検査される`() {
        val req = JobLaunchRequest(tenant, LaunchableJob.BOT_RUN, mapOf("date" to "2026-11-01", "trigger" to "SCHEDULER"))
        assertEquals(tenant, req.tenantId)
        assertEquals(JobLaunchRequest(tenant, LaunchableJob.BOT_RUN, req.params), req)
    }

    @Test
    fun `不正な引数のキーと値は拒否する`() {
        for (key in listOf("", "Date", "1a", "a b", "--date", "a=b", "x".repeat(33))) {
            assertFailsWith<IllegalArgumentException>("key=$key") { JobLaunchRequest(tenant, LaunchableJob.BOT_RUN, mapOf(key to "v")) }
        }
        for (value in listOf("a\nb", "a\rb", "x".repeat(257))) {
            assertFailsWith<IllegalArgumentException> { JobLaunchRequest(tenant, LaunchableJob.BOT_RUN, mapOf("k" to value)) }
        }
    }

    @Test
    fun `結果は起動成功と一時的か恒久的かを区別した失敗`() {
        val ok: LaunchResult = LaunchResult.Launched("exec-1")
        val failed: LaunchResult = LaunchResult.Failed("quota", transient = true)
        assertEquals("exec-1", (ok as LaunchResult.Launched).executionId)
        assertEquals(true, (failed as LaunchResult.Failed).transient)
    }
}
