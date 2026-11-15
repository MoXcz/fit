// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.service

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import app.fit.android.notification.NotificationPermission
import app.fit.core.ApplicationScope
import app.fit.guided.GuidedController
import app.fit.workout.WorkoutController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

// Starts SessionService when a workout or a guided session starts running; the service stops itself.
class SessionServiceStarter(
    private val context: Context,
    private val workout: WorkoutController,
    private val guided: GuidedController,
    private val scope: ApplicationScope,
    private val notificationPermission: NotificationPermission,
) {
    private val active: Boolean get() = workout.state.value.active || guided.state.value.active

    fun start() {
        scope.launch {
            combine(workout.state, guided.state) { workout, guided -> workout.active || guided.active }
                .distinctUntilChanged()
                .filter { it }
                .collect {
                    ContextCompat.getMainExecutor(context).execute { notificationPermission.requestIfNeeded() }
                    startService()
                }
        }
    }

    // A start refused in the background is tried again when the app comes to the front.
    fun retryIfRunning() {
        if (active) startService()
    }

    private fun startService() {
        try {
            ContextCompat.startForegroundService(context, Intent(context, SessionService::class.java))
        } catch (e: IllegalStateException) {
            // Not allowed from the background (Android 12+). The wakeup alarm still fires.
        }
    }
}
