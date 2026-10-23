package jp.jrabot.ingest.agent

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.cloudgcp.auth.RequestPaths
import jp.jrabot.domain.ingest.AgentAuthRequest
import jp.jrabot.domain.ingest.AgentAuthenticator
import jp.jrabot.domain.ingest.AuthResult
import jp.jrabot.domain.ingest.Hashing
import jp.jrabot.domain.ingest.IngestCaller
import jp.jrabot.domain.ingest.NonceStore
import jp.jrabot.domain.time.Clock
import org.slf4j.LoggerFactory
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.atomic.AtomicLong

/**
 * `/v1/agent` 配下のエージェント認証（CONTRACTS §3.3 / 提案 P-1）。運用モードのガード（[jp.jrabot.ingest.guard.AgentApiOpsGuardFilter]）の**後**で動く
 * （NORMAL 以外では DB に触れない）。
 *
 * - 本文を（上限つきで）読んでから SHA-256 を取り、署名を検証する。**認証に成功するまで本文を解釈しない**（展開・JSON 解析は認証後）。
 * - 認証に成功した呼び出し元（[IngestCaller]）と本文を request 属性に置く。**テナントはここで決まる**（エージェントの行から。リクエストの値ではない）。
 * - 失敗は状態コードのみ（401。失効は署名が正しい相手にだけ 403）。理由は WARN ログの列挙名だけ（トークン・署名・本文・nonce を出さない）。
 * - [authenticator] が null（KEK が未設定）の間は**すべて 503**。認証できないので業務データを保存しない。
 */
class AgentAuthFilter(
    private val authenticator: AgentAuthenticator?,
    private val nonces: NonceStore,
    private val clock: Clock,
    private val maxBodyBytes: Int,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(AgentAuthFilter::class.java)
    private val requests = AtomicLong()

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !RequestPaths.isUnder(request.requestURI, PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val auth = authenticator
        if (auth == null) {
            respond(response, 503, "AGENT_AUTH_NOT_CONFIGURED")
            return
        }
        val declared = request.contentLengthLong
        if (declared > maxBodyBytes) {
            respond(response, 413, "BODY_TOO_LARGE")
            return
        }
        val body = request.inputStream.readNBytes(maxBodyBytes + 1)
        if (body.size > maxBodyBytes) {
            respond(response, 413, "BODY_TOO_LARGE")
            return
        }
        val uri = request.requestURI + (request.queryString?.let { "?$it" } ?: "")
        val now = clock.now()
        val result =
            auth.authenticate(
                AgentAuthRequest(
                    request.method,
                    uri,
                    request.getHeader("X-Agent-Id"),
                    request.getHeader("X-Timestamp"),
                    request.getHeader("X-Nonce"),
                    request.getHeader("X-Signature"),
                    Hashing.sha256Hex(body),
                ),
                now,
            )
        when (result) {
            is AuthResult.Denied -> {
                log.warn("agent auth denied: {}", result.failure)
                response.setHeader("WWW-Authenticate", "X-Agent-Signature")
                respond(response, result.failure.status, "UNAUTHORIZED")
            }

            is AuthResult.Authenticated -> {
                purgeSometimes()
                request.setAttribute(ATTR_CALLER, IngestCaller(result.tenantId, result.agentId))
                request.setAttribute(ATTR_BODY, body)
                filterChain.doFilter(request, response)
            }
        }
    }

    /** 期限切れの nonce を、50 リクエストに 1 回まとめて消す（失敗しても認証には影響させない）。 */
    private fun purgeSometimes() {
        if (requests.incrementAndGet() % PURGE_EVERY != 0L) return
        try {
            nonces.purgeExpired(clock.now(), PURGE_BATCH)
        } catch (e: RuntimeException) {
            log.warn("nonce purge failed: {}", e.javaClass.simpleName)
        }
    }

    private fun respond(
        response: HttpServletResponse,
        status: Int,
        code: String,
    ) {
        response.status = status
        response.setHeader("Cache-Control", "no-store")
        response.contentType = "application/json"
        response.writer.write("""{"error":"$code"}""")
    }

    companion object {
        const val PREFIX = "/v1/agent"
        const val ATTR_CALLER = "jrabot.ingest.caller"
        const val ATTR_BODY = "jrabot.ingest.body"
        const val ORDER = 30 // AgentApiOpsGuardFilter.ORDER(20) より後
        private const val PURGE_EVERY = 50L
        private const val PURGE_BATCH = 200
    }
}
