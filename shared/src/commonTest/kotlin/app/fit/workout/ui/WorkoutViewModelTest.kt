// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.Loading
import app.fit.day.SetEdit
import app.fit.exercises.Equipment
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutController
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Setup(
        val f: WorkoutFixture,
        val workout: WorkoutController,
        val ids: List<BlockId>,
    )

    // Today with bench, plank and curl, and a controller over it.
    private suspend fun TestScope.setUp(): Setup {
        val f = WorkoutFixture(this)
        val ids = listOf(bench, plank, curl).map { f.days.addExercise(TODAY, it.id)!! }
        return Setup(f, f.controller(), ids)
    }

    private fun TestScope.viewModel(
        s: Setup,
        block: BlockId = s.ids[0],
    ): WorkoutViewModel {
        val vm = WorkoutViewModel(block.value, s.f.days, s.workout, s.f.settings, s.f.clock, TimeZone.UTC)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        runCurrent()
        return vm
    }

    private fun TestScope.act(
        vm: WorkoutViewModel,
        action: WorkoutAction,
    ) {
        vm.onAction(action)
        runCurrent()
    }

    private fun TestScope.advance(duration: Duration) {
        advanceTimeBy(duration)
        runCurrent()
    }

    private fun WorkoutViewModel.block(): Block = uiState.value.block!!

    @Test
    fun `Complete set writes what was typed, ticks the set and starts the rest`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.Draft(SetField.Weight, "62,5"))
            act(vm, WorkoutAction.Draft(SetField.Reps, "7"))
            assertEquals("7", vm.uiState.value.value(SetField.Reps))
            act(vm, WorkoutAction.CompleteSet)
            val done = vm.block().sets[0]
            assertTrue(done.done)
            assertEquals(62.5, done.weight)
            assertEquals(7, done.reps)
            assertEquals(8, done.targetReps)
            assertEquals(
                START + 90.seconds,
                vm.uiState.value.rest
                    ?.endsAt,
            )
            // The next set starts with no draft.
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.current
                    ?.id,
            )
            assertTrue(
                vm.uiState.value.draft
                    .isEmpty(),
            )
        }

    @Test
    fun `an empty reps field logs the target`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.CompleteSet)
            assertEquals(8, vm.block().sets[0].reps)
        }

    @Test
    fun `the rest shows on every exercise and survives Next and Previous`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.CompleteSet)
            val rest = assertNotNull(vm.uiState.value.rest)
            act(vm, WorkoutAction.Next)
            assertEquals(s.ids[1], vm.block().id)
            assertEquals(rest, vm.uiState.value.rest)
            act(vm, WorkoutAction.Previous)
            assertEquals(s.ids[0], vm.block().id)
            assertEquals(rest, vm.uiState.value.rest)
            advance(30.seconds)
            assertEquals(60.seconds, vm.uiState.value.restLeft)
        }

    @Test
    fun `moving between exercises remembers the position for Resume`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertEquals(s.ids[0], s.workout.position(TODAY))
            act(vm, WorkoutAction.Next)
            assertEquals(s.ids[1], s.workout.position(TODAY))
        }

    @Test
    fun `Start timer holds for what was typed, else the target, and Complete set logs the time held`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[1])
            act(vm, WorkoutAction.StartTimer)
            assertEquals(
                40.seconds,
                vm.uiState.value.hold
                    ?.length,
            )
            act(vm, WorkoutAction.StopHold)
            assertNull(vm.uiState.value.hold)
            assertFalse(vm.block().sets[0].done)
            act(vm, WorkoutAction.Draft(SetField.Seconds, "60"))
            act(vm, WorkoutAction.StartTimer)
            assertEquals(
                60.seconds,
                vm.uiState.value.hold
                    ?.length,
            )
            advance(20.seconds)
            act(vm, WorkoutAction.PauseHold)
            assertEquals(40.seconds, vm.uiState.value.holdLeft)
            act(vm, WorkoutAction.ResumeHold)
            advance(5.seconds)
            act(vm, WorkoutAction.CompleteHold)
            val done = vm.block().sets[0]
            assertTrue(done.done)
            assertEquals(25.seconds, done.duration)
            assertEquals(
                START + 55.seconds,
                vm.uiState.value.rest
                    ?.endsAt,
            )
        }

    @Test
    fun `a running hold keeps the focus on its exercise, so a second hold can't start`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[1])
            act(vm, WorkoutAction.StartTimer)
            val hold = assertNotNull(vm.uiState.value.hold)
            assertFalse(vm.uiState.value.canMove)
            act(vm, WorkoutAction.Next)
            assertEquals(s.ids[1], vm.block().id)
            // A Start timer that got through anyway is refused.
            act(vm, WorkoutAction.StartTimer)
            assertEquals(hold, vm.uiState.value.hold)
            // Opened on another exercise, the screen goes to the held one.
            val other = viewModel(s, s.ids[0])
            assertEquals(s.ids[1], other.block().id)
            act(vm, WorkoutAction.CompleteHold)
            assertTrue(vm.uiState.value.canMove)
            act(vm, WorkoutAction.Next)
            assertEquals(s.ids[2], vm.block().id)
        }

    @Test
    fun `the steppers move the current set's values by the load step, one rep and the time steps`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertEquals("60", vm.uiState.value.value(SetField.Weight))
            assertEquals("8", vm.uiState.value.value(SetField.Reps))
            act(vm, WorkoutAction.Step(SetField.Weight, up = true))
            act(vm, WorkoutAction.Step(SetField.Reps, up = false))
            assertEquals("62.5", vm.uiState.value.value(SetField.Weight))
            assertEquals("7", vm.uiState.value.value(SetField.Reps))
            act(vm, WorkoutAction.CompleteSet)
            assertEquals(62.5, vm.block().sets[0].weight)
            assertEquals(7, vm.block().sets[0].reps)
            val plank = viewModel(s, s.ids[1])
            assertEquals("40", plank.uiState.value.value(SetField.Seconds))
            act(plank, WorkoutAction.Step(SetField.Seconds, up = true))
            assertEquals("45", plank.uiState.value.value(SetField.Seconds))
            repeat(10) { act(plank, WorkoutAction.Step(SetField.Seconds, up = false)) }
            assertEquals("5", plank.uiState.value.value(SetField.Seconds))
        }

    @Test
    fun `during a rest the next set shows and its load changes at once`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.CompleteSet)
            val next = assertNotNull(vm.uiState.value.upNext)
            assertEquals(vm.block().sets[1].id, next.set.id)
            act(vm, WorkoutAction.StepNext(SetField.Weight, up = true))
            assertEquals(62.5, vm.block().sets[1].weight)
            act(vm, WorkoutAction.StepNext(SetField.Reps, up = true))
            assertEquals(9, vm.block().sets[1].reps)
            // The last set of an exercise: the next one is the next exercise's first set.
            act(vm, WorkoutAction.SkipRest)
            vm
                .block()
                .sets
                .drop(1)
                .forEach { act(vm, WorkoutAction.SetDone(it.id, true)) }
            val after = assertNotNull(vm.uiState.value.upNext)
            assertEquals(s.ids[1], after.block.id)
        }

    @Test
    fun `once a working set is done a skipped warm-up isn't the current set, but it can still be ticked`() =
        runTest {
            val s = setUp()
            s.f.days.addWarmups(s.ids[0])
            val vm = viewModel(s)
            assertTrue(
                vm.uiState.value.current!!
                    .warmup,
            )
            val (w1, w2) = vm.block().sets
            val working = vm.block().workingSets
            act(vm, WorkoutAction.SetDone(working[0].id, true))
            assertEquals(
                working[1].id,
                vm.uiState.value.current
                    ?.id,
            )
            working.drop(1).forEach { act(vm, WorkoutAction.SetDone(it.id, true)) }
            assertNull(vm.uiState.value.current)
            assertTrue(vm.uiState.value.allDone)
            act(vm, WorkoutAction.SetDone(w1.id, true))
            assertTrue(
                vm
                    .block()
                    .sets
                    .first { it.id == w1.id }
                    .done,
            )
            assertFalse(
                vm
                    .block()
                    .sets
                    .first { it.id == w2.id }
                    .done,
            )
        }

    @Test
    fun `a hold runs for the time the next set got during the rest`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[1])
            act(vm, WorkoutAction.StartTimer)
            act(vm, WorkoutAction.CompleteHold)
            assertNotNull(vm.uiState.value.rest)
            act(vm, WorkoutAction.StepNext(SetField.Seconds, up = true))
            act(vm, WorkoutAction.SkipRest)
            act(vm, WorkoutAction.StartTimer)
            assertEquals(
                45.seconds,
                vm.uiState.value.hold
                    ?.length,
            )
        }

    @Test
    fun `the sets sheet peeks during a workout, opens in review, and remembers where it was put`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertFalse(vm.uiState.value.sheetExpanded)
            act(vm, WorkoutAction.SetSheet(true))
            assertTrue(vm.uiState.value.sheetExpanded)
            act(vm, WorkoutAction.Next)
            assertFalse(vm.uiState.value.sheetExpanded)
        }

    @Test
    fun `a deleted exercise shows as gone and isn't remembered for Resume`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[1])
            assertEquals(s.ids[1], s.workout.position(TODAY))
            s.f.days.removeBlock(s.ids[1])
            runCurrent()
            assertNull(vm.uiState.value.block)
            assertFalse(vm.uiState.value.loading)
            act(vm, WorkoutAction.Next)
            assertNull(vm.uiState.value.block)
            assertEquals(s.ids[1], s.workout.position(TODAY))
        }

    @Test
    fun `a 0 s rest starts nothing`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[2])
            act(vm, WorkoutAction.CompleteSet)
            assertTrue(vm.block().sets[0].done)
            assertNull(vm.uiState.value.rest)
        }

    @Test
    fun `a finished day opens in review, with its sets open and no timers`() =
        runTest {
            val s = setUp()
            s.f.days.finish(TODAY)
            val vm = viewModel(s)
            assertTrue(vm.uiState.value.review)
            assertTrue(vm.uiState.value.sheetExpanded)
            act(vm, WorkoutAction.SetDone(vm.block().sets[0].id, true))
            assertTrue(vm.block().sets[0].done)
            assertNull(vm.uiState.value.rest)
            assertNull(s.workout.state.value.rest)
        }

    @Test
    fun `another day edits and ticks without timers`() =
        runTest {
            val s = setUp()
            val yesterday = TODAY.minus(1, DateTimeUnit.DAY)
            val id = s.f.days.addExercise(yesterday, bench.id)!!
            val vm = viewModel(s, id)
            assertTrue(vm.uiState.value.review)
            act(vm, WorkoutAction.SetDone(vm.block().sets[0].id, true))
            assertTrue(vm.block().sets[0].done)
            assertNull(s.workout.state.value.rest)
            assertNull(vm.uiState.value.elapsed)
        }

    @Test
    fun `Next and Previous walk the day, and Finish ends it and goes back`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertNull(vm.uiState.value.previous)
            assertEquals(
                s.ids[1],
                vm.uiState.value.next
                    ?.id,
            )
            act(vm, WorkoutAction.Next)
            act(vm, WorkoutAction.Next)
            assertEquals(2, vm.uiState.value.index)
            assertNull(vm.uiState.value.next)
            act(vm, WorkoutAction.Finish)
            assertTrue(vm.uiState.value.finished)
            assertNotNull(
                s.f.days
                    .day(TODAY)
                    .first()!!
                    .finishedAt,
            )
            act(vm, WorkoutAction.FinishedSeen)
            assertFalse(vm.uiState.value.finished)
        }

    @Test
    fun `the session clock counts today's workout once it started`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertNull(vm.uiState.value.elapsed)
            act(vm, WorkoutAction.CompleteSet)
            advance(30.seconds)
            assertEquals(30.seconds, vm.uiState.value.elapsed)
        }

    @Test
    fun `a draft belongs to its set`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.Draft(SetField.Reps, "5"))
            act(vm, WorkoutAction.SetDone(vm.block().sets[0].id, true))
            // The first set was ticked from All sets: the next one starts clean.
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.current
                    ?.id,
            )
            // Not the 5 typed for the first set: the target, which ticking it would log.
            assertEquals("8", vm.uiState.value.value(SetField.Reps))
            assertTrue(
                vm.uiState.value.draft
                    .isEmpty(),
            )
        }

    @Test
    fun `added weight puts a load on a reps-only set, and the unit switches without converting`() =
        runTest {
            val s = setUp()
            val pullUp = s.f.exercises.all.value.size + 100L
            s.f.exercises.all.value += app.fit.exercises.exercise(pullUp, "Pull-up", "Back", app.fit.exercises.Tracks(reps = true))
            val id = s.f.days.addExercise(TODAY, app.fit.exercises.ExerciseId(pullUp))!!
            val vm = viewModel(s, id)
            assertNull(
                vm.uiState.value.current
                    ?.weight,
            )
            act(vm, WorkoutAction.AddedWeight(true))
            assertEquals(
                0.0,
                vm.uiState.value.current
                    ?.weight,
            )
            act(vm, WorkoutAction.Draft(SetField.Weight, "10"))
            act(vm, WorkoutAction.Edit(vm.block().sets[0].id, SetEdit.Unit(WeightUnit.Lb)))
            act(vm, WorkoutAction.CompleteSet)
            val done = vm.block().sets[0]
            assertEquals(10.0, done.weight)
            assertEquals(WeightUnit.Lb, done.weightUnit)
            act(vm, WorkoutAction.AddedWeight(false))
            assertNull(
                vm.uiState.value.current
                    ?.weight,
            )
        }

    @Test
    fun `keep screen on follows the setting`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertTrue(vm.uiState.value.keepScreenOn)
            s.f.settings.setKeepScreenOn(false)
            runCurrent()
            assertFalse(vm.uiState.value.keepScreenOn)
        }

    // All sets, as on the 0.0.4 sets screen.

    @Test
    fun `editing the reps changes what was done and never the target`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val set = vm.block().sets.first()
            act(vm, WorkoutAction.Edit(set.id, SetEdit.Reps(11)))
            act(vm, WorkoutAction.SetDone(set.id, true))
            val done = vm.block().sets.first()
            assertEquals(11, done.reps)
            assertEquals(8, done.targetReps)
        }

    @Test
    fun `plus and minus step the working sets still to do, by each set's unit`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val (first, _, third) = vm.block().sets
            act(vm, WorkoutAction.SetDone(first.id, true))
            act(vm, WorkoutAction.Edit(third.id, SetEdit.Unit(WeightUnit.Lb)))
            act(vm, WorkoutAction.StepWeight(up = true))
            assertEquals(listOf(60.0, 62.5, 65.0), vm.block().sets.map { it.weight })
            act(vm, WorkoutAction.StepWeight(up = false))
            assertEquals(listOf(60.0, 60.0, 60.0), vm.block().sets.map { it.weight })
        }

    @Test
    fun `a removed set comes back in its place with Undo`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val ids = vm.block().sets.map { it.id }
            act(vm, WorkoutAction.Remove(ids[1]))
            assertEquals(1, vm.uiState.value.removed)
            assertEquals(listOf(ids[0], ids[2]), vm.block().sets.map { it.id })
            act(vm, WorkoutAction.Undo)
            assertEquals(0, vm.uiState.value.removed)
            assertEquals(ids, vm.block().sets.map { it.id })
        }

    @Test
    fun `two sets removed before the offer goes both come back with one Undo`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val ids = vm.block().sets.map { it.id }
            act(vm, WorkoutAction.Remove(ids[0]))
            act(vm, WorkoutAction.Remove(ids[2]))
            assertEquals(2, vm.uiState.value.removed)
            assertEquals(listOf(ids[1]), vm.block().sets.map { it.id })
            act(vm, WorkoutAction.Undo)
            assertEquals(ids, vm.block().sets.map { it.id })
            // A new offer starts from nothing.
            act(vm, WorkoutAction.Remove(ids[1]))
            act(vm, WorkoutAction.UndoSeen)
            act(vm, WorkoutAction.Remove(ids[0]))
            assertEquals(1, vm.uiState.value.removed)
        }

    @Test
    fun `add set clones the last working set, and warm-ups go first`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(
                vm,
                WorkoutAction.Edit(
                    vm
                        .block()
                        .sets
                        .last()
                        .id,
                    SetEdit.Weight(70.0),
                ),
            )
            act(vm, WorkoutAction.AddSet)
            assertEquals(4, vm.block().sets.size)
            assertEquals(
                70.0,
                vm
                    .block()
                    .sets
                    .last()
                    .weight,
            )
            act(vm, WorkoutAction.AddWarmups)
            assertEquals(listOf(true, true, false, false, false, false), vm.block().sets.map { it.warmup })
            // A warm-up is the set to do first.
            assertTrue(
                vm.uiState.value.current!!
                    .warmup,
            )
        }

    @Test
    fun `plates per side follow the next set to lift and the bar for its unit`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            assertEquals(Loading(20.0, listOf(20.0), 0.0), vm.uiState.value.plates)
            s.f.settings.setBarWeight(WeightUnit.Kg, 15.0)
            runCurrent()
            assertEquals(Loading(15.0, listOf(20.0, 2.5), 0.0), vm.uiState.value.plates)
        }

    @Test
    fun `plates per side show only for a barbell exercise`() =
        runTest {
            val s = setUp()
            // Curl has a load but no equipment said; a dumbbell press has a dumbbell.
            assertNull(viewModel(s, s.ids[2]).uiState.value.plates)
            val press =
                bench.copy(
                    id = app.fit.exercises.ExerciseId(9),
                    fields = bench.fields.copy(name = "Dumbbell press", equipment = Equipment.Dumbbell),
                )
            s.f.exercises.all.value += press
            val block = s.f.days.addExercise(TODAY, press.id)!!
            assertNull(viewModel(s, block).uiState.value.plates)
        }

    @Test
    fun `after midnight yesterday's rest still shows on its exercise, as in the notification`() =
        runTest {
            val f = WorkoutFixture(this, Instant.parse("2026-11-09T23:59:30Z"))
            val ids = listOf(bench, plank).map { f.days.addExercise(TODAY, it.id)!! }
            val s = Setup(f, f.controller(), ids)
            val vm = viewModel(s)
            act(vm, WorkoutAction.CompleteSet)
            advance(40.seconds)
            // Yesterday now: review, but its rest is still running.
            assertTrue(vm.uiState.value.review)
            assertNotNull(vm.uiState.value.rest)
            assertEquals(s.workout.state.value.rest, vm.uiState.value.rest)
        }

    @Test
    fun `a warm-up set starts no rest`() =
        runTest {
            val s = setUp()
            s.f.days.addWarmups(s.ids[0])
            val vm = viewModel(s)
            assertTrue(
                vm.uiState.value.current!!
                    .warmup,
            )
            act(vm, WorkoutAction.CompleteSet)
            assertNull(vm.uiState.value.rest)
            assertTrue(vm.block().sets[0].done)
        }

    @Test
    fun `RPE is a set option on the set to do, from 5 to 10 in halves, and can be cleared`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val set = vm.uiState.value.optionsSet!!
            assertEquals(vm.uiState.value.current, set)
            act(vm, WorkoutAction.SetRpe(8.5))
            assertEquals(8.5, vm.block().sets[0].rpe)
            // Not an RPE: ignored.
            act(vm, WorkoutAction.SetRpe(8.25))
            assertEquals(8.5, vm.block().sets[0].rpe)
            act(vm, WorkoutAction.SetRpe(null))
            assertEquals(null, vm.block().sets[0].rpe)
        }

    @Test
    fun `during a rest the set options rate the set just done, not the next one`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            val first = vm.block().sets[0].id
            act(vm, WorkoutAction.CompleteSet)
            assertEquals(
                first,
                vm.uiState.value.optionsSet
                    ?.id,
            )
            act(vm, WorkoutAction.SetRpe(9.0))
            assertEquals(9.0, vm.block().sets[0].rpe)
            assertEquals(null, vm.block().sets[1].rpe)
            act(vm, WorkoutAction.SkipRest)
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.optionsSet
                    ?.id,
            )
        }

    @Test
    fun `bodyweight turns on for the set and the working sets after it still to do, and shows no plates`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s)
            act(vm, WorkoutAction.CompleteSet)
            act(vm, WorkoutAction.SkipRest)
            act(vm, WorkoutAction.SetBodyweight(true))
            assertEquals(listOf(false, true, true), vm.block().sets.map { it.bodyweight })
            assertEquals(null, vm.uiState.value.plates)
            act(vm, WorkoutAction.SetBodyweight(false))
            assertEquals(listOf(false, false, false), vm.block().sets.map { it.bodyweight })
        }

    @Test
    fun `with no rest and no set left, the set options rate the last set done`() =
        runTest {
            val s = setUp()
            // Curl rests 0 s: no rest window after a set.
            val vm = viewModel(s, s.ids[2])
            act(vm, WorkoutAction.CompleteSet)
            assertNull(vm.uiState.value.rest)
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.optionsSet
                    ?.id,
            )
            act(vm, WorkoutAction.CompleteSet)
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.optionsSet
                    ?.id,
            )
            act(vm, WorkoutAction.SetRpe(9.5))
            assertEquals(listOf(null, 9.5), vm.block().sets.map { it.rpe })
        }

    @Test
    fun `in review the set options rate the last set done`() =
        runTest {
            val s = setUp()
            val sets =
                s.f.days
                    .day(TODAY)
                    .first()!!
                    .blocks[0]
                    .sets
            s.f.days.setDone(sets[0].id, true)
            s.f.days.setDone(sets[1].id, true)
            s.f.days.finish(TODAY)
            val vm = viewModel(s)
            assertEquals(
                sets[1].id,
                vm.uiState.value.optionsSet
                    ?.id,
            )
        }

    @Test
    fun `a done set picked in All sets gets the set options, until it's picked again or a set is completed`() =
        runTest {
            val s = setUp()
            val vm = viewModel(s, s.ids[2])
            act(vm, WorkoutAction.CompleteSet)
            val first = vm.block().sets[0].id
            act(vm, WorkoutAction.Rate(first))
            assertEquals(
                first,
                vm.uiState.value.optionsSet
                    ?.id,
            )
            act(vm, WorkoutAction.SetRpe(7.0))
            assertEquals(7.0, vm.block().sets[0].rpe)
            act(vm, WorkoutAction.Rate(first))
            assertEquals(
                vm.block().sets[1].id,
                vm.uiState.value.optionsSet
                    ?.id,
            )
            act(vm, WorkoutAction.Rate(first))
            act(vm, WorkoutAction.CompleteSet)
            assertNull(vm.uiState.value.rating)
        }
}
