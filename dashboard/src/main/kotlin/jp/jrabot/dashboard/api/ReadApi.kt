package jp.jrabot.dashboard.api

import jakarta.servlet.http.HttpServletRequest
import jp.jrabot.domain.bot.BotRunRepository
import jp.jrabot.domain.calendar.RaceDayReader
import jp.jrabot.domain.ops.InspectableModeProvider
import jp.jrabot.domain.performance.ComboSummary
import jp.jrabot.domain.performance.PerformanceReadService
import jp.jrabot.domain.performance.PerformanceReport
import jp.jrabot.domain.race.RaceScheduleReader
import jp.jrabot.domain.signal.SignalReader
import jp.jrabot.domain.time.Clock
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

// 読取り API（R15）。**GET だけ**。テナントは認可済みの本人（[ApiAuthFilter]）の行から決まり、クエリで指定させない。DTO を返し、Entity・内部の例外文は出さない。
// 入力の誤りは 400（固定の文言。値を含めない）。応答は `Cache-Control: no-store`。

data class StatusDto(
    val serverTime: Instant,
    val today: LocalDate,
    val mode: String,
    val days: List<DayDto>,
    val lastRun: RunDto?,
    val nextRaceAt: Instant?,
    /** 予定の確認が古い（最終確認から [STALE_AFTER_MINUTES] 分超）。画面は『未更新』を表示する。 */
    val scheduleStale: Boolean,
    val latestConfirmedAt: Instant?,
)

data class DayDto(
    val venueCode: String,
    val status: String,
    val lastConfirmedAt: Instant?,
)

data class RunDto(
    val runId: String,
    val date: LocalDate,
    val trigger: String,
    val state: String,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val exitReason: String?,
)

data class RaceDto(
    val raceId: String,
    val venueCode: String,
    val raceNo: Int,
    val name: String?,
    val postAt: Instant?,
    val status: String,
)

data class SignalDto(
    val signalId: Long?,
    val raceId: String,
    val market: String,
    val umaban: Int,
    val observedAt: Instant,
    val odds: Double,
    val prob: Double,
    val ev: Double,
    val stake: Double,
    val probSource: String?,
    val state: String,
)

data class PageDto<T>(
    val items: List<T>,
    val page: Int,
    val size: Int,
    val total: Int,
)

data class ComboSummaryDto(
    val market: String,
    val count: Int,
    val hits: Int,
    val totalStake: Double,
    val actualPayout: Double,
    val hitRate: Double?,
    val actualRoi: Double?,
)

data class PerformanceDto(
    val from: LocalDate,
    val to: LocalDate,
    val winCount: Int,
    val winHits: Int,
    val winTotalStake: Double,
    val winPaperPayout: Double,
    val winActualPayout: Double?,
    val winHitRate: Double?,
    val winActualRoi: Double?,
    val combos: List<ComboSummaryDto>,
    val winUnsettled: Int,
    val comboUnsettled: Int,
    val racesWithoutPostTime: Int,
    val unreadableNotices: Int,
)

@RestController
@RequestMapping("/api/v1")
class ReadApi(
    private val clock: Clock,
    private val modes: InspectableModeProvider,
    private val days: RaceDayReader,
    private val schedules: RaceScheduleReader,
    private val signals: SignalReader,
    private val runs: BotRunRepository,
    private val performance: PerformanceReadService,
) {
    @GetMapping("/status")
    fun status(request: HttpServletRequest): ResponseEntity<StatusDto> {
        val p = ApiAuthFilter.principalOf(request)
        val today = clock.today()
        val now = clock.now()
        val dayRows = days.listByDate(p.tenantId, today)
        val confirmed = dayRows.mapNotNull { it.lastConfirmedAt }.maxOrNull()
        val races = schedules.listByDate(p.tenantId, today)
        val next = races.mapNotNull { it.scheduledPostAt }.filter { it.isAfter(now) }.minOrNull()
        val lastRun = runs.listByDate(p.tenantId, today).firstOrNull()
        val stale = dayRows.isNotEmpty() && (confirmed == null || Duration.between(confirmed, now) > Duration.ofMinutes(STALE_AFTER_MINUTES))
        return ok(
            StatusDto(
                now,
                today,
                modes.current().name,
                dayRows.map { DayDto(it.venue.code, it.status.name, it.lastConfirmedAt) },
                lastRun?.let(::run),
                next,
                stale,
                confirmed,
            ),
        )
    }

    @GetMapping("/runs")
    fun runs(
        request: HttpServletRequest,
        @RequestParam(required = false) date: String?,
    ): ResponseEntity<List<RunDto>> {
        val p = ApiAuthFilter.principalOf(request)
        return ok(runs.listByDate(p.tenantId, parseDate(date) ?: clock.today()).map(::run))
    }

    @GetMapping("/races")
    fun races(
        request: HttpServletRequest,
        @RequestParam(required = false) date: String?,
    ): ResponseEntity<List<RaceDto>> {
        val p = ApiAuthFilter.principalOf(request)
        val rows = schedules.listByDate(p.tenantId, parseDate(date) ?: clock.today())
        return ok(rows.map { RaceDto(it.raceId.value, it.raceId.venue.code, it.raceId.raceNo, it.raceName, it.scheduledPostAt, it.status.name) })
    }

    @GetMapping("/signals")
    fun signals(
        request: HttpServletRequest,
        @RequestParam(required = false) date: String?,
        @RequestParam(required = false) page: Int?,
        @RequestParam(required = false) size: Int?,
    ): ResponseEntity<PageDto<SignalDto>> {
        val p = ApiAuthFilter.principalOf(request)
        val pageNo = page ?: 0
        val pageSize = size ?: DEFAULT_PAGE_SIZE
        if (pageNo < 0 || pageSize !in 1..MAX_PAGE_SIZE) throw bad()
        val all =
            schedules
                .listByDate(p.tenantId, parseDate(date) ?: clock.today())
                .flatMap { signals.listByRace(p.tenantId, it.raceId) }
                .sortedWith(compareByDescending<jp.jrabot.domain.signal.Signal> { it.observedAt }.thenBy { it.raceId.value }.thenBy { it.umaban.value })
        val slice = all.drop(pageNo * pageSize).take(pageSize)
        return ok(
            PageDto(
                slice.map {
                    SignalDto(
                        it.id?.value,
                        it.raceId.value,
                        it.market.code,
                        it.umaban.value,
                        it.observedAt,
                        it.odds.value,
                        it.prob.value,
                        it.ev,
                        it.stake,
                        it.probSource,
                        it.state.name,
                    )
                },
                pageNo,
                pageSize,
                all.size,
            ),
        )
    }

    @GetMapping("/performance")
    fun performance(
        request: HttpServletRequest,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
    ): ResponseEntity<PerformanceDto> {
        val p = ApiAuthFilter.principalOf(request)
        val end = parseDate(to) ?: clock.today()
        val start = parseDate(from) ?: end.minusDays(DEFAULT_RANGE_DAYS)
        if (end.isBefore(start) || java.time.temporal.ChronoUnit.DAYS
                .between(start, end) > PerformanceReadService.MAX_DAYS
        ) {
            throw bad()
        }
        return ok(dto(performance.report(p.tenantId, start, end)))
    }

    private fun dto(r: PerformanceReport) =
        PerformanceDto(
            r.from,
            r.to,
            r.win.count,
            r.win.hits,
            r.win.totalStake,
            r.win.paperPayout,
            r.win.actualPayout,
            r.win.hitRate,
            r.win.actualRoi,
            r.combos.map(::combo),
            r.winUnsettled,
            r.comboUnsettled,
            r.racesWithoutPostTime,
            r.unreadableNotices,
        )

    private fun combo(c: ComboSummary) = ComboSummaryDto(c.market.code, c.count, c.hits, c.totalStake, c.actualPayout, c.hitRate, c.actualRoi)

    private fun run(r: jp.jrabot.domain.bot.BotRun) = RunDto(r.runId.value, r.runDate, r.trigger.name, r.state.name, r.startedAt, r.finishedAt, r.exitReason)

    private fun parseDate(s: String?): LocalDate? =
        if (s.isNullOrBlank()) {
            null
        } else {
            try {
                LocalDate.parse(s)
            } catch (_: DateTimeParseException) {
                throw bad()
            }
        }

    private fun bad() = ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_request")

    private fun <T : Any> ok(body: T): ResponseEntity<T> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    companion object {
        const val STALE_AFTER_MINUTES = 120L
        const val DEFAULT_PAGE_SIZE = 50
        const val MAX_PAGE_SIZE = 200
        const val DEFAULT_RANGE_DAYS = 30L
    }
}
