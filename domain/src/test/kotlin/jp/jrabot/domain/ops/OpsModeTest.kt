package jp.jrabot.domain.ops

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OpsModeTest {
    @Test
    fun `5 つの値が契約どおりに定義されている`() {
        assertEquals(listOf("NORMAL", "STARTING", "STOPPING", "STOPPED", "UNKNOWN"), OpsMode.entries.map { it.name })
    }

    @Test
    fun `処理を始めてよいのは NORMAL だけ（fail closed）`() {
        assertEquals(listOf(OpsMode.NORMAL), OpsMode.entries.filter { it.allowsWork })
        assertFalse(OpsMode.UNKNOWN.allowsWork)
    }

    @Test
    fun `UNKNOWN だけが未確認`() {
        assertEquals(listOf(OpsMode.UNKNOWN), OpsMode.entries.filterNot { it.isConfirmed })
    }

    @Test
    fun `正本の 4 つの値は変換できる`() {
        assertEquals(OpsMode.NORMAL, OpsMode.fromSourceValue("NORMAL"))
        assertEquals(OpsMode.STARTING, OpsMode.fromSourceValue("STARTING"))
        assertEquals(OpsMode.STOPPING, OpsMode.fromSourceValue("STOPPING"))
        assertEquals(OpsMode.STOPPED, OpsMode.fromSourceValue("STOPPED"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "normal", "Normal", " NORMAL", "NORMAL ", "RUNNING", "UNKNOWN", "null", "1"])
    fun `正本に無い値や表記ゆれは UNKNOWN になり NORMAL にならない`(raw: String) {
        assertNull(OpsMode.fromSourceValueOrNull(raw))
        assertEquals(OpsMode.UNKNOWN, OpsMode.fromSourceValue(raw))
        assertFalse(OpsMode.fromSourceValue(raw).allowsWork)
    }

    @Test
    fun `null は UNKNOWN`() {
        assertEquals(OpsMode.UNKNOWN, OpsMode.fromSourceValue(null))
    }
}
