package jp.jrabot.persistence

import kotlin.test.Test
import kotlin.test.assertEquals

class PersistenceModuleTest {
    @Test
    fun `domain に依存している`() {
        assertEquals("domain", PersistenceModule.DEPENDS_ON)
    }
}
