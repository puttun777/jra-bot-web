package jp.jrabot.domain.botrun

import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.repository.InsertOutcome
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.tenant.TenantScoped
import java.time.Instant

/**
 * 判定の記録（`race_evaluations` の 1 行。旧 `race_evaluations`）。自然キーは `(tenantId, raceId, snapshotAt)`（サイクルの冪等キー: 同じオッズの観測を
 * 再評価しても行は増えない）。[modelId] は使った ML モデル（run の開始時に固定したもの。市場確率だけのときは null）、[fallbackReason] は
 * ML を要求したのに外した（または無効だった）理由のコード（`FallbackReason.name`）。
 */
data class RaceEvaluationRecord(
    override val tenantId: TenantId,
    val raceId: RaceId,
    val snapshotAt: Instant,
    val modelTypeUsed: String?,
    val modelId: String?,
    val fallbackReason: String?,
    /** 判定対象の賭式（`["単勝","ワイド"]` のような JSON 配列。null は全賭式）。 */
    val betTypesJson: String?,
    val priorityMode: String,
    val evThreshold: Double,
    val winOddsMax: Double?,
    val winProbMin: Double?,
    val winRatioMin: Double?,
    val bestEv: Double?,
    /** 採用しなかった理由の件数（`{"EV_NOT_ABOVE_THRESHOLD":3}` のような JSON オブジェクト）。 */
    val rejectionSummaryJson: String,
    /** 採用した（通知候補にした）シグナルの数。 */
    val signalCount: Int,
) : TenantScoped {
    init {
        require(signalCount >= 0) { "signalCount は 0 以上" }
        require(modelId == null || modelId.length <= 64) { "modelId は 64 文字以下" }
        require(fallbackReason == null || fallbackReason.length <= 64) { "fallbackReason は 64 文字以下" }
    }
}

/**
 * 判定の記録の書込み。**冪等**: 同じ `(tenantId, raceId, snapshotAt)` が既にあれば [InsertOutcome.ALREADY_EXISTS]（行を変更しない）。
 * 旧データには UNIQUE が無い（移行データに重複があり得る: D-066）ので、重複の防止は『実行は 1 つだけ』のリース（`bot_run` の UNIQUE）に依存する。
 */
fun interface RaceEvaluationWriter {
    fun insertIfAbsent(record: RaceEvaluationRecord): InsertOutcome
}

/**
 * そのレースで**通知を試みた記録**（Signal が UNSENT 以外 = CLAIMED / SENT / SEND_UNKNOWN）があるか。checkpoint が欠損・破損していても残る
 * DB 側の証拠で、『既に通知したタイミングがあるのに checkpoint にその記録が無い』異常を検出するのに使う（E の暫定対策）。すべてテナントを引数に取る。
 */
fun interface NotifiedSignalProbe {
    fun hasNotificationAttempt(
        tenantId: TenantId,
        raceId: RaceId,
    ): Boolean
}

/** 出走表の馬名（通知の理由文用。`FeatureSource` は馬名を持たない）。すべてテナントを引数に取る。 */
fun interface RaceEntryReader {
    /** 馬番 → 馬名。出走表が無ければ空。馬名の無い馬は含めない。 */
    fun horseNames(
        tenantId: TenantId,
        raceId: RaceId,
    ): Map<Int, String>
}
