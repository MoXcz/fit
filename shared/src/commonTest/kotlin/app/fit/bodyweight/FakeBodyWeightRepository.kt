// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight

import app.fit.core.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate

// In memory, with the same rules as the Room one.
class FakeBodyWeightRepository(
    initial: List<BodyWeight> = emptyList(),
) : BodyWeightRepository {
    val all = MutableStateFlow(initial.associateBy { it.date })

    override val readings: Flow<List<BodyWeight>> = all.map { map -> map.values.sortedByDescending { it.date } }

    override suspend fun set(
        date: LocalDate,
        weight: Double?,
        unit: WeightUnit,
    ): BodyWeight? {
        if (!BodyWeights.isReading(weight)) return delete(date)
        val before = all.value[date]
        all.update { it + (date to BodyWeight(date, requireNotNull(weight), unit)) }
        return before
    }

    override suspend fun delete(date: LocalDate): BodyWeight? {
        val before = all.value[date] ?: return null
        all.update { it - date }
        return before
    }

    override suspend fun restore(reading: BodyWeight) = all.update { it + (reading.date to reading) }
}
