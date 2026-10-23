package jp.jrabot.cloudgcp.ops

import jp.jrabot.domain.ops.OpsMode
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class OpsStateParserTest {
    private fun parse(json: String) = OpsStateParser.parse(json.toByteArray())

    private fun valid(json: String): OpsState = assertIs<ParseResult.Valid>(parse(json)).state

    private fun invalid(json: String): ParseResult.Invalid = assertIs<ParseResult.Invalid>(parse(json))

    @Test
    fun `正常系1-4 4 つのモードを解析できる`() {
        for (m in listOf("NORMAL", "STARTING", "STOPPING", "STOPPED")) {
            assertEquals(OpsMode.fromSourceValue(m), valid(opsStateJson(mode = m)).mode, m)
        }
    }

    @Test
    fun `desired とオペレーションの項目を読む`() {
        val s = valid(opsStateJson(seq = 42, mode = "STOPPING", desiredMode = "STOPPED"))
        assertEquals(42, s.seq)
        assertEquals(OpsMode.STOPPED, s.desiredMode)
        assertEquals(Instant.parse("2026-11-01T10:00:00Z"), s.desiredSetAt)
        assertEquals("stop", s.operationType)
        assertEquals("SUCCEEDED", s.operationStatus)
    }

    @Test
    fun `正常系7 追加フィールドや未知の operation status があっても解析できる（前方互換）`() {
        val json =
            opsStateJson(
                extra = """, "future_field": {"a": [1, 2, 3]}, "lease": {"holder": "x"}, "beacon": {"seq": 1}""",
            ).replace("\"SUCCEEDED\"", "\"SOME_NEW_STATUS\"").replace("\"set_by\"", "\"new_key\": null, \"set_by\"")
        val s = valid(json)
        assertEquals(OpsMode.NORMAL, s.mode)
        assertEquals("SOME_NEW_STATUS", s.operationStatus)
    }

    @Test
    fun `operation と set_at は欠落・null でもよい`() {
        val s =
            valid(
                """{"schema_version":1,"seq":0,"mode":"STOPPED","desired":{"mode":"STOPPED","set_at":null},"operation":null}""",
            )
        assertEquals(0, s.seq)
        assertNull(s.desiredSetAt)
        assertNull(s.operationType)
        valid("""{"schema_version":1,"seq":3,"mode":"NORMAL","desired":{"mode":"NORMAL"}}""")
    }

    @Test
    fun `異常系10 schema_version が 1 以外は不一致`() {
        for (v in listOf("0", "2", "99", "-1")) {
            val r = invalid(opsStateJson().replace("\"schema_version\": 1", "\"schema_version\": $v"))
            assertEquals(OpsFailureReason.SCHEMA_VERSION_MISMATCH, r.reason, v)
        }
        // 将来の版で形が違っても「版の不一致」になる（他の項目の検査より先）
        assertEquals(OpsFailureReason.SCHEMA_VERSION_MISMATCH, invalid("""{"schema_version":2,"state":"x"}""").reason)
    }

    @Test
    fun `schema_version の型が違う`() {
        for (v in listOf("\"1\"", "1.0", "true", "null", "[1]")) {
            val r = invalid(opsStateJson().replace("\"schema_version\": 1", "\"schema_version\": $v"))
            assertEquals(
                if (v == "null") OpsFailureReason.MISSING_FIELD else OpsFailureReason.INVALID_FIELD_TYPE,
                r.reason,
                v,
            )
        }
    }

    @Test
    fun `異常系11 JSON が不正`() {
        val broken =
            listOf(
                "",
                "   ",
                "not json",
                "{",
                "[]",
                "42",
                "null",
                """{"schema_version":1,"seq":1,"mode":"NORMAL","desired":{"mode":"NORMAL"}} trailing""",
                """{"schema_version":1,"seq":1,"mode":"NORMAL","mode":"STOPPED","desired":{"mode":"NORMAL"}}""", // 重複キー
            )
        for (j in broken) assertEquals(OpsFailureReason.INVALID_JSON, invalid(j).reason, j)
        assertEquals(OpsFailureReason.INVALID_JSON, OpsStateParser.parse(byteArrayOf(0xC3.toByte(), 0x28)).let { (it as ParseResult.Invalid).reason })
    }

    @Test
    fun `異常系12 必須項目が足りない`() {
        val noSeq = """{"schema_version":1,"mode":"NORMAL","desired":{"mode":"NORMAL"}}"""
        val noMode = """{"schema_version":1,"seq":1,"desired":{"mode":"NORMAL"}}"""
        val noDesired = """{"schema_version":1,"seq":1,"mode":"NORMAL"}"""
        val noSchema = """{"seq":1,"mode":"NORMAL","desired":{"mode":"NORMAL"}}"""
        for (j in listOf(noSeq, noMode, noDesired, noSchema)) assertEquals(OpsFailureReason.MISSING_FIELD, invalid(j).reason, j)
        assertEquals(OpsFailureReason.INVALID_DESIRED, invalid("""{"schema_version":1,"seq":1,"mode":"NORMAL","desired":{}}""").reason)
    }

    @Test
    fun `異常系13 mode が不正（UNKNOWN・小文字・空・別の値）`() {
        for (m in listOf("UNKNOWN", "normal", "Normal", "", " NORMAL", "RUNNING", "DRAINING")) {
            assertEquals(OpsFailureReason.INVALID_MODE, invalid(opsStateJson(mode = m)).reason, "[$m]")
        }
        assertEquals(OpsFailureReason.INVALID_FIELD_TYPE, invalid(opsStateJson().replace("\"mode\": \"NORMAL\",", "\"mode\": 1,")).reason)
    }

    @Test
    fun `異常系14 desired が不正`() {
        for (m in listOf("STARTING", "STOPPING", "UNKNOWN", "stopped", "")) {
            assertEquals(OpsFailureReason.INVALID_DESIRED, invalid(opsStateJson(desiredMode = m)).reason, "[$m]")
        }
        val base = """{"schema_version":1,"seq":1,"mode":"NORMAL","desired":%s}"""
        for (d in listOf("\"STOPPED\"", "[]", "1", """{"mode":null}""", """{"mode":1}""")) {
            assertEquals(OpsFailureReason.INVALID_DESIRED, invalid(base.format(d)).reason, d)
        }
    }

    @Test
    fun `不正なデータ型`() {
        for (s in listOf("\"1\"", "1.5", "42.0", "true", "-1", "99999999999999999999")) {
            val r = invalid(opsStateJson().replace("\"seq\": 1", "\"seq\": $s"))
            assertEquals(OpsFailureReason.INVALID_FIELD_TYPE, r.reason, "seq=$s")
        }
        assertEquals(OpsFailureReason.INVALID_FIELD_TYPE, invalid(opsStateJson().replace("\"operation\": {", "\"operation\": 5, \"x\": {")).reason)
        assertEquals(OpsFailureReason.INVALID_FIELD_TYPE, invalid(opsStateJson().replace("\"type\": \"stop\"", "\"type\": 7")).reason)
        assertEquals(OpsFailureReason.INVALID_FIELD_TYPE, invalid(opsStateJson().replace("2026-11-01T10:00:00Z", "yesterday")).reason)
    }

    @Test
    fun `検証エラーの detail に入力の値を含めない`() {
        val r = invalid(opsStateJson(mode = "SECRET-TOKEN-VALUE"))
        assertEquals("mode", r.detail)
    }
}
