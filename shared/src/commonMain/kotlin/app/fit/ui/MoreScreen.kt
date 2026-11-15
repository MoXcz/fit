// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.fit.resources.Res
import app.fit.resources.bodyweight_title
import app.fit.resources.history_title
import app.fit.resources.ic_keyboard_arrow_right
import app.fit.resources.more_about
import app.fit.resources.more_data
import app.fit.resources.more_help
import app.fit.resources.more_import
import app.fit.resources.more_title
import app.fit.resources.settings_title
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun MoreScreen(
    onOpenHistory: () -> Unit,
    onOpenBodyWeight: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenData: () -> Unit,
    onOpenHelp: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(title = { ScreenTitle(stringResource(Res.string.more_title)) })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val rows =
                listOf(
                    Res.string.history_title to onOpenHistory,
                    Res.string.bodyweight_title to onOpenBodyWeight,
                    Res.string.settings_title to onOpenSettings,
                    Res.string.more_import to onOpenImport,
                    Res.string.more_data to onOpenData,
                    Res.string.more_help to onOpenHelp,
                    Res.string.more_about to onOpenAbout,
                )
            rows.forEachIndexed { index, (label, onClick) ->
                SegmentedListItem(
                    onClick = onClick,
                    shapes = segmentedShapes(index, rows.size),
                    colors = segmentedColors,
                    trailingContent = { Icon(painterResource(Res.drawable.ic_keyboard_arrow_right), contentDescription = null) },
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@Preview
@Composable
private fun MoreScreenPreview() {
    FitTheme {
        MoreScreen(
            onOpenHistory = {},
            onOpenBodyWeight = {},
            onOpenSettings = {},
            onOpenImport = {},
            onOpenData = {},
            onOpenHelp = {},
            onOpenAbout = {},
        )
    }
}
