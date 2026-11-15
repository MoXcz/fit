// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.data.db.ReminderDao
import app.fit.data.db.ReminderEntity
import app.fit.guided.GuidedPlanId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime

/** The daily reminders, at most one for a recovery session. A deleted session's goes with it. */
interface ReminderRepository {
    val reminders: Flow<List<Reminder>>

    suspend fun all(): List<Reminder>

    fun reminder(planId: GuidedPlanId): Flow<Reminder?>

    /** Writes [reminder] in place of the session's earlier one. */
    suspend fun save(reminder: Reminder)

    /** Today had it: shown, or left out. */
    suspend fun setShownOn(
        planId: GuidedPlanId,
        day: LocalDate,
    )
}

class RoomReminderRepository(
    private val dao: ReminderDao,
) : ReminderRepository {
    override val reminders: Flow<List<Reminder>> = dao.all().map { rows -> rows.map { it.toReminder() } }

    override suspend fun all(): List<Reminder> = dao.list().map { it.toReminder() }

    override fun reminder(planId: GuidedPlanId): Flow<Reminder?> = dao.byPlan(planId.value).map { it?.toReminder() }

    override suspend fun save(reminder: Reminder) =
        dao.upsert(
            ReminderEntity(
                planId = reminder.planId.value,
                minuteOfDay = reminder.time.hour * MINUTES_PER_HOUR + reminder.time.minute,
                enabled = reminder.enabled,
                shownOn = reminder.shownOn?.toString(),
            ),
        )

    override suspend fun setShownOn(
        planId: GuidedPlanId,
        day: LocalDate,
    ) = dao.setShownOn(planId.value, day.toString())
}

private fun dayOrNull(text: String): LocalDate? =
    try {
        LocalDate.parse(text)
    } catch (_: IllegalArgumentException) {
        null
    }

private const val MINUTES_PER_HOUR = 60
private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR

// Values a restored backup may hold are read without failing: a time outside the day is brought
// into it, and a day that isn't a date counts as none.
private fun ReminderEntity.toReminder(): Reminder {
    val minute = minuteOfDay.coerceIn(0, MINUTES_PER_DAY - 1)
    return Reminder(
        planId = GuidedPlanId(planId),
        time = LocalTime(minute / MINUTES_PER_HOUR, minute % MINUTES_PER_HOUR),
        enabled = enabled,
        shownOn = shownOn?.let(::dayOrNull),
    )
}
