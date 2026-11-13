package jp.jrabot.platform.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** フィルタのパス判定が、コンテナ/Spring MVC のハンドラ選択とずれて素通りできないこと。 */
class RequestPathsTest {
    private fun under(
        uri: String,
        prefix: String = "/internal",
    ) = RequestPaths.isUnder(uri, prefix)

    @Test
    fun `通常のパス`() {
        assertTrue(under("/internal"))
        assertTrue(under("/internal/"))
        assertTrue(under("/internal/health"))
        assertTrue(under("/internal/ops/mode?x=1"))
        assertFalse(under("/"))
        assertFalse(under("/internals"))
        assertFalse(under("/api/internal"))
        assertFalse(under("/v1/agent/plan"))
    }

    @Test
    fun `迂回の形は internal の配下とみなされる`() {
        for (uri in listOf(
            "//internal/health",
            "/internal//health",
            "/INTERNAL/health",
            "/Internal/Health",
            "/internal;x=1/health",
            "/internal/;x=1",
            "/;a/internal/health",
            "/internal/./health",
            "/./internal/health",
            "/foo/../internal/health",
            "/internal/foo/../health",
            "/%69nternal/health",
            "/%2569nternal/health".replace("%2569", "%69"),
            "/internal%2fhealth",
            "/internal%2Fhealth",
            "/internal%3Bx/health",
            "/internal/health#frag",
        )) {
            assertTrue(under(uri), "internal 配下とみなすべき: $uri → ${RequestPaths.normalize(uri)}")
        }
    }

    @Test
    fun `デコードできないパスは配下とみなす（守る側に倒す）`() {
        assertNull(RequestPaths.normalize("/internal/%zz"))
        assertTrue(under("/internal/%zz"))
        assertTrue(under("/x/%4"))
        assertTrue(under("/%"))
    }

    @Test
    fun `別のプレフィックスでも同じ規則`() {
        assertTrue(under("/v1/agent/plan", "/v1/agent"))
        assertTrue(under("/V1/AGENT;x=1/plan", "/v1/agent"))
        assertTrue(under("//v1//agent/heartbeat", "/v1/agent"))
        assertFalse(under("/v1/agents", "/v1/agent"))
        assertTrue(under("/api/v1/admin/x", "/api/v1/admin"))
    }

    @Test
    fun `正規化の結果`() {
        assertEquals("/", RequestPaths.normalize(""))
        assertEquals("/", RequestPaths.normalize("/"))
        assertEquals("/a/b", RequestPaths.normalize("/a//b/"))
        assertEquals("/b", RequestPaths.normalize("/a/../b"))
        assertEquals("/", RequestPaths.normalize("/../../.."))
    }
}
