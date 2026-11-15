// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.fit.exercises.exercise
import app.fit.routines.ItemTarget
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.ui.ExercisePickerViewModel
import app.fit.routines.ui.RoutineEditorRoute
import app.fit.routines.ui.RoutineEditorViewModel
import app.fit.routines.ui.RoutinePickerTarget
import app.fit.routines.ui.RoutinesRoute
import app.fit.routines.ui.RoutinesViewModel
import app.fit.ui.UNDO_BAR_TAG
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The Undo bar over long lists: it must never keep the last rows out of reach.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class UndoBarUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val id = RoutineId(1)

    // A routine of [count] exercises, "Exercise 1" to "Exercise <count>".
    private fun longRoutine(
        f: WorkoutFixture,
        count: Int,
    ) {
        f.routines.all.value =
            listOf(
                Routine(
                    id,
                    null,
                    "Full body",
                    "",
                    (1..count).map {
                        RoutineItem(
                            RoutineItemId(it.toLong()),
                            exercise(100L + it, "Exercise $it"),
                            ItemTarget(sets = 3, reps = 8, rest = 90.seconds, duration = 45.seconds),
                        )
                    },
                ),
            )
    }

    private fun TestScope.editor(
        f: WorkoutFixture,
        widthDp: Int = 411,
        heightDp: Int = 891,
        fontScale: Float = 1f,
        // What the app pads a screen by: the navigation bar's height.
        bottomInset: Dp = 0.dp,
        block: SkikoComposeUiTest.() -> Unit,
    ) {
        val editor = RoutineEditorViewModel(id.value, f.routines, f.days, f.clock, TimeZone.UTC).clearedAfterTest()
        val picker = ExercisePickerViewModel(RoutinePickerTarget(id, f.routines), f.exercises).clearedAfterTest()
        runPhoneUiTest(widthDp, heightDp, fontScale) {
            setScreen {
                RoutineEditorRoute(
                    id = id,
                    onBack = {},
                    onUsedToday = {},
                    modifier = Modifier.padding(bottom = bottomInset).testTag("screen"),
                    viewModel = editor,
                    picker = picker,
                )
            }
            waitForIdle()
            block()
        }
    }

    private fun SkikoComposeUiTest.scrollToEnd() {
        repeat(4) { onNode(hasScrollAction()).performTouchInput { swipeUp() } }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.addButton() =
        onNode(hasClickAction() and hasAnyDescendant(hasText("Add exercise")), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun names(f: WorkoutFixture) =
        f.routines.all.value
            .single()
            .items
            .map { it.exercise.fields.name }

    // The row or button showing [text] ends above the bar: nothing of it is under the bar.
    private fun SkikoComposeUiTest.assertClearOfBar(text: String) {
        val row = onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue(row.bottom <= bar.top, "$text ($row) is under the Undo bar ($bar)")
    }

    @Test
    fun `after removing an exercise from a long routine, the last one can be scrolled clear of the Undo bar and opened`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                onNodeWithText("Removed 1 exercise").assertIsDisplayed()
                scrollToEnd()
                onNodeWithText("Removed 1 exercise").assertIsDisplayed()
                assertClearOfBar("Exercise 14")
                screenshot("undo-bar-long-routine")
                onNodeWithText("Exercise 14").performClick()
                waitForIdle()
                // Its sheet opened: the tap reached the row, not the bar.
                onNodeWithText("Remove from routine").assertIsDisplayed()
                assertEquals(13, names(f).size)
            }
        }

    @Test
    fun `an exercise just added to a long routine is scrolled into view, clear of the Undo bar and Add exercise, and can be opened`() =
        runTest {
            listOf(411 to 891, 360 to 780).forEach { (width, height) ->
                val f = WorkoutFixture(this)
                longRoutine(f, 12)
                editor(f, width, height) {
                    onNodeWithText("Add exercise", useUnmergedTree = true).performClick()
                    waitForIdle()
                    onAllNodesWithText("Curl")[0].performClick()
                    waitForIdle()
                    // No scrolling by hand: the row came to the user.
                    onNodeWithText("Added Curl").assertIsDisplayed()
                    onNodeWithText("Curl").assertIsDisplayed()
                    assertClearOfBar("Curl")
                    screenshot(if (width == 411) "undo-bar-after-add" else "undo-bar-after-add-small-phone")
                    onNodeWithText("Curl").performClick()
                    waitForIdle()
                    onNodeWithText("Remove from routine").assertIsDisplayed()
                }
            }
        }

    @Test
    fun `removing an exercise doesn't move the list`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                onNodeWithText("Full body").assertIsDisplayed()
                onNodeWithText("Exercise 2").assertIsDisplayed()
            }
        }

    @Test
    fun `on a 360 x 780 dp phone a long routine's last exercise is reachable with the Undo bar up`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f, 360, 780) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                scrollToEnd()
                onNodeWithText("Removed 1 exercise").assertIsDisplayed()
                assertClearOfBar("Exercise 14")
                screenshot("undo-bar-long-routine-small-phone")
                onNodeWithText("Exercise 14").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").assertIsDisplayed()
            }
        }

    // The JVM window has no system bars, so the navigation bar is the padding the app gives a
    // screen for it (48 dp is 3-button navigation).
    @Test
    fun `with a navigation bar under the screen the Undo bar is above Add exercise, which keeps its margin, and the last row is clear`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f, bottomInset = 48.dp) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                scrollToEnd()
                val screen = onNodeWithTag("screen").fetchSemanticsNode().boundsInRoot
                val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
                val add = addButton()
                assertTrue(bar.bottom <= add.top, "the bar ($bar) is over Add exercise ($add)")
                // 16 dp above the navigation bar (the tagged area ends where it starts), not twice its height.
                assertEquals(with(density) { 16.dp.roundToPx() }, (screen.bottom - add.bottom).toInt())
                assertClearOfBar("Exercise 14")
                screenshot("undo-bar-navigation-bar")
                onNodeWithText("Exercise 14").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").assertIsDisplayed()
            }
        }

    @Test
    fun `at 1_3x text a long routine's last exercise still scrolls clear of the Undo bar`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f, 360, 780, fontScale = 1.3f) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNode(hasScrollAction() and hasAnyDescendant(hasText("Remove from routine")))
                    .performScrollToNode(hasText("Remove from routine"))
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                scrollToEnd()
                assertClearOfBar("Exercise 14")
                val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
                val add = addButton()
                assertTrue(bar.bottom <= add.top, "the bar ($bar) is over Add exercise ($add)")
                screenshot("undo-bar-long-routine-large-text")
            }
        }

    @Test
    fun `swiping the Undo bar away in the routine editor leaves the exercise removed`() =
        runTest {
            val f = WorkoutFixture(this)
            longRoutine(f, 14)
            editor(f) {
                onNodeWithText("Exercise 1").performClick()
                waitForIdle()
                onNodeWithText("Remove from routine").performClick()
                waitForIdle()
                onNodeWithTag(UNDO_BAR_TAG).performTouchInput { swipeRight() }
                waitForIdle()
                onNodeWithText("Removed 1 exercise").assertDoesNotExist()
                onNodeWithText("Undo").assertDoesNotExist()
                assertEquals(13, names(f).size)
                assertEquals("Exercise 2", names(f).first())
            }
        }

    @Test
    fun `after deleting a routine from a long list, New routine can be scrolled clear of the Undo bar and tapped`() =
        runTest {
            val f = WorkoutFixture(this)
            f.routines.all.value = (1..16).map { Routine(RoutineId(it.toLong()), null, "Routine $it", "", emptyList()) }
            val vm = RoutinesViewModel(f.routines, f.programs).clearedAfterTest()
            runPhoneUiTest {
                setScreen { RoutinesRoute(onOpen = {}, viewModel = vm) }
                waitForIdle()
                onAllNodesWithContentDescription("More options")[0].performClick()
                waitForIdle()
                onNodeWithText("Delete").performClick()
                waitForIdle()
                onNodeWithText("Deleted Routine 1").assertIsDisplayed()
                scrollToEnd()
                onNodeWithText("Deleted Routine 1").assertIsDisplayed()
                assertClearOfBar("New routine")
                screenshot("undo-bar-routines-list")
                onNodeWithText("New routine").performClick()
                waitForIdle()
                onNodeWithText("Create").assertIsDisplayed()
            }
        }

    @Test
    fun `swiping the Undo bar away on the routines list leaves the routine deleted`() =
        runTest {
            val f = WorkoutFixture(this)
            f.routines.all.value = (1..3).map { Routine(RoutineId(it.toLong()), null, "Routine $it", "", emptyList()) }
            val vm = RoutinesViewModel(f.routines, f.programs).clearedAfterTest()
            runPhoneUiTest {
                setScreen { RoutinesRoute(onOpen = {}, viewModel = vm) }
                waitForIdle()
                onAllNodesWithContentDescription("More options")[0].performClick()
                waitForIdle()
                onNodeWithText("Delete").performClick()
                waitForIdle()
                onNodeWithTag(UNDO_BAR_TAG).performTouchInput { swipeLeft() }
                waitForIdle()
                onNodeWithText("Undo").assertDoesNotExist()
                assertEquals(
                    listOf("Routine 2", "Routine 3"),
                    f.routines.all.value
                        .map { it.name },
                )
                assertEquals(null, vm.uiState.value.deleted)
            }
        }
}
