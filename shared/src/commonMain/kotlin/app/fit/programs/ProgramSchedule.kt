// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.routines.RoutineId
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus

// Where a date falls in a program: its week (1 is the start's week) and whether it's a deload week.
data class ProgramWeek(
    val week: Int,
    val deload: Boolean,
)

// What the active program says about one date.
data class PlannedDay(
    val program: Program,
    // Null before week 1, or when the program has no start.
    val week: ProgramWeek?,
    // Null on a rest day.
    val routineId: RoutineId?,
) {
    val deload: Boolean get() = week?.deload == true

    // What the plan's loads are cut by this week, if anything.
    val deloadPercent: Int? get() = program.deloadPercent.takeIf { deload }
}

object ProgramSchedule {
    val DELOAD_EVERY: IntRange = 2..16

    fun monday(date: LocalDate): LocalDate = date.minus(date.dayOfWeek.isoDayNumber - DayOfWeek.MONDAY.isoDayNumber, DateTimeUnit.DAY)

    // Whole weeks between the two Mondays, plus one, counted in calendar days (LocalDate), never in
    // milliseconds, so a DST change can't make a week 167 hours long and drop one.
    fun week(
        startsOn: LocalDate,
        date: LocalDate,
    ): Int? = (monday(startsOn).daysUntil(monday(date)) / 7 + 1).takeIf { it >= 1 }

    fun isDeload(
        week: Int,
        every: Int?,
    ): Boolean = every != null && every in DELOAD_EVERY && week % every == 0

    fun weekOf(
        program: Program,
        date: LocalDate,
    ): ProgramWeek? {
        val start = program.startsOn ?: return null
        val week = week(start, date) ?: return null
        return ProgramWeek(week, isDeload(week, program.deloadEvery))
    }

    fun planFor(
        program: Program,
        date: LocalDate,
    ): PlannedDay = PlannedDay(program, weekOf(program, date), program.days[date.dayOfWeek])

    // What a routine applied on [date] is cut by: the active program's deload percent in its deload weeks.
    fun deloadPercent(
        active: Program?,
        date: LocalDate,
    ): Int? = active?.let { planFor(it, date).deloadPercent }

    // Never 1: every week a deload is no program.
    fun steppedDeloadEvery(
        every: Int?,
        up: Boolean,
    ): Int? =
        when {
            every == null -> if (up) DELOAD_EVERY.first else null
            up -> (every + 1).coerceAtMost(DELOAD_EVERY.last)
            every <= DELOAD_EVERY.first -> null
            else -> every - 1
        }
}
