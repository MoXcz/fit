// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.fit.content.Source
import app.fit.exercises.ExerciseId
import app.fit.exercises.ui.ExerciseRoute
import app.fit.exercises.ui.ExerciseViewModel
import app.fit.guided.FakeContentImages
import app.fit.guided.GuidedController
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.practicePlan
import app.fit.guided.recoveryPlan
import app.fit.guided.ui.PlayerRoute
import app.fit.guided.ui.PlayerViewModel
import app.fit.lessons.FakeLessonRepository
import app.fit.lessons.Lesson
import app.fit.lessons.LessonExercise
import app.fit.lessons.LessonId
import app.fit.lessons.ui.LessonRoute
import app.fit.lessons.ui.LessonViewModel
import app.fit.lessons.ui.LessonsRoute
import app.fit.lessons.ui.LessonsViewModel
import app.fit.recovery.ui.SessionRoute
import app.fit.recovery.ui.SessionViewModel
import app.fit.workout.TestClock
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import org.jetbrains.skia.Color
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class LessonsUiTest {
    // Not a test dispatcher: see the other UI tests. Timers run on the fixture's TestClock.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val body =
        """
        ## Setup

        Hands under your shoulders, *a little* wider. **Brace** your stomach, and read the
        [E3 Rehab guide](https://e3rehab.com/how-to-perform-perfect-push-ups/).

        ![Top position](images/top.webp)

        ## Cues

        - Push the floor away.
        - Elbows back at about **45 degrees**, not out to the sides.

        ### Progressions

        1. **Wall push-up:** hands on a wall at shoulder height.
        2. **Incline push-up:** hands on a bench.
        3. Floor push-up.

        **Stop if** you feel sharp pain in your wrists or shoulders.
        """.trimIndent()

    private val pushUp =
        Lesson(
            id = LessonId(1),
            contentId = "push-up",
            title = "Push-up",
            body = body,
            exercises = listOf(LessonExercise(plank.id, "Plank"), LessonExercise(curl.id, "Curl")),
            practice = GuidedPlanId(10),
            sources = listOf(Source("How to perform perfect push-ups (E3 Rehab)", "https://e3rehab.com/how-to-perform-perfect-push-ups/")),
        )

    private val squat = Lesson(LessonId(2), "squat", "Squat basics", "Sit back.", listOf(LessonExercise(curl.id, "Curl")), practice = null)

    private class Fixture(
        val f: WorkoutFixture,
        val lessons: FakeLessonRepository,
        val guided: GuidedController,
        val images: FakeContentImages,
    )

    private fun TestScope.fixture(): Fixture {
        val f = WorkoutFixture(this)
        f.plans.all.value = listOf(recoveryPlan(), practicePlan())
        return Fixture(
            f,
            FakeLessonRepository(listOf(pushUp, squat)),
            f.guided(),
            FakeContentImages(mapOf("images/top.webp" to pngImage())),
        )
    }

    // A wide picture, so the page shows how an image fills the width.
    private fun pngImage(): ByteArray {
        val surface = Surface.makeRasterN32Premul(480, 270)
        val canvas = surface.canvas
        canvas.clear(Color.makeRGB(214, 196, 168))
        canvas.drawRect(Rect.makeXYWH(60f, 150f, 360f, 40f), Paint().apply { color = Color.makeRGB(120, 140, 110) })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    private fun ComposeUiTest.lessonPage(
        x: Fixture,
        opened: MutableList<ExerciseId> = mutableListOf(),
    ) {
        val vm = LessonViewModel(1, x.lessons, x.f.plans, x.guided, x.images).clearedAfterTest()
        setScreen { LessonRoute(id = LessonId(1), onBack = {}, onOpenExercise = { opened += it }, onOpenPlayer = {}, viewModel = vm) }
        waitForIdle()
    }

    private fun SkikoComposeUiTest.scrollTo(text: String) {
        onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
        waitForIdle()
    }

    @Test
    fun `the Lessons list shows each lesson with its exercises and how often it was practised`() =
        runTest {
            val x = fixture()
            val start = TestClock.START
            x.f.plans.log(
                GuidedLog(GuidedPlanId(10), GuidedKind.Practice, "Push-up", TODAY, start, start + 5.seconds, GuidedOutcome.Completed, 4),
            )
            runPhoneUiTest {
                val opened = mutableListOf<LessonId>()
                val vm = LessonsViewModel(x.lessons, x.f.plans).clearedAfterTest()
                setScreen { LessonsRoute(onOpen = { opened += it }, viewModel = vm) }
                waitForIdle()
                onNodeWithText("Plank, Curl · Practised 1 time").assertIsDisplayed()
                // Never practised: nothing said about it.
                onNodeWithText("Curl").assertIsDisplayed()
                screenshot("lessons")
                onNodeWithText("Push-up").performClick()
                assertEquals(listOf(LessonId(1)), opened)
            }
        }

    @Test
    fun `a lesson page renders its Markdown, image, exercises, drill and sources`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                val opened = mutableListOf<ExerciseId>()
                lessonPage(x, opened)
                onNodeWithText("Lesson").assertIsDisplayed()
                onNodeWithText("About 2 min").assertIsDisplayed()
                onNodeWithText("Setup").assertIsDisplayed()
                onNodeWithText("E3 Rehab guide", substring = true).assertIsDisplayed()
                onNodeWithContentDescription("Top position").assertIsDisplayed()
                screenshot("lesson")
                scrollTo("Floor push-up")
                onNodeWithText("Push the floor away.").assertIsDisplayed()
                onNodeWithText("3.").assertIsDisplayed()
                screenshot("lesson-lists")
                scrollTo("6 reps · 2 rounds")
                onNodeWithText("0:40").assertIsDisplayed()
                screenshot("lesson-drill")
                scrollTo("E3 Rehab)")
                screenshot("lesson-sources")
                scrollTo("Plank")
                onAllNodesWithText("Plank").onFirst().performClick()
                assertEquals(listOf(plank.id), opened)
            }
        }

    @Test
    fun `a stored lesson with an empty body shows its page without the notes`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                val lessons = FakeLessonRepository(listOf(squat.copy(body = "")))
                val vm = LessonViewModel(2, lessons, x.f.plans, x.guided, x.images).clearedAfterTest()
                setScreen { LessonRoute(id = LessonId(2), onBack = {}, onOpenExercise = {}, onOpenPlayer = {}, viewModel = vm) }
                waitForIdle()
                onNodeWithText("Squat basics").assertIsDisplayed()
                onNodeWithText("Exercises").assertIsDisplayed()
                onNodeWithText("Curl").assertIsDisplayed()
            }
        }

    @Test
    fun `the lesson page fits a small phone`() =
        runTest {
            val x = fixture()
            runPhoneUiTest(widthDp = 360, heightDp = 780) {
                lessonPage(x)
                onNodeWithText("Practice").assertIsDisplayed()
                screenshot("lesson-360")
            }
        }

    @Test
    fun `the lesson page reads at 1_3x text`() =
        runTest {
            val x = fixture()
            runPhoneUiTest(fontScale = 1.3f) {
                lessonPage(x)
                onNodeWithText("Practice").assertIsDisplayed()
                screenshot("lesson-large-text")
                scrollTo("Floor push-up")
                screenshot("lesson-large-text-lists")
            }
        }

    @Test
    fun `Practice runs the drill in the player, and the page says it was practised after a run`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                val lesson = LessonViewModel(1, x.lessons, x.f.plans, x.guided, x.images).clearedAfterTest()
                val player = PlayerViewModel(x.guided, x.f.days, x.f.settings, x.images, x.f.clock, backgroundScope).clearedAfterTest()
                setScreen {
                    var playing by remember { mutableStateOf(false) }
                    if (playing) {
                        PlayerRoute(onBack = { playing = false }, onContinue = {}, viewModel = player)
                    } else {
                        LessonRoute(
                            id = LessonId(1),
                            onBack = {},
                            onOpenExercise = {},
                            onOpenPlayer = { playing = true },
                            viewModel = lesson,
                        )
                    }
                }
                waitForIdle()
                onNodeWithText("Practice").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Get ready").assertIsDisplayed()
                onNodeWithText("Wall push-up").assertIsDisplayed()
                screenshot("lesson-practice-transition")
                advance(this@runTest, 30.seconds)
                onNodeWithText("Push-up").assertIsDisplayed()
                screenshot("lesson-practice-step")
                // The wall push-up runs out; the incline push-ups wait for Done after their pause.
                advance(this@runTest, 50.seconds)
                onNodeWithText("Done").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithContentDescription("More options").performClick()
                waitForIdle()
                onNodeWithText("End session").performClick()
                waitForIdle()
                onNodeWithText("Ended").assertIsDisplayed()
                onNodeWithText("2 of 3 steps").assertIsDisplayed()
                screenshot("lesson-practice-ended")
                onNodeWithText("Done").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("About 2 min · Practised 1 time").assertIsDisplayed()
                screenshot("lesson-practised")
                assertEquals(
                    listOf(GuidedKind.Practice),
                    x.f.plans.logged.value
                        .map { it.kind },
                )
                runCurrent()
            }
        }

    @Test
    fun `an exercise page lists the lessons that teach it`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                val opened = mutableListOf<LessonId>()
                val vm = ExerciseViewModel(curl.id.value, x.f.exercises, x.f.days, x.lessons, x.f.clock, TimeZone.UTC).clearedAfterTest()
                setScreen { ExerciseRoute(id = curl.id, onEdit = {}, onOpenLesson = { opened += it }, onBack = {}, viewModel = vm) }
                waitForIdle()
                scrollTo("Squat basics")
                onNodeWithText("Lessons").assertIsDisplayed()
                screenshot("exercise-lessons")
                onNodeWithText("Squat basics").performClick()
                assertEquals(listOf(LessonId(2)), opened)
            }
        }

    @Test
    fun `a recovery session's notes render as Markdown`() =
        runTest {
            val x = fixture()
            x.f.plans.all.value =
                listOf(
                    recoveryPlan().copy(
                        notes =
                            "For stiff wrists after typing. Move *slowly*.\n\nStop, and see a **doctor**, if you have:\n\n" +
                                "- sharp or severe pain;\n- numbness or tingling in the hand.\n\nIt doesn't diagnose anything.",
                    ),
                )
            runPhoneUiTest {
                val vm = x.f.sessionViewModel(1, x.guided, x.images).clearedAfterTest()
                setScreen { SessionRoute(id = 1, onBack = {}, onOpenPlayer = {}, viewModel = vm) }
                waitForIdle()
                scrollTo("doesn't diagnose")
                onNodeWithText("sharp or severe pain;").assertIsDisplayed()
                screenshot("recover-session-notes")
            }
        }

    @Test
    fun `Delete on a lesson page offers Undo, which brings the lesson back`() =
        runTest {
            val x = fixture()
            runPhoneUiTest {
                lessonPage(x)
                onNodeWithContentDescription("Delete lesson").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Deleted Push-up").assertIsDisplayed()
                onNodeWithText("Practice").assertDoesNotExist()
                assertEquals(
                    listOf("Squat basics"),
                    x.lessons.all.value
                        .map { it.title },
                )
                screenshot("lesson-deleted")
                onNodeWithText("Undo").performClick()
                advance(this@runTest, 0.seconds)
                onNodeWithText("Practice").assertIsDisplayed()
                assertEquals(2, x.lessons.all.value.size)
            }
        }
}
