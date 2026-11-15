// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.di

import app.fit.android.alarm.AlarmWakeupScheduler
import app.fit.android.content.AndroidDocuments
import app.fit.android.content.AssetBuiltInContent
import app.fit.android.content.BitmapImageProbe
import app.fit.android.content.ZipArchives
import app.fit.android.notification.CueGuidedAlerts
import app.fit.android.notification.EndCue
import app.fit.android.notification.NotificationPermission
import app.fit.android.notification.NotificationReminderNotices
import app.fit.android.notification.NotificationWorkoutAlerts
import app.fit.android.reminder.AlarmReminderAlarms
import app.fit.android.service.SessionServiceStarter
import app.fit.android.setup.ForegroundActivity
import app.fit.android.shortcut.AndroidLauncherShortcuts
import app.fit.content.Archives
import app.fit.content.BuiltInContent
import app.fit.content.ImageProbe
import app.fit.core.AppInfo
import app.fit.core.Documents
import app.fit.data.db.createAppDatabase
import app.fit.data.prefs.createSettingsDataStore
import app.fit.guided.GuidedAlerts
import app.fit.guided.LauncherShortcuts
import app.fit.reminders.ReminderAlarms
import app.fit.reminders.ReminderNotices
import app.fit.workout.WakeupScheduler
import app.fit.workout.WorkoutAlerts
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.bind
import org.koin.dsl.module

val androidModule =
    module {
        single { createSettingsDataStore(androidContext()) }
        single { createAppDatabase(androidContext(), get()) }
        single<BuiltInContent> { AssetBuiltInContent(androidContext(), get()) }
        single { ForegroundActivity() }
        single<WakeupScheduler> { AlarmWakeupScheduler(androidContext()) }
        single { EndCue(androidContext()) }
        single<WorkoutAlerts> { NotificationWorkoutAlerts(androidContext(), get()) }
        single<GuidedAlerts> { CueGuidedAlerts(get()) }
        single<LauncherShortcuts> { AndroidLauncherShortcuts(androidContext()) }
        single<ReminderAlarms> { AlarmReminderAlarms(androidContext()) }
        single<ReminderNotices> { NotificationReminderNotices(androidContext(), get(), get()) }
        single<Archives> { ZipArchives() }
        single {
            AppInfo(
                androidContext()
                    .packageManager
                    .getPackageInfo(androidContext().packageName, 0)
                    .versionName
                    .orEmpty(),
            )
        }
        single<ImageProbe> { BitmapImageProbe() }
        single { AndroidDocuments(androidContext(), get(), get()) } bind Documents::class
        single { NotificationPermission(androidContext(), get()) }
        single { SessionServiceStarter(androidContext(), get(), get(), get(), get()) }
    }
