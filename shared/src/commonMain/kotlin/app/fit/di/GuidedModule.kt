// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.data.prefs.DataStoreGuidedStateStore
import app.fit.guided.GuidedController
import app.fit.guided.GuidedRepository
import app.fit.guided.GuidedStateStore
import app.fit.guided.PinnedShortcutSync
import app.fit.guided.RoomGuidedRepository
import app.fit.guided.ui.PlayerViewModel
import app.fit.recovery.ui.RecoverViewModel
import app.fit.recovery.ui.SessionViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

// GuidedAlerts and LauncherShortcuts come from the platform module; ContentImages from contentModule.
val guidedModule =
    module {
        singleOf(::RoomGuidedRepository) bind GuidedRepository::class
        singleOf(::DataStoreGuidedStateStore) bind GuidedStateStore::class
        singleOf(::GuidedController)
        singleOf(::PinnedShortcutSync)
        viewModelOf(::PlayerViewModel)
        viewModelOf(::RecoverViewModel)
        // The plan id is a parameter.
        viewModelOf(::SessionViewModel)
    }
