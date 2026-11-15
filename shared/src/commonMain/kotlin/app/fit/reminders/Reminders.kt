// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.core.ZoneSource
import app.fit.day.localDate
import app.fit.guided.GuidedController
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedRepository
import app.fit.guided.GuidedSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalTime
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * The one owner of the daily reminders: what is due, what shows and when to wake next. Everything
 * goes through [check], which is idempotent: the alarm, a restart of the phone, an app update, a
 * clock or time-zone change, a changed reminder, a restore and a wipe all call it.
 *
 * A reminder shows once a day at its local time, unless its session was already done that day
 * (logged: completed, or stopped with a step done) or is running. One that's noticed more than
 * [ReminderRules.LATE] late is left out. Nothing is said about days without it.
 */
class Reminders(
    private val repository: ReminderRepository,
    private val plans: GuidedRepository,
    private val sessions: GuidedController,
    private val alarms: ReminderAlarms,
    private val notices: ReminderNotices,
    private val clock: Clock,
    private val zone: ZoneSource,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()

    /** Schedules what the table holds, now and whenever it changes. Called once, when the app starts. */
    fun start() {
        scope.launch { retrying { repository.reminders.collect { check() } } }
        // A session that starts has no use for its reminder any more.
        scope.launch {
            sessions.state
                .map { state -> (state.snapshot?.takeIf { state.active }?.source as? GuidedSource.Plan)?.id }
                .distinctUntilChanged()
                .collect { id -> if (id != null) quietly { notices.clear(id) } }
        }
    }

    /**
     * Sets [planId]'s reminder; without a [time] it keeps the one it has (9:00 at first). One switched
     * on, or moved, to a time already past today starts tomorrow.
     */
    suspend fun set(
        planId: GuidedPlanId,
        time: LocalTime?,
        enabled: Boolean,
    ) {
        mutex.withLock {
            val before = repository.all().firstOrNull { it.planId == planId }
            val at = time ?: before?.time ?: ReminderRules.DEFAULT_TIME
            repository.save(ReminderRules.set(planId, at, enabled, clock.now(), zone.current(), before))
        }
        check()
    }

    /** Shows what's due, drops reminders whose session or switch went, and sets the next wakeup. */
    suspend fun check() =
        mutex.withLock {
            val now = clock.now()
            val zone = zone.current()
            val today = now.localDate(zone)
            val enabled = repository.all().filter { it.enabled }
            quietly { notices.keepOnly(enabled.map { it.planId }.toSet()) }
            val due = enabled.filter { ReminderRules.due(it, now, zone) }
            if (due.isNotEmpty()) {
                val state = sessions.state.value
                val running = (state.snapshot?.takeIf { state.active }?.source as? GuidedSource.Plan)?.id
                val done = plans.logs(GuidedKind.Recovery, today, today).first().mapNotNull { it.planId }
                val allowed = notices.allowed()
                for (reminder in due) {
                    val plan = plans.plan(reminder.planId).first()?.takeIf { it.kind == GuidedKind.Recovery }
                    val wanted = plan != null && plan.id != running && plan.id !in done
                    // Refused notifications don't use the day up: allowed within the hour, it still shows.
                    if (wanted && !allowed) continue
                    if (plan != null && wanted) quietly { notices.show(ReminderNotice(plan.id, plan.title, plan.minutes)) }
                    repository.setShownOn(reminder.planId, today)
                }
            }
            val next = repository.all().filter { it.enabled }.minOfOrNull { ReminderRules.next(it, now, zone) }
            quietly { if (next != null) alarms.schedule(next) else alarms.cancel() }
        }

    // A failed read (storage) is tried again; the alarm and the next start check too.
    private suspend fun retrying(collect: suspend () -> Unit) {
        while (true) {
            try {
                collect()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                delay(1.seconds)
            }
        }
    }
}

// The platform's notifications and alarms can fail; that mustn't lose the rest of the check.
private inline fun quietly(block: () -> Unit) {
    try {
        block()
    } catch (_: RuntimeException) {
    }
}
