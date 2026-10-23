package jp.jrabot.dashboard.guard

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jp.jrabot.cloudgcp.auth.RequestPaths
import jp.jrabot.domain.ops.ModeProvider
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 書込み（管理操作）の運用モードガード（CONTRACTS §3.2・§5.3）。モードが NORMAL でなければ、ハンドラを呼ばずに 409 `OPS_MODE_NOT_NORMAL` を返す。
 *
 * | モード | 応答 |
 * |---|---|
 * | NORMAL | 次へ（**後続の認可**: IAP・`app_user`・`ROLE_ADMIN`・CSRF は Stage 4） |
 * | STARTING / STOPPING / STOPPED | 409 |
 * | UNKNOWN | 409 |
 *
 * - 対象は **GET/HEAD/OPTIONS 以外のすべてのメソッド**（POST/PUT/PATCH/DELETE と未知のメソッド）。`/api/v1/admin` に限らず全パスに掛け、
 *   Stage 4 でルートを足しても書込みがこのガードを通らない経路を作らない（fail closed）。`/internal` 配下は読み取り専用なので対象外。
 * - これは**管理操作を提供するものではない**。ガードだけで、管理者の承認を経ずに操作できる API は作らない（認可・CSRF・監査・画面は Stage 4）。
 * - **順序**: Stage 4 の認証・認可フィルタは [ORDER] より**小さい**順序で登録し、認証された管理者に対してだけモードを確認する
 *   （未認証の呼び出しにモードの状態を教えない）。DB を使う認可の問い合わせより前にモードを確認したい場合は、その判断を Stage 4 で決める。
 * - STOPPED で外部から到達できなくする仕組みは infra 側（ingress=internal、D11）の責務で、ここは変えない。
 */
class AdminWriteGuardFilter(
    private val modeProvider: ModeProvider,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = request.method.uppercase() in SAFE_METHODS || RequestPaths.isUnder(request.requestURI, "/internal")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (modeProvider.current().allowsWork) {
            filterChain.doFilter(request, response)
            return
        }
        response.status = HttpServletResponse.SC_CONFLICT
        response.setHeader("Cache-Control", "no-store")
        response.contentType = "application/json"
        response.writer.write("""{"error":"OPS_MODE_NOT_NORMAL"}""")
    }

    companion object {
        private val SAFE_METHODS = setOf("GET", "HEAD", "OPTIONS")

        /** Stage 4 の認証・認可フィルタ（これより小さい値）の後ろ。 */
        const val ORDER = 50
    }
}
