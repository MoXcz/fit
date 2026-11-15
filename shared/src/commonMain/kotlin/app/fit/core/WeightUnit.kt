// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

// A unit values are only ever compared within: Fit never converts.
sealed interface MeasureUnit {
    val symbol: String
}

enum class WeightUnit(
    override val symbol: String,
) : MeasureUnit {
    Kg("kg"),
    Lb("lb"),
}

enum class DistanceUnit(
    override val symbol: String,
) : MeasureUnit {
    Km("km"),
    Mi("mi"),
    M("m"),
}
