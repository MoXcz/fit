// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.reminder

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fit.core.ApplicationScope
import app.fit.reminders.Reminders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

// The reminder alarm only wakes us; what's due, what shows and the next alarm are decided by
// Reminders.check() from the table and the clock. Adapted from Hush's LevelCheckReceiver.
open class ReminderReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val reminders: Reminders by inject()
    private val scope: ApplicationScope by inject()

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            // goAsync() gives about 10 s.
            try {
                withTimeout(TIMEOUT) { reminders.check() }
            } catch (_: TimeoutCancellationException) {
                // The next start of the app checks again.
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Storage failed; the next start of the app checks again.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val TIMEOUT = 8.seconds
    }
}

// Disabled in the manifest; AlarmReminderAlarms enables it only while a reminder is on. After a
// restart or an update the alarm is gone, and after a clock or time-zone change it's set for the
// wrong instant: each time the reminders are checked and scheduled again.
class ReminderRestartReceiver : ReminderReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action !in ACTIONS) return
        super.onReceive(context, intent)
    }

    private companion object {
        val ACTIONS =
            setOf(
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                Intent.ACTION_TIMEZONE_CHANGED,
                Intent.ACTION_TIME_CHANGED,
            )
    }
}
