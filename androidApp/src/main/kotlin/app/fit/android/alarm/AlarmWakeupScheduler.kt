// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.getSystemService
import app.fit.workout.WakeupScheduler
import kotlin.time.Instant

// Exact when allowed (the user waits for the rest-end or step-end beep), inexact otherwise. One
// PendingIntent per purpose, so scheduling again replaces that purpose's earlier alarm.
class AlarmWakeupScheduler(
    private val context: Context,
) : WakeupScheduler {
    private val alarms: AlarmManager? get() = context.getSystemService()

    override fun scheduleWorkoutWakeup(at: Instant) = schedule(at, wakeIntent(WORKOUT_REQUEST))

    override fun cancelWorkoutWakeup() {
        alarms?.cancel(wakeIntent(WORKOUT_REQUEST))
    }

    override fun scheduleGuidedWakeup(at: Instant) = schedule(at, wakeIntent(GUIDED_REQUEST))

    override fun cancelGuidedWakeup() {
        alarms?.cancel(wakeIntent(GUIDED_REQUEST))
    }

    private fun schedule(
        at: Instant,
        intent: PendingIntent,
    ) {
        val alarms = alarms ?: return
        val millis = at.toEpochMilliseconds()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
            }
        } catch (e: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, intent)
        }
    }

    // One per purpose, so the workout's and the session's alarms never replace each other.
    private fun wakeIntent(request: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            request,
            Intent(context, SessionEndReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private companion object {
        const val WORKOUT_REQUEST = 0
        const val GUIDED_REQUEST = 1
    }
}
