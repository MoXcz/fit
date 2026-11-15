// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val SQUAT = ExerciseId(1)
private val MON = LocalDate(2026, 11, 2)
private val TUE = LocalDate(2026, 11, 3)

private fun past(
    date: LocalDate,
    weight: Double?,
    reps: Int?,
    position: Int = 0,
    unit: WeightUnit = WeightUnit.Kg,
    rpe: Double? = null,
    exercise: ExerciseId = SQUAT,
) = PastSet(date, exercise, position, reps, weight, unit, null, rpe)

internal fun exerciseOf(
    id: Long,
    name: String,
    tracks: Tracks = Tracks.WeightAndReps,
    defaults: ExerciseDefaults = ExerciseDefaults(),
    unit: WeightUnit = WeightUnit.Kg,
    distanceUnit: DistanceUnit = DistanceUnit.Km,
) = Exercise(
    ExerciseId(id),
    contentId = null,
    fields = ExerciseFields(name = name, tracks = tracks, defaults = defaults, weightUnit = unit, distanceUnit = distanceUnit),
)

internal fun set(
    id: Long,
    weight: Double? = null,
    reps: Int? = null,
    targetReps: Int = 8,
    warmup: Boolean = false,
    doneAt: Instant? = null,
    unit: WeightUnit = WeightUnit.Kg,
) = SetEntry(SetId(id), warmup = warmup, targetReps = targetReps, weight = weight, weightUnit = unit, reps = reps, doneAt = doneAt)

class ProgressionTest {
    @Test
    fun `progression adds the step when every working set of the last workout reached the top of the range`() {
        val history = listOf(past(MON, 60.0, 12, 0), past(MON, 60.0, 12, 1), past(MON, 60.0, 13, 2))
        val last = Progression.lastPerformance(history, SQUAT, WeightUnit.Kg)
        assertEquals(62.5, Progression.startWeight(last, defaultWeight = 0.0, repsTarget = 12, step = 2.5, rpeGate = null))
    }

    @Test
    fun `progression keeps the load when one set missed the target`() {
        val history = listOf(past(MON, 60.0, 12, 0), past(MON, 60.0, 9, 1))
        val last = Progression.lastPerformance(history, SQUAT, WeightUnit.Kg)
        assertEquals(60.0, Progression.startWeight(last, 0.0, repsTarget = 12, step = 2.5, rpeGate = null))
    }

    @Test
    fun `progression waits when the hardest set was above the RPE gate, and a set without RPE doesn't block`() {
        val hard = listOf(past(MON, 60.0, 8, 0, rpe = 7.0), past(MON, 60.0, 8, 1, rpe = 9.0))
        assertEquals(60.0, Progression.startWeight(Progression.lastPerformance(hard, SQUAT, WeightUnit.Kg), 0.0, 8, 2.5, rpeGate = 8.0))
        val unrated = listOf(past(MON, 60.0, 8, 0), past(MON, 60.0, 8, 1, rpe = 8.0))
        assertEquals(62.5, Progression.startWeight(Progression.lastPerformance(unrated, SQUAT, WeightUnit.Kg), 0.0, 8, 2.5, rpeGate = 8.0))
    }

    @Test
    fun `the next load comes from the top set, never from a lighter back-off set`() {
        // Done last: a back-off set at 50 kg.
        val history = listOf(past(MON, 80.0, 5, 0), past(MON, 80.0, 5, 1), past(MON, 50.0, 10, 2))
        val last = Progression.lastPerformance(history, SQUAT, WeightUnit.Kg)
        assertEquals(80.0, last?.top?.weight)
        assertEquals(82.5, Progression.startWeight(last, 0.0, repsTarget = 5, step = 2.5, rpeGate = null))
    }

    @Test
    fun `sets in another unit neither set nor judge the load`() {
        val history = listOf(past(MON, 60.0, 8, unit = WeightUnit.Kg), past(TUE, 135.0, 8, unit = WeightUnit.Lb))
        val last = Progression.lastPerformance(history, SQUAT, WeightUnit.Kg)
        assertEquals(MON, last?.date)
        assertEquals(62.5, Progression.startWeight(last, 0.0, repsTarget = 8, step = 2.5, rpeGate = null))
    }

    @Test
    fun `with no history the exercise's default weight is used and nothing is added`() {
        assertEquals(20.0, Progression.startWeight(null, defaultWeight = 20.0, repsTarget = 8, step = 2.5, rpeGate = null))
    }

    @Test
    fun `only the most recent workout counts`() {
        val history = listOf(past(MON, 70.0, 3), past(TUE, 60.0, 12), past(TUE, 60.0, 12, 1, exercise = ExerciseId(9)))
        val last = Progression.lastPerformance(history, SQUAT, WeightUnit.Kg)
        assertEquals(TUE, last?.date)
        assertEquals(1, last?.sets?.size)
    }
}

class WarmupPlanTest {
    @Test
    fun `warm-up percentages and reps for one, two and three sets`() {
        assertEquals(listOf(Warmup(60.0, 5)), WarmupPlan.of(1, 100.0, WeightUnit.Kg))
        assertEquals(listOf(Warmup(50.0, 5), Warmup(75.0, 3)), WarmupPlan.of(2, 100.0, WeightUnit.Kg))
        assertEquals(listOf(Warmup(40.0, 8), Warmup(60.0, 5), Warmup(80.0, 3)), WarmupPlan.of(3, 100.0, WeightUnit.Kg))
    }

    @Test
    fun `warm-up weights round to the nearest 2_5 kg or 5 lb, halves up`() {
        // 0.5 × 65 = 32.5; 0.75 × 65 = 48.75 → 50 (19.5 steps rounds up).
        assertEquals(listOf(32.5, 50.0), WarmupPlan.of(2, 65.0, WeightUnit.Kg).map { it.weight })
        // 0.5 × 135 = 67.5 → 70 (13.5 steps); 0.75 × 135 = 101.25 → 100.
        assertEquals(listOf(70.0, 100.0), WarmupPlan.of(2, 135.0, WeightUnit.Lb).map { it.weight })
    }

    @Test
    fun `warm-up weights are not raised to the bar, so dumbbell warm-ups stay lighter than the work`() {
        assertEquals(listOf(5.0, 7.5), WarmupPlan.of(2, 10.0, WeightUnit.Kg).map { it.weight })
        assertEquals(listOf(5.0, 7.5, 10.0), WarmupPlan.of(3, 12.5, WeightUnit.Kg).map { it.weight })
    }

    @Test
    fun `a warm-up that rounds to nothing is left out`() {
        assertEquals(listOf(2.5, 2.5), WarmupPlan.of(3, 2.5, WeightUnit.Kg).map { it.weight })
        assertEquals(emptyList(), WarmupPlan.of(2, 1.0, WeightUnit.Kg))
    }

    @Test
    fun `a warm-up is never heavier than the work`() {
        assertEquals(listOf(2.5), WarmupPlan.of(1, 2.5, WeightUnit.Kg).map { it.weight })
    }
}

class PlatesTest {
    @Test
    fun `plates per side are taken largest first after the bar`() {
        assertEquals(Loading(20.0, listOf(25.0, 20.0, 2.5), 0.0), Plates.perSide(115.0, WeightUnit.Kg, 20.0))
        assertEquals(Loading(45.0, listOf(45.0, 35.0), 0.0), Plates.perSide(205.0, WeightUnit.Lb, 45.0))
    }

    @Test
    fun `what the plates can't make up is shown as the remainder`() {
        assertEquals(Loading(20.0, listOf(20.0), 0.5), Plates.perSide(61.0, WeightUnit.Kg, 20.0))
    }

    @Test
    fun `the bar alone needs no plates, and a load below the bar has no loading`() {
        assertEquals(Loading(20.0, emptyList(), 0.0), Plates.perSide(20.0, WeightUnit.Kg, 20.0))
        assertNull(Plates.perSide(15.0, WeightUnit.Kg, 20.0))
    }

    @Test
    fun `the bar weight comes from the caller`() {
        assertEquals(Loading(15.0, listOf(5.0), 0.0), Plates.perSide(25.0, WeightUnit.Kg, 15.0))
    }
}

class SessionClockTest {
    private val now = Instant.parse("2026-11-09T18:00:00Z")

    @Test
    fun `stale finish ends the workout at the last set when that was over 15 minutes ago`() {
        val last = now - 40.minutes
        assertEquals(last, SessionClock.finishAt(last, now))
        assertEquals(now, SessionClock.finishAt(now - 15.minutes, now))
        assertEquals(now, SessionClock.finishAt(null, now))
    }

    @Test
    fun `reopen moves the start on by the pause, so the clock carries on`() {
        val start = now - 90.minutes
        val finish = now - 30.minutes
        assertEquals(now - 60.minutes, SessionClock.reopenedStart(start, finish, now))
        assertEquals(start, SessionClock.reopenedStart(start, null, now))
    }

    @Test
    fun `elapsed runs from the start and stops at the finish`() {
        val day = WorkoutDay(DayId(1), MON, startedAt = now - 30.minutes)
        assertEquals(30.minutes, day.elapsed(now))
        assertEquals(20.minutes, day.copy(finishedAt = now - 10.minutes).elapsed(now))
        assertEquals(0.seconds, day.copy(startedAt = null).elapsed(now))
    }

    @Test
    fun `the day key is the local date across the DST change`() {
        val berlin = TimeZone.of("Europe/Berlin")
        // 2026-03-29 has 23 hours in Berlin; 23:30 local that night is 21:30 UTC.
        val lateOnDstDay = FixedClock(Instant.parse("2026-03-29T21:30:00Z"))
        assertEquals(LocalDate(2026, 3, 29), lateOnDstDay.today(berlin))
        val earlyNextDay = FixedClock(Instant.parse("2026-03-29T22:30:00Z"))
        assertEquals(LocalDate(2026, 3, 30), earlyNextDay.today(berlin))
        assertEquals(
            listOf(LocalDate(2026, 3, 23), LocalDate(2026, 3, 29)),
            LocalDate(2026, 3, 29).week().let { listOf(it.first(), it.last()) },
        )
    }

    @Test
    fun `an end time earlier than the start is the next day`() {
        val zone = TimeZone.UTC
        val start = SessionClock.atLocal(MON, LocalTime(23, 30), zone)
        assertEquals(Instant.parse("2026-11-03T00:15:00Z"), SessionClock.atLocal(MON, LocalTime(0, 15), zone, notBefore = start))
        assertEquals(Instant.parse("2026-11-02T23:45:00Z"), SessionClock.atLocal(MON, LocalTime(23, 45), zone, notBefore = start))
    }

    @Test
    fun `the week strip runs Monday to Sunday`() {
        val week = LocalDate(2026, 11, 11).week()
        assertEquals(LocalDate(2026, 11, 9), week.first())
        assertEquals(LocalDate(2026, 11, 15), week.last())
    }
}
