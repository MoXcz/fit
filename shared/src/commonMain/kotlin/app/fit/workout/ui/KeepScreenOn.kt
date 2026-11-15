// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import androidx.compose.runtime.Composable

// Keeps the screen on while in composition, and lets it sleep again when it leaves.
@Composable
expect fun KeepScreenOn()
