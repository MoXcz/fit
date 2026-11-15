// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.fit.core.BodyArea
import app.fit.guided.FakeContentImages
import app.fit.guided.recoveryPlan
import app.fit.recovery.ui.SessionRoute
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.seconds

// The daily reminder on a session's page.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class RemindersUiTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val time = hasContentDescription("Reminder time", substring = true)

    private fun TestScope.fixture(): WorkoutFixture {
        val f = WorkoutFixture(this)
        f.plans.all.value = listOf(recoveryPlan(2, "Neck release", areas = listOf(BodyArea.Neck, BodyArea.UpperBack)))
        return f
    }

    @Test
    fun `a session's daily reminder switches on at 9, and its time opens the picker`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            val r = f.reminders(guided)
            runPhoneUiTest {
                val vm = f.sessionViewModel(2, guided, FakeContentImages(), r).clearedAfterTest()
                setScreen { SessionRoute(id = 2, onBack = {}, onOpenPlayer = {}, viewModel = vm) }
                waitForIdle()
                onNode(hasScrollAction()).performScrollToNode(hasText("Daily reminder"))
                onNodeWithText("Daily reminder").assertIsOff()
                onAllNodes(time).assertCountEquals(0)
                screenshot("reminder-off")

                onNodeWithText("Daily reminder").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Daily reminder").assertIsOn()
                onNode(time).assertIsDisplayed()
                assertEquals(
                    LocalTime(9, 0),
                    r.repository.rows.value
                        .single()
                        .time,
                )
                assertEquals(1, r.notices.requests)
                onAllNodesWithText("Allow").assertCountEquals(0)
                screenshot("reminder-on")

                onNode(time).performClick()
                waitForIdle()
                onNodeWithText("Remind me at").assertIsDisplayed()
                screenshot("reminder-time-picker")
                onNodeWithText("Cancel").performClick()
                advance(this@runTest, 0.seconds)
                onAllNodesWithText("Remind me at").assertCountEquals(0)
                assertEquals(
                    LocalTime(9, 0),
                    r.repository.rows.value
                        .single()
                        .time,
                )

                onNodeWithText("Daily reminder").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Daily reminder").assertIsOff()
                assertFalse(
                    r.repository.rows.value
                        .single()
                        .enabled,
                )
            }
        }

    @Test
    fun `with notifications refused the row says the reminder can't show and offers Allow`() =
        runTest {
            val f = fixture()
            val guided = f.guided()
            val r = f.reminders(guided)
            r.notices.permitted = false
            r.notices.grants = false
            runPhoneUiTest(widthDp = 360, heightDp = 780) {
                val vm = f.sessionViewModel(2, guided, FakeContentImages(), r).clearedAfterTest()
                setScreen { SessionRoute(id = 2, onBack = {}, onOpenPlayer = {}, viewModel = vm) }
                waitForIdle()
                onNode(hasScrollAction()).performScrollToNode(hasText("Daily reminder"))
                onNodeWithText("Daily reminder").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Daily reminder").assertIsOn()
                onNodeWithText("Notifications are off for Fit, so this can't show.").assertIsDisplayed()
                screenshot("reminder-blocked-360")
                onNodeWithText("Allow").performClick()
                advance(this@runTest, 0.seconds)
                assertEquals(1, r.notices.settingsOpened)
            }
        }
}
