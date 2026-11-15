// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import app.fit.day.FakeDayRepository
import app.fit.day.FixedClock
import app.fit.exercises.DefaultField
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseType
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.programs.FakeProgramRepository
import app.fit.routines.FakeRoutineRepository
import app.fit.routines.ItemField
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.defaultTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RoutineViewModelsTest {
    private val squat = exercise(1, "Squat", "Quads")
    private val plank = exercise(2, "Plank", "Abs", tracks = Tracks(time = true))
    private val curl = exercise(3, "Old curl", "Biceps", archived = true)
    private val exercises = FakeExerciseRepository(listOf(squat, plank, curl))
    private val legs =
        Routine(
            RoutineId(1),
            null,
            "Legs",
            "",
            listOf(
                RoutineItem(RoutineItemId(10), squat, squat.defaultTarget()),
                RoutineItem(RoutineItemId(11), plank, plank.defaultTarget()),
            ),
        )
    private val arms = Routine(RoutineId(2), null, "Arms", "", emptyList())
    private val routines = FakeRoutineRepository(exercises, listOf(legs, arms))
    private val programs = FakeProgramRepository(routines)
    private val days = FakeDayRepository(exercises, routines, programs = programs)
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))

    private fun editor() = RoutineEditorViewModel(1, routines, days, clock, TimeZone.UTC)

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private suspend fun legs() = routines.routine(RoutineId(1)).first()!!

    @Test
    fun `the list shows each routine's size and estimate`() =
        runTest {
            val vm = RoutinesViewModel(routines, programs)
            collect(vm.uiState)
            assertEquals(
                listOf("Arms", "Legs"),
                vm.uiState.value.routines
                    .map { it.name },
            )
            assertEquals(
                2,
                vm.uiState.value.routines
                    .last()
                    .exerciseCount,
            )
        }

    @Test
    fun `a new routine needs a free name and then opens`() =
        runTest {
            val vm = RoutinesViewModel(routines, programs)
            collect(vm.uiState)
            vm.onAction(RoutinesAction.StartNew)
            vm.onAction(RoutinesAction.SetNewName("legs"))
            vm.onAction(RoutinesAction.CreateNew)
            assertEquals(NameError.Taken, vm.uiState.value.nameError)
            vm.onAction(RoutinesAction.SetNewName("Push"))
            vm.onAction(RoutinesAction.CreateNew)
            assertNull(vm.uiState.value.newName)
            assertNotNull(vm.uiState.value.opened)
            vm.onAction(RoutinesAction.OpenedSeen)
            assertNull(vm.uiState.value.opened)
        }

    @Test
    fun `a deleted routine comes back with Undo`() =
        runTest {
            val vm = RoutinesViewModel(routines, programs)
            collect(vm.uiState)
            vm.onAction(RoutinesAction.Delete(RoutineId(1)))
            assertEquals("Legs", vm.uiState.value.deleted)
            assertEquals(
                listOf("Arms"),
                vm.uiState.value.routines
                    .map { it.name },
            )
            vm.onAction(RoutinesAction.UndoDelete)
            assertEquals(
                listOf("Arms", "Legs"),
                vm.uiState.value.routines
                    .map { it.name },
            )
            assertNull(vm.uiState.value.deleted)
        }

    @Test
    fun `copying a routine twice gives two free names`() =
        runTest {
            val vm = RoutinesViewModel(routines, programs)
            collect(vm.uiState)
            vm.onAction(RoutinesAction.Copy(RoutineId(1)))
            vm.onAction(RoutinesAction.Copy(RoutineId(1)))
            assertEquals(
                listOf("Arms", "Legs", "Legs copy", "Legs copy 2"),
                vm.uiState.value.routines
                    .map { it.name },
            )
        }

    @Test
    fun `renaming to a taken name is refused and the draft kept`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.SetName("arms"))
            vm.onAction(RoutineEditorAction.CommitName)
            assertEquals(NameError.Taken, vm.uiState.value.nameError)
            assertEquals("arms", vm.uiState.value.name)
            assertEquals("Legs", legs().name)
            vm.onAction(RoutineEditorAction.SetName("Leg day"))
            vm.onAction(RoutineEditorAction.CommitName)
            assertEquals("Leg day", legs().name)
        }

    @Test
    fun `the item sheet writes when it closes and keeps the hidden settings`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.OpenItem(RoutineItemId(10)))
            vm.onAction(RoutineEditorAction.ToggleItemMore)
            vm.onAction(RoutineEditorAction.StepItem(ItemField.ProgressStep, up = true))
            vm.onAction(RoutineEditorAction.StepItem(ItemField.WarmupSets, up = true))
            assertNull(
                legs()
                    .items
                    .first()
                    .target.progressStep,
            )
            vm.onAction(RoutineEditorAction.CloseItem)
            vm.onAction(RoutineEditorAction.OpenItem(RoutineItemId(10)))
            vm.onAction(RoutineEditorAction.StepItem(ItemField.Sets, up = true))
            vm.onAction(RoutineEditorAction.CloseItem)
            val target = legs().items.first().target
            assertEquals(listOf(4, 1), listOf(target.sets, target.warmupSets))
            assertEquals(1.25, target.progressStep)
        }

    @Test
    fun `an exercise just added is the one the screen is asked to show, until it says it did`() =
        runTest {
            val picker = ExercisePickerViewModel(RoutinePickerTarget(RoutineId(1), routines), exercises)
            val editor = editor()
            collect(picker.uiState)
            collect(editor.uiState)
            assertNull(editor.uiState.value.shown)
            picker.onAction(ExercisePickerAction.Add(plank))
            val added = picker.uiState.value.added!!
            editor.onAction(RoutineEditorAction.Added(RoutineItemId(added.id), added.name))
            assertEquals(RoutineItemId(added.id), editor.uiState.value.shown)
            editor.onAction(RoutineEditorAction.ShownSeen)
            assertNull(editor.uiState.value.shown)
            // Nothing else asks for it: not a removal, not a move.
            editor.onAction(RoutineEditorAction.Move(RoutineItemId(10), 1))
            editor.onAction(RoutineEditorAction.Undo)
            assertNull(editor.uiState.value.shown)
        }

    @Test
    fun `a section name typed in the item sheet joins the sections to pick from, once and trimmed`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.OpenItem(RoutineItemId(10)))
            val before =
                vm.uiState.value.item!!
                    .sectionChoices
            assertTrue("Warm-up" in before && "Core" !in before)
            vm.onAction(RoutineEditorAction.SetItemSection(" Core "))
            assertEquals(
                before + "Core",
                vm.uiState.value.item!!
                    .sectionChoices,
            )
            vm.onAction(RoutineEditorAction.SetItemSection("Warm-up"))
            assertEquals(
                before,
                vm.uiState.value.item!!
                    .sectionChoices,
            )
            vm.onAction(RoutineEditorAction.SetItemSection("  "))
            assertEquals(
                before,
                vm.uiState.value.item!!
                    .sectionChoices,
            )
        }

    @Test
    fun `Record as the exercise's own type means no override`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.OpenItem(RoutineItemId(11)))
            vm.onAction(RoutineEditorAction.SetItemType(ExerciseType.RepsAndTime))
            assertEquals(
                Tracks(reps = true, time = true),
                vm.uiState.value.item!!
                    .target.tracks,
            )
            vm.onAction(RoutineEditorAction.SetItemType(ExerciseType.Time))
            assertNull(
                vm.uiState.value.item!!
                    .target.tracks,
            )
        }

    @Test
    fun `selected items are removed with Undo and come back in place`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.SetMode(EditorMode.Select))
            vm.onAction(RoutineEditorAction.ToggleSelected(RoutineItemId(10)))
            vm.onAction(RoutineEditorAction.DeleteSelected)
            assertEquals(EditorUndo.Removed(1), vm.uiState.value.undo)
            assertEquals(EditorMode.Normal, vm.uiState.value.mode)
            assertEquals(listOf("Plank"), legs().items.map { it.exercise.fields.name })
            vm.onAction(RoutineEditorAction.Undo)
            assertEquals(listOf("Squat", "Plank"), legs().items.map { it.exercise.fields.name })
            assertNull(vm.uiState.value.undo)
        }

    @Test
    fun `two removals before the offer goes both come back with one Undo`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            listOf(10L, 11L).forEach { id ->
                vm.onAction(RoutineEditorAction.SetMode(EditorMode.Select))
                vm.onAction(RoutineEditorAction.ToggleSelected(RoutineItemId(id)))
                vm.onAction(RoutineEditorAction.DeleteSelected)
            }
            assertEquals(EditorUndo.Removed(2), vm.uiState.value.undo)
            assertTrue(legs().items.isEmpty())
            vm.onAction(RoutineEditorAction.Undo)
            assertEquals(listOf("Squat", "Plank"), legs().items.map { it.exercise.fields.name })
        }

    @Test
    fun `selected items are copied to another routine with Undo`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.SetMode(EditorMode.Select))
            vm.onAction(RoutineEditorAction.ToggleSelected(RoutineItemId(11)))
            vm.onAction(RoutineEditorAction.ChooseCopyTarget)
            assertEquals(
                listOf("Arms"),
                vm.uiState.value.copyTargets!!
                    .map { it.name },
            )
            vm.onAction(RoutineEditorAction.CopySelectedTo(RoutineId(2)))
            assertEquals(EditorUndo.Copied(1, "Arms"), vm.uiState.value.undo)
            assertEquals(
                1,
                routines
                    .routine(RoutineId(2))
                    .first()!!
                    .items.size,
            )
            vm.onAction(RoutineEditorAction.Undo)
            assertEquals(
                0,
                routines
                    .routine(RoutineId(2))
                    .first()!!
                    .items.size,
            )
        }

    @Test
    fun `the picker adds an existing exercise in one tap, and Undo removes the row but keeps the exercise`() =
        runTest {
            val picker = ExercisePickerViewModel(RoutinePickerTarget(RoutineId(1), routines), exercises)
            val editor = editor()
            collect(picker.uiState)
            collect(editor.uiState)
            assertEquals(
                listOf("Plank", "Squat"),
                picker.uiState.value.exercises
                    .map { it.fields.name },
            )
            picker.onAction(ExercisePickerAction.Add(plank))
            val added = picker.uiState.value.added!!
            editor.onAction(RoutineEditorAction.Added(RoutineItemId(added.id), added.name))
            picker.onAction(ExercisePickerAction.AddedSeen)
            assertEquals(3, legs().items.size)
            editor.onAction(RoutineEditorAction.Undo)
            assertEquals(2, legs().items.size)
            assertTrue(exercises.all.value.any { it.id == ExerciseId(2) })
        }

    @Test
    fun `Create and add makes a timed exercise from the picker, and a taken name writes nothing`() =
        runTest {
            val picker = ExercisePickerViewModel(RoutinePickerTarget(RoutineId(1), routines), exercises)
            collect(picker.uiState)
            picker.onAction(ExercisePickerAction.Search("Side plank"))
            assertTrue(picker.uiState.value.canCreateQuery)
            picker.onAction(ExercisePickerAction.StartCreate)
            assertEquals(
                "Side plank",
                picker.uiState.value.form!!
                    .name,
            )
            picker.onAction(ExercisePickerAction.SetFormType(ExerciseType.Time))
            picker.onAction(ExercisePickerAction.StepForm(DefaultField.Duration, up = false))
            picker.onAction(ExercisePickerAction.SetFormName("plank"))
            picker.onAction(ExercisePickerAction.CreateAndAdd)
            assertEquals(NameError.Taken, picker.uiState.value.formError)
            assertEquals(3, exercises.all.value.size)
            picker.onAction(ExercisePickerAction.SetFormName("Side plank"))
            picker.onAction(ExercisePickerAction.CreateAndAdd)
            assertNotNull(picker.uiState.value.added)
            val created = legs().items.last()
            assertEquals("Side plank", created.exercise.fields.name)
            assertEquals(Tracks(time = true), created.exercise.fields.tracks)
            assertEquals(
                40,
                created.target.duration.inWholeSeconds
                    .toInt(),
            )
        }

    @Test
    fun `closing the picker discards the draft`() =
        runTest {
            val picker = ExercisePickerViewModel(RoutinePickerTarget(RoutineId(1), routines), exercises)
            collect(picker.uiState)
            picker.onAction(ExercisePickerAction.StartCreate)
            picker.onAction(ExercisePickerAction.SetFormName("Draft"))
            picker.onAction(ExercisePickerAction.Reset)
            assertNull(picker.uiState.value.form)
            assertEquals(3, exercises.all.value.size)
        }

    @Test
    fun `the picker filters by category and leaves archived exercises out`() =
        runTest {
            val picker = ExercisePickerViewModel(RoutinePickerTarget(RoutineId(1), routines), exercises)
            collect(picker.uiState)
            assertEquals(listOf("Abs", "Quads"), picker.uiState.value.categories)
            picker.onAction(ExercisePickerAction.Category("Abs"))
            assertEquals(
                listOf("Plank"),
                picker.uiState.value.exercises
                    .map { it.fields.name },
            )
            picker.onAction(ExercisePickerAction.Category("Abs"))
            assertFalse(
                picker.uiState.value.exercises
                    .any { it.archived },
            )
            assertEquals(2, picker.uiState.value.exercises.size)
        }

    @Test
    fun `Use today copies the routine into an empty today and goes to Today`() =
        runTest {
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.UseToday)
            assertTrue(vm.uiState.value.usedToday)
            val today = days.day(LocalDate(2026, 11, 9)).first()!!
            assertEquals(listOf("Squat", "Plank"), today.blocks.map { it.exercise.fields.name })
            assertEquals("Legs", today.name)
            vm.onAction(RoutineEditorAction.UsedTodaySeen)
            assertFalse(vm.uiState.value.usedToday)
        }

    @Test
    fun `Use today asks before replacing today's exercises`() =
        runTest {
            days.addExercise(LocalDate(2026, 11, 9), ExerciseId(2))
            val vm = editor()
            collect(vm.uiState)
            vm.onAction(RoutineEditorAction.UseToday)
            assertTrue(vm.uiState.value.confirmUseToday)
            assertFalse(vm.uiState.value.usedToday)
            vm.onAction(RoutineEditorAction.CancelUseToday)
            assertEquals(
                1,
                days
                    .day(LocalDate(2026, 11, 9))
                    .first()!!
                    .blocks.size,
            )
            vm.onAction(RoutineEditorAction.UseToday)
            vm.onAction(RoutineEditorAction.ConfirmUseToday)
            assertTrue(vm.uiState.value.usedToday)
            assertEquals(
                2,
                days
                    .day(LocalDate(2026, 11, 9))
                    .first()!!
                    .blocks.size,
            )
        }

    @Test
    fun `the Load column is each exercise's last load lifted on an earlier day, and blank when never done`() =
        runTest {
            // Squat: 100 kg on Nov 5, then 105 lb as the last set on Nov 7; today's 200 kg doesn't count.
            suspend fun lift(
                date: LocalDate,
                vararg loads: Pair<Double, app.fit.core.WeightUnit>,
            ) {
                val block = days.addExercise(date, squat.id)!!
                val sets =
                    days
                        .dayOfBlock(block)
                        .first()!!
                        .blocks
                        .single { it.id == block }
                        .sets
                loads.forEachIndexed { i, (weight, unit) ->
                    days.editSet(
                        sets[i].id,
                        app.fit.day.SetEdit
                            .Weight(weight),
                    )
                    days.editSet(
                        sets[i].id,
                        app.fit.day.SetEdit
                            .Unit(unit),
                    )
                    days.setDone(sets[i].id, true)
                }
            }
            lift(LocalDate(2026, 11, 5), 100.0 to app.fit.core.WeightUnit.Kg)
            lift(LocalDate(2026, 11, 7), 110.0 to app.fit.core.WeightUnit.Kg, 105.0 to app.fit.core.WeightUnit.Lb)
            lift(LocalDate(2026, 11, 9), 200.0 to app.fit.core.WeightUnit.Kg)
            val vm = editor()
            collect(vm.uiState)
            assertEquals(
                mapOf(squat.id to app.fit.day.LastLoad(105.0, app.fit.core.WeightUnit.Lb)),
                vm.uiState.value.lastLoads,
            )
            // Plank was never done: no entry, so the column stays blank.
            assertNull(vm.uiState.value.lastLoads[plank.id])
        }
}
