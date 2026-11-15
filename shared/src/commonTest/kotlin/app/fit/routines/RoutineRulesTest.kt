// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines

import app.fit.core.WeightUnit
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.exercise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RoutineRulesTest {
    @Test
    fun `a free routine name adds the first number no routine has`() {
        assertEquals("Monday — upper", freeRoutineName("Monday — upper", listOf("Legs")))
        assertEquals("Monday — upper 2", freeRoutineName("Monday — upper", listOf("monday — upper")))
        assertEquals("Legs 3", freeRoutineName("Legs", listOf("Legs", "Legs 2", "Arms")))
    }

    private val target = ItemTarget(sets = 3, reps = 10, rest = 90.seconds, duration = 40.seconds)

    private fun item(
        id: Long,
        section: String,
        sets: Int = 3,
    ) = RoutineItem(RoutineItemId(id), exercise(id, "E$id"), target.copy(section = section, sets = sets))

    @Test
    fun `the estimate counts set time and rest for every set`() {
        val items = listOf(item(1, MAIN_SECTION, sets = 3), item(2, MAIN_SECTION, sets = 1))
        assertEquals((130 * 4).seconds, estimate(items))
    }

    @Test
    fun `sections keep the order they first appear in`() {
        val items = listOf(item(1, "Warm-up"), item(2, MAIN_SECTION), item(3, "Warm-up"), item(4, "Stretch"))
        assertEquals(listOf("Warm-up", MAIN_SECTION, "Stretch"), sectionsOf(items).map { it.name })
        assertEquals(listOf(1L, 3L, 2L, 4L), inSectionOrder(items) { it.target.section }.map { it.id.value })
    }

    @Test
    fun `an item starts from the exercise's defaults`() {
        val squat =
            exercise(1, "Squat").let {
                it.copy(
                    fields =
                        it.fields.copy(
                            defaults = ExerciseDefaults(sets = 4, reps = 6, repsMax = 8, rest = 3.minutes, duration = 50.seconds),
                        ),
                )
            }
        assertEquals(
            ItemTarget(section = "Warm-up", sets = 4, reps = 6, repsMax = 8, rest = 3.minutes, duration = 50.seconds),
            squat.defaultTarget("Warm-up"),
        )
        assertEquals(MAIN_SECTION, exercise(2, "Dip").defaultTarget().section)
        assertEquals(ExerciseFields(name = "x").defaults.sets, exercise(3, "x").defaultTarget().sets)
    }

    @Test
    fun `warm-up sets go from 0 to 3`() {
        assertEquals(1, target.step(ItemField.WarmupSets, up = true, WeightUnit.Kg).warmupSets)
        assertFalse(target.canStep(ItemField.WarmupSets, up = false, WeightUnit.Kg))
        assertFalse(target.copy(warmupSets = 3).canStep(ItemField.WarmupSets, up = true, WeightUnit.Kg))
    }

    @Test
    fun `the progression step goes from off through plate jumps in the unit`() {
        val on = target.step(ItemField.ProgressStep, up = true, WeightUnit.Kg)
        assertEquals(1.25, on.progressStep)
        assertEquals(2.5, on.step(ItemField.ProgressStep, up = true, WeightUnit.Kg).progressStep)
        assertEquals(null, on.step(ItemField.ProgressStep, up = false, WeightUnit.Kg).progressStep)
        assertEquals(2.5, target.step(ItemField.ProgressStep, up = true, WeightUnit.Lb).progressStep)
        assertFalse(target.copy(progressStep = 10.0).canStep(ItemField.ProgressStep, up = true, WeightUnit.Kg))
        // A value from a file that isn't a choice moves to the nearest choice above.
        assertEquals(5.0, target.copy(progressStep = 3.0).step(ItemField.ProgressStep, up = true, WeightUnit.Kg).progressStep)
    }

    @Test
    fun `the RPE gate goes from off through 6 to 10 in halves`() {
        val on = target.step(ItemField.ProgressRpe, up = true, WeightUnit.Kg)
        assertEquals(6.0, on.progressRpe)
        assertEquals(6.5, on.step(ItemField.ProgressRpe, up = true, WeightUnit.Kg).progressRpe)
        assertFalse(target.copy(progressRpe = 10.0).canStep(ItemField.ProgressRpe, up = true, WeightUnit.Kg))
    }

    @Test
    fun `sets, reps, rest and time follow the exercise editor's rules`() {
        val ranged = target.step(ItemField.RepsMax, up = true, WeightUnit.Kg)
        assertEquals(11, ranged.repsMax)
        assertEquals(null, ranged.step(ItemField.Reps, up = true, WeightUnit.Kg).repsMax)
        assertEquals(105.seconds, target.step(ItemField.Rest, up = true, WeightUnit.Kg).rest)
    }
}
