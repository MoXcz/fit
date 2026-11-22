// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayId
import app.fit.day.Rpe
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.day.ui.setLabel
import app.fit.day.ui.setValues
import app.fit.exercises.ExerciseRef
import app.fit.exercises.RefKind
import app.fit.exercises.exerciseForPreview
import app.fit.exercises.ui.label
import app.fit.resources.Res
import app.fit.resources.decrease_what
import app.fit.resources.deload_week
import app.fit.resources.done
import app.fit.resources.exercise_video
import app.fit.resources.ic_add
import app.fit.resources.ic_check
import app.fit.resources.ic_keyboard_arrow_left
import app.fit.resources.ic_open_in_new
import app.fit.resources.ic_pause_large
import app.fit.resources.ic_play_large
import app.fit.resources.ic_remove
import app.fit.resources.ic_skip_next_large
import app.fit.resources.increase_what
import app.fit.resources.item_reps
import app.fit.resources.load_bodyweight
import app.fit.resources.metric_distance
import app.fit.resources.metric_reps
import app.fit.resources.metric_time
import app.fit.resources.metric_weight
import app.fit.resources.sets_removed
import app.fit.resources.sets_reps
import app.fit.resources.workout_add_rest
import app.fit.resources.workout_added_weight
import app.fit.resources.workout_all_done
import app.fit.resources.workout_all_sets
import app.fit.resources.workout_bodyweight
import app.fit.resources.workout_bodyweight_summary
import app.fit.resources.workout_complete_set
import app.fit.resources.workout_details_hint
import app.fit.resources.workout_finish
import app.fit.resources.workout_gone
import app.fit.resources.workout_hold
import app.fit.resources.workout_next
import app.fit.resources.workout_next_exercise
import app.fit.resources.workout_next_set
import app.fit.resources.workout_next_up
import app.fit.resources.workout_of
import app.fit.resources.workout_pause
import app.fit.resources.workout_paused
import app.fit.resources.workout_position
import app.fit.resources.workout_previous
import app.fit.resources.workout_rest
import app.fit.resources.workout_rest_over
import app.fit.resources.workout_resume
import app.fit.resources.workout_rpe
import app.fit.resources.workout_rpe_value
import app.fit.resources.workout_set_options
import app.fit.resources.workout_sets_count
import app.fit.resources.workout_skip_rest
import app.fit.resources.workout_start_timer
import app.fit.resources.workout_stop_hold
import app.fit.resources.workout_time
import app.fit.routines.MAIN_SECTION
import app.fit.ui.BackButton
import app.fit.ui.BackGuard
import app.fit.ui.MenuItem
import app.fit.ui.OverflowMenu
import app.fit.ui.Pill
import app.fit.ui.ProgressRing
import app.fit.ui.RingSegment
import app.fit.ui.ScreenTopBar
import app.fit.ui.SegmentState
import app.fit.ui.SetRing
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.clockStyle
import app.fit.ui.countdown
import app.fit.ui.formatClock
import app.fit.ui.formatNumber
import app.fit.ui.formatRange
import app.fit.ui.parseDecimal
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import app.fit.workout.HoldRun
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@Composable
fun WorkoutRoute(
    id: BlockId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // Finish leaves for the day's summary.
    onFinished: (LocalDate) -> Unit = { onBack() },
    viewModel: WorkoutViewModel = koinViewModel(key = "workout:${id.value}") { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.finished) {
        if (state.finished) {
            viewModel.onAction(WorkoutAction.FinishedSeen)
            state.day?.date?.let(onFinished) ?: onBack()
        }
    }
    // Leaves with the screen, so Today lets the screen sleep again.
    if (state.keepScreenOn && !state.review) KeepScreenOn()
    WorkoutScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// The sheet's peek: its handle, the "All sets" line and the set chips.
// The largest ring; smaller screens and larger text get a smaller one.
private val RING = 280.dp

private val TOP_BAR_HEIGHT = 64.dp

// Tell the dial's values from the same values in the sheet, and find the ring.
const val DIAL_TAG = "dial"
const val RING_TAG = "ring"

// The dial: one exercise at a time, its sets around a ring with the current set's values in the
// middle, and one connected group of buttons that never moves. Everything else is in the sheet.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WorkoutScreen(
    state: WorkoutUiState,
    onAction: (WorkoutAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val block = state.block
    val day = state.day
    val colors = MaterialTheme.colorScheme
    // Peeking or open, never hidden.
    val sheet =
        rememberBottomSheetState(
            if (state.sheetExpanded) SheetValue.Expanded else SheetValue.PartiallyExpanded,
            setOf(SheetValue.PartiallyExpanded, SheetValue.Expanded),
        )
    val scaffold = rememberBottomSheetScaffoldState(sheet)
    val expanded = state.sheetExpanded
    LaunchedEffect(expanded) { if (expanded) sheet.expand() else sheet.partialExpand() }
    // A drag on the sheet tells the ViewModel where it settled.
    val current by rememberUpdatedState(expanded)
    LaunchedEffect(sheet) {
        snapshotFlow { sheet.currentValue }.collect { value ->
            val open = value == SheetValue.Expanded
            if (open != current) onAction(WorkoutAction.SetSheet(open))
        }
    }
    // Back closes the sheet before it leaves the screen.
    BackGuard(enabled = expanded) { onAction(WorkoutAction.SetSheet(false)) }
    // The sheet's peek: its handle and paddings, then its text (the header and a chip's two lines),
    // which grows with the font scale.
    val peek = with(LocalDensity.current) { 72.dp + 56.sp.toDp() }
    Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        BottomSheetScaffold(
            scaffoldState = scaffold,
            sheetPeekHeight = if (block != null && day != null) peek else 0.dp,
            sheetContainerColor = colors.surfaceContainerHigh,
            sheetDragHandle = {
                Box(
                    Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .size(width = 32.dp, height = 4.dp)
                        .clip(CircleShape)
                        .background(colors.outline),
                )
            },
            containerColor = colors.surfaceContainer,
            sheetContent = {
                if (block != null && day != null) {
                    SetsSheet(
                        state = state,
                        block = block,
                        onToggle = { onAction(WorkoutAction.SetSheet(!expanded)) },
                        onAction = onAction,
                    )
                }
            },
        ) {
            // The keyboard covers the peek, so its height counts once: adding both left the ring no room.
            val under = PaddingValues(bottom = if (block != null) peek else 0.dp)
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(under)
                    .consumeWindowInsets(under)
                    .imePadding(),
            ) {
                TopBar(state = state, onAction = onAction, onBack = onBack)
                when {
                    block != null && day != null -> {
                        Dial(state = state, block = block, onAction = onAction, modifier = Modifier.weight(1f))
                        Controls(state = state, day = day, onAction = onAction, onBack = onBack)
                    }

                    !state.loading -> {
                        Text(
                            stringResource(Res.string.workout_gone),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                        )
                    }
                }
            }
        }
        UndoSnackbar(
            message = state.removed.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.sets_removed, it, it) },
            onUndo = { onAction(WorkoutAction.Undo) },
            onDismiss = { onAction(WorkoutAction.UndoSeen) },
            // Never over a control: under the end of the open sheet's list, else under the top bar,
            // where the dial only has text. Complete set stays free.
            modifier =
                if (expanded) {
                    Modifier.align(Alignment.BottomCenter)
                } else {
                    Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = TOP_BAR_HEIGHT)
                },
        )
    }
}

// Back, "2 of 8 · 25:12", and the overflow.
@Composable
private fun TopBar(
    state: WorkoutUiState,
    onAction: (WorkoutAction) -> Unit,
    onBack: () -> Unit,
) {
    val day = state.day
    val colors = MaterialTheme.colorScheme
    ScreenTopBar(
        title = {
            if (day != null && state.block != null) {
                val position = stringResource(Res.string.workout_position, state.index + 1, day.blocks.size)
                Text(
                    listOfNotNull(position, state.elapsed?.let(::formatClock)).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (day.finished) colors.onSurfaceVariant else colors.outline,
                )
            }
        },
        navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        actions = {
            val items =
                buildList {
                    // Stop logs nothing; Complete set logs the time held.
                    if (state.hold !=
                        null
                    ) {
                        add(MenuItem(stringResource(Res.string.workout_stop_hold), { onAction(WorkoutAction.StopHold) }))
                    }
                    if (day != null &&
                        !state.review
                    ) {
                        add(MenuItem(stringResource(Res.string.workout_finish), { onAction(WorkoutAction.Finish) }))
                    }
                }
            if (items.isNotEmpty()) OverflowMenu(items)
        },
    )
}

@Composable
private fun Dial(
    state: WorkoutUiState,
    block: Block,
    onAction: (WorkoutAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val rest = state.rest
    val hold = state.hold
    val set = state.current
    // Centred in the space above the buttons. The ring takes what the text above and the steppers
    // below leave, up to its full size, so it never runs into them on a small screen or with large text.
    Column(
        modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(DIAL_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // The section, and whether the day's loads were cut for a deload week.
        val deload = state.day?.deload == true
        if (block.section != MAIN_SECTION || deload) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (block.section != MAIN_SECTION) Text(block.section, style = MaterialTheme.typography.labelLarge, color = colors.tertiary)
                if (deload) Pill(stringResource(Res.string.deload_week), tertiary = true)
            }
        }
        Text(
            block.exercise.fields.name,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        // What the ring shows, and the slot's cue as plain text.
        Row(horizontalArrangement = Arrangement.Center) {
            val line =
                when {
                    rest != null -> stringResource(if (resting(state)) Res.string.workout_rest else Res.string.workout_rest_over)
                    set != null && (!state.review || hold != null) -> setLabel(block, set)
                    state.allDone -> stringResource(Res.string.workout_all_done)
                    else -> null
                }
            line?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant) }
            val range = set?.targetRepsMax?.takeIf { rest == null && hold == null && block.tracks.reps && !state.review }
            range?.let {
                Text(
                    " · " + stringResource(Res.string.item_reps, formatRange(set.targetReps, it)),
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.outline,
                )
            }
        }
        if (block.notes.isNotBlank()) {
            Text(
                block.notes,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.outline,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        BoxWithConstraints(
            contentAlignment = Alignment.Center,
            modifier = Modifier.weight(1f, fill = false).padding(top = 12.dp),
        ) {
            val size = minOf(RING, maxWidth, maxHeight)
            // The numbers inside shrink with the ring.
            val scale = size / RING
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).testTag(RING_TAG)) {
                when {
                    rest != null -> RestRing(state, scale)
                    hold != null -> HoldRing(state, scale)
                    else -> SetsRing(state, block, scale, onAction)
                }
            }
        }
        Box(contentAlignment = Alignment.TopCenter, modifier = Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(top = 12.dp)) {
            when {
                rest != null -> NextCard(state, onAction)
                hold == null && set != null && !state.review -> Steppers(state, block, set, onAction)
            }
        }
    }
}

private fun resting(state: WorkoutUiState): Boolean = state.rest?.rang != true && state.restLeft > Duration.ZERO

private class LongestRest {
    var length: Duration = Duration.ZERO
}

// Hush's wavy ring as the rest countdown, in clay; full once the rest is over, which counts up.
@Composable
private fun RestRing(
    state: WorkoutUiState,
    scale: Float,
) {
    val colors = MaterialTheme.colorScheme
    val resting = resting(state)
    val total =
        state.day
            ?.blocks
            ?.firstOrNull { it.id == state.rest?.blockId }
            ?.rest ?: Duration.ZERO
    // The snapshot has no start, so the length is the exercise's rest, or the most left after +15 s,
    // remembered for this rest so the ring doesn't jump back as the time runs down.
    val longest = remember(state.rest?.setId) { LongestRest() }
    longest.length = maxOf(longest.length, total, state.restLeft)
    val length = longest.length
    ProgressRing(
        progress = { if (!resting || length == Duration.ZERO) 1f else (state.restLeft / length).toFloat() },
        wavy = true,
        color = colors.tertiary,
        trackColor = colors.tertiaryContainer,
        modifier = Modifier.fillMaxSize(),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (resting) countdown(state.restLeft) else "+${formatClock(state.restOver)}",
            style = clockStyle(64.sp * scale),
            color = colors.tertiary,
        )
        if (resting && length > Duration.ZERO) {
            Text(
                stringResource(Res.string.workout_of, formatClock(length)),
                style = MaterialTheme.typography.titleMedium,
                color = colors.outline,
            )
        }
    }
}

@Composable
private fun HoldRing(
    state: WorkoutUiState,
    scale: Float,
) {
    val colors = MaterialTheme.colorScheme
    val hold = state.hold ?: return
    val paused = hold.run is HoldRun.Paused
    ProgressRing(
        progress = { if (hold.length == Duration.ZERO) 0f else (state.holdLeft / hold.length).toFloat() },
        wavy = false,
        color = colors.primary,
        trackColor = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxSize(),
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(countdown(state.holdLeft), style = clockStyle(64.sp * scale), color = if (paused) colors.outline else colors.primary)
        Text(
            stringResource(if (paused) Res.string.workout_paused else Res.string.workout_hold),
            style = MaterialTheme.typography.titleMedium,
            color = colors.outline,
        )
    }
}

// The exercise's sets as segments, and the current set's values in the middle.
@Composable
private fun SetsRing(
    state: WorkoutUiState,
    block: Block,
    scale: Float,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val current = state.current.takeIf { !state.review }
    SetRing(
        segments =
            block.sets.map {
                RingSegment(
                    state =
                        when {
                            it.done -> SegmentState.Done
                            it.id == current?.id -> SegmentState.Current
                            else -> SegmentState.ToDo
                        },
                    warmup = it.warmup,
                )
            },
        modifier = Modifier.fillMaxSize(),
    )
    when {
        current != null -> {
            DialValues(state, block, current, scale, onAction)
        }

        state.review -> {
            Text(
                stringResource(Res.string.workout_sets_count, block.workingSets.count { it.done }, block.workingSets.size),
                style = clockStyle(36.sp * scale),
            )
        }

        else -> {
            Icon(painterResource(Res.drawable.ic_check), contentDescription = null, tint = colors.primary, modifier = Modifier.size(56.dp))
        }
    }
}

// The fields the dial shows, in this order; the first is the large one.
private fun Block.dialFields(set: SetEntry): List<SetField> =
    buildList {
        if (tracks.weight || set.weight != null) add(SetField.Weight)
        if (tracks.reps) add(SetField.Reps)
        if (tracks.distance) add(SetField.Distance)
        if (tracks.time) add(SetField.Seconds)
    }.take(2)

private val SetField.metric: StringResource
    get() =
        when (this) {
            SetField.Weight -> Res.string.metric_weight
            SetField.Reps -> Res.string.metric_reps
            SetField.Seconds -> Res.string.metric_time
            SetField.Distance -> Res.string.metric_distance
        }

@Composable
private fun unitLabel(
    field: SetField,
    set: SetEntry,
): String =
    when (field) {
        SetField.Weight -> set.weightUnit.symbol
        SetField.Reps -> stringResource(Res.string.sets_reps)
        SetField.Distance -> set.distanceUnit.symbol
        SetField.Seconds -> stringResource(Res.string.workout_time)
    }

// Tap a number to type it; − and + sit under the ring. A time is never typed here.
@Composable
private fun DialValues(
    state: WorkoutUiState,
    block: Block,
    set: SetEntry,
    scale: Float,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fields = block.dialFields(set)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        fields.forEachIndexed { index, field ->
            val size = (if (index == 0) (if (fields.size == 1) 72.sp else 56.sp) else 34.sp) * scale
            val other = field == SetField.Weight && block.inOtherUnit(set)
            Row(verticalAlignment = Alignment.Bottom) {
                if (index > 0 && field == SetField.Reps && fields[0] == SetField.Weight) {
                    Text("× ", style = clockStyle(size), color = colors.onSurfaceVariant)
                }
                // A bodyweight set's number is what's added.
                if (field == SetField.Weight && set.bodyweight) {
                    Text(
                        stringResource(Res.string.load_bodyweight) + " + ",
                        style = clockStyle(size * 0.6f),
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
                if (field == SetField.Seconds) {
                    Text(formatClock(state.value(field).toIntOrNull()?.seconds ?: Duration.ZERO), style = clockStyle(size))
                } else {
                    DialInput(
                        state = state,
                        set = set,
                        field = field,
                        style = clockStyle(size),
                        name =
                            if (field == SetField.Reps) {
                                stringResource(field.metric)
                            } else {
                                "${stringResource(field.metric)} (${unitLabel(field, set)})"
                            },
                        onAction = onAction,
                    )
                }
                Text(
                    " " + unitLabel(field, set),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (other) FontWeight.Bold else FontWeight.Normal,
                    color = if (other) colors.tertiary else colors.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (index == 0) 10.dp else 4.dp),
                )
            }
        }
    }
}

// As wide as what it holds, so 142.5 shows in full and stays centred.
@Composable
private fun DialInput(
    state: WorkoutUiState,
    set: SetEntry,
    field: SetField,
    style: TextStyle,
    // Read out by accessibility services: the number alone doesn't say what it is.
    name: String,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val value = state.value(field)
    // Typing stays here (no round trip through the state); a value from the steppers or the sheet replaces it.
    var text by remember(set.id, field) { mutableStateOf(value) }
    LaunchedEffect(value) { if (value != text) text = value }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val width =
        with(density) {
            measurer
                .measure(text.ifEmpty { "0" }, style)
                .size.width
                .toDp()
        } + 4.dp
    val decimal = field == SetField.Weight || field == SetField.Distance
    BasicTextField(
        value = text,
        onValueChange = {
            text = it
            onAction(WorkoutAction.Draft(field, it))
        },
        singleLine = true,
        textStyle = style.copy(color = colors.onSurface, textAlign = TextAlign.Center),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions =
            KeyboardOptions(
                keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
        modifier = Modifier.width(width).semantics { contentDescription = name },
    ) { inner ->
        Box(contentAlignment = Alignment.Center) {
            if (text.isEmpty()) Text("0", style = style.copy(color = colors.outline))
            inner()
        }
    }
}

@Composable
private fun Steppers(
    state: WorkoutUiState,
    block: Block,
    set: SetEntry,
    onAction: (WorkoutAction) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        block.dialFields(set).forEach { field ->
            StepperPill(label = unitLabel(field, set), onStep = { up -> onAction(WorkoutAction.Step(field, up)) })
        }
    }
}

@Composable
private fun StepperPill(
    label: String,
    onStep: (up: Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.height(48.dp).clip(CircleShape).background(colors.surfaceContainerHigh),
    ) {
        val less = stringResource(Res.string.decrease_what, label)
        val more = stringResource(Res.string.increase_what, label)
        IconButton(onClick = { onStep(false) }) {
            Icon(painterResource(Res.drawable.ic_remove), contentDescription = less)
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant, modifier = Modifier.widthIn(min = 34.dp))
        IconButton(onClick = { onStep(true) }) {
            Icon(painterResource(Res.drawable.ic_add), contentDescription = more)
        }
    }
}

// During a rest: the set that comes next, which − and + change at once.
@Composable
private fun NextCard(
    state: WorkoutUiState,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val (block, set) = state.upNext ?: return
    val tracks = block.tracks
    val field =
        when {
            tracks.weight -> SetField.Weight
            tracks.reps -> SetField.Reps
            tracks.time -> SetField.Seconds
            else -> SetField.Distance
        }
    val label = unitLabel(field, set)
    Surface(shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                val where =
                    if (block.id ==
                        state.block?.id
                    ) {
                        setLabel(block, set)
                    } else {
                        "${block.exercise.fields.name} · ${setLabel(block, set)}"
                    }
                Text(
                    stringResource(Res.string.workout_next_up, where),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val shown = if (set.id == state.current?.id) state.draftedSet(block, set) else set
                Text(setValues(block, shown), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
            }
            FilledTonalIconButton(onClick = { onAction(WorkoutAction.StepNext(field, up = false)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(Res.drawable.ic_remove), contentDescription = stringResource(Res.string.decrease_what, label))
            }
            FilledTonalIconButton(onClick = { onAction(WorkoutAction.StepNext(field, up = true)) }, shapes = IconButtonDefaults.shapes()) {
                Icon(painterResource(Res.drawable.ic_add), contentDescription = stringResource(Res.string.increase_what, label))
            }
        }
    }
}

// The current set as Complete set would log it: what's typed over what it holds.
private fun WorkoutUiState.draftedSet(
    block: Block,
    set: SetEntry,
): SetEntry =
    set.copy(
        weight = draft[SetField.Weight]?.let(::parseDecimal) ?: set.weight,
        reps = draft[SetField.Reps]?.toIntOrNull() ?: set.reps,
        duration = holdLength(set, block, draft),
    )

// Left: previous (or +15 s while resting, pause while holding). Middle: the one primary action, which
// never moves. Right: next exercise.
@Composable
private fun Controls(
    state: WorkoutUiState,
    day: WorkoutDay,
    onAction: (WorkoutAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val hold = state.hold
    val rest = state.rest
    val block = state.block ?: return
    val height = 88.dp
    // The same width in every state, so the primary action never moves.
    val side = 88.dp
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when {
            rest != null -> {
                FilledTonalButton(
                    onClick = { onAction(WorkoutAction.AddRest) },
                    shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(40.dp), pressedShape = RoundedCornerShape(24.dp)),
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.size(width = side, height = height),
                ) { Text(stringResource(Res.string.workout_add_rest), style = MaterialTheme.typography.titleMedium) }
            }

            hold != null -> {
                val paused = hold.run is HoldRun.Paused
                FilledIconToggleButton(
                    checked = !paused,
                    onCheckedChange = { onAction(if (paused) WorkoutAction.ResumeHold else WorkoutAction.PauseHold) },
                    shapes = IconButtonDefaults.toggleableShapes(checkedShape = MaterialTheme.shapes.extraLarge),
                    modifier = Modifier.size(width = side, height = height),
                ) {
                    Icon(
                        painterResource(if (paused) Res.drawable.ic_play_large else Res.drawable.ic_pause_large),
                        contentDescription = stringResource(if (paused) Res.string.workout_resume else Res.string.workout_pause),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            else -> {
                FilledTonalIconButton(
                    onClick = { onAction(WorkoutAction.Previous) },
                    enabled = state.previous != null && state.canMove,
                    shapes = IconButtonDefaults.shapes(),
                    modifier = Modifier.size(width = side, height = height),
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_keyboard_arrow_left),
                        contentDescription = stringResource(Res.string.workout_previous),
                        modifier = Modifier.size(32.dp),
                    )
                }
            }
        }
        val next = state.next
        val set = state.current
        val (label, action) =
            when {
                rest != null -> {
                    stringResource(if (resting(state)) Res.string.workout_skip_rest else Res.string.workout_next_set) to
                        WorkoutAction.SkipRest
                }

                hold != null -> {
                    stringResource(Res.string.workout_complete_set) to WorkoutAction.CompleteHold
                }

                set != null && !state.review && block.tracks.time -> {
                    stringResource(Res.string.workout_start_timer) to WorkoutAction.StartTimer
                }

                set != null && !state.review -> {
                    stringResource(Res.string.workout_complete_set) to WorkoutAction.CompleteSet
                }

                next != null -> {
                    stringResource(Res.string.workout_next, next.exercise.fields.name) to WorkoutAction.Next
                }

                !day.finished && !state.review -> {
                    stringResource(Res.string.workout_finish) to WorkoutAction.Finish
                }

                else -> {
                    stringResource(Res.string.done) to null
                }
            }
        val clay = rest != null
        Button(
            onClick = { if (action != null) onAction(action) else onBack() },
            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(30.dp), pressedShape = RoundedCornerShape(20.dp)),
            colors =
                if (clay) {
                    ButtonDefaults.buttonColors(containerColor = colors.tertiary, contentColor = colors.onTertiary)
                } else {
                    ButtonDefaults.buttonColors()
                },
            modifier = Modifier.weight(1f).height(height),
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FilledTonalIconButton(
            onClick = { onAction(WorkoutAction.Next) },
            enabled = next != null && state.canMove,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(width = 64.dp, height = height),
        ) {
            Icon(
                painterResource(Res.drawable.ic_skip_next_large),
                contentDescription = stringResource(Res.string.workout_next_exercise),
                modifier = Modifier.size(28.dp),
            )
        }
    }
}

// The peek: "All sets" and a chip per set. Open: every set to edit, then the notes and links.
@Composable
private fun SetsSheet(
    state: WorkoutUiState,
    block: Block,
    onToggle: () -> Unit,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fields = block.exercise.fields
    val hasDetails = fields.notes.isNotBlank() || fields.videoUrl.isNotBlank() || fields.refs.isNotEmpty()
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = UndoBarSpace), modifier = Modifier.imePadding()) {
        item(key = "sheet-header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable(onClick = onToggle),
            ) {
                Text(
                    stringResource(Res.string.workout_all_sets),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (hasDetails) {
                    Text(
                        stringResource(Res.string.workout_details_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.outline,
                    )
                }
            }
        }
        item(key = "chips") { SetChips(state = state, block = block, onToggle = onToggle) }
        item(key = "chips-gap") { Spacer(Modifier.height(16.dp)) }
        allSets(block = block, plates = state.plates, onAction = onAction, rating = state.rating)
        state.optionsSet?.let { set -> item(key = "set-options") { SetOptions(block = block, set = set, onAction = onAction) } }
        item(key = "details") { Details(block) }
    }
}

const val RPE_TAG = "rpe"

// RPE on a working set, the bodyweight switch on sets with a load or reps, and added weight on a
// reps-only slot, for the set [WorkoutUiState.optionsSet] names.
@Composable
private fun SetOptions(
    block: Block,
    set: SetEntry,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val tracks = block.tracks
    val rows =
        buildList<@Composable (Int, Int) -> Unit> {
            if (set.working) add { index, count -> RpeRow(set = set, index = index, count = count, onAction = onAction) }
            if (tracks.weight || tracks.reps) {
                add { index, count ->
                    OptionSwitch(
                        label = stringResource(Res.string.workout_bodyweight),
                        supporting = stringResource(Res.string.workout_bodyweight_summary),
                        checked = set.bodyweight,
                        index = index,
                        count = count,
                        onChange = { onAction(WorkoutAction.SetBodyweight(it)) },
                    )
                }
            }
            if (!tracks.weight && tracks.reps) {
                add { index, count ->
                    OptionSwitch(
                        label = stringResource(Res.string.workout_added_weight),
                        supporting = null,
                        checked = set.weight != null,
                        index = index,
                        count = count,
                        onChange = { onAction(WorkoutAction.AddedWeight(it)) },
                    )
                }
            }
        }
    if (rows.isEmpty()) return
    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(Res.string.workout_set_options, setLabel(block, set)),
            style = MaterialTheme.typography.titleSmall,
            color = colors.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        rows.forEachIndexed { index, row -> row(index, rows.size) }
    }
}

@Composable
private fun OptionSwitch(
    label: String,
    supporting: String?,
    checked: Boolean,
    index: Int,
    count: Int,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier.clip(segmentShape(index, count)).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
    )
}

// 5 to 10 in halves; tapping the chosen one clears it.
@Composable
private fun RpeRow(
    set: SetEntry,
    index: Int,
    count: Int,
    onAction: (WorkoutAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val start = Rpe.values.indexOf(set.rpe ?: RPE_START).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (start - 2).coerceAtLeast(0))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(segmentShape(index, count))
            .background(colors.surfaceBright)
            .padding(vertical = 12.dp),
    ) {
        Text(
            set.rpe?.let { stringResource(Res.string.workout_rpe_value, formatNumber(it)) } ?: stringResource(Res.string.workout_rpe),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        LazyRow(
            state = list,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag(RPE_TAG),
        ) {
            items(Rpe.values, key = { it }) { value ->
                val chosen = set.rpe == value
                val label = formatNumber(value)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier =
                        Modifier
                            .size(width = 52.dp, height = 48.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (chosen) colors.primaryContainer else colors.surfaceContainerHighest)
                            .selectable(selected = chosen, role = Role.RadioButton) {
                                onAction(WorkoutAction.SetRpe(if (chosen) null else value))
                            },
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                        color = if (chosen) colors.onPrimaryContainer else colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Where the RPE row starts scrolled to when nothing's chosen.
private const val RPE_START = 7.0

@Composable
private fun SetChips(
    state: WorkoutUiState,
    block: Block,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val working = block.workingSets
    val current = state.current?.id
    val bodyweight = stringResource(Res.string.load_bodyweight)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        itemsIndexed(block.sets, key = { _, set -> set.id.value }) { _, set ->
            val (container, content) =
                when {
                    set.id == current && !state.review -> colors.primary to colors.onPrimary
                    set.done -> colors.primaryContainer to colors.onPrimaryContainer
                    else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
                }
            val other = block.inOtherUnit(set)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier =
                    Modifier
                        .widthIn(min = 56.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(container)
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(
                    if (set.warmup) "W" else "${working.indexOf(set) + 1}",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (other) colors.tertiary else content,
                )
                Text(
                    chipValue(block, if (set.id == current) state.draftedSet(block, set) else set, other, bodyweight),
                    style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.SemiBold,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

// "62.5×5", "8", "0:40", "BW+10×8"; a load in another unit names its unit.
private fun chipValue(
    block: Block,
    set: SetEntry,
    otherUnit: Boolean,
    bodyweight: String,
): String {
    val tracks = block.tracks
    val weight =
        set.weight?.takeIf { tracks.weight || it > 0 }?.let {
            formatNumber(
                it,
            ) + if (otherUnit) " ${set.weightUnit.symbol}" else ""
        }
    val load =
        when {
            !set.bodyweight -> weight
            weight != null && (set.weight ?: 0.0) > 0 -> "$bodyweight+$weight"
            else -> bodyweight
        }
    val reps = (set.reps ?: set.targetReps).takeIf { tracks.reps }
    val time = (set.duration ?: set.targetDuration ?: block.duration).takeIf { tracks.time }?.let(::formatClock)
    val distance = set.distance?.takeIf { tracks.distance }?.let(::formatNumber)
    return when {
        load != null && reps != null -> "$load×$reps"
        else -> listOfNotNull(load, reps?.toString(), distance, time).firstOrNull() ?: "–"
    }
}

// Notes, the video and the references the exercise holds.
@Composable
private fun Details(block: Block) {
    val fields = block.exercise.fields
    val links =
        buildList {
            if (fields.videoUrl.isNotBlank()) add(ExerciseRef(RefKind.Video, stringResource(Res.string.exercise_video), fields.videoUrl))
            addAll(fields.refs)
        }
    if (fields.notes.isBlank() && links.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 16.dp)) {
        if (fields.notes.isNotBlank()) {
            Text(
                fields.notes,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(segmentShape(0, 1))
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .padding(16.dp),
            )
        }
        val uriHandler = LocalUriHandler.current
        links.forEachIndexed { index, link ->
            SegmentedListItem(
                onClick = { uriHandler.openUri(link.url) },
                shapes = segmentedShapes(index, links.size),
                colors = segmentedColors,
                supportingContent = { Text(if (link.title.isBlank()) stringResource(link.kind.label) else link.url, maxLines = 1) },
                trailingContent = { Icon(painterResource(Res.drawable.ic_open_in_new), contentDescription = null) },
            ) { Text(link.title.ifBlank { link.url }, maxLines = 2) }
        }
    }
}

@Preview
@Composable
private fun WorkoutScreenPreview() {
    val block =
        Block(
            id = BlockId(1),
            exercise = exerciseForPreview(1, "Squat"),
            rest = 90.seconds,
            duration = 45.seconds,
            sets = listOf(SetEntry(SetId(1), targetReps = 8, targetRepsMax = 12, weight = 142.5)),
        )
    val day = WorkoutDay(DayId(1), LocalDate(2026, 11, 9), blocks = listOf(block))
    FitTheme {
        WorkoutScreen(
            state = WorkoutUiState(loading = false, day = day, block = block, today = day.date, now = Instant.fromEpochMilliseconds(0)),
            onAction = {},
            onBack = {},
        )
    }
}
