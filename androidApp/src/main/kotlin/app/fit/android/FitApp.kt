// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android

import android.app.Application
import app.fit.android.di.androidModule
import app.fit.android.notification.WorkoutNotifications
import app.fit.android.service.SessionServiceStarter
import app.fit.android.setup.ForegroundActivity
import app.fit.content.BuiltInContentSync
import app.fit.core.ApplicationScope
import app.fit.di.sharedModules
import app.fit.guided.PinnedShortcutSync
import app.fit.reminders.Reminders
import kotlinx.coroutines.launch
import org.koin.android.ext.android.get
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.startKoin
import java.io.IOException

class FitApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@FitApp)
            modules(sharedModules + androidModule)
        }
        registerActivityLifecycleCallbacks(get<ForegroundActivity>())
        WorkoutNotifications.createChannels(this)
        // Also restores a rest, hold or guided session that was running when the process died.
        get<SessionServiceStarter>().start()
        get<PinnedShortcutSync>().start()
        // Reminders are scheduled from the table at every start: after a force stop nothing else would.
        get<Reminders>().start()
        // Built-in content on first start and after an update; screens show it as it arrives.
        get<ApplicationScope>().launch {
            try {
                get<BuiltInContentSync>().syncIfChanged()
            } catch (_: IOException) {
                // An unreadable asset: nothing was stored, so the next start tries again.
            }
        }
    }
}
