// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.fit.core.BodyArea
import app.fit.guided.FakeContentImages
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedSource
import app.fit.guided.recoveryPlan
import app.fit.recovery.ui.RecoverRoute
import app.fit.recovery.ui.RecoverViewModel
import app.fit.recovery.ui.SessionAction
import app.fit.recovery.ui.SessionRoute
import app.fit.recovery.ui.SessionViewModel
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RecoverUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    // Three sessions, one pinned.
    private fun TestScope.fixture(): WorkoutFixture {
        val f = WorkoutFixture(this)
        f.plans.all.value =
            listOf(
                recoveryPlan(1, "Wrist reset", pinnedAt = START - 1.minutes),
                recoveryPlan(2, "Neck release", areas = listOf(BodyArea.Neck, BodyArea.UpperBack)),
                recoveryPlan(3, "Desk break", areas = listOf(BodyArea.Wrists, BodyArea.Neck)),
            )
        return f
    }

    @Test
    fun `Recover shows pinned sessions first, then area chips and the sessions, one tap from starting`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            runPhoneUiTest {
                var players = 0
                val opened = mutableListOf<GuidedPlanId>()
                val vm = RecoverViewModel(f.plans, guided).clearedAfterTest()
                setScreen { RecoverRoute(onOpenSession = { opened += it }, onOpenPlayer = { players++ }, viewModel = vm) }
                waitForIdle()
                onNodeWithText("Pinned").assertIsDisplayed()
                // Only areas with a session, never Hips or Knees.
                listOf("Wrists", "Forearms", "Neck", "Upper back").forEach { onNodeWithText(it).assertIsDisplayed() }
                onAllNodesWithText("Hips").assertCountEquals(0)
                onNodeWithText("All sessions").assertIsDisplayed()
                screenshot("recover")

                onNodeWithText("Neck").performClick()
                waitForIdle()
                onAllNodesWithText("Neck release").assertCountEquals(1)
                onAllNodesWithText("Desk break").assertCountEquals(1)
                screenshot("recover-area")

                onNodeWithContentDescription("Start Neck release").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, players)
                assertEquals(
                    GuidedSource.Plan(GuidedPlanId(2)),
                    guided.state.value.snapshot
                        ?.source,
                )
                onAllNodesWithText("Desk break").onFirst().performClick()
                assertEquals(listOf(GuidedPlanId(3)), opened)
            }
        }

    @Test
    fun `a session page shows its steps, notes and sources, pins, and starts`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            runPhoneUiTest {
                var players = 0
                val vm = f.sessionViewModel(2, guided, FakeContentImages()).clearedAfterTest()
                setScreen { SessionRoute(id = 2, onBack = {}, onOpenPlayer = { players++ }, viewModel = vm) }
                waitForIdle()
                onNodeWithText("Neck release").assertIsDisplayed()
                onNodeWithText("4 min · Neck, Upper back").assertIsDisplayed()
                onNodeWithText("Wrist bends").assertIsDisplayed()
                onNodeWithText("10 reps").assertIsDisplayed()
                onNodeWithText("0:30 · each side · 2 rounds").assertIsDisplayed()
                screenshot("recover-session")
                onNode(hasScrollAction()).performScrollToNode(hasText("NHS"))
                onNodeWithText("Stop if it hurts.").assertIsDisplayed()
                screenshot("recover-session-sources")

                // Pin and Delete share the bar's one action, a menu.
                onNodeWithContentDescription("More options").performClick()
                waitForIdle()
                screenshot("recover-session-menu")
                onNodeWithText("Pin").performClick()
                advance(this@runTest, 0.seconds)
                assertTrue(
                    f.plans.all.value
                        .first { it.id == GuidedPlanId(2) }
                        .pinned,
                )
                onNode(hasScrollAction()).performScrollToNode(hasText("Neck release"))
                onNodeWithText("Pinned").assertIsDisplayed()
                onNodeWithContentDescription("More options").performClick()
                waitForIdle()
                onNodeWithText("Unpin").assertIsDisplayed()
                pressBack()

                onNodeWithText("Start").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, players)
                assertTrue(guided.state.value.active)
            }
        }

    @Test
    fun `a running session shows first on Recover with Resume`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            guided.startPlan(GuidedPlanId(2))
            guided.next()
            runPhoneUiTest {
                var players = 0
                val vm = RecoverViewModel(f.plans, guided).clearedAfterTest()
                setScreen { RecoverRoute(onOpenSession = {}, onOpenPlayer = { players++ }, viewModel = vm) }
                waitForIdle()
                onNodeWithText("Running · 1 of 6").assertIsDisplayed()
                screenshot("recover-running")
                onNodeWithText("Resume").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, players)
            }
        }

    @Test
    fun `Delete on a session page offers Undo, which brings the session back, and the page leaves once the offer is gone`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            runPhoneUiTest {
                var backs = 0
                val vm = f.sessionViewModel(2, guided, FakeContentImages()).clearedAfterTest()
                setScreen { SessionRoute(id = 2, onBack = { backs++ }, onOpenPlayer = {}, viewModel = vm) }
                waitForIdle()
                onNodeWithContentDescription("More options").performClick()
                waitForIdle()
                onNodeWithText("Delete").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Deleted Neck release").assertIsDisplayed()
                onNodeWithText("Neck release").assertDoesNotExist()
                screenshot("recover-session-deleted")
                onNodeWithText("Undo").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Neck release").assertIsDisplayed()
                assertEquals(0, backs)

                onNodeWithContentDescription("More options").performClick()
                waitForIdle()
                onNodeWithText("Delete").performClick()
                advance(this@runTest, 0.seconds)
                vm.onAction(SessionAction.UndoSeen)
                advance(this@runTest, 0.seconds)
                assertEquals(1, backs)
            }
        }
}
