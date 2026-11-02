package jp.jrabot.domain.notify

import jp.jrabot.domain.notify.NoticeFixtures.race
import jp.jrabot.domain.notify.NoticeFixtures.win
import jp.jrabot.domain.odds.Market
import jp.jrabot.domain.ops.ModeProvider
import jp.jrabot.domain.ops.OpsMode
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.signal.NotificationState
import jp.jrabot.domain.signal.SignalId
import jp.jrabot.domain.tenant.TenantId
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalNotificationDispatcherTest {
    private val tenant = TenantId.of(1)
    private val other = TenantId.of(2)
    private val raceId = "202601040611"
    private val repo = InMemorySignalRepository()
    private val clock = FixedClock()

    /** 台本どおりに結果を返す Notifier。送られた通知を記録する。 */
    private class ScriptedNotifier(
        var result: () -> DeliveryResult = { DeliveryResult.DELIVERED },
        var configured: Boolean = true,
    ) : Notifier {
        val sent = mutableListOf<Notification>()

        override fun canDeliver(channel: NotificationChannel) = configured

        override fun send(notification: Notification): DeliveryResult {
            sent += notification
            return result()
        }
    }

    private var mode = OpsMode.NORMAL
    private val notifier = ScriptedNotifier()

    private fun dispatcher(
        stop: () -> Boolean = { false },
        n: Notifier = notifier,
        mention: MentionPolicy = MentionPolicy.NONE,
    ) = SignalNotificationDispatcher(repo, n, ModeProvider { mode }, clock, mention, stop)

    private fun batch(
        vararg ids: Pair<SignalId, Int>,
        t: TenantId = tenant,
    ) = RaceNoticeBatch(t, RaceId.parse(raceId), race, ids.map { (id, umaban) -> NoticeItem(id, win(umaban = umaban)) })

    private fun add(
        umaban: Int,
        t: TenantId = tenant,
    ) = repo.add(t, raceId, Market.WIN, umaban) to umaban

    @Test
    fun `UNSENT から CLAIMED を経て 全部送れたら SENT`() {
        val a = add(1)
        val b = add(2)
        val report = dispatcher().dispatchRace(batch(a, b), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.DELIVERED, report.status)
        assertEquals(listOf(a.first, b.first), report.sent)
        assertEquals(NotificationState.SENT, repo.stateOf(tenant, a.first))
        assertEquals(NotificationState.SENT, repo.stateOf(tenant, b.first))
        assertEquals(1, notifier.sent.size, "1 レースは 1 通知にまとめる")
        assertTrue(notifier.sent[0].body.contains("**検知シグナル: 2件**"))
        assertEquals(listOf("claim:1", "claim:2", "markSent:1", "markSent:2"), repo.calls, "claim → 送信 → 記録の順")
    }

    @Test
    fun `配送結果が不明なら SEND_UNKNOWN で 再送しない`() {
        notifier.result = { DeliveryResult.UNKNOWN }
        val a = add(1)
        val r1 = dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.UNKNOWN, r1.status)
        assertEquals(NotificationState.SEND_UNKNOWN, repo.stateOf(tenant, a.first))
        // 同じ batch をもう一度（再起動・再実行）: 送らない
        val r2 = dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.NOTHING_TO_SEND, r2.status)
        assertEquals(ItemSkip.NOT_UNSENT, r2.skipped[a.first])
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `確実に送れなかった場合も 状態を戻せないので SEND_UNKNOWN にして SENT にしない`() {
        notifier.result = { DeliveryResult.NOT_SENT }
        val a = add(1)
        val r = dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.NOT_SENT_RECORDED_UNKNOWN, r.status)
        assertEquals(DeliveryResult.NOT_SENT, r.delivery)
        assertEquals(NotificationState.SEND_UNKNOWN, repo.stateOf(tenant, a.first))
        assertTrue(r.sent.isEmpty())
    }

    @Test
    fun `SENT になった後は何度呼んでも送らない`() {
        val a = add(1)
        dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
        repeat(3) { assertEquals(DispatchStatus.NOTHING_TO_SEND, dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE).status) }
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `Notifier が例外を投げたら UNKNOWN として記録する`() {
        val a = add(1)
        val r = dispatcher(n = ScriptedNotifier({ throw IllegalStateException("boom") })).dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.UNKNOWN, r.status)
        assertEquals(NotificationState.SEND_UNKNOWN, repo.stateOf(tenant, a.first))
    }

    @Test
    fun `BACKFILL と判定不能は通知せず状態も変えない`() {
        val a = add(1)
        for (origin in listOf(NotificationOrigin.BACKFILL, NotificationOrigin.UNDETERMINED)) {
            val r = dispatcher().dispatchRace(batch(a), origin)
            assertEquals(DispatchStatus.SUPPRESSED_ORIGIN, r.status)
            assertNull(r.mode)
        }
        assertEquals(NotificationState.UNSENT, repo.stateOf(tenant, a.first))
        assertTrue(notifier.sent.isEmpty() && repo.calls.isEmpty())
    }

    @Test
    fun `NORMAL 以外のモードでは通知せず 未送信を SENT にもしない`() {
        val a = add(1)
        for (m in listOf(OpsMode.STARTING, OpsMode.STOPPING, OpsMode.STOPPED, OpsMode.UNKNOWN)) {
            mode = m
            val r = dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
            assertEquals(DispatchStatus.SUPPRESSED_MODE, r.status, m.name)
            assertEquals(m, r.mode)
        }
        assertEquals(NotificationState.UNSENT, repo.stateOf(tenant, a.first))
        assertTrue(notifier.sent.isEmpty() && repo.calls.isEmpty())
    }

    @Test
    fun `モードは毎回読み直し 前回の NORMAL を根拠にしない`() {
        val a = add(1)
        val b = add(2)
        val d = dispatcher()
        assertEquals(DispatchStatus.DELIVERED, d.dispatchRace(batch(a), NotificationOrigin.LIVE).status)
        mode = OpsMode.UNKNOWN
        assertEquals(DispatchStatus.SUPPRESSED_MODE, d.dispatchRace(batch(b), NotificationOrigin.LIVE).status)
        assertEquals(NotificationState.UNSENT, repo.stateOf(tenant, b.first))
    }

    @Test
    fun `停止要求中は新しい通知を始めない`() {
        val a = add(1)
        val r = dispatcher(stop = { true }).dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.SUPPRESSED_STOPPING, r.status)
        assertEquals(NotificationState.UNSENT, repo.stateOf(tenant, a.first))
    }

    @Test
    fun `宛先が未設定なら claim しない`() {
        val a = add(1)
        val r = dispatcher(n = ScriptedNotifier(configured = false)).dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.NOT_CONFIGURED, r.status)
        assertEquals(NotificationState.UNSENT, repo.stateOf(tenant, a.first))
        assertTrue(repo.calls.isEmpty())
    }

    @Test
    fun `他テナントの Signal は拒否し 状態に触れない`() {
        val mine = add(1)
        val theirs = add(2, other)
        val r = dispatcher().dispatchRace(batch(mine, theirs), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.DELIVERED, r.status)
        assertEquals(listOf(mine.first), r.sent)
        assertEquals(ItemSkip.NOT_FOUND, r.skipped[theirs.first].takeIf { theirs.first != mine.first } ?: ItemSkip.NOT_FOUND)
        assertEquals(NotificationState.UNSENT, repo.stateOf(other, theirs.first))
        assertTrue(
            notifier.sent
                .single()
                .body
                .contains("馬番1") &&
                !notifier.sent
                    .single()
                    .body
                    .contains("馬番2"),
        )
        assertEquals(tenant, notifier.sent.single().tenant)
    }

    @Test
    fun `レースや賭式が保存済みの Signal と食い違う項目は送らない`() {
        val a = add(1)
        val wrongRace = repo.add(tenant, "202601040711", Market.WIN, 2)
        val r =
            dispatcher().dispatchRace(
                RaceNoticeBatch(
                    tenant,
                    RaceId.parse(raceId),
                    race,
                    listOf(
                        NoticeItem(a.first, win(umaban = 1)),
                        NoticeItem(wrongRace, win(umaban = 2)),
                        NoticeItem(repo.add(tenant, raceId, Market.WIN, 5), win(umaban = 6)), // 馬番が違う
                        NoticeItem(repo.add(tenant, raceId, Market.WIDE, 7), win(umaban = 7)), // 賭式が違う
                        NoticeItem(a.first, win(umaban = 1)), // 重複
                    ),
                ),
                NotificationOrigin.LIVE,
            )
        assertEquals(listOf(a.first), r.sent)
        assertEquals(ItemSkip.RACE_MISMATCH, r.skipped[wrongRace])
        assertEquals(ItemSkip.DUPLICATE, r.skipped[a.first])
        assertEquals(2, r.skipped.values.count { it == ItemSkip.CONTENT_MISMATCH })
    }

    @Test
    fun `複数の Bot が同時に動いても 1 つの Signal は 1 回だけ送られる`() {
        val a = add(1)
        val b = add(2)
        val sends = AtomicInteger()
        val n =
            ScriptedNotifier({
                sends.incrementAndGet()
                Thread.sleep(20)
                DeliveryResult.DELIVERED
            })
        val pool = Executors.newFixedThreadPool(8)
        val go = CountDownLatch(1)
        val reports =
            (1..8).map {
                pool.submit<DispatchReport> {
                    go.await()
                    dispatcher(n = n).dispatchRace(batch(a, b), NotificationOrigin.LIVE)
                }
            }
        go.countDown()
        val results = reports.map { it.get() }
        pool.shutdown()
        // claim は Signal ごと（D-255）。同時に動くと a と b を別々の Bot が取り、通知が 2 通に分かれることがある。
        // 保証するのは『1 つの Signal は 1 回だけ送られる』こと（Signal ごとの勝者は 1 つ）で、通知の通数ではない
        val sentIds = results.flatMap { it.sent }
        assertEquals(2, sentIds.size, "各 Signal はちょうど 1 回だけ送られる（重複なし）: $sentIds")
        assertEquals(setOf(a.first, b.first), sentIds.toSet(), "欠落なし")
        val delivered = results.count { it.status == DispatchStatus.DELIVERED }
        assertTrue(delivered in 1..2, "勝者は Signal ごとに 1 つ（通知は 1〜2 通）: $delivered")
        assertEquals(delivered, sends.get(), "送信は勝った Bot の分だけ")
        assertEquals(8 - delivered, results.count { it.status == DispatchStatus.NOTHING_TO_SEND })
        assertEquals(NotificationState.SENT, repo.stateOf(tenant, a.first))
        assertEquals(NotificationState.SENT, repo.stateOf(tenant, b.first))
    }

    @Test
    fun `一部だけ claim に勝った場合 勝った分だけが本文に入り 結果が対応する`() {
        val a = add(1)
        val b = add(2)
        // b は他の Bot が先に claim 済み
        repo.claim(tenant, b.first)
        val r = dispatcher().dispatchRace(batch(a, b), NotificationOrigin.LIVE)
        assertEquals(listOf(a.first), r.sent)
        assertEquals(ItemSkip.NOT_UNSENT, r.skipped[b.first], "検査の時点で UNSENT でない")
        assertFalse(
            notifier.sent
                .single()
                .body
                .contains("馬番2"),
        )
        assertEquals(NotificationState.CLAIMED, repo.stateOf(tenant, b.first), "他者の claim に触れない")
    }

    @Test
    fun `claim の競合で負けた項目は本文から除く`() {
        val a = add(1)
        val b = add(2)
        // find の後・claim の前に他の Bot が b を claim するのを再現する
        val racing =
            object : SignalReaderProxy(repo) {
                override fun claim(
                    tenantId: TenantId,
                    id: SignalId,
                ): Boolean {
                    if (id == b.first) repo.claim(tenantId, id)
                    return super.claim(tenantId, id)
                }
            }
        val d = SignalNotificationDispatcher(racing, notifier, ModeProvider { mode }, clock)
        val r = d.dispatchRace(batch(a, b), NotificationOrigin.LIVE)
        assertEquals(listOf(a.first), r.sent)
        assertEquals(ItemSkip.CLAIM_LOST, r.skipped[b.first])
        assertFalse(
            notifier.sent
                .single()
                .body
                .contains("馬番2"),
        )
    }

    @Test
    fun `送信結果を DB に記録できなかった Signal は CLAIMED のまま報告し 後で SEND_UNKNOWN に回収する`() {
        val a = add(1)
        repo.failMarkSentFor = setOf(a.first.value)
        val r = dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertEquals(DispatchStatus.DELIVERED, r.status)
        assertEquals(listOf(a.first), r.recordFailures)
        assertTrue(r.sent.isEmpty())
        assertEquals(NotificationState.CLAIMED, repo.stateOf(tenant, a.first))
        // 再実行しても送らない
        assertEquals(DispatchStatus.NOTHING_TO_SEND, dispatcher().dispatchRace(batch(a), NotificationOrigin.LIVE).status)
        // 古い claim は SEND_UNKNOWN へ（再送しない）
        clock.instant = clock.instant.plus(Duration.ofHours(1))
        assertEquals(emptyList(), dispatcher().recoverStaleClaims(tenant, Duration.ofHours(2)), "新しい claim は奪わない")
        assertEquals(listOf(a.first), dispatcher().recoverStaleClaims(tenant, Duration.ofMinutes(30)))
        assertEquals(NotificationState.SEND_UNKNOWN, repo.stateOf(tenant, a.first))
        assertEquals(1, notifier.sent.size)
    }

    @Test
    fun `回収は NORMAL のときだけ 他テナントに触れない`() {
        val a = add(1)
        val o = add(1, other)
        repo.claim(tenant, a.first)
        repo.claim(other, o.first)
        clock.instant = Instant.parse("2026-01-04T10:00:00Z")
        mode = OpsMode.UNKNOWN
        assertEquals(emptyList(), dispatcher().recoverStaleClaims(tenant, Duration.ZERO))
        assertEquals(NotificationState.CLAIMED, repo.stateOf(tenant, a.first))
        mode = OpsMode.NORMAL
        assertEquals(listOf(a.first), dispatcher().recoverStaleClaims(tenant, Duration.ZERO))
        assertEquals(NotificationState.CLAIMED, repo.stateOf(other, o.first))
    }

    @Test
    fun `メンション方針は本文の先頭に出る`() {
        val a = add(1)
        dispatcher(mention = MentionPolicy.forUser("123456789012345678")).dispatchRace(batch(a), NotificationOrigin.LIVE)
        assertTrue(
            notifier.sent
                .single()
                .body
                .startsWith("<@123456789012345678>\n"),
        )
    }
}

/** claim の競合を再現するために、一部の操作を差し替えられるようにした委譲。 */
internal open class SignalReaderProxy(
    private val delegate: InMemorySignalRepository,
) : jp.jrabot.domain.signal.SignalRepository by delegate
