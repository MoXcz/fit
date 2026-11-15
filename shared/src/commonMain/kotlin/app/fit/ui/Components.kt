// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2025-2026 Nishant Mishra (Tomato, https://github.com/nsh07/Tomato): adapted from the settings screens.
package app.fit.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.fit.resources.Res
import app.fit.resources.decrease
import app.fit.resources.ic_add
import app.fit.resources.ic_more_vert
import app.fit.resources.ic_remove
import app.fit.resources.increase
import app.fit.resources.more_options
import app.fit.resources.undo
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 16.dp, top = 20.dp, end = 16.dp, bottom = 8.dp),
    )
}

// −/+ around a value that rolls up or down with the step.
@Composable
internal fun Stepper(
    value: String,
    canDecrease: Boolean,
    canIncrease: Boolean,
    onStep: (up: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motionScheme = MaterialTheme.motionScheme
    var lastUp by remember { mutableStateOf(true) }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(
            onClick = {
                lastUp = false
                onStep(false)
            },
            enabled = canDecrease,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(painterResource(Res.drawable.ic_remove), contentDescription = stringResource(Res.string.decrease))
        }
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                val spec = motionScheme.defaultSpatialSpec<IntOffset>()
                val sign = if (lastUp) 1 else -1
                (slideInVertically(spec) { sign * it } + fadeIn(motionScheme.defaultEffectsSpec())) togetherWith
                    (slideOutVertically(spec) { -sign * it } + fadeOut(motionScheme.fastEffectsSpec()))
            },
            contentAlignment = Alignment.Center,
            modifier = Modifier.widthIn(min = 80.dp),
        ) {
            Text(it, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
        FilledTonalIconButton(
            onClick = {
                lastUp = true
                onStep(true)
            },
            enabled = canIncrease,
            shapes = IconButtonDefaults.shapes(),
        ) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = stringResource(Res.string.increase))
        }
    }
}

// How many wipes and restores this process has seen (DataResets), for offers that hold old rows.
val LocalDataEpoch = staticCompositionLocalOf { 0 }

// Bottom space for a list under the Undo bar: its last row scrolls clear of the bar at its tallest
// (two lines, which grow with the text size), margins included.
internal val UndoBarSpace: Dp
    @Composable get() = with(LocalDensity.current) { 56.dp + 40.sp.toDp() }

const val UNDO_BAR_TAG = "undo-bar"

// Shows [message] with Undo until it's used, swiped away, dismissed or times out; null hides it. The
// offer is state the ViewModel holds, so it survives recomposition and the screen only acknowledges
// it. An offer from before a wipe or a restore is dismissed unseen: its rows and their ids belong to
// data that's gone.
@Composable
internal fun UndoSnackbar(
    message: String?,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    // False for a plain notice (e.g. Undo found nothing to take back).
    canUndo: Boolean = true,
) {
    val host = remember { SnackbarHostState() }
    val undo = stringResource(Res.string.undo)
    val epoch = LocalDataEpoch.current
    // Saved with the screen, so it's still known when the screen comes back from under another.
    var offeredIn by rememberSaveable { mutableStateOf<Int?>(null) }
    LaunchedEffect(message, epoch) {
        if (message == null) {
            offeredIn = null
            host.currentSnackbarData?.dismiss()
            return@LaunchedEffect
        }
        if (offeredIn == null) offeredIn = epoch
        if (offeredIn != epoch) {
            offeredIn = null
            host.currentSnackbarData?.dismiss()
            onDismiss()
            return@LaunchedEffect
        }
        val result =
            if (canUndo) {
                host.showSnackbar(message, actionLabel = undo, duration = SnackbarDuration.Long)
            } else {
                host.showSnackbar(message, duration = SnackbarDuration.Short)
            }
        when (result) {
            SnackbarResult.ActionPerformed -> onUndo()
            SnackbarResult.Dismissed -> onDismiss()
        }
    }
    SnackbarHost(host, modifier = modifier) { SwipeAwayBar(it) }
}

// The bar, swiped away either way: the same as letting it time out, so the action stands.
@Composable
private fun SwipeAwayBar(data: SnackbarData) {
    // Per bar and not saveable: the next one must not start swiped away.
    val swipe = remember(data) { SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold = { it / 2 }) }
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {},
        onDismiss = { data.dismiss() },
        // The margin is outside the swipe area, so only the bar itself takes touches.
        modifier = Modifier.padding(12.dp).testTag(UNDO_BAR_TAG),
    ) {
        Snackbar(
            action =
                data.visuals.actionLabel?.let { label ->
                    {
                        TextButton(
                            onClick = { data.performAction() },
                            colors = ButtonDefaults.textButtonColors(contentColor = SnackbarDefaults.actionColor),
                        ) { Text(label) }
                    }
                },
        ) { Text(data.visuals.message, maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

// A labelled row of connected toggle buttons, one per option.
@Composable
internal fun <T> ChoiceRow(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: (T) -> String,
    onSelect: (T) -> Unit,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    ListItem(
        modifier = modifier.clip(segmentShape(index, count)),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = {
            Row(horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                options.forEachIndexed { i, option ->
                    ToggleButton(
                        checked = option == selected,
                        onCheckedChange = { onSelect(option) },
                        shapes =
                            when (i) {
                                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                            },
                    ) { Text(optionLabel(option)) }
                }
            }
        },
    )
}

// Button [index] of [count] in a connected group: round outer ends, small inner corners.
internal fun connectedShape(
    index: Int,
    count: Int,
): Shape {
    val outer = CornerSize(50)
    val inner = CornerSize(8.dp)
    val first = index == 0
    val last = index == count - 1
    return RoundedCornerShape(
        topStart = if (first) outer else inner,
        bottomStart = if (first) outer else inner,
        topEnd = if (last) outer else inner,
        bottomEnd = if (last) outer else inner,
    )
}

// One choice of several as a full-width row of connected toggle buttons.
@Composable
internal fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { if (option != selected) onSelect(option) },
                shapes =
                    when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                // Four options on a small phone or with large text: the label shrinks rather than wrap mid-word.
                Text(
                    optionLabel(option),
                    maxLines = 1,
                    softWrap = false,
                    autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = LocalTextStyle.current.fontSize),
                )
            }
        }
    }
}

// The top bar's one action, when it's a single icon.
@Composable
internal fun TopBarAction(
    icon: DrawableResource,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        modifier = modifier.padding(end = 8.dp),
    ) {
        Icon(painterResource(icon), contentDescription = description)
    }
}

data class MenuItem(
    val label: String,
    val onClick: () -> Unit,
    val color: Color = Color.Unspecified,
)

// The top bar's one action, when there are several things to offer: ⋮ and a menu.
@Composable
internal fun OverflowMenu(
    items: List<MenuItem>,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.padding(end = 8.dp)) {
        FilledTonalIconButton(onClick = { open = true }, enabled = items.isNotEmpty(), shapes = IconButtonDefaults.shapes()) {
            Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            items.forEach { item ->
                DropdownMenuItem(
                    text = { Text(item.label, color = item.color) },
                    onClick = {
                        open = false
                        item.onClick()
                    },
                )
            }
        }
    }
}

// A small tonal pill: "Active", "Deload week".
@Composable
internal fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    tertiary: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = if (tertiary) colors.tertiaryContainer else colors.secondaryContainer,
        contentColor = if (tertiary) colors.onTertiaryContainer else colors.onSecondaryContainer,
        modifier = modifier,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}
