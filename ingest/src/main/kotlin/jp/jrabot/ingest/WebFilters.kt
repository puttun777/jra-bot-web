package jp.jrabot.ingest

import jp.jrabot.cloudgcp.auth.InternalCallerVerifier
import jp.jrabot.domain.ingest.AgentAuthenticator
import jp.jrabot.domain.ingest.AgentCredentialStore
import jp.jrabot.domain.ingest.NonceStore
import jp.jrabot.domain.ingest.SigningKeyCipher
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.time.Clock
import jp.jrabot.ingest.agent.AgentAuthFilter
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

    /** エージェント認証（モードガードの後）。KEK が未設定なら null の認証器で、すべて 503（未認証で業務データを保存しない）。 */
    @Bean
    fun agentAuthFilter(
        credentials: AgentCredentialStore,
        nonces: NonceStore,
        clock: Clock,
        settings: IngestSettings,
    ): FilterRegistrationBean<AgentAuthFilter> =
        FilterRegistrationBean(
            AgentAuthFilter(
                settings.agentKeys?.let { AgentAuthenticator(credentials, nonces, SigningKeyCipher(it)) },
                nonces,
                clock,
                settings.ingest.maxBodyBytes,
            ),
        ).apply {
            addUrlPatterns("/*")
            order = AgentAuthFilter.ORDER
        }
}
