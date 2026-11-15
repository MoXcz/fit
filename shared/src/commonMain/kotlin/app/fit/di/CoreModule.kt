// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.core.AppDispatchers
import app.fit.core.ApplicationScope
import app.fit.core.DataResets
import app.fit.core.SessionTimers
import app.fit.core.ZoneSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.datetime.TimeZone
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.binds
import org.koin.dsl.module
import kotlin.time.Clock

val coreModule =
    module {
        single { AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default) }
        // The only CoroutineScope in the graph: long-lived work (the running workout) runs here.
        singleOf(::ApplicationScope) binds arrayOf(ApplicationScope::class, CoroutineScope::class)
        single<Clock> { Clock.System }
        // Who times now: a workout's rest or hold, or a guided session.
        singleOf(::SessionTimers)
        singleOf(::DataResets)
        // A factory, so a time zone change on the device is picked up by the next screen that asks.
        factory { TimeZone.currentSystemDefault() }
        // For what outlives a screen (the reminders): asked each time it's needed.
        single<ZoneSource> { ZoneSource { TimeZone.currentSystemDefault() } }
    }

/**
 * Every shared module. The platform app adds the settings DataStore, AppDatabase, BuiltInContent,
 * Archives, Documents, WakeupScheduler, WorkoutAlerts, GuidedAlerts, LauncherShortcuts, ReminderAlarms
 * and ReminderNotices.
 */
val sharedModules =
    listOf(
        coreModule,
        dataModule,
        settingsModule,
        contentModule,
        exerciseModule,
        routineModule,
        dayModule,
        workoutModule,
        historyModule,
        programModule,
        bodyWeightModule,
        guidedModule,
        reminderModule,
        lessonModule,
        backupModule,
    )
