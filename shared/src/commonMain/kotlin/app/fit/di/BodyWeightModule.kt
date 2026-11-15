// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.bodyweight.BodyWeightRepository
import app.fit.bodyweight.RoomBodyWeightRepository
import app.fit.bodyweight.ui.BodyWeightViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

val bodyWeightModule =
    module {
        singleOf(::RoomBodyWeightRepository) bind BodyWeightRepository::class
        viewModelOf(::BodyWeightViewModel)
    }
