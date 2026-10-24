package jp.jrabot.ingest

import jp.jrabot.domain.time.SystemClock
import jp.jrabot.ingest.agent.JsonTrees
import jp.jrabot.ingest.agent.PlanProvider
import kotlin.test.Test
import kotlin.test.assertEquals

/** 取得計画: 公式仕様で確認済みと明示された取得だけを配る（既定は空 = idle）。 */
class PlanProviderTest {
    private val json = JsonTrees(1_000_000)
    private val items = """[{"dataspec":"RACE","option":2,"interval":300},{"dataspec":"0B12","key":"20261101","interval":60},{"dataspec":"EVIL","option":1}]"""

    private fun plan(
        itemsJson: String,
        verified: Set<String>,
    ) = PlanProvider(itemsJson, verified, json, SystemClock).plan()

    @Test
    fun `設定が無ければ idle`() {
        val p = plan("", emptySet())
        assertEquals(true, p["idle"])
        assertEquals(emptyList<Any>(), p["items"])
    }

    @Test
    fun `検証済みの仕様に無い項目は配らない`() {
        assertEquals(true, plan(items, emptySet())["idle"], "未確認の仕様は一つも配らない")
        val p = plan(items, setOf("RACE:2"))
        assertEquals(1, (p["items"] as List<*>).size)
        assertEquals(false, p["idle"])
    }

    @Test
    fun `未対応の dataspec や壊れた JSON は配らない`() {
        assertEquals(true, plan(items, setOf("EVIL:1"))["idle"])
        assertEquals(true, plan("{not json", setOf("RACE:2"))["idle"])
    }
}
