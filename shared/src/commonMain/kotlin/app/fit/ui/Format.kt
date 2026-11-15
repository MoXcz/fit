// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// "1:30–2:00", or one time when there's no top.
fun formatDurationRange(
    low: Duration,
    high: Duration?,
): String = if (high == null) formatClock(low) else "${formatClock(low)}–${formatClock(high)}"

// A whole-minute estimate: "49 min".
fun formatEstimate(duration: Duration): String = "${duration.inWholeMinutes} min"

// What a time field accepts: m:ss, h:mm:ss, or plain seconds ("90" is 1:30). Anything else is no value.
fun parseClock(text: String): Duration? {
    val parts = text.trim().split(':')
    if (parts.size > 3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) || it.length > 5 }) return null
    if (parts.size > 1 && parts.drop(1).any { it.length != 2 || it.toInt() > 59 }) return null
    return parts.fold(0L) { total, part -> total * 60 + part.toLong() }.seconds
}

// "20", "22.5" or "1.25": no trailing zeros.
fun formatNumber(value: Double): String {
    val hundredths = (value * 100).roundToLong()
    val whole = hundredths / 100
    val fraction = (hundredths % 100).toString().padStart(2, '0').trimEnd('0')
    return if (fraction.isEmpty()) whole.toString() else "$whole.$fraction"
}

// Whole numbers with a thousands separator: 4310.0 → "4,310".
fun formatThousands(value: Double): String =
    value
        .roundToLong()
        .toString()
        .reversed()
        .chunked(3)
        .joinToString(",")
        .reversed()

// What a weight or distance field accepts: a decimal comma or point; anything else is no value.
// "NaN" and "Infinity" parse as numbers too, and are no value either.
fun parseDecimal(text: String): Double? =
    text
        .trim()
        .replace(',', '.')
        .toDoubleOrNull()
        ?.takeIf { it.isFinite() }

fun formatRange(
    low: Int,
    high: Int?,
): String = if (high == null) "$low" else "$low–$high"

// Every time, as m:ss (and h:mm:ss past an hour): "0:45", "2:05", "1:02:05".
fun formatClock(duration: Duration): String {
    val total = duration.inWholeSeconds.coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = (total % 60).toString().padStart(2, '0')
    return if (hours > 0) "$hours:${minutes.toString().padStart(2, '0')}:$seconds" else "$minutes:$seconds"
}
