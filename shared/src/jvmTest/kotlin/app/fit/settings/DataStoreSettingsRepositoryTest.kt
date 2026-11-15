// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.fit.core.WeightUnit
import app.fit.data.prefs.createSettingsDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DataStoreSettingsRepositoryTest {
    private val file = Files.createTempDirectory("settings").resolve("settings.preferences_pb")
    private val dataStore = createSettingsDataStore { file.toString() }
    private val repo = DataStoreSettingsRepository(dataStore)

    @Test
    fun `defaults come from Settings`() =
        runTest {
            assertEquals(Settings(), repo.settings.first())
        }

    @Test
    fun `stores the weight unit`() =
        runTest {
            repo.setWeightUnit(WeightUnit.Lb)
            assertEquals(WeightUnit.Lb, repo.settings.first().weightUnit)
        }

    @Test
    fun `an unknown weight unit falls back to the default`() =
        runTest {
            dataStore.edit { it[stringPreferencesKey("weight_unit")] = "Stone" }
            assertEquals(Settings().weightUnit, repo.settings.first().weightUnit)
        }

    @Test
    fun `stores a bar weight per unit`() =
        runTest {
            repo.setBarWeight(WeightUnit.Kg, 15.0)
            repo.setBarWeight(WeightUnit.Lb, 35.0)
            val settings = repo.settings.first()
            assertEquals(15.0, settings.bar(WeightUnit.Kg))
            assertEquals(35.0, settings.bar(WeightUnit.Lb))
        }

    @Test
    fun `the alarm and keep screen on start on, as in the old app, and are stored`() =
        runTest {
            assertTrue(repo.settings.first().alarm)
            assertTrue(repo.settings.first().keepScreenOn)
            repo.setAlarm(false)
            repo.setKeepScreenOn(false)
            val settings = repo.settings.first()
            assertFalse(settings.alarm)
            assertFalse(settings.keepScreenOn)
        }
}
