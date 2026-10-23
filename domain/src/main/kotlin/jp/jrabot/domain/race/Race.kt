package jp.jrabot.domain.race

import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.LocalTime

/**
 * レース（DATA_MIGRATION_DESIGN §2.1 の `races`）。開催日・場・レース番号は [id] から得る。
 * コース条件のコード（トラック種別・馬場状態など）は JV-Data の仕様確認（T1.17）の後、特徴量を実装する段階（T3.6）で追加する。
 */
data class Race(
    override val tenantId: TenantId,
    val id: RaceId,
    /** 開催回。旧データには無いので null 可（YSCH/RA の再取得で補完）。 */
    val kai: Int?,
    /** 開催日目。旧データには無いので null 可。 */
    val nichime: Int?,
    /** 発走時刻（JST の現地時刻）。不明（`0000` や欠落）なら null。補正（00:00 を窓の開始時刻に置換する旧処理）はしない。 */
    val postTime: LocalTime?,
    val name: String?,
    val distanceMeters: Int?,
) : TenantScoped {
    init {
        require(kai == null || kai in 1..99) { "kai は 1〜99: $kai" }
        require(nichime == null || nichime in 1..99) { "nichime は 1〜99: $nichime" }
        require(distanceMeters == null || distanceMeters > 0) { "距離は正の値: $distanceMeters" }
        require(name == null || name.length <= 100) { "レース名は 100 文字以下" }
    }

    val date get() = id.date
    val venue get() = id.venue
    val raceNo get() = id.raceNo
}
