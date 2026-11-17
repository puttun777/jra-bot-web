package jp.jrabot.dashboard

import jp.jrabot.dashboard.guard.CsrfGuardFilter
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * CloudFront の標準 URL（I-050・D-288）での CSRF の検査。CloudFront（AllViewer）→ Caddy → oauth2-proxy は Host を変えずに渡すので、
 * dashboard が見る Host は公開 URL（d*.cloudfront.net）。ブラウザの Origin も同じ URL なら通し、別の distribution・別のサイトは 403。
 * （HTTP の試験のクライアントは Host を変えられないので、ここはフィルタの単体で確かめる。Host を変えない転送は infra の aws-edge で確かめる。）
 */
class CsrfGuardCloudFrontTest {
    private val site = "d1234567890abc.cloudfront.net"

    private fun post(
        origin: String?,
        site: String? = "same-origin",
        host: String = this.site,
    ): Int {
        val req =
            MockHttpServletRequest("POST", "/api/v1/admin/agents").apply {
                addHeader("Host", host)
                addHeader(CsrfGuardFilter.HEADER, CsrfGuardFilter.HEADER_VALUE)
                contentType = "application/json"
                origin?.let { addHeader("Origin", it) }
                site?.let { addHeader("Sec-Fetch-Site", it) }
            }
        val res = MockHttpServletResponse()
        CsrfGuardFilter().doFilter(req, res, MockFilterChain())
        return res.status
    }

    @Test
    fun `公開 URL の Origin と Host が一致すれば通す`() {
        assertEquals(200, post("https://$site"))
        assertEquals(200, post(null), "Origin を付けない同一オリジンの要求（他の条件で守る）")
    }

    @Test
    fun `別の distribution・別のサイト・クロスサイトは 403`() {
        assertEquals(403, post("https://d9999999999999.cloudfront.net"))
        assertEquals(403, post("https://evil.example"))
        assertEquals(403, post("https://$site", site = "cross-site"))
        assertEquals(403, post("https://$site", host = "ip-10-80-1-10.ap-northeast-1.compute.internal"), "Host が公開 URL でない（転送で Host が変わった）")
    }
}
