package jp.jrabot.domain.notify

import jp.jrabot.domain.ml.json.CanonicalJson
import jp.jrabot.domain.ml.json.JsonArray
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.StrictJson
import jp.jrabot.domain.signal.SignalId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** 通知内容のスナップショット（V5）: 保存して読み戻しても Discord 本文が一致し、壊れた内容は使わない。合成データだけ。 */
class NoticeSnapshotTest {
    private val race = RaceLabel("東京", 11, "合成ステークス")

    private val win =
        SignalView(
            betType = "単勝",
            umaban = 2,
            odds = 8.0,
            prob = 0.1234567890123,
            ev = 0.1 + 0.2, // 浮動小数の往復が難しい値
            pMkt = 0.125,
            ratio = 0.98765,
            expectedReturn = 1.00000000000001,
            probSource = "market:fallback(ml_disabled)",
            coverage = CoverageView(0.5, null, hasHorseMaster = true, hasPastRuns = false, pastN = 0),
            betAmount = 3.0,
            reason = "過去走: 3 走・平均 4.5 着\n改行と \"引用\" と 絵文字🐴",
        )

    private fun strategy(
        type: String,
        tickets: List<TicketView>,
    ) = StrategyView(type, 2.0, 0.4, 12.3, 0.0456, 0.56, if (type == "ワイド") "実オッズ未取得のため見送り" else null, tickets, KellyView(0.2, 0.1, 3.0, false, null, 0.05))

    private val bundle =
        BundleView(
            strategies =
                listOf(
                    strategy("ワイド", listOf(TicketView("2-5", "3.2〜5.1", 0.21, 0.45, 1.0), TicketView("2-7", "12.3（推定）", 0.0001, -0.2, null))),
                    strategy("三連複", listOf(TicketView("2-5-7", "45.6", 1e-5, 0.3, 0.5))),
                ),
            best = BestView("ワイド", 0.45, 2.0),
            totalSuggestedBet = 4.0,
            totalExpectedProfit = 0.8,
        )

    private val items =
        listOf(
            NoticeItem(SignalId.of(11), win),
            NoticeItem(SignalId.of(12), win.copy(betType = "ワイド", umaban = 5, bundle = bundle, skipReason = null)),
            NoticeItem(
                SignalId.of(13),
                SignalView("単勝", 4, 15.0, 0.05, -0.25, betAmount = null, skipReason = "EV が閾値未満", payoutRate = 0.8, showAdjustedMkt = true),
            ),
        )

    private fun roundTrip(s: NoticeSnapshot): NoticeSnapshot {
        val json = s.canonicalJson()
        return NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, json, s.sha256())
    }

    @Test
    fun `保存して読み戻しても 表示モデルが完全に一致し Discord 本文も一致する ワイド 三連複の買い目を含む`() {
        val original = NoticeSnapshot(race, items)
        val back = roundTrip(original)
        assertEquals(original.items, back.items)
        assertEquals(original.race, back.race)
        val before = NoticeFormatter.formatGrouped(original.race, original.items.map { it.view }, MentionPolicy.NONE)
        val after = NoticeFormatter.formatGrouped(back.race, back.items.map { it.view }, MentionPolicy.NONE)
        assertEquals(before, after)
        assertTrue(after.contains("2-5-7") && after.contains("3.2〜5.1") && after.contains("12.3（推定）"), "ワイド・三連複の買い目と推定の区別が残る")
    }

    @Test
    fun `DB の JSON はキーの順序や空白が変わり得る 読んだ内容を正準化してハッシュを照合する`() {
        val original = NoticeSnapshot(race, items)
        val parsed = StrictJson.parse(original.canonicalJson()) as JsonObject
        // キーを逆順にし、空白を入れた JSON（MySQL の JSON 型から読み戻したときの変化を模す）
        val reversed =
            JsonObject(
                parsed.fields.entries
                    .reversed()
                    .associate { it.key to it.value },
            )
        val text = CanonicalJson.encode(reversed).replace(",\"", ", \"")
        val back = NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, text, original.sha256())
        assertEquals(original.items, back.items)
    }

    @Test
    fun `値が無いものはキーごと省く 0 などの架空の値で埋めない`() {
        val json = NoticeSnapshot(race, listOf(NoticeItem(SignalId.of(1), SignalView("単勝", 1, 2.0, 0.3, 0.1)))).canonicalJson()
        for (k in listOf("pMkt", "ratio", "expectedReturn", "betAmount", "reason", "skipReason", "coverage", "bundle")) {
            assertTrue(!json.contains("\"$k\""), "$k は省かれる")
        }
        assertEquals(
            null,
            roundTrip(NoticeSnapshot(race, listOf(NoticeItem(SignalId.of(1), SignalView("単勝", 1, 2.0, 0.3, 0.1)))))
                .items
                .single()
                .view.betAmount,
        )
    }

    @Test
    fun `内容が 1 文字でも違えば ハッシュ不一致で使わない 版が違う JSON でない もの使わない`() {
        val s = NoticeSnapshot(race, items)
        val json = s.canonicalJson()
        val tampered = json.replace("\"odds\":8.0", "\"odds\":9.0")
        assertNotEquals(json, tampered)
        assertFailsWith<NoticeSnapshotException> { NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, tampered, s.sha256()) }
        assertFailsWith<NoticeSnapshotException> { NoticeSnapshot.decode(2, json, s.sha256()) }
        assertFailsWith<NoticeSnapshotException> { NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, "{broken", s.sha256()) }
        assertFailsWith<NoticeSnapshotException> { NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, "[]", s.sha256()) }
        assertFailsWith<NoticeSnapshotException> { NoticeSnapshot.decode(NoticeSnapshot.SCHEMA, json, "0".repeat(64)) }
        // 形が違う（必要なキーが無い）のにハッシュだけ合わせても使わない
        val noItems = JsonObject.of("race" to (StrictJson.parse(json) as JsonObject)["race"]!!)
        assertFailsWith<NoticeSnapshotException> {
            NoticeSnapshot.decode(
                NoticeSnapshot.SCHEMA,
                CanonicalJson.encode(noItems),
                CanonicalJson.sha256Hex(noItems),
            )
        }
    }

    @Test
    fun `通知項目が空でも保存できる 評価済みのタイミングの記録`() {
        val empty = NoticeSnapshot(race, emptyList())
        val back = roundTrip(empty)
        assertTrue(back.items.isEmpty())
        assertEquals(JsonArray(), (StrictJson.parse(empty.canonicalJson()) as JsonObject)["items"])
    }
}
