package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.api.MlModelApi
import jp.jrabot.domain.ml.registry.ModelCatalogEntry
import jp.jrabot.domain.ml.registry.ModelCatalogReader
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
    private fun entry(
        id: String,
        status: String = "CANDIDATE",
        metrics: String? = null,
    ) = ModelCatalogEntry(
        modelId = id,
        kind = "calibration",
        status = status,
        schemaVersion = 1,
        featureSchemaHash = "sha256:" + "a".repeat(64),
        trainedFrom = null,
        trainedTo = null,
        nTrain = null,
        createdAt = Instant.parse("2026-10-01T00:00:00Z"),
        activatedAt = null,
        activatedBy = null,
        previousModelId = null,
        metricsJson = metrics,
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
                    return listOf(entry("m1", "ACTIVE"))
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
    fun `上限ちょうどまでの指標の JSON は読める`() {
        val prefix = "{\"brier\":0.2,\"pad\":\""
        val atLimit = prefix + "x".repeat(MlModelApi.MAX_METRICS_CHARS - prefix.length - 2) + "\"}"
        assertEquals(MlModelApi.MAX_METRICS_CHARS, atLimit.length)
        val dto = MlModelApi.dto(entry("m4", metrics = atLimit))
        assertTrue(dto.metricsReadable)
        assertEquals(mapOf("brier" to 0.2), dto.metrics)
    }

    @Test
    fun `想定外の値（ハッシュの形式・種別・状態）の行でも一覧に出す`() {
        val odd = entry("weird", status = "ARCHIVED").copy(kind = "unknown", featureSchemaHash = "md5:x")
        val dto = MlModelApi.dto(odd)
        assertEquals("ARCHIVED", dto.status)
        assertEquals("unknown", dto.kind)
    }

    @Test
    fun `指標は最上位の有限の数だけを辞書順に 品質ゲートの目的と理由は別に出す`() {
        // キーを辞書順にしない（MySQL は JSON のキーを長さ順に並べ替えるので、並べ替えが本当に要る）
        val json = """{"n_valid":120,"logloss":0.5,"gate_purpose":"calibration","brier":0.2,"gate_reasons":["ok","n>=100"],"nested":{"x":1},"note":"t"}"""
        val dto = MlModelApi.dto(entry("m2", metrics = json))
        assertTrue(dto.metricsReadable)
        assertEquals(listOf("brier", "logloss", "n_valid"), dto.metrics.keys.toList())
        assertEquals(120.0, dto.metrics["n_valid"])
        assertEquals("calibration", dto.gatePurpose)
        assertEquals(listOf("ok", "n>=100"), dto.gateReasons)
    }

    @Test
    fun `壊れた・大きすぎる指標の JSON でも一覧の行は出し 指標は空で読めないと示す`() {
        // 形式は正しいが大きすぎる（上限の効果だけを見る。上限未満の同じ形は読める: 下の試験）
        val huge = "{\"brier\":0.2,\"pad\":\"" + "x".repeat(MlModelApi.MAX_METRICS_CHARS) + "\"}"
        for (bad in listOf("{", "[1,2]", "\"x\"", "", huge)) {
            val dto = MlModelApi.dto(entry("m3", metrics = bad))
            assertFalse(dto.metricsReadable, bad.take(20))
            assertTrue(dto.metrics.isEmpty() && dto.gateReasons.isEmpty() && dto.gatePurpose == null, bad.take(20))
        }
    }
}
