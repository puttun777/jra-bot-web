package jp.jrabot.persistence

import jp.jrabot.domain.DomainModule

/** :persistence のマーカー。Doma の DAO・Entity は T2.4 で追加する。 */
object PersistenceModule {
    const val NAME = "persistence"
    const val DEPENDS_ON = DomainModule.NAME
}
