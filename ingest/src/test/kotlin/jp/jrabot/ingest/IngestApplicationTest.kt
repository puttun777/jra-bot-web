package jp.jrabot.ingest

import jp.jrabot.cloudgcp.CloudGcpModule
import jp.jrabot.domain.DomainModule
import jp.jrabot.persistence.PersistenceModule
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@SpringBootTest
class IngestApplicationTest {
    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `コンテキストが起動する`() {
        assertNotNull(context)
    }

    @Test
    fun `共有モジュールがクラスパスにある`() {
        assertEquals(listOf("domain", "persistence", "cloud-gcp"), listOf(DomainModule.NAME, PersistenceModule.NAME, CloudGcpModule.NAME))
    }
}
