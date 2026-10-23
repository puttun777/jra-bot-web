package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.ops.ModeSnapshot
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.time.Clock
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * [ModeProvider] の実装（CONTRACTS §5.2）。読取りは [OpsStateSource]（GCS またはローカルファイル）、検証は [OpsStateParser]。
 * GCS 版とローカル版は同じこのクラスと検証を使う。
 *
 * - **キャッシュ**: 成功した結果だけを [cacheTtl]（既定 15 秒）保持する。15 秒を過ぎたら再取得し、再取得に失敗したら UNKNOWN
 *   （古い NORMAL を使い回さない）。時刻は注入した [Clock] で判定する。
 * - **再試行の間隔**: 失敗した（UNKNOWN になった）あとは、[retryInterval]（既定 5 秒）の間は取得せずに UNKNOWN を返す。失敗はキャッシュしない。
 * - **seq の単調性**: 受理した最大の `seq` をキャッシュとは独立に保持する。小さい `seq` を読んだら UNKNOWN（WARN）にしてキャッシュしない。
 *   同じ `seq` で `mode` または `desired.mode` が違う場合も、書込みごとに `seq` が +1 される契約と矛盾するので UNKNOWN にする。
 *   同じ `seq` で内容が同じなら正常（再取得）。巻き戻りの検知はこのプロセスの存続中ずっと有効（正本を意図して巻き戻したらプロセスの再起動が必要）。
 * - **並行**: 取得・検証・キャッシュ更新・seq の更新は 1 つのロックの下で行う。同時に呼ばれても GCS への取得は 1 回で、待っていた呼び出しは
 *   その結果（または失敗の抑制）を見る。ロックの待ちは取得 1 回分（≤ 3 秒）。
 * - **エラー**: 読取りの失敗（[OpsStateReadException]）と検証の失敗は UNKNOWN に変換して例外にしない。それ以外の例外（実装の不具合）は握りつぶさず伝播する。
 * - **ログ**: `mode`・`seq` が変わったときだけ INFO。UNKNOWN は理由が変わったとき（と [warnReminder] ごと）に WARN。内容（JSON）・例外のスタックは出さない。
 */
class CachingModeProvider(
    private val source: OpsStateSource,
    private val clock: Clock,
    private val cacheTtl: Duration = CACHE_TTL,
    private val retryInterval: Duration = MIN_RETRY_INTERVAL,
    private val warnReminder: Duration = WARN_REMINDER,
    private val log: Logger = LoggerFactory.getLogger(CachingModeProvider::class.java),
    private val sourceLabel: String = SOURCE_GCS,
) : InspectableModeProvider {
    private class Cached(
        val state: OpsState,
        val fetchedAt: Instant,
    ) {
        val mode: OpsMode get() = state.mode
    }

    @Volatile
    private var cached: Cached? = null

    private val lock = ReentrantLock()

    // 以下はロックの下でだけ読み書きする
    private var lastAccepted: OpsState? = null
    private var lastFailureAt: Instant? = null
    private var lastInfo: Pair<OpsMode, Long>? = null
    private var lastWarnKey: String? = null
    private var lastWarnAt: Instant? = null

    override fun current(): OpsMode = resolve().first

    /**
     * 診断用の写し。[current] と同じ経路（キャッシュ・単一飛行・検証）で解決した値だけを返し、取得し直さない。
     * 採用された状態が無い（UNKNOWN）ときは詳細を持たない。
     */
    override fun snapshot(): ModeSnapshot {
        val (mode, adopted) = resolve()
        if (mode == OpsMode.UNKNOWN || adopted == null) return ModeSnapshot.unknown()
        val ageMs = Duration.between(adopted.fetchedAt, clock.now()).toMillis().coerceAtLeast(0)
        return ModeSnapshot(mode, adopted.state.desiredMode, adopted.state.seq, ageMs, sourceLabel)
    }

    private fun resolve(): Pair<OpsMode, Cached?> {
        freshOrNull(clock.now())?.let { return it.mode to it }
        return lock.withLock {
            val now = clock.now()
            freshOrNull(now)?.let { return@withLock it.mode to it }
            if (withinRetryWindow(now)) return@withLock OpsMode.UNKNOWN to null
            val mode = refresh(now)
            mode to cached?.takeIf { mode != OpsMode.UNKNOWN }
        }
    }

    /** 時計が戻っている場合（now < fetchedAt）は期限切れとして扱う（古い値を使い続けない）。 */
    private fun freshOrNull(now: Instant): Cached? = cached?.takeIf { !now.isBefore(it.fetchedAt) && now.isBefore(it.fetchedAt.plus(cacheTtl)) }

    private fun withinRetryWindow(now: Instant): Boolean {
        val failedAt = lastFailureAt ?: return false
        return !now.isBefore(failedAt) && now.isBefore(failedAt.plus(retryInterval))
    }

    private fun refresh(startedAt: Instant): OpsMode {
        val bytes =
            try {
                source.read()
            } catch (e: OpsStateReadException) {
                return fail(e.reason, e.message ?: "")
            }
        return when (val parsed = OpsStateParser.parse(bytes)) {
            is ParseResult.Invalid -> fail(parsed.reason, parsed.detail)
            is ParseResult.Valid -> accept(parsed.state, startedAt)
        }
    }

    private fun accept(
        state: OpsState,
        fetchedAt: Instant,
    ): OpsMode {
        val last = lastAccepted
        if (last != null) {
            if (state.seq < last.seq) return fail(OpsFailureReason.SEQ_ROLLBACK, "seq=${state.seq} < 受理済み=${last.seq}")
            if (state.seq == last.seq && (state.mode != last.mode || state.desiredMode != last.desiredMode)) {
                return fail(OpsFailureReason.SEQ_CONFLICT, "seq=${state.seq} で内容が異なる")
            }
        }
        lastAccepted = state
        lastFailureAt = null
        lastWarnKey = null
        cached = Cached(state, fetchedAt)
        val reported = state.mode to state.seq
        if (lastInfo != reported) {
            log.info("ops mode: mode={} seq={}", state.mode, state.seq)
            lastInfo = reported
        }
        return state.mode
    }

    private fun fail(
        reason: OpsFailureReason,
        detail: String,
    ): OpsMode {
        val now = clock.now()
        cached = null
        lastFailureAt = now
        lastInfo = null // 復旧したとき、同じ mode・seq でも INFO を出す
        val key = "$reason:$detail"
        val reminderDue = lastWarnAt?.let { !now.isBefore(it.plus(warnReminder)) } ?: true
        if (key != lastWarnKey || reminderDue) {
            log.warn("ops mode UNKNOWN: reason={} detail={}", reason, detail)
            lastWarnKey = key
            lastWarnAt = now
        }
        return OpsMode.UNKNOWN
    }

    companion object {
        const val SOURCE_GCS = "gcs"
        const val SOURCE_FILE = "file"

        /** CONTRACTS §5.2: 成功した結果を 15 秒キャッシュする。 */
        val CACHE_TTL: Duration = Duration.ofSeconds(15)

        /** CONTRACTS §5.2: 失敗後の再試行は最短 5 秒間隔。 */
        val MIN_RETRY_INTERVAL: Duration = Duration.ofSeconds(5)

        /** 同じ原因の UNKNOWN が続くときの WARN の再掲間隔。 */
        val WARN_REMINDER: Duration = Duration.ofMinutes(5)
    }
}
