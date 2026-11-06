package jp.jrabot.persistence.integration

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ModelRunPin
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.time.Clock
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** モデルレジストリ（`ml_model`）の読取り。bot の DB ユーザー（SELECT のみ）で、テナント境界・ACTIVE の取得・固定（ModelRunPin）を確認する。合成データだけ。 */
@Tag("integration")
class MlModelRegistryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val h = RepositoryHarness(db, user = "bot")

        @JvmStatic
        @AfterAll
        fun close() = h.close()

        private fun hex(n: Int) = n.toString(16).padStart(64, '0')
    }

    @BeforeEach
    fun reset() {
        db.resetData()
        db.exec("UPDATE ml_model SET previous_model_id = NULL")
        db.exec("DELETE FROM ml_model") // resetData は ml_model を消さない
        db.exec("INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (2, 'second', '2026-10-24 00:00:00.000000')")
    }

    private fun insert(
        tenant: Int,
        id: String,
        kind: String,
        status: String,
        previous: String? = null,
        n: Int = 1,
    ) {
        val active = status == "ACTIVE"
        db.exec(
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, " +
                "signature_sha256, status, active_flag, trained_from, trained_to, n_train, created_at, activated_at, activated_by, previous_model_id) VALUES " +
                "($tenant, '$id', '$kind', 1, 'sha256:${hex(
                    n,
                )}', 'gs://synthetic/$id', '${hex(n + 100)}', '${hex(n + 200)}', '$status', ${if (active) "1" else "NULL"}, " +
                "'2025-01-17', '2026-01-17', 1234, '2026-10-01 00:00:00.123456', ${if (active) "'2026-10-02 03:04:05.000000'" else "NULL"}, " +
                "${if (active) "'synthetic-admin'" else "NULL"}, ${previous?.let { "'$it'" } ?: "NULL"})",
        )
    }

    @Test
    fun `一覧（R22）は自分のテナントだけを新しい順に件数の上限まで返し 指標の JSON も読める`() {
        insert(1, "m_old", "calibration", "RETIRED", n = 1)
        insert(1, "m_new", "calibration", "ACTIVE", previous = "m_old", n = 2)
        insert(1, "m_cand", "alpha", "CANDIDATE", n = 3)
        insert(2, "m_other", "calibration", "CANDIDATE", n = 4)
        db.exec("UPDATE ml_model SET created_at = '2026-09-01 00:00:00.000000' WHERE model_id = 'm_old'")
        db.exec("""UPDATE ml_model SET metrics_json = '{"gate_purpose":"p","logloss":0.5}' WHERE model_id = 'm_cand'""")
        val rows = h.catalog.recent(T1, 10)
        assertEquals(listOf("m_cand", "m_new", "m_old"), rows.map { it.record.modelId.value }, "新しい順・同時刻は model_id の昇順。他テナントは出ない")
        assertTrue(rows[0].metricsJson.orEmpty().contains("logloss"), "指標の JSON を読める")
        assertNull(rows[1].metricsJson)
        assertEquals(ModelStatus.ACTIVE, rows[1].record.status)
        assertEquals(listOf("m_cand", "m_new"), h.catalog.recent(T1, 2).map { it.record.modelId.value })
        assertEquals(
            listOf("m_other"),
            h.catalog
                .recent(
                    jp.jrabot.domain.tenant.TenantId
                        .of(2),
                    10,
                ).map { it.record.modelId.value },
        )
        assertFailsWith<IllegalArgumentException> { h.catalog.recent(T1, 0) }
        assertFailsWith<IllegalArgumentException> { h.catalog.recent(T1, 201) }
    }

    @Test
    fun `無いモデルは null`() {
        assertNull(h.models.find(T1, ModelId.of("nope")))
        assertNull(h.models.active(T1, ModelKind.CALIBRATION))
    }

    @Test
    fun `モデルを全列読み戻せる（時刻は UTC、日付・件数・前のモデル）`() {
        insert(1, "m_old", "calibration", "RETIRED", n = 1)
        insert(1, "m_new", "calibration", "ACTIVE", previous = "m_old", n = 2)
        val r = assertNotNull(h.models.find(T1, ModelId.of("m_new")))
        assertEquals(ModelKind.CALIBRATION, r.kind)
        assertEquals(ModelStatus.ACTIVE, r.status)
        assertEquals("sha256:${hex(2)}", r.featureSchemaHash)
        assertEquals(hex(102), r.sha256)
        assertEquals(hex(202), r.signatureSha256)
        assertEquals("gs://synthetic/m_new", r.artifactUri)
        assertEquals(LocalDate.of(2025, 1, 17), r.trainedFrom)
        assertEquals(1234L, r.nTrain)
        assertEquals(Instant.parse("2026-10-01T00:00:00.123456Z"), r.createdAt)
        assertEquals(Instant.parse("2026-10-02T03:04:05Z"), r.activatedAt)
        assertEquals("synthetic-admin", r.activatedBy)
        assertEquals(ModelId.of("m_old"), r.previousModelId)
        assertEquals(ModelStatus.RETIRED, h.models.find(T1, ModelId.of("m_old"))!!.status)
    }

    @Test
    fun `ACTIVE は種別ごとに 1 つで、CANDIDATE・REJECTED・RETIRED は返らない`() {
        insert(1, "cal_a", "calibration", "ACTIVE", n = 1)
        insert(1, "cal_c", "calibration", "CANDIDATE", n = 2)
        insert(1, "alp_r", "alpha", "REJECTED", n = 3)
        insert(1, "alp_x", "alpha", "RETIRED", n = 4)
        insert(1, "leg_a", "legacy_compat", "ACTIVE", n = 5)
        assertEquals(
            "cal_a",
            h.models
                .active(T1, ModelKind.CALIBRATION)!!
                .modelId.value,
        )
        assertNull(h.models.active(T1, ModelKind.ALPHA))
        assertEquals(
            "leg_a",
            h.models
                .active(T1, ModelKind.LEGACY_COMPAT)!!
                .modelId.value,
        )
        assertNull(h.models.active(T1, ModelKind.ENSEMBLE_SET))
        // 同じ種別に ACTIVE を 2 つ置けない（DB の UNIQUE(tenant_id, kind, active_flag)）
        assertFailsWith<Exception> { insert(1, "cal_b", "calibration", "ACTIVE", n = 6) }
    }

    @Test
    fun `テナントが違えば同じ model_id でも別、他テナントの ACTIVE は見えない`() {
        insert(1, "shared_id", "alpha", "ACTIVE", n = 1)
        insert(2, "shared_id", "alpha", "ACTIVE", n = 2)
        insert(2, "only2", "calibration", "ACTIVE", n = 3)
        assertEquals("sha256:${hex(1)}", h.models.find(T1, ModelId.of("shared_id"))!!.featureSchemaHash)
        assertEquals("sha256:${hex(2)}", h.models.find(T2, ModelId.of("shared_id"))!!.featureSchemaHash)
        assertNull(h.models.find(T1, ModelId.of("only2")))
        assertNull(h.models.active(T1, ModelKind.CALIBRATION))
        assertEquals(
            "only2",
            h.models
                .active(T2, ModelKind.CALIBRATION)!!
                .modelId.value,
        )
    }

    @Test
    fun `bot-run の開始時に ACTIVE を固定できる（昇格は読み取り側から行えない）`() {
        insert(1, "cal_a", "calibration", "ACTIVE", n = 1)
        insert(1, "alp_a", "alpha", "ACTIVE", n = 2)
        insert(1, "ens_a", "ensemble_set", "CANDIDATE", n = 3)
        val pin =
            ModelRunPin.capture(
                h.models,
                T1,
                object : Clock {
                    override fun now() = Instant.parse("2026-10-24T01:00:00Z")
                },
            )
        assertEquals(setOf(ModelKind.CALIBRATION, ModelKind.ALPHA), pin.modelIds().keys)
        // bot の DB ユーザーは ml_model を更新できない（昇格は app の管理操作。Stage 4）
        assertSqlError(MySqlError.TABLE_DENIED, MySqlError.COLUMN_DENIED) {
            h.dataSource.connection.use { c -> c.createStatement().use { it.executeUpdate("UPDATE ml_model SET status = 'ACTIVE' WHERE model_id = 'ens_a'") } }
        }
        assertEquals("CANDIDATE", db.scalar("SELECT status FROM ml_model WHERE model_id = 'ens_a'"))
        assertTrue(h.models.find(T1, ModelId.of("ens_a"))!!.status == ModelStatus.CANDIDATE)
    }
}
