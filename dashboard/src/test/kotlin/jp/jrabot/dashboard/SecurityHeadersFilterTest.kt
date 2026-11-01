package jp.jrabot.dashboard

import jp.jrabot.dashboard.guard.SecurityHeadersFilter
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecurityHeadersFilterTest {
    @Test
    fun `すべての応答に CSP・nosniff・frame 禁止・Referrer なしを付ける`() {
        val res = MockHttpServletResponse()
        SecurityHeadersFilter().doFilter(MockHttpServletRequest("GET", "/"), res, MockFilterChain())
        assertEquals(SecurityHeadersFilter.CSP, res.getHeader("Content-Security-Policy"))
        assertEquals("nosniff", res.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", res.getHeader("X-Frame-Options"))
        assertEquals("no-referrer", res.getHeader("Referrer-Policy"))
        assertFalse(SecurityHeadersFilter.CSP.contains("unsafe-inline") || SecurityHeadersFilter.CSP.contains("http"), "インラインや外部オリジンを許さない")
    }

    @Test
    fun `静的な画面はインライン script・インラインイベント・外部 CDN・innerHTML を使わない`() {
        val dir = Path.of("src/main/resources/static")
        val html = Files.readString(dir.resolve("index.html"))
        val js = Files.readString(dir.resolve("app.js"))
        assertFalse(Regex("""<script(?![^>]*\bsrc=)""").containsMatchIn(html), "インライン script")
        assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(html), "インラインイベント")
        assertFalse(Regex("""https?://""").containsMatchIn(html + js + Files.readString(dir.resolve("style.css"))), "外部オリジン")
        assertFalse(js.contains("innerHTML") || js.contains("insertAdjacentHTML") || js.contains("document.write") || js.contains("eval("), "文字列から HTML を作る描画")
        assertTrue(html.contains("lang=\"ja\"") && html.contains("viewport"))
    }
}
