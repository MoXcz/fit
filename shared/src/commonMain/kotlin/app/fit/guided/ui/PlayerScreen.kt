// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.BlockId
import app.fit.guided.GuidedItem
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPhase
import app.fit.guided.Interstitial
import app.fit.resources.Res
import app.fit.resources.done
import app.fit.resources.ic_check
import app.fit.resources.ic_keyboard_arrow_left
import app.fit.resources.ic_pause_large
import app.fit.resources.ic_play_large
import app.fit.resources.ic_skip_next_large
import app.fit.resources.item_reps
import app.fit.resources.player_completed
import app.fit.resources.player_end
import app.fit.resources.player_get_ready
import app.fit.resources.player_last
import app.fit.resources.player_next
import app.fit.resources.player_next_step
import app.fit.resources.player_none
import app.fit.resources.player_previous_step
import app.fit.resources.player_replaced
import app.fit.resources.player_round
import app.fit.resources.player_side
import app.fit.resources.player_skip_warmup
import app.fit.resources.player_steps_done
import app.fit.resources.player_stopped
import app.fit.resources.player_switch_sides
import app.fit.resources.sets_reps
import app.fit.resources.workout_next
import app.fit.resources.workout_of
import app.fit.resources.workout_pause
import app.fit.resources.workout_paused
import app.fit.resources.workout_position
import app.fit.resources.workout_resume
import app.fit.ui.BackButton
import app.fit.ui.DIAL_BUTTON_HEIGHT
import app.fit.ui.DIAL_NEXT
import app.fit.ui.DIAL_RING
import app.fit.ui.DIAL_SIDE
import app.fit.ui.MenuItem
import app.fit.ui.OverflowMenu
import app.fit.ui.ProgressRing
import app.fit.ui.RingSegment
import app.fit.ui.ScreenTopBar
import app.fit.ui.SegmentState
import app.fit.ui.SetRing
import app.fit.ui.UndoSnackbar
import app.fit.ui.clockStyle
import app.fit.ui.countdown
import app.fit.ui.decodeImageOrNull
import app.fit.ui.formatClock
import app.fit.ui.formatRange
import app.fit.workout.ui.KeepScreenOn
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration

const val PLAYER_RING_TAG = "player-ring"

@Composable
fun PlayerRoute(
    onBack: () -> Unit,
    // A finished warm-up goes on to the workout at this exercise.
    onContinue: (BlockId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var left by remember { mutableStateOf(false) }
    LaunchedEffect(state.continueTo, state.leave) {
        val next = state.continueTo
        if (next != null || state.leave) {
            left = true
            viewModel.onAction(PlayerAction.ContinueSeen)
            if (next != null) onContinue(next) else onBack()
        }
    }
    if (state.keepScreenOn && state.guided.active) KeepScreenOn()
    // A warm-up's end screen and the empty player would only flash by on the way to the workout.
    val leaving = left || (state.warmup && state.phase == GuidedPhase.Finished)
    PlayerScreen(
        state = if (leaving) state.copy(loading = true) else state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
    )
}

// The dial with steps instead of sets: the item's name and cue, the ring as its countdown (or its
// reps and Done), and the same group of buttons as the workout, which never moves.
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(colors.surfaceContainer)
                .then(modifier)
                .navigationBarsPadding(),
    ) {
        PlayerColumn(state, onAction, onBack)
        // A start ended another session with progress: a plain notice, since its log is already written.
        UndoSnackbar(
            message = state.guided.replaced?.let { stringResource(Res.string.player_replaced, it) },
            onUndo = {},
            onDismiss = { onAction(PlayerAction.ReplacedSeen) },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = DIAL_BUTTON_HEIGHT + 24.dp),
            canUndo = false,
        )
    }
}

@Composable
private fun PlayerColumn(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(state = state, onAction = onAction, onBack = onBack)
        when {
            state.loading -> {}

            state.snapshot == null -> {
                Text(
                    stringResource(Res.string.player_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }

            state.phase == GuidedPhase.Finished -> {
                Finished(state, Modifier.weight(1f))
                Controls(state, onAction)
            }

            else -> {
                Dial(state, Modifier.weight(1f))
                Controls(state, onAction)
            }
        }
    }
}

// Back, "3 of 12 · 2:14", and End in the overflow.
@Composable
private fun TopBar(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ScreenTopBar(
        title = {
            // The finished screen's ring holds the time.
            if (state.snapshot != null && state.count > 0 && state.phase != GuidedPhase.Finished) {
                val position = stringResource(Res.string.workout_position, minOf(state.index + 1, state.count), state.count)
                Text(
                    listOfNotNull(position, state.elapsed?.let(::formatClock)).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.outline,
                )
            }
        },
        navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        actions = {
            if (state.guided.active) {
                val label = stringResource(if (state.warmup) Res.string.player_skip_warmup else Res.string.player_end)
                OverflowMenu(listOf(MenuItem(label, { onAction(PlayerAction.End) })))
            }
        },
    )
}

@Composable
private fun Dial(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val item = state.item ?: return
    val transition = state.phase == GuidedPhase.Transition
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // What's happening: the session's title, or the pause before the item.
        Text(
            when (state.interstitial) {
                Interstitial.SwitchSides -> {
                    stringResource(Res.string.player_switch_sides)
                }

                Interstitial.Transition -> {
                    stringResource(Res.string.player_get_ready)
                }

                null -> {
                    state.guided.run
                        ?.title
                        .orEmpty()
                }
            },
            style = MaterialTheme.typography.labelLarge,
            color = colors.tertiary,
        )
        Text(
            item.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        whereLine(item)?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant) }
        if (item.cue.isNotBlank()) {
            Text(
                item.cue,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.outline,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        state.image?.let { bytes -> StepImage(bytes) }
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f, fill = false).padding(top = 12.dp)) {
            val size = minOf(DIAL_RING, maxWidth, maxHeight)
            val scale = size / DIAL_RING
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).testTag(PLAYER_RING_TAG)) {
                when {
                    transition -> Countdown(state, scale, wavy = true)
                    item.timed && !state.waitingAtEnd -> Countdown(state, scale, wavy = false)
                    else -> Steps(state, item, scale)
                }
            }
        }
        UpNext(state, Modifier.padding(top = 12.dp))
    }
}

// "Side 1 of 2 · Round 2 of 2".
@Composable
private fun whereLine(item: GuidedItem): String? {
    val side = item.side?.let { stringResource(Res.string.player_side, it) }
    val round = if (item.rounds > 1) stringResource(Res.string.player_round, item.round, item.rounds) else null
    return listOfNotNull(side, round).joinToString(" · ").ifEmpty { null }
}

@Composable
private fun StepImage(bytes: ByteArray) {
    val bitmap: ImageBitmap? =
        remember(bytes) { decodeImageOrNull(bytes) }
    bitmap ?: return
    Image(
        bitmap = bitmap,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.padding(top = 12.dp).size(120.dp).clip(RoundedCornerShape(20.dp)),
    )
}

// A pause in clay (Hush's wavy rest ring), a timed item in the accent.
@Composable
private fun Countdown(
    state: PlayerUiState,
    scale: Float,
    wavy: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    val length = state.length
    val color = if (wavy) colors.tertiary else colors.primary
    ProgressRing(
        progress = { if (length == Duration.ZERO) 0f else (state.left / length).toFloat() },
        wavy = wavy,
        color = color,
        trackColor = if (wavy) colors.tertiaryContainer else colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxSize(),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(countdown(state.left), style = clockStyle(64.sp * scale), color = if (state.paused) colors.outline else color)
        Text(
            if (state.paused) stringResource(Res.string.workout_paused) else stringResource(Res.string.workout_of, formatClock(length)),
            style = MaterialTheme.typography.titleMedium,
            color = colors.outline,
        )
    }
}

// The run's items around the ring, done ones in the accent; the reps in the middle, or a tick once
// a timed item is done.
@Composable
private fun Steps(
    state: PlayerUiState,
    item: GuidedItem,
    scale: Float,
) {
    val colors = MaterialTheme.colorScheme
    val snapshot = state.snapshot ?: return
    val count = state.count
    SetRing(
        segments =
            (0 until count).map { i ->
                RingSegment(
                    when {
                        i in snapshot.done || state.guided.run
                            ?.items
                            ?.getOrNull(i)
                            ?.done == true -> SegmentState.Done

                        i == snapshot.index -> SegmentState.Current

                        else -> SegmentState.ToDo
                    },
                )
            },
        modifier = Modifier.fillMaxSize(),
    )
    if (state.waitingAtEnd || (item.reps == null)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = colors.primary, modifier = Modifier.size(56.dp))
            Text(stringResource(Res.string.done), style = MaterialTheme.typography.titleMedium, color = colors.outline)
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val reps = item.repsMax?.let { formatRange(item.reps, it) } ?: "${item.reps}"
            Text(reps, style = clockStyle((if (item.repsMax != null) 56.sp else 72.sp) * scale))
            Text(stringResource(Res.string.sets_reps), style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
        }
    }
}

// What comes after this item, so the next position can be ready.
@Composable
private fun UpNext(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val text =
        if (state.phase == GuidedPhase.Transition) {
            null
        } else {
            state.guided.upcoming?.let { stringResource(Res.string.workout_next, it.name) } ?: stringResource(Res.string.player_last)
        }
    Box(contentAlignment = Alignment.TopCenter, modifier = modifier.fillMaxWidth().heightIn(min = 24.dp)) {
        text?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, color = colors.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Finished(
    state: PlayerUiState,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val snapshot = state.snapshot ?: return
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            stringResource(if (snapshot.outcome == GuidedOutcome.Stopped) Res.string.player_stopped else Res.string.player_completed),
            style = MaterialTheme.typography.labelLarge,
            color = colors.tertiary,
        )
        Text(
            snapshot.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        BoxWithConstraints(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f, fill = false).padding(top = 12.dp)) {
            val size = minOf(DIAL_RING, maxWidth, maxHeight)
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).testTag(PLAYER_RING_TAG)) {
                ProgressRing(
                    progress = { if (state.count == 0) 1f else snapshot.done.size.toFloat() / state.count },
                    wavy = false,
                    color = colors.primary,
                    trackColor = colors.surfaceContainerHighest,
                    modifier = Modifier.fillMaxSize(),
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatClock(state.elapsed ?: Duration.ZERO), style = clockStyle(64.sp * (size / DIAL_RING)))
                    Text(
                        stringResource(Res.string.player_steps_done, snapshot.done.size, state.count),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.outline,
                    )
                }
            }
        }
    }
}

// Left: previous. Middle: Pause/Resume, Done or Next, never moving. Right: next.
@Composable
private fun Controls(
    state: PlayerUiState,
    onAction: (PlayerAction) -> Unit,
) {
    val finished = state.phase == GuidedPhase.Finished
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        FilledTonalIconButton(
            onClick = { onAction(PlayerAction.Previous) },
            enabled = state.canPrevious,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(width = DIAL_SIDE, height = DIAL_BUTTON_HEIGHT),
        ) {
            Icon(
                painterResource(Res.drawable.ic_keyboard_arrow_left),
                contentDescription = stringResource(Res.string.player_previous_step),
                modifier = Modifier.size(32.dp),
            )
        }
        val (label, action) =
            when (state.primary) {
                PlayerPrimary.Pause -> stringResource(Res.string.workout_pause) to PlayerAction.Pause
                PlayerPrimary.Resume -> stringResource(Res.string.workout_resume) to PlayerAction.Resume
                PlayerPrimary.Done -> stringResource(Res.string.done) to PlayerAction.Done
                PlayerPrimary.Next -> stringResource(Res.string.player_next) to PlayerAction.Next
                PlayerPrimary.Close -> stringResource(Res.string.done) to PlayerAction.Close
            }
        val icon =
            when (state.primary) {
                PlayerPrimary.Pause -> Res.drawable.ic_pause_large
                PlayerPrimary.Resume -> Res.drawable.ic_play_large
                else -> null
            }
        val clay = state.phase == GuidedPhase.Transition
        Button(
            onClick = { onAction(action) },
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(30.dp), pressedShape = RoundedCornerShape(20.dp)),
            colors =
                if (clay) {
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                } else {
                    ButtonDefaults.buttonColors()
                },
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1f).height(DIAL_BUTTON_HEIGHT),
        ) {
            icon?.let { Icon(painterResource(it), contentDescription = null, modifier = Modifier.size(28.dp)) }
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = if (icon != null) ButtonDefaults.IconSpacing else 0.dp),
            )
        }
        FilledTonalIconButton(
            onClick = { onAction(PlayerAction.Next) },
            enabled = state.canNext && !finished,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(width = DIAL_NEXT, height = DIAL_BUTTON_HEIGHT),
        ) {
            Icon(
                painterResource(Res.drawable.ic_skip_next_large),
                contentDescription = stringResource(Res.string.player_next_step),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}
