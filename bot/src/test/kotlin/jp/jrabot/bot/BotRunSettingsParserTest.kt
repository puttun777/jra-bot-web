package jp.jrabot.bot

import jp.jrabot.domain.botrun.TightenMode
import jp.jrabot.domain.ml.pipeline.ProbabilityModelType
import jp.jrabot.domain.ops.AppEnv
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BotRunSettingsParserTest {
    private val ok = baseEnv("/dev/null")

    private fun parse(
        env: Map<String, String> = ok,
        appEnv: AppEnv = AppEnv.LOCAL,
    ) = BotRunSettingsParser.fromEnv(env, appEnv)

    @Test
    fun `すべて指定すれば読める 値はテスト用で運用の既定ではない`() {
        val s = parse()
        val c = s.run
        assertEquals(Duration.ofSeconds(300), c.lease.duration)
        assertEquals(Duration.ofSeconds(60), c.lease.heartbeatInterval)
        assertEquals(Duration.ofSeconds(180), c.maxOddsAge)
        assertEquals(Duration.ofMinutes(840), c.maxRuntime)
        assertEquals(listOf(30.0, 5.0), c.snapshot.minutesBeforePost)
        assertEquals(Duration.ofSeconds(900), c.staleClaimAfter)
        assertEquals(Duration.ofSeconds(15), c.modeCheckInterval)
        assertNull(c.hitRateSchedule)
        assertEquals(TightenMode.OFF, c.snapshot.tighten)
        assertEquals(3, c.snapshotHistoryLimit)
        assertEquals(3, c.maxConsecutiveUnknown)
        assertNull(c.mlModelType)
        assertNull(s.modelStoreUri)
        assertEquals(Duration.ofMinutes(120), c.calendar.watch.watchStart)
        assertEquals(0.08, c.decision.evThreshold) // 旧 evaluate_odds の関数既定値（運用値ではない）
    }

    @Test
    fun `運用値に既定は無い どの変数が欠けても拒否し メッセージは変数名だけで値を含めない`() {
        for (name in botRunEnv().keys) {
            val e = assertFailsWith<IllegalStateException>(name) { parse(ok - name) }
            assertTrue(e.message!!.contains(name), "${e.message}")
        }
    }

    @Test
    fun `範囲外と形式の誤りは拒否される 値をメッセージに出さない`() {
        val bad =
            mapOf(
                "BOT_RUN_LEASE_SECONDS" to listOf("9", "3601", "abc", "-1", "１２０"),
                "BOT_RUN_HEARTBEAT_SECONDS" to listOf("0", "300", "301"), // リース 300 以上は不可
                "BOT_RUN_MAX_RUNTIME_MINUTES" to listOf("0", "841"), // 契約の上限は 14 時間
                "BOT_RUN_MAX_ODDS_AGE_SECONDS" to listOf("0", "86401"),
                "BOT_RUN_SNAPSHOT_HISTORY_LIMIT" to listOf("1", "101"),
                "BOT_RUN_MAX_CYCLE_FAILURES" to listOf("0", "21"),
                "BOT_RUN_TIGHTEN_NEAR" to listOf("yes", "TRUE", "1"),
                "BOT_RUN_SNAPSHOT_MINUTES" to listOf("0", "-5", "5,5", "5,abc", "1441", "", "5.005", "5.001", "0.001", "30,5.001"),
                "BOT_RUN_ML_MODEL_TYPE" to listOf("v2", "auto", "ON"),
                "BOT_RUN_DECISION_PROFILE" to listOf("production", ""),
            )
        for ((name, values) in bad) {
            for (v in values) {
                val e = assertFailsWith<IllegalStateException>("$name=$v") { parse(ok + (name to v)) }
                if (v.length > 2) assertFalse(e.message!!.contains(v), "値を含めない: ${e.message}")
            }
        }
    }

    @Test
    fun `評価のタイミングは複数指定できる`() {
        val c = parse(ok + ("BOT_RUN_SNAPSHOT_MINUTES" to "10, 5,30")).run
        assertEquals(listOf(30.0, 10.0, 5.0), c.snapshot.descending)
        assertEquals(TightenMode.CORRECTED, parse(ok + ("BOT_RUN_TIGHTEN_NEAR" to "true")).run.snapshot.tighten)
        assertEquals(TightenMode.LEGACY_QUIRK, parse(ok + ("BOT_RUN_TIGHTEN_NEAR" to "legacy")).run.snapshot.tighten)
    }

    @Test
    fun `ML を有効にするときだけ保管先と検証用の公開鍵が必須 本番は gs だけ`() {
        val ml = ok + ("BOT_RUN_ML_MODEL_TYPE" to "ensemble")
        assertFailsWith<IllegalStateException> { parse(ml - "MODEL_STORE_URI") }
        assertFailsWith<IllegalStateException> { parse(ml) } // 公開鍵なし
        val full = ml + ("MODEL_VERIFY_PUBKEY" to "AAAA")
        val s = parse(full)
        assertEquals(ProbabilityModelType.ENSEMBLE, s.run.mlModelType)
        assertEquals("file:///tmp/jrabot-test-store", s.modelStoreUri)
        assertNotNull(s.modelVerifyPubKey)
        // 本番では file:/// を許さない
        assertFailsWith<IllegalStateException> { parse(full, AppEnv.PROD) }
        assertEquals("gs://bucket", parse(full + ("MODEL_STORE_URI" to "gs://bucket"), AppEnv.PROD).modelStoreUri)
        for ((raw, type) in mapOf(
            "legacy" to ProbabilityModelType.LEGACY,
            "calibration" to ProbabilityModelType.CALIBRATION,
            "alpha" to ProbabilityModelType.ALPHA,
        )) {
            assertEquals(type, parse(full + ("BOT_RUN_ML_MODEL_TYPE" to raw)).run.mlModelType)
        }
    }

    @Test
    fun `bot-run 以外のタスクはこの設定を要求しない`() {
        val minimal = baseEnv("/dev/null", *botRunEnv().keys.map { it to null }.toTypedArray())
        assertNull(BotSettings.from(minimal, BotTask.CALENDAR_CHECK).botRun)
        assertNull(BotSettings.from(minimal, BotTask.RECONCILE).botRun)
        assertNotNull(BotSettings.from(baseEnv("/dev/null"), BotTask.BOT_RUN).botRun)
    }

    @Test
    fun `サンプル設定ファイルは暫定値であることが明記され 読み込めて テスト用の設定と一致する`() {
        val text = java.io.File("config/bot-run.sample.env").readText()
        assertTrue(text.contains("本番の運用値ではない") && text.contains("暫定"))
        val env =
            text.lines().filter { it.isNotBlank() && !it.startsWith("#") }.associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(botRunEnv(), env)
        parse(ok + env)
    }

    @Test
    fun `hit_rate_min_ev_schedule は明示したときだけ有効 未設定は固定閾値のまま`() {
        assertNull(parse().run.hitRateSchedule)
        assertEquals(jp.jrabot.domain.decision.PriorityMode.EDGE, parse().run.decision.priorityMode)
        val on =
            ok +
                mapOf(
                    "BOT_RUN_PRIORITY_MODE" to "hit_rate",
                    "BOT_RUN_HIT_RATE_MIN_EV" to "0.10",
                    "BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE" to "far=0.12, mid=0.10, near=0.08",
                )
        val c = parse(on).run
        assertEquals(
            jp.jrabot.domain.botrun
                .HitRateSchedule(0.12, 0.10, 0.08),
            c.hitRateSchedule,
        )
        assertEquals(0.10, c.decision.hitRateMinEv)
        // edge のままでは使えない・3 つ揃っていない・数値でない
        assertFailsWith<IllegalStateException> { parse(ok + ("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE" to "far=0.1,mid=0.1,near=0.1")) }
        assertFailsWith<IllegalStateException> { parse(on + ("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE" to "far=0.1,mid=0.1")) }
        assertFailsWith<IllegalStateException> { parse(on + ("BOT_RUN_HIT_RATE_MIN_EV_SCHEDULE" to "far=a,mid=0.1,near=0.1")) }
        assertFailsWith<IllegalStateException> { parse(ok + ("BOT_RUN_PRIORITY_MODE" to "auto")) }
    }

    @Test
    fun `5 分前の強化は false true legacy だけ`() {
        assertFailsWith<IllegalStateException> { parse(ok + ("BOT_RUN_TIGHTEN_NEAR" to "yes")) }
    }
}
