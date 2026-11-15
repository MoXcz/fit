// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.programs.ProgramRepository
import app.fit.programs.RoomProgramRepository
import app.fit.programs.ui.ProgramEditorViewModel
import app.fit.programs.ui.ProgramsViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val programModule =
    module {
        singleOf(::RoomProgramRepository) bind ProgramRepository::class
        viewModelOf(::ProgramsViewModel)
        // The program id is a parameter.
        viewModelOf(::ProgramEditorViewModel)
    }
