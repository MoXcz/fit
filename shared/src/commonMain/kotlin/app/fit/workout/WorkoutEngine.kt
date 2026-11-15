// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Pure rest and hold rules. Every function takes the current instant and returns new values; nothing
// counts down, so the result is the same whether the process lived through it or not.
object WorkoutEngine {
    val EXTRA_REST: Duration = 15.seconds

    // A workout with no set ticked for this long has been left; its notification goes (the day
    // stays unfinished).
    val IDLE: Duration = 2.hours

    // When an unfinished workout stops running: 2 h after its last done set, or after its start.
    fun idleAt(day: WorkoutDay): Instant? {
        if (day.finished) return null
        val from = day.lastDoneAt ?: day.startedAt ?: return null
        return from + IDLE
    }

    // A rest of 0 s means no rest.
    fun rest(
        block: Block,
        setId: SetId,
        now: Instant,
    ): Rest? {
        if (block.rest <= Duration.ZERO) return null
        val max = block.restMax?.takeIf { it > block.rest }
        return Rest(block.id, setId, endsAt = now + block.rest, maxAt = max?.let { now + it })
    }

    fun restLeft(
        rest: Rest,
        now: Instant,
    ): Duration = (rest.endsAt - now).coerceAtLeast(Duration.ZERO)

    // How long past the planned end, once it's over.
    fun restOver(
        rest: Rest,
        now: Instant,
    ): Duration = (now - rest.endsAt).coerceAtLeast(Duration.ZERO)

    // +15 s never ends in the past, and rings again if it had rung and now ends later.
    fun addRest(
        rest: Rest,
        now: Instant,
        extra: Duration = EXTRA_REST,
    ): Rest {
        val endsAt = maxOf(now, rest.endsAt + extra)
        return rest.copy(endsAt = endsAt, rang = rest.rang && endsAt <= now)
    }

    fun ringDue(
        rest: Rest,
        now: Instant,
    ): Boolean = !rest.rang && now >= rest.endsAt

    fun stopDue(
        rest: Rest,
        now: Instant,
    ): Boolean = rest.stopsAt?.let { now >= it } ?: false

    fun startHold(
        setId: SetId,
        blockId: BlockId,
        length: Duration,
        now: Instant,
    ): Hold = Hold(setId, blockId, length, startedAt = now, run = HoldRun.Running(now + length))

    fun holdLeft(
        hold: Hold,
        now: Instant,
    ): Duration =
        when (val run = hold.run) {
            // Capped above too: the wall clock can be set back.
            is HoldRun.Running -> (run.endsAt - now).coerceIn(Duration.ZERO, hold.length)

            is HoldRun.Paused -> run.remaining
        }

    // The time actually held, which is what a timed set logs.
    fun held(
        hold: Hold,
        now: Instant,
    ): Duration = hold.length - holdLeft(hold, now)

    fun pause(
        hold: Hold,
        now: Instant,
    ): Hold = if (hold.run is HoldRun.Running) hold.copy(run = HoldRun.Paused(holdLeft(hold, now))) else hold

    fun resume(
        hold: Hold,
        now: Instant,
    ): Hold =
        when (val run = hold.run) {
            is HoldRun.Paused -> hold.copy(run = HoldRun.Running(now + run.remaining))
            is HoldRun.Running -> hold
        }

    fun holdDue(
        hold: Hold,
        now: Instant,
    ): Boolean {
        val run = hold.run
        return run is HoldRun.Running && now >= run.endsAt
    }

    // When something next needs deciding: a hold's end, a rest's end, or the top of its range.
    fun nextWakeup(snapshot: WorkoutSnapshot): Instant? {
        val hold = snapshot.hold?.run as? HoldRun.Running
        val rest = snapshot.rest
        return listOfNotNull(
            hold?.endsAt,
            rest?.let { if (it.rang) it.stopsAt else it.endsAt },
        ).minOrNull()
    }

    // The exercise of the next set to do from [from]: the same one while it has sets left, else the
    // next one with sets left, else an earlier one. [skip] counts as done (the set being held). As
    // everywhere, only working sets count: a skipped warm-up isn't what's next.
    fun nextBlock(
        day: WorkoutDay,
        from: BlockId?,
        skip: SetId? = null,
    ): Block? {
        val index = day.blocks.indexOfFirst { it.id == from }
        val order = if (index < 0) day.blocks else day.blocks.drop(index) + day.blocks.take(index)
        return order.firstOrNull { block -> block.workingSets.any { !it.done && it.id != skip } }
    }
}
