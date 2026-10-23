package jp.jrabot.domain.contract

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 現在時刻を直接取得する処理を `:domain` に散在させない（D-006・D-041）。許されるのは `time/Clock.kt` の `SystemClock` だけ。
 * Gradle のテストの作業ディレクトリは `:domain` のプロジェクトディレクトリ。
 */
class ClockUsageGuardTest {
    private val mainRoot = File("src/main/kotlin")
    private val forbidden =
        listOf(
            Regex("""\bInstant\.now\("""),
            Regex("""\bLocalDate\.now\("""),
            Regex("""\bLocalDateTime\.now\("""),
            Regex("""\bLocalTime\.now\("""),
            Regex("""\bZonedDateTime\.now\("""),
            Regex("""\bOffsetDateTime\.now\("""),
            Regex("""\bYear\.now\("""),
            Regex("""\bSystem\.currentTimeMillis\("""),
            Regex("""\bSystem\.nanoTime\("""),
            Regex("""\bjava\.util\.Date\("""),
            Regex("""\bCalendar\.getInstance\("""),
        )

    private fun codeLines(file: File) =
        file.readLines().withIndex().filter { (_, l) ->
            val t = l.trim()
            !t.startsWith("*") && !t.startsWith("//") && !t.startsWith("/*")
        }

    @Test
    fun `ソースの場所を見つけられる`() {
        assertTrue(mainRoot.isDirectory, "作業ディレクトリが :domain ではない: ${File(".").absoluteFile}")
        assertTrue(mainRoot.walkTopDown().count { it.extension == "kt" } > 10)
    }

    @Test
    fun `SystemClock 以外では時刻を直接取得しない`() {
        val violations =
            mainRoot
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "Clock.kt" }
                .flatMap { f -> codeLines(f).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }.map { (i, l) -> "${f.path}:${i + 1}: ${l.trim()}" } }
                .toList()
        assertEquals(emptyList(), violations)
    }

    @Test
    fun `Clock の中でも直接取得は SystemClock の now だけ`() {
        val hits = codeLines(File("src/main/kotlin/jp/jrabot/domain/time/Clock.kt")).filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }
        assertEquals(1, hits.size, "$hits")
        assertTrue(hits.single().value.contains("Instant.now()"))
    }
}
