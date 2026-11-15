// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.ExerciseId
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

// A done working set from a workout, for progression, records and an exercise's history.
data class PastSet(
    val date: LocalDate,
    val exerciseId: ExerciseId,
    val position: Int,
    val reps: Int?,
    val weight: Double?,
    val weightUnit: WeightUnit,
    val duration: Duration?,
    val rpe: Double?,
    val distance: Double? = null,
    val distanceUnit: DistanceUnit = DistanceUnit.Km,
    val doneAt: Instant? = null,
    // Done on a day whose loads were cut for a deload week.
    val deload: Boolean = false,
    // [weight] is then what was added to the body weight, and all that records and charts compare.
    val bodyweight: Boolean = false,
)

// A day's set as history; every history read goes through here.
fun SetEntry.toPastSet(
    date: LocalDate,
    exerciseId: ExerciseId,
    position: Int,
    deload: Boolean = false,
): PastSet =
    PastSet(
        date = date,
        exerciseId = exerciseId,
        position = position,
        reps = reps,
        weight = weight,
        weightUnit = weightUnit,
        duration = duration,
        rpe = rpe,
        distance = distance,
        distanceUnit = distanceUnit,
        doneAt = doneAt,
        deload = deload,
        bodyweight = bodyweight,
    )

// An exercise's most recent workout: its done working sets in the exercise's own unit.
data class LastPerformance(
    val date: LocalDate,
    val sets: List<PastSet>,
) {
    // Only when there's no other day to build on (see [Progression.lastPerformance]).
    val deload: Boolean get() = sets.any { it.deload }

    // The heaviest set (then the most reps), so a lighter back-off set never sets the next load.
    val top: PastSet
        get() =
            sets.maxWith(
                compareBy<PastSet>({ it.weight ?: -1.0 }, { it.reps ?: -1 }).thenByDescending { it.position },
            )
}

// What an exercise was last lifted with; for a bodyweight set, what was added.
data class LastLoad(
    val weight: Double,
    val unit: WeightUnit,
    val bodyweight: Boolean = false,
)

object Progression {
    // Per exercise, the load of its most recent done working set (latest day, then last in it), with
    // deload days skipped as in [lastPerformance]; none for an exercise never done or last done
    // without a load.
    fun lastLoads(history: List<PastSet>): Map<ExerciseId, LastLoad> =
        history
            .groupBy { it.exerciseId }
            .mapNotNull { (id, sets) ->
                val pool = sets.filter { !it.deload }.ifEmpty { sets }
                val last = pool.maxWith(compareBy<PastSet>({ it.date }, { it.position }))
                last.weight?.takeIf { it > 0 }?.let { id to LastLoad(it, last.weightUnit, last.bodyweight) }
            }.toMap()

    // Units are never converted, so sets in another unit can't set or judge a load in [unit]. Deload
    // days are skipped: the next week builds on the last full one. Only an exercise done on deload
    // days alone falls back to the latest of them.
    fun lastPerformance(
        history: List<PastSet>,
        exerciseId: ExerciseId,
        unit: WeightUnit,
    ): LastPerformance? {
        val sets = history.filter { it.exerciseId == exerciseId && (it.weight == null || it.weightUnit == unit) }
        val pool = sets.filter { !it.deload }.ifEmpty { sets }
        val date = pool.maxOfOrNull { it.date } ?: return null
        return LastPerformance(date, pool.filter { it.date == date }.sortedBy { it.position })
    }

    // A set without an RPE doesn't block the step. A deload day's easy reps never earn one.
    fun startWeight(
        last: LastPerformance?,
        defaultWeight: Double,
        repsTarget: Int,
        step: Double?,
        rpeGate: Double?,
    ): Double {
        val base = last?.top?.weight ?: return defaultWeight
        if (step == null || last.deload) return base
        val hit = last.sets.all { it.reps != null && it.reps >= repsTarget }
        val easyEnough = rpeGate == null || last.sets.all { (it.rpe ?: 0.0) <= rpeGate }
        return if (hit && easyEnough) roundToHundredths(base + step) else base
    }
}

internal fun roundToHundredths(value: Double): Double = kotlin.math.floor(value * 100 + 0.5) / 100
