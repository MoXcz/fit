// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

object SessionClock {
    // A finish this long after the last set means the app was left open, not still training.
    val STALE_AFTER: Duration = 15.minutes

    fun finishAt(
        lastDoneAt: Instant?,
        now: Instant,
    ): Instant = if (lastDoneAt != null && now - lastDoneAt > STALE_AFTER) lastDoneAt else now

    // The start moves on by the pause, so the clock carries on instead of counting it.
    fun reopenedStart(
        startedAt: Instant?,
        finishedAt: Instant?,
        now: Instant,
    ): Instant? {
        if (startedAt == null || finishedAt == null) return startedAt
        return startedAt + (now - finishedAt).coerceAtLeast(Duration.ZERO)
    }

    // An end earlier than [notBefore] is the next day: a workout that ran past midnight.
    fun atLocal(
        date: LocalDate,
        time: LocalTime,
        zone: TimeZone,
        notBefore: Instant? = null,
    ): Instant {
        val instant = date.atTime(time).toInstant(zone)
        return if (notBefore != null && instant < notBefore) date.plus(1, DateTimeUnit.DAY).atTime(time).toInstant(zone) else instant
    }
}

// Days are local calendar dates in the device's zone, never 24-hour steps of milliseconds.
fun Clock.today(zone: TimeZone): LocalDate = now().toLocalDateTime(zone).date

fun Instant.localTime(zone: TimeZone): LocalTime = toLocalDateTime(zone).time

fun Instant.localDate(zone: TimeZone): LocalDate = toLocalDateTime(zone).date

// Monday to Sunday around [this].
fun LocalDate.week(): List<LocalDate> {
    val monday = minus(dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
    return (0 until 7).map { monday.plus(it, DateTimeUnit.DAY) }
}
