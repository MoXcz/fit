// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import androidx.datastore.core.DataStore
import app.fit.content.Archives
import app.fit.content.BuiltInContent
import app.fit.content.ImageProbe
import app.fit.core.AppInfo
import app.fit.core.Documents
import app.fit.data.db.AppDatabase
import app.fit.guided.GuidedAlerts
import app.fit.guided.LauncherShortcuts
import app.fit.reminders.ReminderAlarms
import app.fit.reminders.ReminderNotices
import app.fit.routines.ui.PickerTarget
import app.fit.workout.WakeupScheduler
import app.fit.workout.WorkoutAlerts
import kotlinx.coroutines.CoroutineDispatcher
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.module
import org.koin.test.verify.verify
import kotlin.test.Test

class KoinModulesTest {
    @OptIn(KoinExperimentalAPI::class)
    @Test
    fun `shared modules have all their dependencies`() {
        // Platform-provided types, bound in androidApp's module.
        val platformTypes =
            listOf(
                CoroutineDispatcher::class,
                DataStore::class,
                AppDatabase::class,
                BuiltInContent::class,
                WakeupScheduler::class,
                WorkoutAlerts::class,
                GuidedAlerts::class,
                LauncherShortcuts::class,
                Archives::class,
                ImageProbe::class,
                Documents::class,
                ReminderAlarms::class,
                ReminderNotices::class,
                AppInfo::class,
                // Ids and dates passed as parameters, and the picker's target built from them.
                Long::class,
                String::class,
                PickerTarget::class,
            )
        module { includes(sharedModules) }.verify(extraTypes = platformTypes)
    }
}
