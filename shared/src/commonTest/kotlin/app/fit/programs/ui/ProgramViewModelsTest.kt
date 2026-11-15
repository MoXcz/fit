// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs.ui

import androidx.lifecycle.ViewModelStore
import app.fit.day.FixedClock
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.exercise
import app.fit.programs.Deload
import app.fit.programs.FakeProgramRepository
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.programs.ProgramWeek
import app.fit.routines.FakeRoutineRepository
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.ui.NameError
import app.fit.routines.ui.RoutinesAction
import app.fit.routines.ui.RoutinesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramViewModelsTest {
    private val squat = exercise(1, "Squat", "Quads")
    private val exercises = FakeExerciseRepository(listOf(squat))

    // 3 × (45 s + 120 s) = 8:15 each.
    private fun routine(
        id: Long,
        name: String,
    ) = Routine(
        RoutineId(id),
        null,
        name,
        "",
        listOf(RoutineItem(RoutineItemId(id * 10), squat, ItemTarget(sets = 3, reps = 5, rest = 120.seconds, duration = 45.seconds))),
    )

    private val upper = routine(1, "Upper")
    private val lower = routine(2, "Lower")
    private val routines = FakeRoutineRepository(exercises, listOf(upper, lower))
    private val split =
        Program(
            ProgramId(1),
            name = "Split",
            active = true,
            startsOn = LocalDate(2026, 11, 2),
            deloadEvery = 2,
            days = mapOf(DayOfWeek.MONDAY to upper.id, DayOfWeek.THURSDAY to lower.id),
        )
    private val home = Program(ProgramId(2), name = "Home")
    private val programs = FakeProgramRepository(routines, listOf(split, home))
    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    // The app's scope, running at once like the screens' Main.
    private fun TestScope.appScope() = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))

    private fun TestScope.list() = ProgramsViewModel(programs, routines, clock, TimeZone.UTC).also { collect(it.uiState) }

    private fun TestScope.editor(id: ProgramId = split.id) =
        ProgramEditorViewModel(id.value, programs, routines, clock, TimeZone.UTC, appScope()).also { collect(it.uiState) }

    private suspend fun program(id: ProgramId = split.id) = programs.program(id).first()!!

    @Test
    fun `the list shows the active program first, with its training days, weekly time and week`() =
        runTest {
            val rows = list().uiState.value.programs
            assertEquals(listOf("Split", "Home"), rows.map { it.name })
            val split = rows.first()
            assertTrue(split.active)
            assertEquals(2, split.trainingDays)
            assertEquals(16.5.minutes, split.weekly)
            assertEquals(ProgramWeek(2, deload = true), split.week)
        }

    @Test
    fun `a new program is named, opened, and a taken name says so`() =
        runTest {
            val vm = list()
            vm.onAction(ProgramsAction.StartNew)
            vm.onAction(ProgramsAction.SetNewName("home"))
            vm.onAction(ProgramsAction.CreateNew)
            assertEquals(NameError.Taken, vm.uiState.value.nameError)
            vm.onAction(ProgramsAction.SetNewName("PPL"))
            vm.onAction(ProgramsAction.CreateNew)
            assertNull(vm.uiState.value.newName)
            val opened = vm.uiState.value.opened!!
            assertEquals("PPL", program(opened).name)
            assertFalse(program(opened).active)
        }

    @Test
    fun `a copy keeps the schedule and isn't active`() =
        runTest {
            val vm = list()
            vm.onAction(ProgramsAction.Copy(split.id))
            val copy =
                vm.uiState.value.programs
                    .single { it.name == "Split copy" }
            assertFalse(copy.active)
            assertEquals(split.days, program(copy.id).days)
        }

    @Test
    fun `deleting a program offers Undo, which brings it back as it was`() =
        runTest {
            val vm = list()
            vm.onAction(ProgramsAction.Delete(split.id))
            assertEquals("Split", vm.uiState.value.deleted)
            assertEquals(
                listOf("Home"),
                vm.uiState.value.programs
                    .map { it.name },
            )
            assertNull(programs.active.first())
            vm.onAction(ProgramsAction.UndoDelete)
            assertNull(vm.uiState.value.deleted)
            assertEquals(split.copy(edited = false), program().copy(edited = false))
            assertEquals(split.id, programs.active.first()?.id)
        }

    @Test
    fun `a deleted active program comes back inactive when another became active meanwhile`() =
        runTest {
            val vm = list()
            vm.onAction(ProgramsAction.Delete(split.id))
            programs.setActive(home.id, true)
            vm.onAction(ProgramsAction.UndoDelete)
            assertFalse(program().active)
            assertEquals(home.id, programs.active.first()?.id)
        }

    @Test
    fun `only one program is active, so activating one deactivates the other, and either can be turned off`() =
        runTest {
            val vm = editor(home.id)
            vm.onAction(ProgramEditorAction.SetActive(true))
            assertTrue(program(home.id).active)
            assertFalse(program().active)
            vm.onAction(ProgramEditorAction.SetActive(false))
            assertNull(programs.active.first())
        }

    @Test
    fun `the editor lists Monday to Sunday with rest days, and a day takes a routine or rest`() =
        runTest {
            val vm = editor()
            val days = vm.uiState.value.days
            assertEquals(DayOfWeek.entries, days.map { it.weekday })
            assertEquals(listOf("Upper", null, null, "Lower", null, null, null), days.map { it.routine?.name })
            vm.onAction(ProgramEditorAction.OpenDay(DayOfWeek.SATURDAY))
            assertEquals(DayOfWeek.SATURDAY, vm.uiState.value.choosing)
            vm.onAction(ProgramEditorAction.ChooseRoutine(lower.id))
            assertNull(vm.uiState.value.choosing)
            vm.onAction(ProgramEditorAction.OpenDay(DayOfWeek.MONDAY))
            vm.onAction(ProgramEditorAction.ChooseRoutine(null))
            assertEquals(mapOf(DayOfWeek.THURSDAY to lower.id, DayOfWeek.SATURDAY to lower.id), program().days)
            assertEquals(2, vm.uiState.value.trainingDays)
        }

    @Test
    fun `the editor sets week 1's start, deload every N weeks and the deload percent`() =
        runTest {
            val vm = editor(home.id)
            assertNull(vm.uiState.value.thisWeek)
            vm.onAction(ProgramEditorAction.OpenStart)
            assertTrue(vm.uiState.value.pickingStart)
            vm.onAction(ProgramEditorAction.SetStart(LocalDate(2026, 10, 28)))
            assertFalse(vm.uiState.value.pickingStart)
            assertEquals(ProgramWeek(3, deload = false), vm.uiState.value.thisWeek)
            vm.onAction(ProgramEditorAction.StepDeloadEvery(up = true))
            vm.onAction(ProgramEditorAction.StepDeloadEvery(up = true))
            assertEquals(3, program(home.id).deloadEvery)
            assertEquals(ProgramWeek(3, deload = true), vm.uiState.value.thisWeek)
            vm.onAction(ProgramEditorAction.StepDeloadPercent(up = true))
            assertEquals(Deload.DEFAULT_PERCENT + 5, program(home.id).deloadPercent)
            vm.onAction(ProgramEditorAction.SetStart(null))
            assertNull(vm.uiState.value.thisWeek)
        }

    @Test
    fun `the name and notes are kept when their fields are left, and a taken name stays with its error`() =
        runTest {
            val vm = editor()
            vm.onAction(ProgramEditorAction.SetName("Home"))
            vm.onAction(ProgramEditorAction.CommitName)
            assertEquals(NameError.Taken, vm.uiState.value.nameError)
            assertEquals("Split", program().name)
            vm.onAction(ProgramEditorAction.SetName("Upper/lower"))
            vm.onAction(ProgramEditorAction.CommitName)
            assertNull(vm.uiState.value.nameError)
            assertEquals("Upper/lower", program().name)
            vm.onAction(ProgramEditorAction.SetNotes("Block 1"))
            vm.onAction(ProgramEditorAction.CommitNotes)
            assertEquals("Block 1", program().notes)
        }

    @Test
    fun `a name and notes still being typed when the editor closes are kept`() =
        runTest {
            val store = ViewModelStore()
            val vm = editor()
            store.put("editor", vm)
            vm.onAction(ProgramEditorAction.SetName("Block 2"))
            vm.onAction(ProgramEditorAction.SetNotes("Lighter"))
            store.clear()
            runCurrent()
            assertEquals("Block 2", program().name)
            assertEquals("Lighter", program().notes)
        }

    @Test
    fun `a routine a program uses leaves its days as rest days when deleted, says so, and Undo gives them back`() =
        runTest {
            val vm = RoutinesViewModel(routines, programs).also { collect(it.uiState) }
            collect(programs.programs)
            vm.onAction(RoutinesAction.Delete(upper.id))
            assertEquals("Upper", vm.uiState.value.deleted)
            assertEquals(listOf("Split"), vm.uiState.value.restIn)
            assertEquals(mapOf(DayOfWeek.THURSDAY to lower.id), program().days)
            vm.onAction(RoutinesAction.UndoDelete)
            assertEquals(split.days, program().days)
        }

    @Test
    fun `a routine no program uses is deleted with the plain message`() =
        runTest {
            val unused = routine(3, "Spare")
            routines.all.value = routines.all.value + unused
            val vm = RoutinesViewModel(routines, programs).also { collect(it.uiState) }
            vm.onAction(RoutinesAction.Delete(unused.id))
            assertEquals("Spare", vm.uiState.value.deleted)
            assertEquals(emptyList(), vm.uiState.value.restIn)
        }

    @Test
    fun `activating a program without a start starts week 1 this week, and one with a start keeps it`() =
        runTest {
            val vm = editor(home.id)
            vm.onAction(ProgramEditorAction.SetActive(true))
            assertEquals(LocalDate(2026, 11, 9), program(home.id).startsOn)
            assertEquals(ProgramWeek(1, deload = false), vm.uiState.value.thisWeek)
            editor().onAction(ProgramEditorAction.SetActive(true))
            assertEquals(LocalDate(2026, 11, 2), program().startsOn)
        }

    @Test
    fun `a taken name shows while it's typed, and leaving with it keeps the old name`() =
        runTest {
            val store = ViewModelStore()
            val vm = editor()
            store.put("editor", vm)
            vm.onAction(ProgramEditorAction.SetName("HOME"))
            assertEquals(NameError.Taken, vm.uiState.value.nameError)
            vm.onAction(ProgramEditorAction.SetName("Homer"))
            assertNull(vm.uiState.value.nameError)
            vm.onAction(ProgramEditorAction.SetName("home"))
            store.clear()
            runCurrent()
            assertEquals("Split", program().name)
        }

    @Test
    fun `a change made just before Back still lands, on the app scope`() =
        runTest {
            val store = ViewModelStore()
            val vm = editor(home.id)
            store.put("editor", vm)
            vm.onAction(ProgramEditorAction.OpenDay(DayOfWeek.FRIDAY))
            vm.onAction(ProgramEditorAction.ChooseRoutine(upper.id))
            vm.onAction(ProgramEditorAction.StepDeloadEvery(up = true))
            store.clear()
            runCurrent()
            assertEquals(mapOf(DayOfWeek.FRIDAY to upper.id), program(home.id).days)
            assertEquals(2, program(home.id).deloadEvery)
        }
}
