package jp.jrabot.ingest

import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.ingest.guard.AgentApiOpsGuardFilter
import jp.jrabot.ingest.internal.InternalAuthFilter
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** サーブレットフィルタの登録と順序（認証 → モードガード）。すべての URL に掛け、対象かどうかはフィルタが正規化したパスで判断する。 */
@Configuration(proxyBeanMethods = false)
class WebFilters {
    @Bean
    fun internalAuthFilter(verifier: InternalCallerVerifier): FilterRegistrationBean<InternalAuthFilter> =
        FilterRegistrationBean(InternalAuthFilter(verifier)).apply {
            addUrlPatterns("/*")
            order = 10
        }

    @Bean
    fun agentApiOpsGuardFilter(mode: InspectableModeProvider): FilterRegistrationBean<AgentApiOpsGuardFilter> =
        FilterRegistrationBean(AgentApiOpsGuardFilter(mode)).apply {
            addUrlPatterns("/*")
            order = AgentApiOpsGuardFilter.ORDER
        }
}
