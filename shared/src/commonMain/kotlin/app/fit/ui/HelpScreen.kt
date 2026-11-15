// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.fit.content.Markdown
import app.fit.content.MdBlock
import app.fit.resources.Res
import app.fit.resources.more_help
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.stringResource

const val USER_GUIDE_FILE = "files/user-guide.md"

// The user guide that ships with the app, so it reads without a network.
@Composable
fun HelpScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val guide by produceState(emptyList<MdBlock>()) {
        value = Markdown.parse(Res.readBytes(USER_GUIDE_FILE).decodeToString()).blocks
    }
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.more_help)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 16.dp),
        ) {
            if (guide.isNotEmpty()) {
                MarkdownText(
                    guide,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(segmentShape(0, 1))
                            .background(MaterialTheme.colorScheme.surfaceBright)
                            .padding(16.dp),
                )
            }
        }
    }
}
