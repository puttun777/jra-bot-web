package jp.jrabot.persistence.integration

import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.DuplicateModelException
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.ml.registry.NewModelRecord
import jp.jrabot.domain.tenant.TenantId
import jp.jrabot.persistence.jdbc.DataSourceFactory
import jp.jrabot.persistence.jdbc.DbSettings
import jp.jrabot.persistence.repository.TrainingPersistence
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

/**
 * 学習 Job（ml-train）のレジストリ。本番と同じ DB ユーザー `mltrain`（ml_model の SELECT / INSERT だけ）で、CANDIDATE / REJECTED の登録・
 * 主キーの重複・テナント境界・ACTIVE の指標の読取りを確かめる（D-257。旧 Python の MySqlRegistry の置き換え）。合成データだけ。
 */
@Tag("integration")
class MlModelTrainingRegistryIntegrationTest {
    companion object {
        private val db = MySqlSupport.migratedDatabase()
        private val ds =
            DataSourceFactory.create(
                DbSettings(MySqlSupport.jdbcUrl(db.name), "mltrain", MySqlSupport.PASSWORDS.getValue("mltrain"), 2),
                "it-mltrain",
            )
        private val registry = TrainingPersistence.trainingRegistry(ds)

        @JvmStatic
        @AfterAll
        fun close() = ds.close()

        private fun hex(n: Int) = n.toString(16).padStart(64, '0')
    }

    @BeforeEach
    fun reset() {
        db.exec("UPDATE ml_model SET previous_model_id = NULL")
        db.exec("DELETE FROM ml_model")
        db.exec("INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (1, 'synthetic', '2026-10-24 00:00:00.000000')")
        db.exec("INSERT IGNORE INTO tenant (tenant_id, name, created_at) VALUES (2, 'second', '2026-10-24 00:00:00.000000')")
    }

    private fun row(
        tenant: Long = 1,
        id: String = "mc-0001",
        status: ModelStatus = ModelStatus.CANDIDATE,
    ) = NewModelRecord(
        TenantId.of(tenant),
        ModelId.of(id),
        ModelKind.CALIBRATION,
        1,
        "sha256:${hex(1)}",
        "file:///store/$tenant/models/$id.json",
        hex(2),
        hex(3),
        status,
        LocalDate.of(2025, 1, 17),
        LocalDate.of(2026, 1, 17),
        1234,
        """{"gate_verdict":"PASS","logloss":0.25}""",
        Instant.parse("2026-11-02T01:02:03.456789Z"),
    )

    @Test
    fun `candidate and rejected rows are inserted and read back, without any active flag`() {
        registry.insert(row())
        registry.insert(row(id = "mc-0002", status = ModelStatus.REJECTED))
        val r = assertNotNull(registry.find(TenantId.of(1), ModelId.of("mc-0001")))
        assertEquals(ModelStatus.CANDIDATE, r.status)
        assertEquals("sha256:${hex(1)}", r.featureSchemaHash)
        assertEquals(hex(2), r.sha256)
        assertEquals(Instant.parse("2026-11-02T01:02:03.456789Z"), r.createdAt)
        assertEquals(LocalDate.of(2026, 1, 17), r.trainedTo)
        assertEquals("0", db.scalar("SELECT COUNT(*) FROM ml_model WHERE active_flag IS NOT NULL OR activated_at IS NOT NULL"))
        assertEquals("REJECTED", db.scalar("SELECT status FROM ml_model WHERE model_id = 'mc-0002'"))
    }

    @Test
    fun `a duplicate primary key is reported as such`() {
        registry.insert(row())
        assertFailsWith<DuplicateModelException> { registry.insert(row()) }
        registry.insert(row(tenant = 2)) // 別テナントの同じ model_id は別の行
        assertEquals("2", db.scalar("SELECT COUNT(*) FROM ml_model WHERE model_id = 'mc-0001'"))
    }

    @Test
    fun `active metrics are read per tenant and kind only`() {
        assertNull(registry.activeMetrics(TenantId.of(1), ModelKind.CALIBRATION))
        val (schemaHash, sha, sigSha) = Triple(hex(9), hex(8), hex(7))
        db.exec(
            "INSERT INTO ml_model (tenant_id, model_id, kind, schema_version, feature_schema_hash, artifact_uri, sha256, signature_sha256, " +
                "status, active_flag, metrics_json, created_at, activated_at, activated_by) VALUES " +
                "(2, 'act-2', 'calibration', 1, 'sha256:$schemaHash', 'u', '$sha', '$sigSha', 'ACTIVE', 1, " +
                "'{\"logloss\": 0.31}', '2026-10-01 00:00:00.000000', '2026-10-02 00:00:00.000000', 'synthetic-admin')",
        )
        assertNull(registry.activeMetrics(TenantId.of(1), ModelKind.CALIBRATION), "他テナントの ACTIVE は見えない")
        assertNull(registry.activeMetrics(TenantId.of(2), ModelKind.ALPHA), "種別が違えば無い")
        val a = assertNotNull(registry.activeMetrics(TenantId.of(2), ModelKind.CALIBRATION))
        assertEquals("act-2", a.modelId.value)
        assertEquals(
            0.31,
            Regex("\"logloss\":\\s*([0-9.]+)")
                .find(assertNotNull(a.metricsJson))
                ?.groupValues
                ?.get(1)
                ?.toDouble(),
        )
    }

    @Test
    fun `the mltrain user cannot promote, update or delete through the registry's connection`() {
        registry.insert(row())
        ds.connection.use { c ->
            c.createStatement().use { s ->
                assertDenied { s.executeUpdate("UPDATE ml_model SET status = 'ACTIVE', active_flag = 1 WHERE model_id = 'mc-0001'") }
                assertDenied { s.executeUpdate("DELETE FROM ml_model WHERE model_id = 'mc-0001'") }
            }
        }
        assertEquals("CANDIDATE", db.scalar("SELECT status FROM ml_model WHERE model_id = 'mc-0001'"))
    }
}
