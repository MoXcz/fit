// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import app.fit.resources.Res
import app.fit.resources.today_title
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SmokeUiTest {
    @Test
    fun `compose resources load headless`() =
        runPhoneUiTest {
            setScreen { Text(stringResource(Res.string.today_title)) }
            onNodeWithText("Today").assertIsDisplayed()
            screenshot("smoke")
        }
}
