package jp.jrabot.ingest.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.RequestPaths
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `/internal` 配下を、ops 用サービスアカウントの Google ID トークン（`Authorization: Bearer`）だけに限る（CONTRACTS §11.2）。
 * 署名・issuer・audience・有効期限・`email`/`email_verified` の検証は [InternalCallerVerifier]。満たさなければ 401
 * （`WWW-Authenticate` 付き。理由はレスポンスに出さない）。このフィルタを通ったら、ハンドラはテナントのデータを持たない読み取り専用の情報だけを返す。
 *
 * パス判定は [RequestPaths] の正規化（`;`・`//`・`.`・`%xx`・大文字小文字）で行い、MVC のハンドラ選択とのずれで素通りできないようにする。
 * ローカルテストと本番で認証条件を変える仕組みは持たない（テストは検証器の鍵の取得先を差し替えるだけ）。
 */
class InternalAuthFilter(
    private val verifier: InternalCallerVerifier,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !RequestPaths.isUnder(request.requestURI, INTERNAL_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = bearerToken(request.getHeader("Authorization"))
        when (verifier.verify(token)) {
            is CallerVerification.Verified -> {
                filterChain.doFilter(request, response)
            }

            is CallerVerification.Rejected -> {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                response.setHeader("WWW-Authenticate", "Bearer")
                response.setHeader("Cache-Control", "no-store")
                response.contentType = "application/json"
                response.writer.write("""{"error":"unauthorized"}""")
            }
        }
    }

    companion object {
        const val INTERNAL_PREFIX = "/internal"

        /** `Bearer <token>`（スキームは大文字小文字を区別しない）以外は null。 */
        internal fun bearerToken(header: String?): String? {
            if (header == null || header.length <= 7 || !header.regionMatches(0, "Bearer ", 0, 7, ignoreCase = true)) return null
            return header.substring(7).trim().ifEmpty { null }
        }
    }
}
