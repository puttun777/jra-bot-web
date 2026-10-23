package jp.jrabot.domain.calendar

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 開催判定に曜日・祝日を使わない（D-024・RACE_CALENDAR_DESIGN §1）ことの構造検査。
 * 開催の有無・起動の可否を決める `jp.jrabot.domain.calendar`（と bot-run の遷移）に、曜日・祝日・システム時計・既定のタイムゾーンの参照が無い。
 */
class CalendarSourceGuardTest {
    private val roots = listOf(File("src/main/kotlin/jp/jrabot/domain/calendar"), File("src/main/kotlin/jp/jrabot/domain/bot"))
    private val forbidden =
        listOf(
            Regex("""\bDayOfWeek\b"""),
            Regex("""\bgetDayOfWeek\b|\.dayOfWeek\b"""),
            Regex("""(?i)\bholiday\b|祝日カレンダー|\bJapaneseHoliday"""),
            Regex("""\bZoneId\.systemDefault\b"""),
            Regex("""\bTimeZone\.getDefault\b"""),
            Regex("""\bLocale\.getDefault\b"""),
        )

    private fun codeLines(file: File) =
        file.readLines().withIndex().filter { (_, l) ->
            val t = l.trim()
            !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
        }

    @Test
    fun `ソースの場所を見つけられる`() {
        assertTrue(roots.all { it.isDirectory }, "作業ディレクトリが :domain ではない")
        assertTrue(roots.sumOf { r -> r.walkTopDown().count { it.extension == "kt" } } >= 8)
    }

    @Test
    fun `曜日・祝日・既定のタイムゾーンを参照しない`() {
        val violations =
            roots
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" }.toList() }
                .flatMap { f -> codeLines(f).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }.map { (i, l) -> "${f.path}:${i + 1}: ${l.trim()}" } }
        assertEquals(emptyList(), violations)
    }
}
