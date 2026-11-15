// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.reminders

import app.fit.guided.GuidedPlanId
import kotlin.time.Instant

/**
 * Wakes the app when the next reminder is due, even while it sleeps or is dead, and again after the
 * phone restarts, the app is updated or the clock or time zone changes ([Reminders.check]). One alarm:
 * scheduling again replaces it. It needn't be exact, and never decides anything itself.
 */
interface ReminderAlarms {
    fun schedule(at: Instant)

    fun cancel()
}

// What a reminder shows: the session's name and its length.
data class ReminderNotice(
    val planId: GuidedPlanId,
    val title: String,
    val minutes: Int,
)

/** The reminder notifications, one per session; a tap starts the session and opens the player. */
interface ReminderNotices {
    /** Whether a reminder can show: notifications are allowed for the app and for reminders. */
    fun allowed(): Boolean

    /**
     * Asks the user to allow notifications with the system's question. With [orSettings] (the user's
     * own tap on Allow), once the system no longer asks it opens the app's notification settings
     * instead. True when reminders can show afterwards.
     */
    suspend fun request(orSettings: Boolean = false): Boolean

    /** Shows it, in place of an earlier one for the same session. */
    fun show(notice: ReminderNotice)

    fun clear(planId: GuidedPlanId)

    /** Removes the reminders showing for any other session. */
    fun keepOnly(planIds: Set<GuidedPlanId>)
}
