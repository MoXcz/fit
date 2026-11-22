// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.fit.day.BlockId
import app.fit.exercises.Sides
import app.fit.guided.FakeContentImages
import app.fit.guided.GuidedController
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedStep
import app.fit.guided.recoveryPlan
import app.fit.guided.ui.PLAYER_RING_TAG
import app.fit.guided.ui.PlayerRoute
import app.fit.guided.ui.PlayerViewModel
import app.fit.routines.WARMUP_SECTION
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class PlayerUiTest {
    // Not a test dispatcher: see the other UI tests. Timers run on the fixture's TestClock.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private class Session(
        val f: WorkoutFixture,
        val guided: GuidedController,
        val scope: CoroutineScope,
    )

    private fun TestScope.session(images: Map<String, ByteArray> = emptyMap()): Pair<Session, FakeContentImages> {
        val f = WorkoutFixture(this)
        return Session(f, f.guided(), backgroundScope) to FakeContentImages(images)
    }

    private fun ComposeUiTest.player(
        session: Session,
        images: FakeContentImages,
        continued: MutableList<BlockId> = mutableListOf(),
    ): PlayerViewModel {
        val f = session.f
        val vm = PlayerViewModel(session.guided, f.days, f.settings, images, f.clock, session.scope).clearedAfterTest()
        setScreen { PlayerRoute(onBack = {}, onContinue = { continued += it }, viewModel = vm) }
        waitForIdle()
        return vm
    }

    private fun ComposeUiTest.overflow(item: String) {
        onNodeWithContentDescription("More options").performClick()
        waitForIdle()
        onNodeWithText(item).performClick()
        waitForIdle()
    }

    @Test
    fun `the player runs a session - a transition, a counted step, a timed step on each side, paused, and the end`() =
        runTest {
            val (session, images) = session()
            session.guided.startPlan(GuidedPlanId(1))
            runCurrent()
            runPhoneUiTest {
                player(session, images)
                // The pause before the first step: what's coming, counting down in clay.
                onNodeWithText("Get ready").assertIsDisplayed()
                onNodeWithText("Wrist bends").assertIsDisplayed()
                onNodeWithText("0:05").assertIsDisplayed()
                onNodeWithText("1 of 6 · 0:00").assertIsDisplayed()
                screenshot("player-transition")

                advance(this@runTest, 5.seconds)
                onNodeWithText("10").assertIsDisplayed()
                onNodeWithText("reps").assertIsDisplayed()
                onNodeWithText("Bend up, then down").assertIsDisplayed()
                onNodeWithText("Next: Flexor stretch").assertIsDisplayed()
                screenshot("player-rep-step")
                val primary = onNodeWithText("Done").fetchSemanticsNode().boundsInRoot

                onNodeWithText("Done").performClick()
                advance(this@runTest, 5.seconds)
                onNodeWithText("Flexor stretch").assertIsDisplayed()
                onNodeWithText("Side 1 of 2 · Round 1 of 2").assertIsDisplayed()
                onNodeWithText("0:30").assertIsDisplayed()
                onNodeWithText("of 0:30").assertIsDisplayed()
                screenshot("player-timed-step")
                // The primary action never moves.
                assertEquals(primary, onNodeWithText("Pause").fetchSemanticsNode().boundsInRoot)

                advance(this@runTest, 10.seconds)
                onNodeWithText("Pause").performClick()
                advance(this@runTest, 30.seconds)
                onNodeWithText("Paused").assertIsDisplayed()
                onNodeWithText("0:20").assertIsDisplayed()
                screenshot("player-paused")

                onNodeWithText("Resume").performClick()
                advance(this@runTest, 20.seconds)
                onNodeWithText("Switch sides").assertIsDisplayed()
                onNodeWithText("Side 2 of 2 · Round 1 of 2").assertIsDisplayed()
                screenshot("player-switch-sides")
                assertEquals(1, session.f.guidedAlerts.rung)

                overflow("End session")
                onNodeWithText("Ended").assertIsDisplayed()
                onNodeWithText("2 of 6 steps").assertIsDisplayed()
                onNodeWithContentDescription("Next step").assertIsNotEnabled()
                screenshot("player-finished")
                assertEquals(1, session.f.plans.logged.value.size)
            }
        }

    @Test
    fun `a completed session says so with its time and steps`() =
        runTest {
            val (session, images) = session()
            session.f.plans.all.value = listOf(recoveryPlan(transition = 0, steps = listOf(GuidedStep("Shake out", duration = 15.seconds))))
            session.guided.startPlan(GuidedPlanId(1))
            runCurrent()
            runPhoneUiTest {
                player(session, images)
                advance(this@runTest, 15.seconds)
                // The eyebrow and the button.
                onAllNodesWithText("Done").assertCountEquals(2)
                onNodeWithText("1 of 1 steps").assertIsDisplayed()
                onNodeWithText("0:15").assertIsDisplayed()
                screenshot("player-completed")
            }
        }

    @Test
    fun `with auto-advance off a timed step waits at 0 for Next`() =
        runTest {
            val (session, images) = session()
            session.f.settings.setAutoAdvance(false)
            session.f.plans.all.value =
                listOf(
                    recoveryPlan(
                        transition = 0,
                        steps = listOf(GuidedStep("Shake out", duration = 15.seconds), GuidedStep("Neck", reps = 5)),
                    ),
                )
            session.guided.startPlan(GuidedPlanId(1))
            runCurrent()
            runPhoneUiTest {
                player(session, images)
                advance(this@runTest, 40.seconds)
                onNodeWithText("Next").assertIsDisplayed()
                onNodeWithText("Next: Neck").assertIsDisplayed()
                screenshot("player-waiting-at-0")
            }
        }

    @Test
    fun `on a small phone with an image the step, its image, the ring and the buttons all fit`() =
        runTest {
            val (session, images) = session(images = mapOf("images/prayer.png" to pngImage()))
            session.f.plans.all.value =
                listOf(
                    recoveryPlan(
                        transition = 0,
                        steps =
                            listOf(
                                GuidedStep(
                                    "Prayer stretch",
                                    duration = 30.seconds,
                                    sides = Sides.Each,
                                    cue = "Palms together, lower slowly until you feel a gentle stretch",
                                    image = "images/prayer.png",
                                ),
                            ),
                    ),
                )
            session.guided.startPlan(GuidedPlanId(1))
            runCurrent()
            runPhoneUiTest(widthDp = 360, heightDp = 780) {
                player(session, images)
                onNodeWithText("Prayer stretch").assertIsDisplayed()
                val ring = onNode(hasTestTag(PLAYER_RING_TAG)).fetchSemanticsNode().boundsInRoot
                val pause = onNodeWithText("Pause").fetchSemanticsNode().boundsInRoot
                val cue = onNodeWithText("Palms together, lower slowly until you feel a gentle stretch").fetchSemanticsNode().boundsInRoot
                assertTrue(ring.bottom <= pause.top, "ring $ring runs into the buttons $pause")
                assertTrue(cue.bottom <= ring.top, "the cue $cue runs into the ring $ring")
                assertTrue(ring.height > 100f)
                screenshot("player-360x780")
            }
        }

    @Test
    fun `the warm-up runner ticks each set, skips with Next, and Skip warm-up goes on to the first working exercise`() =
        runTest {
            val f = WorkoutFixture(this)
            f.days.addExercise(TODAY, plank.id, WARMUP_SECTION)
            f.days.addExercise(TODAY, curl.id, WARMUP_SECTION)
            val main = f.days.addExercise(TODAY, bench.id)!!
            val guided = f.guided()
            guided.startWarmup(TODAY)
            runCurrent()
            val session = Session(f, guided, backgroundScope)
            runPhoneUiTest {
                val continued = mutableListOf<BlockId>()
                player(session, FakeContentImages(), continued)
                // No pause between warm-up items: the plank counts down at once.
                onNodeWithText("Plank").assertIsDisplayed()
                onNodeWithText("0:40").assertIsDisplayed()
                screenshot("player-warmup-timed")
                advance(this@runTest, 40.seconds)
                onNodeWithText("2 of 4 · 0:40").assertIsDisplayed()
                // Back to the first plank: done, so it waits instead of running again.
                onNodeWithContentDescription("Previous step").performClick()
                waitForIdle()
                onNodeWithText("Next").assertIsDisplayed()
                screenshot("player-warmup-done-item")
                onNodeWithContentDescription("Next step").performClick()
                onNodeWithContentDescription("Next step").performClick()
                waitForIdle()
                onNodeWithText("Curl").assertIsDisplayed()
                onNodeWithText("Done").assertIsDisplayed()
                screenshot("player-warmup-reps")
                overflow("Skip warm-up")
                assertEquals(listOf(main), continued)
                // Nothing of the ended session shows while the workout opens.
                onNodeWithText("No session is running.").assertDoesNotExist()
                onNodeWithText("Ended").assertDoesNotExist()
            }
        }

    // A plain picture standing in for a step's image.
    private fun pngImage(): ByteArray {
        val surface = Surface.makeRasterN32Premul(240, 240)
        val canvas = surface.canvas
        canvas.clear(Color.makeRGB(214, 196, 168))
        canvas.drawRect(Rect.makeXYWH(60f, 40f, 120f, 160f), Paint().apply { color = Color.makeRGB(120, 140, 110) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    @Test
    fun `starting a session over one with progress says which one it ended`() =
        runTest {
            val (session, images) = session()
            session.f.plans.all.value += recoveryPlan(id = 2, title = "Neck release")
            session.guided.startPlan(GuidedPlanId(1))
            session.guided.next()
            session.guided.done()
            session.guided.startPlan(GuidedPlanId(2))
            runCurrent()
            runPhoneUiTest {
                player(session, images)
                waitUntil(timeoutMillis = 5_000) { onAllNodesWithText("Ended Wrist reset").fetchSemanticsNodes().isNotEmpty() }
                onNodeWithText("Get ready").assertExists()
                screenshot("player-replaced-notice")
                assertEquals(
                    GuidedOutcome.Stopped,
                    session.f.plans.logged.value
                        .single()
                        .outcome,
                )
            }
        }
}
