// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fit.core.WeightUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The user's settings. Single source of truth for everything in [Settings]. */
interface SettingsRepository {
    val settings: Flow<Settings>

    suspend fun setWeightUnit(unit: WeightUnit)

    /** The bar weight for [unit], used by the plate calculator. */
    suspend fun setBarWeight(
        unit: WeightUnit,
        weight: Double,
    )

    suspend fun setAlarm(on: Boolean)

    suspend fun setKeepScreenOn(on: Boolean)

    suspend fun setAutoAdvance(on: Boolean)

    /** Every setting at once (a restored backup). */
    suspend fun replace(settings: Settings)
}

class DataStoreSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) : SettingsRepository {
    override val settings: Flow<Settings> =
        dataStore.data.map { prefs ->
            Settings(
                weightUnit = prefs[Keys.weightUnit]?.let(::weightUnitOrNull) ?: defaults.weightUnit,
                barKg = prefs[Keys.barKg] ?: defaults.barKg,
                barLb = prefs[Keys.barLb] ?: defaults.barLb,
                alarm = prefs[Keys.alarm] ?: defaults.alarm,
                keepScreenOn = prefs[Keys.keepScreenOn] ?: defaults.keepScreenOn,
                autoAdvance = prefs[Keys.autoAdvance] ?: defaults.autoAdvance,
            )
        }

    override suspend fun setWeightUnit(unit: WeightUnit) {
        dataStore.edit { prefs -> prefs[Keys.weightUnit] = unit.name }
    }

    override suspend fun setBarWeight(
        unit: WeightUnit,
        weight: Double,
    ) {
        dataStore.edit { prefs -> prefs[if (unit == WeightUnit.Kg) Keys.barKg else Keys.barLb] = weight }
    }

    override suspend fun setAlarm(on: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.alarm] = on }
    }

    override suspend fun setKeepScreenOn(on: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.keepScreenOn] = on }
    }

    override suspend fun setAutoAdvance(on: Boolean) {
        dataStore.edit { prefs -> prefs[Keys.autoAdvance] = on }
    }

    override suspend fun replace(settings: Settings) {
        dataStore.edit { prefs ->
            prefs[Keys.weightUnit] = settings.weightUnit.name
            prefs[Keys.barKg] = settings.barKg
            prefs[Keys.barLb] = settings.barLb
            prefs[Keys.alarm] = settings.alarm
            prefs[Keys.keepScreenOn] = settings.keepScreenOn
            prefs[Keys.autoAdvance] = settings.autoAdvance
        }
    }

    private object Keys {
        val weightUnit = stringPreferencesKey("weight_unit")
        val barKg = doublePreferencesKey("bar_kg")
        val barLb = doublePreferencesKey("bar_lb")
        val alarm = booleanPreferencesKey("alarm")
        val keepScreenOn = booleanPreferencesKey("keep_screen_on")
        val autoAdvance = booleanPreferencesKey("auto_advance")
    }

    private companion object {
        val defaults = Settings()
    }
}

// An unknown name (written by a newer version) falls back to the default instead of crashing.
private fun weightUnitOrNull(name: String): WeightUnit? = WeightUnit.entries.firstOrNull { it.name == name }
