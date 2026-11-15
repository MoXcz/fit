// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayId
import app.fit.day.DayMark
import app.fit.day.Fatigue
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.day.mark
import app.fit.day.today
import app.fit.exercises.exercise
import app.fit.ui.showsYear
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class HistoryRulesTest {
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private var nextId = 1L

    private fun day(
        date: LocalDate,
        working: Int,
        warmups: Int = 0,
        finished: Boolean = false,
    ): WorkoutDay {
        val sets =
            List(warmups) { SetEntry(SetId(nextId++), warmup = true, targetReps = 5, doneAt = at) } +
                List(working) { SetEntry(SetId(nextId++), targetReps = 5, reps = 5, doneAt = at) }
        val block = Block(BlockId(nextId++), exercise(1, "Squat"), rest = 90.seconds, duration = 45.seconds, sets = sets)
        return WorkoutDay(DayId(nextId++), date, blocks = listOf(block), finishedAt = at.takeIf { finished })
    }

    private fun d(day: Int) = LocalDate(2026, 11, day)

    @Test
    fun `the dot grows with done working sets at 1, 9 and 18`() {
        assertNull(dotSize(0))
        assertEquals(DotSize.Small, dotSize(1))
        assertEquals(DotSize.Small, dotSize(8))
        assertEquals(DotSize.Medium, dotSize(9))
        assertEquals(DotSize.Medium, dotSize(17))
        assertEquals(DotSize.Large, dotSize(18))
        assertEquals(DotSize.Large, dotSize(40))
    }

    @Test
    fun `warm-ups don't size the dot, and a day with only warm-ups gets none`() {
        assertEquals(CalendarMark(DotSize.Small, finished = false), day(d(9), working = 8, warmups = 3).calendarMark())
        assertNull(day(d(9), working = 0, warmups = 3).calendarMark())
    }

    @Test
    fun `a finished day is marked as finished, and a finished day with nothing done gets no mark`() {
        assertEquals(CalendarMark(DotSize.Medium, finished = true), day(d(9), working = 9, finished = true).calendarMark())
        assertNull(day(d(9), working = 0, finished = true).calendarMark())
        assertNull(null.calendarMark())
    }

    @Test
    fun `the month grid is Monday first, in whole weeks, with days outside the month left empty`() {
        // November 2026 starts on a Sunday and ends on a Monday: six weeks.
        val november = MonthGrid.weeks(d(17))
        assertEquals(6, november.size)
        assertTrue(november.all { it.size == 7 })
        assertEquals(List(6) { null } + d(1), november.first())
        assertEquals(listOf(d(30)) + List(6) { null }, november.last())
        // February 2027 starts on a Monday and has 28 days: four weeks, none empty.
        val february = MonthGrid.weeks(LocalDate(2027, 2, 14))
        assertEquals(4, february.size)
        assertEquals(LocalDate(2027, 2, 1), february.first().first())
        assertEquals(LocalDate(2027, 2, 28), february.last().last())
        assertEquals(LocalDate(2027, 2, 28), MonthGrid.lastDay(LocalDate(2027, 2, 10)))
    }

    @Test
    fun `calendar cells outline today, mute the future, fill the selected day and mark trained days`() {
        val cells = MonthGrid.cells(d(1), today = d(10), selected = d(9), days = listOf(day(d(9), working = 20, finished = true)))
        val all = cells.flatten().filterNotNull().associateBy { it.date }
        assertEquals(30, all.size)
        assertTrue(all.getValue(d(10)).today)
        assertFalse(all.getValue(d(10)).future)
        assertTrue(all.getValue(d(11)).future)
        assertTrue(all.getValue(d(9)).selected)
        assertEquals(CalendarMark(DotSize.Large, finished = true), all.getValue(d(9)).mark)
        assertNull(all.getValue(d(8)).mark)
    }

    @Test
    fun `the streak counts back from today when today has a workout`() {
        val stats = HistoryStats.of(setOf(d(7), d(8), d(9)), today = d(9))
        assertEquals(3, stats.streak)
    }

    @Test
    fun `the streak counts back from yesterday while today has nothing yet`() {
        assertEquals(2, HistoryStats.of(setOf(d(7), d(8)), today = d(9)).streak)
        // Two days without a workout: no streak, and nothing else is said about it.
        assertEquals(0, HistoryStats.of(setOf(d(6), d(7)), today = d(9)).streak)
        assertEquals(0, HistoryStats.of(emptySet(), today = d(9)).streak)
    }

    @Test
    fun `a day without a workout ends the streak`() {
        assertEquals(1, HistoryStats.of(setOf(d(5), d(6), d(8)), today = d(8)).streak)
    }

    @Test
    fun `workouts count distinct days, and the last 7 days include today and the six before it`() {
        val stats = HistoryStats.of(setOf(d(1), d(2), d(3), d(4), d(9), d(10)), today = d(10))
        assertEquals(6, stats.workouts)
        // 4 to 10: the 4th, 9th and 10th.
        assertEquals(3, stats.lastSevenDays)
    }

    @Test
    fun `a day with sets done after today isn't counted, so the three numbers agree`() {
        val stats = HistoryStats.of(setOf(d(8), d(9), d(12)), today = d(9))
        assertEquals(HistoryStats(workouts = 2, lastSevenDays = 2, streak = 2), stats)
    }

    @Test
    fun `the calendar marks the same days as the week strip, finished the same way`() {
        val days = listOf(day(d(7), working = 1), day(d(8), working = 0, warmups = 2), day(d(9), working = 3, finished = true), null)
        days.forEach { day ->
            val mark = day.calendarMark()
            assertEquals(day.mark() == DayMark.None, mark == null)
            assertEquals(day.mark() == DayMark.Finished, mark?.finished == true)
        }
    }

    @Test
    fun `a date shows its year only when it isn't this year`() {
        assertFalse(showsYear(d(2), today = d(9)))
        assertTrue(showsYear(LocalDate(2025, 12, 30), today = LocalDate(2026, 1, 2)))
    }

    @Test
    fun `the streak follows calendar days across a DST change`() {
        // Berlin moves to summer time on Sunday 28 March 2027, a 23-hour day.
        val zone = TimeZone.of("Europe/Berlin")
        // 00:30 on Monday 29 March 2027 in Berlin is 22:30 UTC on Sunday: the UTC day would be a day behind.
        val clock =
            object : Clock {
                override fun now() = Instant.parse("2027-03-28T22:30:00Z")
            }
        val today = clock.today(zone)
        assertEquals(LocalDate(2027, 3, 29), today)
        // Saturday, Sunday (23 hours long) and Monday.
        val trained = setOf(LocalDate(2027, 3, 27), LocalDate(2027, 3, 28), LocalDate(2027, 3, 29))
        assertEquals(3, HistoryStats.of(trained, today).streak)
        assertEquals(3, HistoryStats.of(trained, today).lastSevenDays)
    }

    @Test
    fun `fatigue is 1 to 5, or none`() {
        assertTrue(Fatigue.isValid(null))
        assertTrue((1..5).all { Fatigue.isValid(it) })
        assertFalse(Fatigue.isValid(0))
        assertFalse(Fatigue.isValid(6))
        assertFalse(Fatigue.isValid(-1))
    }
}
