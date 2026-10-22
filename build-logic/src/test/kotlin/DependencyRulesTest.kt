package jrabot.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DependencyRulesTest {
    private val ok =
        mapOf(
            ":domain" to emptySet(),
            ":persistence" to setOf(":domain"),
            ":db-migration" to emptySet(),
            ":cloud-gcp" to setOf(":domain"),
            ":bot" to setOf(":domain", ":persistence", ":cloud-gcp"),
            ":ingest" to setOf(":domain", ":persistence", ":cloud-gcp"),
            ":dashboard" to setOf(":domain", ":persistence", ":cloud-gcp"),
        )

    @Test
    fun `設計どおりの依存は違反なし`() {
        assertEquals(emptyList(), DependencyRules.checkProjectDependencies(ok))
    }

    @Test
    fun `実行アプリどうしの依存はすべて禁止`() {
        val apps = listOf(":bot", ":ingest", ":dashboard")
        for (from in apps) {
            for (to in apps.filter { it != from }) {
                val graph = ok + (from to (ok.getValue(from) + to))
                val v = DependencyRules.checkProjectDependencies(graph)
                assertEquals(1, v.size, "$from → $to を検知できること: $v")
                assertTrue(v.single().startsWith("$from → $to"))
            }
        }
    }

    @Test
    fun `domain は何にも依存できない`() {
        for (to in listOf(":persistence", ":cloud-gcp", ":bot", ":db-migration")) {
            assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":domain" to setOf(to))).size, "domain → $to")
        }
    }

    @Test
    fun `persistence と cloud-gcp は実行アプリや互いに依存できない`() {
        for (m in listOf(":persistence", ":cloud-gcp")) {
            for (to in listOf(":bot", ":ingest", ":dashboard", ":db-migration")) {
                assertEquals(1, DependencyRules.checkProjectDependencies(ok + (m to setOf(":domain", to))).size, "$m → $to")
            }
        }
        assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":persistence" to setOf(":domain", ":cloud-gcp"))).size)
        assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":cloud-gcp" to setOf(":domain", ":persistence"))).size)
    }

    @Test
    fun `db-migration は独立していて、アプリも db-migration に依存できない`() {
        assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":db-migration" to setOf(":domain"))).size)
        assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":bot" to (ok.getValue(":bot") + ":db-migration"))).size)
    }

    @Test
    fun `未知のモジュールは違反`() {
        assertEquals(1, DependencyRules.checkProjectDependencies(ok + (":common" to emptySet())).size)
    }

    @Test
    fun `domain の外部ライブラリ禁止`() {
        val v =
            DependencyRules.checkDomainExternalGroups(
                listOf("org.springframework.boot", "org.seasar.doma", "com.mysql", "com.google.cloud", "org.jetbrains.kotlin", "org.junit.jupiter"),
            )
        assertEquals(4, v.size, v.toString())
    }

    @Test
    fun `domain の import 禁止`() {
        val v =
            DependencyRules.checkDomainImports(
                mapOf(
                    "A.kt" to listOf("java.sql.Connection", "kotlin.math.max"),
                    "B.kt" to listOf("org.springframework.stereotype.Component", "java.time.Instant"),
                ),
            )
        assertEquals(2, v.size, v.toString())
    }

    @Test
    fun `BOM は検査対象外`() {
        assertTrue(DependencyRules.isBom("org.springframework.boot", "spring-boot-dependencies"))
        assertEquals(false, DependencyRules.isBom("org.springframework.boot", "spring-boot-starter"))
    }
}
