// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Instant

// What a guided session runs: a plan, or a day's warm-up section.
sealed interface GuidedSource {
    data class Plan(
        val id: GuidedPlanId,
    ) : GuidedSource

    data class Warmup(
        val date: LocalDate,
    ) : GuidedSource
}

enum class GuidedPhase { Transition, Step, Finished }

sealed interface StepClock {
    data class Running(
        val endsAt: Instant,
    ) : StepClock

    data class Paused(
        val remaining: Duration,
    ) : StepClock

    // A counted step waiting for Done, a timed step waiting at 0 for Next, or a done warm-up item.
    data object Waiting : StepClock
}

// Everything about the running session that survives process death; the rest comes from the clock.
data class GuidedSnapshot(
    val source: GuidedSource,
    // Kept for the log, so a plan that changed or went still logs what ran.
    val title: String,
    // Null for a warm-up, which isn't logged.
    val kind: GuidedKind?,
    val startedAt: Instant,
    val index: Int = 0,
    val phase: GuidedPhase = GuidedPhase.Transition,
    val clock: StepClock = StepClock.Waiting,
    // Items finished: ran to 0, or Done.
    val done: Set<Int> = emptySet(),
    val outcome: GuidedOutcome? = null,
    val endedAt: Instant? = null,
    // Since when it's been still (paused, or waiting for Done or Next) with nothing pressed; null
    // while it counts down. Left still for GuidedEngine.IDLE, it ends itself.
    val idleFrom: Instant? = null,
    // When it was paused; the paused time goes into [pausedFor] once it moves again.
    val pausedAt: Instant? = null,
    val pausedFor: Duration = Duration.ZERO,
) {
    val finished: Boolean get() = phase == GuidedPhase.Finished

    // Counting down; a paused or waiting session isn't.
    val timing: Boolean get() = !finished && clock is StepClock.Running
}

data class GuidedState(
    val snapshot: GuidedSnapshot? = null,
    val run: GuidedRun? = null,
    // When this state was published; the ticker moves it on while something counts down.
    val now: Instant = Instant.fromEpochMilliseconds(0),
    // The title of a session with progress that the last start ended; the player says so once.
    val replaced: String? = null,
) {
    // The notification shows while a session runs, paused or not, until it ends.
    val active: Boolean get() = snapshot != null && run != null && !snapshot.finished

    val item: GuidedItem? get() = snapshot?.let { run?.items?.getOrNull(it.index) }

    // The item after the current one, or the current one during its transition.
    val upcoming: GuidedItem?
        get() {
            val snapshot = snapshot ?: return null
            val next = if (snapshot.phase == GuidedPhase.Transition) snapshot.index else snapshot.index + 1
            return run?.items?.getOrNull(next)
        }

    val left: Duration get() = snapshot?.let { GuidedEngine.left(it, now) } ?: Duration.ZERO
}
