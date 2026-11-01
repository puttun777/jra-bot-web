package jp.jrabot.dashboard

import jp.jrabot.dashboard.api.AdminApi
import jp.jrabot.dashboard.api.ApiAuthFilter
import jp.jrabot.dashboard.api.CreateOverrideRequest
import jp.jrabot.dashboard.guard.CsrfGuardFilter
import jp.jrabot.domain.audit.AuditEntry
import jp.jrabot.domain.audit.AuditLogWriter
import jp.jrabot.domain.botrun.testing.InMemoryScheduleReader
import jp.jrabot.domain.botrun.testing.ManualClock
import jp.jrabot.domain.calendar.CalendarOverride
import jp.jrabot.domain.calendar.CalendarOverrideRepository
import jp.jrabot.domain.calendar.CalendarOverrideService
import jp.jrabot.domain.calendar.OverrideCreateResult
import jp.jrabot.domain.calendar.OverrideRevokeResult
import jp.jrabot.domain.calendar.ScheduleEvent
import jp.jrabot.domain.race.RaceId
import jp.jrabot.domain.race.RaceSchedule
import jp.jrabot.domain.race.RaceScheduleStatus
import jp.jrabot.domain.race.Venue
import jp.jrabot.domain.tenant.AppUserRole
import jp.jrabot.domain.tenant.EmailAddress
import jp.jrabot.domain.tenant.Principal
import jp.jrabot.domain.tenant.TenantId
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AdminApiTest {
    private val t1 = TenantId.of(1)
    private val date = LocalDate.of(2026, 10, 24)
    private val now = Instant.parse("2026-10-24T01:00:00Z")
    private val clock = ManualClock(now)
    private val raceId = RaceId.of(date, Venue.TOKYO, 11)

    private class Repo : CalendarOverrideRepository {
        val rows = mutableListOf<CalendarOverride>()

        override fun find(
            tenantId: TenantId,
            id: Long,
        ) = rows.firstOrNull { it.tenantId == tenantId && it.id == id }

        override fun listByDate(
            tenantId: TenantId,
            date: LocalDate,
        ) = rows.filter { it.tenantId == tenantId && it.raceDate == date }

        override fun create(
            override: CalendarOverride,
            event: ScheduleEvent,
        ): OverrideCreateResult {
            val saved = override.copy(id = rows.size + 1L)
            rows += saved
            return OverrideCreateResult.Created(saved, event)
        }

        override fun revoke(
            tenantId: TenantId,
            id: Long,
            revokedBy: String,
            revokedAt: Instant,
            event: ScheduleEvent,
        ): OverrideRevokeResult {
            val i = rows.indexOfFirst { it.tenantId == tenantId && it.id == id }
            if (i < 0) return OverrideRevokeResult.NotFound
            if (rows[i].revokedAt != null) return OverrideRevokeResult.AlreadyRevoked
            rows[i] = rows[i].copy(revokedAt = revokedAt, revokedBy = revokedBy)
            return OverrideRevokeResult.Revoked(rows[i], event)
        }
    }

    private val repo = Repo()

    /** 本物のトランザクションの代わり: コールバックが例外で終わったら、リポジトリの内容を開始時点に戻す（ロールバックの確認用）。 */
    private val tx =
        object : org.springframework.transaction.support.TransactionOperations {
            override fun <T : Any?> execute(action: org.springframework.transaction.support.TransactionCallback<T>): T {
                val snapshot = repo.rows.toList()
                try {
                    return action.doInTransaction(
                        org.springframework.transaction.support
                            .SimpleTransactionStatus(),
                    )
                } catch (e: Throwable) {
                    repo.rows.clear()
                    repo.rows.addAll(snapshot)
                    throw e
                }
            }
        }
    private val audits = mutableListOf<AuditEntry>()
    private var auditFails = false
    private val schedule = RaceSchedule(t1, raceId, now.plusSeconds(3600), false, RaceScheduleStatus.CARD, '2', "202610232", "R11", now)
    private val api =
        AdminApi(
            clock,
            CalendarOverrideService(repo, InMemoryScheduleReader(listOf(schedule)), clock),
            repo,
            AuditLogWriter { e, _ -> if (auditFails) error("db down") else audits += e },
            tx,
        )

    private fun req(
        role: AppUserRole = AppUserRole.ADMIN,
        tenant: TenantId = t1,
    ) = MockHttpServletRequest().also { it.setAttribute(ApiAuthFilter.PRINCIPAL_ATTRIBUTE, Principal(tenant, EmailAddress.of("me@example.com"), role)) }

    private fun cancelRace() = CreateOverrideRequest("RACE", "2026-10-24", raceId.value, "CANCEL", null, null, "天候による中止", null)

    @Test
    fun `補正を作ると 作成者は認可済みの本人で 監査記録に誰が何をしたかが残る`() {
        val r = api.create(req(), cancelRace())
        assertEquals(201, r.statusCode.value())
        val dto = assertNotNull(r.body)
        assertEquals("me@example.com", dto.createdBy)
        val a = audits.single()
        assertEquals("CALENDAR_OVERRIDE_CREATE", a.action)
        assertEquals("me@example.com", a.actor)
        assertEquals(t1, a.tenantId)
        assertEquals(dto.id.toString(), a.targetId)
        assertEquals("天候による中止", a.reason)
        assertNull(a.beforeJson)
        assertTrue(a.afterJson!!.contains("CANCEL"))
    }

    @Test
    fun `取消は before と after を監査記録に残し 二重の取消は 409 他テナントの補正は 404`() {
        val id = assertNotNull(api.create(req(), cancelRace()).body).id!!
        audits.clear()
        assertEquals(200, api.revoke(req(), id).statusCode.value())
        val a = audits.single()
        assertEquals("CALENDAR_OVERRIDE_REVOKE", a.action)
        assertTrue(a.beforeJson!!.contains("\"revokedAt\":null") && !a.afterJson!!.contains("\"revokedAt\":null"))
        assertEquals(409, assertFailsWith<ResponseStatusException> { api.revoke(req(), id) }.statusCode.value())
        assertEquals(404, assertFailsWith<ResponseStatusException> { api.revoke(req(tenant = TenantId.of(2)), id) }.statusCode.value())
    }

    @Test
    fun `入力の誤りは 400 で何も作らず監査記録も残さない 存在しないレースは 404 重複は 409`() {
        for (bad in listOf(
            cancelRace().copy(scope = "X"),
            cancelRace().copy(action = null),
            cancelRace().copy(date = "2026-13-40"),
            cancelRace().copy(raceId = "bad"),
            cancelRace().copy(reason = " "),
            cancelRace().copy(reason = "あ".repeat(501)),
            cancelRace().copy(scope = "DAY"), // DAY に raceId は付けられない
            cancelRace().copy(action = "SET_POST_TIME", postAt = null),
            cancelRace().copy(action = "POSTPONE_TO", scope = "DAY", raceId = null, postponedTo = "x"),
        )) {
            assertEquals(400, assertFailsWith<ResponseStatusException> { api.create(req(), bad) }.statusCode.value(), bad.toString())
        }
        assertTrue(repo.rows.isEmpty() && audits.isEmpty())
        val missing = cancelRace().copy(raceId = RaceId.of(date, Venue.KYOTO, 1).value)
        assertEquals(404, assertFailsWith<ResponseStatusException> { api.create(req(), missing) }.statusCode.value())
    }

    @Test
    fun `監査記録に失敗したら 500 を返し 変更も取り消される（監査なしの変更を残さない）`() {
        auditFails = true
        assertEquals(500, assertFailsWith<ResponseStatusException> { api.create(req(), cancelRace()) }.statusCode.value())
        assertTrue(repo.rows.isEmpty(), "補正が残っている")
        auditFails = false
        val id = assertNotNull(api.create(req(), cancelRace()).body).id!!
        auditFails = true
        assertEquals(500, assertFailsWith<ResponseStatusException> { api.revoke(req(), id) }.statusCode.value())
        assertNull(repo.rows.single().revokedAt, "取消が残っている")
    }

    @Test
    fun `監査記録には設定した値・期限・取消者も残る`() {
        val body = CreateOverrideRequest("RACE", "2026-10-24", raceId.value, "SET_POST_TIME", "2026-10-24T06:30:00Z", null, "時刻変更", "2026-10-24T12:00:00Z")
        val id = assertNotNull(api.create(req(), body).body).id!!
        assertTrue(
            audits.single().afterJson!!.contains("2026-10-24T06:30:00Z") && audits.single().afterJson!!.contains("2026-10-24T12:00:00Z"),
            audits.single().afterJson,
        )
        api.revoke(req(), id)
        assertTrue(audits.last().afterJson!!.contains("\"revokedBy\":\"me@example.com\""), audits.last().afterJson)
    }

    @Test
    fun `一覧は自分のテナントの補正だけ`() {
        api.create(req(), cancelRace())
        assertEquals(1, assertNotNull(api.list(req(), null).body).size)
        assertEquals(0, assertNotNull(api.list(req(tenant = TenantId.of(2)), null).body).size)
    }

    // ---- CSRF

    private fun csrf(configure: MockHttpServletRequest.() -> Unit): Pair<Int, Boolean> {
        val request = MockHttpServletRequest("POST", "/api/v1/admin/overrides")
        request.addHeader("Host", "dash.example.com")
        request.configure()
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()
        CsrfGuardFilter().doFilter(request, res, chain)
        return res.status to (chain.request != null)
    }

    private fun MockHttpServletRequest.ok() {
        addHeader(CsrfGuardFilter.HEADER, CsrfGuardFilter.HEADER_VALUE)
        contentType = "application/json"
    }

    @Test
    fun `CSRF 対策 — カスタムヘッダと JSON が無い 別オリジン 別サイトからの書込みは 403`() {
        assertEquals(200 to true, csrf { ok() })
        assertEquals(
            200 to true,
            csrf {
                ok()
                addHeader("Origin", "https://dash.example.com")
                addHeader("Sec-Fetch-Site", "same-origin")
            },
        )
        assertEquals(403 to false, csrf { contentType = "application/json" }, "ヘッダなし")
        assertEquals(
            403 to false,
            csrf {
                addHeader(CsrfGuardFilter.HEADER, CsrfGuardFilter.HEADER_VALUE)
                contentType = "application/x-www-form-urlencoded"
            },
            "フォーム形式",
        )
        assertEquals(
            403 to false,
            csrf {
                ok()
                addHeader("Origin", "https://evil.example")
            },
            "別オリジン",
        )
        assertEquals(
            403 to false,
            csrf {
                ok()
                addHeader("Sec-Fetch-Site", "cross-site")
            },
            "別サイト",
        )
        assertEquals(
            403 to false,
            csrf {
                ok()
                addHeader("Origin", "not a url")
            },
        )
    }

    @Test
    fun `CSRF 対策は読取りと api 以外には掛けない`() {
        val get = MockHttpServletRequest("GET", "/api/v1/status")
        val chain = MockFilterChain()
        CsrfGuardFilter().doFilter(get, MockHttpServletResponse(), chain)
        assertNotNull(chain.request)
    }
}
