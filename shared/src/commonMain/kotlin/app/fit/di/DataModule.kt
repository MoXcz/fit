// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.data.db.AppDatabase
import org.koin.dsl.module

// The AppDatabase instance comes from the platform module (it owns the file path).
val dataModule =
    module {
        single { get<AppDatabase>().exerciseDao() }
        single { get<AppDatabase>().routineDao() }
        single { get<AppDatabase>().dayDao() }
        single { get<AppDatabase>().programDao() }
        single { get<AppDatabase>().bodyWeightDao() }
        single { get<AppDatabase>().guidedDao() }
        single { get<AppDatabase>().lessonDao() }
        single { get<AppDatabase>().imageDao() }
        single { get<AppDatabase>().reminderDao() }
    }
