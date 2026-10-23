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
    fun `domain は HTTP クライアント JSON JPA 他クラウドにも依存できない`() {
        val groups =
            listOf(
                "com.fasterxml.jackson.core",
                "io.ktor",
                "com.squareup.okhttp3",
                "org.apache.httpcomponents",
                "org.apache.hc.client5",
                "org.hibernate.orm",
                "jakarta.persistence",
                "software.amazon.awssdk",
                "com.azure",
                "io.grpc",
            )
        assertEquals(groups.size, DependencyRules.checkDomainExternalGroups(groups).size)
        val imports =
            listOf(
                "java.net.http.HttpClient",
                "okhttp3.OkHttpClient",
                "io.ktor.client.HttpClient",
                "com.fasterxml.jackson.databind.ObjectMapper",
                "jakarta.persistence.Entity",
                "org.hibernate.Session",
                "com.google.api.client.http.HttpTransport",
                "org.flywaydb.core.Flyway",
                "software.amazon.awssdk.services.s3.S3Client",
                "java.net.HttpURLConnection",
            )
        assertEquals(imports.size, DependencyRules.checkDomainImports(mapOf("A.kt" to imports)).size)
    }

    @Test
    fun `domain で許す標準 API は違反にならない`() {
        val ok = listOf("java.time.Instant", "java.io.InputStream", "java.util.UUID", "java.net.URI", "kotlin.math.abs", "java.security.MessageDigest")
        assertEquals(emptyList(), DependencyRules.checkDomainImports(mapOf("A.kt" to ok)))
    }

    @Test
    fun `domain の import 無しの完全修飾名による参照も検知する`() {
        val lines =
            mapOf(
                "A.kt" to
                    listOf(
                        1 to "import java.time.Instant",
                        2 to "val c: java.sql.Connection? = null",
                        3 to "    // java.sql.Connection はコメントなので対象外",
                        4 to "     * org.springframework.stereotype.Component の KDoc",
                        5 to "val h = java.net.http.HttpClient.newHttpClient()",
                    ),
            )
        val v = DependencyRules.checkDomainQualifiedReferences(lines)
        assertEquals(2, v.size, v.toString())
        assertTrue(v[0].startsWith("A.kt:2:") && v[1].startsWith("A.kt:5:"), v.toString())
    }

    @Test
    fun `BOM は検査対象外`() {
        assertTrue(DependencyRules.isBom("org.springframework.boot", "spring-boot-dependencies"))
        assertEquals(false, DependencyRules.isBom("org.springframework.boot", "spring-boot-starter"))
    }

    @Test
    fun `Flyway は db-migration だけが持てる`() {
        val ok =
            mapOf(
                ":db-migration" to setOf("org.flywaydb:flyway-core", "org.flywaydb:flyway-mysql"),
                ":bot" to setOf("org.springframework.boot:spring-boot-starter"),
            )
        assertEquals(emptyList(), DependencyRules.checkFlywayOnlyInDbMigration(ok))
        val bad =
            mapOf(
                ":bot" to setOf("org.flywaydb:flyway-core"),
                ":ingest" to setOf("org.springframework.boot:spring-boot-starter-flyway"),
                ":dashboard" to setOf("org.springframework.boot:spring-boot-flyway"),
                ":persistence" to setOf("org.flywaydb:flyway-mysql"),
            )
        assertEquals(4, DependencyRules.checkFlywayOnlyInDbMigration(bad).size)
    }

    @Test
    fun `db-migration は Spring と Doma に依存しない`() {
        assertEquals(emptyList(), DependencyRules.checkDbMigrationExternal(listOf("org.flywaydb:flyway-core", "com.mysql:mysql-connector-j")))
        assertEquals(
            2,
            DependencyRules.checkDbMigrationExternal(listOf("org.springframework.boot:spring-boot-starter", "org.seasar.doma:doma-core")).size,
        )
    }
}
