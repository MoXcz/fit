// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.FakeBodyWeightRepository
import app.fit.core.SessionTimers
import app.fit.core.WeightUnit
import app.fit.day.FakeDayRepository
import app.fit.day.FixedClock
import app.fit.day.WorkoutDay
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.guided.FakeGuidedAlerts
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.FakeGuidedStateStore
import app.fit.guided.GuidedController
import app.fit.guided.guidedController
import app.fit.programs.FakeProgramRepository
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.routines.FakeRoutineRepository
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.settings.FakeSettingsRepository
import app.fit.workout.FakeWakeupScheduler
import app.fit.workout.FakeWorkoutAlerts
import app.fit.workout.FakeWorkoutStateStore
import app.fit.workout.WorkoutController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The active program's plan on Today: the empty day's state, deload weeks and the day's body weight.
@OptIn(ExperimentalCoroutinesApi::class)
class TodayPlanTest {
    private val squat =
        exercise(1, "Squat", "Quads").let { it.copy(fields = it.fields.copy(defaults = ExerciseDefaults(weight = 100.0))) }
    private val plank = exercise(2, "Plank", "Abs", Tracks(time = true))
    private val exercises = FakeExerciseRepository(listOf(squat, plank))
    private val legs =
        Routine(
            RoutineId(1),
            null,
            "Legs",
            "",
            listOf(
                RoutineItem(RoutineItemId(10), squat, ItemTarget(sets = 3, reps = 5, rest = 120.seconds, duration = 45.seconds)),
                RoutineItem(RoutineItemId(11), plank, ItemTarget(sets = 2, reps = 1, rest = 30.seconds, duration = 40.seconds)),
            ),
        )
    private val arms =
        Routine(
            RoutineId(2),
            null,
            "Arms",
            "",
            listOf(RoutineItem(RoutineItemId(20), plank, ItemTarget(sets = 1, reps = 1, rest = 0.seconds, duration = 30.seconds))),
        )
    private val routines = FakeRoutineRepository(exercises, listOf(legs, arms))

    // Monday 9 Nov is week 2 of a program that started on 2 Nov.
    private val program =
        Program(
            ProgramId(1),
            name = "Split",
            active = true,
            startsOn = LocalDate(2026, 11, 2),
            days = mapOf(DayOfWeek.MONDAY to legs.id, DayOfWeek.TUESDAY to arms.id),
        )
    private val programs = FakeProgramRepository(routines, listOf(program))
    private val bodyWeights = FakeBodyWeightRepository()
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val days = FakeDayRepository(exercises, routines, clock, programs)
    private val today = LocalDate(2026, 11, 9)
    private val tomorrow = LocalDate(2026, 11, 10)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val timers = SessionTimers()
    private val plans = FakeGuidedRepository()
    private lateinit var guided: GuidedController

    private fun TestScope.viewModel(): TodayViewModel {
        val workout =
            WorkoutController(
                days,
                FakeSettingsRepository(),
                FakeWorkoutStateStore(),
                FakeWakeupScheduler(),
                FakeWorkoutAlerts(),
                timers,
                clock,
                TimeZone.UTC,
                backgroundScope,
            )
        guided =
            guidedController(
                plans,
                days,
                FakeSettingsRepository(),
                FakeGuidedStateStore(),
                FakeWakeupScheduler(),
                FakeGuidedAlerts(),
                timers,
                clock,
                backgroundScope,
            )
        runCurrent()
        val vm =
            TodayViewModel(days, routines, programs, bodyWeights, FakeSettingsRepository(), workout, guided, plans, clock, TimeZone.UTC)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private suspend fun day(date: LocalDate = today): WorkoutDay? = days.day(date).first()

    private fun deloadEvery(weeks: Int) = programs.all.update { list -> list.map { it.copy(deloadEvery = weeks) } }

    @Test
    fun `an empty day leads with the day's planned routine, with the other routines below`() =
        runTest {
            val vm = viewModel()
            val state = vm.uiState.value
            assertEquals("Legs", state.planned?.name)
            assertEquals(PrimaryAction.StartPlan, state.primary)
            assertEquals(listOf("Arms"), state.otherRoutines.map { it.name })
            assertEquals(2, state.programWeek)
            assertFalse(state.deload)
        }

    @Test
    fun `Start workout on an empty today applies the plan, starts the clock and opens the first exercise in one tap`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.StartPlan)
            val day = assertNotNull(day())
            assertEquals(listOf("Squat", "Plank"), day.blocks.map { it.exercise.fields.name })
            assertEquals("Legs", day.name)
            assertTrue(day.started)
            assertEquals(day.blocks.first().id, vm.uiState.value.opened)
        }

    @Test
    fun `on another day the plan is Use routine, which applies it without starting the clock`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.Next)
            assertEquals(PrimaryAction.UsePlan, vm.uiState.value.primary)
            assertEquals(
                "Arms",
                vm.uiState.value.planned
                    ?.name,
            )
            vm.onAction(TodayAction.UsePlan)
            val day = assertNotNull(day(tomorrow))
            assertEquals(listOf("Plank"), day.blocks.map { it.exercise.fields.name })
            assertFalse(day.started)
            assertNull(vm.uiState.value.opened)
            assertEquals(PrimaryAction.Review, vm.uiState.value.primary)
        }

    @Test
    fun `a weekday without a routine is a rest day that offers Add exercise and every routine`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.Select(LocalDate(2026, 11, 11)))
            val state = vm.uiState.value
            assertNotNull(state.plan)
            assertNull(state.planned)
            assertEquals(PrimaryAction.AddExercise, state.primary)
            assertEquals(listOf("Arms", "Legs"), state.otherRoutines.map { it.name })
        }

    @Test
    fun `one of the other routines applies with one tap, without starting`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.ApplyRoutine(arms.id))
            assertEquals(listOf("Plank"), day()!!.blocks.map { it.exercise.fields.name })
            assertFalse(day()!!.started)
            assertEquals(PrimaryAction.Start, vm.uiState.value.primary)
        }

    @Test
    fun `Add exercise never applies the plan, on a planned empty day either`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.OpenPicker)
            assertNull(day())
            // What the picker does when an exercise is picked.
            val id = days.addExercise(today, plank.id)!!
            vm.onAction(TodayAction.Added(id, "Plank"))
            val day = day()!!
            assertEquals(listOf("Plank"), day.blocks.map { it.exercise.fields.name })
            assertEquals("", day.name)
            assertFalse(day.deload)
            assertEquals(PrimaryAction.Start, vm.uiState.value.primary)
        }

    @Test
    fun `in a deload week the plan's loads are cut, the day is marked, and the hero says so`() =
        runTest {
            deloadEvery(2)
            val vm = viewModel()
            assertTrue(vm.uiState.value.deload)
            vm.onAction(TodayAction.StartPlan)
            val day = day()!!
            assertTrue(day.deload)
            assertEquals(
                listOf(90.0, 90.0, 90.0),
                day.blocks
                    .first()
                    .sets
                    .map { it.weight },
            )
            assertTrue(vm.uiState.value.deload)
            assertEquals(2, vm.uiState.value.programWeek)
        }

    @Test
    fun `a routine chosen by hand in a deload week is cut too`() =
        runTest {
            deloadEvery(2)
            val vm = viewModel()
            vm.onAction(TodayAction.Select(LocalDate(2026, 11, 11)))
            vm.onAction(TodayAction.OpenChooser)
            vm.onAction(TodayAction.ChooseRoutine(legs.id))
            val day = day(LocalDate(2026, 11, 11))!!
            assertTrue(day.deload)
            assertEquals(
                90.0,
                day.blocks
                    .first()
                    .sets
                    .first()
                    .weight,
            )
        }

    @Test
    fun `the launcher's Start workout applies the plan to an empty today and starts it`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.StartFromShortcut)
            assertTrue(day()!!.started)
            assertEquals(2, day()!!.blocks.size)
        }

    @Test
    fun `a planned routine deleted since leaves the day a rest day`() =
        runTest {
            val vm = viewModel()
            routines.delete(legs.id)
            assertNull(vm.uiState.value.planned)
            assertEquals(PrimaryAction.AddExercise, vm.uiState.value.primary)
            vm.onAction(TodayAction.StartPlan)
            assertNull(day())
        }

    @Test
    fun `session details write the day's body weight, and emptying it deletes only that day's reading, with Undo`() =
        runTest {
            val earlier = BodyWeight(LocalDate(2026, 11, 2), 82.0, WeightUnit.Kg)
            bodyWeights.restore(earlier)
            val vm = viewModel()
            vm.onAction(TodayAction.OpenDetails)
            val sheet = assertIs<TodaySheet.Details>(vm.uiState.value.sheet)
            assertEquals("", sheet.bodyWeight)
            assertEquals(earlier, sheet.earlier)
            vm.onAction(TodayAction.SetBodyWeight("81.5"))
            vm.onAction(TodayAction.CloseSheet)
            assertEquals(listOf(81.5, 82.0), bodyWeights.readings.first().map { it.weight })
            vm.onAction(TodayAction.OpenDetails)
            assertEquals("81.5", assertIs<TodaySheet.Details>(vm.uiState.value.sheet).bodyWeight)
            vm.onAction(TodayAction.SetBodyWeight(""))
            vm.onAction(TodayAction.CloseSheet)
            assertEquals(listOf(earlier), bodyWeights.readings.first())
            assertEquals(TodayUndo.BodyWeightChanged(BodyWeight(today, 81.5, WeightUnit.Kg), deleted = true), vm.uiState.value.undo)
            vm.onAction(TodayAction.Undo)
            assertEquals(
                81.5,
                bodyWeights.readings
                    .first()
                    .first()
                    .weight,
            )
        }

    @Test
    fun `text in session details that isn't a weight never deletes the day's reading`() =
        runTest {
            val own = BodyWeight(today, 81.5, WeightUnit.Kg)
            bodyWeights.restore(own)
            val vm = viewModel()
            vm.onAction(TodayAction.OpenDetails)
            vm.onAction(TodayAction.SetBodyWeight("81.5kg"))
            assertTrue(assertIs<TodaySheet.Details>(vm.uiState.value.sheet).bodyWeightInvalid)
            vm.onAction(TodayAction.CloseSheet)
            assertEquals(listOf(own), bodyWeights.readings.first())
            assertNull(vm.uiState.value.undo)
            // A new value replaces it, and Undo puts it back.
            vm.onAction(TodayAction.OpenDetails)
            vm.onAction(TodayAction.SetBodyWeight("80"))
            vm.onAction(TodayAction.CloseSheet)
            assertEquals(TodayUndo.BodyWeightChanged(own, deleted = false), vm.uiState.value.undo)
            vm.onAction(TodayAction.Undo)
            assertEquals(listOf(own), bodyWeights.readings.first())
        }

    @Test
    fun `an exercise added by hand in a deload week starts cut and marks the day, but brings no plan`() =
        runTest {
            deloadEvery(2)
            val vm = viewModel()
            val id = days.addExercise(today, squat.id)!!
            vm.onAction(TodayAction.Added(id, "Squat"))
            val day = day()!!
            assertEquals(listOf("Squat"), day.blocks.map { it.exercise.fields.name })
            assertTrue(day.deload)
            assertEquals(
                90.0,
                day.blocks
                    .single()
                    .sets
                    .first()
                    .weight,
            )
        }
}
