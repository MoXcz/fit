// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings.ui

import app.fit.core.WeightUnit
import app.fit.settings.FakeSettingsRepository
import app.fit.settings.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val repository = FakeSettingsRepository()

    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the unit setting is stored and shown`() =
        runTest {
            val vm = SettingsViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertEquals(WeightUnit.Kg, vm.uiState.value.settings.weightUnit)
            vm.onAction(SettingsAction.SetUnit(WeightUnit.Lb))
            assertEquals(WeightUnit.Lb, vm.uiState.value.settings.weightUnit)
        }

    @Test
    fun `bar weights default to 20 kg and 45 lb and step by 2_5 kg or 5 lb within their range`() =
        runTest {
            val vm = SettingsViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertEquals(Settings(barKg = 20.0, barLb = 45.0), vm.uiState.value.settings)
            vm.onAction(SettingsAction.StepBar(WeightUnit.Kg, up = false))
            vm.onAction(SettingsAction.StepBar(WeightUnit.Lb, up = true))
            assertEquals(17.5, vm.uiState.value.settings.barKg)
            assertEquals(50.0, vm.uiState.value.settings.barLb)
            repository.setBarWeight(WeightUnit.Kg, 0.0)
            vm.onAction(SettingsAction.StepBar(WeightUnit.Kg, up = false))
            assertEquals(0.0, vm.uiState.value.settings.barKg)
        }

    @Test
    fun `auto-advance is on by default, as in the old app, and switches off`() =
        runTest {
            val repository = FakeSettingsRepository()
            val vm = SettingsViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertTrue(vm.uiState.value.settings.autoAdvance)
            vm.onAction(SettingsAction.SetAutoAdvance(false))
            assertFalse(repository.settings.value.autoAdvance)
            assertFalse(vm.uiState.value.settings.autoAdvance)
        }

    @Test
    fun `the alarm and keep screen on are on by default and switch off`() =
        runTest {
            val vm = SettingsViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.uiState.collect {} }
            assertTrue(vm.uiState.value.settings.alarm)
            assertTrue(vm.uiState.value.settings.keepScreenOn)
            vm.onAction(SettingsAction.SetAlarm(false))
            vm.onAction(SettingsAction.SetKeepScreenOn(false))
            assertFalse(vm.uiState.value.settings.alarm)
            assertFalse(vm.uiState.value.settings.keepScreenOn)
        }
}
