// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import app.fit.core.BodyArea
import app.fit.guided.FakeContentImages
import app.fit.guided.FakeLauncherShortcuts
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedRepository
import app.fit.guided.GuidedSource
import app.fit.guided.PinnedShortcut
import app.fit.guided.PinnedShortcutSync
import app.fit.guided.recoveryPlan
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RecoveryViewModelsTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    private fun WorkoutFixture.threePlans() {
        plans.all.value =
            listOf(
                recoveryPlan(1, "Wrist reset"),
                recoveryPlan(2, "Neck release", areas = listOf(BodyArea.Neck, BodyArea.UpperBack)),
                recoveryPlan(3, "Desk break", areas = listOf(BodyArea.Wrists, BodyArea.Neck)),
            )
    }

    @Test
    fun `Recover shows chips only for areas with sessions, and a chip lists that area's sessions`() =
        runTest {
            val f = WorkoutFixture(this)
            f.threePlans()
            val vm = RecoverViewModel(f.plans, f.guided())
            collect(vm.uiState)
            val state = vm.uiState.value
            assertEquals(listOf(BodyArea.Wrists, BodyArea.Forearms, BodyArea.Neck, BodyArea.UpperBack), state.areas)
            assertEquals(listOf("Desk break", "Neck release", "Wrist reset"), state.shown.map { it.title })
            vm.onAction(RecoverAction.SelectArea(BodyArea.Neck))
            assertEquals(
                listOf("Desk break", "Neck release"),
                vm.uiState.value.shown
                    .map { it.title },
            )
            // The chosen chip again shows every session.
            vm.onAction(RecoverAction.SelectArea(BodyArea.Neck))
            assertNull(vm.uiState.value.area)
            assertEquals(3, vm.uiState.value.shown.size)
        }

    @Test
    fun `pinned sessions come first, the latest pin first`() =
        runTest {
            val f = WorkoutFixture(this)
            f.threePlans()
            val vm = RecoverViewModel(f.plans, f.guided())
            collect(vm.uiState)
            f.plans.setPinned(GuidedPlanId(1), true)
            advanceTimeBy(1.minutes)
            f.plans.setPinned(GuidedPlanId(3), true)
            runCurrent()
            assertEquals(
                listOf("Desk break", "Wrist reset"),
                vm.uiState.value.pinned
                    .map { it.title },
            )
        }

    @Test
    fun `one tap on a session starts it and opens the player`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val vm = RecoverViewModel(f.plans, guided)
            collect(vm.uiState)
            vm.onAction(RecoverAction.Start(GuidedPlanId(1)))
            runCurrent()
            assertTrue(vm.uiState.value.opened)
            assertEquals(
                GuidedSource.Plan(GuidedPlanId(1)),
                guided.state.value.snapshot
                    ?.source,
            )
            vm.onAction(RecoverAction.OpenedSeen)
            assertFalse(vm.uiState.value.opened)
        }

    @Test
    fun `the session page pins and unpins, and Start opens the player`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val vm = f.sessionViewModel(1, guided, FakeContentImages())
            collect(vm.uiState)
            assertEquals(
                "Wrist reset",
                vm.uiState.value.plan
                    ?.title,
            )
            vm.onAction(SessionAction.TogglePin)
            runCurrent()
            assertTrue(
                vm.uiState.value.plan!!
                    .pinned,
            )
            vm.onAction(SessionAction.TogglePin)
            runCurrent()
            assertFalse(
                vm.uiState.value.plan!!
                    .pinned,
            )
            vm.onAction(SessionAction.Start)
            runCurrent()
            assertTrue(vm.uiState.value.opened)
            assertTrue(guided.state.value.active)
        }

    @Test
    fun `a running session shows first on Recover with Resume, which opens the player`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val vm = RecoverViewModel(f.plans, guided)
            collect(vm.uiState)
            assertNull(vm.uiState.value.running)
            guided.startPlan(GuidedPlanId(1))
            runCurrent()
            assertEquals(
                "Wrist reset",
                vm.uiState.value.running
                    ?.snapshot
                    ?.title,
            )
            vm.onAction(RecoverAction.Resume)
            assertTrue(vm.uiState.value.opened)
            guided.end()
            runCurrent()
            assertNull(vm.uiState.value.running)
        }

    @Test
    fun `shortcuts the launcher refused are offered again on the next resume`() =
        runTest {
            val f = WorkoutFixture(this)
            val shortcuts = FakeLauncherShortcuts()
            val sync = PinnedShortcutSync(f.plans, shortcuts, backgroundScope)
            shortcuts.refusing = true
            sync.start()
            f.plans.setPinned(GuidedPlanId(1), true)
            runCurrent()
            assertEquals(emptyList(), shortcuts.published)
            shortcuts.refusing = false
            sync.retry()
            runCurrent()
            assertEquals(listOf(listOf(PinnedShortcut(GuidedPlanId(1), "Wrist reset"))), shortcuts.published)
            // Nothing left to offer.
            sync.retry()
            runCurrent()
            assertEquals(1, shortcuts.published.size)
        }

    @Test
    fun `a pinned list that fails to read is watched again, not dropped`() =
        runTest {
            val f = WorkoutFixture(this)
            val shortcuts = FakeLauncherShortcuts()
            var reads = 0
            val failing =
                object : GuidedRepository by f.plans {
                    override val pinned: Flow<List<GuidedPlan>> =
                        flow {
                            if (reads++ == 0) throw IllegalStateException("disk")
                            emitAll(f.plans.pinned)
                        }
                }
            PinnedShortcutSync(failing, shortcuts, backgroundScope).start()
            runCurrent()
            advanceTimeBy(2.seconds)
            f.plans.setPinned(GuidedPlanId(1), true)
            runCurrent()
            assertEquals(listOf(PinnedShortcut(GuidedPlanId(1), "Wrist reset")), shortcuts.published.last())
        }

    @Test
    fun `pinned sessions become launcher shortcuts, the latest pin first, and unpinning removes one`() =
        runTest {
            val f = WorkoutFixture(this)
            f.threePlans()
            val shortcuts = FakeLauncherShortcuts()
            PinnedShortcutSync(f.plans, shortcuts, backgroundScope).start()
            runCurrent()
            f.plans.setPinned(GuidedPlanId(2), true)
            runCurrent()
            advanceTimeBy(1.minutes)
            f.plans.setPinned(GuidedPlanId(1), true)
            runCurrent()
            f.plans.setPinned(GuidedPlanId(2), false)
            runCurrent()
            assertEquals(
                listOf(
                    emptyList(),
                    listOf(PinnedShortcut(GuidedPlanId(2), "Neck release")),
                    listOf(PinnedShortcut(GuidedPlanId(1), "Wrist reset"), PinnedShortcut(GuidedPlanId(2), "Neck release")),
                    listOf(PinnedShortcut(GuidedPlanId(1), "Wrist reset")),
                ),
                shortcuts.published,
            )
        }

    @Test
    fun `Delete removes the session and offers Undo, which brings it back with its pin and its logs' link`() =
        runTest {
            val f = WorkoutFixture(this)
            f.plans.setPinned(GuidedPlanId(1), true)
            val start = f.clock.now()
            f.plans.log(
                GuidedLog(GuidedPlanId(1), GuidedKind.Recovery, "Wrist reset", TODAY, start, start + 1.minutes, GuidedOutcome.Completed, 3),
            )
            val vm = f.sessionViewModel(1, f.guided(), FakeContentImages())
            collect(vm.uiState)

            vm.onAction(SessionAction.Delete)
            runCurrent()
            assertNull(vm.uiState.value.plan)
            assertEquals("Wrist reset", vm.uiState.value.deleted)
            assertFalse(vm.uiState.value.closed)
            // The run stays in the history.
            assertNull(
                f.plans.logged.value
                    .single()
                    .planId,
            )

            vm.onAction(SessionAction.UndoDelete)
            runCurrent()
            val back = vm.uiState.value.plan!!
            assertTrue(back.pinned)
            assertNull(vm.uiState.value.deleted)
            assertEquals(
                GuidedPlanId(1),
                f.plans.logged.value
                    .single()
                    .planId,
            )
            // The offer going away with the session back leaves the page open.
            vm.onAction(SessionAction.UndoSeen)
            assertFalse(vm.uiState.value.closed)
        }

    @Test
    fun `once the Undo offer runs out the deleted session's page closes`() =
        runTest {
            val f = WorkoutFixture(this)
            val vm = f.sessionViewModel(1, f.guided(), FakeContentImages())
            collect(vm.uiState)
            vm.onAction(SessionAction.Delete)
            runCurrent()
            vm.onAction(SessionAction.UndoSeen)
            runCurrent()
            assertTrue(vm.uiState.value.closed)
            assertNull(vm.uiState.value.deleted)
        }

    @Test
    fun `deleting a session that is running ends it first, logged with the steps done`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val vm = f.sessionViewModel(1, guided, FakeContentImages())
            collect(vm.uiState)
            vm.onAction(SessionAction.Start)
            runCurrent()
            // Past the 5 s pause, then Done on the counted first step.
            advanceTimeBy(6.seconds)
            guided.done()
            runCurrent()

            vm.onAction(SessionAction.Delete)
            runCurrent()
            assertFalse(guided.state.value.active)
            assertNull(guided.state.value.snapshot)
            assertNull(f.guidedStore.stored)
            val log =
                f.plans.logged.value
                    .single()
            assertEquals(GuidedOutcome.Stopped, log.outcome)
            assertEquals(1, log.stepsDone)
            // Undo gives the run its session back too.
            vm.onAction(SessionAction.UndoDelete)
            runCurrent()
            assertEquals(
                GuidedPlanId(1),
                f.plans.logged.value
                    .single()
                    .planId,
            )
        }

    @Test
    fun `an Undo that can't put the session back says so`() =
        runTest {
            val f = WorkoutFixture(this)
            val vm = f.sessionViewModel(1, f.guided(), FakeContentImages())
            collect(vm.uiState)
            vm.onAction(SessionAction.Delete)
            runCurrent()
            // Meanwhile an import brought a session with its id.
            f.plans.all.value = listOf(recoveryPlan(1, "Imported"))
            vm.onAction(SessionAction.UndoDelete)
            runCurrent()
            assertTrue(vm.uiState.value.undoFailed)
            assertEquals(
                "Imported",
                vm.uiState.value.plan
                    ?.title,
            )
            vm.onAction(SessionAction.UndoSeen)
            assertFalse(vm.uiState.value.undoFailed)
            assertFalse(vm.uiState.value.closed)
        }
}
