// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.core.content.pm.ShortcutManagerCompat
import app.fit.android.alarm.expireEach
import app.fit.android.content.AndroidDocuments
import app.fit.android.service.SessionServiceStarter
import app.fit.android.shortcut.AndroidLauncherShortcuts
import app.fit.content.ImportInbox
import app.fit.core.ApplicationScope
import app.fit.guided.GuidedController
import app.fit.guided.GuidedPlanId
import app.fit.guided.PinnedShortcutSync
import app.fit.ui.App
import app.fit.ui.Launch
import app.fit.workout.WorkoutController
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {
    private val workout: WorkoutController by inject()
    private val guided: GuidedController by inject()
    private val scope: ApplicationScope by inject()
    private val serviceStarter: SessionServiceStarter by inject()
    private val shortcuts: PinnedShortcutSync by inject()
    private val documents: AndroidDocuments by inject()
    private val inbox: ImportInbox by inject()

    // What a launcher shortcut or the notification asked for; App acknowledges it.
    private var launch by mutableStateOf<Launch?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        documents.attach(this)
        // Not again after a configuration change.
        if (savedInstanceState == null) handle(intent)
        setContent { App(launch = launch, onLaunchSeen = { launch = null }) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    // A pinned session's shortcut, or its reminder, starts it here, so the player opens on it running.
    private fun handle(intent: Intent?) {
        when (intent?.action) {
            ACTION_START_WORKOUT -> {
                launch = Launch.StartWorkout
            }

            ACTION_OPEN_PLAYER -> {
                launch = Launch.OpenPlayer
            }

            // A .md file or a .zip pack shared with Fit, or opened with it: the import screen previews it.
            Intent.ACTION_SEND, Intent.ACTION_VIEW -> {
                val uri =
                    if (intent.action == Intent.ACTION_SEND) {
                        IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        intent.data
                    }
                // Only what a provider hands over, never a path into the app's own files.
                if (uri?.scheme != ContentResolver.SCHEME_CONTENT) return
                scope.launch {
                    // Under its own id, so this activity's import screen shows it and no other does.
                    val id = inbox.offer(documents.read(uri))
                    runOnUiThread { launch = Launch.OpenImport(id) }
                }
            }

            ACTION_START_SESSION, ACTION_START_REMINDED -> {
                val id = intent.getLongExtra(EXTRA_PLAN_ID, -1L).takeIf { it > 0 } ?: return
                if (intent.action == ACTION_START_REMINDED) {
                    // Reopened from the recents list, the task brings the tapped reminder's intent
                    // again; that isn't another tap.
                    if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
                } else {
                    // Lets the launcher rank the shortcuts by use.
                    ShortcutManagerCompat.reportShortcutUsed(this, AndroidLauncherShortcuts.shortcutId(id))
                }
                // On the app's scope: the start mustn't be cancelled with this activity.
                scope.launch { if (guided.startPlan(GuidedPlanId(id))) runOnUiThread { launch = Launch.OpenPlayer } }
            }
        }
    }

    // A rest, hold or step that ended while the screen was off ends now, whatever the alarm did; a
    // service start the system refused in the background, or shortcuts the launcher refused, are tried
    // again.
    override fun onResume() {
        super.onResume()
        serviceStarter.retryIfRunning()
        shortcuts.retry()
        scope.launch { expireEach({ guided.expireIfDue() }, { workout.expireIfDue() }) }
    }

    companion object {
        // The static launcher shortcut (res/xml/shortcuts.xml).
        const val ACTION_START_WORKOUT = "app.fit.action.START_WORKOUT"

        // A pinned session's dynamic shortcut, with its plan id.
        const val ACTION_START_SESSION = "app.fit.action.START_SESSION"
        const val EXTRA_PLAN_ID = "plan_id"

        // A tapped reminder, with its plan id: the same start as the shortcut's.
        const val ACTION_START_REMINDED = "app.fit.action.START_REMINDED"

        // The ongoing notification of a guided session.
        const val ACTION_OPEN_PLAYER = "app.fit.action.OPEN_PLAYER"
    }
}
