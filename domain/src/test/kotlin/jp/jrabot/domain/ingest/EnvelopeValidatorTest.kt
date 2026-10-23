package jp.jrabot.domain.ingest

import jp.jrabot.domain.agent.AgentId
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EnvelopeValidatorTest {
    private val now = Instant.parse("2026-11-01T01:00:00Z")
    private val agent = AgentId.of("ag_test")
    private val validator = EnvelopeValidator()

    private fun record(
        bytes: ByteArray = "hello".toByteArray(),
        id: String = "RA",
        sha: String = Hashing.sha256Hex(bytes),
        b64: String = Base64.getEncoder().encodeToString(bytes),
    ) = mapOf("id" to id, "bytesB64" to b64, "sha256" to sha)

    private fun envelope(vararg overrides: Pair<String, Any?>): MutableMap<String, Any?> {
        val m =
            mutableMapOf<String, Any?>(
                "protocol" to 1,
                "agentId" to "ag_test",
                "batchId" to "01890a5d-ac96-774b-bcce-b302099a8057",
                "class" to "S",
                "observedAt" to "2026-11-01T00:59:00.123Z",
                "dataspec" to "RACE",
                "option" to 2,
                "fromtime" to "20261031200000",
                "lastFileTimestamp" to "20261031200000",
                "eof" to true,
                "records" to listOf(record()),
            )
        overrides.forEach { (k, v) -> if (v == null) m.remove(k) else m[k] = v }
        return m
    }

    private fun invalid(tree: Any?): IngestRejection = assertIs<EnvelopeResult.Invalid>(validator.validate(tree, agent, now)).rejection

    private fun valid(tree: Any?): IngestEnvelope = assertIs<EnvelopeResult.Valid>(validator.validate(tree, agent, now)).envelope

    @Test
    fun `正しい封筒は通り、項目がそのまま取り出せる`() {
        val e = valid(envelope())
        assertEquals(BatchClass.STORED, e.batchClass)
        assertEquals("RACE", e.dataspec)
        assertEquals(2, e.option)
        assertEquals(true, e.eof)
        assertEquals(1, e.records.size)
        assertEquals("hello", String(e.records[0].bytes))
        assertNull(e.fetch)
    }

    @Test
    fun `tenantId を本文で指示する封筒は理由つきで拒否する（テナントは本文から決めない）`() {
        assertEquals("TENANT_FIELD_FORBIDDEN", invalid(envelope("tenantId" to 2)).code)
        assertEquals("TENANT_FIELD_FORBIDDEN", invalid(envelope("tenant_id" to 2)).code)
        val rec = mapOf("id" to "RA", "bytesB64" to "aGVsbG8=", "sha256" to Hashing.sha256Hex("hello".toByteArray()), "tenantId" to 9)
        assertEquals("TENANT_FIELD_FORBIDDEN", invalid(envelope("records" to listOf(rec))).code)
    }

    @Test
    fun `未知のキー・欠落・型違いは400か422`() {
        assertEquals(422, invalid(envelope("extra" to 1)).status)
        assertEquals(400, invalid(envelope("batchId" to null)).status)
        assertEquals(400, invalid(envelope("eof" to "true")).status)
        assertEquals(400, invalid(envelope("records" to "x")).status)
        assertEquals(400, invalid("not an object").status)
        assertEquals(400, invalid(null).status)
    }

    @Test
    fun `未対応のprotocolは422`() {
        assertEquals("UNSUPPORTED_PROTOCOL", invalid(envelope("protocol" to 2)).code)
        assertEquals(400, invalid(envelope("protocol" to "1")).status)
        assertEquals(400, invalid(envelope("protocol" to 1.5)).status)
    }

    @Test
    fun `認証したエージェントと違うagentIdは403`() {
        assertEquals(403, invalid(envelope("agentId" to "ag_other")).status)
        assertEquals(422, invalid(envelope("agentId" to "bad id!")).status)
    }

    @Test
    fun `batchId は小文字のUUIDv7だけ（大文字・別の版・形式違いは拒否）`() {
        for (bad in listOf(
            "01890A5D-AC96-774B-BCCE-B302099A8057",
            "01890a5d-ac96-474b-bcce-b302099a8057",
            "not-a-uuid",
            "01890a5d-ac96-774b-cc00-b302099a8057",
        )) {
            assertEquals("INVALID_BATCH_ID", invalid(envelope("batchId" to bad)).code, bad)
        }
    }

    @Test
    fun `不正なデータ種別・class・class と種別の不一致`() {
        assertEquals("UNSUPPORTED_DATASPEC", invalid(envelope("dataspec" to "ZZZZ")).code)
        assertEquals("INVALID_CLASS", invalid(envelope("class" to "C")).code)
        assertEquals("CLASS_DATASPEC_MISMATCH", invalid(envelope("class" to "R")).code) // RACE は蓄積系
        assertEquals("CLASS_DATASPEC_MISMATCH", invalid(envelope("dataspec" to "0B31")).code)
        val r = valid(envelope("class" to "R", "dataspec" to "0B31", "eof" to false))
        assertEquals(BatchClass.REALTIME, r.batchClass)
    }

    @Test
    fun `タイムスタンプ： 未来・古すぎる速報・形式違い`() {
        assertEquals("OBSERVED_AT_IN_FUTURE", invalid(envelope("observedAt" to "2026-11-01T01:06:00Z")).code)
        valid(envelope("observedAt" to "2026-11-01T01:04:59Z")) // 許容内（+300 秒）
        assertEquals("INVALID_OBSERVED_AT", invalid(envelope("observedAt" to "2026-11-01 01:00:00")).code)
        assertEquals("INVALID_OBSERVED_AT", invalid(envelope("observedAt" to "2026-11-01T10:00:00+09:00")).code)
        // 蓄積系は古くてよいが、速報は 7 日より古いものを拒否
        valid(envelope("observedAt" to "2026-01-01T00:00:00Z"))
        assertEquals(
            "OBSERVED_AT_TOO_OLD",
            invalid(envelope("class" to "R", "dataspec" to "0B31", "eof" to false, "observedAt" to "2026-10-20T00:00:00Z")).code,
        )
        assertEquals("INVALID_TIMESTAMP", invalid(envelope("fromtime" to "20261301000000")).code) // 13 月
        assertEquals("INVALID_TIMESTAMP", invalid(envelope("lastFileTimestamp" to "2026103120")).code)
        assertEquals("INVALID_OPTION", invalid(envelope("option" to 10)).code)
    }

    @Test
    fun `レコード件数の上限と空バッチ`() {
        val many = (1..201).map { record(bytes = "r$it".toByteArray()) }
        assertEquals(413, invalid(envelope("records" to many)).status)
        valid(envelope("records" to many.take(200), "eof" to false))
        assertEquals("EMPTY_BATCH", invalid(envelope("records" to emptyList<Any>(), "eof" to false)).code)
        assertTrue(valid(envelope("records" to emptyList<Any>(), "eof" to true)).records.isEmpty()) // EOF だけの最終バッチ
    }

    @Test
    fun `レコードの検証： SHA-256・Base64・サイズ・ID`() {
        assertEquals("RECORD_SHA256_MISMATCH", invalid(envelope("records" to listOf(record(sha = "0".repeat(64))))).code)
        assertEquals("INVALID_SHA256", invalid(envelope("records" to listOf(record(sha = "ABC")))).code)
        assertEquals("INVALID_BASE64", invalid(envelope("records" to listOf(record(b64 = "aGVsbG8")))).code) // パディング欠落
        assertEquals("INVALID_BASE64", invalid(envelope("records" to listOf(record(b64 = "aGVs\nbG8=")))).code)
        assertEquals("INVALID_BASE64", invalid(envelope("records" to listOf(record(b64 = "aGVsbG8_")))).code) // URL セーフ文字
        assertEquals("INVALID_RECORD_ID", invalid(envelope("records" to listOf(record(id = "ra")))).code)
        assertEquals("INVALID_RECORD_ID", invalid(envelope("records" to listOf(record(id = "TOOLONG")))).code)
        val small = EnvelopeValidator(EnvelopeLimits(maxRecordBytes = 1024))
        val big = ByteArray(2048) { 1 }
        val r = small.validate(envelope("records" to listOf(record(bytes = big))), agent, now)
        assertEquals(413, assertIs<EnvelopeResult.Invalid>(r).rejection.status)
    }

    @Test
    fun `fetch： 取得セッション情報の検証`() {
        val fetch = mapOf("id" to "11111111-2222-4333-8444-555555555555", "seq" to 0, "targetFrom" to "20261101", "targetTo" to "20271231")
        val e = valid(envelope("dataspec" to "YSCH", "fetch" to fetch))
        assertEquals(0, e.fetch?.seq)
        assertEquals(FetchOrigin.LIVE, e.fetch?.origin)
        assertEquals("INVALID_FETCH_ID", invalid(envelope("fetch" to fetch + ("id" to "x"))).code)
        assertEquals("INVALID_FETCH_SEQ", invalid(envelope("fetch" to fetch + ("seq" to -1))).code)
        assertEquals("INVALID_FETCH_RANGE", invalid(envelope("fetch" to fetch + ("targetTo" to "20261031"))).code)
        assertEquals("INVALID_FETCH_RANGE", invalid(envelope("fetch" to fetch - "targetTo")).code)
        assertEquals("INVALID_DATE", invalid(envelope("fetch" to fetch + ("targetFrom" to "20261331"))).code)
        assertEquals("INVALID_BACKFILL_ID", invalid(envelope("fetch" to fetch + ("origin" to "BACKFILL"))).code) // BACKFILL と backfillId は対
        assertEquals("INVALID_BACKFILL_ID", invalid(envelope("fetch" to fetch + ("backfillId" to 3))).code)
        assertEquals(FetchOrigin.BACKFILL, valid(envelope("fetch" to fetch + ("origin" to "BACKFILL") + ("backfillId" to 3))).fetch!!.origin)
        assertEquals("UNKNOWN_FIELD", invalid(envelope("fetch" to fetch + ("zzz" to 1))).code)
        // 速報（R）に fetch は付けない
        assertEquals("FETCH_NOT_APPLICABLE", invalid(envelope("class" to "R", "dataspec" to "0B31", "eof" to false, "fetch" to fetch)).code)
    }

    @Test
    fun `payloadSha256 は内容が同じなら同じで、1項目でも違えば変わる（JSONの整形に依存しない）`() {
        val a = valid(envelope())
        val b = valid(envelope().toSortedMap().toMutableMap())
        assertEquals(a.payloadSha256, b.payloadSha256)
        assertNotEquals(a.payloadSha256, valid(envelope("eof" to false)).payloadSha256)
        assertNotEquals(a.payloadSha256, valid(envelope("records" to listOf(record(bytes = "other".toByteArray())))).payloadSha256)
        assertNotEquals(a.payloadSha256, valid(envelope("observedAt" to "2026-11-01T00:59:01Z")).payloadSha256)
    }

    @Test
    fun `record_hash： 蓄積系は同じバイトなら同じ、速報は観測時刻ごとに別`() {
        val sha = Hashing.sha256Hex("x".toByteArray())
        val t1 = Instant.parse("2026-11-01T00:00:00Z")
        val t2 = Instant.parse("2026-11-01T00:01:00Z")
        assertEquals(Hashing.recordHash("RACE", "RA", BatchClass.STORED, t1, sha), Hashing.recordHash("RACE", "RA", BatchClass.STORED, t2, sha))
        assertNotEquals(Hashing.recordHash("0B31", "O1", BatchClass.REALTIME, t1, sha), Hashing.recordHash("0B31", "O1", BatchClass.REALTIME, t2, sha))
        assertNotEquals(Hashing.recordHash("RACE", "RA", BatchClass.STORED, t1, sha), Hashing.recordHash("RACE", "SE", BatchClass.STORED, t1, sha))
    }
}
