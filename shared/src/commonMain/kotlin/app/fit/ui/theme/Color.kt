// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// "Sage": one muted sage-green accent on near-neutral greys. Every role below is generated from the
// seed with Material Color Utilities (spec 2021, standard contrast, @material/material-color-utilities
// 0.3.0) and these tonal palettes, so a retune means regenerating this file from new numbers, not
// hand-editing single colours:
//   seed #617A5F (HCT hue 148.0)
//   primary   hue 148, chroma 24   (primary action, progress, selected tab)
//   secondary hue 148, chroma 8
//   tertiary  hue 60,  chroma 16   (a muted clay)
//   neutral   hue 148, chroma 3    neutral variant chroma 6
// Checked contrast (WCAG), light / dark: primary on surface 6.2 / 10.9, onPrimary on primary
// 6.5 / 7.7, onPrimaryContainer on primaryContainer 7.2 / 7.2, onSurfaceVariant on surface 8.9 / 10.9,
// tertiary on surface 6.1 / 10.9, outline on surface 4.3 / 5.8 (used only for large text and borders).

internal val LightColors =
    lightColorScheme(
        primary = Color(0xFF496548),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCAEBC6),
        onPrimaryContainer = Color(0xFF324D32),
        secondary = Color(0xFF596057),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFDEE5D9),
        onSecondaryContainer = Color(0xFF424940),
        tertiary = Color(0xFF745943),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFFFDCC1),
        onTertiaryContainer = Color(0xFF5A422E),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF93000A),
        background = Color(0xFFFAF9F5),
        onBackground = Color(0xFF1A1C19),
        surface = Color(0xFFFAF9F5),
        onSurface = Color(0xFF1A1C19),
        surfaceVariant = Color(0xFFE0E4DB),
        onSurfaceVariant = Color(0xFF434842),
        outline = Color(0xFF747871),
        outlineVariant = Color(0xFFC4C8BF),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFF2F312E),
        inverseOnSurface = Color(0xFFF1F1EC),
        inversePrimary = Color(0xFFAFCFAB),
        surfaceDim = Color(0xFFDADAD6),
        surfaceBright = Color(0xFFFAF9F5),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF4F4EF),
        surfaceContainer = Color(0xFFEEEEE9),
        surfaceContainerHigh = Color(0xFFE9E8E4),
        surfaceContainerHighest = Color(0xFFE3E3DE),
    )

internal val DarkColors =
    darkColorScheme(
        primary = Color(0xFFAFCFAB),
        onPrimary = Color(0xFF1B361D),
        primaryContainer = Color(0xFF324D32),
        onPrimaryContainer = Color(0xFFCAEBC6),
        secondary = Color(0xFFC2C9BD),
        onSecondary = Color(0xFF2C322A),
        secondaryContainer = Color(0xFF424940),
        onSecondaryContainer = Color(0xFFDEE5D9),
        tertiary = Color(0xFFE3C0A5),
        onTertiary = Color(0xFF412C19),
        tertiaryContainer = Color(0xFF5A422E),
        onTertiaryContainer = Color(0xFFFFDCC1),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF121411),
        onBackground = Color(0xFFE3E3DE),
        surface = Color(0xFF121411),
        onSurface = Color(0xFFE3E3DE),
        surfaceVariant = Color(0xFF434842),
        onSurfaceVariant = Color(0xFFC4C8BF),
        outline = Color(0xFF8E928A),
        outlineVariant = Color(0xFF434842),
        scrim = Color(0xFF000000),
        inverseSurface = Color(0xFFE3E3DE),
        inverseOnSurface = Color(0xFF2F312E),
        inversePrimary = Color(0xFF496548),
        surfaceDim = Color(0xFF121411),
        surfaceBright = Color(0xFF383A37),
        surfaceContainerLowest = Color(0xFF0D0F0C),
        surfaceContainerLow = Color(0xFF1A1C19),
        surfaceContainer = Color(0xFF1E201D),
        surfaceContainerHigh = Color(0xFF292A28),
        surfaceContainerHighest = Color(0xFF343532),
    )
