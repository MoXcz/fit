// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView

// The keep-screen-on part of Hush's DimClockWindowEffect: the view's flag, so it goes with the screen.
@Composable
actual fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        val before = view.keepScreenOn
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = before }
    }
}
