// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlanId
import app.fit.guided.recoveryPlan
import app.fit.workout.TestClock.Companion.START
import app.fit.workout.TestClock.Companion.TODAY
import app.fit.workout.WorkoutFixture
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The clock starts at 10:00 UTC on TODAY.
@OptIn(ExperimentalCoroutinesApi::class)
class RemindersTest {
    private val plan = GuidedPlanId(1)
    private val noon = LocalTime(12, 0)
    private val utc = TimeZone.UTC

    private fun TestScope.advance(by: Duration) {
        advanceTimeBy(by)
        runCurrent()
    }

    private fun at(
        day: LocalDate,
        time: LocalTime,
    ): Instant = ReminderRules.at(day, time, utc)

    private val tomorrow = TODAY.plus(1, DateTimeUnit.DAY)

    @Test
    fun `a reminder set for later today is due at its time and for an hour after`() {
        val reminder = ReminderRules.set(plan, noon, enabled = true, START, utc)
        assertNull(reminder.shownOn)
        assertEquals(at(TODAY, noon), ReminderRules.next(reminder, START, utc))
        assertFalse(ReminderRules.due(reminder, START + 119.minutes, utc))
        assertTrue(ReminderRules.due(reminder, START + 2.hours, utc))
        assertTrue(ReminderRules.due(reminder, START + 3.hours, utc))
        assertFalse(ReminderRules.due(reminder, START + 3.hours + 1.seconds, utc))
        assertEquals(at(tomorrow, noon), ReminderRules.next(reminder, START + 3.hours + 1.seconds, utc))
    }

    @Test
    fun `a reminder set to a time already past today starts tomorrow`() {
        val reminder = ReminderRules.set(plan, LocalTime(9, 30), enabled = true, START, utc)
        assertEquals(TODAY, reminder.shownOn)
        assertFalse(ReminderRules.due(reminder, START, utc))
        assertEquals(at(tomorrow, LocalTime(9, 30)), ReminderRules.next(reminder, START, utc))
    }

    @Test
    fun `a reminder switched off is never due`() {
        val reminder = ReminderRules.set(plan, noon, enabled = false, START, utc)
        assertFalse(ReminderRules.due(reminder, START + 2.hours, utc))
    }

    @Test
    fun `a day that had its reminder gets no other when the time is moved later`() {
        val shown = Reminder(plan, LocalTime(9, 0), enabled = true, shownOn = TODAY)
        val moved = ReminderRules.set(plan, LocalTime(18, 0), enabled = true, START, utc, before = shown)
        assertEquals(TODAY, moved.shownOn)
        assertFalse(ReminderRules.due(moved, START + 8.hours, utc))
        assertEquals(at(tomorrow, LocalTime(18, 0)), ReminderRules.next(moved, START, utc))
    }

    @Test
    fun `a reminder marked for a later day, after the clock was set back, isn't due`() {
        val reminder = Reminder(plan, noon, enabled = true, shownOn = tomorrow)
        assertFalse(ReminderRules.due(reminder, START + 2.hours, utc))
    }

    @Test
    fun `the next wakeup is never a time already past`() {
        val reminder = ReminderRules.set(plan, noon, enabled = true, START, utc)
        assertEquals(at(tomorrow, noon), ReminderRules.next(reminder, START + 2.hours + 1.minutes, utc))
    }

    @Test
    fun `the time stays the same on the local clock across a daylight-saving change`() {
        val madrid = TimeZone.of("Europe/Madrid")
        val before = ReminderRules.at(LocalDate(2027, 3, 27), LocalTime(9, 0), madrid)
        val after = ReminderRules.at(LocalDate(2027, 3, 28), LocalTime(9, 0), madrid)
        assertEquals(23.hours, after - before)
    }

    @Test
    fun `setting a reminder schedules its alarm, and at its time it shows the session's name and length`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            assertEquals(at(TODAY, noon), r.alarms.scheduled)
            assertTrue(r.notices.shown.isEmpty())
            advance(2.hours)
            r.reminders.check()
            assertEquals(listOf(ReminderNotice(plan, "Wrist reset", 4)), r.notices.shown)
            assertEquals(at(tomorrow, noon), r.alarms.scheduled)
        }

    @Test
    fun `an alarm that comes early waits for the time, then shows`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            advance(2.hours - 3.minutes)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            assertEquals(at(TODAY, noon), r.alarms.scheduled)
            advance(3.minutes)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `moving the time later after it showed doesn't show it again that day`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            advance(2.hours)
            r.reminders.check()
            r.reminders.set(plan, LocalTime(18, 0), enabled = true)
            assertEquals(at(tomorrow, LocalTime(18, 0)), r.alarms.scheduled)
            advance(6.hours)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `a lesson's practice that day doesn't count as doing the session`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            f.plans.log(GuidedLog(plan, GuidedKind.Practice, "Push-up", TODAY, START, START + 4.minutes, GuidedOutcome.Completed, 2))
            advance(2.hours)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `refused notifications don't use the day up, and never leave an alarm in the past`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            r.notices.permitted = false
            advance(2.hours)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            assertEquals(at(tomorrow, noon), r.alarms.scheduled)
            // Allowed twenty minutes later: it still shows today.
            r.notices.permitted = true
            advance(20.minutes)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `a day gets its reminder once`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            advance(2.hours)
            r.reminders.check()
            advance(10.minutes)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `no reminder on a day the session was already done`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            f.plans.log(GuidedLog(plan, GuidedKind.Recovery, "Wrist reset", TODAY, START, START + 4.minutes, GuidedOutcome.Stopped, 1))
            advance(2.hours)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            assertEquals(at(tomorrow, noon), r.alarms.scheduled)
            // The next day it comes up again.
            advance(24.hours)
            r.reminders.check()
            assertEquals(1, r.notices.shown.size)
        }

    @Test
    fun `no reminder while that session is running`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val r = f.reminders(guided)
            r.reminders.set(plan, LocalTime(10, 1), enabled = true)
            advance(50.seconds)
            guided.startPlan(plan)
            advance(10.seconds)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
        }

    @Test
    fun `a reminder noticed over an hour late is left out for the day`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            advance(4.hours)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            assertEquals(at(tomorrow, noon), r.alarms.scheduled)
        }

    @Test
    fun `with several reminders the alarm is for the earliest, and each shows at its own time`() =
        runTest {
            val f = WorkoutFixture(this)
            f.plans.all.value = listOf(recoveryPlan(1, "Wrist reset"), recoveryPlan(2, "Neck release"))
            val r = f.reminders(f.guided())
            r.reminders.set(GuidedPlanId(2), LocalTime(18, 0), enabled = true)
            r.reminders.set(plan, noon, enabled = true)
            assertEquals(at(TODAY, noon), r.alarms.scheduled)
            advance(2.hours)
            r.reminders.check()
            assertEquals(listOf("Wrist reset"), r.notices.shown.map { it.title })
            assertEquals(at(TODAY, LocalTime(18, 0)), r.alarms.scheduled)
            advance(6.hours)
            r.reminders.check()
            assertEquals(listOf("Wrist reset", "Neck release"), r.notices.shown.map { it.title })
        }

    @Test
    fun `switching the reminder off cancels the alarm and takes its notification down`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            advance(2.hours)
            r.reminders.check()
            assertEquals(setOf(plan), r.notices.showing.keys)
            r.reminders.set(plan, noon, enabled = false)
            assertNull(r.alarms.scheduled)
            assertTrue(r.notices.showing.isEmpty())
        }

    @Test
    fun `starting the session takes its reminder down`() =
        runTest {
            val f = WorkoutFixture(this)
            val guided = f.guided()
            val r = f.reminders(guided)
            r.reminders.start()
            r.reminders.set(plan, noon, enabled = true)
            advance(2.hours)
            r.reminders.check()
            assertEquals(setOf(plan), r.notices.showing.keys)
            guided.startPlan(plan)
            runCurrent()
            assertTrue(r.notices.showing.isEmpty())
        }

    @Test
    fun `deleting the session cancels its alarm, and Undo schedules it again`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.start()
            r.reminders.set(plan, noon, enabled = true)
            runCurrent()
            val deleted = f.plans.delete(plan)!!
            runCurrent()
            assertNull(r.alarms.scheduled)
            assertTrue(f.plans.restore(deleted))
            runCurrent()
            assertEquals(at(TODAY, noon), r.alarms.scheduled)
        }

    @Test
    fun `after a time-zone change the alarm follows the local clock`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.reminders.set(plan, noon, enabled = true)
            r.zone = TimeZone.of("UTC+01:00")
            r.reminders.check()
            assertEquals(at(TODAY, noon) - 1.hours, r.alarms.scheduled)
        }

    @Test
    fun `a reminder for a session that's gone or isn't a recovery session shows nothing`() =
        runTest {
            val f = WorkoutFixture(this)
            val r = f.reminders(f.guided())
            r.repository.rows.value = listOf(Reminder(GuidedPlanId(99), noon, enabled = true))
            advance(2.hours)
            r.reminders.check()
            assertTrue(r.notices.shown.isEmpty())
            assertNull(r.alarms.scheduled)
        }
}
