// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.fit.resources.Res
import app.fit.resources.ic_delete
import org.jetbrains.compose.resources.painterResource

// Swipe a row to the start to remove it (the screen offers Undo). [label] names the same action for
// accessibility services, which can't swipe.
@Composable
internal fun SwipeToRemove(
    label: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    content: @Composable () -> Unit,
) {
    // Not saveable: a row that Undo brings back must not come back swiped away.
    val state = remember { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it / 2 }) }
    SwipeToDismissBox(
        state = state,
        backgroundContent = {
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 20.dp),
            ) {
                Icon(painterResource(Res.drawable.ic_delete), contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
        enableDismissFromStartToEnd = false,
        onDismiss = { onRemove() },
        modifier = modifier.semantics { customActions = listOf(CustomAccessibilityAction(label) { true.also { onRemove() } }) },
    ) { content() }
}
