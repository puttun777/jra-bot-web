package jp.jrabot.domain.calendar

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SourceVersionTest {
    private fun v(text: String) = checkNotNull(SourceVersion.parseOrNull(text)) { text }

    @Test
    fun `作成日と区分に分解して元の形に戻せる`() {
        val sv = v("202610232")
        assertEquals(SourceVersion(LocalDate.of(2026, 10, 23), RaceDataKubun.CARD), sv)
        assertEquals("202610232", sv.encode())
    }

    @Test
    fun `解釈できない値は補正せず null`() {
        for (bad in listOf(
            "",
            "20261023",
            "2026102322",
            "202610A32",
            "202613012",
            "202610320",
            "20261023A",
            "２０２６１０２３２",
            "190001012".replace("1900", "1899"),
        )) {
            assertNull(SourceVersion.parseOrNull(bad), bad)
        }
    }

    @Test
    fun `作成日が新しい方が新しい（区分は見ない）`() {
        assertEquals(VersionOrder.NEWER, v("202610241").compareTo(v("202610237")))
        assertEquals(VersionOrder.OLDER, v("202610237").compareTo(v("202610241")))
    }

    @Test
    fun `辞書順では逆になる比較でも日付と区分の規則で決める`() {
        // 文字列比較では "202610239" > "202610241" だが、作成日は 10-24 の方が新しい
        assertEquals(VersionOrder.NEWER, v("202610241").compareTo(v("202610239")))
        assertEquals(VersionOrder.OLDER, v("202610239").compareTo(v("202610241")))
        // 文字列比較では "202610230" < "202610231" だが、削除（0）は強い値で通常の区分より新しい
        assertEquals(VersionOrder.NEWER, v("202610230").compareTo(v("202610231")))
    }

    @Test
    fun `同じ作成日では通常の区分は昇順が新しい`() {
        assertEquals(VersionOrder.NEWER, v("202610232").compareTo(v("202610231")))
        assertEquals(VersionOrder.OLDER, v("202610231").compareTo(v("202610232")))
        assertEquals(VersionOrder.NEWER, v("202610237").compareTo(v("202610233")))
    }

    @Test
    fun `同じ作成日では中止（9）と削除（0）は通常の区分より新しい`() {
        for (strong in listOf("202610239", "202610230")) {
            assertEquals(VersionOrder.NEWER, v(strong).compareTo(v("202610237")), strong)
            assertEquals(VersionOrder.OLDER, v("202610237").compareTo(v(strong)), strong)
        }
    }

    @Test
    fun `同じ作成日の 9 と 0 は順序を決めない`() {
        assertEquals(VersionOrder.UNDECIDABLE, v("202610239").compareTo(v("202610230")))
        assertEquals(VersionOrder.UNDECIDABLE, v("202610230").compareTo(v("202610239")))
    }

    @Test
    fun `同じ版は SAME`() {
        assertEquals(VersionOrder.SAME, v("202610232").compareTo(v("202610232")))
        assertEquals(VersionOrder.SAME, v("202610239").compareTo(v("202610239")))
    }

    @Test
    fun `作成日が新しければ 9 や 0 の後でも新しい（復活の可否は状態遷移が決める）`() {
        assertEquals(VersionOrder.NEWER, v("202610242").compareTo(v("202610239")))
        assertEquals(VersionOrder.NEWER, v("202610242").compareTo(v("202610230")))
    }

    @Test
    fun `成績系の区分は版の比較には使えるが状態の反映は対象外`() {
        assertEquals(false, RaceDataKubun.RESULT_7.handledByCalendar)
        assertEquals(true, RaceDataKubun.CARD.handledByCalendar)
        assertNull(RaceDataKubun.fromCodeOrNull('A'))
    }
}
