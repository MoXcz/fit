// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.settings.DataStoreSettingsRepository
import app.fit.settings.SettingsRepository
import app.fit.settings.ui.SettingsViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

// The DataStore<Preferences> instance comes from the platform module (it owns the file path).
val settingsModule =
    module {
        singleOf(::DataStoreSettingsRepository) bind SettingsRepository::class
        viewModelOf(::SettingsViewModel)
    }
