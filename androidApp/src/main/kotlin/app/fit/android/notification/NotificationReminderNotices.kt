// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.notification

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.getSystemService
import app.fit.android.MainActivity
import app.fit.android.R
import app.fit.android.setup.ForegroundActivity
import app.fit.guided.GuidedPlanId
import app.fit.reminders.ReminderNotice
import app.fit.reminders.ReminderNotices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// A reminder is one notification per session, on its own channel so it can be silenced without the
// workout's. It makes the system's sound, shows no badge and has no actions: a tap starts the
// session, a swipe is the end of it.
class NotificationReminderNotices(
    private val context: Context,
    private val permission: NotificationPermission,
    private val foreground: ForegroundActivity,
) : ReminderNotices {
    private val manager get() = NotificationManagerCompat.from(context)

    override fun allowed(): Boolean =
        manager.areNotificationsEnabled() &&
            manager.getNotificationChannel(WorkoutNotifications.REMINDER_CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    override suspend fun request(orSettings: Boolean): Boolean =
        withContext(Dispatchers.Main.immediate) {
            if (allowed()) return@withContext true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !WorkoutNotifications.canPost(context)) {
                val askedAt = SystemClock.elapsedRealtime()
                if (permission.request()) return@withContext allowed()
                // An answer this quick came from the system, not the user: it no longer asks.
                if (SystemClock.elapsedRealtime() - askedAt > UNASKED_MS) return@withContext false
            }
            if (orSettings) openSettings()
            false
        }

    override fun show(notice: ReminderNotice) {
        val id = notice.planId.value.toInt()
        val notification =
            NotificationCompat
                .Builder(context, WorkoutNotifications.REMINDER_CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notice.title)
                .setContentText(context.getString(R.string.notification_reminder_minutes, notice.minutes))
                .setContentIntent(start(notice.planId))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setBadgeIconType(NotificationCompat.BADGE_ICON_NONE)
                .build()
        if (!WorkoutNotifications.canPost(context)) return
        try {
            manager.notify(TAG, id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }

    override fun clear(planId: GuidedPlanId) {
        manager.cancel(TAG, planId.value.toInt())
    }

    override fun keepOnly(planIds: Set<GuidedPlanId>) {
        val ids = planIds.map { it.value.toInt() }.toSet()
        context
            .getSystemService<NotificationManager>()
            ?.activeNotifications
            .orEmpty()
            .filter { it.tag == TAG && it.id !in ids }
            .forEach { manager.cancel(TAG, it.id) }
    }

    // The same start as a pinned session's shortcut: the player opens on it running.
    private fun start(planId: GuidedPlanId): PendingIntent =
        PendingIntent.getActivity(
            context,
            planId.value.toInt(),
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_REMINDED)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_PLAN_ID, planId.value),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openSettings() {
        val activity = foreground.activity ?: return
        try {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        } catch (_: ActivityNotFoundException) {
        }
    }

    private companion object {
        const val TAG = "reminder"
        const val UNASKED_MS = 400L
    }
}
