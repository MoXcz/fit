// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.notification

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.fit.android.MainActivity
import app.fit.android.R
import app.fit.guided.GuidedAlerts
import app.fit.workout.WorkoutAlerts
import kotlin.time.Instant

// What the ongoing notification shows. [base] is the chronometer's: counting down to it, or up from it.
// [player]: a guided session, which a tap opens on the player.
data class OngoingContent(
    val title: String,
    val text: String?,
    val base: Instant?,
    val countDown: Boolean,
    val player: Boolean = false,
)

// The channels the guide allows: the running workout (low), a timer's end (high) and reminders.
object WorkoutNotifications {
    const val WORKOUT_CHANNEL = "workout"
    const val TIMER_END_CHANNEL = "timer_end"
    const val REMINDER_CHANNEL = "reminders"
    const val ONGOING_ID = 1
    const val END_ID = 2

    fun createChannels(context: Context) {
        NotificationManagerCompat.from(context).createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat
                    .Builder(WORKOUT_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName(context.getString(R.string.channel_workout))
                    .setDescription(context.getString(R.string.channel_workout_description))
                    .setShowBadge(false)
                    .build(),
                // High, so it shows over the lock screen, but silent: EndCue beeps and vibrates, so the
                // alarm setting turns both off and the system never adds a second sound.
                NotificationChannelCompat
                    .Builder(TIMER_END_CHANNEL, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName(context.getString(R.string.channel_timer_end))
                    .setDescription(context.getString(R.string.channel_timer_end_description))
                    .setSound(null, null)
                    .setVibrationEnabled(false)
                    .build(),
                // Its own channel, so reminders can be silenced or switched off apart from the workout.
                // The system's default sound: the alarm setting is about timers, not these.
                NotificationChannelCompat
                    .Builder(REMINDER_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName(context.getString(R.string.channel_reminders))
                    .setDescription(context.getString(R.string.channel_reminders_description))
                    .setShowBadge(false)
                    .build(),
            ),
        )
    }

    // The clock is a system chronometer, so the notification only changes with the rest or hold.
    fun ongoing(
        context: Context,
        content: OngoingContent,
    ): Notification =
        NotificationCompat
            .Builder(context, WORKOUT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setContentIntent(if (content.player) openPlayer(context) else openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                val base = content.base
                if (base != null) {
                    setWhen(base.toEpochMilliseconds())
                    setShowWhen(true)
                    setUsesChronometer(true)
                    setChronometerCountDown(content.countDown)
                } else {
                    setShowWhen(false)
                }
            }.build()

    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun openPlayer(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun end(
        context: Context,
        title: String,
        text: String,
    ): Notification =
        NotificationCompat
            .Builder(context, TIMER_END_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSilent(true)
            .build()

    internal fun notify(
        context: Context,
        id: Int,
        notification: Notification,
    ) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the call.
        }
    }
}

class NotificationWorkoutAlerts(
    private val context: Context,
    private val cue: EndCue,
) : WorkoutAlerts {
    override fun restEnded(next: String?) {
        val text = next?.let { context.getString(R.string.notification_next, it) } ?: context.getString(R.string.notification_all_done)
        end(context.getString(R.string.notification_rest_over), text)
    }

    override fun holdEnded(exercise: String) {
        end(context.getString(R.string.notification_time), context.getString(R.string.notification_hold_done, exercise))
    }

    override fun clear() {
        NotificationManagerCompat.from(context).cancel(WorkoutNotifications.END_ID)
    }

    // A failed notification or sound must not take the workout down with it.
    private fun end(
        title: String,
        text: String,
    ) {
        try {
            WorkoutNotifications.notify(context, WorkoutNotifications.END_ID, WorkoutNotifications.end(context, title, text))
        } catch (e: RuntimeException) {
            // The notification is lost; the beep still plays.
        }
        try {
            cue.play()
        } catch (e: RuntimeException) {
            // The beep is lost; the notification still shows.
        }
    }
}

// A step's end only beeps and vibrates: the ongoing notification already names the next step.
class CueGuidedAlerts(
    private val cue: EndCue,
) : GuidedAlerts {
    override fun stepEnded() {
        try {
            cue.play()
        } catch (e: RuntimeException) {
            // The beep is lost; the session goes on.
        }
    }
}
