package jp.jrabot.dashboard

import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.cloudgcp.auth.PersonVerifier
import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.guard.AdminWriteGuardFilter
import jp.jrabot.dashboard.internal.InternalAuthFilter
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.tenant.HumanAuthorizer
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** サーブレットフィルタの登録と順序（内部 API の認証 → 書込みのモードガード）。すべての URL に掛け、対象かどうかはフィルタが正規化したパスで判断する。 */
@Configuration(proxyBeanMethods = false)
class WebFilters {
    @Bean
    fun internalAuthFilter(verifier: InternalCallerVerifier): FilterRegistrationBean<InternalAuthFilter> =
        FilterRegistrationBean(InternalAuthFilter(verifier)).apply {
            addUrlPatterns("/*")
            order = 10
        }

    @Bean
    fun apiAuthFilter(
        verifier: PersonVerifier,
        authorizer: HumanAuthorizer,
    ): FilterRegistrationBean<ApiAuthFilter> =
        FilterRegistrationBean(ApiAuthFilter(verifier, authorizer)).apply {
            addUrlPatterns("/*")
            order = 20
        }

    @Bean
    fun adminWriteGuardFilter(mode: InspectableModeProvider): FilterRegistrationBean<AdminWriteGuardFilter> =
        FilterRegistrationBean(AdminWriteGuardFilter(mode)).apply {
            addUrlPatterns("/*")
            order = AdminWriteGuardFilter.ORDER
        }
}
