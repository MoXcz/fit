// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.core.ZoneSource
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.GuidedController
import app.fit.guided.GuidedPlanId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

// As the database does: a reminder goes with its session, and comes back when Undo restores it.
class FakeReminderRepository(
    private val plans: FakeGuidedRepository,
) : ReminderRepository {
    val rows = MutableStateFlow<List<Reminder>>(emptyList())

    override val reminders: Flow<List<Reminder>> =
        combine(rows, plans.all) { rows, plans -> rows.filter { row -> plans.any { it.id == row.planId } }.sortedBy { it.planId.value } }

    override suspend fun all(): List<Reminder> = reminders.first()

    override fun reminder(planId: GuidedPlanId): Flow<Reminder?> = reminders.map { list -> list.firstOrNull { it.planId == planId } }

    override suspend fun save(reminder: Reminder) {
        rows.update { list -> list.filter { it.planId != reminder.planId } + reminder }
    }

    override suspend fun setShownOn(
        planId: GuidedPlanId,
        day: LocalDate,
    ) {
        rows.update { list -> list.map { if (it.planId == planId) it.copy(shownOn = day) else it } }
    }
}

class FakeReminderAlarms : ReminderAlarms {
    var scheduled: Instant? = null

    // Everything ever scheduled, in order.
    val history = mutableListOf<Instant>()
    var cancels = 0

    override fun schedule(at: Instant) {
        scheduled = at
        history += at
    }

    override fun cancel() {
        scheduled = null
        cancels++
    }
}

class FakeReminderNotices(
    // Whether notifications are allowed now, and what the user answers when asked.
    var permitted: Boolean = true,
    var grants: Boolean = true,
) : ReminderNotices {
    // What's showing, by session; and every notice ever shown, in order.
    val showing = mutableMapOf<GuidedPlanId, ReminderNotice>()
    val shown = mutableListOf<ReminderNotice>()
    var requests = 0
    var settingsOpened = 0

    override fun allowed(): Boolean = permitted

    override suspend fun request(orSettings: Boolean): Boolean {
        requests++
        if (!permitted && grants) permitted = true
        if (!permitted && orSettings) settingsOpened++
        return permitted
    }

    // As the platform does: without permission nothing shows, and nothing says so.
    override fun show(notice: ReminderNotice) {
        if (!permitted) return
        showing[notice.planId] = notice
        shown += notice
    }

    override fun clear(planId: GuidedPlanId) {
        showing -= planId
    }

    override fun keepOnly(planIds: Set<GuidedPlanId>) {
        showing.keys.retainAll(planIds)
    }
}

// The reminders around a guided controller, with fakes for the alarm and the notifications.
class ReminderFixture(
    plans: FakeGuidedRepository,
    sessions: GuidedController,
    clock: Clock,
    scope: CoroutineScope,
    var zone: TimeZone = TimeZone.UTC,
) {
    val repository = FakeReminderRepository(plans)
    val alarms = FakeReminderAlarms()
    val notices = FakeReminderNotices()
    val reminders = Reminders(repository, plans, sessions, alarms, notices, clock, ZoneSource { zone }, scope)
}
