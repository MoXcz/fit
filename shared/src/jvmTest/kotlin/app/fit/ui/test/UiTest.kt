// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.test

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SkikoComposeUiTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import app.fit.ui.theme.FitTheme
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import java.io.File
import kotlin.time.Duration

// A phone in portrait: 411 × 891 dp at 2.625 (1080 × 2339 px), as the user's phone.
private const val PHONE_SCALE = 2.625f

// ViewModels outlive a test unless cleared: their once-a-second tickers would carry on in real time
// and later run on the next test's Main dispatcher, failing that test.
private val openViewModels = mutableListOf<ViewModel>()
private var store: ViewModelStore? = null

// For a ViewModel a test builds itself.
fun <T : ViewModel> T.clearedAfterTest(): T = also { openViewModels += it }

// Runs [block] headless on a phone-sized window, then clears every ViewModel it used. Smaller
// phones and larger text are a size in dp and a font scale.
@OptIn(ExperimentalTestApi::class)
fun runPhoneUiTest(
    widthDp: Int = 411,
    heightDp: Int = 891,
    fontScale: Float = 1f,
    block: SkikoComposeUiTest.() -> Unit,
) {
    try {
        runSkikoComposeUiTest(
            size = Size(widthDp * PHONE_SCALE, heightDp * PHONE_SCALE),
            density = Density(PHONE_SCALE, fontScale),
        ) { block() }
    } finally {
        openViewModels.forEach { it.viewModelScope.cancel() }
        openViewModels.clear()
        store?.clear()
        store = null
    }
}

// Android Back, sent the way the platform sends it: as a navigation event, which sheets, dialogs and
// BackGuard all listen to.
private val back = DirectNavigationEventInput()

// Listens for Back as a phone does, and keeps the screens' ViewModels in a store the test clears.
@Composable
fun PhoneHost(content: @Composable () -> Unit) {
    val dispatcher = LocalNavigationEventDispatcherOwner.current?.navigationEventDispatcher
    DisposableEffect(dispatcher) {
        dispatcher?.addInput(back)
        onDispose { dispatcher?.removeInput(back) }
    }
    val owner =
        remember {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore().also { store = it }
            }
        }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.setScreen(content: @Composable () -> Unit) =
    setContent {
        PhoneHost {
            FitTheme(darkTheme = true) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) { content() }
            }
        }
    }

// Saves the whole window, sheets and dialogs included, as shared/build/ui-screenshots/<name>.png (under
// build/, so never committed).
@OptIn(ExperimentalTestApi::class)
fun SkikoComposeUiTest.screenshot(name: String) {
    // A click leaves the test's mouse where it was, and a row moving under it (a sheet sliding up)
    // shows a hover a phone never shows; move it out of the window first.
    onAllNodes(isRoot()).onFirst().performMouseInput { moveTo(Offset(-10f, -10f)) }
    waitForIdle()
    val bitmap = captureToImage().asSkiaBitmap()
    val png = Image.makeFromBitmap(bitmap).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encoding failed")
    val file = File(System.getProperty("ui.screenshots.dir") ?: "build/ui-screenshots", "$name.png")
    file.parentFile.mkdirs()
    file.writeBytes(png.bytes)
}

// Moves the test clock, and every timer and delay on it, on; then lets the screen catch up.
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
fun ComposeUiTest.advance(
    scope: TestScope,
    by: Duration,
) {
    scope.testScheduler.advanceTimeBy(by)
    scope.testScheduler.runCurrent()
    waitForIdle()
}

// A change from outside the screen (another screen, the system), run to completion now.
@OptIn(ExperimentalCoroutinesApi::class)
fun TestScope.change(block: suspend () -> Unit) {
    launch(UnconfinedTestDispatcher(testScheduler)) { block() }
    testScheduler.runCurrent()
}

@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pressBack() {
    runOnUiThread { back.backCompleted() }
    waitForIdle()
}
