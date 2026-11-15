// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Composable
actual fun formatTime(time: LocalTime): String {
    val pattern = if (DateFormat.is24HourFormat(LocalContext.current)) "HH:mm" else "h:mm a"
    val best = DateFormat.getBestDateTimePattern(Locale.getDefault(), pattern)
    return java.time.LocalTime
        .of(time.hour, time.minute)
        .format(DateTimeFormatter.ofPattern(best))
}

@Composable
actual fun uses24HourClock(): Boolean = DateFormat.is24HourFormat(LocalContext.current)

@Composable
actual fun formatDate(
    date: LocalDate,
    withYear: Boolean,
): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), if (withYear) "EEEdMMMyyyy" else "EEEdMMM")
    return java.time.LocalDate
        .of(date.year, date.month.ordinal + 1, date.day)
        .format(DateTimeFormatter.ofPattern(pattern))
}

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
actual fun formatMonth(date: LocalDate): String {
    val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "LLLLyyyy")
    return java.time.LocalDate
        .of(date.year, date.month.ordinal + 1, date.day)
        .format(DateTimeFormatter.ofPattern(pattern))
}
