package jp.jrabot.dashboard.guard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.web.filter.OncePerRequestFilter

/**
 * すべての応答にセキュリティヘッダを付ける。CSP は自分のオリジンだけ（スクリプト・スタイル・接続）で、インライン script/style・外部 CDN を許さない
 * （画面は静的な HTML/CSS/JS + JSON API。値は textContent で描画する）。フレーム埋込み禁止・MIME 推測禁止・Referrer なし。
 */
class SecurityHeadersFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        response.setHeader("Content-Security-Policy", CSP)
        response.setHeader("X-Content-Type-Options", "nosniff")
        response.setHeader("X-Frame-Options", "DENY")
        response.setHeader("Referrer-Policy", "no-referrer")
        filterChain.doFilter(request, response)
    }

    companion object {
        const val ORDER = 5
        const val CSP =
            "default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; " +
                "base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
    }
}
