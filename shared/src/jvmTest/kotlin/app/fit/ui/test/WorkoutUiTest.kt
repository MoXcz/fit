// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.text.TextLayoutResult
import app.fit.core.WeightUnit
import app.fit.day.BlockId
import app.fit.day.SetEdit
import app.fit.ui.UNDO_BAR_TAG
import app.fit.workout.FakeWorkoutStateStore
import app.fit.workout.Rest
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import app.fit.workout.WorkoutSnapshot
import app.fit.workout.WorkoutStateStore
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import app.fit.workout.ui.DIAL_TAG
import app.fit.workout.ui.RING_TAG
import app.fit.workout.ui.WorkoutRoute
import app.fit.workout.ui.WorkoutViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class WorkoutUiTest {
    // Not a test dispatcher: runTest would adopt its scheduler, and the ViewModels' once-a-second
    // tickers would keep it from ever going idle. Timers run on the fixture's TestClock either way.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Gym(
        val f: WorkoutFixture,
        val ids: List<BlockId>,
    )

    // Today with bench (90–120 s rest), plank (40 s holds, 30 s rest) and curl (no rest).
    private suspend fun TestScope.gym(): Gym {
        val f = WorkoutFixture(this)
        return Gym(f, listOf(bench, plank, curl).map { f.days.addExercise(TODAY, it.id)!! })
    }

    private fun ComposeUiTest.focus(
        gym: Gym,
        block: BlockId,
        store: WorkoutStateStore = gym.f.store,
    ): WorkoutViewModel {
        val f = gym.f
        val vm = WorkoutViewModel(block.value, f.days, f.controller(store), f.settings, f.clock, TimeZone.UTC).clearedAfterTest()
        setScreen { WorkoutRoute(id = block, onBack = {}, viewModel = vm) }
        waitForIdle()
        return vm
    }

    // A value on the dial, not the same value in the sheet.
    private fun ComposeUiTest.dial(text: String) = onNode(hasText(text) and hasAnyAncestor(hasTestTag(DIAL_TAG)))

    private fun ComposeUiTest.overflow(item: String) {
        onNodeWithContentDescription("More options").performClick()
        waitForIdle()
        onNodeWithText(item).performClick()
        waitForIdle()
    }

    private fun ComposeUiTest.openSheet() {
        onNodeWithText("All sets").performClick()
        waitForIdle()
    }

    @Test
    fun `the dial shows the working set, Complete set starts the rest, and the next set changes during it`() =
        runTest {
            val gym = gym()
            runPhoneUiTest {
                val vm = focus(gym, gym.ids[0])
                onNodeWithText("Set 1 of 3").assertIsDisplayed()
                dial("60").assertIsDisplayed()
                dial("8").assertIsDisplayed()
                onNodeWithText("Complete set").assertIsDisplayed()
                // The numbers say what they are, and the ring is read as sets done.
                onNodeWithContentDescription("Weight (kg)").assertExists()
                onNodeWithContentDescription("Reps").assertExists()
                onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo(0f, 0f..3f, steps = 2))).assertExists()
                screenshot("focus-current-set")
                val complete = onNodeWithText("Complete set").fetchSemanticsNode().boundsInRoot
                onNodeWithContentDescription("Increase kg").performClick()
                waitForIdle()
                dial("62.5").assertIsDisplayed()
                onNodeWithText("Complete set").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    62.5,
                    vm.uiState.value.block!!
                        .sets[0]
                        .weight,
                )
                onNodeWithText("Rest").assertIsDisplayed()
                onNodeWithText("1:30").assertIsDisplayed()
                onNodeWithText("Next · Set 2 of 3").assertIsDisplayed()
                onNodeWithText("60 kg × 8").assertIsDisplayed()
                // The primary action stays where Complete set was.
                assertEquals(complete, onNodeWithText("Skip rest").fetchSemanticsNode().boundsInRoot)
                screenshot("focus-rest")
                onNodeWithContentDescription("Increase kg").performClick()
                onNodeWithContentDescription("Increase kg").performClick()
                waitForIdle()
                onNodeWithText("65 kg × 8").assertIsDisplayed()
                assertEquals(
                    65.0,
                    vm.uiState.value.block!!
                        .sets[1]
                        .weight,
                )
                screenshot("focus-rest-next-adjusted")
                onNodeWithText("+15 s").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("1:45").assertIsDisplayed()
                onNodeWithText("of 1:45").assertIsDisplayed()
                // The ring keeps the longer length as the time runs down.
                advance(this@runTest, 30.seconds)
                onNodeWithText("1:15").assertIsDisplayed()
                onNodeWithText("of 1:45").assertIsDisplayed()
                screenshot("focus-rest-after-15s")
                advance(this@runTest, 80.seconds)
                onNodeWithText("Rest over").assertIsDisplayed()
                onNodeWithText("+0:05").assertIsDisplayed()
                screenshot("focus-rest-over")
                assertEquals(listOf("rest:Bench press"), gym.f.alerts.rung)
                // Once the rest is over, Skip is the way into the next set and says so.
                onNodeWithText("Next set").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Complete set").assertIsDisplayed()
                onNodeWithText("Set 2 of 3").assertIsDisplayed()
                dial("65").assertIsDisplayed()
            }
        }

    @Test
    fun `ticking a warm-up set starts no rest`() =
        runTest {
            val gym = gym()
            gym.f.days.addWarmups(gym.ids[0])
            runPhoneUiTest {
                focus(gym, gym.ids[0])
                onNodeWithText("Warm-up 1 of 2").assertIsDisplayed()
                screenshot("focus-warmup-set")
                onNodeWithText("Complete set").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Warm-up 2 of 2").assertIsDisplayed()
                onNodeWithText("Rest").assertDoesNotExist()
            }
        }

    @Test
    fun `a hold counts down on the ring, pauses and resumes, Stop logs nothing, and Complete set logs the time held`() =
        runTest {
            val gym = gym()
            runPhoneUiTest {
                val vm = focus(gym, gym.ids[1])
                dial("0:40").assertIsDisplayed()
                onNodeWithContentDescription("Increase time").performClick()
                waitForIdle()
                dial("0:45").assertIsDisplayed()
                screenshot("focus-timed-set")
                onNodeWithText("Start timer").performClick()
                advance(this@runTest, 10.seconds)
                onNodeWithText("Hold").assertIsDisplayed()
                dial("0:35").assertIsDisplayed()
                // The hold keeps the screen on its exercise.
                onNodeWithContentDescription("Next exercise").assertIsNotEnabled()
                screenshot("focus-hold")
                onNodeWithContentDescription("Pause").performClick()
                advance(this@runTest, 60.seconds)
                onNodeWithText("Paused").assertIsDisplayed()
                dial("0:35").assertIsDisplayed()
                onNodeWithContentDescription("Resume").performClick()
                waitForIdle()
                // Stop, which logs nothing, is in the overflow.
                overflow("Stop without logging")
                advance(this@runTest, 0.seconds)
                onNodeWithText("Start timer").assertIsDisplayed()
                assertFalse(
                    vm.uiState.value.block!!
                        .sets[0]
                        .done,
                )
                onNodeWithText("Start timer").performClick()
                advance(this@runTest, 12.seconds)
                onNodeWithText("Complete set").performClick()
                advance(this@runTest, 0.seconds)
                val held =
                    vm.uiState.value.block!!
                        .sets[0]
                assertTrue(held.done)
                assertEquals(12.seconds, held.duration)
                onNodeWithText("Rest").assertIsDisplayed()
            }
        }

    @Test
    fun `opening another exercise while a hold runs shows the held one`() =
        runTest {
            val gym = gym()
            runPhoneUiTest {
                val vm = focus(gym, gym.ids[1])
                onNodeWithText("Start timer").performClick()
                advance(this@runTest, 1.seconds)
                vm.clearedAfterTest()
            }
            runPhoneUiTest {
                focus(gym, gym.ids[2])
                onNodeWithText("Plank").assertIsDisplayed()
                onNodeWithText("Curl").assertDoesNotExist()
                onNodeWithText("Hold").assertIsDisplayed()
                screenshot("focus-hold-opened-from-another-exercise")
            }
        }

    @Test
    fun `a finished day opens in review with its sets sheet open and no timers`() =
        runTest {
            val gym = gym()
            gym.f.days.finish(TODAY)
            runPhoneUiTest {
                focus(gym, gym.ids[0])
                onNodeWithText("Complete set").assertDoesNotExist()
                onNodeWithText("0/3 sets").assertIsDisplayed()
                onAllNodesWithContentDescription("Mark set done")[0].performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Rest").assertDoesNotExist()
                onNodeWithText("1/3 sets").assertIsDisplayed()
                screenshot("focus-review-finished-day")
                // Back closes the sheet first.
                pressBack()
                onAllNodesWithContentDescription("Mark set done")[0].assertIsNotDisplayed()
            }
        }

    @Test
    fun `another day edits and ticks without timers`() =
        runTest {
            val f = WorkoutFixture(this)
            val yesterday = f.days.addExercise(TODAY.minus(1, DateTimeUnit.DAY), bench.id)!!
            val gym = Gym(f, listOf(yesterday))
            runPhoneUiTest {
                focus(gym, yesterday)
                onAllNodesWithContentDescription("Mark set done")[0].performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Rest").assertDoesNotExist()
                onNodeWithText("Complete set").assertDoesNotExist()
                assertEquals(
                    1,
                    f.days.all.value[TODAY.minus(1, DateTimeUnit.DAY)]
                        ?.doneSets,
                )
            }
        }

    @Test
    fun `the sets sheet peeks with a chip per set, opens to every set, and marks a set in another unit`() =
        runTest {
            val gym = gym()
            val (first, second) =
                gym.f.days
                    .dayOfBlock(gym.ids[0])
                    .first()!!
                    .blocks[0]
                    .sets
            gym.f.days.editSet(first.id, SetEdit.Weight(142.5))
            gym.f.days.editSet(second.id, SetEdit.Weight(317.5))
            gym.f.days.editSet(second.id, SetEdit.Unit(WeightUnit.Lb))
            runPhoneUiTest {
                focus(gym, gym.ids[0])
                // The peek: one chip per set, the one in pounds says so.
                onNodeWithText("142.5×8").assertIsDisplayed()
                onNodeWithText("317.5 lb×8").assertIsDisplayed()
                openSheet()
                screenshot("focus-all-sets-142_5-317_5")
                // The dial shows the first set; the sheet shows both, in full.
                listOf("142.5", "317.5").forEach { value ->
                    onAllNodesWithText(value).fetchSemanticsNodes().forEachIndexed { index, _ ->
                        onAllNodesWithText(value)[index].assertFits()
                    }
                }
                pressBack()
                onNodeWithText("Complete set").assertIsDisplayed()
            }
        }

    @Test
    fun `with the Undo bar up and the sheet closed, Complete set can be tapped and the bar covers no control`() =
        runTest {
            listOf(411 to 891, 360 to 780).forEach { (width, height) ->
                val gym = gym()
                runPhoneUiTest(width, height) {
                    val vm = focus(gym, gym.ids[0])
                    openSheet()
                    onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))[2]
                        .performTouchInput { swipeLeft() }
                    advance(this@runTest, 0.seconds)
                    // Open: at the bottom, under the end of the sets.
                    onNodeWithText("Set removed").assertIsDisplayed()
                    pressBack()
                    waitForIdle()
                    onNodeWithText("Set removed").assertIsDisplayed()
                    val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
                    val controls =
                        listOf(
                            onNodeWithText("Complete set"),
                            onNodeWithTag(RING_TAG),
                            onNodeWithContentDescription("Back"),
                            onNodeWithContentDescription("More options"),
                            onNodeWithText("All sets"),
                        ) + onAllNodesWithContentDescription("Increase").let { all -> all.fetchSemanticsNodes().indices.map { all[it] } }
                    controls.forEach {
                        val bounds = it.fetchSemanticsNode().boundsInRoot
                        assertFalse(bar.overlaps(bounds), "the bar ($bar) is over a control ($bounds)")
                    }
                    screenshot(if (width == 411) "focus-undo-bar-sheet-closed" else "focus-undo-bar-sheet-closed-small-phone")
                    onNodeWithText("Complete set").performClick()
                    advance(this@runTest, 0.seconds)
                    assertEquals(
                        1,
                        vm.uiState.value.block!!
                            .sets
                            .count { it.done },
                    )
                }
            }
        }

    @Test
    fun `a set swipes away and comes back with Undo`() =
        runTest {
            val gym = gym()
            runPhoneUiTest {
                val vm = focus(gym, gym.ids[0])
                openSheet()
                onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))[2]
                    .performTouchInput { swipeLeft() }
                advance(this@runTest, 0.seconds)
                assertEquals(
                    2,
                    vm.uiState.value.block!!
                        .sets.size,
                )
                onNodeWithText("Undo").assertIsDisplayed()
                screenshot("focus-set-removed-undo")
                onNodeWithText("Undo").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    3,
                    vm.uiState.value.block!!
                        .sets.size,
                )
            }
        }

    @Test
    fun `a time is typed as m_ss and reads back as m_ss`() =
        runTest {
            val gym = gym()
            runPhoneUiTest {
                val vm = focus(gym, gym.ids[1])
                openSheet()
                // The dial has no time box; the sheet's rows take m:ss or plain seconds.
                onAllNodes(hasSetTextAction())[0].performTextInput("1:05")
                onAllNodes(hasSetTextAction())[0].performImeAction()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    65.seconds,
                    vm.uiState.value.block!!
                        .sets[0]
                        .duration,
                )
                onAllNodes(hasSetTextAction())[1].performTextInput("90")
                onAllNodes(hasSetTextAction())[1].performImeAction()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    90.seconds,
                    vm.uiState.value.block!!
                        .sets[1]
                        .duration,
                )
                onAllNodes(hasSetTextAction() and hasText("1:05")).assertCountEquals(1)
                onAllNodes(hasSetTextAction() and hasText("1:30")).assertCountEquals(1)
                screenshot("focus-time-entry")
                // A value that doesn't read goes back to the stored time; an empty field clears it.
                onAllNodes(hasSetTextAction())[0].performTextReplacement("1:75")
                onAllNodes(hasSetTextAction())[0].performImeAction()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    65.seconds,
                    vm.uiState.value.block!!
                        .sets[0]
                        .duration,
                )
                onAllNodes(hasSetTextAction() and hasText("1:05")).assertCountEquals(1)
                onAllNodes(hasSetTextAction())[1].performTextClearance()
                onAllNodes(hasSetTextAction())[1].performImeAction()
                advance(this@runTest, 0.seconds)
                assertEquals(
                    null,
                    vm.uiState.value.block!!
                        .sets[1]
                        .duration,
                )
            }
        }

    @Test
    fun `Exercise details show the notes and links in the sheet`() =
        runTest {
            val f = WorkoutFixture(this)
            val noted =
                bench.copy(
                    fields = bench.fields.copy(notes = "Feet flat, shoulder blades back.", videoUrl = "https://example.org/bench"),
                )
            f.exercises.all.value =
                f.exercises.all.value
                    .map { if (it.id == bench.id) noted else it }
            val gym = Gym(f, listOf(f.days.addExercise(TODAY, bench.id)!!))
            runPhoneUiTest {
                focus(gym, gym.ids[0])
                onNodeWithText("Notes & links").assertIsDisplayed()
                openSheet()
                onNodeWithText("Feet flat, shoulder blades back.").performScrollTo().assertIsDisplayed()
                onNodeWithText("Video").assertIsDisplayed()
                screenshot("focus-exercise-details")
            }
        }

    @Test
    fun `a rest that ended over a minute before the app came back shows as over, without a ring`() =
        runTest {
            val gym = gym()
            val plankSet =
                gym.f.days
                    .dayOfBlock(gym.ids[1])
                    .first()!!
                    .blocks[1]
                    .sets[0]
            val store = FakeWorkoutStateStore(WorkoutSnapshot(date = TODAY, rest = Rest(gym.ids[1], plankSet.id, START - 2.minutes)))
            runPhoneUiTest {
                focus(gym, gym.ids[1], store)
                onNodeWithText("Rest over").assertIsDisplayed()
                onNodeWithText("+2:00").assertIsDisplayed()
                assertTrue(
                    gym.f.alerts.rung
                        .isEmpty(),
                )
                screenshot("focus-late-rest-silent")
            }
        }
}

// The text is laid out within its node, on one line: nothing clipped (the 0.0.4 bug), nothing wrapped.
private fun SemanticsNodeInteraction.assertFits() {
    val node = fetchSemanticsNode()
    val layouts = mutableListOf<TextLayoutResult>()
    node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
    val layout = layouts.single()
    val text = layout.layoutInput.text
    assertEquals(1, layout.lineCount, "wrapped: $text")
    assertFalse(layout.didOverflowWidth, "clipped: $text")
    val width = layout.getLineRight(0) - layout.getLineLeft(0)
    assertTrue(width <= node.size.width, "clipped: $text is $width px in ${node.size.width} px")
}
