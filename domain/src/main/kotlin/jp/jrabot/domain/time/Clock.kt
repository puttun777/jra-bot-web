package jp.jrabot.domain.time

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** タイムゾーン。保存・内部処理は UTC、開催日の判定と画面表示は Asia/Tokyo（D-006・D-041）。 */
object Zones {
    val UTC: ZoneId = ZoneId.of("UTC")
    val JST: ZoneId = ZoneId.of("Asia/Tokyo")
}

/**
 * 現在時刻の取得口。業務コードは `Instant.now()` などを直接呼ばず、この型を受け取る（テストで固定できる）。
 *
 * - [now] は UTC 基準の瞬間（[Instant]）。DB の `DATETIME(6)`（UTC、マイクロ秒）に合わせ、実装はマイクロ秒に切り捨てて返す。
 * - [zone] は開催日の判定・画面表示に使う地域（既定は Asia/Tokyo）。保存には使わない。
 * - 冪等性・副作用: なし。単調増加は保証しない。
 */
interface Clock {
    fun now(): Instant

    val zone: ZoneId get() = Zones.JST

    /** [zone] での現在の暦日（開催日の判定に使う）。 */
    fun today(): LocalDate = now().atZone(zone).toLocalDate()

    fun nowInZone(): ZonedDateTime = now().atZone(zone)
}

/**
 * システム時計の実装。ドメインで現在時刻を直接取得してよい**唯一の場所**
 * （`ClockUsageGuardTest` が他の箇所での直接取得を検出する）。
 */
object SystemClock : Clock {
    override fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
}
