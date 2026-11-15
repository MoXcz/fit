// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.exercises.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class GuidedEngineTest {
    private val t0 = Instant.parse("2026-11-09T10:00:00Z")
    private val source = GuidedSource.Plan(GuidedPlanId(1))
    private val run = GuidedRun.of(recoveryPlan())

    private fun start(
        run: GuidedRun = this.run,
        from: Int = 0,
    ) = GuidedEngine.start(source, run, GuidedKind.Recovery, t0, from)

    private fun at(seconds: Int) = t0 + seconds.seconds

    private fun ends(snapshot: GuidedSnapshot) = (snapshot.clock as StepClock.Running).endsAt

    @Test
    fun `a step done on each side runs twice, and rounds repeat it side by side`() {
        // Wrist bends; then flexor stretch L, R, L, R (two rounds); then shake out.
        assertEquals(6, run.items.size)
        assertEquals(listOf(null, 1, 2, 1, 2, null), run.items.map { it.side })
        assertEquals(listOf(1, 1, 1, 2, 2, 1), run.items.map { it.round })
        assertEquals(listOf(10, null, null, null, null, null), run.items.map { it.reps })
    }

    @Test
    fun `an inline step without sides runs once`() {
        val plain = GuidedRun.of("x", listOf(GuidedStep("Shake out", duration = 15.seconds)), 5.seconds)
        assertEquals(listOf<Int?>(null), plain.items.map { it.side })
    }

    @Test
    fun `the plan's transition comes before every step and round, a short switch between sides`() {
        assertEquals(
            listOf(5.seconds, 5.seconds, GuidedRun.SWITCH_SIDES, 5.seconds, GuidedRun.SWITCH_SIDES, 5.seconds),
            run.items.indices.map(run::pauseBefore),
        )
        assertEquals(listOf(Interstitial.Transition, Interstitial.SwitchSides), listOf(run.interstitial(1), run.interstitial(2)))
        // A plan without transitions still switches sides with a pause.
        val none = GuidedRun.of(recoveryPlan(transition = 0))
        assertEquals(listOf(0, 0, 5, 0, 5, 0), none.items.indices.map { none.pauseBefore(it).inWholeSeconds.toInt() })
    }

    @Test
    fun `the total counts each item, a counted one at 3 s a rep, and every pause`() {
        // 30 s of reps + 4 × 30 s + 15 s, and 4 transitions of 5 s + 2 switches of 5 s.
        assertEquals((30 + 120 + 15 + 20 + 10).seconds, run.total)
    }

    @Test
    fun `a session starts in the transition before its first step, which then counts down`() {
        val started = start()
        assertEquals(GuidedPhase.Transition, started.phase)
        assertEquals(at(5), ends(started))
        val counted = GuidedEngine.expire(started, run, at(5), autoAdvance = true).snapshot
        assertEquals(GuidedPhase.Step, counted.phase)
        // The first step is counted: it waits for Done.
        assertEquals(StepClock.Waiting, counted.clock)
    }

    @Test
    fun `a transition of 0 s goes straight into the step`() {
        val none = GuidedRun.of(recoveryPlan(transition = 0))
        val started = GuidedEngine.start(source, none, GuidedKind.Recovery, t0, from = 1)
        assertEquals(GuidedPhase.Step, started.phase)
        assertEquals(at(30), ends(started))
    }

    @Test
    fun `Done on a counted step logs it and moves on to the next step's transition`() {
        val waiting = GuidedEngine.expire(start(), run, at(5), autoAdvance = true).snapshot
        val change = GuidedEngine.done(waiting, run, at(20))
        assertEquals(listOf<GuidedEvent>(GuidedEvent.Done(0, at(20))), change.events)
        assertEquals(setOf(0), change.snapshot.done)
        assertEquals(1, change.snapshot.index)
        assertEquals(GuidedPhase.Transition, change.snapshot.phase)
        assertEquals(at(25), ends(change.snapshot))
    }

    @Test
    fun `Done does nothing on a timed step or during a transition`() {
        val transition = start(from = 1)
        assertEquals(transition, GuidedEngine.done(transition, run, at(1)).snapshot)
        val timed = GuidedEngine.next(transition, run, at(1))
        assertEquals(timed, GuidedEngine.done(timed, run, at(2)).snapshot)
    }

    @Test
    fun `with auto-advance on a timed step ends and the next pause starts from its end, not from when it's noticed`() {
        val timed = GuidedEngine.next(start(from = 1), run, t0) // flexor L, ends at 30 s
        val change = GuidedEngine.expire(timed, run, at(31), autoAdvance = true)
        assertEquals(listOf<GuidedEvent>(GuidedEvent.Ended(1, at(30))), change.events)
        assertEquals(2, change.snapshot.index)
        assertEquals(GuidedPhase.Transition, change.snapshot.phase)
        // Switch sides from the end at 30 s.
        assertEquals(at(35), ends(change.snapshot))
    }

    @Test
    fun `with auto-advance off a timed step waits at 0 for Next`() {
        val timed = GuidedEngine.next(start(from = 1), run, t0)
        val change = GuidedEngine.expire(timed, run, at(90), autoAdvance = false)
        assertEquals(listOf<GuidedEvent>(GuidedEvent.Ended(1, at(30))), change.events)
        assertEquals(1, change.snapshot.index)
        assertEquals(StepClock.Waiting, change.snapshot.clock)
        assertEquals(setOf(1), change.snapshot.done)
        // Nothing more ends while it waits.
        assertEquals(change.snapshot, GuidedEngine.expire(change.snapshot, run, at(300), autoAdvance = false).snapshot)
        val next = GuidedEngine.next(change.snapshot, run, at(90))
        assertEquals(2, next.index)
        assertEquals(GuidedPhase.Transition, next.phase)
    }

    @Test
    fun `a run that went on with the process dead catches up end by end`() {
        val timed = GuidedEngine.next(start(from = 1), run, t0) // L ends 30, switch to 35, R ends 65, pause to 70, L ends 100
        val change = GuidedEngine.expire(timed, run, at(101), autoAdvance = true)
        assertEquals(listOf(at(30), at(65), at(100)), change.events.map { it.at })
        assertEquals(4, change.snapshot.index)
        assertEquals(at(105), ends(change.snapshot))
    }

    @Test
    fun `pause keeps what's left and resume sets a new end`() {
        val timed = GuidedEngine.next(start(from = 1), run, t0)
        val paused = GuidedEngine.pause(timed, at(10))
        assertEquals(StepClock.Paused(20.seconds), paused.clock)
        assertFalse(paused.timing)
        // A paused step never ends.
        assertEquals(paused, GuidedEngine.expire(paused, run, at(500), autoAdvance = true).snapshot)
        val resumed = GuidedEngine.resume(paused, at(500))
        assertEquals(at(520), ends(resumed))
        assertTrue(resumed.timing)
    }

    @Test
    fun `Next skips a transition into its step, and a step without logging it`() {
        val transition = start(from = 1)
        val step = GuidedEngine.next(transition, run, at(2))
        assertEquals(GuidedPhase.Step, step.phase)
        assertEquals(at(32), ends(step))
        val skipped = GuidedEngine.next(step, run, at(10))
        assertEquals(2, skipped.index)
        assertEquals(emptySet(), skipped.done)
    }

    @Test
    fun `Previous goes back into the item before, and on the first item starts it again`() {
        val second = GuidedEngine.next(start(from = 2), run, t0)
        val back = GuidedEngine.previous(second, run, at(4))
        assertEquals(1, back.index)
        assertEquals(GuidedPhase.Step, back.phase)
        assertEquals(at(34), ends(back))
        val first = GuidedEngine.expire(start(), run, at(5), autoAdvance = true).snapshot
        assertEquals(0, GuidedEngine.previous(first, run, at(6)).index)
        // Nothing before the first transition.
        assertEquals(start(), GuidedEngine.previous(start(), run, at(1)))
    }

    @Test
    fun `End stops the run with the items done`() {
        val waiting = GuidedEngine.expire(start(), run, at(5), autoAdvance = true).snapshot
        val after = GuidedEngine.done(waiting, run, at(20)).snapshot
        val ended = GuidedEngine.end(after, at(22))
        assertEquals(GuidedPhase.Finished, ended.phase)
        assertEquals(GuidedOutcome.Stopped, ended.outcome)
        assertEquals(at(22), ended.endedAt)
        val log = GuidedEngine.log(ended, kotlinx.datetime.LocalDate(2026, 11, 9))!!
        assertEquals(GuidedOutcome.Stopped, log.outcome)
        assertEquals(1, log.stepsDone)
        assertEquals("Wrist reset", log.title)
        assertEquals(GuidedPlanId(1), log.planId)
    }

    @Test
    fun `the last item ending completes the run`() {
        val last = GuidedEngine.next(start(from = 5), run, t0) // shake out, 15 s
        val change = GuidedEngine.expire(last, run, at(15), autoAdvance = true)
        assertEquals(GuidedPhase.Finished, change.snapshot.phase)
        assertEquals(GuidedOutcome.Completed, change.snapshot.outcome)
        assertEquals(at(15), change.snapshot.endedAt)
        assertEquals(setOf(5), change.snapshot.done)
        // Ended stays ended.
        assertEquals(change.snapshot, GuidedEngine.expire(change.snapshot, run, at(60), autoAdvance = true).snapshot)
        assertEquals(change.snapshot, GuidedEngine.end(change.snapshot, at(70)))
    }

    @Test
    fun `a warm-up never runs a done item again, so a revisit can't overwrite it`() {
        val warmup =
            GuidedRun(
                "Warm-up",
                listOf(GuidedItem("Plank", length = 30.seconds, done = true), GuidedItem("Jacks", length = 20.seconds)),
                transition = Duration.ZERO,
                keepsDone = true,
            )
        val second = GuidedEngine.start(GuidedSource.Warmup(kotlinx.datetime.LocalDate(2026, 11, 9)), warmup, null, t0, from = 1)
        val back = GuidedEngine.previous(second, warmup, at(5))
        assertEquals(0, back.index)
        assertEquals(StepClock.Waiting, back.clock)
        assertEquals(back, GuidedEngine.expire(back, warmup, at(600), autoAdvance = true).snapshot)
        // And no Done on it either.
        assertEquals(back, GuidedEngine.done(back, warmup, at(6)).snapshot)
        // A warm-up isn't logged.
        assertEquals(null, GuidedEngine.log(GuidedEngine.end(back, at(7)), kotlinx.datetime.LocalDate(2026, 11, 9)))
    }

    @Test
    fun `a recovery session runs a done item again when you go back to it`() {
        val first = GuidedEngine.next(start(from = 1), run, t0)
        val done = GuidedEngine.expire(first, run, at(31), autoAdvance = true).snapshot
        val back = GuidedEngine.previous(done, run, at(32))
        assertEquals(1, back.index)
        assertEquals(at(62), ends(back))
    }

    @Test
    fun `the next end is the running one, and none once paused, waiting or finished`() {
        assertEquals(at(5), GuidedEngine.nextEnd(start()))
        assertEquals(null, GuidedEngine.nextEnd(GuidedEngine.pause(start(), at(1))))
        assertEquals(null, GuidedEngine.nextEnd(GuidedEngine.expire(start(), run, at(5), autoAdvance = true).snapshot))
        assertEquals(null, GuidedEngine.nextEnd(GuidedEngine.end(start(), at(1))))
    }

    @Test
    fun `a session left still for 30 minutes ends itself, stopped, when it was last touched`() {
        // Waiting for Done on the first step from 5 s.
        val waiting = GuidedEngine.expire(start(), run, at(5), autoAdvance = true).snapshot
        assertEquals(at(5) + GuidedEngine.IDLE, GuidedEngine.idleAt(waiting))
        assertEquals(waiting, GuidedEngine.expire(waiting, run, at(5) + GuidedEngine.IDLE - 1.seconds, autoAdvance = true).snapshot)
        val ended = GuidedEngine.expire(waiting, run, at(5) + GuidedEngine.IDLE, autoAdvance = true).snapshot
        assertEquals(GuidedPhase.Finished, ended.phase)
        assertEquals(GuidedOutcome.Stopped, ended.outcome)
        assertEquals(at(5), ended.endedAt)
        assertEquals(null, GuidedEngine.idleAt(ended))
        // Counting down is never idle.
        assertEquals(null, GuidedEngine.idleAt(start()))
    }

    @Test
    fun `the idle deadline moves with every press`() {
        val paused = GuidedEngine.pause(start(from = 1), at(2))
        assertEquals(at(2) + GuidedEngine.IDLE, GuidedEngine.idleAt(paused))
        val skipped = GuidedEngine.next(paused, run, at(600))
        assertEquals(at(600) + GuidedEngine.IDLE, GuidedEngine.idleAt(skipped))
        // A run that waited at 0 with auto-advance off is idle from its end.
        val timed = GuidedEngine.next(start(from = 1), run, t0)
        val waiting = GuidedEngine.expire(timed, run, at(40), autoAdvance = false).snapshot
        assertEquals(at(30) + GuidedEngine.IDLE, GuidedEngine.idleAt(waiting))
    }

    @Test
    fun `skipping while paused enters the next step paused, so nothing starts counting`() {
        val paused = GuidedEngine.pause(GuidedEngine.next(start(from = 1), run, t0), at(10))
        val next = GuidedEngine.next(paused, run, at(20))
        assertEquals(2, next.index)
        assertEquals(StepClock.Paused(GuidedRun.SWITCH_SIDES), next.clock)
        assertFalse(next.timing)
        val into = GuidedEngine.next(next, run, at(21))
        assertEquals(StepClock.Paused(30.seconds), into.clock)
        val back = GuidedEngine.previous(into, run, at(22))
        assertEquals(StepClock.Paused(30.seconds), back.clock)
        assertEquals(1, back.index)
    }

    @Test
    fun `the time a run took leaves paused time out`() {
        val running = GuidedEngine.next(start(from = 1), run, t0)
        val paused = GuidedEngine.pause(running, at(10))
        // Ten minutes paused, skipping on while paused, then resumed.
        val skipped = GuidedEngine.next(paused, run, at(300))
        val resumed = GuidedEngine.resume(skipped, at(610))
        val ended = GuidedEngine.end(resumed, at(640))
        assertEquals(600.seconds, ended.pausedFor)
        assertEquals(40.seconds, GuidedEngine.active(ended))
        assertEquals(40.seconds, GuidedEngine.log(ended.copy(done = setOf(1)), kotlinx.datetime.LocalDate(2026, 11, 9))!!.duration)
        // A run that ended with no step done isn't logged.
        assertEquals(null, GuidedEngine.log(ended, kotlinx.datetime.LocalDate(2026, 11, 9)))
        // Ending while paused leaves the pause out too.
        val endedPaused = GuidedEngine.end(GuidedEngine.pause(running, at(10)), at(100))
        assertEquals(10.seconds, GuidedEngine.active(endedPaused))
    }

    @Test
    fun `a 0 s step can't exist, but a 0 s pause is none`() {
        val sides = GuidedRun.of("x", listOf(GuidedStep("A", duration = 10.seconds, sides = Sides.Each)), Duration.ZERO)
        val started = GuidedEngine.start(source, sides, GuidedKind.Recovery, t0)
        assertEquals(GuidedPhase.Step, started.phase)
        assertEquals(at(10), ends(started))
    }
}
