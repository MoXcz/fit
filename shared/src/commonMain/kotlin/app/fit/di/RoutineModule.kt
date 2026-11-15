// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.routines.RoomRoutineRepository
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import app.fit.routines.ui.ExercisePickerViewModel
import app.fit.routines.ui.ROUTINE_PICKER
import app.fit.routines.ui.RoutineEditorViewModel
import app.fit.routines.ui.RoutinePickerTarget
import app.fit.routines.ui.RoutinesViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val routineModule =
    module {
        singleOf(::RoomRoutineRepository) bind RoutineRepository::class
        viewModelOf(::RoutinesViewModel)
        // The routine id is a parameter.
        viewModelOf(::RoutineEditorViewModel)
        viewModel(named(ROUTINE_PICKER)) { (id: Long) -> ExercisePickerViewModel(RoutinePickerTarget(RoutineId(id), get()), get()) }
    }
