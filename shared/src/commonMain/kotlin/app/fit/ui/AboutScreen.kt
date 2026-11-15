// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.fit.content.Source
import app.fit.recovery.ui.SourceRow
import app.fit.resources.Res
import app.fit.resources.about_credits
import app.fit.resources.about_licence
import app.fit.resources.about_source
import app.fit.resources.about_version
import app.fit.resources.app_name
import app.fit.resources.more_about
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.stringResource

private const val SOURCE_URL = "https://github.com/MoXcz/fit"
private const val HUSH_URL = "https://github.com/MoXcz/hush"
private const val TOMATO_URL = "https://github.com/nsh07/Tomato"

// The version, the licence and where the app comes from.
@Composable
fun AboutScreen(
    version: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.more_about)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 10.dp)
                        .fillMaxWidth()
                        .clip(segmentShape(0, 1))
                        .background(colors.surfaceBright)
                        .padding(16.dp),
            ) {
                Text(stringResource(Res.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(Res.string.about_version, version), color = colors.onSurfaceVariant)
                Text(stringResource(Res.string.about_licence))
                Text(stringResource(Res.string.about_credits))
            }
            val links =
                listOf(
                    Source(stringResource(Res.string.about_source), SOURCE_URL),
                    Source("Hush", HUSH_URL),
                    Source("Tomato", TOMATO_URL),
                )
            links.forEachIndexed { index, link -> SourceRow(link, index, links.size) }
        }
    }
}
