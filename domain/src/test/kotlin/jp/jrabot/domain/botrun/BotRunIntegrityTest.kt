package jp.jrabot.domain.botrun

import jp.jrabot.domain.bot.BotRunState
import jp.jrabot.domain.botrun.testing.StubJudge
import jp.jrabot.domain.calendar.Cal
import jp.jrabot.domain.calendar.CalendarSettings
import jp.jrabot.domain.notify.NoticeSnapshot
import jp.jrabot.domain.notify.NotificationOrigin
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.signal.NotificationState
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 監査（2026-10-27）の C1/C2・E に対する回帰試験。合成データ・手で進める時計だけを使う。
 * - C1/C2: 予定を信頼できず評価を逃したのに SUCCEEDED にしない。正常な 0 件・中止・通知対象なしは異常にしない。
 * - E: checkpoint が欠損・破損していても、通知済みのレースを新しいオッズで再通知しない（初回の空 checkpoint は正常）。
 */
class BotRunIntegrityTest {
    private fun staleCfg() = Kit.defaultConfig().copy(calendar = CalendarSettings(Cal.WATCH, staleAfter = Duration.ofHours(3)))

    private fun errorsOf(k: Kit) = k.notifier.errors.map { it.subject }

    // ---- C1/C2 ----

    @Test
    fun `実行中に予定が古くなり評価を逃したら SUCCEEDED にしない FAILED と警告`() {
        // 予定の確認は 06:00。staleAfter=3h なので 09:00 から信頼できない。R1 の 5 分前（09:50）にはもう評価できない
        val k = Kit(config = staleCfg())
        val out = k.run()
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(RunExit.FAILURE, out.exitCode)
        assertEquals(BotRunState.FAILED, out.state)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(k.evaluations.rows.isEmpty())
        assertTrue(errorsOf(k).any { it.contains("EVALUATION_OPPORTUNITY_LOST") }, "警告が出る: ${errorsOf(k)}")
        assertEquals(
            BotRunState.FAILED,
            k.runs
                .all()
                .single()
                .state,
        )
    }

    @Test
    fun `一部のレースだけ信頼できなくなった場合も 評価を逃したレースがあれば FAILED`() {
        // R1 は評価できるが、予定の確認が古くなった後の R3 は評価できない（staleAfter=3h40m → 09:40 から古い。R1 の 5 分前 09:50 には既に古いので別の設定）
        val k = Kit(config = Kit.defaultConfig().copy(calendar = CalendarSettings(Cal.WATCH, staleAfter = Duration.ofHours(4).plusMinutes(30))))
        val out = k.run()
        // 10:30 を過ぎると古い: R1・R2 は評価済み、R3（発走 10:45、5 分前 10:40）は逃した
        assertEquals(2, k.notifier.signals.size)
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
    }

    @Test
    fun `評価した結果 通知対象が 0 件でも正常終了 異常にしない`() {
        val k = Kit(judge = StubJudge { emptyList() })
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(RunExit.OK, out.exitCode)
        assertEquals(3, k.evaluations.rows.size)
        assertTrue(k.notifier.signals.isEmpty())
        assertTrue(errorsOf(k).isEmpty(), "警告なし: ${errorsOf(k)}")
    }

    @Test
    fun `中止されたレースは評価しなくても正常終了`() {
        val races = Cal.races(count = 3).mapIndexed { i, r -> if (i == 1) r.copy(status = RaceScheduleStatus.CANCELLED) else r }
        val k = Kit(races = races)
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(2, k.evaluations.rows.size)
        assertTrue(errorsOf(k).isEmpty(), "警告なし: ${errorsOf(k)}")
    }

    @Test
    fun `オッズが一度も届かなかったレースは 必要な評価機会の喪失で FAILED exit 1 と警告 再試行しない`() {
        val k = Kit()
        k.autoOdds = false
        val out = k.run()
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(RunExit.FAILURE, out.exitCode, "回復できない喪失は 1（一時障害の 75 ではない）")
        assertEquals(BotRunState.FAILED, out.state)
        assertTrue(errorsOf(k).any { it.contains("EVALUATION_OPPORTUNITY_LOST") }, "警告が出る: ${errorsOf(k)}")
        assertTrue(k.timings.rows.isEmpty(), "評価できなかったタイミングは確定しない")

        // 同じ日を再実行しても FAILED・警告を繰り返さない（全レースが発走後で、もう評価できない）。何も書かず exit 0
        val runsBefore = k.runs.all().size
        val errorsBefore = k.notifier.errors.size
        val again = k.run(k.request(owner = "owner-B"))
        assertEquals(RunExit.OK, again.exitCode)
        assertEquals("EVALUATION_LOSS_ALREADY_RECORDED", again.reason)
        assertEquals(runsBefore, k.runs.all().size)
        assertEquals(errorsBefore, k.notifier.errors.size)
    }

    // ---- E: 通知タイミングは DB が正（checkpoint の欠損・破損・古い状態でも再通知しない） ----

    private fun interruptedAfterR1(k: Kit) {
        k.onPause = { if (k.clock.now() >= k.at(9, 52)) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertEquals(1, k.notifier.signals.size)
        k.stop = false
        k.onPause = {}
    }

    private fun r1Observations(k: Kit) =
        k.signals
            .all()
            .filter { it.raceId == k.raceIds[0] }
            .map { it.observedAt }
            .toSet()

    private fun assertNoRenotify(
        k: Kit,
        out: RunOutcome,
    ) {
        assertEquals("ALL_RACES_OFF", out.reason, "DB の確定記録で復元できるので隔離も喪失もない")
        assertEquals(3, k.notifier.signals.size, "R1 は再通知されない。R2・R3 は通常どおり")
        assertEquals(1, r1Observations(k).size, "R1 に新しいオッズの観測の Signal が増えない（同じタイミングを再評価していない）")
        assertEquals(3, k.timings.rows.size, "各レース 1 つのタイミング")
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
    }

    @Test
    fun `checkpoint が壊れていても 新しいオッズで同じタイミングを再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.runs.rewriteCheckpoints { "{broken" }
        assertNoRenotify(k, k.run(k.request(owner = "owner-B")))
        assertTrue(k.events.any { it.startsWith("CHECKPOINT_CORRUPT") })
    }

    @Test
    fun `checkpoint が欠損していても 新しいオッズで同じタイミングを再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.runs.rewriteCheckpoints { null }
        assertNoRenotify(k, k.run(k.request(owner = "owner-B")))
    }

    @Test
    fun `checkpoint が古い状態（発火の記録が無い）でも 再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.runs.rewriteCheckpoints { RunCheckpoint.EMPTY.toJson() }
        assertNoRenotify(k, k.run(k.request(owner = "owner-B")))
    }

    @Test
    fun `V5 より前の版が送った通知（確定の記録が無い）は checkpoint が無事なら再通知しない`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.timings.rows.clear() // 旧版は確定の記録を残さない
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertEquals(1, r1Observations(k).size)
    }

    @Test
    fun `V5 より前の版が送った通知で checkpoint も失われたら そのレースは通知を止め 評価機会の喪失で FAILED`() {
        val k = Kit()
        interruptedAfterR1(k)
        k.timings.rows.clear()
        k.runs.rewriteCheckpoints { null }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals(3, k.notifier.signals.size, "R1 は再通知されない（R2・R3 は通常どおり）")
        assertEquals(1, r1Observations(k).size)
        assertEquals("EVALUATION_OPPORTUNITY_LOST", out.reason)
        assertEquals(BotRunState.FAILED, out.state)
        assertTrue(errorsOf(k).any { it.contains("CHECKPOINT_UNRELIABLE") })
    }

    @Test
    fun `初回起動の空 checkpoint は正常 止めない`() {
        val k = Kit()
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
    }

    @Test
    fun `前の run が開始前に中断して checkpoint が null でも 通知記録が無ければ止めない`() {
        val k = Kit()
        k.stop = true
        k.run()
        k.stop = false
        k.runs.rewriteCheckpoints { null }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.notifier.signals.size)
        assertTrue(k.events.none { it.startsWith("RACES_QUARANTINED") })
    }

    // ---- D-18: 確定の後・送信の前に落ちたら自動で再送しない ----

    @Test
    fun `確定の後 送信の前に中断したタイミングは 再起動しても自動で再送しない 通知内容と状態が残る`() {
        val k = Kit()
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        val slot =
            k.timings.rows.values
                .single()
        assertEquals(500, slot.timingCenti)
        assertTrue(k.notifier.signals.isEmpty(), "送っていない")
        assertTrue(k.signals.all().all { it.state == NotificationState.UNSENT }, "送信前に中断された可能性: Signal は UNSENT のまま")
        k.stop = false
        (k.judge as StubJudge).onEvaluate = {}
        k.runs.rewriteCheckpoints { null }
        val out = k.run(k.request(owner = "owner-B"))
        assertEquals("ALL_RACES_OFF", out.reason)
        assertTrue(
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .all { it.state == NotificationState.UNSENT },
            "R1 は自動で再送しない（取りこぼしを選ぶ）",
        )
        assertEquals(2, k.notifier.signals.size, "R2・R3 だけ")
    }

    // ---- 通知内容のスナップショット ----

    @Test
    fun `通知は確定した行から作る 保存した内容は Signal の行と一致し ハッシュが検証できる`() {
        val k = Kit()
        k.run()
        val slot =
            k.timings.rows.values
                .first { it.raceId == k.raceIds[0] }
        val snap = NoticeSnapshot.decode(slot.noticeSchema, slot.noticeJson, slot.noticeSha256)
        val rows =
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .associateBy { it.id!! }
        assertTrue(snap.items.isNotEmpty())
        for (item in snap.items) {
            val row = rows.getValue(item.signalId)
            assertEquals(row.odds.value, item.view.odds)
            assertEquals(row.prob.value, item.view.prob)
            assertEquals(row.ev, item.view.ev)
            assertEquals(row.umaban.value, item.view.umaban)
        }
        assertEquals(slot.noticeSha256, snap.sha256())
        assertEquals(slot.oddsObservedAt, rows.values.first().observedAt)
    }

    @Test
    fun `確定した通知内容が壊れていたら 送らない 別の値で補わない`() {
        val k = Kit()
        // 確定の直前に内容が書き換わる状況（改ざん・破損）を、読み戻しの結果だけを壊して再現する
        val corrupt =
            object : TimingNoticeStore by k.timings {
                override fun find(
                    tenantId: jp.jrabot.domain.tenant.TenantId,
                    raceId: jp.jrabot.domain.race.RaceId,
                    timingCenti: Int,
                ) = k.timings.find(tenantId, raceId, timingCenti)?.let { it.copy(noticeJson = it.noticeJson.replace("\"odds\":", "\"odds\":1")) }
            }
        val processor =
            RaceCycleProcessor(
                k.config,
                k.tenant,
                NotificationOrigin.LIVE,
                k.odds,
                StubJudge(),
                k.evaluations,
                k.signals,
                jp.jrabot.domain.botrun.testing
                    .InMemoryEntries(),
                k.featureSource,
                jp.jrabot.domain.botrun.testing
                    .InMemoryRaces(),
                k.dispatcher,
                corrupt,
                "00000000-0000-4000-8000-000000000001",
            )
        k.autoOdds = false
        k.clock.instant = k.at(9, 51)
        k.odds.add(Kit.snapshot(k.raceIds[0], k.at(9, 50, 30)))
        val step = processor.process(k.raceIds[0], k.at(9, 55), RaceCheckpoint(RaceStage.MONITORING), k.clock.now()) { true }
        assertTrue(step is RaceStep.SnapshotInvalid)
        assertTrue(k.notifier.signals.isEmpty())
    }

    @Test
    fun `通知対象が 0 件でも 評価済みのタイミングとして確定する`() {
        val k = Kit(judge = StubJudge { emptyList() })
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason)
        assertEquals(3, k.timings.rows.size)
        assertTrue(
            k.timings.rows.values
                .all { NoticeSnapshot.decode(it.noticeSchema, it.noticeJson, it.noticeSha256).items.isEmpty() },
        )
        assertTrue(k.notifier.signals.isEmpty())
    }

    @Test
    fun `複数のタイミングが同時に到来したら 1 回の評価で全部確定し 通知は 1 回`() {
        val cfg = Kit.defaultConfig().copy(snapshot = SnapshotPolicy(listOf(30.0, 5.0), TightenMode.OFF))
        val k = Kit(start = Cal.jst(Cal.DATE, 9, 52), config = cfg)
        k.run()
        val r1 =
            k.timings.rows.values
                .filter { it.raceId == k.raceIds[0] }
        assertEquals(setOf(500, 3000), r1.map { it.timingCenti }.toSet(), "遅い起動で 30 分前と 5 分前が同時に到来")
        assertEquals(1, r1.map { it.noticeSha256 }.toSet().size, "同じ内容で確定")
        assertEquals(
            1,
            k.signals
                .all()
                .filter { it.raceId == k.raceIds[0] }
                .map { it.observedAt }
                .toSet()
                .size,
            "評価は 1 回",
        )
    }

    // ---- B: 既存 Signal と計算結果の不一致 ----

    @Test
    fun `保存済みの Signal と計算結果が違うときは通知しない 一致するものだけ通知する`() {
        var amount = 3.0
        val k = Kit(judge = StubJudge { listOf(StubJudge.bet(2, amount = amount), StubJudge.bet(4, odds = 15.0, amount = 1.0)) })
        k.autoOdds = false
        k.raceIds.forEach { k.odds.add(Kit.snapshot(it, k.at(9, 52))) } // 同じ観測（自然キーが同じ）を再評価する状況
        k.clock.advance(Duration.between(k.clock.now(), k.at(9, 52)))
        (k.judge as StubJudge).onEvaluate = { if (it == k.raceIds[0]) k.stop = true }
        assertEquals("SIGTERM", k.run().reason)
        assertTrue(k.signals.all().all { it.state == NotificationState.UNSENT })
        assertEquals(mapOf(2 to 3.0, 4 to 1.0), k.signals.all().associate { it.umaban.value to it.stake })

        // 旧版が残した状態の再現: 確定の記録と checkpoint が無い。推奨額が変わった計算結果で、同じ観測の行が既にある
        k.stop = false
        (k.judge as StubJudge).onEvaluate = {}
        k.timings.rows.clear()
        k.runs.rewriteCheckpoints { null }
        amount = 7.0
        k.run(k.request(owner = "owner-B"))

        val r1 = k.signals.all().filter { it.raceId == k.raceIds[0] }
        assertEquals(3.0, r1.single { it.umaban.value == 2 }.stake, "DB の値は書き換えない")
        assertEquals(NotificationState.UNSENT, r1.single { it.umaban.value == 2 }.state, "不一致の行は claim も通知もしない")
        assertEquals(NotificationState.SENT, r1.single { it.umaban.value == 4 }.state, "一致する行は通知する")
        assertTrue(errorsOf(k).any { it.contains("SIGNAL_MISMATCH") }, "原因を記録: ${errorsOf(k)}")
        assertTrue(k.notifier.signals.none { it.body.contains("7.0") }, "計算し直した値（推奨額 7.0）を通知していない")
    }

    // ---- G1: 実オッズが無いワイド・三連複は推定オッズで買い目にしない ----

    @Test
    fun `組み合わせの実オッズが無いとき ワイド 三連複を推定オッズで通知しない 見送りとして理由を示す`() {
        val k = Kit(judge = null) // 実物の判定。組み合わせオッズは保存していない（INGEST_STORE_COMBO_ODDS=false の既定）
        k.run()
        assertTrue(k.signals.all().none { it.market != jp.jrabot.domain.odds.Market.WIN }, "ワイド・三連複の Signal を作らない")
        assertEquals(3, k.evaluations.rows.size, "判定は行われている（全レース・1 タイミング）")
        assertTrue(k.notifier.signals.all { !it.body.contains("（推定）") || it.body.contains("実オッズ未取得") }, "推定を実オッズとして示さない")
    }

    // ---- リース: 1 サイクルの中で通知が長引いても、レースの合間に heartbeat を更新する ----

    @Test
    fun `同時に発走する複数のレースの通知が長引いても リースを失わない`() {
        val races = (1..3).map { Cal.race(no = it, post = java.time.LocalTime.of(9, 55)) }
        val cfg = Kit.defaultConfig().copy(snapshot = SnapshotPolicy(listOf(15.0), TightenMode.OFF))
        val k = Kit(races = races, config = cfg)
        // 1 レースの評価・通知に 130 秒かかる（Discord の 429 の待機など）。リースは 300 秒、heartbeat は 60 秒間隔。
        // サイクルの先頭でしか更新しないと、3 レース目（開始から 390 秒後）の checkpoint の保存でリースが切れている
        (k.judge as StubJudge).onEvaluate = {
            k.clock.advance(Duration.ofSeconds(130))
            k.feedOdds() // 取込側は新しい観測を保存し続ける
        }
        val out = k.run()
        assertEquals("ALL_RACES_OFF", out.reason, "リース喪失で止まらない: ${out.reason}")
        assertEquals(3, k.notifier.signals.size)
        assertEquals(3, k.timings.rows.size)
    }
}
