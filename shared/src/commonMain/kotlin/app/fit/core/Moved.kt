// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

// The list with the item at [from] moved [by] places (negative is towards the start), kept inside it.
fun <T> List<T>.moved(
    from: Int,
    by: Int,
): List<T> {
    if (from !in indices) return this
    val to = (from + by).coerceIn(indices)
    if (to == from) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
