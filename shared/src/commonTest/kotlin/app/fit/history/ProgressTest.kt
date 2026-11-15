// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.day.PastSet
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ProgressTest {
    private val id = ExerciseId(1)
    private val squat = ExerciseFields(name = "Squat", tracks = Tracks.WeightAndReps)

    private fun d(day: Int) = LocalDate(2026, 11, day)

    private fun set(
        date: LocalDate,
        weight: Double? = null,
        reps: Int? = null,
        unit: WeightUnit = WeightUnit.Kg,
        position: Int = 0,
        duration: Duration? = null,
        distance: Double? = null,
        distanceUnit: DistanceUnit = DistanceUnit.Km,
        doneAt: Instant? = null,
    ) = PastSet(date, id, position, reps, weight, unit, duration, null, distance, distanceUnit, doneAt)

    @Test
    fun `within a day, sets are in the order they were done, across two blocks of the exercise`() {
        val at = Instant.parse("2026-11-09T10:00:00Z")
        val sets =
            listOf(
                set(d(9), 60.0, 5, position = 0, doneAt = at + 5.minutes),
                set(d(9), 50.0, 5, position = 0, doneAt = at),
                set(d(9), 55.0, 5, position = 1, doneAt = at + 2.minutes),
            )
        assertEquals(
            listOf(50.0, 55.0, 60.0),
            Progress
                .byDay(sets)
                .single()
                .sets
                .map { it.weight },
        )
    }

    @Test
    fun `an exercise that records weight but was done unloaded is charted in reps`() {
        val chart = Progress.chart(listOf(set(d(2), 0.0, 8), set(d(9), null, 10)), squat)!!
        assertEquals(Metric.Reps to listOf(8.0, 10.0), chart.metric to chart.points.map { it.value })
    }

    @Test
    fun `history groups sets by day, newest day first, sets in order`() {
        val sets = listOf(set(d(2), 60.0, 5, position = 1), set(d(9), 62.5, 5), set(d(2), 57.5, 5, position = 0))
        val days = Progress.byDay(sets)
        assertEquals(listOf(d(9), d(2)), days.map { it.date })
        assertEquals(listOf(57.5, 60.0), days[1].sets.map { it.weight })
    }

    @Test
    fun `the chart is each day's best load in the exercise's unit, oldest first`() {
        val sets =
            listOf(
                set(d(9), 62.5, 5),
                set(d(2), 60.0, 5),
                set(d(2), 55.0, 8),
                // Another unit is never mixed in.
                set(d(5), 200.0, 5, WeightUnit.Lb),
            )
        val chart = Progress.chart(sets, squat)!!
        assertEquals(Metric.Load, chart.metric)
        assertEquals(WeightUnit.Kg, chart.unit)
        assertEquals(listOf(ChartPoint(d(2), 60.0), ChartPoint(d(9), 62.5)), chart.points)
    }

    @Test
    fun `an exercise done only in another unit is charted in that unit`() {
        val chart = Progress.chart(listOf(set(d(2), 135.0, 5, WeightUnit.Lb), set(d(9), 145.0, 5, WeightUnit.Lb)), squat)!!
        assertEquals(WeightUnit.Lb, chart.unit)
        assertEquals(listOf(135.0, 145.0), chart.points.map { it.value })
    }

    @Test
    fun `exercises without weight chart reps, else distance, else time`() {
        val reps =
            Progress.chart(
                listOf(set(d(2), reps = 8), set(d(2), reps = 10), set(d(9), reps = 12)),
                ExerciseFields(name = "X", tracks = Tracks(reps = true)),
            )!!
        assertEquals(Metric.Reps to listOf(10.0, 12.0), reps.metric to reps.points.map { it.value })
        val walk =
            Progress.chart(
                listOf(set(d(2), distance = 5.0, duration = 3000.seconds), set(d(9), distance = 3.0, distanceUnit = DistanceUnit.Mi)),
                ExerciseFields(name = "X", tracks = Tracks(distance = true, time = true)),
            )!!
        assertEquals(Triple(Metric.Distance, DistanceUnit.Km, listOf(5.0)), Triple(walk.metric, walk.unit, walk.points.map { it.value }))
        val plank =
            Progress.chart(
                listOf(set(d(2), duration = 40.seconds), set(d(9), duration = 60.seconds)),
                ExerciseFields(name = "X", tracks = Tracks(time = true)),
            )!!
        assertEquals(Metric.Time to listOf(40.0, 60.0), plank.metric to plank.points.map { it.value })
    }

    @Test
    fun `nothing logged in the metric means no chart`() {
        assertNull(Progress.chart(emptyList(), squat))
        assertNull(Progress.chart(listOf(set(d(2), 0.0, null)), squat))
    }

    @Test
    fun `the chart keeps the last 30 sessions, however far apart`() {
        // Every third day for 40 sessions: the first 10 drop off, the gaps stay.
        val sets = (0 until 40).map { set(d(1).plus(it * 3, DateTimeUnit.DAY), 20.0 + it, 5) }
        val chart = Progress.chart(sets, squat)!!
        assertEquals(Progress.CHART_SESSIONS, chart.points.size)
        assertEquals(d(1).plus(30, DateTimeUnit.DAY), chart.points.first().date)
        assertEquals(d(1).plus(117, DateTimeUnit.DAY), chart.points.last().date)
    }
}
