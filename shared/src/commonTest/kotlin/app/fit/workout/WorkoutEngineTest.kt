// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayId
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class WorkoutEngineTest {
    private val now = Instant.parse("2026-11-09T10:00:00Z")

    private fun block(
        id: Long,
        rest: Duration = 90.seconds,
        restMax: Duration? = null,
        done: List<Boolean> = listOf(false),
    ) = Block(
        id = BlockId(id),
        exercise = bench,
        rest = rest,
        restMax = restMax,
        duration = 45.seconds,
        sets = done.mapIndexed { i, d -> SetEntry(SetId(id * 10 + i), targetReps = 8, doneAt = now.takeIf { d }) },
    )

    @Test
    fun `a rest of 0 s means no rest`() {
        assertNull(WorkoutEngine.rest(block(1, rest = Duration.ZERO), SetId(1), now))
    }

    @Test
    fun `a rest counts over until the top of its range, and has no top when the max isn't above the min`() {
        val ranged = WorkoutEngine.rest(block(1, rest = 90.seconds, restMax = 120.seconds), SetId(1), now)!!
        assertEquals(now + 90.seconds, ranged.endsAt)
        assertEquals(now + 120.seconds, ranged.stopsAt)
        assertNull(WorkoutEngine.rest(block(1, rest = 90.seconds, restMax = 90.seconds), SetId(1), now)!!.maxAt)
    }

    @Test
    fun `the rest rings at 0, counts over, and stops at the max`() {
        val rest = WorkoutEngine.rest(block(1, restMax = 120.seconds), SetId(1), now)!!
        assertFalse(WorkoutEngine.ringDue(rest, now + 89.seconds))
        assertTrue(WorkoutEngine.ringDue(rest, now + 90.seconds))
        val rang = rest.copy(rang = true)
        assertFalse(WorkoutEngine.ringDue(rang, now + 100.seconds))
        assertEquals(10.seconds, WorkoutEngine.restOver(rang, now + 100.seconds))
        assertEquals(Duration.ZERO, WorkoutEngine.restLeft(rang, now + 100.seconds))
        assertFalse(WorkoutEngine.stopDue(rang, now + 119.seconds))
        assertTrue(WorkoutEngine.stopDue(rang, now + 120.seconds))
    }

    @Test
    fun `a rest without a max never stops by itself`() {
        val rest = WorkoutEngine.rest(block(1), SetId(1), now)!!.copy(rang = true)
        assertFalse(WorkoutEngine.stopDue(rest, now + 3_600.seconds))
    }

    @Test
    fun `+15s never ends in the past and rings again once it ends later`() {
        val rest = WorkoutEngine.rest(block(1), SetId(1), now)!!
        assertEquals(now + 105.seconds, WorkoutEngine.addRest(rest, now).endsAt)
        // 30 s over: +15 s would still be over, so it ends now and doesn't ring again.
        val over = rest.copy(rang = true)
        val bumped = WorkoutEngine.addRest(over, now + 120.seconds)
        assertEquals(now + 120.seconds, bumped.endsAt)
        // 5 s over: +15 s ends 10 s from now and rings again then.
        val again = WorkoutEngine.addRest(over, now + 95.seconds)
        assertEquals(now + 105.seconds, again.endsAt)
        assertFalse(again.rang)
    }

    @Test
    fun `+15s past the max keeps the rest until it rang`() {
        val rest = WorkoutEngine.rest(block(1, rest = 90.seconds, restMax = 100.seconds), SetId(1), now)!!
        val longer = WorkoutEngine.addRest(rest, now)
        assertEquals(now + 105.seconds, longer.stopsAt)
    }

    @Test
    fun `hold pause keeps the remaining time and resume sets a new end`() {
        val hold = WorkoutEngine.startHold(SetId(1), BlockId(1), 40.seconds, now)
        val paused = WorkoutEngine.pause(hold, now + 10.seconds)
        assertEquals(30.seconds, WorkoutEngine.holdLeft(paused, now + 60.seconds))
        val resumed = WorkoutEngine.resume(paused, now + 60.seconds)
        assertEquals(HoldRun.Running(now + 90.seconds), resumed.run)
        assertEquals(20.seconds, WorkoutEngine.held(resumed, now + 70.seconds))
        assertFalse(WorkoutEngine.holdDue(paused, now + 1_000.seconds))
        assertTrue(WorkoutEngine.holdDue(resumed, now + 90.seconds))
    }

    @Test
    fun `the time held never goes past the hold's length or below 0`() {
        val hold = WorkoutEngine.startHold(SetId(1), BlockId(1), 40.seconds, now)
        assertEquals(40.seconds, WorkoutEngine.held(hold, now + 60.seconds))
        assertEquals(Duration.ZERO, WorkoutEngine.held(hold, now - 60.seconds))
    }

    @Test
    fun `the next wakeup is the hold's end, the rest's end, or its max once it rang`() {
        val rest = WorkoutEngine.rest(block(1, restMax = 120.seconds), SetId(1), now)!!
        assertEquals(now + 90.seconds, WorkoutEngine.nextWakeup(WorkoutSnapshot(rest = rest)))
        assertEquals(now + 120.seconds, WorkoutEngine.nextWakeup(WorkoutSnapshot(rest = rest.copy(rang = true))))
        assertNull(WorkoutEngine.nextWakeup(WorkoutSnapshot(rest = WorkoutEngine.rest(block(1), SetId(1), now)!!.copy(rang = true))))
        val hold = WorkoutEngine.startHold(SetId(1), BlockId(1), 40.seconds, now)
        assertEquals(now + 40.seconds, WorkoutEngine.nextWakeup(WorkoutSnapshot(hold = hold)))
        assertNull(WorkoutEngine.nextWakeup(WorkoutSnapshot(hold = WorkoutEngine.pause(hold, now))))
    }

    @Test
    fun `the next exercise is this one while it has sets left, else the next one with sets left`() {
        val day =
            WorkoutDay(
                DayId(1),
                LocalDate(2026, 11, 9),
                blocks =
                    listOf(
                        block(1, done = listOf(true, false)),
                        block(2, done = listOf(true, true)),
                        block(3, done = listOf(false)),
                        block(4, done = listOf(true)),
                    ),
            )
        assertEquals(BlockId(1), WorkoutEngine.nextBlock(day, BlockId(1))?.id)
        // The held set counts as done: after it comes the next exercise.
        assertEquals(BlockId(3), WorkoutEngine.nextBlock(day, BlockId(1), skip = SetId(11))?.id)
        // From nowhere, the first exercise with sets left.
        assertEquals(BlockId(1), WorkoutEngine.nextBlock(day, null)?.id)
        assertEquals(BlockId(3), WorkoutEngine.nextBlock(day, BlockId(2))?.id)
        // From the last exercise it wraps round to one left behind.
        assertEquals(BlockId(1), WorkoutEngine.nextBlock(day, BlockId(4))?.id)
        val allDone = day.copy(blocks = day.blocks.map { b -> b.copy(sets = b.sets.map { it.copy(doneAt = now) }) })
        assertNull(WorkoutEngine.nextBlock(allDone, BlockId(1)))
    }
}
