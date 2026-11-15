// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.reminders.ReminderRepository
import app.fit.reminders.Reminders
import app.fit.reminders.RoomReminderRepository
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

// ReminderAlarms and ReminderNotices come from the platform module.
val reminderModule =
    module {
        singleOf(::RoomReminderRepository) bind ReminderRepository::class
        singleOf(::Reminders)
    }
