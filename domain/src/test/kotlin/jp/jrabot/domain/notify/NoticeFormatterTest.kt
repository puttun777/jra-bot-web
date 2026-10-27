package jp.jrabot.domain.notify

import jp.jrabot.domain.notify.NoticeFixtures.bundle
import jp.jrabot.domain.notify.NoticeFixtures.race
import jp.jrabot.domain.notify.NoticeFixtures.strategy
import jp.jrabot.domain.notify.NoticeFixtures.ticket
import jp.jrabot.domain.notify.NoticeFixtures.win
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticeFormatterTest {
    @Test
    fun `単勝の単独通知は主要項目をすべて出す`() {
        val text = NoticeFormatter.formatSingle(race, win())
        assertTrue(text.startsWith("【JRA EV検知】\n**合成 11R** 合成記念\n━━━━━━━━━━━━━━━━\n"))
        for (expected in listOf(
            "🎯 **賭式：単勝**",
            "🐴 **馬番：3**",
            "💰 オッズ：**4.5**",
            "📊 推定勝率：30.00%",
            "📈 期待値（EV）：**35.0%**",
            "📉 市場確率：22.22%",
            "📊 倍率（p/mkt）：1.35",
            "💰 期待回収：1.35x",
            "🔍 確率ソース：ml:alpha",
            "📋 特徴量カバレッジ：80.00% (master✓) (past_runs✓, n=5)",
            "⚙️ 動的β：0.456",
            "💵 推奨投資額：**2.0点**",
            "💡 理由：馬名=合成一号",
        )) {
            assertTrue(text.contains(expected), "含まれる: $expected\n$text")
        }
        assertTrue(text.endsWith("━━━━━━━━━━━━━━━━"))
    }

    @Test
    fun `欠損値は 0 で埋めず行を出さない`() {
        val sparse =
            win().copy(
                pMkt = null,
                ratio = null,
                expectedReturn = null,
                probSource = null,
                coverage = null,
                betAmount = null,
                bundle = null,
                reason = null,
                odds = 0.0,
                prob = 0.0,
            )
        val text = NoticeFormatter.formatSingle(race, sparse)
        assertFalse(
            text.contains(
                "市場確率",
            ) || text.contains("倍率") || text.contains("期待回収") || text.contains("確率ソース") || text.contains("カバレッジ") || text.contains("推奨投資額") ||
                text.contains("投資戦略"),
        )
        assertTrue(text.contains("💡 理由：\n"))
    }

    @Test
    fun `提供された市場確率と倍率を再計算せず使う`() {
        val v = win().copy(pMkt = 0.5, ratio = 9.99, expectedReturn = 7.77)
        val text = NoticeFormatter.formatSingle(race, v)
        assertTrue(text.contains("📉 市場確率：50.00%") && text.contains("倍率（p/mkt）：9.99") && text.contains("期待回収：7.77x"))
    }

    @Test
    fun `メンションは本人の ID を先頭に付けるだけ`() {
        val m = MentionPolicy.forUser("123456789012345678")
        assertTrue(NoticeFormatter.formatSingle(race, win(), m).startsWith("<@123456789012345678>\n【JRA EV検知】"))
        assertTrue(NoticeFormatter.formatGrouped(race, listOf(win()), m).startsWith("<@123456789012345678>\n【JRA EV検知】"))
        assertTrue(NoticeFormatter.formatGrouped(race, listOf(win())).startsWith("【JRA EV検知】"))
    }

    @Test
    fun `馬名やレース名の at マークでメンションが発生しない`() {
        val evil = RaceLabel("@everyone", 1, "<@&1234> @here")
        val text = NoticeFormatter.formatGrouped(evil, listOf(win().copy(reason = "@everyone")))
        assertFalse(Regex("@(?!​)").containsMatchIn(text), "すべての @ の直後にゼロ幅スペース")
        assertFalse(text.contains("@everyone") || text.contains("@here"))
    }

    @Test
    fun `単勝 ワイド 三連複を統合し 推奨額は最大 買い目は重複除外で EV 降順`() {
        val wide =
            strategy(
                "ワイド",
                3.0,
                1.1,
                tickets = listOf(ticket("01-02", 0.10), ticket("[BOX]01-02", 0.15), ticket("01-03", 0.30, stake = null), ticket("02-03", 0.20, "12.3（推定）")),
            )
        val tri = strategy("三連複", 1.5, 0.9, tickets = listOf(ticket("01-02-03", 0.5, "55.5", 0.01)))
        val s1 = win(umaban = 1, bundle = bundle(strategy("単勝", 2.0, 0.7), wide, tri, best = BestView("ワイド", 0.3, 3.0)), betType = "ワイド")
        val s2 =
            win(umaban = 2, bundle = bundle(strategy("単勝", 2.5, 0.6), wide.copy(suggestedBet = 4.0), tri, best = BestView("三連複", 0.5, 1.5)), betType = "三連複")
        val text = NoticeFormatter.formatGrouped(race, listOf(s1, s2))
        assertTrue(text.contains("**検知シグナル: 2件**"))
        assertTrue(text.contains("**単勝:**\n  推奨投資額：**2.5点**"), "推奨額は最大（合算しない）")
        assertTrue(text.contains("**ワイド:**\n  推奨投資額：**4.0点**"))
        // 同じ組合せ（[BOX] の有無を無視）は EV が高い方が残り、EV 降順
        val wideBlock = text.substringAfter("**ワイド:**").substringBefore("**三連複:**")
        assertEquals(1, Regex("01-02 ").findAll(wideBlock).count(), wideBlock)
        assertTrue(wideBlock.contains("[BOX]01-02 odds=3.2〜5.1 p=3.45% EV=15.0% stake=1.0点"))
        assertTrue(wideBlock.indexOf("01-03") < wideBlock.indexOf("02-03") && wideBlock.indexOf("02-03") < wideBlock.indexOf("[BOX]01-02"))
        assertTrue(wideBlock.contains("02-03 odds=12.3（推定）"), "推定オッズは推定と分かる")
        assertTrue(text.contains("⭐ **最推奨：三連複** （EV=50.0%, 投資額=1.5点）"))
        assertTrue(text.contains("📊 **合計投資額：8.0点**"), text)
        assertTrue(text.contains("📈 **合計期待利益：+2.7点**"), text)
    }

    @Test
    fun `買い目は上位 6 点まで`() {
        val tickets = (1..9).map { ticket("01-%02d".format(it + 1), it / 100.0) }
        val text = NoticeFormatter.formatGrouped(race, listOf(win(bundle = bundle(strategy("ワイド", 3.0, 1.0, tickets)), betType = "ワイド")))
        assertEquals(6, Regex("    - 01-").findAll(text).count())
        assertTrue(text.contains("01-10 odds") && !text.contains("01-02 odds"), "EV の低い方が落ちる")
    }

    @Test
    fun `見送りと購入候補を区別し 見送りは戦略へ統合しない`() {
        val skip = win(umaban = 7, ev = 0.02, bundle = bundle(strategy("単勝", 9.0, 9.0))).copy(skipReason = "Kelly推奨額(0.30点) < min_bet(1.0点)")
        val text = NoticeFormatter.formatGrouped(race, listOf(skip, win(umaban = 3)))
        assertTrue(text.contains("⚠️ **馬番7** （単勝 EV=2.0%） - 見送り\n  Kelly推奨額(0.30点) < min_bet(1.0点)\n"))
        assertTrue(text.contains("🐴 **馬番3** （単勝 EV=35.0%）"))
        assertTrue(text.contains("推奨投資額：**2.0点**\n  期待利益"), "見送りの戦略値（9.0）は統合されない")
        val onlySkip = NoticeFormatter.formatGrouped(race, listOf(skip))
        assertTrue(onlySkip.contains("💵 **投資戦略（統合）**\n\n📊 **合計投資額：0.0点**\n📈 **合計期待利益：+0.0点**"), onlySkip)
    }

    @Test
    fun `Kelly の注記 見送り クリップ 縮小確率`() {
        val k1 = strategy("ワイド", 1.0, 0.5, kelly = KellyView(0.2, 0.1, 3.0, true, null, 0.30), prob = 0.25)
        val t1 = NoticeFormatter.formatGrouped(race, listOf(win(bundle = bundle(k1))))
        assertTrue(t1.contains("📊 Kelly: raw=0.2000 adjusted=0.1000 (推奨=3.00点 → max_betでクリップ)"), t1)
        assertTrue(t1.contains("ℹ️ 期待利益は投資額計算に使った確率(p=30.00%)で計算"))
        val k2 = strategy("ワイド", 1.0, 0.5, kelly = KellyView(0.2, 0.1, 3.0, false, "実オッズ未取得のため見送り", 0.25), prob = 0.25)
        val t2 = NoticeFormatter.formatGrouped(race, listOf(win(bundle = bundle(k2))))
        assertTrue(t2.contains("  ⚠️ 見送り: 実オッズ未取得のため見送り"))
        assertFalse(t2.contains("ℹ️"), "確率が同じなら出さない（CORRECTED）")
        val legacy = NoticeFormatter.formatGrouped(race, listOf(win(bundle = bundle(k2))), mode = FormatMode.LEGACY_COMPAT)
        assertTrue(legacy.contains("ℹ️ 期待利益は投資額計算に使った確率(p=25.00%)で計算"), "旧実装の癖（常に出る）")
    }

    @Test
    fun `Unicode と絵文字の馬名はそのまま`() {
        val text = NoticeFormatter.formatGrouped(RaceLabel("東京🐴", 9, "ＡＢＣ記念🎯"), listOf(win()))
        assertTrue(text.contains("**東京🐴 9R** ＡＢＣ記念🎯"))
    }

    @Test
    fun `空のシグナルは拒否`() {
        assertFailsWith<IllegalArgumentException> { NoticeFormatter.formatGrouped(race, emptyList()) }
    }

    @Test
    fun `件名は 200 文字以内`() {
        assertTrue(NoticeFormatter.subject(RaceLabel("x".repeat(500), 1, "")).length <= 200)
    }

    @Test
    fun `実オッズを推定として 推定を実オッズとして表示しない`() {
        val real = ticket("01-02", 0.1, "3.2〜5.1")
        val est = ticket("01-03", 0.2, "12.3（推定）")
        val text = NoticeFormatter.formatGrouped(race, listOf(win(bundle = bundle(strategy("ワイド", 1.0, 1.0, listOf(real, est))), betType = "ワイド")))
        assertTrue(text.contains("01-02 odds=3.2〜5.1 ") && !text.contains("01-02 odds=3.2〜5.1（推定）"))
        assertTrue(text.contains("01-03 odds=12.3（推定） "))
    }
}
