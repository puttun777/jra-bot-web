package jp.jrabot.domain.agent

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AgentHeartbeatTest {
    private val at = Instant.parse("2026-11-01T01:23:45Z")

    @Test
    fun `時刻と ID と版だけを持つ`() {
        val hb = AgentHeartbeat(AgentId.of("ag_0123abcd"), at, "0.3.0")
        // データ本文・トークン・IP・メールアドレスを持てない（フィールドは 3 つだけ。CONTRACTS §5.4）
        assertEquals(3, AgentHeartbeat::class.java.declaredMethods.count { it.name.startsWith("component") })
        assertEquals("0.3.0", hb.agentVersion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["0.3.0", "1", "1.0.0-rc.1", "2026.11.01+build5"])
    fun `版の形式`(version: String) {
        assertEquals(version, AgentHeartbeat(AgentId.of("a"), at, version).agentVersion)
    }

    @ParameterizedTest
    @ValueSource(strings = ["", " 1", "v 1", "1\n", ".1", "-1", "a/b", "ver sion"])
    fun `不正な版は拒否する`(version: String) {
        assertFailsWith<IllegalArgumentException> { AgentHeartbeat(AgentId.of("a"), at, version) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "../x", "a/b", "a b", "a.b"])
    fun `不正な AgentId は拒否する`(text: String) {
        assertNull(AgentId.ofOrNull(text))
        assertFailsWith<IllegalArgumentException> { AgentId.of(text) }
    }

    @Test
    fun `長さの上限`() {
        assertEquals(32, AgentHeartbeat(AgentId.of("a"), at, "1" + "x".repeat(31)).agentVersion.length)
        assertFailsWith<IllegalArgumentException> { AgentHeartbeat(AgentId.of("a"), at, "1" + "x".repeat(32)) }
        assertEquals(64, AgentId.of("x".repeat(64)).value.length)
        assertNull(AgentId.ofOrNull("x".repeat(65)))
    }

    @Test
    fun `書込みの結果は 3 つ`() {
        assertEquals(listOf("WRITTEN", "SKIPPED", "FAILED"), MirrorOutcome.entries.map { it.name })
    }
}
