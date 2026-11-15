// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayId
import app.fit.day.PastSet
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RecordsTest {
    private val at = Instant.parse("2026-11-09T10:00:00Z")
    private val today = LocalDate(2026, 11, 9)
    private val before = LocalDate(2026, 11, 2)
    private var nextId = 1L

    private val squat = exercise(1, "Squat")
    private val pullUp = exercise(2, "Pull-up", tracks = Tracks(reps = true))
    private val plank = exercise(3, "Plank", tracks = Tracks(time = true))
    private val walk = exercise(4, "Walk", tracks = Tracks(distance = true, time = true))

    private fun set(
        weight: Double? = null,
        reps: Int? = null,
        unit: WeightUnit = WeightUnit.Kg,
        duration: Duration? = null,
        distance: Double? = null,
        distanceUnit: DistanceUnit = DistanceUnit.Km,
        warmup: Boolean = false,
        done: Boolean = true,
    ) = SetEntry(
        SetId(nextId++),
        warmup = warmup,
        targetReps = 5,
        weight = weight,
        weightUnit = unit,
        reps = reps,
        duration = duration,
        distance = distance,
        distanceUnit = distanceUnit,
        doneAt = at.takeIf { done },
    )

    private fun block(
        exercise: Exercise,
        vararg sets: SetEntry,
    ) = Block(BlockId(nextId++), exercise, rest = 90.seconds, duration = 45.seconds, sets = sets.toList())

    private fun dayOf(vararg blocks: Block) = WorkoutDay(DayId(1), today, blocks = blocks.toList())

    private fun past(
        id: ExerciseId,
        weight: Double? = null,
        reps: Int? = null,
        unit: WeightUnit = WeightUnit.Kg,
        duration: Duration? = null,
        distance: Double? = null,
        distanceUnit: DistanceUnit = DistanceUnit.Km,
        date: LocalDate = before,
    ) = PastSet(date, id, 0, reps, weight, unit, duration, null, distance, distanceUnit)

    @Test
    fun `a heavier load than on any earlier day is a record, with what it was`() {
        val day = dayOf(block(squat, set(60.0, 5), set(62.5, 5)))
        val records = Records.of(day, listOf(past(squat.id, 57.5, 5), past(squat.id, 60.0, 3)))
        assertEquals(listOf(Record(squat.id, "Squat", Metric.Load, 62.5, 60.0, WeightUnit.Kg)), records)
    }

    @Test
    fun `records are compared within one unit only`() {
        // 100 lb is less than 60 kg, but kg and lb are never compared: the lb best was 90.
        val day = dayOf(block(squat, set(100.0, 5, WeightUnit.Lb)))
        val records = Records.of(day, listOf(past(squat.id, 60.0, 5), past(squat.id, 90.0, 5, WeightUnit.Lb)))
        assertEquals(listOf(Record(squat.id, "Squat", Metric.Load, 100.0, 90.0, WeightUnit.Lb)), records)
        // A heavier kg set than every earlier lb set isn't a record either.
        assertEquals(emptyList(), Records.of(dayOf(block(squat, set(80.0, 5))), listOf(past(squat.id, 150.0, 5, WeightUnit.Lb))))
    }

    @Test
    fun `the first time in a unit has nothing to beat, so it isn't a record`() {
        assertEquals(emptyList(), Records.of(dayOf(block(squat, set(60.0, 5))), emptyList()))
        assertEquals(emptyList(), Records.of(dayOf(block(squat, set(130.0, 5, WeightUnit.Lb))), listOf(past(squat.id, 60.0, 5))))
    }

    @Test
    fun `warm-ups and sets not done never make a record`() {
        val day = dayOf(block(squat, set(100.0, 3, warmup = true), set(70.0, 5, done = false), set(60.0, 5)))
        assertEquals(emptyList(), Records.of(day, listOf(past(squat.id, 60.0, 5))))
    }

    @Test
    fun `equal to the best is not a record`() {
        assertEquals(emptyList(), Records.of(dayOf(block(squat, set(60.0, 5))), listOf(past(squat.id, 60.0, 8))))
    }

    @Test
    fun `an exercise without weight is measured in reps, else distance, else time`() {
        val day =
            dayOf(
                block(pullUp, set(reps = 12)),
                block(walk, set(distance = 5.5, duration = 3000.seconds)),
                block(plank, set(duration = 70.seconds)),
            )
        val earlier =
            listOf(
                past(pullUp.id, reps = 10),
                past(walk.id, distance = 5.0, duration = 2400.seconds),
                past(walk.id, distance = 8.0, distanceUnit = DistanceUnit.Mi),
                past(plank.id, duration = 60.seconds),
            )
        assertEquals(
            listOf(
                Record(pullUp.id, "Pull-up", Metric.Reps, 12.0, 10.0, null),
                Record(walk.id, "Walk", Metric.Distance, 5.5, 5.0, DistanceUnit.Km),
                Record(plank.id, "Plank", Metric.Time, 70.0, 60.0, null),
            ),
            Records.of(day, earlier),
        )
    }

    @Test
    fun `two blocks of one exercise count as one, and only earlier days are compared`() {
        val day = dayOf(block(squat, set(60.0, 5)), block(squat, set(65.0, 3)))
        val sameDay = past(squat.id, 70.0, 1, date = today)
        assertEquals(
            listOf(Record(squat.id, "Squat", Metric.Load, 65.0, 62.5, WeightUnit.Kg)),
            Records.of(day, listOf(past(squat.id, 62.5, 5), sameDay)),
        )
    }

    @Test
    fun `an exercise that records weight but was done unloaded gets its record in reps, against unloaded sets only`() {
        val day = dayOf(block(squat, set(0.0, 15), set(null, 12)))
        // 20 reps at 60 kg is a loaded set: reps under a load don't compare with bodyweight reps.
        val earlier = listOf(past(squat.id, 0.0, 12), past(squat.id, 60.0, 20))
        assertEquals(listOf(Record(squat.id, "Squat", Metric.Reps, 15.0, 12.0, null)), Records.of(day, earlier))
    }
}
