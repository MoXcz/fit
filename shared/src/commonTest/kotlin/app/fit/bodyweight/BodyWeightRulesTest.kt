// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight

import app.fit.core.WeightUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BodyWeightRulesTest {
    private val nov2 = BodyWeight(LocalDate(2026, 11, 2), 82.0, WeightUnit.Kg)
    private val nov9 = BodyWeight(LocalDate(2026, 11, 9), 81.5, WeightUnit.Kg)

    @Test
    fun `the body weight for a day is the latest reading on or before it`() {
        val readings = listOf(nov9, nov2)
        assertNull(BodyWeights.on(readings, LocalDate(2026, 11, 1)))
        assertEquals(nov2, BodyWeights.on(readings, LocalDate(2026, 11, 2)))
        assertEquals(nov2, BodyWeights.on(readings, LocalDate(2026, 11, 8)))
        assertEquals(nov9, BodyWeights.on(readings, LocalDate(2026, 11, 20)))
    }

    @Test
    fun `an empty, zero or negative body weight is no reading`() {
        assertFalse(BodyWeights.isReading(null))
        assertFalse(BodyWeights.isReading(0.0))
        assertFalse(BodyWeights.isReading(-3.0))
        assertFalse(BodyWeights.isReading(Double.NaN))
        assertTrue(BodyWeights.isReading(81.5))
    }

    @Test
    fun `saving an empty or zero body weight deletes the day's reading`() =
        runTest {
            val repo = FakeBodyWeightRepository(listOf(nov2, nov9))
            assertEquals(nov9, repo.set(nov9.date, null, WeightUnit.Kg))
            assertEquals(listOf(nov2), repo.readings.first())
            assertEquals(nov2, repo.set(nov2.date, 0.0, WeightUnit.Kg))
            assertEquals(emptyList(), repo.readings.first())
            // Nothing to delete is fine.
            assertNull(repo.set(nov2.date, -1.0, WeightUnit.Kg))
        }

    @Test
    fun `only blank text or a weight of zero or less deletes a reading, and text that isn't a weight never does`() {
        assertEquals(BodyWeightEntry.Delete, BodyWeights.entry(""))
        assertEquals(BodyWeightEntry.Delete, BodyWeights.entry("  "))
        assertEquals(BodyWeightEntry.Delete, BodyWeights.entry("0"))
        assertEquals(BodyWeightEntry.Delete, BodyWeights.entry("-2"))
        assertEquals(BodyWeightEntry.Reading(81.5), BodyWeights.entry("81,5"))
        assertEquals(BodyWeightEntry.Invalid, BodyWeights.entry("8l.5"))
        assertEquals(BodyWeightEntry.Invalid, BodyWeights.entry("1001"))
        assertEquals(BodyWeightEntry.Invalid, BodyWeights.entry("NaN"))
    }
}
