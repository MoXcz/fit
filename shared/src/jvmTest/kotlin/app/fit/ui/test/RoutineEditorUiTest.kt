// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import app.fit.day.SetEdit
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.ui.ExercisePickerViewModel
import app.fit.routines.ui.RoutineEditorRoute
import app.fit.routines.ui.RoutineEditorViewModel
import app.fit.routines.ui.RoutinePickerTarget
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RoutineEditorUiTest {
    // Not a test dispatcher: runTest would adopt its scheduler, and the ViewModels' once-a-second
    // tickers would keep it from ever going idle. Timers run on the fixture's TestClock either way.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the routine editor opens an item's sheet and adds an exercise from the picker`() =
        runTest {
            val f = WorkoutFixture(this)
            val id = RoutineId(1)
            f.routines.all.value =
                listOf(
                    Routine(
                        id,
                        null,
                        "Push",
                        "",
                        listOf(
                            RoutineItem(RoutineItemId(10), bench, ItemTarget(sets = 3, reps = 8, rest = 90.seconds, duration = 45.seconds)),
                        ),
                    ),
                )
            // Bench was last lifted yesterday at 57.5 kg: the Load column shows it.
            val yesterday = f.days.addExercise(LocalDate(2026, 11, 8), bench.id)!!
            f.days
                .dayOfBlock(yesterday)
                .first()!!
                .blocks
                .single()
                .sets
                .forEach {
                    f.days.editSet(it.id, SetEdit.Weight(57.5))
                    f.days.setDone(it.id, true)
                }
            val editor = RoutineEditorViewModel(id.value, f.routines, f.days, f.clock, TimeZone.UTC).clearedAfterTest()
            val picker = ExercisePickerViewModel(RoutinePickerTarget(id, f.routines), f.exercises).clearedAfterTest()
            runPhoneUiTest {
                setScreen { RoutineEditorRoute(id = id, onBack = {}, onUsedToday = {}, viewModel = editor, picker = picker) }
                onNodeWithText("Push").assertIsDisplayed()
                // The action group under the title, a dense row, and the Add exercise button.
                onNodeWithText("Use today").assertIsDisplayed()
                onNodeWithText("Reorder").assertIsDisplayed()
                onNodeWithText("Select").assertIsDisplayed()
                onNodeWithText("3 × 8").assertIsDisplayed()
                onNodeWithText("57.5 kg").assertIsDisplayed()
                onNodeWithText("1:30").assertIsDisplayed()
                onNodeWithText("Add exercise", useUnmergedTree = true).assertIsDisplayed()
                screenshot("routine-editor")
                onNodeWithText("Bench press").performClick()
                waitForIdle()
                // The item sheet: the basic targets, and More for the rest.
                onNodeWithText("More").assertIsDisplayed()
                screenshot("routine-item-sheet")
                pressBack()
                onNodeWithText("More").assertDoesNotExist()
                onNodeWithText("Add exercise", useUnmergedTree = true).performClick()
                waitForIdle()
                screenshot("routine-picker")
                onAllNodesWithText("Curl")[0].performClick()
                waitForIdle()
                onNodeWithText("Curl").assertIsDisplayed()
                screenshot("routine-after-add")
                assertEquals(listOf("Bench press", "Curl"), names(f))
                // Reorder: drag Curl's handle above Bench press; the FAB steps aside.
                onNodeWithText("Reorder").performClick()
                waitForIdle()
                onNodeWithText("Add exercise", useUnmergedTree = true).assertDoesNotExist()
                onAllNodesWithContentDescription("Drag to reorder", useUnmergedTree = true)[1].performTouchInput {
                    down(center)
                    repeat(10) { moveBy(Offset(0f, -20f)) }
                    up()
                }
                waitForIdle()
                assertEquals(listOf("Curl", "Bench press"), names(f))
                screenshot("routine-reorder")
                pressBack()
                onNodeWithText("Use today").assertIsDisplayed()
            }
        }

    @Test
    fun `an exercise is marked as warm-up on the item sheet's first level, and More holds no second section choice`() =
        runTest {
            val f = WorkoutFixture(this)
            val id = RoutineId(1)
            f.routines.all.value =
                listOf(
                    Routine(
                        id,
                        null,
                        "Push",
                        "",
                        listOf(
                            RoutineItem(RoutineItemId(10), bench, ItemTarget(sets = 3, reps = 8, rest = 90.seconds, duration = 45.seconds)),
                        ),
                    ),
                )
            val section = {
                f.routines.all.value
                    .single()
                    .items
                    .single()
                    .target.section
            }
            listOf(411 to 891, 360 to 780).forEach { (width, height) ->
                f.routines.saveItem(RoutineItemId(10), ItemTarget(sets = 3, reps = 8, rest = 90.seconds, duration = 45.seconds))
                val editor = RoutineEditorViewModel(id.value, f.routines, f.days, f.clock, TimeZone.UTC).clearedAfterTest()
                val picker = ExercisePickerViewModel(RoutinePickerTarget(id, f.routines), f.exercises).clearedAfterTest()
                runPhoneUiTest(width, height) {
                    setScreen { RoutineEditorRoute(id = id, onBack = {}, onUsedToday = {}, viewModel = editor, picker = picker) }
                    onNodeWithText("Bench press").performClick()
                    waitForIdle()
                    // Beside sets, reps and rest, with More still closed.
                    onNodeWithText("More").assertIsDisplayed()
                    onNodeWithText("Section name").assertDoesNotExist()
                    onNodeWithText("Main").assertIsSelected()
                    onNodeWithText("Warm-up").performClick()
                    waitForIdle()
                    onNodeWithText("Warm-up").assertIsSelected()
                    screenshot(if (width == 411) "routine-item-sheet-section" else "routine-item-sheet-section-small-phone")
                    // More keeps only the field for a section of one's own: the chips aren't repeated.
                    onNodeWithText("More").performClick()
                    waitForIdle()
                    onNodeWithText("Section name").assertExists()
                    assertEquals(1, onAllNodesWithText("Main").fetchSemanticsNodes().size)
                    // A name typed there shows as a chip at once, selected, also with More closed again.
                    onNode(hasSetTextAction() and hasText("Section name")).performTextReplacement("Core")
                    waitForIdle()
                    onNodeWithText("Less").performClick()
                    waitForIdle()
                    onNodeWithText("Core").assertIsSelected()
                    onNodeWithText("Warm-up").performClick()
                    waitForIdle()
                    onNodeWithText("Core").assertDoesNotExist()
                    pressBack()
                    waitForIdle()
                    assertEquals("Warm-up", section())
                }
            }
        }

    private fun names(f: WorkoutFixture) =
        f.routines.all.value
            .single()
            .items
            .map { it.exercise.fields.name }
}
