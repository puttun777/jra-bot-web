package jp.jrabot.cloudgcp

import jp.jrabot.domain.DomainModule

/** :cloud-gcp のマーカー。ModeProvider などのポート実装は T2.7 以降で追加する。 */
object CloudGcpModule {
    const val NAME = "cloud-gcp"
    const val DEPENDS_ON = DomainModule.NAME
}
