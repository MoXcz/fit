// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable

/** A modal sheet that is open in full or hidden, never half open. */
@Composable
fun rememberFullSheetState(): SheetState =
    rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))
