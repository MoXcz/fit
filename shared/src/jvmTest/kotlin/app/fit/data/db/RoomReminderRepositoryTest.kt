// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import app.fit.content.ImportPreview
import app.fit.guided.FakeArchives
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedPlanId
import app.fit.reminders.Reminder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomReminderRepositoryTest {
    private val s = Storage()

    @AfterTest
    fun close() = s.close()

    private suspend fun session(): GuidedPlanId {
        val pack = FakeArchives.zip(Files10.recovery("wrist-reset", "Wrist reset"))
        s.importer.import(assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", pack)))
        return s.guided
            .plans(GuidedKind.Recovery)
            .first()
            .single()
            .id
    }

    @Test
    fun `a session has one reminder, and saving it again replaces it`(): Unit =
        runBlocking {
            val plan = session()
            assertNull(s.reminderRepository.reminder(plan).first())
            s.reminderRepository.save(Reminder(plan, LocalTime(7, 30), enabled = true))
            s.reminderRepository.save(Reminder(plan, LocalTime(18, 5), enabled = false, shownOn = LocalDate(2026, 11, 13)))
            assertEquals(
                listOf(Reminder(plan, LocalTime(18, 5), enabled = false, shownOn = LocalDate(2026, 11, 13))),
                s.reminderRepository.all(),
            )
        }

    @Test
    fun `the day it was last shown is kept apart from its time`(): Unit =
        runBlocking {
            val plan = session()
            s.reminderRepository.save(Reminder(plan, LocalTime(7, 30), enabled = true))
            s.reminderRepository.setShownOn(plan, LocalDate(2026, 11, 13))
            assertEquals(
                Reminder(plan, LocalTime(7, 30), enabled = true, shownOn = LocalDate(2026, 11, 13)),
                s.reminderRepository.reminder(plan).first(),
            )
        }

    @Test
    fun `deleting the session takes its reminder, and Undo brings it back`(): Unit =
        runBlocking {
            val plan = session()
            val reminder = Reminder(plan, LocalTime(7, 30), enabled = true)
            s.reminderRepository.save(reminder)
            val deleted = assertNotNull(s.guided.delete(plan))
            assertEquals(emptyList(), s.reminderRepository.all())
            assertTrue(s.guided.restore(deleted))
            assertEquals(listOf(reminder), s.reminderRepository.all())
        }

    @Test
    fun `values a hand-made row may hold are read without failing`(): Unit =
        runBlocking {
            val plan = session()
            s.execute("INSERT INTO reminder (planId, minuteOfDay, enabled, shownOn) VALUES (${plan.value}, 5000, 1, 'soon')")
            assertEquals(listOf(Reminder(plan, LocalTime(23, 59), enabled = true)), s.reminderRepository.all())
        }
}
