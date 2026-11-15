// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.di

import app.fit.backup.Backups
import app.fit.backup.LiveSessions
import app.fit.backup.RoomBackups
import app.fit.backup.ui.DataViewModel
import app.fit.content.BuiltInContentSync
import app.fit.content.ContentImporter
import app.fit.content.ContentStateStore
import app.fit.content.ImportInbox
import app.fit.content.RoomContentImporter
import app.fit.content.StoredContentImages
import app.fit.content.ui.ImportViewModel
import app.fit.data.prefs.DataStoreContentStateStore
import app.fit.guided.ContentImages
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.bind
import org.koin.dsl.module

// BuiltInContent (the bundled files and images), Archives and ImageProbe come from the platform module.
val contentModule =
    module {
        singleOf(::DataStoreContentStateStore) bind ContentStateStore::class
        singleOf(::BuiltInContentSync)
        singleOf(::StoredContentImages) bind ContentImages::class
        singleOf(::RoomContentImporter) bind ContentImporter::class
        singleOf(::ImportInbox)
        viewModelOf(::ImportViewModel)
    }

// Archives and Documents come from the platform module.
val backupModule =
    module {
        singleOf(::LiveSessions)
        singleOf(::RoomBackups) bind Backups::class
        viewModelOf(::DataViewModel)
    }
