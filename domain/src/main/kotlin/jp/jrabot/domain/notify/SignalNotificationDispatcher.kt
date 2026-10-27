package jp.jrabot.domain.notify

import jp.jrabot.domain.decision.BetType
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.signal.SignalRepository
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.domain.time.Clock
import java.time.Duration

/**
 * 通知の出どころ。**呼び出し側が必ず明示する（既定値なし）**。通知してよいのは [LIVE] だけ。
 * [BACKFILL]（過去データの取込み・再計算）では通常の競馬予測通知を送らない。判定できないときは [UNDETERMINED] を渡す（LIVE とみなして送らない）。
 */
enum class NotificationOrigin { LIVE, BACKFILL, UNDETERMINED }

/** 通知に含める 1 件。[signalId] は保存済みの Signal（`signals.id`）、[view] はその表示モデル（[NoticeViews.from]）。 */
data class NoticeItem(
    val signalId: SignalId,
    val view: SignalView,
)

/** 1 レース分の通知（複数シグナルを 1 つの通知にまとめる）。 */
data class RaceNoticeBatch(
    val tenantId: TenantId,
    val raceId: RaceId,
    val race: RaceLabel,
    val items: List<NoticeItem>,
) {
    init {
        require(items.isNotEmpty()) { "通知する項目が空" }
    }
}

enum class DispatchStatus {
    /** 全部送れた。claim した Signal を SENT にした。 */
    DELIVERED,

    /** 送れたか不明（一部だけ送信済みを含む）。claim した Signal を SEND_UNKNOWN にした。再送しない。 */
    UNKNOWN,

    /** 確実に送れなかったが、claim 済みで状態を戻せないため SEND_UNKNOWN にした（取りこぼし。管理画面で確認する）。 */
    NOT_SENT_RECORDED_UNKNOWN,

    /** [NotificationOrigin] が LIVE ではない。何も変更していない。 */
    SUPPRESSED_ORIGIN,

    /** 運用モードが NORMAL ではない（UNKNOWN を含む）。何も変更していない。 */
    SUPPRESSED_MODE,

    /** 停止要求中。何も変更していない。 */
    SUPPRESSED_STOPPING,

    /** 宛先が未設定または不正。claim していない。 */
    NOT_CONFIGURED,

    /** 適格な項目が無い、または claim に全部負けた（他が先に claim 済み）。何も送っていない。 */
    NOTHING_TO_SEND,
}

enum class ItemSkip {
    /** 該当テナントに無い Signal（存在しない・他テナント）。 */
    NOT_FOUND,

    /** UNSENT ではない（既に CLAIMED / SENT / SEND_UNKNOWN）。 */
    NOT_UNSENT,

    /** 保存済みの Signal のレースが batch と違う。 */
    RACE_MISMATCH,

    /** 保存済みの Signal の賭式・馬番が表示モデルと違う。 */
    CONTENT_MISMATCH,

    /** 同じ Signal が batch に複数ある。 */
    DUPLICATE,

    /** 他が先に claim した。 */
    CLAIM_LOST,
}

/** 1 回の通知の結果。ログ・管理画面向け。URL・秘密・本文は含まない。 */
data class DispatchReport(
    val status: DispatchStatus,
    val mode: OpsMode?,
    val delivery: DeliveryResult?,
    /** SENT にした Signal。 */
    val sent: List<SignalId> = emptyList(),
    /** SEND_UNKNOWN にした Signal。 */
    val sendUnknown: List<SignalId> = emptyList(),
    val skipped: Map<SignalId, ItemSkip> = emptyMap(),
    /** 送信結果を DB に記録できなかった Signal（CLAIMED のまま。[SignalNotificationDispatcher.recoverStaleClaims] が SEND_UNKNOWN にする）。 */
    val recordFailures: List<SignalId> = emptyList(),
)

/**
 * 通知の調整役（T3.7）。1 レースの複数シグナルを 1 通知にまとめ、**claim → 送信 → 結果の記録**を行う（CONTRACTS §9.2・RACE_CALENDAR_DESIGN §6.3）。
 *
 * - 順序: 出どころ（LIVE のみ）→ 運用モード（毎回読む。NORMAL のみ）→ 停止要求 → 宛先の確認 → 項目の検査（テナント・状態・整合）→ 本文の試作 →
 *   Signal を 1 件ずつ原子的に claim（勝った分だけが本文に入る）→ 送信 → 記録。
 * - 状態は UNSENT → CLAIMED → SENT | SEND_UNKNOWN の一方向。**SENT にするのは全部送れたとき（DELIVERED）だけ**。UNKNOWN（一部送信を含む）は SEND_UNKNOWN。
 *   確実に送れなかった（NOT_SENT）場合も、claim 済みの状態は戻せない（SignalWriter に解放が無い）ので SEND_UNKNOWN にする（取りこぼしを選び、二重通知を避ける）。
 * - exactly-once は保証しない。保証するのは『自動で再送しない（at-most-once）』こと。claim は DB の条件つき UPDATE なので、同時に動く複数の Bot の間でも勝者は 1 つ。
 * - このクラスは送信先の URL を扱わない（[Notifier] の責務）。例外を握りつぶさず、送信（[Notifier.send]）の例外は UNKNOWN として扱う。
 */
class SignalNotificationDispatcher(
    private val signals: SignalRepository,
    private val notifier: Notifier,
    private val modes: ModeProvider,
    private val clock: Clock,
    private val mention: MentionPolicy = MentionPolicy.NONE,
    /** 停止要求（SIGTERM など）の有無。true の間は新しい通知を始めない。 */
    private val stopRequested: () -> Boolean = { false },
) {
    fun dispatchRace(
        batch: RaceNoticeBatch,
        origin: NotificationOrigin,
    ): DispatchReport {
        if (origin != NotificationOrigin.LIVE) return DispatchReport(DispatchStatus.SUPPRESSED_ORIGIN, null, null)
        val mode = modes.current()
        if (!mode.allowsWork) return DispatchReport(DispatchStatus.SUPPRESSED_MODE, mode, null)
        if (stopRequested()) return DispatchReport(DispatchStatus.SUPPRESSED_STOPPING, mode, null)
        if (!notifier.canDeliver(NotificationChannel.SIGNAL)) return DispatchReport(DispatchStatus.NOT_CONFIGURED, mode, null)

        val skipped = LinkedHashMap<SignalId, ItemSkip>()
        val eligible = eligibleItems(batch, skipped)
        if (eligible.isEmpty()) return DispatchReport(DispatchStatus.NOTHING_TO_SEND, mode, null, skipped = skipped)
        // 本文を先に試作する（形式の誤りで claim だけ残すことを避ける）
        NoticeFormatter.formatGrouped(batch.race, eligible.map { it.view }, mention)

        val claimed = mutableListOf<NoticeItem>()
        for (item in eligible) {
            if (signals.claim(batch.tenantId, item.signalId)) claimed += item else skipped[item.signalId] = ItemSkip.CLAIM_LOST
        }
        if (claimed.isEmpty()) return DispatchReport(DispatchStatus.NOTHING_TO_SEND, mode, null, skipped = skipped)

        val result: DeliveryResult =
            try {
                val body = NoticeFormatter.formatGrouped(batch.race, claimed.map { it.view }, mention)
                notifier.send(Notification(batch.tenantId, NotificationChannel.SIGNAL, NoticeFormatter.subject(batch.race), body))
            } catch (e: Exception) {
                DeliveryResult.UNKNOWN
            }
        return record(batch.tenantId, claimed.map { it.signalId }, result, mode, skipped)
    }

    private fun eligibleItems(
        batch: RaceNoticeBatch,
        skipped: MutableMap<SignalId, ItemSkip>,
    ): List<NoticeItem> {
        val seen = HashSet<SignalId>()
        val out = mutableListOf<NoticeItem>()
        for (item in batch.items) {
            if (!seen.add(item.signalId)) {
                skipped[item.signalId] = ItemSkip.DUPLICATE
                continue
            }
            val row = signals.find(batch.tenantId, item.signalId)
            val reason =
                when {
                    row == null || row.tenantId != batch.tenantId -> ItemSkip.NOT_FOUND
                    row.state != NotificationState.UNSENT -> ItemSkip.NOT_UNSENT
                    row.raceId != batch.raceId -> ItemSkip.RACE_MISMATCH
                    BetType.fromLabelOrNull(item.view.betType)?.marketCode != row.market.code || row.umaban.value != item.view.umaban -> ItemSkip.CONTENT_MISMATCH
                    else -> null
                }
            if (reason == null) out += item else skipped[item.signalId] = reason
        }
        return out
    }

    private fun record(
        tenantId: TenantId,
        ids: List<SignalId>,
        result: DeliveryResult,
        mode: OpsMode,
        skipped: Map<SignalId, ItemSkip>,
    ): DispatchReport {
        val delivered = result == DeliveryResult.DELIVERED
        val recorded = mutableListOf<SignalId>()
        val failures = mutableListOf<SignalId>()
        for (id in ids) {
            val ok =
                try {
                    if (delivered) signals.markSent(tenantId, id) else signals.markSendUnknown(tenantId, id)
                } catch (e: RuntimeException) {
                    false
                }
            if (ok) recorded += id else failures += id
        }
        val status =
            when (result) {
                DeliveryResult.DELIVERED -> DispatchStatus.DELIVERED
                DeliveryResult.UNKNOWN -> DispatchStatus.UNKNOWN
                DeliveryResult.NOT_SENT -> DispatchStatus.NOT_SENT_RECORDED_UNKNOWN
            }
        return DispatchReport(
            status = status,
            mode = mode,
            delivery = result,
            sent = if (delivered) recorded else emptyList(),
            sendUnknown = if (delivered) emptyList() else recorded,
            skipped = skipped,
            recordFailures = failures,
        )
    }

    /**
     * claim したまま結果を記録できなかった Signal（プロセスの停止・DB 障害）を SEND_UNKNOWN にする。**再送はしない**（二重通知より取りこぼしを選ぶ）。
     * [staleAfter] より古い claim だけが対象: 他のプロセスが送信中の claim を奪わない。運用モードが NORMAL のときだけ書込む。戻り値は SEND_UNKNOWN にした Signal。
     */
    fun recoverStaleClaims(
        tenantId: TenantId,
        staleAfter: Duration,
        limit: Int = 1000,
    ): List<SignalId> {
        require(!staleAfter.isNegative) { "staleAfter は 0 以上" }
        if (!modes.current().allowsWork) return emptyList()
        val cutoff = clock.now().minus(staleAfter)
        val recovered = mutableListOf<SignalId>()
        for (s in signals.listClaimedBefore(tenantId, cutoff, limit)) {
            val id = s.id ?: continue
            if (signals.markSendUnknown(tenantId, id)) recovered += id
        }
        return recovered
    }
}
