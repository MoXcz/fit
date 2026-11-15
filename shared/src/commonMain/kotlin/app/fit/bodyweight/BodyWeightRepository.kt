// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight

import app.fit.core.WeightUnit
import app.fit.data.db.BodyWeightDao
import app.fit.data.db.BodyWeightEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate

/** Body weight readings, one per day. */
interface BodyWeightRepository {
    /** Every reading, newest first. */
    val readings: Flow<List<BodyWeight>>

    /** Saves the day's reading; empty, zero or less deletes it. Returns the reading it replaced or deleted. */
    suspend fun set(
        date: LocalDate,
        weight: Double?,
        unit: WeightUnit,
    ): BodyWeight?

    /** Deletes the day's reading and returns it, for [restore]. */
    suspend fun delete(date: LocalDate): BodyWeight?

    suspend fun restore(reading: BodyWeight)
}

// The body weight on [date]: the latest reading on or before it.
fun BodyWeightRepository.on(date: LocalDate): Flow<BodyWeight?> = readings.map { BodyWeights.on(it, date) }

class RoomBodyWeightRepository(
    private val dao: BodyWeightDao,
) : BodyWeightRepository {
    override val readings: Flow<List<BodyWeight>> = dao.all().map { rows -> rows.map { it.toReading() } }

    override suspend fun set(
        date: LocalDate,
        weight: Double?,
        unit: WeightUnit,
    ): BodyWeight? {
        if (!BodyWeights.isReading(weight)) return delete(date)
        val before = dao.find(date.toString())?.toReading()
        dao.upsert(BodyWeightEntity(date.toString(), requireNotNull(weight), unit.name))
        return before
    }

    override suspend fun delete(date: LocalDate): BodyWeight? {
        val before = dao.find(date.toString())?.toReading() ?: return null
        dao.delete(date.toString())
        return before
    }

    override suspend fun restore(reading: BodyWeight) =
        dao.upsert(BodyWeightEntity(reading.date.toString(), reading.weight, reading.unit.name))
}

// An unknown unit (a newer version's data) reads as kg instead of crashing.
private fun BodyWeightEntity.toReading() =
    BodyWeight(LocalDate.parse(day), weight, WeightUnit.entries.firstOrNull { it.name == unit } ?: WeightUnit.Kg)
