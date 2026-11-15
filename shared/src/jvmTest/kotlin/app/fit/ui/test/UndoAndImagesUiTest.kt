// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.dp
import app.fit.ui.LocalDataEpoch
import app.fit.ui.UNDO_BAR_TAG
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.decodeImageOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class UndoAndImagesUiTest {
    @Test
    fun `an Undo offer from before a wipe or restore is dismissed unseen, and a later one shows`() =
        runPhoneUiTest {
            var epoch by mutableIntStateOf(0)
            var message by mutableStateOf<String?>("Deleted Legs")
            var dismissed = 0
            setScreen {
                CompositionLocalProvider(LocalDataEpoch provides epoch) {
                    UndoSnackbar(message = message, onUndo = {}, onDismiss = { dismissed++ })
                }
            }
            waitForIdle()
            onNodeWithText("Deleted Legs").assertIsDisplayed()
            onNodeWithText("Undo").assertIsDisplayed()

            epoch = 1
            waitForIdle()
            onNodeWithText("Undo").assertDoesNotExist()
            assertEquals(1, dismissed)

            // The view model drops the offer when told; the next one belongs to the new data.
            message = null
            waitForIdle()
            message = "Deleted Arms"
            waitForIdle()
            onNodeWithText("Deleted Arms").assertIsDisplayed()
            onNodeWithText("Undo").assertIsDisplayed()
            assertEquals(1, dismissed)
        }

    @Test
    fun `swiping the Undo bar away, either way, dismisses it without undoing`() =
        runPhoneUiTest {
            var message by mutableStateOf<String?>("Deleted Legs")
            var undone = 0
            var dismissed = 0
            setScreen {
                UndoSnackbar(
                    message = message,
                    onUndo = { undone++ },
                    onDismiss = {
                        dismissed++
                        message = null
                    },
                )
            }
            waitForIdle()
            onNodeWithTag(UNDO_BAR_TAG).performTouchInput { swipeRight() }
            waitForIdle()
            onNodeWithText("Deleted Legs").assertDoesNotExist()
            assertEquals(0, undone)
            assertEquals(1, dismissed)

            message = "Deleted Arms"
            waitForIdle()
            onNodeWithText("Deleted Arms").assertIsDisplayed()
            onNodeWithTag(UNDO_BAR_TAG).performTouchInput { swipeLeft() }
            waitForIdle()
            onNodeWithText("Deleted Arms").assertDoesNotExist()
            assertEquals(0, undone)
            assertEquals(2, dismissed)
        }

    @Test
    fun `the Undo bar takes touches on itself only, and Undo still undoes`() =
        runPhoneUiTest {
            var message by mutableStateOf<String?>("Deleted Legs")
            var behind = 0
            var undone = 0
            setScreen {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().clickable { behind++ })
                    UndoSnackbar(
                        message = message,
                        onUndo = {
                            undone++
                            message = null
                        },
                        onDismiss = { message = null },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
            waitForIdle()
            val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
            // Its margins all round, and the rest of the screen, belong to what's behind.
            onRoot().performTouchInput {
                click(Offset(bar.center.x, bar.top - 4.dp.toPx()))
                click(Offset(bar.center.x, bar.bottom + 4.dp.toPx()))
                click(Offset(bar.left - 4.dp.toPx(), bar.center.y))
                click(Offset(bar.right + 4.dp.toPx(), bar.center.y))
                click(Offset(bar.center.x, 100f))
            }
            waitForIdle()
            assertEquals(5, behind)
            // On the bar's text: nothing behind is tapped, and the bar stays.
            onRoot().performTouchInput { click(Offset(bar.left + 24.dp.toPx(), bar.center.y)) }
            waitForIdle()
            assertEquals(5, behind)
            onNodeWithText("Deleted Legs").assertIsDisplayed()
            onNodeWithText("Undo").performClick()
            waitForIdle()
            assertEquals(1, undone)
            onNodeWithText("Deleted Legs").assertDoesNotExist()
        }

    @Test
    fun `the space lists leave for the Undo bar holds its longest message at normal and at 1_3x text, cut to two lines`() {
        val long = "Deleted Upper body strength and conditioning · now Rest in Winter block, Summer block, Deload and Travel weeks"
        listOf(1f, 1.3f).forEach { scale ->
            runPhoneUiTest(360, 780, fontScale = scale) {
                setScreen {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(8.dp).height(UndoBarSpace).testTag("space"))
                        UndoSnackbar(message = long, onUndo = {}, onDismiss = {}, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                }
                waitForIdle()
                val bar = onNodeWithTag(UNDO_BAR_TAG).fetchSemanticsNode().boundsInRoot
                val space = onNodeWithTag("space").fetchSemanticsNode().boundsInRoot
                val margins = with(density) { 24.dp.toPx() }
                assertTrue(
                    bar.height + margins <= space.height,
                    "at $scale× the bar is ${bar.height} px + margins, the space ${space.height} px",
                )
                onNodeWithText("Undo").assertIsDisplayed()
                if (scale > 1f) screenshot("undo-bar-long-message-large-text")
            }
        }
    }

    @Test
    fun `a second offer replaces the first, and swiping it away dismisses only that one`() =
        runPhoneUiTest {
            var message by mutableStateOf<String?>("Removed 1 exercise")
            var undone = 0
            var dismissed = 0
            setScreen { UndoSnackbar(message = message, onUndo = { undone++ }, onDismiss = { dismissed++ }) }
            waitForIdle()
            message = "Removed 2 exercises"
            waitForIdle()
            onNodeWithText("Removed 1 exercise").assertDoesNotExist()
            assertEquals(0, dismissed)
            onNodeWithTag(UNDO_BAR_TAG).performTouchInput { swipeLeft() }
            waitForIdle()
            assertEquals(0, undone)
            assertEquals(1, dismissed)
        }

    @Test
    fun `bytes that aren't an image show nothing, however the platform's decoder fails`() {
        assertNull(decodeImageOrNull("not an image".encodeToByteArray()))
        // Android's decoder gives null for bytes it can't read, which Compose turns into this.
        assertNull(decodeImageOrNull(ByteArray(8)) { throw NullPointerException("bitmap") })
        assertNull(decodeImageOrNull(ByteArray(8)) { throw IllegalStateException("decoder") })
    }
}
