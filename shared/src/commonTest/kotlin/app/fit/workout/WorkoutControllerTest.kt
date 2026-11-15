// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.TestClock.Companion.TODAY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutControllerTest {
    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private suspend fun WorkoutFixture.day(date: LocalDate = TODAY): WorkoutDay = days.day(date).first()!!

    private suspend fun WorkoutFixture.block(
        id: BlockId,
        date: LocalDate = TODAY,
    ): Block = day(date).blocks.first { it.id == id }

    private suspend fun WorkoutFixture.set(
        id: BlockId,
        index: Int,
    ): SetEntry = block(id).sets[index]

    // Today with bench, plank and curl.
    private suspend fun WorkoutFixture.today(): List<BlockId> = listOf(bench, plank, curl).map { days.addExercise(TODAY, it.id)!! }

    private suspend fun WorkoutController.tick(
        fixture: WorkoutFixture,
        block: BlockId,
        index: Int = 0,
    ) = setDone(block, fixture.set(block, index).id, true)

    @Test
    fun `completing a set starts the rest from its block's rest and range, and starts today's clock`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            val rest = assertNotNull(workout.state.value.rest)
            assertEquals(START + 90.seconds, rest.endsAt)
            assertEquals(START + 120.seconds, rest.maxAt)
            assertEquals(START + 90.seconds, f.wakeups.scheduled)
            assertTrue(f.set(benchId, 0).done)
            assertEquals(START, f.day().startedAt)
            assertEquals(rest, f.store.stored?.rest)
        }

    @Test
    fun `the rest rings once at 0, counts over, and stops itself at the max`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(89.seconds)
            assertTrue(f.alerts.rung.isEmpty())
            advance(1.seconds)
            assertEquals(listOf("rest:Bench press"), f.alerts.rung)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            assertEquals(START + 120.seconds, f.wakeups.scheduled)
            advance(15.seconds)
            assertEquals(START + 105.seconds, workout.state.value.now)
            assertNotNull(workout.state.value.rest)
            advance(15.seconds)
            assertNull(workout.state.value.rest)
            assertEquals(START + 2.hours, f.wakeups.scheduled)
            assertEquals(1, f.alerts.rung.size)
        }

    @Test
    fun `a rest with no max counts over until Skip`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.setDone(plankId, f.set(plankId, 0).id, true)
            advance(10.seconds * 60)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            assertEquals(START + 2.hours, f.wakeups.scheduled)
            workout.skipRest()
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `+15s moves the end on and re-arms the alarm`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            workout.addRest()
            assertEquals(
                START + 105.seconds,
                workout.state.value.rest!!
                    .endsAt,
            )
            assertEquals(START + 105.seconds, f.wakeups.scheduled)
            advance(95.seconds)
            assertTrue(f.alerts.rung.isEmpty())
            advance(10.seconds)
            assertEquals(1, f.alerts.rung.size)
            // 5 s over: +15 s rings again 10 s from now.
            advance(5.seconds)
            workout.addRest()
            assertFalse(
                workout.state.value.rest!!
                    .rang,
            )
            assertEquals(START + 120.seconds, f.wakeups.scheduled)
            advance(10.seconds)
            assertEquals(2, f.alerts.rung.size)
            // It was pushed to the max, so it rang and stopped together.
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `Skip stops the rest and cancels its wakeup`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            workout.skipRest()
            assertNull(workout.state.value.rest)
            assertNull(f.wakeups.scheduled)
            advance(200.seconds)
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `a 0 s rest starts nothing`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, _, curlId) = f.today()
            val workout = f.controller()
            workout.tick(f, curlId)
            assertTrue(f.set(curlId, 0).done)
            assertNull(workout.state.value.rest)
            assertTrue(f.wakeups.history.isEmpty())
            advance(10.seconds)
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `rest survives moving to another exercise`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId, plankId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, benchId)
            workout.tick(f, benchId)
            val rest = workout.state.value.rest
            workout.open(TODAY, plankId)
            assertEquals(rest, workout.state.value.rest)
            assertEquals(plankId, workout.position(TODAY))
            advance(90.seconds)
            assertEquals(1, f.alerts.rung.size)
        }

    @Test
    fun `rest survives the process being killed and still rings`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            f.controller(process = process).tick(f, benchId)
            advance(30.seconds)
            process.cancel()
            // A new process: a new controller over the same stored snapshot.
            val restored = f.controller()
            assertEquals(
                START + 90.seconds,
                restored.state.value.rest
                    ?.endsAt,
            )
            assertEquals(START + 90.seconds, f.wakeups.scheduled)
            advance(60.seconds)
            assertTrue(
                restored.state.value.rest!!
                    .rang,
            )
            assertEquals(listOf("rest:Bench press"), f.alerts.rung)
        }

    @Test
    fun `the rest's Next names the exercise of the next set, not the one just done`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId, 0)
            runCurrent()
            assertEquals("Bench press", workout.state.value.next)
            workout.tick(f, benchId, 1)
            workout.tick(f, benchId, 2)
            runCurrent()
            assertEquals("Plank", workout.state.value.next)
            advance(90.seconds)
            assertEquals(listOf("rest:Plank"), f.alerts.rung)
        }

    @Test
    fun `hold pause and resume keep the time left, and Stop logs nothing`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            val set = f.set(plankId, 0)
            workout.startHold(plankId, set.id, 40.seconds)
            runCurrent()
            assertEquals("Plank", workout.state.value.holding)
            assertEquals(START + 40.seconds, f.wakeups.scheduled)
            advance(10.seconds)
            workout.pauseHold()
            assertNull(f.wakeups.scheduled)
            advance(60.seconds)
            workout.resumeHold()
            assertEquals(
                HoldRun.Running(START + 100.seconds),
                workout.state.value.hold!!
                    .run,
            )
            workout.stopHold()
            assertNull(workout.state.value.hold)
            assertNull(workout.state.value.rest)
            assertFalse(f.set(plankId, 0).done)
            assertNull(f.set(plankId, 0).duration)
            advance(60.seconds)
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `Complete set during a hold logs the time held and starts the rest`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(25.seconds)
            workout.completeHold()
            val done = f.set(plankId, 0)
            assertTrue(done.done)
            assertEquals(25.seconds, done.duration)
            assertNull(workout.state.value.hold)
            assertEquals(
                START + 55.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `ticking the held set in All sets also logs the time held`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(12.seconds)
            workout.tick(f, plankId, 0)
            assertEquals(12.seconds, f.set(plankId, 0).duration)
            assertNull(workout.state.value.hold)
        }

    @Test
    fun `a hold that runs out logs its whole length at its end, rests, and rings`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(10.seconds)
            workout.pauseHold()
            advance(5.seconds)
            workout.resumeHold()
            advance(30.seconds)
            val done = f.set(plankId, 0)
            assertEquals(40.seconds, done.duration)
            assertEquals(START + 45.seconds, done.doneAt)
            assertEquals(listOf("hold:Plank"), f.alerts.rung)
            assertEquals(
                START + 75.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
        }

    @Test
    fun `a hold that ended while the app was away is logged once on restore, with the time it ran`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val set = f.set(plankId, 0)
            val stale =
                WorkoutSnapshot(
                    date = TODAY,
                    hold = Hold(set.id, plankId, 40.seconds, START, HoldRun.Running(START + 40.seconds)),
                )
            advance(50.seconds)
            val first = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            f.controller(FakeWorkoutStateStore(stale), first)
            val logged = f.set(plankId, 0)
            assertEquals(40.seconds, logged.duration)
            assertEquals(START + 40.seconds, logged.doneAt)
            first.cancel()
            // The process died again before the snapshot was saved: restoring the same one logs nothing new.
            val second = f.controller(FakeWorkoutStateStore(stale))
            assertEquals(logged, f.set(plankId, 0))
            assertFalse(f.set(plankId, 1).done)
            // Its rest started at the hold's end.
            assertEquals(
                START + 70.seconds,
                second.state.value.rest
                    ?.endsAt,
            )
        }

    @Test
    fun `expireIfDue is idempotent`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            // Due now, but the ticker hasn't run yet: the ticker and three wakeups race for it.
            advanceTimeBy(40.seconds)
            val results = List(3) { async { workout.expireIfDue() } }.awaitAll()
            runCurrent()
            assertTrue(results.count { it } <= 1)
            assertFalse(workout.expireIfDue())
            assertEquals(listOf("hold:Plank"), f.alerts.rung)
            assertEquals(40.seconds, f.set(plankId, 0).duration)
            assertEquals(
                START + 70.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
        }

    @Test
    fun `only today runs timers`() =
        runTest {
            val f = WorkoutFixture(this)
            val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
            val benchYesterday = f.days.addExercise(yesterday, bench.id)!!
            val plankYesterday = f.days.addExercise(yesterday, plank.id)!!
            val workout = f.controller()
            workout.setDone(benchYesterday, f.block(benchYesterday, yesterday).sets[0].id, true)
            assertTrue(
                f
                    .block(benchYesterday, yesterday)
                    .sets[0]
                    .done,
            )
            assertNull(workout.state.value.rest)
            workout.startHold(plankYesterday, f.block(plankYesterday, yesterday).sets[0].id, 40.seconds)
            assertNull(workout.state.value.hold)
            assertNull(
                f.days
                    .day(yesterday)
                    .first()!!
                    .startedAt,
            )
            workout.open(yesterday, benchYesterday)
            assertNull(workout.position(yesterday))
            assertTrue(f.wakeups.history.isEmpty())
        }

    @Test
    fun `a finished day runs no timers, and finishing or deleting today drops its rest and hold`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId, plankId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            f.days.finish(TODAY)
            runCurrent()
            assertNull(workout.state.value.rest)
            assertNull(f.wakeups.scheduled)
            workout.tick(f, benchId, 1)
            assertNull(workout.state.value.rest)
            f.days.reopen(TODAY)
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            assertNotNull(workout.state.value.hold)
            f.days.deleteDay(TODAY)
            runCurrent()
            assertNull(workout.state.value.hold)
        }

    @Test
    fun `unticking a set stops the rest it started`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            workout.setDone(benchId, f.set(benchId, 0).id, false)
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `with the alarm off nothing is scheduled and nothing rings, but the rest still counts`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId, plankId) = f.today()
            f.settings.setAlarm(false)
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(100.seconds)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            workout.skipRest()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(40.seconds)
            assertTrue(f.set(plankId, 0).done)
            // Only the idle deadline, which never rings.
            assertTrue(f.wakeups.history.all { it >= START + 2.hours })
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `turning the alarm off mid-rest cancels the wakeup`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            f.settings.setAlarm(false)
            runCurrent()
            assertEquals(START + 2.hours, f.wakeups.scheduled)
            advance(90.seconds)
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `ticking another set during a hold records it and keeps the hold, without a rest`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            workout.tick(f, benchId)
            assertTrue(f.set(benchId, 0).done)
            assertNotNull(workout.state.value.hold)
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `a removed set takes its hold with it`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            val set = f.set(plankId, 0)
            workout.startHold(plankId, set.id, 40.seconds)
            f.days.removeSet(set.id)
            runCurrent()
            assertNull(workout.state.value.hold)
        }

    @Test
    fun `a failing restore starts fresh`() =
        runTest {
            val f = WorkoutFixture(this)
            val failing =
                object : WorkoutStateStore by f.store {
                    override suspend fun load(): WorkoutSnapshot? = throw IllegalStateException("corrupt")
                }
            val workout = f.controller(failing)
            assertNull(workout.position(TODAY))
            assertEquals(WorkoutSnapshot(), workout.state.value.snapshot)
        }

    // A: the workout itself

    @Test
    fun `the workout runs from its first tick until Finish, and Next always names what comes next`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, benchId)
            runCurrent()
            assertFalse(workout.state.value.active)
            workout.tick(f, benchId)
            runCurrent()
            assertTrue(workout.state.value.running)
            assertEquals(START, workout.state.value.startedAt)
            workout.skipRest()
            runCurrent()
            // Nothing timed: still running, and Next is the exercise of the next set.
            assertTrue(workout.state.value.active)
            assertEquals("Bench press", workout.state.value.next)
            f.days.finish(TODAY)
            runCurrent()
            assertFalse(workout.state.value.active)
        }

    @Test
    fun `Start from Today runs the workout once the focus view opens, and Delete day ends it`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            f.days.start(TODAY)
            workout.open(TODAY, benchId)
            runCurrent()
            assertTrue(workout.state.value.running)
            assertNull(workout.state.value.rest)
            assertEquals("Bench press", workout.state.value.next)
            f.days.deleteDay(TODAY)
            runCurrent()
            assertFalse(workout.state.value.active)
        }

    // B: late ends

    private fun WorkoutFixture.rest(
        block: BlockId,
        set: SetEntry,
        endsAt: Instant,
    ) = WorkoutSnapshot(date = TODAY, rest = Rest(block, set.id, endsAt))

    @Test
    fun `a rest that ended under a minute before restore rings`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            f.controller(FakeWorkoutStateStore(f.rest(plankId, f.set(plankId, 0), START - 30.seconds)))
            assertEquals(listOf("rest:Plank"), f.alerts.rung)
        }

    @Test
    fun `a rest that ended over a minute before restore shows as over without ringing`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller(FakeWorkoutStateStore(f.rest(plankId, f.set(plankId, 0), START - 2.minutes)))
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `a hold and its rest that both ended long before restore are logged and shown, silently`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val set = f.set(plankId, 0)
            val stale =
                WorkoutSnapshot(
                    date = TODAY,
                    hold = Hold(set.id, plankId, 40.seconds, START - 300.seconds, HoldRun.Running(START - 260.seconds)),
                )
            val workout = f.controller(FakeWorkoutStateStore(stale))
            assertEquals(40.seconds, f.set(plankId, 0).duration)
            assertEquals(START - 260.seconds, f.set(plankId, 0).doneAt)
            assertEquals(
                START - 230.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `a hold that ended seconds before restore is logged and rings`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val set = f.set(plankId, 0)
            val stale =
                WorkoutSnapshot(
                    date = TODAY,
                    hold = Hold(set.id, plankId, 40.seconds, START - 50.seconds, HoldRun.Running(START - 10.seconds)),
                )
            val workout = f.controller(FakeWorkoutStateStore(stale))
            assertTrue(f.set(plankId, 0).done)
            assertEquals(listOf("hold:Plank"), f.alerts.rung)
            assertEquals(
                START + 20.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
        }

    @Test
    fun `restore never shows a rest that ended while the process was dead`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val over = Rest(benchId, f.set(benchId, 0).id, START - 200.seconds, maxAt = START - 100.seconds)
            val store = FakeWorkoutStateStore(WorkoutSnapshot(date = TODAY, rest = over))
            val workout =
                WorkoutController(f.days, f.settings, store, f.wakeups, f.alerts, f.timers, f.clock, TimeZone.UTC, backgroundScope)
            val seen = mutableListOf<WorkoutState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { workout.state.collect { seen += it } }
            runCurrent()
            assertTrue(seen.none { it.rest != null })
            assertNull(store.stored?.rest)
        }

    // C: warm-ups

    @Test
    fun `a warm-up set starts no rest and leaves a running one alone`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId, plankId) = f.today()
            f.days.addWarmups(benchId)
            val (first, second) = f.block(benchId).sets.filter { it.warmup }
            val workout = f.controller()
            workout.setDone(benchId, first.id, true)
            assertTrue(
                f
                    .block(benchId)
                    .sets
                    .first { it.id == first.id }
                    .done,
            )
            assertNull(workout.state.value.rest)
            workout.setDone(plankId, f.set(plankId, 0).id, true)
            val rest = workout.state.value.rest
            workout.setDone(benchId, second.id, true)
            assertEquals(rest, workout.state.value.rest)
        }

    // Review fixes

    @Test
    fun `only one hold runs at a time`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            val first = f.set(plankId, 0)
            workout.startHold(plankId, first.id, 40.seconds)
            workout.startHold(plankId, f.set(plankId, 1).id, 40.seconds)
            assertEquals(
                first.id,
                workout.state.value.hold
                    ?.setId,
            )
        }

    @Test
    fun `a rest started before midnight rings after it`() =
        runTest {
            val f = WorkoutFixture(this, Instant.parse("2026-11-09T23:59:30Z"))
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(90.seconds)
            assertEquals(listOf("rest:Bench press"), f.alerts.rung)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
        }

    @Test
    fun `a hold started before midnight is logged against its own day and rings, with no new rest`() =
        runTest {
            val f = WorkoutFixture(this, Instant.parse("2026-11-09T23:59:30Z"))
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(40.seconds)
            assertEquals(40.seconds, f.set(plankId, 0).duration)
            assertEquals(listOf("hold:Plank"), f.alerts.rung)
            assertNull(workout.state.value.hold)
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `ticking the held set after midnight completes the hold`() =
        runTest {
            val f = WorkoutFixture(this, Instant.parse("2026-11-09T23:59:30Z"))
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(35.seconds)
            workout.tick(f, plankId)
            assertEquals(35.seconds, f.set(plankId, 0).duration)
            assertNull(workout.state.value.hold)
        }

    @Test
    fun `opening the new day keeps a rest still running from the old one until it's over`() =
        runTest {
            val f = WorkoutFixture(this, Instant.parse("2026-11-09T23:59:30Z"))
            val (benchId) = f.today()
            val tomorrow = LocalDate(2026, 11, 10)
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(40.seconds)
            val next = f.days.addExercise(tomorrow, bench.id)!!
            workout.open(tomorrow, next)
            assertNull(workout.position(tomorrow))
            assertNotNull(workout.state.value.rest)
            advance(60.seconds)
            assertEquals(1, f.alerts.rung.size)
            workout.open(tomorrow, next)
            assertEquals(next, workout.position(tomorrow))
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `unticking only the set that started the rest stops it, and clears its alert`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId, 0)
            workout.tick(f, benchId, 1)
            workout.setDone(benchId, f.set(benchId, 0).id, false)
            assertEquals(
                f.set(benchId, 1).id,
                workout.state.value.rest
                    ?.setId,
            )
            val clears = f.alerts.clears
            workout.setDone(benchId, f.set(benchId, 1).id, false)
            assertNull(workout.state.value.rest)
            assertEquals(clears + 1, f.alerts.clears)
        }

    @Test
    fun `failing alerts never stop the rest or the ticker`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            f.alerts.failing = true
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(90.seconds)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            advance(30.seconds)
            assertNull(workout.state.value.rest)
        }

    @Test
    fun `a failing store never stops the ticker, and the next change is saved`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            f.store.failing = true
            workout.tick(f, benchId)
            advance(90.seconds)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            f.store.failing = false
            advance(30.seconds)
            assertNull(workout.state.value.rest)
            assertNull(f.store.stored?.rest)
        }

    @Test
    fun `a hold whose log fails is logged on the next second`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            var fails = 1
            val flaky =
                object : DayRepository by f.days {
                    override suspend fun logHold(
                        id: SetId,
                        held: Duration,
                        startedAt: Instant,
                        at: Instant,
                    ) {
                        if (fails-- > 0) throw IllegalStateException("database locked")
                        f.days.logHold(id, held, startedAt, at)
                    }
                }
            val workout = f.controller(days = flaky)
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(40.seconds)
            assertFalse(f.set(plankId, 0).done)
            advance(1.seconds)
            assertEquals(START + 40.seconds, f.set(plankId, 0).doneAt)
            assertNull(workout.state.value.hold)
        }

    @Test
    fun `a second tap on a done set doesn't restart the rest`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            advance(30.seconds)
            val clears = f.alerts.clears
            workout.tick(f, benchId)
            assertEquals(
                START + 90.seconds,
                workout.state.value.rest
                    ?.endsAt,
            )
            assertEquals(clears, f.alerts.clears)
        }

    @Test
    fun `pausing a hold already past its end logs it instead`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            // Due, but the ticker hasn't run yet.
            advanceTimeBy(40.seconds)
            workout.pauseHold()
            assertNull(workout.state.value.hold)
            assertEquals(40.seconds, f.set(plankId, 0).duration)
        }

    @Test
    fun `skipping and ticking again in the same second keeps the ticker`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId, 0)
            workout.skipRest()
            workout.tick(f, benchId, 1)
            advance(90.seconds)
            assertEquals(1, f.alerts.rung.size)
        }

    @Test
    fun `a paused hold survives the process being killed and resumes where it was`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val process = CoroutineScope(backgroundScope.coroutineContext + Job(backgroundScope.coroutineContext[Job]))
            val first = f.controller(process = process)
            first.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(10.seconds)
            first.pauseHold()
            process.cancel()
            advance(100.seconds)
            val restored = f.controller()
            assertEquals(
                HoldRun.Paused(30.seconds),
                restored.state.value.hold
                    ?.run,
            )
            restored.resumeHold()
            advance(30.seconds)
            assertEquals(40.seconds, f.set(plankId, 0).duration)
            assertEquals(START + 140.seconds, f.set(plankId, 0).doneAt)
        }

    @Test
    fun `deleting the held set's exercise drops the hold`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            f.days.removeBlock(plankId)
            runCurrent()
            assertNull(workout.state.value.hold)
            advance(60.seconds)
            assertTrue(f.alerts.rung.isEmpty())
        }

    @Test
    fun `turning the alarm back on mid-rest schedules the wakeup again`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            workout.tick(f, benchId)
            f.settings.setAlarm(false)
            runCurrent()
            assertEquals(START + 2.hours, f.wakeups.scheduled)
            f.settings.setAlarm(true)
            runCurrent()
            assertEquals(START + 90.seconds, f.wakeups.scheduled)
            assertNotNull(workout.state.value.rest)
        }

    // The 2 h idle rule

    @Test
    fun `a workout with no set ticked for 2 h stops running, and its day stays unfinished`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, _, curlId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, curlId)
            workout.tick(f, curlId)
            runCurrent()
            assertTrue(workout.state.value.running)
            assertEquals(START + 2.hours, f.wakeups.scheduled)
            advance(2.hours - 1.seconds)
            assertTrue(workout.state.value.running)
            advance(1.seconds)
            assertFalse(workout.state.value.active)
            assertNull(f.day().finishedAt)
        }

    @Test
    fun `a tick resets the idle clock, and a tick after it went idle brings it back`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, _, curlId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, curlId)
            workout.tick(f, curlId, 0)
            advance(1.hours)
            workout.tick(f, curlId, 1)
            runCurrent()
            assertEquals(START + 3.hours, f.wakeups.scheduled)
            advance(1.hours + 30.seconds)
            assertTrue(workout.state.value.running)
            advance(1.hours)
            assertFalse(workout.state.value.running)
            workout.setDone(curlId, f.set(curlId, 1).id, false)
            workout.tick(f, curlId, 1)
            runCurrent()
            assertTrue(workout.state.value.running)
        }

    @Test
    fun `a workout started but never ticked goes idle 2 h after its start`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            val workout = f.controller()
            f.days.start(TODAY)
            workout.open(TODAY, benchId)
            runCurrent()
            assertTrue(workout.state.value.running)
            advance(2.hours)
            assertFalse(workout.state.value.running)
        }

    @Test
    fun `a restore past the idle deadline doesn't bring the workout back`() =
        runTest {
            val f = WorkoutFixture(this)
            val (benchId) = f.today()
            f.days.start(TODAY)
            advance(3.hours)
            val workout = f.controller(FakeWorkoutStateStore(WorkoutSnapshot(date = TODAY, blockId = benchId)))
            runCurrent()
            assertFalse(workout.state.value.active)
            assertNull(f.wakeups.scheduled)
        }

    @Test
    fun `a hold running at the idle deadline finishes first, and its log starts the clock again`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId, curlId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, curlId)
            workout.tick(f, curlId)
            advance(2.hours - 10.seconds)
            workout.startHold(plankId, f.set(plankId, 0).id, 40.seconds)
            advance(10.seconds)
            assertFalse(workout.state.value.running)
            assertTrue(workout.state.value.active)
            assertNotNull(workout.state.value.hold)
            advance(30.seconds)
            assertTrue(f.set(plankId, 0).done)
            assertTrue(workout.state.value.running)
        }

    @Test
    fun `a rest still counting over at the idle deadline is over then`() =
        runTest {
            val f = WorkoutFixture(this)
            val (_, plankId) = f.today()
            val workout = f.controller()
            workout.open(TODAY, plankId)
            workout.setDone(plankId, f.set(plankId, 0).id, true)
            advance(1.hours)
            assertTrue(
                workout.state.value.rest!!
                    .rang,
            )
            advance(1.hours)
            assertNull(workout.state.value.rest)
            assertFalse(workout.state.value.active)
            assertEquals(1, f.alerts.rung.size)
        }

    @Test
    fun `reset forgets the workout, its rest and its wakeup, clears the alert, and nothing comes back`() =
        runTest {
            val f = WorkoutFixture(this)
            val controller = f.controller()
            val blocks = f.today()
            controller.open(TODAY, blocks[0])
            controller.tick(f, blocks[0])
            assertNotNull(controller.state.value.snapshot.rest)
            assertNotNull(f.wakeups.scheduled)
            val clears = f.alerts.clears

            controller.reset()
            runCurrent()
            assertEquals(WorkoutSnapshot(), controller.state.value.snapshot)
            assertEquals(WorkoutSnapshot(), f.store.stored)
            assertTrue(f.alerts.clears > clears)
            // The day still exists here, so only its idle deadline may stay scheduled; no rest end.
            advance(10.minutes)
            assertEquals(emptyList(), f.alerts.rung)
            assertNull(
                f
                    .controller(process = backgroundScope)
                    .state.value.snapshot.rest,
            )
        }
}
