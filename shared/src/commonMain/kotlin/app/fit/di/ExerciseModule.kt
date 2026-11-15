// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.exercises.ExerciseRepository
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.ui.ExerciseEditViewModel
import app.fit.exercises.ui.ExerciseViewModel
import app.fit.exercises.ui.ExercisesViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val exerciseModule =
    module {
        singleOf(::RoomExerciseRepository) bind ExerciseRepository::class
        viewModelOf(::ExercisesViewModel)
        // The exercise id is a parameter.
        viewModelOf(::ExerciseViewModel)
        viewModelOf(::ExerciseEditViewModel)
    }
