package jp.jrabot.domain.bot

import jp.jrabot.domain.tenant.TenantId
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BotRunTest {
    private val started = Instant.parse("2026-11-01T00:00:00Z")
    private val runId = RunId.parse("0199c7a0-1b2c-7d3e-8f40-123456789abc")

    private fun run(
        state: BotRunState = BotRunState.RUNNING,
        finishedAt: Instant? = null,
        version: Int = 0,
    ) = BotRun(
        TenantId.of(1),
        runId,
        LocalDate.of(2026, 11, 1),
        BotRunTrigger.SCHEDULER,
        state,
        leaseOwner = "container-1",
        leaseExpiresAt = started.plusSeconds(300),
        heartbeatAt = started,
        checkpoint = null,
        startedAt = started,
        finishedAt = finishedAt,
        exitReason = null,
        version = version,
    )

    @Test
    fun `排他を持つのは実行中の 3 状態だけ`() {
        assertEquals(
            listOf(BotRunState.STARTING, BotRunState.RUNNING, BotRunState.DRAINING),
            BotRunState.entries.filter { it.holdsActiveSlot },
        )
    }

    @Test
    fun `状態の一覧は設計と契約の INTERRUPTED を含む`() {
        assertEquals(
            listOf("STARTING", "RUNNING", "DRAINING", "SUCCEEDED", "FAILED", "ABORTED", "STOPPED_BY_MODE", "INTERRUPTED"),
            BotRunState.entries.map { it.name },
        )
    }

    @Test
    fun `実行中は finishedAt を持てず 終了後は開始以降の時刻だけ`() {
        assertFailsWith<IllegalArgumentException> { run(BotRunState.RUNNING, finishedAt = started.plusSeconds(1)) }
        assertEquals(started, run(BotRunState.SUCCEEDED, finishedAt = started).finishedAt)
        assertFailsWith<IllegalArgumentException> { run(BotRunState.FAILED, finishedAt = started.minusSeconds(1)) }
        assertFailsWith<IllegalArgumentException> { run(version = -1) }
    }

    @Test
    fun `RunId は小文字ハイフン付き UUID だけ`() {
        val uuid = UUID.fromString("0199c7a0-1b2c-7d3e-8f40-123456789abc")
        assertEquals(runId, RunId.of(uuid))
        assertEquals(runId.hashCode(), RunId.of(uuid).hashCode())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "", "0199C7A0-1B2C-7D3E-8F40-123456789ABC", "0199c7a01b2c7d3e8f40123456789abc",
            "0199c7a0-1b2c-7d3e-8f40-123456789ab", " 0199c7a0-1b2c-7d3e-8f40-123456789abc", "../0199c7a0-1b2c-7d3e-8f40-12345678",
        ],
    )
    fun `RunId の不正な形式は拒否する`(text: String) {
        assertNull(RunId.parseOrNull(text))
        assertFailsWith<IllegalArgumentException> { RunId.parse(text) }
    }
}
