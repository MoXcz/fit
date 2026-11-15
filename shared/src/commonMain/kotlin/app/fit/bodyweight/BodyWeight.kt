// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight

import app.fit.core.WeightUnit
import kotlinx.datetime.LocalDate

// One reading per day.
data class BodyWeight(
    val date: LocalDate,
    val weight: Double,
    val unit: WeightUnit,
)

// What a body weight field holds.
sealed interface BodyWeightEntry {
    // Blank, or zero or less: the day's reading goes.
    data object Delete : BodyWeightEntry

    data class Reading(
        val weight: Double,
    ) : BodyWeightEntry

    // Not a number, or above the cap: nothing is written, the old reading stays.
    data object Invalid : BodyWeightEntry
}

object BodyWeights {
    const val MAX = 1000.0

    // A day's body weight is the latest reading on or before it.
    fun on(
        readings: List<BodyWeight>,
        date: LocalDate,
    ): BodyWeight? = readings.filter { it.date <= date }.maxByOrNull { it.date }

    // Empty, zero or less is no reading: saving it deletes the day's.
    fun isReading(weight: Double?): Boolean = weight != null && weight.isFinite() && weight > 0 && weight <= MAX

    // Text that doesn't read as a weight never deletes anything; only blank or ≤ 0 does.
    fun entry(text: String): BodyWeightEntry {
        val clean = text.trim()
        if (clean.isEmpty()) return BodyWeightEntry.Delete
        val weight = clean.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() } ?: return BodyWeightEntry.Invalid
        return when {
            weight <= 0 -> BodyWeightEntry.Delete
            weight > MAX -> BodyWeightEntry.Invalid
            else -> BodyWeightEntry.Reading(weight)
        }
    }
}
