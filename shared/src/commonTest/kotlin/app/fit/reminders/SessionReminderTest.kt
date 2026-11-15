// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.guided.GuidedPlanId
import app.fit.recovery.ui.SessionAction
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The reminder row on a session's page.
@OptIn(ExperimentalCoroutinesApi::class)
class SessionReminderTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.collect(flow: Flow<*>) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect {} }
    }

    @Test
    fun `a session has no reminder until it's switched on, then one at 9 in the morning`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            assertFalse(vm.uiState.value.reminderOn)
            assertEquals(LocalTime(9, 0), vm.uiState.value.reminderTime)
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            assertTrue(vm.uiState.value.reminderOn)
            assertEquals(
                LocalTime(9, 0),
                r.repository.rows.value
                    .single()
                    .time,
            )
            assertFalse(vm.uiState.value.reminderBlocked)
        }

    @Test
    fun `switching it on asks for notifications, and switching it off doesn't`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.notices.permitted = false
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            assertEquals(1, r.notices.requests)
            assertFalse(vm.uiState.value.reminderBlocked)
            vm.onAction(SessionAction.SetReminder(false))
            runCurrent()
            assertEquals(1, r.notices.requests)
            assertFalse(vm.uiState.value.reminderOn)
        }

    @Test
    fun `refused notifications leave the reminder on and the row says it can't show`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.notices.permitted = false
            r.notices.grants = false
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            assertTrue(vm.uiState.value.reminderOn)
            assertTrue(vm.uiState.value.reminderBlocked)
            assertEquals(0, r.notices.settingsOpened)
        }

    @Test
    fun `Allow asks again and, once the system no longer asks, opens the notification settings`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.notices.permitted = false
            r.notices.grants = false
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            vm.onAction(SessionAction.AllowNotifications)
            runCurrent()
            assertEquals(1, r.notices.settingsOpened)
            assertTrue(vm.uiState.value.reminderBlocked)
            // Allowed in the system's settings; back on the page.
            r.notices.permitted = true
            vm.onAction(SessionAction.Refresh)
            assertFalse(vm.uiState.value.reminderBlocked)
        }

    @Test
    fun `a reminder waiting for notifications shows as soon as they're allowed`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.repository.rows.value = listOf(Reminder(GuidedPlanId(1), LocalTime(10, 0), enabled = true))
            r.notices.permitted = false
            r.notices.grants = false
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            r.notices.permitted = true
            vm.onAction(SessionAction.Refresh)
            runCurrent()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `switching on a reminder that has a time keeps it, even before the page has loaded it`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.repository.rows.value = listOf(Reminder(GuidedPlanId(1), LocalTime(7, 30), enabled = false))
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            assertEquals(
                Reminder(GuidedPlanId(1), LocalTime(7, 30), enabled = true),
                r.repository.rows.value
                    .single()
                    .copy(shownOn = null),
            )
        }

    @Test
    fun `picking a time moves the reminder and switches it on, and closing the picker changes nothing`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            vm.onAction(SessionAction.PickTime)
            assertTrue(vm.uiState.value.pickingTime)
            vm.onAction(SessionAction.TimePicked(null))
            assertFalse(vm.uiState.value.pickingTime)
            assertTrue(
                r.repository.rows.value
                    .isEmpty(),
            )
            vm.onAction(SessionAction.PickTime)
            vm.onAction(SessionAction.TimePicked(LocalTime(18, 30)))
            runCurrent()
            assertTrue(vm.uiState.value.reminderOn)
            assertEquals(LocalTime(18, 30), vm.uiState.value.reminderTime)
        }

    @Test
    fun `switching the reminder off and on keeps its time`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            val vm = f.sessionViewModel(1, f.guided(), reminders = r)
            collect(vm.uiState)
            vm.onAction(SessionAction.TimePicked(LocalTime(18, 30)))
            runCurrent()
            vm.onAction(SessionAction.SetReminder(false))
            runCurrent()
            vm.onAction(SessionAction.SetReminder(true))
            runCurrent()
            assertEquals(LocalTime(18, 30), vm.uiState.value.reminderTime)
        }
}
