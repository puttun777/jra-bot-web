package jp.jrabot.dashboard.api

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.cloudgcp.auth.CallerVerification
import jp.jrabot.cloudgcp.auth.GoogleSignedTokenVerifier
import jp.jrabot.cloudgcp.auth.PersonVerifier
import jp.jrabot.cloudgcp.auth.RequestPaths
import jp.jrabot.domain.tenant.AuthDecision
import jp.jrabot.domain.tenant.HumanAuthorizer
import jp.jrabot.domain.tenant.Principal
import org.springframework.web.filter.OncePerRequestFilter

/**
 * `/api` 配下を、IAP の JWT で確認できた**許可された人**だけに限る（CONTRACTS §11.1、deny by default）。
 * ① JWT の署名・issuer・audience・有効期限（[PersonVerifier]）→ 検証できなければ 401。② メールが `ALLOWED_USERS` に含まれ、`app_user` に有効な行があり、ADMIN なら
 * `ADMIN_USERS` にも載っている（[HumanAuthorizer]）→ 満たさなければ 403。通過した主体は [PRINCIPAL_ATTRIBUTE] に入れる（テナントはここで決まる。リクエストの値で決めない）。
 * ops 用サービスアカウントは人ではないので、`ALLOWED_USERS` に入れない限り 403（`/internal` 専用）。理由はレスポンスに出さない。キャッシュしない。
 */
class ApiAuthFilter(
    private val verifier: PersonVerifier,
    private val authorizer: HumanAuthorizer,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !RequestPaths.isUnder(request.requestURI, API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val verified = verifier.verifyPerson(request.getHeader(GoogleSignedTokenVerifier.IAP_HEADER))
        if (verified !is CallerVerification.Verified) return reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized")
        when (val decision = authorizer.authorize(verified.email)) {
            is AuthDecision.Allowed -> {
                // 既定: 読取り以外（GET/HEAD/OPTIONS 以外）は管理者だけ。書込みを足すときにコントローラがロールの確認を忘れても閲覧者は通さない（レビュー A1）
                // /api/v1/admin 以下は読取りも管理者だけ（管理用の一覧・履歴を閲覧者に見せない）
                val adminOnly = request.method !in READ_METHODS || RequestPaths.isUnder(request.requestURI, "/api/v1/admin")
                if (adminOnly && !decision.principal.isAdmin) {
                    log.info("api auth denied: reason=ROLE_NOT_ADMIN method={}", request.method)
                    return reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden")
                }
                request.setAttribute(PRINCIPAL_ATTRIBUTE, decision.principal)
                filterChain.doFilter(request, response)
            }

            is AuthDecision.Denied -> {
                // 本人がログインできないときの原因調査用。理由の固定語彙だけ（メール等の値は出さない。レビュー A4）
                log.info("api auth denied: reason={}", decision.reason)
                reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden")
            }
        }
    }

    private fun reject(
        response: HttpServletResponse,
        status: Int,
        error: String,
    ) {
        response.status = status
        response.setHeader("Cache-Control", "no-store")
        response.contentType = "application/json"
        response.writer.write("""{"error":"$error"}""")
    }

    companion object {
        const val API_PREFIX = "/api"
        private val READ_METHODS = setOf("GET", "HEAD", "OPTIONS")
        private val log: org.slf4j.Logger = org.slf4j.LoggerFactory.getLogger(ApiAuthFilter::class.java)
        const val PRINCIPAL_ATTRIBUTE = "jrabot.principal"

        fun principalOf(request: HttpServletRequest): Principal = request.getAttribute(PRINCIPAL_ATTRIBUTE) as? Principal ?: error("認証フィルタを通っていないリクエスト")
    }
}
