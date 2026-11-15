// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.day.BlockId
import app.fit.day.SetId
import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

// The rest after a set. It belongs to the session, not to the exercise on screen.
data class Rest(
    // The set that started it, and its block.
    val blockId: BlockId,
    val setId: SetId,
    val endsAt: Instant,
    // The top of the rest range: past endsAt the rest counts over until here. Null: until Skip.
    val maxAt: Instant? = null,
    // The alarm for endsAt went off; it rings once.
    val rang: Boolean = false,
) {
    // +15 s can push the end past the max, so the rest never stops before it rang.
    val stopsAt: Instant? get() = maxAt?.let { maxOf(it, endsAt) }
}

sealed interface HoldRun {
    data class Running(
        val endsAt: Instant,
    ) : HoldRun

    data class Paused(
        val remaining: Duration,
    ) : HoldRun
}

// A timed set counting down.
data class Hold(
    val setId: SetId,
    val blockId: BlockId,
    val length: Duration,
    // First start, kept through pauses: today's first logged set starts the day's clock here.
    val startedAt: Instant,
    val run: HoldRun,
)

// Everything about the running workout that survives process death; the rest is derived from the clock.
data class WorkoutSnapshot(
    // The workout's day: the position, rest, hold and notification belong to it.
    val date: LocalDate? = null,
    // The exercise last open in the focus view, for Resume.
    val blockId: BlockId? = null,
    val rest: Rest? = null,
    val hold: Hold? = null,
) {
    val timing: Boolean get() = rest != null || hold != null
}

data class WorkoutState(
    val snapshot: WorkoutSnapshot = WorkoutSnapshot(),
    // When this state was published; the ticker moves it on every second while a timer runs.
    val now: Instant = Instant.fromEpochMilliseconds(0),
    // For the notification: the exercise of the next set to do, and the one being held.
    val next: String? = null,
    val holding: String? = null,
    // The day's workout runs from Start or its first tick until Finish or Delete day.
    val running: Boolean = false,
    val startedAt: Instant? = null,
    val name: String = "",
) {
    // The notification shows while the workout runs, or while something is timed.
    val active: Boolean get() = running || snapshot.timing

    val rest: Rest? get() = snapshot.rest
    val hold: Hold? get() = snapshot.hold
}
