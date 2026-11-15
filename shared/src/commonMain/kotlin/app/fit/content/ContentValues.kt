// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val SLUG = Regex("[a-z0-9]+(-[a-z0-9]+)*")
private val DURATION = Regex("(?:(\\d+)m)?(?:(\\d+)s)?")
private val IMAGE = Regex("images/[a-z0-9][a-z0-9._-]*\\.(webp|png|jpg)")

internal fun isSlug(text: String): Boolean = SLUG.matches(text)

internal fun isImagePath(text: String): Boolean = IMAGE.matches(text)

internal fun isWebUrl(text: String): Boolean = text.startsWith("https://") || text.startsWith("http://")

// "45" (seconds), "45s", "2m" or "1m30s".
internal fun parseDuration(text: String): Duration? {
    val trimmed = text.trim()
    trimmed.toIntOrNull()?.let { return if (it >= 0) it.seconds else null }
    if (trimmed.isEmpty()) return null
    val match = DURATION.matchEntire(trimmed) ?: return null
    val (minutes, seconds) = match.destructured
    return (minutes.toIntOrNull() ?: 0).minutes + (seconds.toIntOrNull() ?: 0).seconds
}

// "8" or "8-12"; the second half is null for a single value and never below the first.
internal fun <T : Comparable<T>> parseRange(
    text: String,
    parse: (String) -> T?,
): Pair<T, T?>? {
    val parts = text.split('-')
    return when (parts.size) {
        1 -> {
            parse(parts[0])?.let { it to null }
        }

        2 -> {
            val low = parse(parts[0]) ?: return null
            val high = parse(parts[1]) ?: return null
            if (high < low) null else low to high.takeIf { it != low }
        }

        else -> {
            null
        }
    }
}
