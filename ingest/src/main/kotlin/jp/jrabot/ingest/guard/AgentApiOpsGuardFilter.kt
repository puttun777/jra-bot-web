package jp.jrabot.ingest.guard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.platform.auth.RequestPaths
import org.springframework.web.filter.OncePerRequestFilter

/**
 * エージェント向け API（`/v1/agent` 配下）の運用モードガード（CONTRACTS §3.2・§3.6・§5.3）。モードが NORMAL でなければ、
 * **ハンドラ（= DB に触れる処理）を呼ばずに** 503 を返す。
 *
 * | モード | 応答 |
 * |---|---|
 * | NORMAL | 次へ（エージェント認証などの後続処理） |
 * | STARTING / STOPPING / STOPPED | 503 + `X-Ops-Mode: <mode>` |
 * | UNKNOWN | 503（`X-Ops-Mode` なし。確定していないモードを名乗らない） |
 *
 * - `/v1/agent/mode` は対象外（DB に触れず、ビーコンと同じ内容を返す API。Stage 3）。`/internal` 配下にも掛けない
 *   （`/internal/ready` は STARTING でも DB の準備を確認する必要がある）。
 * - **順序**: エージェント認証（トークンのハッシュを DB で引く）より**前**に動かす。そうしないと、NORMAL 以外でも認証が DB に触れる。
 *   Stage 3 の認証フィルタは [ORDER] より大きい順序で登録すること。
 * - `ModeProvider.current()` は 1 リクエストにつき 1 回だけ呼ぶ。
 */
class AgentApiOpsGuardFilter(
    private val modeProvider: ModeProvider,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean {
        if (RequestPaths.normalize(request.requestURI) == "$PREFIX/mode") return true // /v1/agent/mode だけは対象外
        return !RequestPaths.isUnder(request.requestURI, PREFIX)
    }

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val mode = modeProvider.current()
        if (mode.allowsWork) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
        if (mode.isConfirmed) response.setHeader(HEADER_OPS_MODE, mode.name)
        response.setHeader("Cache-Control", "no-store")
        response.contentType = "application/json"
        response.writer.write(if (mode == OpsMode.UNKNOWN) """{"error":"OPS_MODE_UNKNOWN"}""" else """{"error":"OPS_MODE_NOT_NORMAL"}""")
    }

    companion object {
        const val PREFIX = "/v1/agent"
        const val HEADER_OPS_MODE = "X-Ops-Mode"

        /** 認証フィルタ（Stage 3）より前。 */
        const val ORDER = 20
    }
}
