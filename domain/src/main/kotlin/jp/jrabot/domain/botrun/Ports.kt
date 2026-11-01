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

/** 判定の記録の結果。[id] は `race_evaluations.id`（既存の行があればその行）。 */
data class RecordedEvaluation(
    val outcome: InsertOutcome,
    val id: Long,
)

/**
 * 判定の記録の書込み。**冪等**: 同じ `(tenantId, raceId, snapshotAt)` の新方式の行が既にあれば [InsertOutcome.ALREADY_EXISTS]（行を変更しない）。
 * 新方式の行の一意性は DB が守る（V5 の生成列 `uq_native` の UNIQUE）。旧データ（`legacy_id` あり）には重複があり得る（D-066）が、そこには触れない。
 * 評価結果の一意性（観測時刻のキー）と、通知タイミングの一意性（[TimingNoticeStore]）は別のキー。
 */
interface RaceEvaluationWriter {
    fun record(record: RaceEvaluationRecord): RecordedEvaluation

    fun insertIfAbsent(record: RaceEvaluationRecord): InsertOutcome = record(record).outcome
}

/**
 * 確定した通知タイミング 1 件（`race_timing_notice` の 1 行）。[noticeJson] は [jp.jrabot.domain.notify.NoticeSnapshot] の正準 JSON、[noticeSha256] はそのハッシュ、
 * [noticeSchema] は版。追記専用で、確定した内容は変えない。
 */
data class TimingNotice(
    override val tenantId: TenantId,
    val raceId: RaceId,
    val timingCenti: Int,
    val runId: String,
    val evaluationId: Long,
    val oddsObservedAt: Instant,
    val noticeSchema: Int,
    val noticeJson: String,
    val noticeSha256: String,
    val firedAt: Instant,
) : TenantScoped {
    init {
        require(timingCenti in TimingKey.MIN_CENTI..TimingKey.MAX_CENTI) { "timingCenti の範囲" }
    }
}

/**
 * 通知タイミングの永続的な確定（V5）。**同じレース・同じ評価タイミングは 1 回しか確定できない**（`(tenant_id, race_id, timing_centi)` の PK）。
 * オッズの観測時刻が変わっても、checkpoint が欠損・破損・古くても変わらない。確定は通知（Discord への送信）の**前**に行い、確定した内容から通知を作る。
 * 確定の後・送信の前に落ちた場合は自動で再送しない（at-most-once。exactly-once は保証しない: D-18）。
 */
interface TimingNoticeStore {
    /** 確定済みのタイミング（`timing_centi`）。 */
    fun firedCenti(
        tenantId: TenantId,
        raceId: RaceId,
    ): Set<Int> = list(tenantId, raceId).map { it.timingCenti }.toSet()

    /**
     * [notices]（同じレース・同じ内容で、タイミングだけが違う行）を 1 つのトランザクションで確定する。新しく確定できたタイミング（`timing_centi`）を返す。
     * 既に確定済みのタイミングは何も変えず、戻り値に含めない。全部が既存なら空。
     */
    fun claim(notices: List<TimingNotice>): Set<Int>

    /** そのレースの確定済みのタイミング（古い順）。 */
    fun list(
        tenantId: TenantId,
        raceId: RaceId,
    ): List<TimingNotice>

    fun find(
        tenantId: TenantId,
        raceId: RaceId,
        timingCenti: Int,
    ): TimingNotice?
}

/**
 * そのレースで**通知を試みた Signal**（UNSENT 以外 = CLAIMED / SENT / SEND_UNKNOWN）の観測時刻（`observed_at`）の集合。確定した通知タイミング
 * （[TimingNoticeStore]）の `odds_observed_at` で説明できない通知は、V5 より前の版が送ったもの（確定の記録が無い）と判断する。
 * checkpoint も欠損・破損していると、どのタイミングを通知済みか分からないので、そのレースは通知を止める。すべてテナントを引数に取る。
 */
fun interface NotifiedSignalProbe {
    fun notifiedObservations(
        tenantId: TenantId,
        raceId: RaceId,
    ): Set<Instant>
}

/** [NotifiedSignalProbe] の実装（保存済みの Signal の状態から）。 */
class SignalNotifiedProbe(
    private val signals: jp.jrabot.domain.signal.SignalReader,
) : NotifiedSignalProbe {
    override fun notifiedObservations(
        tenantId: TenantId,
        raceId: RaceId,
    ): Set<Instant> =
        signals
            .listByRace(tenantId, raceId)
            .filter { it.state != jp.jrabot.domain.signal.NotificationState.UNSENT }
            .map { it.observedAt }
            .toSet()
}

/** 出走表の馬名（通知の理由文用。`FeatureSource` は馬名を持たない）。すべてテナントを引数に取る。 */
fun interface RaceEntryReader {
    /** 馬番 → 馬名。出走表が無ければ空。馬名の無い馬は含めない。 */
    fun horseNames(
        tenantId: TenantId,
        raceId: RaceId,
    ): Map<Int, String>
}
