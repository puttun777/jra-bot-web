package jp.jrabot.legacyimport

import jp.jrabot.domain.ml.json.JsonFloat
import jp.jrabot.domain.ml.json.JsonInt
import jp.jrabot.domain.ml.json.JsonNull
import jp.jrabot.domain.ml.json.JsonObject
import jp.jrabot.domain.ml.json.JsonString
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** 値の変換（D-274）。 */
class ConvertTest {
    private val cv = Convert(ZoneId.of("Asia/Tokyo"))

    private fun code(block: () -> Unit): String = assertFailsWith<Reject> { block() }.code

    @Test
    fun `時刻 — T 区切りの naive は JST、空白区切りは UTC、時差付きはその時差で UTC にする`() {
        assertEquals("2026-10-24 00:55:00.000000", cv.time("ts", JsonString("2026-10-24T09:55:00")))
        assertEquals("2026-10-23 15:00:00.123456", cv.time("ts", JsonString("2026-10-24T00:00:00.123456")))
        assertEquals("2026-10-24 01:00:00.500000", cv.time("ts", JsonString("2026-10-24T10:00:00.5")))
        assertEquals("2026-10-24 01:00:00.000000", cv.time("created_at", JsonString("2026-10-24 01:00:00")))
        assertEquals("2026-10-24 01:00:00.000000", cv.time("ts", JsonString("2026-10-24T10:00:00+09:00")))
        assertEquals("2026-10-24 10:00:00.000000", cv.time("ts", JsonString("2026-10-24T10:00:00Z")))
        assertEquals("2026-10-24 10:00:00.000000", Convert(ZoneId.of("UTC")).time("ts", JsonString("2026-10-24T10:00:00")))
        assertNull(cv.time("ts", JsonNull))
        assertEquals("time_format", code { cv.time("ts", JsonString("24/10/2026 09:57")) })
        assertEquals("time_format", code { cv.time("ts", JsonString("2026-02-30T10:00:00")) })
        assertEquals("time_format", code { cv.time("ts", JsonString("2026-10-24T10:00:00.1234567")) })
        assertEquals("time_format", code { cv.time("ts", JsonString("2026-10-24T25:00:00")) })
        assertEquals("time_format", code { cv.time("ts", JsonInt(1)) })
        assertEquals("null", code { cv.requiredTime("ts", JsonNull) })
    }

    @Test
    fun `日付・発走時刻 — 2 つの形と整数を受け付け、暦にない日付は隔離、発走時刻の 00_00 と範囲外は不明`() {
        assertEquals("2026-10-24", cv.date("date", JsonString("20261024")))
        assertEquals("2026-10-24", cv.date("date", JsonInt(20261024)))
        assertEquals("2026-10-24", cv.date("date", JsonString("2026-10-24")))
        assertEquals("date_format", code { cv.date("date", JsonString("20260230")) })
        assertEquals("date_format", code { cv.date("date", JsonString("2026/10/24")) })
        val nulled = mutableListOf<String>()
        assertNull(cv.dateOrNull("birth_date", JsonString("x")) { nulled += it })
        assertEquals(listOf("birth_date"), nulled)
        assertEquals("10:05:00", cv.postTime(JsonString("10:05")))
        assertEquals("23:59:00", cv.postTime(JsonString("23:59")))
        for (bad in listOf("00:00", "24:10", "12:60", "1005", "")) assertNull(cv.postTime(JsonString(bad)), bad)
        assertNull(cv.postTime(JsonNull))
    }

    @Test
    fun `文字列・整数・実数・JSON・印付きの値`() {
        assertEquals("05", cv.text("track_cd", JsonString("05"), 2, ascii = true))
        assertEquals("5", cv.text("track_cd", JsonInt(5), 2, ascii = true))
        assertEquals("too_long", code { cv.text("x", JsonString("abc"), 2) })
        assertEquals("not_ascii", code { cv.text("x", JsonString("東"), 2, ascii = true) })
        assertEquals("東京", cv.text("x", JsonString("東京"), 2), "文字数は符号位置で数える")
        assertEquals(3L, cv.long("n", JsonFloat(3.0), 0L..9L))
        assertEquals("type", code { cv.long("n", JsonFloat(3.5), 0L..9L) })
        assertEquals("out_of_range", code { cv.long("umaban", JsonInt(0), 1L..99L) })
        assertEquals(1.5, cv.double("odds", JsonString("1.5")))
        assertEquals("non_finite", code { cv.double("ev", JsonObject.of("\$real" to JsonString("inf"))) })
        assertEquals("blob", code { cv.double("ev", JsonObject.of("\$blob" to JsonString("AA=="))) })
        assertEquals("invalid_utf8", code { cv.text("name", JsonObject.of("\$bytes" to JsonString("Zv9n")), 10) })
        assertEquals("""{"a":1,"b":[1.5]}""", cv.json("j", JsonString("""{ "b": [1.5], "a": 1 }""")))
        assertEquals("json", code { cv.json("j", JsonString("{broken")) })
        assertEquals("json", code { cv.json("j", JsonString("[NaN]")) })
        assertEquals("enum", code { cv.market("market", JsonString("place")) })
        assertEquals(0L, cv.flag("clipped", JsonNull, 0L))
        assertEquals("out_of_range", code { cv.flag("sent", JsonInt(2), 0L) })
    }

    @Test
    fun `夏時間で無い・重なる時刻、MySQL の範囲外の年、10 進でない数字の文字列は隔離`() {
        val ny = Convert(ZoneId.of("America/New_York"))
        assertEquals("time_format", code { ny.time("ts", JsonString("2024-03-10T02:30:00")) }, "切替で無い時刻")
        assertEquals("time_format", code { ny.time("ts", JsonString("2024-11-03T01:30:00")) }, "重なる時刻")
        assertEquals("2024-03-10 07:30:00.000000", ny.time("ts", JsonString("2024-03-10T03:30:00")))
        assertEquals("time_format", code { cv.time("ts", JsonString("9999-12-31T23:00:00-05:00")) })
        assertEquals("time_format", code { cv.time("ts", JsonString("1000-01-01T05:00:00")) }, "UTC で 999 年")
        assertEquals("date_format", code { cv.date("date", JsonString("09990101")) })
        for (bad in listOf("1.5f", "0x1p3", " 1.5", "1d", "NaN", "Infinity")) assertEquals("type", code { cv.double("x", JsonString(bad)) }, bad)
        assertEquals(-1.5e3, cv.double("x", JsonString("-1.5e3")))
        for (bad in listOf("+1", " 1", "1.0", "0x10")) assertEquals("type", code { cv.long("x", JsonString(bad), 0L..9L) }, bad)
        assertEquals(7L, cv.long("x", JsonString("7"), 0L..9L))
    }
}
