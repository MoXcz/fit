// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.routines.RoutineId
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline

@JvmInline
value class ProgramId(
    val value: Long,
)

// A weekly schedule: which routine each weekday plans. One program is active at most.
data class Program(
    val id: ProgramId,
    // The content file's id for built-in programs; null for ones made in the app.
    val contentId: String? = null,
    val name: String,
    val notes: String = "",
    val active: Boolean = false,
    // Any day of week 1; weeks run Monday to Sunday. Without it there are no weeks and no deloads.
    val startsOn: LocalDate? = null,
    // Every Nth week is a deload week; null is never.
    val deloadEvery: Int? = null,
    val deloadPercent: Int = Deload.DEFAULT_PERCENT,
    // Weekdays without a routine are rest days.
    val days: Map<DayOfWeek, RoutineId> = emptyMap(),
    val edited: Boolean = false,
)

// A weekday of a program, for "now Rest in …" after a routine it used is deleted.
data class ProgramDayRef(
    val programId: ProgramId,
    val programName: String,
    val weekday: DayOfWeek,
)
