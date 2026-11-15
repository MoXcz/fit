// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import kotlinx.datetime.LocalDate
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// Something the controller acts on: a timed item ran out (beep, and a warm-up logs its set), or a
// counted one was done.
sealed interface GuidedEvent {
    val index: Int
    val at: Instant

    data class Ended(
        override val index: Int,
        override val at: Instant,
    ) : GuidedEvent

    data class Done(
        override val index: Int,
        override val at: Instant,
    ) : GuidedEvent
}

data class GuidedChange(
    val snapshot: GuidedSnapshot,
    val events: List<GuidedEvent> = emptyList(),
)

// Pure rules of a guided run. Every function takes the instant and returns the new snapshot; ends are
// computed from the previous end, not from when they're noticed, so a session that ran with the
// process dead comes out the same.
object GuidedEngine {
    // A session left paused or waiting this long, with nothing pressed, ends itself.
    val IDLE: Duration = 30.minutes

    fun start(
        source: GuidedSource,
        run: GuidedRun,
        kind: GuidedKind?,
        now: Instant,
        from: Int = 0,
    ): GuidedSnapshot = arrive(GuidedSnapshot(source, run.title, kind, startedAt = now), run, from, now)

    // To [index]'s pause when it has one, else straight into it; past the last item the run is over.
    // [paused]: a paused session skipping on stays paused.
    fun arrive(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        index: Int,
        at: Instant,
        paused: Boolean = false,
    ): GuidedSnapshot {
        if (index >= run.items.size) return finish(snapshot, GuidedOutcome.Completed, at)
        val pause = run.pauseBefore(index)
        if (pause > Duration.ZERO) {
            val clock = if (paused) StepClock.Paused(pause) else StepClock.Running(at + pause)
            return settle(snapshot.copy(index = index, phase = GuidedPhase.Transition, clock = clock), at, paused)
        }
        return enter(snapshot, run, index, at, paused)
    }

    // A timed item counts down at once; a counted one waits for Done. A done warm-up item waits, so
    // running it again can't overwrite what it logged.
    fun enter(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        index: Int,
        at: Instant,
        paused: Boolean = false,
    ): GuidedSnapshot {
        val item = run.items[index]
        val clock =
            when {
                run.keepsDone && isDone(snapshot, run, index) -> StepClock.Waiting
                item.length == null -> StepClock.Waiting
                paused -> StepClock.Paused(item.length)
                else -> StepClock.Running(at + item.length)
            }
        return settle(snapshot.copy(index = index, phase = GuidedPhase.Step, clock = clock), at, paused && clock !is StepClock.Running)
    }

    // Still (paused or waiting) from [at], or counting down; a pause that's over counts as paused time.
    // [paused]: the session stays paused, also on a counted step a paused skip landed on.
    private fun settle(
        snapshot: GuidedSnapshot,
        at: Instant,
        paused: Boolean = snapshot.clock is StepClock.Paused,
    ): GuidedSnapshot {
        val pausedAt = snapshot.pausedAt
        val pausedFor =
            if (pausedAt != null &&
                !paused
            ) {
                snapshot.pausedFor + (at - pausedAt).coerceAtLeast(Duration.ZERO)
            } else {
                snapshot.pausedFor
            }
        return snapshot.copy(
            idleFrom = if (snapshot.clock is StepClock.Running) null else at,
            pausedAt = if (paused) pausedAt ?: at else null,
            pausedFor = pausedFor,
        )
    }

    fun isDone(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        index: Int,
    ): Boolean = index in snapshot.done || run.items.getOrNull(index)?.done == true

    // Every end up to [now], in order. With auto-advance off a timed item waits at 0 for Next. A run
    // left still for [IDLE] ends, as stopped, when it was last touched.
    fun expire(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        now: Instant,
        autoAdvance: Boolean,
    ): GuidedChange {
        var current = snapshot
        val events = mutableListOf<GuidedEvent>()
        while (!current.finished) {
            val end = (current.clock as? StepClock.Running)?.endsAt ?: break
            if (now < end) break
            current =
                when (current.phase) {
                    GuidedPhase.Transition -> {
                        enter(current, run, current.index, end)
                    }

                    else -> {
                        events += GuidedEvent.Ended(current.index, end)
                        val done = current.copy(done = current.done + current.index)
                        if (autoAdvance) arrive(done, run, current.index + 1, end) else settle(done.copy(clock = StepClock.Waiting), end)
                    }
                }
        }
        val idleAt = idleAt(current)
        if (idleAt != null && now >= idleAt) current = finish(current, GuidedOutcome.Stopped, idleAt - IDLE)
        return GuidedChange(current, events)
    }

    // Done on a counted item still to do; it moves on.
    fun done(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        now: Instant,
    ): GuidedChange {
        val item = run.items.getOrNull(snapshot.index) ?: return GuidedChange(snapshot)
        if (snapshot.phase != GuidedPhase.Step || item.timed || (run.keepsDone && isDone(snapshot, run, snapshot.index))) {
            return GuidedChange(snapshot)
        }
        val done = snapshot.copy(done = snapshot.done + snapshot.index)
        return GuidedChange(arrive(done, run, snapshot.index + 1, now), listOf(GuidedEvent.Done(snapshot.index, now)))
    }

    // Skips the pause into the item, or the item without logging it. A paused session stays paused.
    fun next(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        now: Instant,
    ): GuidedSnapshot {
        val paused = snapshot.pausedAt != null
        return when (snapshot.phase) {
            GuidedPhase.Transition -> enter(snapshot, run, snapshot.index, now, paused)
            GuidedPhase.Step -> arrive(snapshot, run, snapshot.index + 1, now, paused)
            GuidedPhase.Finished -> snapshot
        }
    }

    // Back to the item before, straight into it; on the first item it starts again. A paused session
    // stays paused.
    fun previous(
        snapshot: GuidedSnapshot,
        run: GuidedRun,
        now: Instant,
    ): GuidedSnapshot {
        if (snapshot.finished || (snapshot.phase == GuidedPhase.Transition && snapshot.index == 0)) return snapshot
        return enter(snapshot, run, (snapshot.index - 1).coerceAtLeast(0), now, paused = snapshot.pausedAt != null)
    }

    fun pause(
        snapshot: GuidedSnapshot,
        now: Instant,
    ): GuidedSnapshot {
        val clock = snapshot.clock as? StepClock.Running ?: return snapshot
        if (snapshot.finished) return snapshot
        return settle(snapshot.copy(clock = StepClock.Paused((clock.endsAt - now).coerceAtLeast(Duration.ZERO))), now)
    }

    fun resume(
        snapshot: GuidedSnapshot,
        now: Instant,
    ): GuidedSnapshot {
        val clock = snapshot.clock as? StepClock.Paused ?: return snapshot
        return settle(snapshot.copy(clock = StepClock.Running(now + clock.remaining)), now)
    }

    fun end(
        snapshot: GuidedSnapshot,
        now: Instant,
    ): GuidedSnapshot = if (snapshot.finished) snapshot else finish(snapshot, GuidedOutcome.Stopped, now)

    private fun finish(
        snapshot: GuidedSnapshot,
        outcome: GuidedOutcome,
        at: Instant,
    ): GuidedSnapshot {
        val settled = settle(snapshot.copy(clock = StepClock.Waiting), at)
        return settled.copy(phase = GuidedPhase.Finished, outcome = outcome, endedAt = at, idleFrom = null)
    }

    fun left(
        snapshot: GuidedSnapshot,
        now: Instant,
    ): Duration =
        when (val clock = snapshot.clock) {
            is StepClock.Running -> (clock.endsAt - now).coerceAtLeast(Duration.ZERO)
            is StepClock.Paused -> clock.remaining
            StepClock.Waiting -> Duration.ZERO
        }

    // When the running countdown ends.
    fun nextEnd(snapshot: GuidedSnapshot): Instant? = (snapshot.clock as? StepClock.Running)?.endsAt?.takeIf { !snapshot.finished }

    // When a still session ends itself.
    fun idleAt(snapshot: GuidedSnapshot): Instant? =
        snapshot.idleFrom?.takeIf { !snapshot.finished && snapshot.clock !is StepClock.Running }?.plus(IDLE)

    // The time it ran: from start to end, paused time left out.
    fun active(snapshot: GuidedSnapshot): Duration {
        val end = snapshot.endedAt ?: return Duration.ZERO
        return (end - snapshot.startedAt - snapshot.pausedFor).coerceAtLeast(Duration.ZERO)
    }

    // A run that ended with no step done isn't logged: nothing was done to show.
    fun log(
        snapshot: GuidedSnapshot,
        day: LocalDate,
    ): GuidedLog? {
        if (snapshot.done.isEmpty()) return null
        val kind = snapshot.kind ?: return null
        val outcome = snapshot.outcome ?: return null
        val endedAt = snapshot.endedAt ?: return null
        val planId = (snapshot.source as? GuidedSource.Plan)?.id
        return GuidedLog(planId, kind, snapshot.title, day, snapshot.startedAt, endedAt, outcome, snapshot.done.size, active(snapshot))
    }
}
