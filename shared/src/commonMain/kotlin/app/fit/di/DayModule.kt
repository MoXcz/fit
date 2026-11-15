// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.day.DayRepository
import app.fit.day.RoomDayRepository
import app.fit.day.ui.TodayViewModel
import app.fit.routines.ui.DAY_PICKER
import app.fit.routines.ui.DayPickerTarget
import app.fit.routines.ui.ExercisePickerViewModel
import kotlinx.datetime.LocalDate
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

val dayModule =
    module {
        singleOf(::RoomDayRepository) bind DayRepository::class
        viewModelOf(::TodayViewModel)
        viewModel(named(DAY_PICKER)) { (date: String) -> ExercisePickerViewModel(DayPickerTarget(LocalDate.parse(date), get()), get()) }
    }
