// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import app.fit.bodyweight.FakeBodyWeightRepository
import app.fit.core.SessionTimers
import app.fit.day.DayMark
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
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedSource
import app.fit.guided.guidedController
import app.fit.programs.FakeProgramRepository
import app.fit.routines.FakeRoutineRepository
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.WARMUP_SECTION
import app.fit.routines.ui.NameError
import app.fit.settings.FakeSettingsRepository
import app.fit.workout.FakeWakeupScheduler
import app.fit.workout.FakeWorkoutAlerts
import app.fit.workout.FakeWorkoutStateStore
import app.fit.workout.WorkoutController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    private val squat =
        exercise(
            1,
            "Squat",
            "Quads",
        ).let { it.copy(fields = it.fields.copy(defaults = ExerciseDefaults(sets = 2, reps = 5, weight = 40.0))) }
    private val plank = exercise(2, "Plank", "Abs", tracks = Tracks(time = true))
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
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val programs = FakeProgramRepository(routines)
    private val bodyWeights = FakeBodyWeightRepository()
    private val days = FakeDayRepository(exercises, routines, clock, programs)
    private val today = LocalDate(2026, 11, 9)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private lateinit var workout: WorkoutController

    private val timers = SessionTimers()
    private val plans = FakeGuidedRepository()
    private lateinit var guided: GuidedController

    private fun TestScope.viewModel(): TodayViewModel {
        workout =
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
        collect(vm.uiState)
        return vm
    }

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private suspend fun day(date: LocalDate = today): WorkoutDay? = days.day(date).first()

    private fun TodayViewModel.choose(
        id: RoutineId,
        replace: Boolean = true,
    ) {
        onAction(TodayAction.OpenChooser)
        onAction(TodayAction.SetReplace(replace))
        onAction(TodayAction.ChooseRoutine(id))
    }

    @Test
    fun `an empty day without a plan offers Add exercise and its routines, and choosing one fills the day and names it`() =
        runTest {
            val vm = viewModel()
            assertEquals(today, vm.uiState.value.date)
            assertEquals(PrimaryAction.AddExercise, vm.uiState.value.primary)
            assertEquals(
                listOf("Arms", "Legs"),
                vm.uiState.value.otherRoutines
                    .map { it.name },
            )
            assertNull(vm.uiState.value.status)
            vm.choose(RoutineId(1))
            assertNull(vm.uiState.value.sheet)
            assertEquals(listOf("Squat", "Plank"), day()!!.blocks.map { it.exercise.fields.name })
            assertEquals("Legs", day()!!.name)
            assertEquals(PrimaryAction.Start, vm.uiState.value.primary)
            assertEquals(DayStatus.Planned, vm.uiState.value.status)
        }

    @Test
    fun `a recovery session done today is a quiet line and its own week mark, never in the day's sets or volume`() =
        runTest {
            val vm = viewModel()
            vm.choose(legs.id)
            runCurrent()
            val block = day()!!.blocks.first()
            days.setDone(block.sets.first().id, true)
            val start = Instant.parse("2026-11-09T07:00:00Z")
            plans.log(
                GuidedLog(
                    GuidedPlanId(1),
                    GuidedKind.Recovery,
                    "Wrist reset",
                    today,
                    start,
                    start + 8.minutes,
                    GuidedOutcome.Completed,
                    12,
                ),
            )
            runCurrent()
            val state = vm.uiState.value
            assertEquals(listOf("Wrist reset"), state.recovery.map { it.title })
            assertEquals(1, state.day!!.doneSets)
            assertEquals(listOf(true), state.week.filter { it.recovery }.map { it.date == today })
            // Yesterday has no session, so no line there.
            vm.onAction(TodayAction.Previous)
            assertEquals(emptyList(), vm.uiState.value.recovery)
        }

    @Test
    fun `a lesson's practice run is no recovery line or week mark on Today`() =
        runTest {
            val vm = viewModel()
            val start = Instant.parse("2026-11-09T07:00:00Z")
            plans.log(
                GuidedLog(GuidedPlanId(9), GuidedKind.Practice, "Push-up", today, start, start + 5.minutes, GuidedOutcome.Completed, 9),
            )
            runCurrent()
            val state = vm.uiState.value
            assertEquals(emptyList(), state.recovery)
            assertEquals(emptyList(), state.week.filter { it.recovery })
        }

    @Test
    fun `the warm-up row and Start run today's warm-up on the player`() =
        runTest {
            days.addExercise(today, plank.id, WARMUP_SECTION)
            days.addExercise(today, squat.id)
            val vm = viewModel()
            vm.onAction(TodayAction.Start)
            runCurrent()
            assertTrue(vm.uiState.value.player)
            assertNull(vm.uiState.value.opened)
            assertEquals(
                GuidedSource.Warmup(today),
                guided.state.value.snapshot
                    ?.source,
            )
            vm.onAction(TodayAction.PlayerSeen)
            // Resume goes back to the running warm-up.
            vm.onAction(TodayAction.Resume)
            runCurrent()
            assertTrue(vm.uiState.value.player)
            vm.onAction(TodayAction.PlayerSeen)
            vm.onAction(TodayAction.Warmup)
            runCurrent()
            assertTrue(vm.uiState.value.player)
        }

    @Test
    fun `Start runs the warm-up when any warm-up block has sets left, not only the first`() =
        runTest {
            days.addExercise(today, squat.id)
            days.addExercise(today, plank.id, WARMUP_SECTION)
            val vm = viewModel()
            vm.onAction(TodayAction.Start)
            runCurrent()
            assertTrue(vm.uiState.value.player)
        }

    @Test
    fun `Resume runs the warm-up when a warm-up block is next, but not once past a skipped warm-up`() =
        runTest {
            days.addExercise(today, plank.id, WARMUP_SECTION)
            val squatBlock = days.addExercise(today, squat.id)!!
            days.start(today)
            val vm = viewModel()
            vm.onAction(TodayAction.Resume)
            runCurrent()
            assertTrue(vm.uiState.value.player)
            assertNull(vm.uiState.value.opened)
            vm.onAction(TodayAction.PlayerSeen)
            // Skip warm-up went on to the squat's dial, which saved it as the position.
            guided.end()
            workout.open(today, squatBlock)
            runCurrent()
            vm.onAction(TodayAction.Resume)
            runCurrent()
            assertEquals(squatBlock, vm.uiState.value.opened)
            assertFalse(vm.uiState.value.player)
        }

    @Test
    fun `replacing a populated day asks first, and adding to it doesn't`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.choose(RoutineId(2), replace = true)
            assertEquals(
                "Arms",
                vm.uiState.value.confirmReplace
                    ?.name,
            )
            assertEquals(2, day()!!.blocks.size)
            vm.onAction(TodayAction.ConfirmReplace)
            assertEquals(listOf("Plank"), day()!!.blocks.map { it.exercise.fields.name })
            assertEquals("Arms", day()!!.name)
            vm.choose(RoutineId(1), replace = false)
            assertNull(vm.uiState.value.confirmReplace)
            assertEquals(3, day()!!.blocks.size)
            assertEquals("Arms", day()!!.name)
        }

    @Test
    fun `Add exercise adds only that exercise, never a routine, and Undo takes it back`() =
        runTest {
            val vm = viewModel()
            val id = days.addExercise(today, squat.id)!!
            vm.onAction(TodayAction.Added(id, "Squat"))
            assertEquals(listOf("Squat"), day()!!.blocks.map { it.exercise.fields.name })
            assertEquals(
                2,
                day()!!
                    .blocks
                    .single()
                    .sets.size,
            )
            assertEquals("", day()!!.name)
            assertEquals(TodayUndo.Added("Squat"), vm.uiState.value.undo)
            vm.onAction(TodayAction.Undo)
            assertTrue(day()!!.blocks.isEmpty())
        }

    @Test
    fun `Copy a previous day lists only earlier days with sets, and appends a copy`() =
        runTest {
            days.applyRoutine(LocalDate(2026, 11, 6), RoutineId(1), replace = false)
            days.setName(LocalDate(2026, 11, 7), "Nothing here")
            days.applyRoutine(LocalDate(2026, 11, 10), RoutineId(2), replace = false)
            val vm = viewModel()
            vm.onAction(TodayAction.OpenCopyDay)
            val sheet = assertIs<TodaySheet.CopyDay>(vm.uiState.value.sheet)
            assertEquals(listOf(LocalDate(2026, 11, 6)), sheet.days.map { it.date })
            vm.onAction(TodayAction.CopyDay(LocalDate(2026, 11, 6)))
            assertEquals(listOf("Squat", "Plank"), day()!!.blocks.map { it.exercise.fields.name })
        }

    @Test
    fun `previous, next and the week strip move the day, and the week marks only days with done sets`() =
        runTest {
            days.applyRoutine(LocalDate(2026, 11, 10), RoutineId(2), replace = false)
            val vm = viewModel()
            vm.onAction(TodayAction.Previous)
            assertEquals(LocalDate(2026, 11, 8), vm.uiState.value.date)
            assertEquals(
                LocalDate(2026, 11, 2),
                vm.uiState.value.week
                    .first()
                    .date,
            )
            vm.onAction(TodayAction.Next)
            vm.onAction(TodayAction.Next)
            assertEquals(LocalDate(2026, 11, 10), vm.uiState.value.date)
            assertEquals(
                DayMark.None,
                vm.uiState.value.week[1]
                    .mark,
            )
            val set =
                day(LocalDate(2026, 11, 10))!!
                    .blocks
                    .single()
                    .sets
                    .single()
            days.setDone(set.id, true)
            assertEquals(
                DayMark.Trained,
                vm.uiState.value.week[1]
                    .mark,
            )
            vm.onAction(TodayAction.ShowToday)
            assertEquals(today, vm.uiState.value.date)
            assertTrue(
                vm.uiState.value.week[0]
                    .today,
            )
        }

    @Test
    fun `Start starts the clock and opens the first exercise with sets left, and Resume opens it too`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val (squatBlock, plankBlock) = day()!!.blocks
            vm.onAction(TodayAction.Start)
            assertEquals(clock.now, day()!!.startedAt)
            assertEquals(squatBlock.id, vm.uiState.value.opened)
            vm.onAction(TodayAction.OpenedSeen)
            assertEquals(PrimaryAction.Resume, vm.uiState.value.primary)
            squatBlock.sets.forEach { days.setDone(it.id, true) }
            vm.onAction(TodayAction.Resume)
            assertEquals(plankBlock.id, vm.uiState.value.opened)
        }

    @Test
    fun `Resume opens the exercise last open in the focus view`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val (_, plankBlock) = day()!!.blocks
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            workout.open(today, plankBlock.id)
            vm.onAction(TodayAction.Resume)
            assertEquals(plankBlock.id, vm.uiState.value.opened)
        }

    @Test
    fun `the launcher shortcut starts today's workout, or resumes it`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.Select(LocalDate(2026, 11, 7)))
            vm.onAction(TodayAction.StartFromShortcut)
            // An empty today: it only shows Today.
            assertEquals(today, vm.uiState.value.date)
            assertNull(vm.uiState.value.opened)
            vm.choose(RoutineId(1))
            val (squatBlock, plankBlock) = day()!!.blocks
            vm.onAction(TodayAction.StartFromShortcut)
            assertEquals(clock.now, day()!!.startedAt)
            assertEquals(squatBlock.id, vm.uiState.value.opened)
            vm.onAction(TodayAction.OpenedSeen)
            workout.open(today, plankBlock.id)
            vm.onAction(TodayAction.StartFromShortcut)
            assertEquals(plankBlock.id, vm.uiState.value.opened)
            vm.onAction(TodayAction.OpenedSeen)
            vm.onAction(TodayAction.Finish)
            vm.onAction(TodayAction.StartFromShortcut)
            assertNull(vm.uiState.value.opened)
        }

    @Test
    fun `Finish opens the day's summary, and View summary opens it again`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            vm.onAction(TodayAction.Finish)
            assertEquals(today, vm.uiState.value.summary)
            vm.onAction(TodayAction.SummarySeen)
            assertNull(vm.uiState.value.summary)
            assertEquals(PrimaryAction.Summary, vm.uiState.value.primary)
            vm.onAction(TodayAction.OpenSummary)
            assertEquals(today, vm.uiState.value.summary)
        }

    @Test
    fun `the calendar sheet opens on the shown day's month, moves by months, and a day picked in it is shown`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            days.setDone(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            vm.onAction(TodayAction.OpenCalendar)
            assertEquals(TodaySheet.Calendar(LocalDate(2026, 11, 1)), vm.uiState.value.sheet)
            val cells =
                vm.uiState.value.calendar!!
                    .weeks
                    .flatten()
                    .filterNotNull()
            assertEquals(30, cells.size)
            val todayCell = cells.single { it.date == today }
            assertTrue(todayCell.today && todayCell.selected)
            assertNotNull(todayCell.mark)
            vm.onAction(TodayAction.ShowMonth(-1))
            assertEquals(TodaySheet.Calendar(LocalDate(2026, 10, 1)), vm.uiState.value.sheet)
            // The month shown and its weeks always come together.
            assertEquals(
                LocalDate(2026, 10, 1),
                vm.uiState.value.calendar!!
                    .month,
            )
            assertEquals(
                31,
                vm.uiState.value.calendar!!
                    .weeks
                    .flatten()
                    .count { it != null },
            )
            vm.onAction(TodayAction.Select(LocalDate(2026, 10, 14)))
            assertNull(vm.uiState.value.sheet)
            assertEquals(LocalDate(2026, 10, 14), vm.uiState.value.date)
            assertNull(vm.uiState.value.calendar)
        }

    @Test
    fun `Finish ends at the last set when it was long ago, and Reopen carries the clock on`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val set =
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
            days.setDone(set.id, true)
            val lastSet = clock.now
            clock.now += 40.minutes
            vm.onAction(TodayAction.Finish)
            assertEquals(lastSet, day()!!.finishedAt)
            assertEquals(DayStatus.Completed, vm.uiState.value.status)
            assertEquals(PrimaryAction.Summary, vm.uiState.value.primary)
            clock.now += 20.minutes
            vm.onAction(TodayAction.Reopen)
            assertNull(day()!!.finishedAt)
            assertEquals(lastSet + 60.minutes, day()!!.startedAt)
            assertEquals(0.seconds, day()!!.elapsed(clock.now))
        }

    @Test
    fun `the first ticked set starts the session clock`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            assertNull(day()!!.startedAt)
            days.setDone(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            assertEquals(clock.now, day()!!.startedAt)
            clock.now += 5.minutes
            assertEquals(5.minutes, day()!!.elapsed(clock.now))
            assertEquals(PrimaryAction.Resume, vm.uiState.value.primary)
        }

    @Test
    fun `a deleted day comes back with Undo`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.DeleteDay)
            assertNull(day())
            assertEquals(TodayUndo.Deleted(today), vm.uiState.value.undo)
            vm.onAction(TodayAction.Undo)
            assertEquals(2, day()!!.blocks.size)
        }

    @Test
    fun `an exercise removed in reorder mode comes back with Undo, and moves stay in their section`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.SetMode(TodayMode.Reorder))
            val (first, second) = day()!!.blocks
            vm.onAction(TodayAction.Move(second.id, -1))
            assertEquals(listOf(second.id, first.id), day()!!.blocks.map { it.id })
            vm.onAction(TodayAction.Remove(first.id))
            assertEquals(TodayUndo.Removed(listOf("Squat")), vm.uiState.value.undo)
            assertEquals(1, day()!!.blocks.size)
            vm.onAction(TodayAction.Undo)
            assertEquals(listOf(second.id, first.id), day()!!.blocks.map { it.id })
        }

    @Test
    fun `two exercises swiped away before the offer goes both come back with one Undo`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.choose(RoutineId(2), replace = false)
            val ids = day()!!.blocks.map { it.id }
            vm.onAction(TodayAction.SetMode(TodayMode.Reorder))
            vm.onAction(TodayAction.Remove(ids[0]))
            vm.onAction(TodayAction.Remove(ids[2]))
            assertEquals(TodayUndo.Removed(listOf("Squat", "Plank")), vm.uiState.value.undo)
            vm.onAction(TodayAction.Undo)
            assertEquals(ids, day()!!.blocks.map { it.id })
        }

    @Test
    fun `session details write the name and notes when the sheet closes`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.OpenDetails)
            assertEquals("Legs", assertIs<TodaySheet.Details>(vm.uiState.value.sheet).name)
            vm.onAction(TodayAction.SetName("Heavy legs "))
            vm.onAction(TodayAction.SetNotes("Felt good"))
            assertEquals("Legs", day()!!.name)
            vm.onAction(TodayAction.CloseSheet)
            assertEquals("Heavy legs", day()!!.name)
            assertEquals("Felt good", day()!!.notes)
        }

    @Test
    fun `save as routine needs a free name and copies targets, not the reps done`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val set =
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
            days.editSet(
                set.id,
                app.fit.day.SetEdit
                    .Reps(12),
            )
            vm.onAction(TodayAction.OpenOptions)
            vm.onAction(TodayAction.OpenSaveAs)
            // The options sheet closes and the dialog starts from the day's name, made free.
            assertNull(vm.uiState.value.sheet)
            assertEquals(
                "Legs 2",
                vm.uiState.value.saveAs
                    ?.name,
            )
            vm.onAction(TodayAction.SetSaveAsName("legs"))
            vm.onAction(TodayAction.SaveAsRoutine)
            assertEquals(
                NameError.Taken,
                vm.uiState.value.saveAs
                    ?.error,
            )
            vm.onAction(TodayAction.SetSaveAsName("Legs again"))
            assertNull(
                vm.uiState.value.saveAs
                    ?.error,
            )
            vm.onAction(TodayAction.SaveAsRoutine)
            assertNull(vm.uiState.value.saveAs)
            assertEquals(TodayUndo.Saved("Legs again"), vm.uiState.value.undo)
            val saved = routines.routines.first().single { it.name == "Legs again" }
            assertEquals(listOf(3, 2), saved.items.map { it.target.sets })
            assertEquals(
                5,
                saved.items
                    .first()
                    .target.reps,
            )
        }

    @Test
    fun `an end time earlier than the start is the next day, and End at last set uses the last set`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.EditTime(TimeField.Start))
            assertEquals(
                LocalTime(10, 0),
                vm.uiState.value.timePicker
                    ?.initial,
            )
            vm.onAction(TodayAction.SetTime(LocalTime(23, 30)))
            vm.onAction(TodayAction.EditTime(TimeField.End))
            vm.onAction(TodayAction.SetTime(LocalTime(0, 15)))
            assertEquals(Instant.parse("2026-11-10T00:15:00Z"), day()!!.finishedAt)
            assertEquals(45.minutes, day()!!.elapsed(clock.now))
            days.setDone(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            vm.onAction(TodayAction.EndAtLastSet)
            assertEquals(clock.now, day()!!.finishedAt)
        }

    @Test
    fun `the picker and sheets open one at a time`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.OpenOptions)
            assertEquals(TodaySheet.Options, vm.uiState.value.sheet)
            vm.onAction(TodayAction.OpenPicker)
            assertNull(vm.uiState.value.sheet)
            assertTrue(vm.uiState.value.pickerOpen)
            vm.onAction(TodayAction.ClosePicker)
            assertNotNull(vm.uiState.value)
            assertTrue(!vm.uiState.value.pickerOpen)
        }

    @Test
    fun `replacing a finished day starts it over`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            days.setDone(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            vm.onAction(TodayAction.Finish)
            assertEquals(DayStatus.Completed, vm.uiState.value.status)
            vm.choose(RoutineId(2), replace = true)
            vm.onAction(TodayAction.ConfirmReplace)
            assertNull(day()!!.startedAt)
            assertNull(day()!!.finishedAt)
            assertEquals(DayStatus.Planned, vm.uiState.value.status)
            assertEquals(PrimaryAction.Start, vm.uiState.value.primary)
        }

    @Test
    fun `a start time after the finish is refused and the dialog stays open`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            clock.now += 30.minutes
            vm.onAction(TodayAction.Finish)
            vm.onAction(TodayAction.EditTime(TimeField.Start))
            vm.onAction(TodayAction.SetTime(LocalTime(11, 0)))
            assertEquals(Instant.parse("2026-11-09T10:00:00Z"), day()!!.startedAt)
            assertEquals(
                true,
                vm.uiState.value.timePicker
                    ?.startAfterEnd,
            )
            vm.onAction(TodayAction.SetTime(LocalTime(9, 30)))
            assertNull(vm.uiState.value.timePicker)
            assertEquals(Instant.parse("2026-11-09T09:30:00Z"), day()!!.startedAt)
        }

    @Test
    fun `following today moves to the new date at midnight, and a chosen day stays put`() =
        runTest {
            clock.now = Instant.parse("2026-11-09T23:59:59Z")
            val vm = viewModel()
            assertEquals(today, vm.uiState.value.date)
            clock.now = Instant.parse("2026-11-10T00:00:01Z")
            advanceTimeBy(1_500)
            assertEquals(LocalDate(2026, 11, 10), vm.uiState.value.date)
            assertEquals(LocalDate(2026, 11, 10), vm.uiState.value.today)
            vm.onAction(TodayAction.Select(LocalDate(2026, 11, 5)))
            clock.now = Instant.parse("2026-11-11T00:00:01Z")
            advanceTimeBy(1_500)
            assertEquals(LocalDate(2026, 11, 5), vm.uiState.value.date)
        }

    @Test
    fun `Clear end only clears the end, while Reopen moves the start on`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.Start)
            val start = day()!!.startedAt
            clock.now += 30.minutes
            vm.onAction(TodayAction.Finish)
            clock.now += 10.minutes
            vm.onAction(TodayAction.ClearEnd)
            assertNull(day()!!.finishedAt)
            assertEquals(start, day()!!.startedAt)
        }

    @Test
    fun `another day that was started is Logged with its counts, never in progress with a running clock`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.Start)
            assertEquals(DayStatus.InProgress, vm.uiState.value.status)
            vm.onAction(TodayAction.Previous)
            vm.choose(RoutineId(1))
            assertEquals(DayStatus.Planned, vm.uiState.value.status)
            vm.onAction(TodayAction.Start)
            assertEquals(DayStatus.Logged, vm.uiState.value.status)
            vm.onAction(TodayAction.Finish)
            assertEquals(DayStatus.Completed, vm.uiState.value.status)
        }

    @Test
    fun `ticking a set on another day records it but never starts that day's clock`() =
        runTest {
            val vm = viewModel()
            vm.onAction(TodayAction.Previous)
            vm.choose(RoutineId(1))
            val yesterday = LocalDate(2026, 11, 8)
            days.setDone(
                day(yesterday)!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            assertNull(day(yesterday)!!.startedAt)
            assertEquals(1, day(yesterday)!!.doneSets)
            assertEquals(DayStatus.Logged, vm.uiState.value.status)
        }

    @Test
    fun `Undo that finds the day filled again says it couldn't undo`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.onAction(TodayAction.DeleteDay)
            vm.choose(RoutineId(2))
            vm.onAction(TodayAction.Undo)
            assertEquals(TodayUndo.Failed, vm.uiState.value.undo)
            assertEquals(listOf("Plank"), day()!!.blocks.map { it.exercise.fields.name })
            vm.onAction(TodayAction.UndoSeen)
            assertNull(vm.uiState.value.undo)
        }

    @Test
    fun `status and action agree on every kind of day`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            assertEquals(DayStatus.Planned to PrimaryAction.Start, vm.uiState.value.status to vm.uiState.value.primary)
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            assertEquals(DayStatus.InProgress to PrimaryAction.Resume, vm.uiState.value.status to vm.uiState.value.primary)
            day()!!.blocks.flatMap { it.sets }.forEach { days.setDone(it.id, true) }
            // Everything ticked: the one button finishes.
            assertEquals(DayStatus.InProgress to PrimaryAction.Finish, vm.uiState.value.status to vm.uiState.value.primary)
            vm.onAction(TodayAction.Finish)
            assertEquals(DayStatus.Completed to PrimaryAction.Summary, vm.uiState.value.status to vm.uiState.value.primary)
            // Yesterday with a set done says it was logged and offers Review, never Start.
            vm.onAction(TodayAction.Previous)
            vm.choose(RoutineId(1))
            assertEquals(DayStatus.Planned to PrimaryAction.Review, vm.uiState.value.status to vm.uiState.value.primary)
            days.setDone(
                day(LocalDate(2026, 11, 8))!!
                    .blocks
                    .first()
                    .sets
                    .first()
                    .id,
                true,
            )
            // Logged but never finished: its summary, like a finished day; the rows still review.
            assertEquals(DayStatus.Logged to PrimaryAction.Summary, vm.uiState.value.status to vm.uiState.value.primary)
            vm.onAction(TodayAction.OpenSummary)
            assertEquals(LocalDate(2026, 11, 8), vm.uiState.value.summary)
            vm.onAction(TodayAction.Review)
            assertEquals(
                day(LocalDate(2026, 11, 8))!!
                    .blocks
                    .first()
                    .id,
                vm.uiState.value.opened,
            )
        }

    @Test
    fun `the hero names the exercise last open while it has sets left, and Resume opens that one`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val (squatBlock, plankBlock) = day()!!.blocks
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            assertEquals(
                squatBlock.id,
                vm.uiState.value.current
                    ?.id,
            )
            workout.open(today, plankBlock.id)
            runCurrent()
            assertEquals(plankBlock.id, vm.uiState.value.position)
            assertEquals(
                plankBlock.id,
                vm.uiState.value.current
                    ?.id,
            )
            plankBlock.sets.forEach { days.setDone(it.id, true) }
            // Its sets are done: the first exercise with sets left.
            assertEquals(
                squatBlock.id,
                vm.uiState.value.current
                    ?.id,
            )
            vm.onAction(TodayAction.Resume)
            assertEquals(squatBlock.id, vm.uiState.value.opened)
        }

    @Test
    fun `a drag moves an exercise several places within its section`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            vm.choose(RoutineId(2), replace = false)
            val (a, b, c) = day()!!.blocks
            vm.onAction(TodayAction.Move(c.id, -2))
            assertEquals(listOf(c.id, a.id, b.id), day()!!.blocks.map { it.id })
            vm.onAction(TodayAction.Move(c.id, 1))
            assertEquals(listOf(a.id, c.id, b.id), day()!!.blocks.map { it.id })
        }

    @Test
    fun `a skipped warm-up set doesn't keep the workout open or come up next`() =
        runTest {
            val vm = viewModel()
            vm.choose(RoutineId(1))
            val squat = day()!!.blocks.first()
            days.editSet(
                squat.sets.first().id,
                app.fit.day.SetEdit
                    .Weight(60.0),
            )
            days.addWarmups(squat.id)
            vm.onAction(TodayAction.Start)
            vm.onAction(TodayAction.OpenedSeen)
            day()!!.blocks.forEach { block -> block.workingSets.forEach { days.setDone(it.id, true) } }
            assertTrue(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .any { it.warmup && !it.done },
            )
            assertEquals(PrimaryAction.Finish, vm.uiState.value.primary)
            assertNull(vm.uiState.value.current)
        }
}
