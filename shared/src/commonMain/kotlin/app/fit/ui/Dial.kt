// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fit.ui.theme.LocalAppFonts
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// The dial's sizes, shared by the workout's focus view and the guided player, so both groups of
// buttons sit in the same place.
internal val DIAL_RING = 280.dp
internal val DIAL_BUTTON_HEIGHT = 88.dp
internal val DIAL_SIDE = 88.dp
internal val DIAL_NEXT = 64.dp

// Large numerals with tabular figures.
@Composable
internal fun clockStyle(size: TextUnit): TextStyle =
    TextStyle(
        fontFamily = LocalAppFonts.current.clock,
        fontSize = size,
        lineHeight = size * 1.1f,
        letterSpacing = (-1.5).sp,
        fontFeatureSettings = "tnum",
        color = MaterialTheme.colorScheme.onSurface,
    )

// Whole seconds, rounded up, so 0:00 shows only at the end.
internal fun countdown(left: Duration): String = formatClock(left + 999.milliseconds)
