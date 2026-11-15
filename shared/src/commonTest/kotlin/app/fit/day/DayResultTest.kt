// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.WeightUnit
import app.fit.day.ui.volumeText
import app.fit.exercises.exercise
import app.fit.ui.formatThousands
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DayResultTest {
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private var nextId = 1L

    private fun set(
        weight: Double? = 60.0,
        reps: Int? = 5,
        unit: WeightUnit = WeightUnit.Kg,
        done: Boolean = true,
        warmup: Boolean = false,
    ) = SetEntry(
        SetId(nextId++),
        warmup = warmup,
        targetReps = 5,
        weight = weight,
        weightUnit = unit,
        reps = reps,
        doneAt = at.takeIf { done },
    )

    private fun block(
        id: Long,
        vararg sets: SetEntry,
    ) = Block(BlockId(id), exercise(id, "Ex $id"), rest = 90.seconds, duration = 45.seconds, sets = sets.toList())

    @Test
    fun `volume adds load times reps of done working sets, per unit`() {
        val day =
            WorkoutDay(
                DayId(1),
                LocalDate(2026, 11, 9),
                blocks =
                    listOf(
                        block(1, set(warmup = true), set(), set(62.5, 5), set(done = false)),
                        block(2, set(100.0, 3, WeightUnit.Lb), set(weight = null, reps = 10)),
                    ),
            )
        assertEquals(mapOf(WeightUnit.Kg to 612.5, WeightUnit.Lb to 300.0), day.volume)
        assertEquals("613 kg + 300 lb", volumeText(day.volume))
        assertNull(volumeText(emptyMap()))
    }

    @Test
    fun `thousands get a separator`() {
        assertEquals("4,310", formatThousands(4310.0))
        assertEquals("999", formatThousands(999.0))
        assertEquals("1,234,568", formatThousands(1234567.6))
    }

    @Test
    fun `a set in a unit unlike the exercise's other sets is marked`() {
        val kg = set()
        val lb = set(unit = WeightUnit.Lb)
        val mixed = block(1, kg, set(), lb)
        assertEquals(WeightUnit.Kg, mixed.mainUnit)
        assertTrue(mixed.inOtherUnit(lb))
        assertFalse(mixed.inOtherUnit(kg))
        // All in lb: lb is the exercise's unit here, so nothing stands out.
        val allLb = block(2, set(unit = WeightUnit.Lb), set(unit = WeightUnit.Lb))
        assertEquals(WeightUnit.Lb, allLb.mainUnit)
        assertFalse(allLb.inOtherUnit(allLb.sets[0]))
        // A tie goes to the exercise's unit; a set without a load is never marked.
        assertEquals(WeightUnit.Kg, block(3, set(), set(unit = WeightUnit.Lb)).mainUnit)
        assertFalse(mixed.inOtherUnit(set(weight = null, unit = WeightUnit.Lb)))
    }

    @Test
    fun `the current exercise is the one last open while it has sets left, else the first with sets left`() {
        val done = block(1, set())
        val open = block(2, set(done = false))
        val later = block(3, set(done = false))
        val day = WorkoutDay(DayId(1), LocalDate(2026, 11, 9), blocks = listOf(done, open, later))
        assertEquals(later.id, day.current(later.id)?.id)
        assertEquals(open.id, day.current(done.id)?.id)
        assertEquals(open.id, day.current(null)?.id)
        assertFalse(day.allDone)
        val finished = day.copy(blocks = listOf(done))
        assertNull(finished.current(done.id))
        assertTrue(finished.allDone)
    }

    @Test
    fun `warm-ups never block, done follows the working sets, and a skipped warm-up isn't next`() {
        val skipped = set(done = false, warmup = true)
        val first = set(done = false)
        val fresh = block(1, skipped, first)
        // Nothing done yet: the warm-up comes first.
        assertEquals(skipped.id, fresh.nextSet?.id)
        val started = block(2, skipped, set(), first)
        assertEquals(first.id, started.nextSet?.id)
        assertTrue(started.hasSetsLeft)
        val worked = block(3, skipped, set(), set())
        assertNull(worked.nextSet)
        assertFalse(worked.hasSetsLeft)
        val day = WorkoutDay(DayId(1), LocalDate(2026, 11, 9), blocks = listOf(worked))
        assertTrue(day.allDone)
        assertNull(day.current(null))
    }
}
