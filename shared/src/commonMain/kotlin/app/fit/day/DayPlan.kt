// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.WeightUnit
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.programs.Deload
import app.fit.routines.ItemTarget
import app.fit.routines.MAIN_SECTION
import app.fit.routines.RoutineItem

// How a day's blocks are made: from a routine, an exercise, another day or a block's last set.
object DayPlan {
    // Decided once, at apply time: later routine edits never reach the day. In a deload week
    // ([deloadPercent]) the load is the base without a step, cut by the percent; warm-ups follow
    // from the cut load. A bodyweight set's load is what's added, so that's what moves.
    fun fromRoutine(
        items: List<RoutineItem>,
        history: List<PastSet>,
        deloadPercent: Int? = null,
    ): List<PlannedBlock> =
        items.map { item ->
            val target = item.target
            val fields = item.exercise.fields
            val tracks = item.tracks
            val last = Progression.lastPerformance(history, item.exercise.id, fields.weightUnit)
            val bodyweight = last?.top?.bodyweight == true
            val weight =
                if (tracks.weight) {
                    val base =
                        Progression.startWeight(
                            last = last,
                            defaultWeight = fields.defaults.weight,
                            repsTarget = target.repsMax ?: target.reps,
                            step = target.progressStep.takeIf { deloadPercent == null },
                            rpeGate = target.progressRpe,
                        )
                    cutFor(deloadPercent, base, last, fields.weightUnit)
                } else {
                    null
                }
            val working =
                PlannedSet(
                    targetReps = target.reps,
                    targetRepsMax = target.repsMax,
                    targetDuration = target.duration.takeIf { tracks.time },
                    weight = weight,
                    weightUnit = fields.weightUnit,
                    distanceUnit = fields.distanceUnit,
                    bodyweight = bodyweight,
                )
            val warmups =
                if (weight != null && weight > 0 && target.warmupSets > 0 && !bodyweight) {
                    WarmupPlan.of(target.warmupSets, weight, fields.weightUnit).map { warmup ->
                        working.copy(warmup = true, targetReps = warmup.reps, targetRepsMax = null, weight = warmup.weight)
                    }
                } else {
                    emptyList()
                }
            PlannedBlock(
                exerciseId = item.exercise.id,
                section = target.section,
                rest = target.rest,
                restMax = target.restMax,
                duration = target.duration,
                tracksOverride = target.tracks,
                warmupSets = target.warmupSets,
                notes = target.notes,
                sets = warmups + List(target.sets) { working },
            )
        }

    // A base that came from a deload day is already light, so it isn't cut again.
    private fun cutFor(
        deloadPercent: Int?,
        base: Double,
        last: LastPerformance?,
        unit: WeightUnit,
    ): Double = if (deloadPercent != null && last?.deload != true) Deload.cut(base, deloadPercent, unit) else base

    // Only this exercise: adding by hand never applies a plan. In a deload week its load is cut too.
    fun adHoc(
        exercise: Exercise,
        history: List<PastSet>,
        section: String = MAIN_SECTION,
        deloadPercent: Int? = null,
    ): PlannedBlock {
        val fields = exercise.fields
        val d = fields.defaults
        val tracks = fields.tracks
        val last = Progression.lastPerformance(history, exercise.id, fields.weightUnit)
        val top = last?.top
        val set =
            PlannedSet(
                targetReps = top?.reps ?: d.reps,
                targetRepsMax = if (top?.reps != null) null else d.repsMax,
                targetDuration = if (tracks.time) top?.duration ?: d.duration else null,
                weight = if (tracks.weight) cutFor(deloadPercent, top?.weight ?: d.weight, last, fields.weightUnit) else null,
                weightUnit = fields.weightUnit,
                distanceUnit = fields.distanceUnit,
                bodyweight = top?.bodyweight == true,
            )
        return PlannedBlock(
            exerciseId = exercise.id,
            section = section,
            rest = d.rest,
            restMax = d.restMax,
            duration = d.duration,
            sets = List(d.sets.coerceAtLeast(1)) { set },
        )
    }

    fun copyOf(day: WorkoutDay): List<PlannedBlock> =
        day.blocks.map { block ->
            PlannedBlock(
                exerciseId = block.exercise.id,
                section = block.section,
                rest = block.rest,
                restMax = block.restMax,
                duration = block.duration,
                tracksOverride = block.tracksOverride,
                warmupSets = block.warmupSets,
                notes = block.notes,
                sets = block.sets.map { it.planned() },
            )
        }

    fun nextSet(block: Block): PlannedSet {
        block.workingSets.lastOrNull()?.let { return it.planned() }
        val fields = block.exercise.fields
        val d = fields.defaults
        return PlannedSet(
            targetReps = d.reps,
            targetRepsMax = d.repsMax,
            targetDuration = d.duration.takeIf { block.tracks.time },
            weight = d.weight.takeIf { block.tracks.weight },
            weightUnit = fields.weightUnit,
            distanceUnit = fields.distanceUnit,
        )
    }

    fun warmupsFor(block: Block): List<PlannedSet> {
        if (!block.tracks.weight || block.sets.any { it.warmup }) return emptyList()
        // Not before bodyweight sets: their weight is only what's added.
        val work = block.workingSets.firstOrNull { (it.weight ?: 0.0) > 0 && !it.bodyweight } ?: return emptyList()
        val count = block.warmupSets.takeIf { it in 1..WarmupPlan.MAX_SETS } ?: DEFAULT_WARMUPS
        return WarmupPlan.of(count, requireNotNull(work.weight), work.weightUnit).map {
            work.planned().copy(warmup = true, targetReps = it.reps, targetRepsMax = null, weight = it.weight)
        }
    }

    // Targets only, never the reps that were done.
    fun routineItems(day: WorkoutDay): List<Pair<ExerciseId, ItemTarget>> =
        day.blocks.map { block ->
            val first = block.workingSets.firstOrNull()
            block.exercise.id to
                ItemTarget(
                    section = block.section,
                    sets = block.workingSets.size.coerceAtLeast(1),
                    reps = first?.targetReps ?: block.exercise.fields.defaults.reps,
                    repsMax = first?.targetRepsMax,
                    rest = block.rest,
                    restMax = block.restMax,
                    duration = block.duration,
                    tracks = block.tracksOverride,
                    warmupSets = block.warmupSets,
                    notes = block.notes,
                )
        }

    private const val DEFAULT_WARMUPS = 2
}

private fun SetEntry.planned() =
    PlannedSet(
        warmup = warmup,
        targetReps = targetReps,
        targetRepsMax = targetRepsMax,
        targetDuration = targetDuration,
        weight = weight,
        weightUnit = weightUnit,
        distanceUnit = distanceUnit,
        bodyweight = bodyweight,
    )
