// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.day.SetEntry
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.routines.WARMUP_SECTION
import app.fit.workout.HoldRun
import app.fit.workout.TestClock
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class GuidedControllerTest {
    private val plan = GuidedPlanId(1)
    private val today = TestClock.TODAY

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun GuidedController.snapshot(): GuidedSnapshot = state.value.snapshot!!

    // Plank (2 × 40 s) and curl (2 × 10 reps) as the warm-up, bench after it.
    private suspend fun WorkoutFixture.warmupDay() {
        days.addExercise(today, plank.id, WARMUP_SECTION)
        days.addExercise(today, curl.id, WARMUP_SECTION)
        days.addExercise(today, bench.id)
    }

    private suspend fun WorkoutFixture.warmupSets(): List<SetEntry> =
        days
            .day(today)
            .first()!!
            .blocks
            .filter { it.section == WARMUP_SECTION }
            .flatMap { it.sets }

    @Test
    fun `starting a plan counts down its first transition and schedules a wakeup at its end`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            assertTrue(guided.startPlan(plan))
            runCurrent()
            assertTrue(guided.state.value.active)
            assertEquals(GuidedPhase.Transition, guided.snapshot().phase)
            assertEquals(f.clock.now() + 5.seconds, f.wakeups.guided)
            assertEquals(guided.snapshot(), f.guidedStore.stored)
        }

    @Test
    fun `a plan that isn't there doesn't start`() =
        runTest {
            val guided = WorkoutFixture(this).guided()
            assertFalse(guided.startPlan(GuidedPlanId(99)))
            assertNull(guided.state.value.snapshot)
        }

    @Test
    fun `each timed step end beeps once, with the alarm on, and the session runs on by itself`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(5.seconds)
            guided.done() // wrist bends
            advance(5.seconds + 30.seconds) // transition, then flexor L
            assertEquals(1, f.guidedAlerts.rung)
            assertEquals(2, guided.snapshot().index)
            assertEquals(GuidedPhase.Transition, guided.snapshot().phase)
            advance(5.seconds + 30.seconds) // switch sides, then R
            assertEquals(2, f.guidedAlerts.rung)
        }

    @Test
    fun `with the alarm off nothing is scheduled or rung, and the ticker still moves the session on`() =
        runTest {
            val f = WorkoutFixture(this)
            f.settings.setAlarm(false)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            guided.done()
            advance(5.seconds + 30.seconds)
            assertEquals(0, f.guidedAlerts.rung)
            // Only idle deadlines, which never ring: no step end was scheduled.
            assertTrue(f.wakeups.guidedHistory.all { it >= TestClock.START + GuidedEngine.IDLE })
            assertEquals(2, guided.snapshot().index)
        }

    @Test
    fun `turning the alarm off cancels the step's wakeup, and on schedules it again`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            runCurrent()
            assertNotNull(f.wakeups.guided)
            f.settings.setAlarm(false)
            runCurrent()
            assertNull(f.wakeups.guided)
            f.settings.setAlarm(true)
            runCurrent()
            assertEquals(TestClock.START + 5.seconds, f.wakeups.guided)
        }

    @Test
    fun `with auto-advance off a timed step waits at 0 for Next`() =
        runTest {
            val f = WorkoutFixture(this)
            f.settings.setAutoAdvance(false)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            guided.done()
            advance(5.seconds + 30.seconds + 2.minutes)
            assertEquals(1, guided.snapshot().index)
            assertEquals(StepClock.Waiting, guided.snapshot().clock)
            assertEquals(1, f.guidedAlerts.rung)
            guided.next()
            assertEquals(2, guided.snapshot().index)
        }

    @Test
    fun `expireIfDue is idempotent - only the first call after an end does anything`() =
        runTest {
            val f = WorkoutFixture(this)
            val process = CoroutineScope(SupervisorJob())
            val guided = f.guided(process = process)
            guided.startPlan(plan)
            guided.next()
            guided.done()
            guided.next() // into flexor L, ends in 30 s
            process.cancel() // no ticker: only expireIfDue moves it
            advanceTimeBy(31.seconds)
            val restarted = f.guided()
            val after = restarted.snapshot()
            assertFalse(restarted.expireIfDue())
            assertFalse(restarted.expireIfDue())
            assertEquals(after, restarted.snapshot())
            assertEquals(1, f.guidedAlerts.rung)
        }

    @Test
    fun `a session comes back after process death where the clock says it is`() =
        runTest {
            val f = WorkoutFixture(this)
            val process = CoroutineScope(SupervisorJob())
            val guided = f.guided(process = process)
            guided.startPlan(plan)
            guided.next()
            guided.done() // into the transition before flexor L
            process.cancel()
            // 5 s pause, L 30 s, switch 5 s, R 30 s: 2 s into the pause before round 2.
            advanceTimeBy(72.seconds)
            val restored = f.guided()
            val snapshot = restored.snapshot()
            assertEquals(3, snapshot.index)
            assertEquals(GuidedPhase.Transition, snapshot.phase)
            assertEquals(setOf(0, 1, 2), snapshot.done)
            assertEquals(StepClock.Running(TestClock.START + 75.seconds), snapshot.clock)
            assertTrue(restored.state.value.active)
        }

    @Test
    fun `End logs the run as stopped with the steps done, once`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            advance(10.seconds)
            guided.done()
            guided.end()
            guided.end()
            val log =
                f.plans.logged.value
                    .single()
            assertEquals(GuidedOutcome.Stopped, log.outcome)
            assertEquals(1, log.stepsDone)
            assertEquals(today, log.day)
            assertEquals(TestClock.START + 10.seconds, log.endedAt)
            assertFalse(guided.state.value.active)
            guided.dismiss()
            assertNull(guided.state.value.snapshot)
            assertNull(f.guidedStore.stored)
        }

    @Test
    fun `running to the end logs the run as completed`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(5.seconds)
            guided.done()
            advance(10.minutes)
            val snapshot = guided.snapshot()
            assertEquals(GuidedPhase.Finished, snapshot.phase)
            val log =
                f.plans.logged.value
                    .single()
            assertEquals(GuidedOutcome.Completed, log.outcome)
            assertEquals(6, log.stepsDone)
            assertEquals("Wrist reset", log.title)
            assertNull(f.wakeups.guided)
        }

    @Test
    fun `starting another session ends the running one, unlogged with no step done`() =
        runTest {
            val f = WorkoutFixture(this)
            f.plans.all.value += recoveryPlan(id = 2, title = "Neck")
            val guided = f.guided()
            guided.startPlan(plan)
            advance(3.seconds)
            guided.startPlan(GuidedPlanId(2))
            assertEquals(GuidedSource.Plan(GuidedPlanId(2)), guided.snapshot().source)
            assertEquals(emptyList(), f.plans.logged.value)
            // The same one again just keeps running.
            advance(1.seconds)
            val running = guided.snapshot()
            guided.startPlan(GuidedPlanId(2))
            assertEquals(running, guided.snapshot())
        }

    @Test
    fun `only one session times at once - a guided start ends the workout's rest and pauses its hold`() =
        runTest {
            val f = WorkoutFixture(this)
            val block = f.days.addExercise(today, bench.id)!!
            val sets =
                f.days
                    .dayOfBlock(block)
                    .first()!!
                    .blocks
                    .single()
                    .sets
            val workout = f.controller()
            val guided = f.guided()
            workout.setDone(block, sets[0].id, true)
            runCurrent()
            assertNotNull(workout.state.value.rest)
            guided.startPlan(plan)
            runCurrent()
            assertNull(workout.state.value.rest)
            assertTrue(guided.snapshot().timing)

            val hold = f.days.addExercise(today, plank.id)!!
            val holdSet =
                f.days
                    .dayOfBlock(hold)
                    .first()!!
                    .blocks
                    .first { it.id == hold }
                    .sets
                    .first()
            workout.startHold(hold, holdSet.id, 40.seconds)
            runCurrent()
            // The workout's hold took over: the session paused.
            assertTrue(guided.snapshot().clock is StepClock.Paused)
            guided.resume()
            runCurrent()
            assertTrue(
                workout.state.value.hold
                    ?.run is HoldRun.Paused,
            )
            assertTrue(guided.snapshot().timing)
        }

    @Test
    fun `the warm-up runner ticks every set of an item, not just the first, with no rest between items`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            val workout = f.controller()
            val guided = f.guided()
            assertTrue(guided.startWarmup(today))
            runCurrent()
            // No pause before the first item: plank set 1 counts down at once.
            assertEquals(GuidedPhase.Step, guided.snapshot().phase)
            advance(40.seconds) // plank 1 logged, plank 2 starts at once
            assertEquals(1, guided.snapshot().index)
            assertEquals(GuidedPhase.Step, guided.snapshot().phase)
            advance(40.seconds) // plank 2 logged
            guided.done() // curl 1
            guided.done() // curl 2
            val sets = f.warmupSets()
            assertEquals(listOf(true, true, true, true), sets.map { it.done })
            assertEquals(listOf(40.seconds, 40.seconds), sets.take(2).map { it.duration })
            assertEquals(listOf(10, 10), sets.drop(2).map { it.reps })
            assertEquals(GuidedPhase.Finished, guided.snapshot().phase)
            // The workout never rested between them, and a warm-up isn't a logged session.
            assertNull(workout.state.value.rest)
            assertEquals(emptyList(), f.plans.logged.value)
        }

    @Test
    fun `revisiting a finished warm-up item never runs it again or overwrites what it logged`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            val guided = f.guided()
            guided.startWarmup(today)
            advance(40.seconds)
            val logged = f.warmupSets().first()
            guided.previous()
            assertEquals(0, guided.snapshot().index)
            assertEquals(StepClock.Waiting, guided.snapshot().clock)
            advance(5.minutes)
            guided.done()
            assertEquals(logged, f.warmupSets().first())
            assertEquals(0, guided.snapshot().index)
        }

    @Test
    fun `the warm-up runner starts at the first item still to do`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            f.days.setDone(f.warmupSets().first().id, true)
            val guided = f.guided()
            guided.startWarmup(today)
            assertEquals(1, guided.snapshot().index)
        }

    @Test
    fun `Next skips a warm-up item without logging it, and Skip warm-up ends the runner with the rest undone`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            val guided = f.guided()
            guided.startWarmup(today)
            guided.next()
            assertEquals(1, guided.snapshot().index)
            assertFalse(f.warmupSets().first().done)
            guided.end()
            assertEquals(GuidedPhase.Finished, guided.snapshot().phase)
            assertEquals(listOf(false, false, false, false), f.warmupSets().map { it.done })
            assertEquals(emptyList(), f.plans.logged.value)
        }

    @Test
    fun `a day without a warm-up section has no warm-up to run`() =
        runTest {
            val f = WorkoutFixture(this)
            f.days.addExercise(today, bench.id)
            assertFalse(f.guided().startWarmup(today))
        }

    @Test
    fun `a session left still for 30 minutes ends itself, logged as stopped, with a wakeup at the deadline even with the alarm off`() =
        runTest {
            val f = WorkoutFixture(this)
            f.settings.setAlarm(false)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            guided.done() // one step done
            guided.next()
            guided.pause() // still from 0 s
            runCurrent()
            assertEquals(TestClock.START + GuidedEngine.IDLE, f.wakeups.guided)
            advance(GuidedEngine.IDLE)
            assertFalse(guided.state.value.active)
            val log =
                f.plans.logged.value
                    .single()
            assertEquals(GuidedOutcome.Stopped, log.outcome)
            assertEquals(1, log.stepsDone)
            assertEquals(TestClock.START, log.endedAt)
            assertEquals(0, f.guidedAlerts.rung)
            assertNull(f.wakeups.guided)
        }

    @Test
    fun `a session that idles out with no step done isn't logged`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next() // waiting for Done
            advance(GuidedEngine.IDLE)
            assertFalse(guided.state.value.active)
            assertEquals(emptyList(), f.plans.logged.value)
        }

    @Test
    fun `End with no step done logs nothing, End after one step logs it`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(10.seconds)
            guided.end()
            assertEquals(emptyList(), f.plans.logged.value)
            guided.startPlan(plan)
            guided.next()
            guided.done()
            guided.end()
            assertEquals(
                listOf(1),
                f.plans.logged.value
                    .map { it.stepsDone },
            )
        }

    @Test
    fun `the idle deadline moves with each press`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            advance(20.minutes)
            guided.done() // into the pause before the next step: counting again
            advance(5.seconds)
            guided.pause()
            advance(25.minutes)
            assertTrue(guided.state.value.active)
            advance(5.minutes)
            assertFalse(guided.state.value.active)
        }

    @Test
    fun `a session restored past its idle deadline is over, logged once`() =
        runTest {
            val f = WorkoutFixture(this)
            val process = CoroutineScope(SupervisorJob())
            val guided = f.guided(process = process)
            guided.startPlan(plan)
            guided.next()
            guided.done()
            guided.next()
            guided.pause()
            process.cancel()
            advanceTimeBy(2.hours)
            val restored = f.guided()
            assertFalse(restored.state.value.active)
            assertEquals(GuidedOutcome.Stopped, restored.snapshot().outcome)
            assertEquals(1, f.plans.logged.value.size)
            // And again: no second log, no revival.
            val again = f.guided()
            assertFalse(again.state.value.active)
            assertEquals(1, f.plans.logged.value.size)
        }

    @Test
    fun `a warm-up left idle ends without a log`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            f.days.addExercise(today, curl.id, WARMUP_SECTION)
            val guided = f.guided()
            guided.startWarmup(today)
            guided.pause()
            advance(GuidedEngine.IDLE)
            assertEquals(GuidedPhase.Finished, guided.snapshot().phase)
            assertEquals(emptyList(), f.plans.logged.value)
        }

    @Test
    fun `a warm-up restored the next day is dropped, logging nothing more`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            val process = CoroutineScope(SupervisorJob())
            f.guided(process = process).startWarmup(today)
            process.cancel()
            advanceTimeBy(24.hours) // the plank's end passed long ago, on another day
            val restored = f.guided()
            assertNull(restored.state.value.snapshot)
            assertEquals(listOf(false, false, false, false), f.warmupSets().map { it.done })
        }

    @Test
    fun `finishing the day ends its running warm-up`() =
        runTest {
            val f = WorkoutFixture(this)
            f.warmupDay()
            val guided = f.guided()
            guided.startWarmup(today)
            runCurrent()
            f.days.finish(today)
            runCurrent()
            assertNull(guided.state.value.snapshot)
            advance(2.minutes)
            assertEquals(listOf(false, false, false, false), f.warmupSets().map { it.done })
        }

    @Test
    fun `the log counts the time the session ran, not the time it was paused`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(2.seconds)
            guided.pause()
            advance(10.minutes)
            guided.resume()
            advance(3.seconds)
            guided.done() // the pause ran into the counted first step
            guided.end()
            assertEquals(
                5.seconds,
                f.plans.logged.value
                    .single()
                    .duration,
            )
        }

    @Test
    fun `a run is logged on the day it started, past midnight too`() =
        runTest {
            val f = WorkoutFixture(this, start = kotlin.time.Instant.parse("2026-11-09T23:59:00Z"))
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            guided.done()
            advance(5.minutes)
            guided.end()
            assertEquals(
                today,
                f.plans.logged.value
                    .single()
                    .day,
            )
        }

    @Test
    fun `an end noticed over a minute late moves on silently, one under a minute rings once`() =
        runTest {
            for ((late, rings) in listOf(2.minutes to 0, 30.seconds to 1)) {
                val f = WorkoutFixture(this)
                f.settings.setAutoAdvance(false)
                f.plans.all.value = listOf(recoveryPlan(transition = 0, steps = listOf(GuidedStep("Shake out", duration = 30.seconds))))
                val process = CoroutineScope(SupervisorJob())
                f.guided(process = process).startPlan(plan) // ends in 30 s
                process.cancel()
                advanceTimeBy(30.seconds + late)
                f.guided()
                assertEquals(rings, f.guidedAlerts.rung, "late by $late")
            }
        }

    @Test
    fun `turning auto-advance off or the alarm off mid-run takes effect at the next end`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next()
            guided.done()
            guided.next() // flexor L running
            f.settings.setAutoAdvance(false)
            f.settings.setAlarm(false)
            advance(31.seconds)
            assertEquals(1, guided.snapshot().index)
            assertEquals(StepClock.Waiting, guided.snapshot().clock)
            assertEquals(0, f.guidedAlerts.rung)
        }

    @Test
    fun `a workout rest started while a step waits for Done leaves it waiting, and Done takes the timers back`() =
        runTest {
            val f = WorkoutFixture(this)
            val block = f.days.addExercise(today, bench.id)!!
            val set =
                f.days
                    .dayOfBlock(block)
                    .first()!!
                    .blocks
                    .single()
                    .sets[0]
            val workout = f.controller()
            val guided = f.guided()
            guided.startPlan(plan)
            guided.next() // waiting for Done
            workout.setDone(block, set.id, true)
            runCurrent()
            assertEquals(StepClock.Waiting, guided.snapshot().clock)
            assertNotNull(workout.state.value.rest)
            guided.done() // the pause before the next step counts down
            runCurrent()
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `with no transition the step counts down at once, and a workout rest pauses it`() =
        runTest {
            val f = WorkoutFixture(this)
            f.plans.all.value = listOf(recoveryPlan(transition = 0, steps = listOf(GuidedStep("Shake out", duration = 15.seconds))))
            val block = f.days.addExercise(today, bench.id)!!
            val set =
                f.days
                    .dayOfBlock(block)
                    .first()!!
                    .blocks
                    .single()
                    .sets[0]
            val workout = f.controller()
            val guided = f.guided()
            guided.startPlan(plan)
            runCurrent()
            assertTrue(guided.snapshot().timing)
            workout.setDone(block, set.id, true)
            runCurrent()
            assertTrue(guided.snapshot().clock is StepClock.Paused)
        }

    @Test
    fun `skipping while paused stays paused and leaves the workout's rest alone`() =
        runTest {
            val f = WorkoutFixture(this)
            val block = f.days.addExercise(today, bench.id)!!
            val set =
                f.days
                    .dayOfBlock(block)
                    .first()!!
                    .blocks
                    .single()
                    .sets[0]
            val workout = f.controller()
            val guided = f.guided()
            guided.startPlan(plan)
            runCurrent()
            workout.setDone(block, set.id, true) // the rest pauses the session
            runCurrent()
            guided.next()
            guided.next()
            runCurrent()
            assertTrue(guided.snapshot().clock !is StepClock.Running)
            assertNotNull(workout.state.value.rest)
        }

    @Test
    fun `starting another session names the one with progress it ended, until the player has said so`() =
        runTest {
            val f = WorkoutFixture(this)
            f.plans.all.value += recoveryPlan(id = 2, title = "Neck")
            val guided = f.guided()
            guided.startPlan(plan)
            guided.startPlan(GuidedPlanId(2)) // nothing done yet: no notice
            assertNull(guided.state.value.replaced)
            guided.next()
            guided.done()
            guided.startPlan(plan)
            assertEquals("Neck", guided.state.value.replaced)
            assertEquals(
                listOf("Neck" to 1),
                f.plans.logged.value
                    .map { it.title to it.stepsDone },
            )
            guided.replacedSeen()
            assertNull(guided.state.value.replaced)
        }

    @Test
    fun `a timed warm-up slot with no length waits for Done instead of logging a 0 s hold`() =
        runTest {
            val f = WorkoutFixture(this)
            val hold =
                exercise(9, "Dead hang", tracks = Tracks(time = true)).let {
                    it.copy(fields = it.fields.copy(defaults = ExerciseDefaults(sets = 1, duration = Duration.ZERO)))
                }
            f.exercises.all.value += hold
            f.days.addExercise(today, hold.id, WARMUP_SECTION)
            val guided = f.guided()
            guided.startWarmup(today)
            runCurrent()
            assertEquals(StepClock.Waiting, guided.snapshot().clock)
            assertNull(
                guided.state.value.item
                    ?.length,
            )
            guided.done()
            assertTrue(f.warmupSets().first().done)
            assertEquals(GuidedPhase.Finished, guided.snapshot().phase)
        }

    @Test
    fun `a paused workout hold on a set the runner logs goes, with no rest`() =
        runTest {
            val f = WorkoutFixture(this)
            val block = f.days.addExercise(today, plank.id, WARMUP_SECTION)!!
            val set = f.warmupSets().first()
            val workout = f.controller()
            val guided = f.guided()
            workout.startHold(block, set.id, 40.seconds)
            runCurrent()
            guided.startWarmup(today) // the hold pauses
            runCurrent()
            assertTrue(
                workout.state.value.hold
                    ?.run is HoldRun.Paused,
            )
            advance(40.seconds) // the runner logs the set
            assertTrue(f.warmupSets().first().done)
            assertNull(workout.state.value.hold)
            assertNull(workout.state.value.rest)
            workout.completeHold()
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `closing a plan's session logs it as stopped and drops it, and another plan's session is left alone`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(6.seconds)
            guided.done()
            guided.close(GuidedPlanId(99))
            assertTrue(guided.state.value.active)

            guided.close(plan)
            runCurrent()
            assertNull(guided.state.value.snapshot)
            assertNull(f.guidedStore.stored)
            assertNull(f.wakeups.guided)
            assertEquals(
                listOf(GuidedOutcome.Stopped),
                f.plans.logged.value
                    .map { it.outcome },
            )
        }

    @Test
    fun `reset drops the session with nothing logged, its saved state and its wakeup`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            guided.startPlan(plan)
            advance(6.seconds)
            guided.done()
            assertNotNull(f.wakeups.guided)

            guided.reset()
            runCurrent()
            assertNull(guided.state.value.snapshot)
            assertNull(f.guidedStore.stored)
            assertNull(f.wakeups.guided)
            assertEquals(emptyList(), f.plans.logged.value)
            // Nothing of it comes back with the process.
            assertNull(
                f
                    .guided(process = backgroundScope)
                    .state.value.snapshot,
            )
        }
}
