// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.data.prefs.DataStoreWorkoutStateStore
import app.fit.workout.WorkoutController
import app.fit.workout.WorkoutStateStore
import app.fit.workout.ui.WorkoutViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

// WakeupScheduler and WorkoutAlerts come from the platform module.
val workoutModule =
    module {
        singleOf(::DataStoreWorkoutStateStore) bind WorkoutStateStore::class
        singleOf(::WorkoutController)
        // The block id is a parameter.
        viewModelOf(::WorkoutViewModel)
    }
