// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided.ui

import androidx.lifecycle.ViewModelStore
import app.fit.guided.FakeContentImages
import app.fit.guided.GuidedController
import app.fit.guided.GuidedPhase
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedStep
import app.fit.guided.Interstitial
import app.fit.guided.recoveryPlan
import app.fit.routines.WARMUP_SECTION
import app.fit.workout.TestClock
import app.fit.workout.WorkoutFixture
import app.fit.workout.bench
import app.fit.workout.curl
import app.fit.workout.plank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.player(
        f: WorkoutFixture,
        guided: GuidedController,
        images: FakeContentImages = FakeContentImages(),
    ): PlayerViewModel {
        val vm = PlayerViewModel(guided, f.days, f.settings, images, f.clock, backgroundScope)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
        return vm
    }

    private fun PlayerViewModel.act(
        scope: TestScope,
        action: PlayerAction,
    ) {
        onAction(action)
        scope.runCurrent()
    }

    @Test
    fun `the primary is Pause while counting down, Resume when paused, Done on a counted step`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val vm = player(f, guided)
            guided.startPlan(GuidedPlanId(1))
            runCurrent()
            assertEquals(Interstitial.Transition, vm.uiState.value.interstitial)
            assertEquals(PlayerPrimary.Pause, vm.uiState.value.primary)
            vm.act(this, PlayerAction.Pause)
            assertEquals(PlayerPrimary.Resume, vm.uiState.value.primary)
            vm.act(this, PlayerAction.Next)
            assertEquals(GuidedPhase.Step, vm.uiState.value.phase)
            assertEquals(PlayerPrimary.Done, vm.uiState.value.primary)
            vm.act(this, PlayerAction.Done)
            assertEquals(1, vm.uiState.value.index)
            assertEquals(
                1,
                vm.uiState.value.snapshot!!
                    .done.size,
            )
        }

    @Test
    fun `a timed step waiting at 0 offers Next, and the end offers Close, which leaves`() =
        runTest {
            val f = WorkoutFixture(this)
            f.settings.setAutoAdvance(false)
            f.plans.all.value = listOf(recoveryPlan(transition = 0, steps = listOf(GuidedStep("Shake out", duration = 15.seconds))))
            val guided = f.guided()
            val vm = player(f, guided)
            guided.startPlan(GuidedPlanId(1))
            advanceTimeBy(16.seconds)
            runCurrent()
            assertEquals(PlayerPrimary.Next, vm.uiState.value.primary)
            vm.act(this, PlayerAction.Next)
            assertEquals(GuidedPhase.Finished, vm.uiState.value.phase)
            assertEquals(PlayerPrimary.Close, vm.uiState.value.primary)
            vm.act(this, PlayerAction.Close)
            assertTrue(vm.uiState.value.leave)
            assertNull(guided.state.value.snapshot)
        }

    @Test
    fun `a step's image is read for the player`() =
        runTest {
            val f = WorkoutFixture(this)
            val bytes = byteArrayOf(1, 2, 3)
            f.plans.all.value =
                listOf(recoveryPlan(transition = 0, steps = listOf(GuidedStep("Prayer", duration = 30.seconds, image = "images/p.webp"))))
            val guided = f.guided()
            val vm = player(f, guided, FakeContentImages(mapOf("images/p.webp" to bytes)))
            guided.startPlan(GuidedPlanId(1))
            runCurrent()
            assertContentEquals(bytes, vm.uiState.value.image)
        }

    @Test
    fun `leaving a finished session's screen any way, Back too, dismisses it`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val store = ViewModelStore()
            store.put("player", player(f, guided))
            guided.startPlan(GuidedPlanId(1))
            guided.end()
            runCurrent()
            store.clear()
            runCurrent()
            assertNull(guided.state.value.snapshot)
        }

    @Test
    fun `leaving a running session's screen keeps it running`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val store = ViewModelStore()
            store.put("player", player(f, guided))
            guided.startPlan(GuidedPlanId(1))
            store.clear()
            runCurrent()
            assertTrue(guided.state.value.active)
        }

    @Test
    fun `a finished warm-up goes on to the first working exercise with sets left`() =
        runTest {
            val f = WorkoutFixture(this)
            f.days.addExercise(TestClock.TODAY, curl.id, WARMUP_SECTION)
            val main = f.days.addExercise(TestClock.TODAY, bench.id)!!
            f.days.addExercise(TestClock.TODAY, plank.id)
            val guided = f.guided()
            val vm = player(f, guided)
            guided.startWarmup(TestClock.TODAY)
            runCurrent()
            vm.act(this, PlayerAction.End)
            assertEquals(main, vm.uiState.value.continueTo)
            vm.act(this, PlayerAction.ContinueSeen)
            assertNull(vm.uiState.value.continueTo)
            assertNull(guided.state.value.snapshot)
        }
}
