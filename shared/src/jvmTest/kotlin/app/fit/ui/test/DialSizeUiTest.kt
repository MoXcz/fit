// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.fit.day.BlockId
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.plank
import app.fit.workout.ui.RING_TAG
import app.fit.workout.ui.WorkoutRoute
import app.fit.workout.ui.WorkoutViewModel
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
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// The dial on a small phone (360 × 780 dp) and with large text (1.3×): the ring shrinks to fit, and
// nothing runs into anything else.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class DialSizeUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private suspend fun TestScope.gym(): Pair<WorkoutFixture, List<BlockId>> {
        val f = WorkoutFixture(this)
        return f to listOf(bench, plank).map { f.days.addExercise(TODAY, it.id)!! }
    }

    private fun ComposeUiTest.focus(
        f: WorkoutFixture,
        block: BlockId,
    ) {
        val vm = WorkoutViewModel(block.value, f.days, f.controller(), f.settings, f.clock, TimeZone.UTC).clearedAfterTest()
        setScreen { WorkoutRoute(id = block, onBack = {}, viewModel = vm) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.bounds(text: String): Rect = onAllNodesWithText(text).onFirst().fetchSemanticsNode().boundsInRoot

    // Top to bottom: the name, the ring, what's under it, the primary action, the sheet.
    private fun SkikoComposeUiTest.assertStacked(
        under: Rect,
        primary: String,
    ) {
        val name = bounds("Bench press").takeIf { onAllNodesWithText("Bench press").fetchSemanticsNodes().isNotEmpty() }
        val ring = onNodeWithTag(RING_TAG).fetchSemanticsNode().boundsInRoot
        val button = bounds(primary)
        val sheet = bounds("All sets")
        name?.let { assertTrue(it.bottom <= ring.top, "name $it over ring $ring") }
        assertTrue(ring.bottom <= under.top, "ring $ring over $under")
        assertTrue(under.bottom <= button.top, "$under over $primary $button")
        assertTrue(button.bottom <= sheet.top, "$primary $button over the sheet $sheet")
    }

    private fun SkikoComposeUiTest.node(matcher: androidx.compose.ui.test.SemanticsMatcher): Rect =
        onNode(matcher).fetchSemanticsNode().boundsInRoot

    private fun check(
        name: String,
        widthDp: Int,
        heightDp: Int,
        fontScale: Float,
    ) = runTest {
        val (f, ids) = gym()
        runPhoneUiTest(widthDp, heightDp, fontScale) {
            focus(f, ids[0])
            assertStacked(node(hasContentDescription("Increase reps")), "Complete set")
            screenshot("$name-working-set")
            onNodeWithText("Complete set").performClick()
            advance(this@runTest, 0.seconds)
            assertStacked(node(hasText("Next · Set 2 of 3")), "Skip rest")
            screenshot("$name-rest")
        }
        // A fresh day: the bench rest above would otherwise still be running.
        val (g, holds) = gym()
        runPhoneUiTest(widthDp, heightDp, fontScale) {
            focus(g, holds[1])
            onNodeWithText("Start timer").performClick()
            advance(this@runTest, 5.seconds)
            val ring = onNodeWithTag(RING_TAG).fetchSemanticsNode().boundsInRoot
            val button = bounds("Complete set")
            assertTrue(ring.bottom <= button.top, "ring $ring over Complete set $button")
            assertTrue(onNode(hasTestTag(RING_TAG)).fetchSemanticsNode().size.width > 0)
            screenshot("$name-hold")
        }
    }

    @Test
    fun `on a 360 x 780 dp phone the dial fits`() = check("small-phone", 360, 780, 1f)

    @Test
    fun `with 1_3x text the dial fits`() = check("large-text", 411, 891, 1.3f)
}
