package jp.jrabot.persistence

import jp.jrabot.domain.DomainModule

/**
 * :persistence のマーカー。Doma の DAO・Entity・Repository 実装は内部（`internal`）で、公開するのは
 * [jp.jrabot.persistence.jdbc.DbSettings]・[jp.jrabot.persistence.jdbc.DataSourceFactory]・[PersistenceConfiguration] だけ。
 */
object PersistenceModule {
    const val NAME = "persistence"
    const val DEPENDS_ON = DomainModule.NAME
}
