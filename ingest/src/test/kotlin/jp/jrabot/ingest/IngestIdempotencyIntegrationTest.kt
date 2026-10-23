package jp.jrabot.ingest

import jp.jrabot.domain.ingest.EnvelopeResult
import jp.jrabot.domain.ingest.EnvelopeValidator
import jp.jrabot.persistence.integration.runConcurrently
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** バッチの冪等性・並行・途中失敗・再開（本物の MySQL）。アプリ内の排他ではなく DB の UNIQUE とトランザクションで守られていることを確かめる。 */
@Tag("integration")
class IngestIdempotencyIntegrationTest {
    private val it = IngestIt

    @BeforeEach
    fun setUp() = it.reset()

    private fun env(
        batch: String = it.batchId(),
        vararg r: Map<String, Any?>,
    ) = it.envelope(batchId = batch, records = r.toList())

    @Test
    fun `初回はACKされ、同じ内容の再送は重複ACK（二重登録しない）`() {
        val batch = it.batchId()
        val e = env(batch, it.nys("20261101"), it.nys("20261101", venue = "09", kai = 2))
        val first = it.parse(it.ingest(it.one, e))
        assertEquals(2, first["accepted"])
        assertEquals(0, first["duplicates"])
        assertEquals(false, first["duplicate"])
        val second = it.ingest(it.one, e)
        assertEquals(200, second.status)
        val s = it.parse(second)
        assertEquals(true, s["duplicate"])
        assertEquals(2, s["accepted"], "重複ACKは最初の処理の件数")
        assertEquals(first["ackTs"], s["ackTs"], "同じ ackTs")
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_batch WHERE status = 'COMPLETED'"))
        assertEquals(2, it.count("SELECT COUNT(*) FROM ingest_raw"))
        assertEquals(2, it.count("SELECT COUNT(*) FROM race_day"))
        // gzip で送り直しても（バイト列が違っても）同じバッチ
        val gz = it.parse(it.ingest(it.one, e, gzip = true))
        assertEquals(true, gz["duplicate"])
    }

    @Test
    fun `同じbatchIdで内容が違えば拒否し、何も変えない`() {
        val batch = it.batchId()
        assertEquals(200, it.ingest(it.one, env(batch, it.nys("20261101"))).status)
        val reuse = it.ingest(it.one, env(batch, it.nys("20261108")))
        assertEquals(422, reuse.status)
        assertTrue(reuse.body.contains("BATCH_ID_REUSED"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_day"))
        // eof だけが違う再送も別内容
        assertEquals(422, it.ingest(it.one, it.envelope(batchId = batch, eof = false, records = listOf(it.nys("20261101")))).status)
        // 別のエージェントが同じテナントで同じ batchId を使うことも拒否（他人のバッチを横取りしない）
        val other = it.register("ag_three", 1)
        assertEquals(422, it.ingest(other, it.envelope(batchId = batch, agent = other, records = listOf(it.nys("20261101")))).status)
        it.db.exec("DELETE FROM agent_nonce")
        it.db.exec("DELETE FROM agent WHERE agent_id = 'ag_three'")
    }

    @Test
    fun `別のbatchIdでも同じレコードは record_hash で吸収される（蓄積系）`() {
        val rec = it.nys("20261101")
        val a = it.parse(it.ingest(it.one, env(it.batchId(), rec)))
        val b = it.parse(it.ingest(it.one, env(it.batchId(), rec, it.nys("20261108"))))
        assertEquals(1, a["accepted"])
        assertEquals(1, b["accepted"])
        assertEquals(1, b["duplicates"])
        assertEquals(2, it.count("SELECT COUNT(*) FROM ingest_raw"))
        assertEquals(2, it.count("SELECT COUNT(*) FROM ingest_batch"))
        // 速報(R)は同じバイト列でも観測時刻が違えば別の点
        val race = "202611010511"
        it.ingest(it.one, env(it.batchId(), it.nra(race, "2", "20261030")))
        val t1 =
            it.ingest(
                it.one,
                it.envelope(cls = "R", dataspec = "0B31", eof = false, records = listOf(it.now(race, 1 to 2.5)), observedAt = ItClock.now().minusSeconds(60)),
            )
        val t2 = it.ingest(it.one, it.envelope(cls = "R", dataspec = "0B31", eof = false, records = listOf(it.now(race, 1 to 2.5)), observedAt = ItClock.now()))
        assertEquals(1, it.parse(t1)["accepted"])
        assertEquals(1, it.parse(t2)["accepted"], "同じオッズでも別の時刻の観測は別のレコード")
        assertEquals(2, it.count("SELECT COUNT(DISTINCT observed_at) FROM odds_snapshots"))
    }

    @Test
    fun `同じbatchIdの同時送信でも二重登録しない（勝者は1つ、他は重複ACKか409）`() {
        val batch = it.batchId()
        val e = env(batch, it.nys("20261101"), it.nys("20261108"), it.nys("20261115"))
        repeat(5) { round ->
            it.reset()
            val results = runConcurrently(8) { _ -> it.ingest(it.one, e) }
            val resps = results.map { r -> r.getOrThrow() }
            assertTrue(resps.all { r -> r.status == 200 || r.status == 409 }, "round $round: ${resps.map { r -> r.status }}")
            val winners = resps.filter { r -> r.status == 200 }.map { r -> it.parse(r) }.filter { m -> m["duplicate"] == false }
            assertEquals(1, winners.size, "round $round: 初回の処理は 1 本だけ")
            assertEquals(3, winners.single()["accepted"])
            assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_batch"))
            assertEquals("COMPLETED", it.db.scalar("SELECT status FROM ingest_batch"))
            assertEquals(3, it.count("SELECT COUNT(*) FROM ingest_raw"))
            assertEquals(3, it.count("SELECT COUNT(*) FROM race_day"))
            assertEquals(3, it.count("SELECT COUNT(*) FROM race_schedule_event WHERE event_type = 'DAY_STATUS'"), "履歴も二重にならない")
        }
    }

    @Test
    fun `別のbatchIdで同じレコードを同時に送っても、生レコードは1行`() {
        val rec = it.nys("20261101")
        val results = runConcurrently(8) { _ -> it.ingest(it.one, env(it.batchId(), rec)) }
        val maps = results.map { r -> it.parse(r.getOrThrow()) }
        assertEquals(8, maps.size)
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw"))
        assertEquals(1, maps.sumOf { m -> m["accepted"] as Int }, "新規に保存したのは 1 本だけ")
        assertEquals(7, maps.sumOf { m -> m["duplicates"] as Int })
        assertEquals(8, it.count("SELECT COUNT(*) FROM ingest_batch WHERE status = 'COMPLETED'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_day"))
    }

    @Test
    fun `処理中なら409、放置されて一定時間が過ぎれば引き継いで完了できる`() {
        val batch = it.batchId()
        val e = env(batch, it.nys("20261101"))
        val tree = it.json.readValue(it.bytes(e), Any::class.java)
        val valid =
            assertIs<EnvelopeResult.Valid>(
                EnvelopeValidator().validate(
                    tree,
                    jp.jrabot.domain.agent.AgentId
                        .of("ag_one"),
                    ItClock.now(),
                ),
            )
        // 別のリクエストが処理中の状態を作る（PROCESSING の行）
        it.db.exec(
            "INSERT INTO ingest_batch (tenant_id, batch_id, agent_id, batch_class, dataspec, option_no, fromtime, last_file_timestamp, eof, " +
                "observed_at, received_at, " +
                "payload_sha256, record_count, status) VALUES (1, '$batch', 'ag_one', 'S', 'RACE', 2, '20261001000000', '20261031200000', 1, NOW(6), NOW(6), " +
                "'${valid.envelope.payloadSha256}', 1, 'PROCESSING')",
        )
        val busy = it.ingest(it.one, e)
        assertEquals(409, busy.status)
        assertTrue(busy.body.contains("BATCH_IN_PROGRESS"))
        assertEquals("5", busy.header("Retry-After"))
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day"))
        // 3 分前に始まった処理は放置とみなして引き継ぐ
        it.db.exec("UPDATE ingest_batch SET received_at = DATE_SUB(NOW(6), INTERVAL 3 MINUTE) WHERE batch_id = '$batch'")
        val takeover = it.ingest(it.one, e)
        assertEquals(200, takeover.status, takeover.body)
        assertEquals("COMPLETED", it.db.scalar("SELECT status FROM ingest_batch WHERE batch_id = '$batch'"))
        assertEquals(1, it.count("SELECT COUNT(*) FROM race_day"))
        assertEquals(true, it.parse(it.ingest(it.one, e))["duplicate"])
    }

    @Test
    fun `途中で失敗したら全部巻き戻り、バッチはFAILED。再送で完了できる`() {
        val batch = it.batchId()
        val e = env(batch, it.nys("20261101"), it.nys("20261108"), it.nys("20261115"))
        it.faults.rawInsertFailOn = 3 // 3 件目の保存で失敗（1・2 件目の保存と開催日の反映は同じトランザクション）
        val failed = it.ingest(it.one, e)
        assertEquals(500, failed.status)
        assertEquals("""{"error":"INTERNAL"}""", failed.body, "例外の内容を返さない")
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw"), "不完全な生レコードを確定しない")
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day"), "不完全な開催日を確定しない")
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_schedule_event"))
        assertEquals("FAILED", it.db.scalar("SELECT status FROM ingest_batch WHERE batch_id = '$batch'"))
        assertTrue(it.db.scalar("SELECT fail_reason FROM ingest_batch WHERE batch_id = '$batch'")!!.startsWith("ERROR:"))
        it.faults.reset()
        val retry = it.parse(it.ingest(it.one, e))
        assertEquals(3, retry["accepted"])
        assertEquals(false, retry["duplicate"])
        assertEquals(3, it.count("SELECT COUNT(*) FROM race_day"))
        assertEquals("COMPLETED", it.db.scalar("SELECT status FROM ingest_batch WHERE batch_id = '$batch'"))
    }

    @Test
    fun `ACK直前の失敗（完了の印の後・コミット前）では何も確定せず、応答を取り損ねた再送は重複ACKになる`() {
        val fid = it.fetchId()
        val batch = it.batchId()
        val e = it.envelope(batchId = batch, dataspec = "RACE", fetch = it.fetch(fid, 0, null, null), records = listOf(it.rec("RA", "raw-bytes".toByteArray())))
        it.faults.fetchSaveFails = true // バッチの完了印を付けた後、取得セッションの保存で失敗 = コミット前の切断を再現
        assertEquals(500, it.ingest(it.one, e).status)
        assertEquals("FAILED", it.db.scalar("SELECT status FROM ingest_batch WHERE batch_id = '$batch'"), "完了の印は巻き戻る")
        assertEquals(0, it.count("SELECT COUNT(*) FROM ingest_raw"))
        assertEquals("IN_PROGRESS", it.db.scalar("SELECT status FROM ingest_fetch WHERE fetch_id = '$fid'"))
        assertEquals(0, it.db.scalar("SELECT next_seq FROM ingest_fetch WHERE fetch_id = '$fid'")!!.toInt())
        it.faults.reset()
        val ok = it.parse(it.ingest(it.one, e))
        assertEquals(1, ok["accepted"])
        // 応答（200）をエージェントが受け取れなかったものとして、同じバッチを再送 → 重複ACK、二重登録なし
        val again = it.parse(it.ingest(it.one, e))
        assertEquals(true, again["duplicate"])
        assertEquals(1, it.count("SELECT COUNT(*) FROM ingest_raw"))
        assertEquals(1, it.db.scalar("SELECT next_seq FROM ingest_fetch WHERE fetch_id = '$fid'")!!.toInt(), "取得セッションの連番も二重に進まない")
    }

    @Test
    fun `再試行しても直らない失敗（権限の欠落）は500で本文は固定語彙、直せば同じバッチを再送できる`() {
        val e = env(it.batchId(), it.nys("20261101"))
        it.db.exec("REVOKE INSERT ON `${it.db.name}`.`ingest_raw` FROM 'ingest'@'%'")
        try {
            val r = it.ingest(it.one, e)
            assertEquals(500, r.status, "権限の欠落は再試行しても直らないので 5xx（500）")
            assertEquals("""{"error":"INTERNAL"}""", r.body)
        } finally {
            it.db.exec("GRANT INSERT ON `${it.db.name}`.`ingest_raw` TO 'ingest'@'%'")
        }
        assertEquals(0, it.count("SELECT COUNT(*) FROM race_day"))
        assertEquals(200, it.ingest(it.one, e).status)
    }
}
