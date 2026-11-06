package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.api.MlModelApi
import jp.jrabot.domain.ml.ModelId
import jp.jrabot.domain.ml.model.ModelKind
import jp.jrabot.domain.ml.registry.ModelCatalogEntry
import jp.jrabot.domain.ml.registry.ModelCatalogReader
import jp.jrabot.domain.ml.registry.ModelRecord
import jp.jrabot.domain.ml.registry.ModelStatus
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.Principal
import jp.jrabot.domain.tenant.TenantId
import org.springframework.mock.web.MockHttpServletRequest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** ML モデルの一覧（R22。D-268）: 表示だけ。テナントは本人の行から、指標は最上位の有限の数だけ、壊れた指標でも一覧は出す。 */
class MlModelApiTest {
    private fun record(
        tenant: Long,
        id: String,
        status: ModelStatus = ModelStatus.CANDIDATE,
    ) = ModelRecord(
        tenantId = TenantId.of(tenant),
        modelId = ModelId.of(id),
        kind = ModelKind.CALIBRATION,
        schemaVersion = 1,
        featureSchemaHash = "sha256:" + "a".repeat(64),
        artifactUri = "gs://synthetic/$id",
        sha256 = "b".repeat(64),
        signatureSha256 = "c".repeat(64),
        status = status,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
        activatedAt = if (status == ModelStatus.ACTIVE) Instant.parse("2026-10-02T00:00:00Z") else null,
    )

    private fun req(tenant: Long) =
        MockHttpServletRequest().also {
            it.setAttribute(ApiAuthFilter.PRINCIPAL_ATTRIBUTE, Principal(TenantId.of(tenant), EmailAddress.of("me@example.com"), AppUserRole.ADMIN))
        }

    @Test
    fun `一覧は本人のテナントで読み 件数の上限を渡し no-store`() {
        val seen = mutableListOf<Pair<TenantId, Int>>()
        val catalog =
            object : ModelCatalogReader {
                override fun recent(
                    tenantId: TenantId,
                    limit: Int,
                ): List<ModelCatalogEntry> {
                    seen += tenantId to limit
                    return listOf(ModelCatalogEntry(record(tenantId.value, "m1", ModelStatus.ACTIVE), null))
                }
            }
        val res = MlModelApi(catalog).list(req(2))
        assertEquals(listOf(TenantId.of(2) to MlModelApi.LIMIT), seen)
        assertEquals("no-store", res.headers.cacheControl)
        val row = assertNotNull(res.body).single()
        assertEquals("m1", row.modelId)
        assertEquals("ACTIVE", row.status)
        assertEquals("calibration", row.kind)
        assertFalse(row.metricsReadable, "指標が無ければ読めないと表示する")
    }

    @Test
    fun `指標は最上位の有限の数だけを辞書順に 品質ゲートの目的と理由は別に出す`() {
        val json = """{"brier":0.2,"gate_purpose":"calibration","gate_reasons":["ok","n>=100"],"logloss":0.5,"n_valid":120,"nested":{"x":1},"note":"t"}"""
        val dto = MlModelApi.dto(ModelCatalogEntry(record(1, "m2"), json))
        assertTrue(dto.metricsReadable)
        assertEquals(listOf("brier", "logloss", "n_valid"), dto.metrics.keys.toList())
        assertEquals(120.0, dto.metrics["n_valid"])
        assertEquals("calibration", dto.gatePurpose)
        assertEquals(listOf("ok", "n>=100"), dto.gateReasons)
    }

    @Test
    fun `壊れた指標の JSON でも一覧の行は出し 指標は空で読めないと示す`() {
        for (bad in listOf("{", "[1,2]", "\"x\"", "")) {
            val dto = MlModelApi.dto(ModelCatalogEntry(record(1, "m3"), bad))
            assertFalse(dto.metricsReadable, bad)
            assertTrue(dto.metrics.isEmpty() && dto.gateReasons.isEmpty() && dto.gatePurpose == null, bad)
        }
    }
}
