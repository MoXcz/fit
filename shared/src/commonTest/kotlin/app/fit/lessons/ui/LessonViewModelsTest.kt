// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons.ui

import app.fit.content.MdBlock
import app.fit.content.MdSpan
import app.fit.content.Source
import app.fit.exercises.ui.ExerciseViewModel
import app.fit.guided.FakeContentImages
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedSource
import app.fit.guided.practicePlan
import app.fit.guided.recoveryPlan
import app.fit.lessons.FakeLessonRepository
import app.fit.lessons.Lesson
import app.fit.lessons.LessonExercise
import app.fit.lessons.LessonId
import app.fit.lessons.Practice
import app.fit.workout.TestClock
import app.fit.workout.WorkoutFixture
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class LessonViewModelsTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val drill = GuidedPlanId(10)

    private val pushUp =
        Lesson(
            id = LessonId(1),
            contentId = "push-up",
            title = "Push-up",
            body = "## Setup\n\n- Hands under your **shoulders**.\n\n![Top](images/top.webp)",
            exercises = listOf(LessonExercise(plank.id, "Plank")),
            practice = drill,
            sources = listOf(Source("E3 Rehab", "https://e3rehab.com/")),
        )

    // Teaches curls; its drill isn't in the plans.
    private val curls = Lesson(LessonId(2), "curls", "Curls", "Curl.", listOf(LessonExercise(curl.id, "Curl")), practice = null)

    private fun TestScope.fixture(): Pair<WorkoutFixture, FakeLessonRepository> {
        val f = WorkoutFixture(this)
        f.plans.all.value = listOf(recoveryPlan(), practicePlan())
        return f to FakeLessonRepository(listOf(pushUp, curls), f.plans)
    }

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun log(
        kind: GuidedKind,
        plan: Long,
        at: Int,
    ): GuidedLog {
        val start = TestClock.START - at.minutes
        return GuidedLog(GuidedPlanId(plan), kind, "x", TestClock.TODAY, start, start + 5.minutes, GuidedOutcome.Completed, 3)
    }

    @Test
    fun `the lesson list shows each lesson's exercises and how often its drill ran`() =
        runTest {
            val (f, lessons) = fixture()
            f.plans.log(log(GuidedKind.Practice, 10, at = 60))
            f.plans.log(log(GuidedKind.Practice, 10, at = 30))
            // A recovery run of the same plan id never counts as practice.
            f.plans.log(log(GuidedKind.Recovery, 10, at = 10))
            val vm = LessonsViewModel(lessons, f.plans)
            collect(vm.uiState)
            assertEquals(
                listOf(LessonRow(LessonId(2), "Curls", listOf("Curl"), 0), LessonRow(LessonId(1), "Push-up", listOf("Plank"), 2)),
                vm.uiState.value.lessons,
            )
        }

    @Test
    fun `the lesson page shows the rendered body, its images, the drill and its estimate`() =
        runTest {
            val (f, lessons) = fixture()
            val bytes = byteArrayOf(1, 2, 3)
            val vm = LessonViewModel(1, lessons, f.plans, f.guided(), FakeContentImages(mapOf("images/top.webp" to bytes)))
            collect(vm.uiState)
            val state = vm.uiState.value
            assertEquals("Push-up", state.lesson?.title)
            assertEquals(
                listOf(
                    MdBlock.Heading(2, listOf(MdSpan("Setup"))),
                    MdBlock.Bullets(listOf(listOf(MdSpan("Hands under your "), MdSpan("shoulders", bold = true), MdSpan(".")))),
                    MdBlock.Image("Top", "images/top.webp", 5),
                ),
                state.body,
            )
            assertTrue(state.images["images/top.webp"].contentEquals(bytes))
            assertEquals(practicePlan(), state.practice)
            // 136 s.
            assertEquals(2, state.practiceMinutes)
            assertEquals(0, state.practised)
        }

    @Test
    fun `Practice runs the drill on the player as a practice session`() =
        runTest {
            val (f, lessons) = fixture()
            val guided = f.guided()
            val vm = LessonViewModel(1, lessons, f.plans, guided, FakeContentImages())
            collect(vm.uiState)
            vm.onAction(LessonAction.Practice)
            runCurrent()
            assertTrue(vm.uiState.value.opened)
            val snapshot = guided.state.value.snapshot!!
            assertEquals(GuidedSource.Plan(drill), snapshot.source)
            assertEquals(GuidedKind.Practice, snapshot.kind)
            assertEquals("Push-up", snapshot.title)
            vm.onAction(LessonAction.OpenedSeen)
            assertFalse(vm.uiState.value.opened)
        }

    @Test
    fun `a practice run is logged as practice and counts once a step is done`() =
        runTest {
            val (f, lessons) = fixture()
            val guided = f.guided()
            val vm = LessonViewModel(1, lessons, f.plans, guided, FakeContentImages())
            collect(vm.uiState)
            // Ended before a step is done: no log, no count.
            vm.onAction(LessonAction.Practice)
            advance(10.seconds)
            guided.end()
            guided.dismiss()
            assertEquals(emptyList(), f.plans.logged.value)
            assertEquals(0, vm.uiState.value.practised)

            vm.onAction(LessonAction.Practice)
            // The 20 s transition, then the 40 s wall push-up runs out.
            advance(61.seconds)
            guided.end()
            val log =
                f.plans.logged.value
                    .single()
            assertEquals(GuidedKind.Practice, log.kind)
            assertEquals(drill, log.planId)
            assertEquals(GuidedOutcome.Stopped, log.outcome)
            assertEquals(1, log.stepsDone)
            assertEquals(1, vm.uiState.value.practised)
        }

    @Test
    fun `a practice run left still for 30 minutes ends itself and counts with its steps done`() =
        runTest {
            val (f, lessons) = fixture()
            val guided = f.guided()
            val vm = LessonViewModel(1, lessons, f.plans, guided, FakeContentImages())
            collect(vm.uiState)
            vm.onAction(LessonAction.Practice)
            // The wall push-up ends; the incline push-ups wait for Done after their pause.
            advance(81.seconds)
            advance(30.minutes)
            assertTrue(
                guided.state.value.snapshot!!
                    .finished,
            )
            assertEquals(
                listOf(GuidedKind.Practice to 1),
                f.plans.logged.value
                    .map { it.kind to it.stepsDone },
            )
            assertEquals(1, vm.uiState.value.practised)
        }

    @Test
    fun `a lesson without a drill, or whose drill lost its steps, offers no Practice`() =
        runTest {
            val (f, lessons) = fixture()
            val guided = f.guided()
            val none = LessonViewModel(2, lessons, f.plans, guided, FakeContentImages())
            collect(none.uiState)
            assertNull(none.uiState.value.practice)
            f.plans.all.value = listOf(practicePlan().copy(steps = emptyList()))
            val emptied = LessonViewModel(1, lessons, f.plans, guided, FakeContentImages())
            collect(emptied.uiState)
            assertNull(emptied.uiState.value.practice)
            emptied.onAction(LessonAction.Practice)
            runCurrent()
            assertNull(guided.state.value.snapshot)
        }

    @Test
    fun `a stored lesson with an empty body loads with no notes`() =
        runTest {
            val f = WorkoutFixture(this)
            val vm = LessonViewModel(2, FakeLessonRepository(listOf(curls.copy(body = ""))), f.plans, f.guided(), FakeContentImages())
            collect(vm.uiState)
            assertEquals(
                "Curls",
                vm.uiState.value.lesson
                    ?.title,
            )
            assertEquals(emptyList(), vm.uiState.value.body)
        }

    @Test
    fun `a lesson that isn't there loads as missing`() =
        runTest {
            val (f, lessons) = fixture()
            val vm = LessonViewModel(99, lessons, f.plans, f.guided(), FakeContentImages())
            collect(vm.uiState)
            assertFalse(vm.uiState.value.loading)
            assertNull(vm.uiState.value.lesson)
        }

    @Test
    fun `an exercise page lists the lessons that teach it`() =
        runTest {
            val (f, lessons) = fixture()
            val vm = ExerciseViewModel(plank.id.value, f.exercises, f.days, lessons, f.clock, TimeZone.UTC)
            collect(vm.uiState)
            assertEquals(
                listOf("Push-up"),
                vm.uiState.value.lessons
                    .map { it.title },
            )
            val bare = ExerciseViewModel(3, f.exercises, f.days, FakeLessonRepository(), f.clock, TimeZone.UTC)
            collect(bare.uiState)
            assertEquals(emptyList(), bare.uiState.value.lessons)
        }

    @Test
    fun `Delete removes the lesson and offers Undo, which brings it back with its practice count`() =
        runTest {
            val (f, lessons) = fixture()
            f.plans.log(log(GuidedKind.Practice, 10, at = 60))
            val vm = LessonViewModel(1, lessons, f.plans, f.guided(), FakeContentImages())
            collect(vm.uiState)
            assertEquals(1, vm.uiState.value.practised)

            vm.onAction(LessonAction.Delete)
            runCurrent()
            assertNull(vm.uiState.value.lesson)
            assertEquals("Push-up", vm.uiState.value.deleted)
            assertEquals(listOf("Curls"), lessons.all.value.map { it.title })
            // The drill went with it, so its run counts for nothing until Undo.
            assertNull(
                f.plans.all.value
                    .firstOrNull { it.id == drill },
            )
            assertEquals(emptyMap(), Practice.counts(f.plans.logged.value))

            vm.onAction(LessonAction.UndoDelete)
            runCurrent()
            assertEquals(
                "Push-up",
                vm.uiState.value.lesson
                    ?.title,
            )
            assertEquals(1, vm.uiState.value.practised)
            assertNull(vm.uiState.value.deleted)
        }

    @Test
    fun `once the Undo offer runs out the deleted lesson's page closes`() =
        runTest {
            val (f, lessons) = fixture()
            val vm = LessonViewModel(1, lessons, f.plans, f.guided(), FakeContentImages())
            collect(vm.uiState)
            vm.onAction(LessonAction.Delete)
            runCurrent()
            assertFalse(vm.uiState.value.closed)
            vm.onAction(LessonAction.UndoSeen)
            runCurrent()
            assertTrue(vm.uiState.value.closed)
        }

    @Test
    fun `deleting a lesson whose drill is running ends the run first, and it still counts after Undo`() =
        runTest {
            val (f, lessons) = fixture()
            val guided = f.guided()
            val vm = LessonViewModel(1, lessons, f.plans, guided, FakeContentImages())
            collect(vm.uiState)
            vm.onAction(LessonAction.Practice)
            // The 20 s transition, then the 40 s wall push-up runs out.
            advance(61.seconds)

            vm.onAction(LessonAction.Delete)
            runCurrent()
            assertNull(guided.state.value.snapshot)
            assertEquals(
                GuidedOutcome.Stopped,
                f.plans.logged.value
                    .single()
                    .outcome,
            )
            vm.onAction(LessonAction.UndoDelete)
            runCurrent()
            assertEquals(1, vm.uiState.value.practised)
        }
}
