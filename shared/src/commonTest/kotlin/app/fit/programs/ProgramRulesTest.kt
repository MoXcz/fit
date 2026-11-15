// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.core.WeightUnit
import app.fit.routines.RoutineId
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgramWeekTest {
    @Test
    fun `the program week counts whole weeks between the Mondays of the start and the day, plus one`() {
        // A Wednesday start: its whole week is week 1, from Monday on.
        val start = LocalDate(2026, 11, 4)
        assertEquals(1, ProgramSchedule.week(start, LocalDate(2026, 11, 2)))
        assertEquals(1, ProgramSchedule.week(start, LocalDate(2026, 11, 8)))
        assertEquals(2, ProgramSchedule.week(start, LocalDate(2026, 11, 9)))
        assertEquals(6, ProgramSchedule.week(start, LocalDate(2026, 12, 7)))
    }

    @Test
    fun `the program week has no off-by-one across a DST change in either direction`() {
        // Europe moves its clocks on 25 Oct 2026 (back) and 28 Mar 2027 (forward): a week of
        // milliseconds around them is an hour short or long, which the old app's floor got wrong.
        val autumn = LocalDate(2026, 10, 19)
        assertEquals(2, ProgramSchedule.week(autumn, LocalDate(2026, 10, 26)))
        assertEquals(2, ProgramSchedule.week(autumn, LocalDate(2026, 11, 1)))
        assertEquals(3, ProgramSchedule.week(autumn, LocalDate(2026, 11, 2)))
        val spring = LocalDate(2027, 3, 22)
        assertEquals(1, ProgramSchedule.week(spring, LocalDate(2027, 3, 28)))
        assertEquals(2, ProgramSchedule.week(spring, LocalDate(2027, 3, 29)))
    }

    @Test
    fun `the program week carries on across the end of a year, ISO week 53 included`() {
        // 2026 has 53 ISO weeks; 28 Dec 2026 is a Monday and 4 Jan 2027 the next one.
        val start = LocalDate(2026, 12, 28)
        assertEquals(1, ProgramSchedule.week(start, LocalDate(2027, 1, 3)))
        assertEquals(2, ProgramSchedule.week(start, LocalDate(2027, 1, 4)))
        assertEquals(53, ProgramSchedule.week(LocalDate(2026, 1, 1), LocalDate(2026, 12, 31)))
        assertEquals(54, ProgramSchedule.week(LocalDate(2026, 1, 1), LocalDate(2027, 1, 4)))
    }

    @Test
    fun `a day before week 1 has no program week`() {
        assertNull(ProgramSchedule.week(LocalDate(2026, 11, 9), LocalDate(2026, 11, 8)))
        assertNull(ProgramSchedule.weekOf(Program(ProgramId(1), name = "P"), LocalDate(2026, 11, 9)))
    }

    @Test
    fun `a deload week is one whose number is a multiple of deload every, and never without one`() {
        assertEquals(listOf(4, 8, 12), (1..12).filter { ProgramSchedule.isDeload(it, 4) })
        assertFalse(ProgramSchedule.isDeload(6, null))
        assertFalse(ProgramSchedule.isDeload(1, 1))
        val program = Program(ProgramId(1), name = "P", startsOn = LocalDate(2026, 11, 2), deloadEvery = 2)
        assertEquals(ProgramWeek(2, deload = true), ProgramSchedule.weekOf(program, LocalDate(2026, 11, 12)))
        assertEquals(ProgramWeek(3, deload = false), ProgramSchedule.weekOf(program, LocalDate(2026, 11, 16)))
    }

    @Test
    fun `deload every steps from never to 2 and back, up to 16`() {
        assertEquals(2, ProgramSchedule.steppedDeloadEvery(null, up = true))
        assertNull(ProgramSchedule.steppedDeloadEvery(null, up = false))
        assertNull(ProgramSchedule.steppedDeloadEvery(2, up = false))
        assertEquals(5, ProgramSchedule.steppedDeloadEvery(6, up = false))
        assertEquals(16, ProgramSchedule.steppedDeloadEvery(16, up = true))
    }
}

class ProgramPlanTest {
    private val upper = RoutineId(1)
    private val lower = RoutineId(2)
    private val program =
        Program(
            ProgramId(1),
            name = "Upper/lower",
            active = true,
            startsOn = LocalDate(2026, 11, 2),
            deloadEvery = 4,
            deloadPercent = 15,
            days = mapOf(DayOfWeek.MONDAY to upper, DayOfWeek.TUESDAY to lower),
        )

    @Test
    fun `the plan for a date is the routine for its weekday, and a weekday without one is a rest day`() {
        assertEquals(upper, ProgramSchedule.planFor(program, LocalDate(2026, 11, 9)).routineId)
        assertEquals(lower, ProgramSchedule.planFor(program, LocalDate(2026, 11, 10)).routineId)
        assertNull(ProgramSchedule.planFor(program, LocalDate(2026, 11, 11)).routineId)
        // Any date: the plan doesn't need a start.
        assertEquals(upper, ProgramSchedule.planFor(program.copy(startsOn = null), LocalDate(2020, 1, 6)).routineId)
    }

    @Test
    fun `a routine applied in a deload week is cut by the program's percent, and in no other week`() {
        assertEquals(15, ProgramSchedule.deloadPercent(program, LocalDate(2026, 11, 23)))
        assertNull(ProgramSchedule.deloadPercent(program, LocalDate(2026, 11, 16)))
        assertNull(ProgramSchedule.deloadPercent(null, LocalDate(2026, 11, 23)))
    }
}

class DeloadTest {
    @Test
    fun `the deload cut takes the percent off and rounds to the nearest plate step in kg`() {
        assertEquals(90.0, Deload.cut(100.0, 10, WeightUnit.Kg))
        // 56.25 is halfway: halves go up, as warm-ups do.
        assertEquals(57.5, Deload.cut(62.5, 10, WeightUnit.Kg))
        assertEquals(52.5, Deload.cut(62.5, 15, WeightUnit.Kg))
        assertEquals(0.0, Deload.cut(0.0, 10, WeightUnit.Kg))
    }

    @Test
    fun `the deload cut rounds to 5 lb in pounds`() {
        assertEquals(40.0, Deload.cut(45.0, 10, WeightUnit.Lb))
        assertEquals(205.0, Deload.cut(225.0, 10, WeightUnit.Lb))
    }

    @Test
    fun `a light load above one step comes out lighter and never 0, and one step or less stays uncut`() {
        // 9 rounds back to 10: one step down instead.
        assertEquals(7.5, Deload.cut(10.0, 10, WeightUnit.Kg))
        assertEquals(2.5, Deload.cut(5.0, 10, WeightUnit.Kg))
        assertEquals(2.5, Deload.cut(4.0, 10, WeightUnit.Kg))
        assertEquals(5.0, Deload.cut(10.0, 10, WeightUnit.Lb))
        // Nothing lighter than one step can be loaded: it stays as it is.
        assertEquals(2.5, Deload.cut(2.5, 10, WeightUnit.Kg))
        assertEquals(1.0, Deload.cut(1.0, 10, WeightUnit.Kg))
        assertEquals(5.0, Deload.cut(5.0, 10, WeightUnit.Lb))
        assertEquals(0.0, Deload.cut(0.0, 10, WeightUnit.Kg))
        listOf(3.0, 5.0, 7.5, 10.0, 12.5, 20.0).forEach { load ->
            val cut = Deload.cut(load, 5, WeightUnit.Kg)
            assertTrue(cut > 0 && cut < load, "$load → $cut")
        }
    }

    @Test
    fun `the deload percent steps by 5 between 5 and 50`() {
        assertEquals(15, Deload.steppedPercent(10, up = true))
        assertEquals(5, Deload.steppedPercent(5, up = false))
        assertEquals(50, Deload.steppedPercent(50, up = true))
        assertTrue(Deload.DEFAULT_PERCENT in Deload.PERCENTS)
    }
}
