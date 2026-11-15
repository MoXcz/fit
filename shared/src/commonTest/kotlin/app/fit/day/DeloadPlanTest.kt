// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.WeightUnit
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.Tracks
import app.fit.history.Metric
import app.fit.history.Records
import app.fit.routines.ItemTarget
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Deload days, bodyweight sets and how progression, warm-ups, volume and records treat them.
class DeloadPlanTest {
    private val squat = exerciseOf(1, "Squat", defaults = ExerciseDefaults(sets = 3, reps = 5, weight = 20.0))
    private val dip = exerciseOf(2, "Dip", defaults = ExerciseDefaults(sets = 3, reps = 8))
    private val pushUp = exerciseOf(3, "Push-up", tracks = Tracks(reps = true))
    private val week5 = LocalDate(2026, 11, 2)
    private val week6 = LocalDate(2026, 11, 9)
    private val now = Instant.parse("2026-11-16T10:00:00Z")

    private fun past(
        date: LocalDate,
        weight: Double?,
        reps: Int,
        deload: Boolean = false,
        bodyweight: Boolean = false,
        exercise: app.fit.exercises.Exercise = squat,
        position: Int = 0,
    ) = PastSet(date, exercise.id, position, reps, weight, WeightUnit.Kg, null, null, deload = deload, bodyweight = bodyweight)

    private fun item(
        exercise: app.fit.exercises.Exercise,
        warmups: Int = 0,
        step: Double? = 2.5,
    ) = RoutineItem(
        RoutineItemId(exercise.id.value),
        exercise,
        ItemTarget(sets = 3, reps = 5, rest = 120.seconds, duration = 45.seconds, progressStep = step, warmupSets = warmups),
    )

    @Test
    fun `progression skips deload days, so the next week builds on the last full one`() {
        val history = listOf(past(week5, 100.0, 5), past(week6, 90.0, 5, deload = true))
        val last = Progression.lastPerformance(history, squat.id, WeightUnit.Kg)!!
        assertEquals(week5, last.date)
        assertEquals(102.5, Progression.startWeight(last, 20.0, repsTarget = 5, step = 2.5, rpeGate = null))
    }

    @Test
    fun `an exercise done only on a deload day starts from it, without a step`() {
        val last = Progression.lastPerformance(listOf(past(week6, 90.0, 5, deload = true)), squat.id, WeightUnit.Kg)!!
        assertEquals(90.0, Progression.startWeight(last, 20.0, repsTarget = 5, step = 2.5, rpeGate = null))
    }

    @Test
    fun `in a deload week the plan's loads are the base without a step, cut and rounded, and warm-ups follow the cut`() {
        val history = listOf(past(week5, 100.0, 5))
        val full = DayPlan.fromRoutine(listOf(item(squat, warmups = 2)), history).single()
        assertEquals(102.5, full.sets.last().weight)
        val deload = DayPlan.fromRoutine(listOf(item(squat, warmups = 2)), history, deloadPercent = 10).single()
        assertEquals(listOf(45.0, 67.5, 90.0, 90.0, 90.0), deload.sets.map { it.weight })
        assertEquals(listOf(true, true, false, false, false), deload.sets.map { it.warmup })
    }

    @Test
    fun `new sets are bodyweight sets when the last top set was one, with its added weight and no warm-ups`() {
        val history = listOf(past(week5, 10.0, 8, bodyweight = true, exercise = dip))
        val block = DayPlan.fromRoutine(listOf(item(dip, warmups = 2, step = null)), history).single()
        assertTrue(block.sets.all { it.bodyweight && !it.warmup })
        assertEquals(10.0, block.sets.first().weight)
        // A reps-only slot keeps the flag but no load.
        val reps = DayPlan.fromRoutine(listOf(item(pushUp)), listOf(past(week5, null, 20, bodyweight = true, exercise = pushUp))).single()
        assertTrue(reps.sets.all { it.bodyweight && it.weight == null })
        assertTrue(DayPlan.adHoc(dip, history).sets.all { it.bodyweight })
    }

    @Test
    fun `a deload cuts a bodyweight set's added weight only`() {
        val history = listOf(past(week5, 20.0, 8, bodyweight = true, exercise = dip))
        val block = DayPlan.fromRoutine(listOf(item(dip)), history, deloadPercent = 25).single()
        assertEquals(15.0, block.sets.first().weight)
        val light = listOf(past(week5, 10.0, 8, bodyweight = true, exercise = dip))
        assertEquals(
            7.5,
            DayPlan
                .fromRoutine(listOf(item(dip)), light, deloadPercent = 10)
                .single()
                .sets
                .first()
                .weight,
        )
    }

    @Test
    fun `volume counts a bodyweight set's added weight only, so a set with nothing added adds nothing`() {
        val sets =
            listOf(
                SetEntry(SetId(1), targetReps = 8, weight = 10.0, reps = 8, doneAt = now, bodyweight = true),
                SetEntry(SetId(2), targetReps = 8, reps = 10, doneAt = now, bodyweight = true),
            )
        val block = Block(BlockId(1), dip, rest = 90.seconds, duration = 45.seconds, sets = sets)
        assertEquals(mapOf(WeightUnit.Kg to 80.0), WorkoutDay(DayId(1), week6, blocks = listOf(block)).volume)
    }

    @Test
    fun `a bodyweight set's record compares the added weight, so gaining body weight is never one`() {
        val earlier = listOf(past(week5, 10.0, 8, bodyweight = true, exercise = dip))

        fun day(added: Double?) =
            WorkoutDay(
                DayId(1),
                week6,
                blocks =
                    listOf(
                        Block(
                            BlockId(1),
                            dip,
                            rest = 90.seconds,
                            duration = 45.seconds,
                            sets = listOf(SetEntry(SetId(1), targetReps = 8, weight = added, reps = 9, doneAt = now, bodyweight = true)),
                        ),
                    ),
            )
        val record = Records.of(day(12.5), earlier).single()
        assertEquals(Metric.Load, record.metric)
        assertEquals(12.5, record.value)
        assertEquals(10.0, record.was)
        assertEquals(emptyList(), Records.of(day(10.0), earlier))
    }

    @Test
    fun `a bodyweight set with nothing added falls back to reps, against other unloaded sets`() {
        val earlier = listOf(past(week5, null, 12, bodyweight = true, exercise = pushUp))
        val sets = listOf(SetEntry(SetId(1), targetReps = 12, reps = 15, doneAt = now, bodyweight = true))
        val day =
            WorkoutDay(DayId(1), week6, blocks = listOf(Block(BlockId(1), pushUp, rest = 60.seconds, duration = 45.seconds, sets = sets)))
        val record = Records.of(day, earlier).single()
        assertEquals(Metric.Reps, record.metric)
        assertEquals(15.0, record.value)
    }

    @Test
    fun `a base from a deload day isn't cut again in the next deload week`() {
        val onlyDeload = listOf(past(week5, 90.0, 5, deload = true))
        assertEquals(
            90.0,
            DayPlan
                .fromRoutine(listOf(item(squat)), onlyDeload, deloadPercent = 10)
                .single()
                .sets
                .first()
                .weight,
        )
    }

    @Test
    fun `an exercise added by hand in a deload week starts cut, from the last full week`() {
        val history = listOf(past(week5, 100.0, 5), past(week6, 90.0, 5, deload = true))
        assertEquals(
            100.0,
            DayPlan
                .adHoc(squat, history)
                .sets
                .first()
                .weight,
        )
        assertEquals(
            90.0,
            DayPlan
                .adHoc(squat, history, deloadPercent = 10)
                .sets
                .first()
                .weight,
        )
    }

    @Test
    fun `the last load lifted skips deload days, and a bodyweight set's says it's what was added`() {
        val history =
            listOf(
                past(week5, 100.0, 5),
                past(week6, 90.0, 5, deload = true),
                past(week5, 10.0, 8, bodyweight = true, exercise = dip),
            )
        val loads = Progression.lastLoads(history)
        assertEquals(LastLoad(100.0, WeightUnit.Kg), loads[squat.id])
        assertEquals(LastLoad(10.0, WeightUnit.Kg, bodyweight = true), loads[dip.id])
        assertEquals(LastLoad(90.0, WeightUnit.Kg), Progression.lastLoads(history.filter { it.deload })[squat.id])
    }
}
