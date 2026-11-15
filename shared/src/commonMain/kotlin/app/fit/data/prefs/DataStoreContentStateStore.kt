// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fit.content.ContentStateStore
import kotlinx.coroutines.flow.first

// Shares the settings file; the key isn't a setting, so Settings() doesn't show it.
class DataStoreContentStateStore(
    private val dataStore: DataStore<Preferences>,
) : ContentStateStore {
    override suspend fun loadedFingerprint(): String? = dataStore.data.first()[key]

    override suspend fun setLoadedFingerprint(fingerprint: String?) {
        dataStore.edit { if (fingerprint == null) it.remove(key) else it[key] = fingerprint }
    }

    private companion object {
        val key = stringPreferencesKey("builtin_content_fingerprint")
    }
}
