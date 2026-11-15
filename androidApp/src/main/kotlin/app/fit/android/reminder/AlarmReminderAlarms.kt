// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.getSystemService
import app.fit.reminders.ReminderAlarms
import kotlin.time.Instant

// One inexact alarm for the next reminder due; it fires in idle mode too. Inexact on purpose: nobody
// waits for a reminder to the second, and exact alarms are kept for the timers. Adapted from Hush's
// AlarmWakeupScheduler (the level check and its boot receiver).
class AlarmReminderAlarms(
    private val context: Context,
) : ReminderAlarms {
    private val alarms: AlarmManager? get() = context.getSystemService()

    override fun schedule(at: Instant) {
        setRestartReceiverEnabled(true)
        alarms?.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilliseconds(), intent())
    }

    override fun cancel() {
        alarms?.cancel(intent())
        setRestartReceiverEnabled(false)
    }

    // Alarms don't survive a restart or an update, and are set for an instant that a clock or
    // time-zone change makes the wrong one. The receiver for all of that only runs while a reminder
    // is on.
    private fun setRestartReceiverEnabled(enabled: Boolean) {
        val component = ComponentName(context, ReminderRestartReceiver::class.java)
        val state =
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        val packages = context.packageManager
        if (packages.getComponentEnabledSetting(component) == state) return
        packages.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP)
    }

    private fun intent(): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, ReminderReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
