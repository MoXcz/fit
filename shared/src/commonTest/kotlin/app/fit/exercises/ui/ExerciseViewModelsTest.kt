// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import app.fit.core.WeightUnit
import app.fit.day.FakeDayRepository
import app.fit.day.FixedClock
import app.fit.day.SetEdit
import app.fit.exercises.DefaultField
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRef
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.RefKind
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.lessons.FakeLessonRepository
import app.fit.routines.FakeRoutineRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ExerciseViewModelsTest {
    private val repository =
        FakeExerciseRepository(
            listOf(
                exercise(1, "Plank", "Abs", tracks = Tracks(time = true)),
                exercise(2, "Dip", "Triceps"),
                exercise(3, "Old curl", "Biceps", archived = true),
            ),
        )
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val days = FakeDayRepository(repository, FakeRoutineRepository(repository), clock)

    // A day of Dip ([id] 2) with its working sets done at [load] × [reps].
    private suspend fun dip(
        date: LocalDate,
        load: Double?,
        reps: Int = 8,
    ) {
        val block = days.addExercise(date, ExerciseId(2))!!
        days.dayOfBlock(block).first()!!.blocks.single().sets.forEach {
            days.editSet(it.id, SetEdit.Weight(load))
            days.editSet(it.id, SetEdit.Reps(reps))
            days.setDone(it.id, true)
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun TestScope.collect(flow: kotlinx.coroutines.flow.Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun `the library searches and switches to the archived exercises`() =
        runTest {
            val vm = ExercisesViewModel(repository)
            collect(vm.uiState)
            assertEquals(
                listOf("Triceps", "Abs"),
                vm.uiState.value.groups
                    .map { it.category },
            )
            assertEquals(1, vm.uiState.value.archivedCount)
            vm.onAction(ExercisesAction.Search("dip"))
            assertEquals(
                listOf("Triceps"),
                vm.uiState.value.groups
                    .map { it.category },
            )
            vm.onAction(ExercisesAction.Search(""))
            vm.onAction(ExercisesAction.ShowArchived(true))
            assertEquals(
                listOf("Biceps"),
                vm.uiState.value.groups
                    .map { it.category },
            )
        }

    @Test
    fun `unarchiving the last archived exercise goes back to the active list`() =
        runTest {
            val vm = ExercisesViewModel(repository)
            collect(vm.uiState)
            vm.onAction(ExercisesAction.ShowArchived(true))
            repository.setArchived(ExerciseId(3), false)
            assertFalse(vm.uiState.value.archived)
            assertEquals(
                3,
                vm.uiState.value.groups
                    .sumOf { it.exercises.size },
            )
        }

    @Test
    fun `an exercise is archived and unarchived from its page`() =
        runTest {
            val vm = ExerciseViewModel(1, repository, days, FakeLessonRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            vm.onAction(ExerciseAction.Archive)
            assertTrue(
                vm.uiState.value.exercise!!
                    .archived,
            )
            vm.onAction(ExerciseAction.Unarchive)
            assertFalse(
                vm.uiState.value.exercise!!
                    .archived,
            )
        }

    @Test
    fun `the exercise page shows its done working sets by day and charts each day's best`() =
        runTest {
            val vm = ExerciseViewModel(2, repository, days, FakeLessonRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(emptyList(), vm.uiState.value.history)
            assertNull(vm.uiState.value.chart)
            listOf(LocalDate(2026, 11, 2) to 20.0, LocalDate(2026, 11, 5) to 25.0).forEach { (date, load) ->
                val block = days.addExercise(date, ExerciseId(2))!!
                val sets =
                    suspend {
                        days
                            .dayOfBlock(block)
                            .first()!!
                            .blocks
                            .single()
                            .sets
                    }
                sets().forEach { days.editSet(it.id, SetEdit.Weight(load)) }
                days.addWarmups(block)
                sets().forEach { set ->
                    // A warm-up heavier than the work, which must not show anywhere.
                    if (set.warmup) days.editSet(set.id, SetEdit.Weight(90.0))
                    days.setDone(set.id, true)
                }
                assertTrue(sets().any { it.warmup })
            }
            val state = vm.uiState.value
            assertEquals(listOf(LocalDate(2026, 11, 5), LocalDate(2026, 11, 2)), state.history.map { it.date })
            assertTrue(state.history.flatMap { it.sets }.none { it.weight == 90.0 })
            assertEquals(listOf(20.0, 25.0), state.chart!!.points.map { it.value })
        }

    @Test
    fun `the exercise page shows the latest 30 days of history until Show all`() =
        runTest {
            (1..35).forEach { dip(LocalDate(2026, 9, 1).plus(it, DateTimeUnit.DAY), 20.0) }
            val vm = ExerciseViewModel(2, repository, days, FakeLessonRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(HISTORY_DAYS, vm.uiState.value.history.size)
            assertEquals(5, vm.uiState.value.moreDays)
            assertEquals(
                LocalDate(2026, 10, 6),
                vm.uiState.value.history
                    .first()
                    .date,
            )
            vm.onAction(ExerciseAction.ShowAllHistory)
            assertEquals(35, vm.uiState.value.history.size)
            assertEquals(0, vm.uiState.value.moreDays)
        }

    @Test
    fun `an exercise that records weight, done without a load, charts its reps instead of nothing`() =
        runTest {
            dip(LocalDate(2026, 11, 2), null, reps = 8)
            dip(LocalDate(2026, 11, 5), 0.0, reps = 10)
            val vm = ExerciseViewModel(2, repository, days, FakeLessonRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            val chart = vm.uiState.value.chart!!
            assertEquals(app.fit.history.Metric.Reps to listOf(8.0, 10.0), chart.metric to chart.points.map { it.value })
        }

    @Test
    fun `an exercise that doesn't exist loads as null`() =
        runTest {
            val vm = ExerciseViewModel(99, repository, days, FakeLessonRepository(), clock, TimeZone.UTC)
            collect(vm.uiState)
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.exercise)
        }

    @Test
    fun `edits are only written on Save, which then reports saved until seen`() =
        runTest {
            val vm = ExerciseEditViewModel(2, repository)
            vm.onAction(ExerciseEditAction.SetName("Ring dip"))
            vm.onAction(ExerciseEditAction.Step(DefaultField.Sets, up = true))
            vm.onAction(ExerciseEditAction.SetWeightUnit(WeightUnit.Lb))
            assertEquals(
                "Dip",
                repository
                    .exercise(ExerciseId(2))
                    .first()!!
                    .fields.name,
            )
            vm.onAction(ExerciseEditAction.Save)
            val saved = repository.exercise(ExerciseId(2)).first()!!.fields
            assertEquals("Ring dip", saved.name)
            assertEquals(4, saved.defaults.sets)
            assertEquals(WeightUnit.Lb, saved.weightUnit)
            assertTrue(vm.uiState.value.saved)
            vm.onAction(ExerciseEditAction.SavedSeen)
            assertFalse(vm.uiState.value.saved)
        }

    @Test
    fun `equipment is chosen in the editor and cleared again`() =
        runTest {
            val vm = ExerciseEditViewModel(2, repository)
            vm.onAction(ExerciseEditAction.SetEquipment(Equipment.Barbell))
            vm.onAction(ExerciseEditAction.Save)
            assertEquals(
                Equipment.Barbell,
                repository
                    .exercise(ExerciseId(2))
                    .first()!!
                    .fields.equipment,
            )
            vm.onAction(ExerciseEditAction.SetEquipment(null))
            vm.onAction(ExerciseEditAction.Save)
            assertEquals(
                null,
                repository
                    .exercise(ExerciseId(2))
                    .first()!!
                    .fields.equipment,
            )
        }

    @Test
    fun `a taken name is reported and cleared by editing the name`() =
        runTest {
            val vm = ExerciseEditViewModel(2, repository)
            vm.onAction(ExerciseEditAction.SetName("plank"))
            vm.onAction(ExerciseEditAction.Save)
            assertEquals(EditError.NameTaken, vm.uiState.value.error)
            assertFalse(vm.uiState.value.saved)
            vm.onAction(ExerciseEditAction.SetName("Planks"))
            assertNull(vm.uiState.value.error)
        }

    @Test
    fun `untracking everything or a bad video address can't be saved`() =
        runTest {
            val vm = ExerciseEditViewModel(1, repository)
            vm.onAction(ExerciseEditAction.ToggleMetric(Metric.Time))
            vm.onAction(ExerciseEditAction.Save)
            assertEquals(EditError.NothingTracked, vm.uiState.value.error)
            vm.onAction(ExerciseEditAction.ToggleMetric(Metric.Reps))
            vm.onAction(ExerciseEditAction.SetVideoUrl("youtube.com/x"))
            vm.onAction(ExerciseEditAction.Save)
            assertEquals(EditError.BadVideoUrl, vm.uiState.value.error)
            assertEquals(
                Tracks(time = true),
                repository
                    .exercise(ExerciseId(1))
                    .first()!!
                    .fields.tracks,
            )
        }

    @Test
    fun `references are added with a web address and removed`() =
        runTest {
            val vm = ExerciseEditViewModel(1, repository)
            vm.onAction(ExerciseEditAction.EditNewRef(NewRef(RefKind.Video, " Form ", "example.org")))
            vm.onAction(ExerciseEditAction.AddRef)
            assertEquals(EditError.BadRefUrl, vm.uiState.value.error)
            vm.onAction(ExerciseEditAction.EditNewRef(NewRef(RefKind.Video, " Form ", " https://example.org ")))
            vm.onAction(ExerciseEditAction.AddRef)
            assertEquals(
                listOf(ExerciseRef(RefKind.Video, "Form", "https://example.org")),
                vm.uiState.value.fields!!
                    .refs,
            )
            assertEquals(NewRef(), vm.uiState.value.newRef)
            vm.onAction(ExerciseEditAction.RemoveRef(0))
            assertEquals(
                emptyList(),
                vm.uiState.value.fields!!
                    .refs,
            )
        }

    @Test
    fun `a category outside the usual list stays choosable`() =
        runTest {
            repository.all.value += exercise(4, "Swing", "Kettlebell")
            val vm = ExerciseEditViewModel(4, repository)
            assertEquals(
                "Kettlebell",
                vm.uiState.value.categories
                    .last(),
            )
            assertEquals(
                false,
                ExerciseEditViewModel(1, repository)
                    .uiState.value.categories
                    .contains("Kettlebell"),
            )
        }
}
