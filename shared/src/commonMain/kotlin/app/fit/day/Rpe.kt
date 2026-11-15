// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

// Rate of perceived exertion, 5 to 10 in halves; optional on every working set.
object Rpe {
    val values: List<Double> = (10..20).map { it / 2.0 }

    fun isValid(rpe: Double?): Boolean = rpe == null || rpe in values
}
