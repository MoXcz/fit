// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.WeightUnit

// What goes on each side of the bar. [remainder] is what the plates can't make up, per side.
data class Loading(
    val bar: Double,
    val perSide: List<Double>,
    val remainder: Double,
)

object Plates {
    private val KG = listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25)
    private val LB = listOf(45.0, 35.0, 25.0, 10.0, 5.0, 2.5)

    fun perSide(
        total: Double,
        unit: WeightUnit,
        bar: Double,
    ): Loading? {
        if (total < bar) return null
        var side = (total - bar) / 2
        val plates = mutableListOf<Double>()
        for (plate in if (unit == WeightUnit.Kg) KG else LB) {
            while (side >= plate - EPSILON) {
                plates += plate
                side -= plate
            }
        }
        return Loading(bar, plates, roundToHundredths(side.coerceAtLeast(0.0)))
    }

    private const val EPSILON = 1e-9
}
