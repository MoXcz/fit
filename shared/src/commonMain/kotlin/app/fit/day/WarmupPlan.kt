// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.WeightUnit
import app.fit.exercises.step
import kotlin.math.floor

data class Warmup(
    val weight: Double,
    val reps: Int,
)

object WarmupPlan {
    private val PERCENTAGES = mapOf(1 to listOf(0.6), 2 to listOf(0.5, 0.75), 3 to listOf(0.4, 0.6, 0.8))

    const val MAX_SETS = 3

    // Not raised to the bar, so a dumbbell warm-up stays lighter than the work.
    fun of(
        count: Int,
        work: Double,
        unit: WeightUnit,
    ): List<Warmup> {
        val percentages = PERCENTAGES[count.coerceIn(1, MAX_SETS)].orEmpty()
        val step = unit.step()
        // A share that rounds to nothing isn't a warm-up set.
        return percentages.mapNotNull { pct ->
            val weight = floor(work * pct / step + 0.5) * step
            Warmup(weight = weight.coerceAtMost(work), reps = repsAt(pct)).takeIf { weight > 0 }
        }
    }

    private fun repsAt(pct: Double): Int =
        when {
            pct < 0.5 -> 8
            pct < 0.7 -> 5
            else -> 3
        }
}
