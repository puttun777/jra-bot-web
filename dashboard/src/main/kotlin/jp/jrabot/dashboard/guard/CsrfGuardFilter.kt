package jp.jrabot.dashboard.guard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.platform.auth.RequestPaths
import org.springframework.web.filter.OncePerRequestFilter
import java.net.URI

/**
 * `/api` の書込み（GET/HEAD/OPTIONS 以外）の CSRF 対策。ブラウザは IAP の Cookie を自動で送るので、他のサイトから書込みを送らせない:
 * ① カスタムヘッダ `X-Requested-With: jrabot-dashboard` が必須（クロスオリジンのフォーム・単純リクエストでは付けられず、CORS を許可していないのでプリフライトも通らない）
 * ② `Content-Type: application/json` が必須（フォーム送信の形式を拒否）
 * ③ `Sec-Fetch-Site` があれば `same-origin`（直接の操作は `none`）以外を拒否 ④ `Origin` があれば Host と一致すること。
 * 不足・不一致は 403 `{"error":"csrf"}`（理由の詳細は出さない）。認証・認可（[jp.jrabot.dashboard.api.ApiAuthFilter]）より前に評価する。
 */
class CsrfGuardFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.method.uppercase() in SAFE_METHODS || !RequestPaths.isUnder(request.requestURI, "/api")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!allowed(request)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            response.setHeader("Cache-Control", "no-store")
            response.contentType = "application/json"
            response.writer.write("""{"error":"csrf"}""")
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun allowed(request: HttpServletRequest): Boolean {
        if (request.getHeader(HEADER) != HEADER_VALUE) return false
        val type =
            request.contentType
                ?.substringBefore(';')
                ?.trim()
                ?.lowercase()
        if (type != "application/json") return false
        val site = request.getHeader("Sec-Fetch-Site")
        if (site != null && site != "same-origin" && site != "none") return false
        val origin = request.getHeader("Origin")
        if (origin != null) {
            val host = request.getHeader("Host") ?: return false
            val originHost =
                try {
                    URI(origin).authority
                } catch (_: Exception) {
                    return false
                }
            if (originHost == null || !originHost.equals(host, ignoreCase = true)) return false
        }
        return true
    }

    companion object {
        const val ORDER = 15
        const val HEADER = "X-Requested-With"
        const val HEADER_VALUE = "jrabot-dashboard"
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")
    }
}
