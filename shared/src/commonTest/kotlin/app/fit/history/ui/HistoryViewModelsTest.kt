// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.lifecycle.ViewModelStore
import app.fit.core.WeightUnit
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.day.FakeDayRepository
import app.fit.day.FixedClock
import app.fit.day.PastSet
import app.fit.day.SetEdit
import app.fit.day.WorkoutDay
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseId
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.exercise
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.history.CalendarMark
import app.fit.history.DotSize
import app.fit.history.HistoryStats
import app.fit.history.Metric
import app.fit.history.Record
import app.fit.routines.FakeRoutineRepository
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
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelsTest {
    private val squat =
        exercise(1, "Squat").let { it.copy(fields = it.fields.copy(defaults = ExerciseDefaults(sets = 3, reps = 5, weight = 60.0))) }
    private val exercises = FakeExerciseRepository(listOf(squat))
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val days = FakeDayRepository(exercises, FakeRoutineRepository(exercises), clock)
    private val today = LocalDate(2026, 11, 9)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.summary(date: LocalDate = today) = SummaryViewModel(date.toString(), days, clock, TimeZone.UTC, backgroundScope)

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    // A squat day with its working sets done at [load] (and [unit]); a warm-up only day has no work.
    private suspend fun squatDay(
        date: LocalDate,
        load: Double = 60.0,
        unit: WeightUnit = WeightUnit.Kg,
        done: Int = 3,
    ): BlockId {
        val block = days.addExercise(date, squat.id)!!
        days
            .dayOfBlock(block)
            .first()!!
            .blocks
            .single()
            .sets
            .take(done)
            .forEach {
                days.editSet(it.id, SetEdit.Unit(unit))
                days.editSet(it.id, SetEdit.Weight(load))
                days.setDone(it.id, true)
            }
        return block
    }

    @Test
    fun `History counts workouts, the last 7 days and the streak, and lists workouts newest first`() =
        runTest {
            squatDay(LocalDate(2026, 10, 20))
            squatDay(LocalDate(2026, 11, 7))
            squatDay(LocalDate(2026, 11, 8))
            // Planned but nothing done: not a workout.
            squatDay(today, done = 0)
            val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            val state = vm.uiState.value
            assertEquals(HistoryStats(workouts = 3, lastSevenDays = 2, streak = 2), state.stats)
            assertEquals(listOf(LocalDate(2026, 11, 8), LocalDate(2026, 11, 7), LocalDate(2026, 10, 20)), state.days.map { it.date })
        }

    @Test
    fun `a lesson's practice run marks nothing and never counts as a workout, in the streak or the last 7 days`() =
        runTest {
            val logs = FakeGuidedRepository()
            val start = Instant.parse("2026-11-09T07:00:00Z")
            logs.log(
                GuidedLog(GuidedPlanId(9), GuidedKind.Practice, "Push-up", today, start, start + 5.minutes, GuidedOutcome.Completed, 9),
            )
            val vm = HistoryViewModel(days, logs, clock, TimeZone.UTC)
            collect(vm.uiState)
            val state = vm.uiState.value
            assertEquals(HistoryStats(workouts = 0, lastSevenDays = 0, streak = 0), state.stats)
            assertEquals(emptyList(), state.days)
            assertEquals(
                emptyList(),
                state.weeks
                    .flatten()
                    .filterNotNull()
                    .filter { it.recovery },
            )
        }

    @Test
    fun `a recovery session gets its own calendar mark and never counts as a workout, in the streak or the last 7 days`() =
        runTest {
            squatDay(LocalDate(2026, 11, 8))
            val logs = FakeGuidedRepository()
            val start = Instant.parse("2026-11-09T07:00:00Z")
            logs.log(
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
            val sunday = Instant.parse("2026-11-07T07:00:00Z")
            logs.log(
                GuidedLog(
                    GuidedPlanId(1),
                    GuidedKind.Recovery,
                    "Wrist reset",
                    LocalDate(2026, 11, 7),
                    sunday,
                    sunday,
                    GuidedOutcome.Stopped,
                    0,
                ),
            )
            val vm = HistoryViewModel(days, logs, clock, TimeZone.UTC)
            collect(vm.uiState)
            val state = vm.uiState.value
            assertEquals(HistoryStats(workouts = 1, lastSevenDays = 1, streak = 1), state.stats)
            assertEquals(listOf(LocalDate(2026, 11, 8)), state.days.map { it.date })
            val cells =
                state.weeks
                    .flatten()
                    .filterNotNull()
                    .associateBy { it.date }
            assertEquals(true, cells.getValue(today).recovery)
            assertEquals(null, cells.getValue(today).mark)
            assertEquals(true, cells.getValue(LocalDate(2026, 11, 7)).recovery)
            assertEquals(false, cells.getValue(LocalDate(2026, 11, 8)).recovery)
        }

    @Test
    fun `the History calendar opens on this month and moves a month at a time`() =
        runTest {
            squatDay(LocalDate(2026, 10, 20))
            val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(LocalDate(2026, 11, 1), vm.uiState.value.month)
            vm.onAction(HistoryAction.ShowMonth(-1))
            assertEquals(LocalDate(2026, 10, 1), vm.uiState.value.month)
            val cell =
                vm.uiState.value.weeks
                    .flatten()
                    .single { it?.date == LocalDate(2026, 10, 20) }!!
            assertEquals(CalendarMark(DotSize.Small, finished = false), cell.mark)
        }

    private fun HistoryViewModel.selectedCells() =
        uiState.value.weeks
            .flatten()
            .filterNotNull()
            .filter { it.selected }
            .map { it.date }

    @Test
    fun `History opens with today picked, and picking a day shows its workout, its records and its recovery sessions`() =
        runTest {
            squatDay(LocalDate(2026, 11, 2), load = 60.0)
            squatDay(LocalDate(2026, 11, 4), load = 62.5)
            val logs = FakeGuidedRepository()
            val start = Instant.parse("2026-11-04T07:00:00Z")
            val wrists =
                GuidedLog(
                    GuidedPlanId(1),
                    GuidedKind.Recovery,
                    "Wrist reset",
                    LocalDate(2026, 11, 4),
                    start,
                    start + 8.minutes,
                    GuidedOutcome.Completed,
                    12,
                )
            logs.log(wrists)
            val vm = HistoryViewModel(days, logs, clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(PickedDay(today), vm.uiState.value.picked)
            assertEquals(listOf(today), vm.selectedCells())

            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 4)))
            val picked = vm.uiState.value.picked
            assertEquals(LocalDate(2026, 11, 4), picked.date)
            assertEquals(3, picked.workout?.doneSets)
            assertEquals(listOf(Record(squat.id, "Squat", Metric.Load, 62.5, 60.0, WeightUnit.Kg)), picked.records)
            assertEquals(listOf(wrists), picked.recovery)
            assertEquals(listOf(LocalDate(2026, 11, 4)), vm.selectedCells())
        }

    @Test
    fun `a picked day with only a plan, only a recovery session or only a practice run has no workout`() =
        runTest {
            // Planned, nothing done: not a workout, as in the list and the counts.
            squatDay(LocalDate(2026, 11, 3), done = 0)
            val logs = FakeGuidedRepository()
            val start = Instant.parse("2026-11-05T07:00:00Z")
            val wrists =
                GuidedLog(
                    GuidedPlanId(1),
                    GuidedKind.Recovery,
                    "Wrist reset",
                    LocalDate(2026, 11, 5),
                    start,
                    start + 8.minutes,
                    GuidedOutcome.Completed,
                    12,
                )
            logs.log(wrists)
            logs.log(wrists.copy(kind = GuidedKind.Practice, title = "Push-up", day = LocalDate(2026, 11, 6)))
            val vm = HistoryViewModel(days, logs, clock, TimeZone.UTC)
            collect(vm.uiState)
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 3)))
            assertEquals(PickedDay(LocalDate(2026, 11, 3)), vm.uiState.value.picked)
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 5)))
            assertEquals(PickedDay(LocalDate(2026, 11, 5), recovery = listOf(wrists)), vm.uiState.value.picked)
            // Practice stays out of History.
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 6)))
            assertEquals(PickedDay(LocalDate(2026, 11, 6)), vm.uiState.value.picked)
        }

    @Test
    fun `the picked day stays picked when the month changes, and a set done on it shows at once`() =
        runTest {
            val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 4)))
            vm.onAction(HistoryAction.ShowMonth(-1))
            assertEquals(LocalDate(2026, 11, 4), vm.uiState.value.picked.date)
            assertEquals(emptyList(), vm.selectedCells())
            assertNull(vm.uiState.value.picked.workout)
            squatDay(LocalDate(2026, 11, 4))
            assertEquals(
                3,
                vm.uiState.value.picked.workout
                    ?.doneSets,
            )
        }

    @Test
    fun `the summary lists records against earlier days in the same unit`() =
        runTest {
            squatDay(LocalDate(2026, 11, 2), load = 60.0)
            squatDay(LocalDate(2026, 11, 4), load = 140.0, unit = WeightUnit.Lb)
            squatDay(today, load = 62.5)
            val vm = summary()
            collect(vm.uiState)
            assertEquals(listOf(Record(squat.id, "Squat", Metric.Load, 62.5, 60.0, WeightUnit.Kg)), vm.uiState.value.records)
        }

    @Test
    fun `fatigue is set from the summary, cleared with null, and a value outside 1 to 5 is ignored`() =
        runTest {
            squatDay(today)
            val vm = summary()
            collect(vm.uiState)
            vm.onAction(SummaryAction.SetFatigue(4))
            assertEquals(
                4,
                vm.uiState.value.day!!
                    .fatigue,
            )
            vm.onAction(SummaryAction.SetFatigue(6))
            vm.onAction(SummaryAction.SetFatigue(0))
            assertEquals(
                4,
                vm.uiState.value.day!!
                    .fatigue,
            )
            vm.onAction(SummaryAction.SetFatigue(null))
            assertNull(
                vm.uiState.value.day!!
                    .fatigue,
            )
        }

    @Test
    fun `notes typed quickly are written once, after typing pauses`() =
        runTest {
            squatDay(today)
            val vm = summary()
            collect(vm.uiState)
            val before = days.notesWrites
            "Felt good".indices.forEach { vm.onAction(SummaryAction.SetNotes("Felt good".take(it + 1))) }
            advanceTimeBy(400.milliseconds)
            assertEquals(before, days.notesWrites)
            advanceTimeBy(200.milliseconds)
            runCurrent()
            assertEquals(before + 1, days.notesWrites)
            assertEquals("Felt good", days.day(today).first()!!.notes)
        }

    @Test
    fun `notes still waiting for the pause are saved when the screen leaves`() =
        runTest {
            squatDay(today)
            val vm = summary()
            collect(vm.uiState)
            vm.onAction(SummaryAction.SetNotes("Knees fine"))
            vm.onAction(SummaryAction.SaveNotes)
            runCurrent()
            assertEquals("Knees fine", days.day(today).first()!!.notes)
            // Nothing left to write after the pause.
            val writes = days.notesWrites
            advanceTimeBy(1.seconds)
            runCurrent()
            assertEquals(writes, days.notesWrites)
        }

    @Test
    fun `notes still waiting are saved when the ViewModel is cleared`() =
        runTest {
            squatDay(today)
            val store = ViewModelStore()
            val vm = summary()
            store.put("summary", vm)
            vm.onAction(SummaryAction.SetNotes("Left in a hurry"))
            store.clear()
            runCurrent()
            assertEquals("Left in a hurry", days.day(today).first()!!.notes)
        }

    @Test
    fun `a summary of a day never filled loads with no day and no records, and its notes start the day`() =
        runTest {
            val vm = summary()
            collect(vm.uiState)
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.day)
            assertEquals(emptyList(), vm.uiState.value.records)
            vm.onAction(SummaryAction.SetNotes("Rest day"))
            vm.onAction(SummaryAction.SaveNotes)
            runCurrent()
            assertEquals(
                "Rest day",
                vm.uiState.value.day!!
                    .notes,
            )
        }

    @Test
    fun `History's today moves on at midnight, and the streak, the last 7 days and the outline follow`() =
        runTest {
            squatDay(LocalDate(2026, 11, 7))
            squatDay(LocalDate(2026, 11, 8))
            clock.now = Instant.parse("2026-11-09T23:59:30Z")
            val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(today, vm.uiState.value.today)
            assertEquals(2, vm.uiState.value.stats.streak)
            clock.now = Instant.parse("2026-11-10T00:00:30Z")
            advanceTimeBy(1.seconds)
            runCurrent()
            val state = vm.uiState.value
            assertEquals(LocalDate(2026, 11, 10), state.today)
            // Never tapped, so the picked day is still today.
            assertEquals(LocalDate(2026, 11, 10), state.picked.date)
            // Two days back from yesterday (the 9th) has none: no streak.
            assertEquals(0, state.stats.streak)
            assertEquals(2, state.stats.lastSevenDays)
            assertTrue(
                state.weeks
                    .flatten()
                    .single { it?.date == LocalDate(2026, 11, 10) }!!
                    .today,
            )
        }

    @Test
    fun `a tapped day stays picked across midnight, also when it was today itself`() =
        runTest {
            listOf(LocalDate(2026, 11, 4), today).forEach { tapped ->
                clock.now = Instant.parse("2026-11-09T23:59:30Z")
                val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
                collect(vm.uiState)
                vm.onAction(HistoryAction.Pick(tapped))
                clock.now = Instant.parse("2026-11-10T00:00:30Z")
                advanceTimeBy(1.seconds)
                runCurrent()
                val state = vm.uiState.value
                assertEquals(LocalDate(2026, 11, 10), state.today)
                assertEquals(tapped, state.picked.date)
                assertEquals(listOf(tapped), vm.selectedCells())
            }
        }

    @Test
    fun `picking days and writing fatigue read the workouts once, and the records only when the sets change`() =
        runTest {
            squatDay(LocalDate(2026, 11, 2), load = 60.0)
            squatDay(LocalDate(2026, 11, 4), load = 62.5)
            val counting = CountingDays(days)
            val vm = HistoryViewModel(counting, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 4)))
            assertEquals(1, vm.uiState.value.picked.records.size)
            val reads = counting.earlier
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 3)))
            vm.onAction(HistoryAction.Pick(LocalDate(2026, 11, 4)))
            days.setFatigue(LocalDate(2026, 11, 4), 3)
            assertEquals(
                3,
                vm.uiState.value.picked.workout
                    ?.fatigue,
            )
            assertEquals(1, counting.trained)
            // Once more for picking the 4th again; none for the fatigue.
            assertEquals(reads + 1, counting.earlier)
        }

    private class CountingDays(
        private val days: DayRepository,
    ) : DayRepository by days {
        var trained = 0
        var earlier = 0

        override fun trainedDays(): Flow<List<WorkoutDay>> = days.trainedDays().also { trained++ }

        override suspend fun earlierSets(
            exerciseIds: List<ExerciseId>,
            date: LocalDate,
        ): List<PastSet> = days.earlierSets(exerciseIds, date).also { earlier++ }
    }

    @Test
    fun `workouts with a gap before yesterday have a streak of 0`() =
        runTest {
            squatDay(LocalDate(2026, 11, 2))
            squatDay(LocalDate(2026, 11, 5))
            val vm = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(HistoryStats(workouts = 2, lastSevenDays = 1, streak = 0), vm.uiState.value.stats)
        }

    @Test
    fun `fatigue entered in the summary shows in History`() =
        runTest {
            squatDay(today)
            val summary = summary()
            summary.onAction(SummaryAction.SetFatigue(2))
            val history = HistoryViewModel(days, FakeGuidedRepository(), clock, TimeZone.UTC)
            collect(history.uiState)
            assertEquals(
                2,
                history.uiState.value.days
                    .single()
                    .fatigue,
            )
        }
}
