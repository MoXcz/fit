// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.notification

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import app.fit.android.setup.ForegroundActivity
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger

// Asks for POST_NOTIFICATIONS the first time the workout runs (not at launch), once per process, and
// when a reminder is switched on.
class NotificationPermission(
    private val context: Context,
    private val foreground: ForegroundActivity,
) {
    private var asked = false
    private val keys = AtomicInteger()

    // Main thread only.
    fun requestIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || asked || WorkoutNotifications.canPost(context)) return
        val registry = foreground.activityResultRegistry ?: return
        asked = true
        var launcher: ActivityResultLauncher<String>? = null
        launcher =
            registry.register("notification-permission-${keys.incrementAndGet()}", ActivityResultContracts.RequestPermission()) {
                launcher?.unregister()
            }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // The same question, asked when a reminder is switched on, with its answer. Main thread only.
    // False too when no activity is in front to ask from.
    suspend fun request(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || WorkoutNotifications.canPost(context)) return true
        val registry = foreground.activityResultRegistry ?: return false
        asked = true
        val answer = CompletableDeferred<Boolean>()
        val launcher =
            registry.register("notification-permission-${keys.incrementAndGet()}", ActivityResultContracts.RequestPermission()) {
                answer.complete(it)
            }
        return try {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            answer.await()
        } finally {
            launcher.unregister()
        }
    }
}
