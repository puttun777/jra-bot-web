package jp.jrabot.dashboard.internal

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.platform.auth.CallerVerification
import jp.jrabot.platform.auth.InternalCallerVerifier
import jp.jrabot.platform.auth.RejectReason
import jp.jrabot.platform.auth.RequestPaths
import jp.jrabot.platform.auth.TokenHeader
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `/internal` 配下を、IAP が付けた JWT（`X-Goog-IAP-JWT-Assertion`）の ops 用サービスアカウントだけに限る（CONTRACTS §11.1）。
 * 署名（ES256、IAP の公開鍵）・issuer・audience・有効期限・`email == OPS_SA_EMAIL` の検証は [InternalCallerVerifier]。**ヘッダーが存在するだけでは認めない**。
 *
 * - トークンが無い/検証できない → 401。署名は正しいが ops 以外の主体（= 通常の利用者）→ 403（`/internal` は ops SA 専用）。理由はレスポンスに出さない。
 * - ops SA に許すのは `/internal/health|ready|ops/mode` の GET だけで、テナントのデータには一切アクセスできない（このフィルタは `/internal` 以外に主体を与えない）。
 * - 人（利用者）の認可（`ALLOWED_USERS`・`app_user`・ロール）は Stage 4（T4.1）。それまでは `/internal` 以外に公開するルートを作らない。
 * - パス判定は [RequestPaths] の正規化で行い、MVC のハンドラ選択とのずれで素通りできないようにする。
 */
class InternalAuthFilter(
    private val verifier: InternalCallerVerifier,
    /** トークンを読むヘッダー（GCP: IAP の JWT。AWS: `Authorization: Bearer` の運用エージェントのトークン。D-281）。 */
    private val header: TokenHeader = TokenHeader.IAP_ASSERTION,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !RequestPaths.isUnder(request.requestURI, INTERNAL_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        when (val result = verifier.verify(header.extract(request::getHeader))) {
            is CallerVerification.Verified -> {
                filterChain.doFilter(request, response)
            }

            is CallerVerification.Rejected -> {
                val forbidden = result.reason == RejectReason.NOT_OPS_PRINCIPAL
                response.status = if (forbidden) HttpServletResponse.SC_FORBIDDEN else HttpServletResponse.SC_UNAUTHORIZED
                response.setHeader("Cache-Control", "no-store")
                response.contentType = "application/json"
                response.writer.write(if (forbidden) """{"error":"forbidden"}""" else """{"error":"unauthorized"}""")
            }
        }
    }

    companion object {
        const val INTERNAL_PREFIX = "/internal"
    }
}
