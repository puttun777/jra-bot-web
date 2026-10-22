package jp.jrabot.cloudgcp

import kotlin.test.Test
import kotlin.test.assertEquals

class CloudGcpModuleTest {
    @Test
    fun `domain に依存している`() {
        assertEquals("domain", CloudGcpModule.DEPENDS_ON)
    }
}
