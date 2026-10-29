package jp.jrabot.persistence.integration

import jp.jrabot.domain.ingest.ParseStatus
import jp.jrabot.domain.ingest.RawRecordStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 保留（PENDING）の生レコードの一覧は**新しい順**で、確定済みの行は更新されず、テナントを越えない（監査 G2 の追補）。 */
@Tag("integration")
class RawPendingOrderIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "ingest", poolMax = 2)

        @JvmStatic
        @AfterAll
        fun close() = h.close()
    }

    private fun hash(n: Int) = n.toString().padStart(64, '0')

    private val rawCols = "(tenant_id, record_hash, batch_id, seq, dataspec, record_id, payload, received_at, parse_status, parse_error)"
    private val batchCols = "(tenant_id, batch_id, agent_id, batch_class, dataspec, observed_at, received_at, payload_sha256, record_count)"
    private val agentCols = "(tenant_id, agent_id, name, token_hash, token_expires_at, status, created_at)"

    private fun batchId(tenant: Int) = "00000000-0000-7000-8000-00000000000$tenant"

    private fun raw(
        tenant: Int,
        h: Int,
        seq: Int,
        receivedAt: String,
    ) {
        val values = "$tenant, '${hash(h)}', '${batchId(tenant)}', $seq, '0B31', 'NOW', 'x', '$receivedAt', 'PENDING', 'RACE_NOT_FOUND'"
        db.exec("INSERT INTO ingest_raw $rawCols VALUES ($values)")
    }

    private fun seed() {
        db.resetData()
        db.exec("DELETE FROM ingest_raw")
        db.exec("DELETE FROM ingest_batch")
        db.exec("DELETE FROM agent")
        for (t in 1..2) {
            db.exec("INSERT INTO agent $agentCols VALUES ($t, 'a$t', 'pc', '${hash(t)}', '2030-01-01', 'ACTIVE', NOW(6))")
            val batch = "$t, '${batchId(t)}', 'a$t', 'R', '0B31', '2026-11-01 01:00:00.000000', NOW(6), '${hash(10 + t)}', 3"
            db.exec("INSERT INTO ingest_batch $batchCols VALUES ($batch)")
        }
        // テナント 1: 古い順に 1, 2, 3 の 3 件（3 が最新）。テナント 2 にも 1 件
        for (n in 1..3) raw(1, 100 + n, n, "2026-11-01 01:0$n:00.000000")
        raw(2, 200, 1, "2026-11-01 02:00:00.000000")
    }

    @Test
    fun `新しい順に上限まで返す 古い保留が居座っても新しい保留の再適用が飢えない 他のテナントは混ざらない`() {
        seed()
        val store = h.bean(RawRecordStore::class.java)
        assertEquals(listOf(hash(103), hash(102)), store.listPending(T1, "RACE_NOT_FOUND", 2).map { it.recordHash })
        assertEquals(listOf(hash(200)), store.listPending(T2, "RACE_NOT_FOUND", 10).map { it.recordHash })
        assertTrue(store.listPending(T1, "OTHER", 10).isEmpty())
    }

    @Test
    fun `settlePending は PENDING の行だけを更新し 確定済みは変えず 他のテナントの行には効かない`() {
        seed()
        val store = h.bean(RawRecordStore::class.java)
        assertTrue(store.settlePending(T1, hash(101), ParseStatus.PARSED, null))
        assertEquals(false, store.settlePending(T1, hash(101), ParseStatus.FAILED, "X"), "確定済みは変えない")
        assertEquals(false, store.settlePending(T2, hash(102), ParseStatus.PARSED, null), "別テナントの行には効かない")
        assertEquals("PARSED", db.scalar("SELECT parse_status FROM ingest_raw WHERE record_hash = '${hash(101)}'"))
        assertEquals("PENDING", db.scalar("SELECT parse_status FROM ingest_raw WHERE record_hash = '${hash(102)}'"))
    }
}
