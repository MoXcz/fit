// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.core.MeasureUnit
import app.fit.day.PastSet
import app.fit.exercises.ExerciseFields
import kotlinx.datetime.LocalDate

// An exercise's done working sets on one day, in the order they were done.
data class ExerciseDay(
    val date: LocalDate,
    val sets: List<PastSet>,
)

data class ChartPoint(
    val date: LocalDate,
    val value: Double,
)

// One value per day in one metric and unit, oldest first.
data class ProgressChart(
    val metric: Metric,
    val unit: MeasureUnit?,
    val points: List<ChartPoint>,
)

object Progress {
    // The last 30 sessions (days with sets), however far apart they were.
    const val CHART_SESSIONS = 30

    // Newest day first; within a day, in the order the sets were done.
    fun byDay(sets: List<PastSet>): List<ExerciseDay> =
        sets
            .groupBy { it.date }
            .map { (date, day) -> ExerciseDay(date, day.sortedWith(compareBy({ it.doneAt }, { it.position }))) }
            .sortedByDescending { it.date }

    // The best set of each day in the exercise's metric. Units are never mixed: load and distance
    // are charted in the exercise's own unit, or the one most of its sets used if none are in it.
    fun chart(
        sets: List<PastSet>,
        fields: ExerciseFields,
    ): ProgressChart? {
        val tracks = fields.tracks
        val metric = tracks.metricFor(sets) ?: return null
        val measured = sets.mapNotNull { set -> set.measure(metric, tracks)?.let { set.date to it } }
        if (measured.isEmpty()) return null
        val own: MeasureUnit? =
            when (metric) {
                Metric.Load -> fields.weightUnit
                Metric.Distance -> fields.distanceUnit
                Metric.Reps, Metric.Time -> null
            }
        val unit =
            if (measured.any { it.second.unit == own }) {
                own
            } else {
                measured
                    .groupingBy { it.second.unit }
                    .eachCount()
                    .maxBy { it.value }
                    .key
            }
        val points =
            measured
                .filter { it.second.unit == unit }
                .groupBy({ it.first }, { it.second.value })
                .map { (date, values) -> ChartPoint(date, values.max()) }
                .sortedBy { it.date }
                .takeLast(CHART_SESSIONS)
        return ProgressChart(metric, unit, points)
    }
}
