// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings

import app.fit.core.WeightUnit
import app.fit.exercises.step

data class Settings(
    // Exercises made in the app start in this unit; each exercise, and each set, keeps its own.
    val weightUnit: WeightUnit = WeightUnit.Kg,
    // The bar the plate calculator subtracts, one per unit.
    val barKg: Double = 20.0,
    val barLb: Double = 45.0,
    // Beep, vibration and the end notification when a rest or hold runs out.
    val alarm: Boolean = true,
    // While the focus view of today's workout or the guided player is open.
    val keepScreenOn: Boolean = true,
    // A timed guided step moves on when it ends; off, it waits at 0 for Next.
    val autoAdvance: Boolean = true,
) {
    fun bar(unit: WeightUnit): Double = if (unit == WeightUnit.Kg) barKg else barLb
}

// Up to a heavy specialty bar; down to 0 for loads without one.
private fun maxBar(unit: WeightUnit): Double = if (unit == WeightUnit.Kg) 50.0 else 110.0

fun Settings.canStepBar(
    unit: WeightUnit,
    up: Boolean,
): Boolean = steppedBar(unit, up) != bar(unit)

// One plate step (2.5 kg, 5 lb) at a time.
fun Settings.steppedBar(
    unit: WeightUnit,
    up: Boolean,
): Double = (bar(unit) + if (up) unit.step() else -unit.step()).coerceIn(0.0, maxBar(unit))
