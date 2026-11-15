// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** A time of day in the user's locale and 12/24-hour preference. */
@Composable
expect fun formatTime(time: LocalTime): String

/** Whether the user reads the clock as 24 hours, for the time picker. */
@Composable
expect fun uses24HourClock(): Boolean

/** A short date with its weekday, e.g. "Mon, 9 Nov" (or "Mon, 9 Nov 2026" [withYear]), in the user's locale. */
@Composable
expect fun formatDate(
    date: LocalDate,
    withYear: Boolean = false,
): String

// A date in another year than [today] says which.
fun showsYear(
    date: LocalDate,
    today: LocalDate,
): Boolean = date.year != today.year

/** A date in a list of past days: with its year when that isn't [today]'s. */
@Composable
fun formatDate(
    date: LocalDate,
    today: LocalDate,
): String = formatDate(date, withYear = showsYear(date, today))

/** The weekday's one-letter name, for the week strip. */
@Composable
expect fun weekdayLetter(date: LocalDate): String

/** The weekday's full name, e.g. "Monday", in the user's locale. */
@Composable
expect fun weekdayName(day: DayOfWeek): String

/** A month and its year, e.g. "November 2026", in the user's locale. */
@Composable
expect fun formatMonth(date: LocalDate): String
