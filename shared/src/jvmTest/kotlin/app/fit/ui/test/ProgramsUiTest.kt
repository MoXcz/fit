// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.FakeBodyWeightRepository
import app.fit.bodyweight.ui.BodyWeightRoute
import app.fit.bodyweight.ui.BodyWeightViewModel
import app.fit.core.WeightUnit
import app.fit.day.FixedClock
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.exercise
import app.fit.programs.FakeProgramRepository
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.programs.ui.ProgramEditorRoute
import app.fit.programs.ui.ProgramEditorViewModel
import app.fit.programs.ui.ProgramsRoute
import app.fit.programs.ui.ProgramsViewModel
import app.fit.routines.FakeRoutineRepository
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.settings.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Train → Programs, the program editor, and More → Body weight.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class ProgramsUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z"))
    private val today = LocalDate(2026, 11, 9)
    private val squat = exercise(1, "Squat", "Quads")
    private val exercises = FakeExerciseRepository(listOf(squat))

    private fun routine(
        id: Long,
        name: String,
        sets: Int,
    ) = Routine(
        RoutineId(id),
        null,
        name,
        "",
        List(
            4,
        ) { RoutineItem(RoutineItemId(id * 10 + it), squat, ItemTarget(sets = sets, reps = 8, rest = 120.seconds, duration = 45.seconds)) },
    )

    private val upperA = routine(1, "Upper A", 3)
    private val lowerA = routine(2, "Lower A", 4)
    private val upperB = routine(3, "Upper B", 3)
    private val lowerB = routine(4, "Lower B", 4)
    private val routines = FakeRoutineRepository(exercises, listOf(upperA, lowerA, upperB, lowerB))
    private val split =
        Program(
            ProgramId(1),
            name = "4 day upper/lower",
            notes = "Heavy first, then volume.",
            active = true,
            startsOn = LocalDate(2026, 10, 19),
            deloadEvery = 4,
            days =
                mapOf(
                    DayOfWeek.MONDAY to upperA.id,
                    DayOfWeek.TUESDAY to lowerA.id,
                    DayOfWeek.THURSDAY to upperB.id,
                    DayOfWeek.FRIDAY to lowerB.id,
                ),
        )
    private val programs =
        FakeProgramRepository(
            routines,
            listOf(
                split,
                Program(
                    ProgramId(2),
                    name = "Home",
                    days =
                        mapOf(DayOfWeek.SATURDAY to upperA.id),
                ),
            ),
        )

    @Test
    fun `the Programs list shows the active one first with its week, and a delete offers Undo`() =
        runPhoneUiTest {
            val vm = ProgramsViewModel(programs, routines, clock, TimeZone.UTC).clearedAfterTest()
            setScreen { ProgramsRoute(onOpen = {}, viewModel = vm) }
            waitForIdle()
            onNodeWithText("Active").assertIsDisplayed()
            onNodeWithText("4 days a week · ~154 min a week · Week 4 · Deload week").assertIsDisplayed()
            onNodeWithText("1 day a week · ~33 min a week").assertIsDisplayed()
            onNodeWithText("New program").assertIsDisplayed()
            screenshot("programs-list")
            onAllNodesWithContentDescription("More options")[1].performClick()
            waitForIdle()
            onNodeWithText("Delete").performClick()
            waitForIdle()
            onNodeWithText("Deleted Home").assertIsDisplayed()
            screenshot("programs-deleted-undo")
            onNodeWithText("Undo").performClick()
            waitForIdle()
            onNodeWithText("Home").assertIsDisplayed()
        }

    private fun editor() = ProgramEditorViewModel(split.id.value, programs, routines, clock, TimeZone.UTC, MainScope()).clearedAfterTest()

    @Test
    fun `the program editor shows the week with rest days, its total, and a day takes another routine from a sheet`() =
        runPhoneUiTest {
            val vm = editor()
            setScreen { ProgramEditorRoute(id = split.id, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNodeWithText("4 day upper/lower").assertIsDisplayed()
            onNodeWithText("4 days a week · ~154 min a week").assertIsDisplayed()
            onNodeWithText("Monday").assertIsDisplayed()
            onAllNodesWithText("Rest").fetchSemanticsNodes().size.let { assertEquals(3, it) }
            screenshot("program-editor")
            onNodeWithText("Wednesday").performClick()
            waitForIdle()
            screenshot("program-editor-day-sheet")
            onAllNodesWithText("Lower B").onFirst().let { onAllNodesWithText("Lower B")[1].performClick() }
            waitForIdle()
            assertEquals(lowerB.id, runBlocking { programs.program(split.id).first()!!.days[DayOfWeek.WEDNESDAY] })
            onNode(hasScrollAction()).performScrollToNode(hasText("Deload loads by"))
            onNodeWithText("This week: Week 4 · Deload week").assertIsDisplayed()
            onNodeWithText("4 weeks").assertIsDisplayed()
            onNodeWithText("−10%").assertIsDisplayed()
            screenshot("program-editor-weeks")
        }

    @Test
    fun `on a 360 x 780 dp phone the program editor fits`() =
        runPhoneUiTest(360, 780) {
            val vm = editor()
            setScreen { ProgramEditorRoute(id = split.id, onBack = {}, viewModel = vm) }
            waitForIdle()
            onNodeWithText("Sunday").assertIsDisplayed()
            screenshot("program-editor-360x780")
        }

    @Test
    fun `Body weight shows today's reading, the line of the last ones and every reading, and a swipe deletes one with Undo`() =
        runPhoneUiTest {
            val readings =
                (0 until 8).map { i ->
                    BodyWeight(today.minus(i * 3, DateTimeUnit.DAY), 82.5 - i * 0.3 + (i % 3) * 0.2, WeightUnit.Kg)
                } + BodyWeight(LocalDate(2026, 9, 1), 182.0, WeightUnit.Lb)
            val repo = FakeBodyWeightRepository(readings.map { it.copy(weight = (it.weight * 10).toLong() / 10.0) })
            val vm = BodyWeightViewModel(repo, FakeSettingsRepository(), clock, TimeZone.UTC).clearedAfterTest()
            setScreen { BodyWeightRoute(onBack = {}, viewModel = vm) }
            waitForIdle()
            onNodeWithText("Log today").assertIsDisplayed()
            onAllNodesWithText("82.5 kg").onFirst().assertIsDisplayed()
            screenshot("bodyweight")
            onNodeWithText("Log today").performClick()
            waitForIdle()
            onNodeWithText("Empty deletes the reading").assertIsDisplayed()
            screenshot("bodyweight-log")
            // Text that isn't a weight keeps the dialog open and today's reading.
            onNode(hasSetTextAction()).performTextReplacement("8l.5")
            waitForIdle()
            onNodeWithText("Save").performClick()
            waitForIdle()
            onNodeWithText("Enter a weight up to 1000").assertIsDisplayed()
            screenshot("bodyweight-invalid")
            onNode(hasSetTextAction()).performTextReplacement("82")
            onNodeWithText("Save").performClick()
            waitForIdle()
            onNodeWithText("Body weight was 82.5 kg").assertIsDisplayed()
            screenshot("bodyweight-replaced-undo")
            onNodeWithText("Undo").performClick()
            waitForIdle()
            onNodeWithText("Log today").performClick()
            waitForIdle()
            onNodeWithText("Cancel").performClick()
            waitForIdle()
            onNode(hasScrollAction()).performScrollToNode(hasText("182 lb"))
            onNodeWithText("182 lb").performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Deleted 182 lb").assertIsDisplayed()
            assertEquals(8, runBlocking { repo.readings.first().size })
            onNodeWithText("Undo").performClick()
            waitForIdle()
            assertEquals(9, runBlocking { repo.readings.first().size })
        }
}
