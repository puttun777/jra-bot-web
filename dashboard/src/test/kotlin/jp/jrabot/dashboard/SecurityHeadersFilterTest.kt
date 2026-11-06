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
        // 画面の処理は TypeScript（src/main/ts。D-263）。jar に入るのはその変換結果（static/js）
        val js =
            Files.list(Path.of("src/main/ts")).use { files ->
                files
                    .filter { it.toString().endsWith(".ts") }
                    .sorted()
                    .map { Files.readString(it) }
                    .toList()
                    .joinToString("\n")
            }
        assertTrue(js.contains("textContent"), "src/main/ts を読めている")
        assertFalse(Regex("""<script(?![^>]*\bsrc=)""").containsMatchIn(html), "インライン script")
        assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(html), "インラインイベント")
        assertFalse(Regex("""https?://""").containsMatchIn(html + js + Files.readString(dir.resolve("style.css"))), "外部オリジン")
        assertFalse(
            js.contains("innerHTML") || js.contains("outerHTML") || js.contains("insertAdjacentHTML") || js.contains("document.write") || js.contains("eval("),
            "文字列から HTML を作る描画",
        )
        assertTrue(html.contains("""<script type="module" src="/js/app.js"></script>"""), "変換後のモジュールを読む")
        assertTrue(html.contains("lang=\"ja\"") && html.contains("viewport"))
    }

    @Test
    fun `jar の static に変換後の画面のモジュールが入る（Gradle の compileFrontend が resources に繋がっている）`() {
        val loader = checkNotNull(javaClass.classLoader)
        for (name in listOf("app", "api", "dom", "format", "schema", "validate", "view")) {
            val res = checkNotNull(loader.getResource("static/js/$name.js")) { "static/js/$name.js が classpath に無い" }
            val text = res.readText()
            assertFalse(TS_ONLY.containsMatchIn(text), "$name.js は TypeScript のままではない（型の宣言・import type が残っていない）")
        }
        assertTrue(checkNotNull(loader.getResource("static/js/app.js")).readText().contains("from \"./api.js\""), "相対 import は .js 付き（ブラウザの ES モジュール）")
        assertEquals(null, loader.getResource("static/app.js"), "旧 app.js は削除済み")
    }

    private companion object {
        val TS_ONLY = Regex("""^\s*(export\s+)?(type|interface)\s+\w+|\bimport\s+type\b|\bimport\s*\{[^}]*\btype\s""", RegexOption.MULTILINE)
    }
}
