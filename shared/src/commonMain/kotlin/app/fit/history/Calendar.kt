// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.day.DayMark
import app.fit.day.WorkoutDay
import app.fit.day.mark
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus

// A day's dot by its done working sets (the old app's thresholds). Volume can't size it: it's per
// unit, and kg and lb don't compare.
enum class DotSize { Small, Medium, Large }

fun dotSize(doneWorkingSets: Int): DotSize? =
    when {
        doneWorkingSets <= 0 -> null
        doneWorkingSets <= 8 -> DotSize.Small
        doneWorkingSets <= 17 -> DotSize.Medium
        else -> DotSize.Large
    }

data class CalendarMark(
    val size: DotSize,
    val finished: Boolean,
)

// The week strip's mark ([mark]) with a size: one rule for which days are marked and finished.
fun WorkoutDay?.calendarMark(): CalendarMark? {
    val mark = mark()
    if (this == null || mark == DayMark.None) return null
    return dotSize(doneSets)?.let { CalendarMark(it, finished = mark == DayMark.Finished) }
}

data class CalendarCell(
    val date: LocalDate,
    val mark: CalendarMark?,
    val today: Boolean,
    val future: Boolean,
    val selected: Boolean,
    // A recovery session ran that day: its own mark, apart from the workout's.
    val recovery: Boolean = false,
)

// A month (its first day) and its weeks, ready to show.
data class CalendarMonth(
    val month: LocalDate,
    val weeks: List<List<CalendarCell?>>,
)

object MonthGrid {
    fun monthOf(date: LocalDate): LocalDate = LocalDate(date.year, date.month, 1)

    fun lastDay(month: LocalDate): LocalDate = monthOf(month).plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)

    // Monday-first whole weeks covering the month; days outside it are null.
    fun weeks(month: LocalDate): List<List<LocalDate?>> {
        val first = monthOf(month)
        val last = lastDay(first)
        val start = first.minus(first.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)
        return generateSequence(start) { it.plus(1, DateTimeUnit.WEEK) }
            .takeWhile { it <= last }
            .map { monday -> (0 until 7).map { monday.plus(it, DateTimeUnit.DAY).takeIf { day -> day in first..last } } }
            .toList()
    }

    fun cells(
        month: LocalDate,
        today: LocalDate,
        selected: LocalDate?,
        days: List<WorkoutDay>,
        recovery: Set<LocalDate> = emptySet(),
    ): List<List<CalendarCell?>> {
        val byDate = days.associateBy { it.date }
        return weeks(month).map { week ->
            week.map { date ->
                date?.let { CalendarCell(it, byDate[it].calendarMark(), it == today, it > today, it == selected, it in recovery) }
            }
        }
    }
}
