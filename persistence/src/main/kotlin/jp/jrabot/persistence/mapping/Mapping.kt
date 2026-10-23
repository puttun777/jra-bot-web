package jp.jrabot.persistence.mapping

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 時刻の変換（DB は UTC の `DATETIME(6)`、ドメインは [Instant]）。変換はここだけで行う。
 * 書込み時はマイクロ秒に切り捨てる（DB に任せると 7 桁目以降が**丸め**られるため、アプリ側で決める）。
 */
internal fun Instant.toDbTime(): LocalDateTime = LocalDateTime.ofInstant(truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC)

internal fun LocalDateTime.toInstantUtc(): Instant = toInstant(ZoneOffset.UTC)

internal fun LocalDateTime?.toInstantUtcOrNull(): Instant? = this?.toInstantUtc()

internal fun Instant?.toDbTimeOrNull(): LocalDateTime? = this?.toDbTime()

/** DB の ENUM 文字列をドメインの列挙へ。名前が一致しない値は DDL とドメインの食い違いなので例外にする（黙って丸めない）。 */
internal inline fun <reified E : Enum<E>> enumFromDb(
    value: String,
    column: String,
): E =
    enumValues<E>().firstOrNull { it.name == value }
        ?: throw IllegalStateException("$column の値 '$value' がドメインの ${E::class.simpleName} に無い（DDL とドメインの食い違い）")
