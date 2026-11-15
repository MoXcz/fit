// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import app.fit.bodyweight.FakeBodyWeightRepository
import app.fit.core.SessionTimers
import app.fit.day.DayRepository
import app.fit.day.FakeDayRepository
import app.fit.exercises.Equipment
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.guided.ContentImages
import app.fit.guided.FakeContentImages
import app.fit.guided.FakeGuidedAlerts
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.FakeGuidedStateStore
import app.fit.guided.GuidedController
import app.fit.guided.GuidedStateStore
import app.fit.guided.guidedController
import app.fit.guided.recoveryPlan
import app.fit.programs.FakeProgramRepository
import app.fit.recovery.ui.SessionViewModel
import app.fit.reminders.ReminderFixture
import app.fit.routines.FakeRoutineRepository
import app.fit.settings.FakeSettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Wall clock tied to virtual time, so advanceTimeBy moves both delays and "now". */
@OptIn(ExperimentalCoroutinesApi::class)
class TestClock(
    private val scheduler: TestCoroutineScheduler,
    private val start: Instant = START,
) : Clock {
    override fun now(): Instant = start + scheduler.currentTime.milliseconds

    companion object {
        val START: Instant = Instant.parse("2026-11-09T10:00:00Z")
        val TODAY = LocalDate(2026, 11, 9)
    }
}

class FakeWorkoutStateStore(
    var stored: WorkoutSnapshot? = null,
) : WorkoutStateStore {
    var saves = 0

    // Every save throws while set, as a full disk would.
    var failing = false

    override suspend fun load(): WorkoutSnapshot? = stored

    override suspend fun save(snapshot: WorkoutSnapshot) {
        if (failing) throw IllegalStateException("disk full")
        stored = snapshot
        saves++
    }
}

class FakeWakeupScheduler : WakeupScheduler {
    var scheduled: Instant? = null
    var guided: Instant? = null

    // Everything ever scheduled, to show nothing was.
    val history = mutableListOf<Instant>()
    val guidedHistory = mutableListOf<Instant>()

    override fun scheduleWorkoutWakeup(at: Instant) {
        scheduled = at
        history += at
    }

    override fun cancelWorkoutWakeup() {
        scheduled = null
    }

    override fun scheduleGuidedWakeup(at: Instant) {
        guided = at
        guidedHistory += at
    }

    override fun cancelGuidedWakeup() {
        guided = null
    }
}

class FakeWorkoutAlerts : WorkoutAlerts {
    // "rest:<next>" or "hold:<exercise>", in order.
    val rung = mutableListOf<String>()
    var clears = 0

    // Every call throws while set, as a broken notification or audio service would.
    var failing = false

    override fun restEnded(next: String?) {
        if (failing) throw IllegalStateException("no audio")
        rung += "rest:$next"
    }

    override fun holdEnded(exercise: String) {
        if (failing) throw IllegalStateException("no audio")
        rung += "hold:$exercise"
    }

    override fun clear() {
        if (failing) throw IllegalStateException("no notifications")
        clears++
    }
}

private fun withDefaults(
    exercise: Exercise,
    defaults: ExerciseDefaults,
) = exercise.copy(fields = exercise.fields.copy(defaults = defaults))

// Bench (a barbell): 3 sets, 90–120 s rest. Plank: a 40 s hold, 30 s rest. Curl (no equipment said): no rest.
val bench =
    withDefaults(
        exercise(1, "Bench press", "Chest").let { it.copy(fields = it.fields.copy(equipment = Equipment.Barbell)) },
        ExerciseDefaults(sets = 3, reps = 8, rest = 90.seconds, restMax = 120.seconds, weight = 60.0),
    )
val plank =
    withDefaults(exercise(2, "Plank", "Abs", Tracks(time = true)), ExerciseDefaults(sets = 2, rest = 30.seconds, duration = 40.seconds))
val curl = withDefaults(exercise(3, "Curl", "Biceps"), ExerciseDefaults(sets = 2, reps = 10, rest = Duration.ZERO, weight = 10.0))

/** A day repository, settings and ports around one controller, all on the test's virtual clock. */
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutFixture(
    private val scope: TestScope,
    start: Instant = TestClock.START,
) {
    val clock = TestClock(scope.testScheduler, start)
    val exercises = FakeExerciseRepository(listOf(bench, plank, curl))
    val routines = FakeRoutineRepository(exercises)
    val programs = FakeProgramRepository(routines)
    val bodyWeights = FakeBodyWeightRepository()
    val days = FakeDayRepository(exercises, routines, clock, programs)
    val settings = FakeSettingsRepository()
    val store = FakeWorkoutStateStore()
    val wakeups = FakeWakeupScheduler()
    val alerts = FakeWorkoutAlerts()
    val timers = SessionTimers()
    val plans = FakeGuidedRepository(listOf(recoveryPlan()), clock)
    val guidedStore = FakeGuidedStateStore()
    val guidedAlerts = FakeGuidedAlerts()

    // [process]: cancel it to stand for the process being killed.
    fun controller(
        store: WorkoutStateStore = this.store,
        process: CoroutineScope = scope.backgroundScope,
        days: DayRepository = this.days,
    ): WorkoutController =
        WorkoutController(days, settings, store, wakeups, alerts, timers, clock, TimeZone.UTC, process).also {
            scope.runCurrent()
        }

    // The guided session's controller on the same days, settings, wakeups and timers.
    fun guided(
        store: GuidedStateStore = guidedStore,
        process: CoroutineScope = scope.backgroundScope,
    ): GuidedController =
        guidedController(plans, days, settings, store, wakeups, guidedAlerts, timers, clock, process).also {
            scope.runCurrent()
        }

    // The reminders around [guided], not yet started.
    fun reminders(
        guided: GuidedController,
        process: CoroutineScope = scope.backgroundScope,
    ) = ReminderFixture(plans, guided, clock, process)

    fun sessionViewModel(
        id: Long,
        guided: GuidedController,
        images: ContentImages = FakeContentImages(),
        reminders: ReminderFixture = reminders(guided),
    ) = SessionViewModel(id, plans, guided, images, reminders.repository, reminders.reminders, reminders.notices)
}
