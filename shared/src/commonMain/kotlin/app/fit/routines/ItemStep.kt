// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines

import app.fit.core.WeightUnit
import app.fit.exercises.DefaultField
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.step

// A value of a routine slot the item sheet changes with − and +.
enum class ItemField { Sets, Reps, RepsMax, Rest, RestMax, Duration, WarmupSets, ProgressStep, ProgressRpe }

private const val MAX_WARMUP_SETS = 3

// Progression steps from "off" up: the usual small plate jumps.
private val KG_STEPS = listOf(1.25, 2.5, 5.0, 10.0)
private val LB_STEPS = listOf(2.5, 5.0, 10.0, 20.0)

// RPE gate: off, then 6 to 10 in half steps.
private val RPE_GATES = (12..20).map { it / 2.0 }

fun ItemTarget.canStep(
    field: ItemField,
    up: Boolean,
    unit: WeightUnit,
): Boolean = step(field, up, unit) != this

// One step of [field]. Sets, reps, rest and time follow the exercise editor's rules; the
// progression step and RPE gate step through fixed choices and "off" (null) below the first.
fun ItemTarget.step(
    field: ItemField,
    up: Boolean,
    unit: WeightUnit,
): ItemTarget =
    when (field) {
        ItemField.Sets -> withDefaults(DefaultField.Sets, up)
        ItemField.Reps -> withDefaults(DefaultField.Reps, up)
        ItemField.RepsMax -> withDefaults(DefaultField.RepsMax, up)
        ItemField.Rest -> withDefaults(DefaultField.Rest, up)
        ItemField.RestMax -> withDefaults(DefaultField.RestMax, up)
        ItemField.Duration -> withDefaults(DefaultField.Duration, up)
        ItemField.WarmupSets -> copy(warmupSets = (warmupSets + if (up) 1 else -1).coerceIn(0, MAX_WARMUP_SETS))
        ItemField.ProgressStep -> copy(progressStep = progressStep.stepThrough(if (unit == WeightUnit.Kg) KG_STEPS else LB_STEPS, up))
        ItemField.ProgressRpe -> copy(progressRpe = progressRpe.stepThrough(RPE_GATES, up))
    }

// Up goes to the next choice above the value, down to the next below it, or off (null) below the first.
private fun Double?.stepThrough(
    choices: List<Double>,
    up: Boolean,
): Double? =
    when {
        this == null -> if (up) choices.first() else null
        up -> choices.firstOrNull { it > this } ?: this
        else -> choices.lastOrNull { it < this }
    }

private fun ItemTarget.withDefaults(
    field: DefaultField,
    up: Boolean,
): ItemTarget {
    val d =
        ExerciseDefaults(sets = sets, reps = reps, repsMax = repsMax, rest = rest, restMax = restMax, duration = duration)
            .step(field, up, WeightUnit.Kg)
    return copy(sets = d.sets, reps = d.reps, repsMax = d.repsMax, rest = d.rest, restMax = d.restMax, duration = d.duration)
}
