package jp.jrabot.dbmigration

import kotlin.test.Test
import kotlin.test.assertEquals

class DbMigrationMainTest {
    @Test
    fun `モジュール名`() {
        assertEquals("db-migration", DbMigrationMain.NAME)
    }
}
