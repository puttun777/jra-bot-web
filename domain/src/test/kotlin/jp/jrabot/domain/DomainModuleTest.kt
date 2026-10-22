package jp.jrabot.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DomainModuleTest {
    @Test
    fun `モジュール名`() {
        assertEquals("domain", DomainModule.NAME)
    }
}
