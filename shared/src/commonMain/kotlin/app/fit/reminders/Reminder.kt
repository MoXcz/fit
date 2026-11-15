// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.day.localDate
import app.fit.guided.GuidedPlanId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

// A recovery session's daily reminder, at a time on the local clock.
data class Reminder(
    val planId: GuidedPlanId,
    val time: LocalTime,
    val enabled: Boolean,
    // The local day it last came up, shown or left out; that day gets no other.
    val shownOn: LocalDate? = null,
)

// When a reminder is due. Times are local: each day's is worked out from that day's date, so a
// time-zone or daylight-saving change moves the instant, not the time on the clock.
object ReminderRules {
    // 9 in the morning until the user picks a time.
    val DEFAULT_TIME = LocalTime(9, 0)

    // A reminder noticed later than this (the phone was off) is left out for the day, not shown late.
    val LATE: Duration = 1.hours

    fun at(
        day: LocalDate,
        time: LocalTime,
        zone: TimeZone,
    ): Instant = day.atTime(time).toInstant(zone)

    // Today's time has come, not long ago, and today hasn't had it yet.
    fun due(
        reminder: Reminder,
        now: Instant,
        zone: TimeZone,
    ): Boolean {
        val today = now.localDate(zone)
        val at = at(today, reminder.time, zone)
        return reminder.enabled && !hadIt(reminder, today) && now >= at && now - at <= LATE
    }

    // When to wake for it: today's time while it's still ahead, else tomorrow's. Never a time already
    // past: what's due now is handled by the check that asks.
    fun next(
        reminder: Reminder,
        now: Instant,
        zone: TimeZone,
    ): Instant {
        val today = now.localDate(zone)
        val at = at(today, reminder.time, zone)
        return if (!hadIt(reminder, today) && now < at) at else at(today.plus(1, DateTimeUnit.DAY), reminder.time, zone)
    }

    // A reminder switched on, or moved, to a time already past today starts tomorrow: it never comes
    // up the moment it's set. A day that had its reminder keeps that, whatever the new time.
    fun set(
        planId: GuidedPlanId,
        time: LocalTime,
        enabled: Boolean,
        now: Instant,
        zone: TimeZone,
        before: Reminder? = null,
    ): Reminder {
        val today = now.localDate(zone)
        val had = before != null && hadIt(before, today)
        return Reminder(planId, time, enabled, shownOn = today.takeIf { had || at(today, time, zone) <= now })
    }

    // A day after today counts too: the clock was set back since.
    private fun hadIt(
        reminder: Reminder,
        today: LocalDate,
    ): Boolean = reminder.shownOn?.let { it >= today } == true
}
