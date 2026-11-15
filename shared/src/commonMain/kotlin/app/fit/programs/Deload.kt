// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.core.WeightUnit
import app.fit.exercises.step
import kotlin.math.floor

object Deload {
    const val DEFAULT_PERCENT = 10
    val PERCENTS: IntRange = 5..50
    const val PERCENT_STEP = 5

    // The load less [percent], to the nearest plate step (2.5 kg, 5 lb; halves up). A load above one
    // step always comes out lighter and never 0: when rounding lands on the load or on 0 it goes one
    // step down, but not below one step. A load of one step or less stays as it is: nothing lighter
    // can be loaded, and a load nobody can set is worse than one that isn't reduced.
    fun cut(
        weight: Double,
        percent: Int,
        unit: WeightUnit,
    ): Double {
        if (weight <= 0) return 0.0
        val step = unit.step()
        // In hundredths first, so 62.5 × 0.9 is 56.25 and not 56.249999….
        val less = floor(weight * (100 - percent) + 0.5) / 100
        if (weight <= step) return weight
        val rounded = floor(less / step + 0.5) * step
        return if (rounded >= weight || rounded <= 0) (weight - step).coerceAtLeast(step) else rounded
    }

    fun steppedPercent(
        percent: Int,
        up: Boolean,
    ): Int = (percent + if (up) PERCENT_STEP else -PERCENT_STEP).coerceIn(PERCENTS)
}
