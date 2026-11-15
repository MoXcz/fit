// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import app.fit.core.WeightUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// A default the editor changes with − and +.
enum class DefaultField { Sets, Reps, RepsMax, Rest, RestMax, Duration, Weight }

private const val MAX_SETS = 20
private const val MAX_REPS = 1000
private val MAX_REST = 1.hours
internal val MIN_DURATION = 5.seconds
internal val MAX_DURATION = 2.hours
const val MAX_WEIGHT = 1000.0

// Small steps for short times, bigger ones for long times (a 30-minute walk isn't 5 s at a time).
private fun timeStep(at: Duration): Duration =
    when {
        at < 1.minutes -> 5.seconds
        at < 10.minutes -> 15.seconds
        else -> 1.minutes
    }

internal fun Duration.stepped(up: Boolean): Duration = if (up) this + timeStep(this) else this - timeStep(this - 1.seconds)

// The weight step is the smallest usual plate pair: 2.5 kg or 5 lb.
fun WeightUnit.step(): Double = if (this == WeightUnit.Kg) 2.5 else 5.0

fun ExerciseDefaults.canStep(
    field: DefaultField,
    up: Boolean,
    unit: WeightUnit,
): Boolean = step(field, up, unit) != this

// One step of [field]. A maximum is "none" until it's raised above its minimum, and goes back to
// none when it comes down to the minimum or the minimum passes it.
fun ExerciseDefaults.step(
    field: DefaultField,
    up: Boolean,
    unit: WeightUnit,
): ExerciseDefaults =
    when (field) {
        DefaultField.Sets -> {
            copy(sets = (sets + if (up) 1 else -1).coerceIn(1, MAX_SETS))
        }

        DefaultField.Reps -> {
            val next = (reps + if (up) 1 else -1).coerceIn(1, MAX_REPS)
            copy(reps = next, repsMax = repsMax?.takeIf { it > next })
        }

        DefaultField.RepsMax -> {
            val current = repsMax ?: reps
            val next = (current + if (up) 1 else -1).coerceAtMost(MAX_REPS)
            copy(repsMax = next.takeIf { it > reps })
        }

        DefaultField.Rest -> {
            val next = rest.stepped(up).coerceIn(Duration.ZERO, MAX_REST)
            copy(rest = next, restMax = restMax?.takeIf { it > next })
        }

        DefaultField.RestMax -> {
            val next = (restMax ?: rest).stepped(up).coerceAtMost(MAX_REST)
            copy(restMax = next.takeIf { it > rest })
        }

        DefaultField.Duration -> {
            // A shorter time from a content file stays reachable but is never stepped up to by going down.
            copy(duration = duration.stepped(up).coerceIn(minOf(duration, MIN_DURATION), MAX_DURATION))
        }

        DefaultField.Weight -> {
            copy(weight = (weight + if (up) unit.step() else -unit.step()).coerceIn(0.0, MAX_WEIGHT))
        }
    }
