package jp.jrabot.platform.ops

import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.ops.WorkloadStatus
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** ops-state の `workloads`（AWS の起動目的。D-282）の解析と、モードと同じキャッシュ・検証を通ること。 */
class WorkloadsTest {
    private val clock = MutableClock(Instant.parse("2026-11-13T00:00:00Z"))

    private fun provider(vararg steps: ScriptedSource.Step) = CachingModeProvider(ScriptedSource(*steps), clock, log = RecordingLogger())

    @Test
    fun `workloads が無ければ Unspecified（GCP の構成）、あれば両方の真偽値`() {
        assertEquals(WorkloadStatus.Unspecified, provider(body(opsStateJson())).workloads())
        val p = provider(body(opsStateJson(extra = """, "workloads": {"raceday": false, "batch": true}""")))
        assertEquals(OpsMode.NORMAL, p.current())
        assertEquals(WorkloadStatus.Specified(raceday = false, batch = true), p.workloads())
    }

    @Test
    fun `workloads の形が違えば正本ごと不正（UNKNOWN。部分的な指定で許可に倒さない）`() {
        for (bad in listOf(
            """, "workloads": {"raceday": true}""",
            """, "workloads": {"raceday": "true", "batch": false}""",
            """, "workloads": {"raceday": true, "batch": 1}""",
            """, "workloads": []""",
            """, "workloads": "VIEW"""",
        )) {
            val p = provider(body(opsStateJson(extra = bad)))
            assertEquals(OpsMode.UNKNOWN, p.current(), bad)
            assertEquals(WorkloadStatus.Unknown, p.workloads(), bad)
        }
        assertEquals(WorkloadStatus.Unspecified, provider(body(opsStateJson(extra = """, "workloads": null"""))).workloads())
    }

    @Test
    fun `読めないときは Unknown。同じ seq で workloads が変わったら書き戻りとして UNKNOWN`() {
        assertEquals(WorkloadStatus.Unknown, provider(fail(OpsFailureReason.SOURCE_TIMEOUT)).workloads())
        val p =
            provider(
                body(opsStateJson(seq = 5, extra = """, "workloads": {"raceday": false, "batch": false}""")),
                body(opsStateJson(seq = 5, extra = """, "workloads": {"raceday": true, "batch": false}""")),
            )
        assertIs<WorkloadStatus.Specified>(p.workloads())
        clock.advance(Duration.ofSeconds(16))
        assertEquals(WorkloadStatus.Unknown, p.workloads())
        assertEquals(OpsMode.UNKNOWN, p.current())
    }
}
