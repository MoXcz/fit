// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import app.fit.android.R
import app.fit.android.notification.OngoingContent
import app.fit.android.notification.WorkoutNotifications
import app.fit.core.AppDispatchers
import app.fit.guided.GuidedController
import app.fit.guided.GuidedPhase
import app.fit.guided.GuidedState
import app.fit.guided.Interstitial
import app.fit.guided.StepClock
import app.fit.workout.HoldRun
import app.fit.workout.WorkoutController
import app.fit.workout.WorkoutState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

// Keeps the process alive while today's workout or a guided session runs, and shows it as one ongoing
// notification. It only renders the controllers' state and stops itself once both are over.
class SessionService : Service() {
    private val workout: WorkoutController by inject()
    private val guided: GuidedController by inject()
    private val dispatchers: AppDispatchers by inject()
    private val scope by lazy { CoroutineScope(SupervisorJob() + dispatchers.default) }
    private var rendering: Job? = null

    // The latest start, so stopping never cancels one that arrived after the session ended.
    @Volatile private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        // Must be foreground within seconds of startForegroundService(), even if about to stop.
        val content =
            content(workout.state.value, guided.state.value)
                ?: OngoingContent(getString(R.string.notification_workout), null, null, false)
        ServiceCompat.startForeground(
            this,
            WorkoutNotifications.ONGOING_ID,
            WorkoutNotifications.ongoing(this, content),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        if (rendering == null) {
            rendering =
                scope.launch {
                    combine(workout.state, guided.state, ::content)
                        .distinctUntilChanged()
                        .collect { render(it) }
                }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun render(content: OngoingContent?) {
        if (content == null) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(lastStartId)
            return
        }
        if (WorkoutNotifications.canPost(this)) {
            try {
                NotificationManagerCompat
                    .from(this)
                    .notify(WorkoutNotifications.ONGOING_ID, WorkoutNotifications.ongoing(this, content))
            } catch (e: SecurityException) {
                // Permission revoked between the check and the call; the foreground notice stays as it was.
            }
        }
    }

    // The one that's timing shows; with neither timing, a guided session shows over the workout clock.
    private fun content(
        workout: WorkoutState,
        guided: GuidedState,
    ): OngoingContent? =
        when {
            guided.active && (guided.snapshot?.timing == true || !workout.snapshot.timing) -> guided.content()
            workout.active -> workout.content()
            else -> null
        }

    // Resting: down to the end, then up from it. Holding: down to the end; paused, no clock. Otherwise
    // the workout counts up from its start. "Next:" is always there.
    private fun WorkoutState.content(): OngoingContent {
        val next = next?.let { getString(R.string.notification_next, it) } ?: getString(R.string.notification_all_done)
        val rest = rest
        val hold = hold
        return when {
            rest != null && !rest.rang -> {
                OngoingContent(getString(R.string.notification_rest), next, rest.endsAt, countDown = true)
            }

            rest != null -> {
                OngoingContent(getString(R.string.notification_rest_over), next, rest.endsAt, countDown = false)
            }

            hold != null -> {
                val running = hold.run as? HoldRun.Running
                val state = getString(if (running != null) R.string.notification_hold else R.string.notification_paused)
                val title = holding?.let { getString(R.string.notification_holding, it, state) } ?: state
                OngoingContent(title, next, running?.endsAt, countDown = true)
            }

            else -> {
                OngoingContent(name.ifBlank { getString(R.string.notification_workout) }, next, startedAt, countDown = false)
            }
        }
    }

    // The step (or the pause before it) counting down, and what comes next; paused or waiting, no clock.
    private fun GuidedState.content(): OngoingContent? {
        val snapshot = snapshot ?: return null
        val item = item ?: return null
        val running = (snapshot.clock as? StepClock.Running)?.endsAt
        val paused = snapshot.clock is StepClock.Paused
        val name =
            item.side?.let { getString(R.string.notification_step_side, item.name, it) }
                ?: item.reps?.takeIf { snapshot.phase == GuidedPhase.Step }?.let {
                    resources.getQuantityString(R.plurals.notification_step_reps, it, item.name, it)
                }
                ?: item.name
        val title =
            when {
                snapshot.phase == GuidedPhase.Transition && run?.interstitial(snapshot.index) == Interstitial.SwitchSides -> {
                    getString(R.string.notification_switch_sides)
                }

                snapshot.phase == GuidedPhase.Transition -> {
                    getString(R.string.notification_get_ready)
                }

                else -> {
                    name
                }
            }.let { if (paused) getString(R.string.notification_holding, it, getString(R.string.notification_paused)) else it }
        val text =
            if (snapshot.phase == GuidedPhase.Transition) {
                getString(R.string.notification_next, name)
            } else {
                upcoming?.let { getString(R.string.notification_next, it.name) } ?: getString(R.string.notification_last_step)
            }
        return OngoingContent(title, text, running, countDown = true, player = true)
    }
}
