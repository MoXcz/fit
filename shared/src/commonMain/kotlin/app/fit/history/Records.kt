// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.core.MeasureUnit
import app.fit.day.PastSet
import app.fit.day.WorkoutDay
import app.fit.day.toPastSet
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks

// What an exercise's best is measured in: its load, else reps, else distance, else time.
enum class Metric { Load, Reps, Distance, Time }

// The metrics an exercise records, best first.
fun Tracks.metrics(): List<Metric> =
    listOfNotNull(
        Metric.Load.takeIf { weight },
        Metric.Reps.takeIf { reps },
        Metric.Distance.takeIf { distance },
        Metric.Time.takeIf { time },
    )

// The first metric [sets] have a value in, so an exercise that records weight but was done without
// a load is measured in reps rather than in nothing.
fun Tracks.metricFor(sets: List<PastSet>): Metric? {
    val all = metrics()
    return all.firstOrNull { metric -> sets.any { it.measure(metric, this) != null } } ?: all.firstOrNull()
}

// One set's value in a metric, with the unit it can only be compared within (none for reps and time).
data class Measure(
    val value: Double,
    val unit: MeasureUnit?,
)

// Reps of a loaded set only compare with other loads, so when an exercise that records weight falls
// back to reps, only its unloaded sets count. A bodyweight set's load is what was added, so one with
// nothing added is unloaded and a gain in body weight is never a record.
fun PastSet.measure(
    metric: Metric,
    tracks: Tracks,
): Measure? {
    val load = weight?.takeIf { it > 0 }
    return when (metric) {
        Metric.Load -> load?.let { Measure(it, weightUnit) }
        Metric.Reps -> reps?.takeIf { it > 0 && !(tracks.weight && load != null) }?.let { Measure(it.toDouble(), null) }
        Metric.Distance -> distance?.takeIf { it > 0 }?.let { Measure(it, distanceUnit) }
        Metric.Time -> duration?.inWholeSeconds?.takeIf { it > 0 }?.let { Measure(it.toDouble(), null) }
    }
}

// "22.5 kg (was 20)": a best beaten on this day, against every earlier day in the same unit.
data class Record(
    val exerciseId: ExerciseId,
    val name: String,
    val metric: Metric,
    val value: Double,
    val was: Double,
    val unit: MeasureUnit?,
)

object Records {
    // Done working sets only; the first time in a unit has nothing to beat, so it isn't a record
    // (else every new exercise, and every switch to lb, would be one).
    fun of(
        day: WorkoutDay,
        earlier: List<PastSet>,
    ): List<Record> =
        day.blocks.groupBy { it.exercise.id }.flatMap { (id, blocks) ->
            val tracks = blocks.first().tracks
            val name =
                blocks
                    .first()
                    .exercise.fields.name
            val today =
                blocks.flatMap { block ->
                    block.workingSets.filter { it.done }.mapIndexed { i, set -> set.toPastSet(day.date, id, i) }
                }
            val metric = tracks.metricFor(today) ?: return@flatMap emptyList()
            val before = earlier.filter { it.exerciseId == id && it.date < day.date }.mapNotNull { it.measure(metric, tracks) }
            today
                .mapNotNull { it.measure(metric, tracks) }
                .groupBy { it.unit }
                .mapNotNull { (unit, measures) ->
                    val best = measures.maxOf { it.value }
                    val was = before.filter { it.unit == unit }.maxOfOrNull { it.value } ?: return@mapNotNull null
                    Record(id, name, metric, best, was, unit).takeIf { best > was }
                }
        }
}
