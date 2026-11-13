package jp.jrabot.platform.ops

import jp.jrabot.domain.ops.ModeSnapshot
import jp.jrabot.domain.ops.OpsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** `/internal/ops/mode` 用の診断の写し（CONTRACTS §3.2）。取得し直さず、採用中の値だけを返す。 */
class ModeSnapshotTest {
    private val clock = MutableClock()

    private fun provider(
        source: OpsStateSource,
        label: String = CachingModeProvider.SOURCE_GCS,
    ) = CachingModeProvider(source, clock, log = RecordingLogger(), sourceLabel = label)

    @Test
    fun `採用中の状態の mode・desired・seq・経過・source を返す`() {
        val p = provider(ScriptedSource(body(opsStateJson(seq = 7, mode = "STOPPING", desiredMode = "STOPPED"))))
        assertEquals(ModeSnapshot(OpsMode.STOPPING, OpsMode.STOPPED, 7, 0, "gcs"), p.snapshot())
        clock.advanceMillis(4_500)
        assertEquals(ModeSnapshot(OpsMode.STOPPING, OpsMode.STOPPED, 7, 4_500, "gcs"), p.snapshot())
    }

    @Test
    fun `キャッシュの有効期間内は取得し直さない（診断のたびに GCS を読まない）`() {
        val source = ScriptedSource(body(opsStateJson(seq = 1)))
        val p = provider(source)
        repeat(20) { p.snapshot() }
        p.current()
        assertEquals(1, source.reads.get())
    }

    @Test
    fun `期限が切れたら取り直し、新しい seq を返す`() {
        val source = ScriptedSource(body(opsStateJson(seq = 1)), body(opsStateJson(seq = 2)))
        val p = provider(source)
        assertEquals(1L, p.snapshot().seq)
        clock.advanceSeconds(15)
        val s = p.snapshot()
        assertEquals(2L, s.seq)
        assertEquals(0L, s.cacheAgeMs)
    }

    @Test
    fun `読取り失敗は UNKNOWN で、詳細を持たない（直前の成功値を残さない）`() {
        val source = ScriptedSource(body(opsStateJson(seq = 3)), fail(OpsFailureReason.SOURCE_TIMEOUT))
        val p = provider(source)
        assertEquals(3L, p.snapshot().seq)
        clock.advanceSeconds(15)
        val s = p.snapshot()
        assertEquals(ModeSnapshot.unknown(), s)
        assertEquals(OpsMode.UNKNOWN, s.mode)
        assertNull(s.seq)
        assertNull(s.desiredMode)
        assertNull(s.cacheAgeMs)
        assertEquals("unknown", s.source)
    }

    @Test
    fun `seq の巻き戻りは UNKNOWN`() {
        val p = provider(ScriptedSource(body(opsStateJson(seq = 5)), body(opsStateJson(seq = 4))))
        p.snapshot()
        clock.advanceSeconds(15)
        assertEquals(ModeSnapshot.unknown(), p.snapshot())
    }

    @Test
    fun `ローカルファイル実装の source は file（GCS と偽らない）`() {
        val p = provider(ScriptedSource(body(opsStateJson())), CachingModeProvider.SOURCE_FILE)
        assertEquals("file", p.snapshot().source)
    }

    @Test
    fun `写しの不変条件（UNKNOWN は詳細なし、確定は詳細あり）`() {
        assertFailsWith<IllegalArgumentException> { ModeSnapshot(OpsMode.UNKNOWN, OpsMode.NORMAL, 1, 0, "gcs") }
        assertFailsWith<IllegalArgumentException> { ModeSnapshot(OpsMode.NORMAL, null, null, null, "unknown") }
        assertFailsWith<IllegalArgumentException> { ModeSnapshot(OpsMode.NORMAL, OpsMode.NORMAL, 1, 0, "unknown") }
    }
}
