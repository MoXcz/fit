// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.Tracks
import app.fit.routines.ItemTarget
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class DayPlanTest {
    private val squat = exerciseOf(1, "Squat", defaults = ExerciseDefaults(sets = 4, reps = 6, weight = 20.0))
    private val plank = exerciseOf(2, "Plank", tracks = Tracks(time = true), defaults = ExerciseDefaults(sets = 2, duration = 60.seconds))
    private val walk = exerciseOf(3, "Walk", tracks = Tracks(distance = true, time = true), distanceUnit = DistanceUnit.Mi)
    private val mon = LocalDate(2026, 11, 2)
    private val now = Instant.parse("2026-11-09T18:00:00Z")

    private fun item(
        exercise: app.fit.exercises.Exercise,
        target: ItemTarget,
    ) = RoutineItem(RoutineItemId(exercise.id.value * 10), exercise, target)

    private val squatTarget =
        ItemTarget(sets = 3, reps = 8, repsMax = 12, rest = 120.seconds, duration = 45.seconds, progressStep = 2.5, warmupSets = 2)

    @Test
    fun `applying a routine copies its targets into set rows with progression and warm-ups first`() {
        val history = (0..2).map { PastSet(mon, squat.id, it, 12, 60.0, WeightUnit.Kg, null, null) }
        val (block) = DayPlan.fromRoutine(listOf(item(squat, squatTarget)), history)
        assertEquals(listOf(true, true, false, false, false), block.sets.map { it.warmup })
        assertEquals(listOf(32.5, 47.5, 62.5, 62.5, 62.5), block.sets.map { it.weight })
        assertEquals(listOf(5, 3, 8, 8, 8), block.sets.map { it.targetReps })
        assertEquals(listOf(null, null, 12, 12, 12), block.sets.map { it.targetRepsMax })
        assertEquals(2, block.warmupSets)
        assertEquals(120.seconds, block.rest)
    }

    @Test
    fun `a routine slot without history starts at the exercise's default weight, and no warm-ups below an empty bar`() {
        val (block) = DayPlan.fromRoutine(listOf(item(squat, squatTarget)), emptyList())
        assertEquals(listOf(true, true, false, false, false), block.sets.map { it.warmup })
        assertEquals(20.0, block.sets.last().weight)
        val noLoad = item(squat.copy(fields = squat.fields.copy(defaults = ExerciseDefaults())), squatTarget)
        assertTrue(
            DayPlan
                .fromRoutine(listOf(noLoad), emptyList())
                .single()
                .sets
                .none { it.warmup },
        )
    }

    @Test
    fun `timed slots get their set time as the target, and slots without weight get no load`() {
        val (block) =
            DayPlan.fromRoutine(
                listOf(item(plank, ItemTarget(sets = 2, reps = 1, rest = 30.seconds, duration = 40.seconds))),
                emptyList(),
            )
        assertEquals(listOf(40.seconds, 40.seconds), block.sets.map { it.targetDuration })
        assertEquals(listOf(null, null), block.sets.map { it.weight })
    }

    @Test
    fun `sets take the distance unit of their exercise`() {
        val (block) =
            DayPlan.fromRoutine(
                listOf(item(walk, ItemTarget(sets = 1, reps = 1, rest = 0.seconds, duration = 30.minutes))),
                emptyList(),
            )
        assertEquals(DistanceUnit.Mi, block.sets.single().distanceUnit)
        assertTrue(DayPlan.adHoc(walk, emptyList()).sets.all { it.distanceUnit == DistanceUnit.Mi })
    }

    @Test
    fun `an added exercise gets its usual sets with targets from its last top set`() {
        val history =
            listOf(
                PastSet(mon, squat.id, 0, 5, 80.0, WeightUnit.Kg, null, null),
                PastSet(mon, squat.id, 1, 10, 60.0, WeightUnit.Kg, null, null),
            )
        val block = DayPlan.adHoc(squat, history)
        assertEquals(4, block.sets.size)
        assertEquals(setOf(80.0), block.sets.map { it.weight }.toSet())
        assertEquals(setOf(5), block.sets.map { it.targetReps }.toSet())
        assertTrue(block.sets.none { it.warmup })
    }

    @Test
    fun `an added exercise without history takes the exercise's defaults`() {
        val block =
            DayPlan.adHoc(
                squat.copy(fields = squat.fields.copy(defaults = ExerciseDefaults(sets = 3, reps = 8, repsMax = 12, weight = 40.0))),
                emptyList(),
            )
        assertEquals(3, block.sets.size)
        assertEquals(8, block.sets.first().targetReps)
        assertEquals(12, block.sets.first().targetRepsMax)
        assertEquals(40.0, block.sets.first().weight)
        assertEquals(
            60.seconds,
            DayPlan
                .adHoc(plank, emptyList())
                .sets
                .first()
                .targetDuration,
        )
    }

    private val loggedDay =
        WorkoutDay(
            DayId(1),
            mon,
            name = "Legs",
            blocks =
                listOf(
                    Block(
                        BlockId(1),
                        squat,
                        rest = 90.seconds,
                        duration = 45.seconds,
                        warmupSets = 1,
                        sets =
                            listOf(
                                SetEntry(SetId(1), warmup = true, targetReps = 5, weight = 40.0, reps = 5, doneAt = now),
                                SetEntry(SetId(2), targetReps = 8, targetRepsMax = 12, weight = 60.0, reps = 11, rpe = 8.0, doneAt = now),
                                SetEntry(SetId(3), targetReps = 6, weight = 60.0, reps = 4, doneAt = now),
                            ),
                    ),
                ),
        )

    @Test
    fun `copying a day takes targets, loads, units and warm-ups but nothing that was done`() {
        val (block) = DayPlan.copyOf(loggedDay)
        assertEquals(listOf(true, false, false), block.sets.map { it.warmup })
        assertEquals(listOf(5, 8, 6), block.sets.map { it.targetReps })
        assertEquals(listOf(40.0, 60.0, 60.0), block.sets.map { it.weight })
        assertEquals(1, block.warmupSets)
    }

    @Test
    fun `add set clones the last working set, not a warm-up`() {
        val block = loggedDay.blocks.single()
        val next = DayPlan.nextSet(block)
        assertFalse(next.warmup)
        assertEquals(6, next.targetReps)
        assertEquals(60.0, next.weight)
        val onlyWarmup = block.copy(sets = block.sets.take(1))
        assertEquals(6, DayPlan.nextSet(onlyWarmup).targetReps)
        assertEquals(20.0, DayPlan.nextSet(onlyWarmup).weight)
    }

    @Test
    fun `warm-up sets added by hand ramp up to the first working load`() {
        val block = loggedDay.blocks.single().let { it.copy(sets = it.sets.drop(1), warmupSets = 0) }
        assertEquals(listOf(30.0, 45.0), DayPlan.warmupsFor(block).map { it.weight })
        assertTrue(DayPlan.warmupsFor(loggedDay.blocks.single()).isEmpty())
    }

    @Test
    fun `save as routine takes the working-set count and the first working set's targets, never the reps done`() {
        val (pair) = DayPlan.routineItems(loggedDay)
        val target = pair.second
        assertEquals(squat.id, pair.first)
        assertEquals(2, target.sets)
        assertEquals(8, target.reps)
        assertEquals(12, target.repsMax)
        assertEquals(1, target.warmupSets)
        assertNull(target.progressStep)
    }

    @Test
    fun `warm-up sets are left out of every count, estimate and mark the same way`() {
        val day = loggedDay
        assertEquals(2, day.totalSets)
        assertEquals(2, day.doneSets)
        assertEquals((90 + 45).seconds * 2, day.estimate)
        val onlyWarmups = day.copy(blocks = day.blocks.map { b -> b.copy(sets = b.sets.take(1)) })
        assertEquals(0, onlyWarmups.doneSets)
        assertEquals(DayMark.None, onlyWarmups.mark())
    }

    @Test
    fun `a finished day with no done sets gets no mark, not a green one`() {
        val empty = WorkoutDay(DayId(1), mon, startedAt = now, finishedAt = now)
        assertEquals(DayMark.None, empty.mark())
        assertEquals(DayMark.None, (null as WorkoutDay?).mark())
        assertEquals(DayMark.Trained, loggedDay.mark())
        assertEquals(DayMark.Finished, loggedDay.copy(finishedAt = now).mark())
    }

    @Test
    fun `ticking a set fills what was done from the target and leaves the target alone`() {
        val set = SetEntry(SetId(1), targetReps = 8, targetRepsMax = 12, targetDuration = 40.seconds)
        val ticked = set.ticked(now, Tracks(reps = true, time = true))
        assertEquals(8, ticked.reps)
        assertEquals(40.seconds, ticked.duration)
        assertEquals(8, ticked.targetReps)
        assertEquals(12, ticked.targetRepsMax)
        assertEquals(10, set.copy(reps = 10).ticked(now, Tracks.WeightAndReps).reps)
        assertNull(set.ticked(now, Tracks(time = true)).reps)
    }
}
