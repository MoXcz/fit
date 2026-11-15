// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import app.fit.core.WeightUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DefaultsStepTest {
    private val base = ExerciseDefaults(sets = 3, reps = 10, rest = 90.seconds, duration = 45.seconds, weight = 20.0)

    private fun ExerciseDefaults.up(field: DefaultField) = step(field, up = true, WeightUnit.Kg)

    private fun ExerciseDefaults.down(field: DefaultField) = step(field, up = false, WeightUnit.Kg)

    @Test
    fun `a maximum starts above the minimum and goes back to none at it`() {
        val ranged = base.up(DefaultField.RepsMax)
        assertEquals(11, ranged.repsMax)
        assertEquals(null, ranged.down(DefaultField.RepsMax).repsMax)
        assertFalse(base.canStep(DefaultField.RepsMax, up = false, WeightUnit.Kg))
    }

    @Test
    fun `raising the minimum past the maximum drops the range`() {
        val ranged = base.copy(repsMax = 11)
        assertEquals(
            ExerciseDefaults(sets = 3, reps = 11, repsMax = null, rest = 90.seconds, duration = 45.seconds, weight = 20.0),
            ranged.up(DefaultField.Reps),
        )
        val rest = base.copy(restMax = 105.seconds).up(DefaultField.Rest)
        assertEquals(105.seconds, rest.rest)
        assertEquals(null, rest.restMax)
    }

    @Test
    fun `time steps grow with the time`() {
        assertEquals(50.seconds, base.up(DefaultField.Duration).duration)
        assertEquals(55.seconds, base.copy(duration = 1.minutes).down(DefaultField.Duration).duration)
        assertEquals(105.seconds, base.up(DefaultField.Rest).rest)
        assertEquals(31.minutes, base.copy(duration = 30.minutes).up(DefaultField.Duration).duration)
        assertEquals(9.minutes + 45.seconds, base.copy(duration = 10.minutes).down(DefaultField.Duration).duration)
    }

    @Test
    fun `values stay in their limits`() {
        assertEquals(1, base.copy(sets = 1).down(DefaultField.Sets).sets)
        assertEquals(0.seconds, base.copy(rest = 0.seconds).down(DefaultField.Rest).rest)
        assertEquals(5.seconds, base.copy(duration = 5.seconds).down(DefaultField.Duration).duration)
        // A 3 s time from a content file can't go down, and going down never raises it.
        assertFalse(base.copy(duration = 3.seconds).canStep(DefaultField.Duration, up = false, WeightUnit.Kg))
        assertEquals(0.0, base.copy(weight = 0.0).down(DefaultField.Weight).weight)
        assertFalse(base.copy(weight = 0.0).canStep(DefaultField.Weight, up = false, WeightUnit.Kg))
        assertTrue(base.canStep(DefaultField.Weight, up = false, WeightUnit.Kg))
    }

    @Test
    fun `weight steps by 2_5 kg or 5 lb`() {
        assertEquals(22.5, base.up(DefaultField.Weight).weight)
        assertEquals(25.0, base.step(DefaultField.Weight, up = true, WeightUnit.Lb).weight)
    }
}
