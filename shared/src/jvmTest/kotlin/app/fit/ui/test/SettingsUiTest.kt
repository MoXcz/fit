// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.fit.core.WeightUnit
import app.fit.settings.FakeSettingsRepository
import app.fit.settings.ui.SettingsRoute
import app.fit.settings.ui.SettingsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
class SettingsUiTest {
    // Not a test dispatcher: runTest would adopt its scheduler, and the ViewModels' once-a-second
    // tickers would keep it from ever going idle. Timers run on the fixture's TestClock either way.
    @BeforeTest
    fun setUp() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the unit, the bar weight, the alarm and keep screen on change from the screen`() =
        runPhoneUiTest {
            val repository = FakeSettingsRepository()
            setScreen { SettingsRoute(onBack = {}, viewModel = SettingsViewModel(repository).clearedAfterTest()) }
            onNodeWithText("Alarm").assertIsOn()
            onNodeWithText("Alarm").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            onNodeWithText("Keep screen on").assertIsOn()
            onNodeWithText("Auto-advance").assertIsOn()
            onNodeWithText("20 kg").assertExists()
            screenshot("settings")
            onNodeWithText("Auto-advance").performClick()
            waitForIdle()
            onNodeWithText("Auto-advance").assertIsOff()
            assertFalse(repository.settings.value.autoAdvance)
            screenshot("settings-auto-advance-off")
            onNodeWithText("Alarm").performClick()
            onNodeWithText("Keep screen on").performClick()
            onNodeWithText("lb").performClick()
            // The first − is the kg bar's.
            onAllNodesWithContentDescription("Decrease")[0].performClick()
            waitForIdle()
            onNodeWithText("Alarm").assertIsOff()
            onNodeWithText("Keep screen on").assertIsOff()
            onNodeWithText("17.5 kg").assertExists()
            val settings = repository.settings.value
            assertFalse(settings.alarm)
            assertFalse(settings.keepScreenOn)
            assertEquals(WeightUnit.Lb, settings.weightUnit)
            assertEquals(17.5, settings.barKg)
            screenshot("settings-changed")
        }
}
