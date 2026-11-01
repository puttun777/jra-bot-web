package jp.jrabot.dashboard

import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.cloudgcp.auth.PersonVerifier
import org.junit.jupiter.api.Test
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import java.nio.file.Files
import kotlin.test.assertSame

/**
 * **本番と同じ配線**（試験用の差し替え `TestAuthConfig` を使わない）で起動できること。検証器の Bean が曖昧になって本番だけ起動に失敗した
 * 不具合（image smoke で発覚）の回帰試験。DB には接続しない（遅延）・認証の実動作は他の試験。
 */
class DashboardDefaultWiringTest {
    @Test
    fun `差し替えなしの既定の配線で起動し 人の検証と内部 API の検証は同じ検証器を使う`() {
        val ops = writeOpsState(Files.createTempDirectory("dashboard-wiring"))
        val props = baseProperties(ops)
        val ctx =
            SpringApplicationBuilder(DashboardApplication::class.java)
                .web(WebApplicationType.NONE)
                .properties(*props)
                .run(*props.map { "--$it" }.toTypedArray())
        try {
            assertSame<Any>(ctx.getBean(PersonVerifier::class.java), ctx.getBean(InternalCallerVerifier::class.java))
        } finally {
            ctx.close()
        }
    }
}
