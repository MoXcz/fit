// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.runtime.Composable
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

@Composable
actual fun formatTime(time: LocalTime): String =
    java.time.LocalTime
        .of(time.hour, time.minute)
        .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

@Composable
actual fun uses24HourClock(): Boolean = true

@Composable
actual fun formatDate(
    date: LocalDate,
    withYear: Boolean,
): String =
    java.time.LocalDate
        .of(date.year, date.month.ordinal + 1, date.day)
        .format(DateTimeFormatter.ofPattern(if (withYear) "EEE, d MMM yyyy" else "EEE, d MMM"))

@Composable
actual fun weekdayLetter(date: LocalDate): String =
    java.time.DayOfWeek
        .of(date.dayOfWeek.ordinal + 1)
        .getDisplayName(TextStyle.NARROW, Locale.getDefault())

@Composable
actual fun weekdayName(day: DayOfWeek): String =
    java.time.DayOfWeek
        .of(day.ordinal + 1)
        .getDisplayName(TextStyle.FULL, Locale.getDefault())

@Composable
actual fun formatMonth(date: LocalDate): String =
    java.time.LocalDate
        .of(date.year, date.month.ordinal + 1, date.day)
        .format(DateTimeFormatter.ofPattern("LLLL yyyy"))
