// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import kotlinx.coroutines.CancellationException

/** Persists the running guided session so it survives process death. */
interface GuidedStateStore {
    suspend fun load(): GuidedSnapshot?

    /** Null clears it. */
    suspend fun save(snapshot: GuidedSnapshot?)
}

/** Beep and vibration when a timed step runs out. Only called with the alarm on. */
interface GuidedAlerts {
    fun stepEnded()
}

/** Launcher shortcuts for pinned sessions, besides the static "Start workout" one. */
interface LauncherShortcuts {
    /**
     * Replaces every dynamic shortcut with these, in order; the platform may show fewer. False when the
     * launcher refused (rate-limited), so it can be tried again later.
     */
    fun setPinned(sessions: List<PinnedShortcut>): Boolean
}

data class PinnedShortcut(
    val planId: GuidedPlanId,
    val title: String,
)

/** Images that content files name (`images/<name>.webp`). */
interface ContentImages {
    /** The file's bytes, or null when it isn't there. */
    suspend fun load(path: String): ByteArray?
}

// Each path's bytes; an unreadable image is null and just isn't shown.
suspend fun ContentImages.loadAll(paths: List<String>): Map<String, ByteArray?> =
    paths.distinct().associateWith { path ->
        try {
            load(path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
