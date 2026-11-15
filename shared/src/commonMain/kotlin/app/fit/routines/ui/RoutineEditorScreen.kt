// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.LastLoad
import app.fit.exercises.ui.typeLabel
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.day_drag
import app.fit.resources.done
import app.fit.resources.editor_add_exercise
import app.fit.resources.editor_added
import app.fit.resources.editor_col_exercise
import app.fit.resources.editor_col_load
import app.fit.resources.editor_col_rest
import app.fit.resources.editor_col_sets
import app.fit.resources.editor_copied
import app.fit.resources.editor_copy_title
import app.fit.resources.editor_copy_to
import app.fit.resources.editor_empty
import app.fit.resources.editor_move_down
import app.fit.resources.editor_move_up
import app.fit.resources.editor_no_other_routines
import app.fit.resources.editor_notes
import app.fit.resources.editor_removed
import app.fit.resources.editor_reorder
import app.fit.resources.editor_select
import app.fit.resources.editor_selected
import app.fit.resources.editor_use_today
import app.fit.resources.ic_add
import app.fit.resources.ic_drag_indicator
import app.fit.resources.ic_edit
import app.fit.resources.ic_play_large
import app.fit.resources.item_progress
import app.fit.resources.item_reps
import app.fit.resources.item_rest
import app.fit.resources.item_sets_target
import app.fit.resources.item_warmup_sets
import app.fit.resources.replace
import app.fit.resources.replace_today_body
import app.fit.resources.replace_today_title
import app.fit.resources.routine_name
import app.fit.resources.routines_delete
import app.fit.routines.MAIN_SECTION
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.sectionsOf
import app.fit.ui.BackButton
import app.fit.ui.BackGuard
import app.fit.ui.MenuItem
import app.fit.ui.OverflowMenu
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.connectedShape
import app.fit.ui.dragHandle
import app.fit.ui.formatClock
import app.fit.ui.formatDurationRange
import app.fit.ui.formatNumber
import app.fit.ui.formatRange
import app.fit.ui.rememberDragReorder
import app.fit.ui.rememberFullSheetState
import app.fit.ui.reorderable
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.listRowShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import org.koin.core.qualifier.named
import kotlin.time.Duration

@Composable
fun RoutineEditorRoute(
    id: RoutineId,
    onBack: () -> Unit,
    onUsedToday: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutineEditorViewModel = koinViewModel(key = "routine:${id.value}") { parametersOf(id.value) },
    picker: ExercisePickerViewModel =
        koinViewModel(key = "picker:${id.value}", qualifier = named(ROUTINE_PICKER)) { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val pickerState by picker.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.usedToday) {
        if (state.usedToday) {
            viewModel.onAction(RoutineEditorAction.UsedTodaySeen)
            onUsedToday()
        }
    }
    RoutineEditorScreen(
        state = state,
        onAction = viewModel::onAction,
        onBack = onBack,
        modifier = modifier,
        picker = {
            ExercisePickerSheet(
                state = pickerState,
                onAction = picker::onAction,
                onAdded = { viewModel.onAction(RoutineEditorAction.Added(RoutineItemId(it.id), it.name)) },
                onClose = { viewModel.onAction(RoutineEditorAction.ClosePicker) },
            )
        },
    )
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun RoutineEditorScreen(
    state: RoutineEditorUiState,
    onAction: (RoutineEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    picker: @Composable () -> Unit = {},
) {
    // Back leaves reorder or select mode before it leaves the screen.
    BackGuard(enabled = state.mode != EditorMode.Normal) { onAction(RoutineEditorAction.SetMode(EditorMode.Normal)) }
    state.notes?.let { NotesSheet(notes = it, onAction = onAction) }
    state.item?.let { ItemSheet(editor = it, onAction = onAction) }
    state.copyTargets?.let { CopyTargetDialog(targets = it, onAction = onAction) }
    if (state.confirmUseToday) ReplaceTodayDialog(onAction = onAction)
    if (state.pickerOpen) picker()

    val routine = state.routine
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize()) {
            // Back, title, one action: in a mode, Back leaves the mode.
            val normal = state.mode == EditorMode.Normal
            ScreenTopBar(
                title = {
                    when (state.mode) {
                        EditorMode.Normal -> {}

                        EditorMode.Reorder -> {
                            ScreenTitle(stringResource(Res.string.editor_reorder))
                        }

                        EditorMode.Select -> {
                            ScreenTitle(
                                pluralStringResource(Res.plurals.editor_selected, state.selected.size, state.selected.size),
                            )
                        }
                    }
                },
                navigationIcon = {
                    BackButton(
                        onClick = { if (normal) onBack() else onAction(RoutineEditorAction.SetMode(EditorMode.Normal)) },
                        modifier = Modifier.padding(start = 8.dp),
                    )
                },
                actions = {
                    if (normal && routine != null) {
                        OverflowMenu(listOf(MenuItem(stringResource(Res.string.editor_notes), { onAction(RoutineEditorAction.OpenNotes) })))
                    }
                },
            )
            Box(Modifier.weight(1f)) {
                // The button's height as laid out, so the list's end clears it at any text size.
                var addSpace by remember { mutableStateOf(0.dp) }
                val density = LocalDensity.current
                if (routine != null) {
                    EditorList(
                        routine = routine,
                        state = state,
                        onAction = onAction,
                        bottomSpace = UndoBarSpace + if (normal) addSpace else 0.dp,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                // Stacked, so the Undo bar is above the button (or the selection bar) by layout.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    UndoSnackbar(
                        message = state.undo?.let { undoMessage(it) },
                        onUndo = { onAction(RoutineEditorAction.Undo) },
                        onDismiss = { onAction(RoutineEditorAction.UndoSeen) },
                        modifier = Modifier.align(Alignment.CenterHorizontally),
                    )
                    if (routine != null && normal) {
                        ExtendedFloatingActionButton(
                            onClick = { onAction(RoutineEditorAction.OpenPicker) },
                            icon = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                            text = { Text(stringResource(Res.string.editor_add_exercise)) },
                            modifier =
                                Modifier
                                    .align(Alignment.End)
                                    .onSizeChanged { addSpace = with(density) { it.height.toDp() } }
                                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        )
                    }
                }
            }
            if (routine != null && state.mode == EditorMode.Select) {
                SelectionBar(hasSelection = state.selected.isNotEmpty(), onAction = onAction)
            }
        }
    }
}

@Composable
private fun undoMessage(undo: EditorUndo): String =
    when (undo) {
        is EditorUndo.Added -> stringResource(Res.string.editor_added, undo.name)
        is EditorUndo.Removed -> pluralStringResource(Res.plurals.editor_removed, undo.count, undo.count)
        is EditorUndo.Copied -> pluralStringResource(Res.plurals.editor_copied, undo.count, undo.count, undo.to)
    }

@Composable
private fun EditorList(
    routine: Routine,
    state: RoutineEditorUiState,
    onAction: (RoutineEditorAction) -> Unit,
    // Room to scroll the last row clear of the Undo bar and what's under it.
    bottomSpace: Dp,
    modifier: Modifier = Modifier,
) {
    val sections = sectionsOf(routine.items)
    val list = rememberLazyListState()
    val drag = rememberDragReorder(routine.items.map { it.id.value })
    // An exercise just added is brought into view, clear of the bar that says so.
    val shownIndex =
        state.shown?.let { id ->
            var index = HEAD_ITEMS
            sections.firstNotNullOfOrNull { section ->
                val at = section.items.indexOfFirst { it.id == id }
                (index + 1 + at).takeIf { at >= 0 }.also { index += 1 + section.items.size }
            }
        }
    LaunchedEffect(shownIndex) {
        if (shownIndex == null) return@LaunchedEffect
        list.showClear(shownIndex)
        onAction(RoutineEditorAction.ShownSeen)
    }
    LazyColumn(state = list, modifier = modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = bottomSpace)) {
        item(key = "name") { NameField(state = state, onAction = onAction) }
        item(key = "summary") {
            Column(Modifier.padding(horizontal = 32.dp)) {
                Text(
                    routineSummary(routine.toRow()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (routine.notes.isNotBlank()) {
                    Text(
                        routine.notes,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
        item(key = "actions") {
            if (state.mode == EditorMode.Normal) ActionGroup(canUseToday = routine.items.isNotEmpty(), onAction = onAction)
        }
        if (routine.items.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(Res.string.editor_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(32.dp),
                )
            }
        } else {
            item(key = "columns") { ColumnHeads() }
        }
        sections.forEach { section ->
            item(key = "section:${section.name}") {
                // As in the old app: Main only gets a header next to other sections.
                if (section.name != MAIN_SECTION || sections.size > 1) SectionTitle(section.name, modifier = Modifier.padding(start = 4.dp))
            }
            val keys = section.items.map { it.id.value }
            itemsIndexed(section.items, key = { _, item -> item.id.value }) { index, item ->
                ItemRow(
                    item = item,
                    index = index,
                    count = section.items.size,
                    mode = state.mode,
                    selected = item.id in state.selected,
                    load = state.lastLoads[item.exercise.id],
                    onAction = onAction,
                    handle =
                        Modifier.dragHandle(drag, list, item.id.value, keys) { key, by ->
                            onAction(RoutineEditorAction.Move(RoutineItemId(key as Long), by))
                        },
                    modifier = Modifier.reorderable(drag, item.id.value),
                )
            }
        }
    }
}

// The name, the summary, the actions and the column heads (or the empty line) before the sections.
private const val HEAD_ITEMS = 4

// Scrolls item [index] above the list's bottom space: by as little as it takes when it's on screen,
// else to the middle.
private suspend fun LazyListState.showClear(index: Int) {
    val clear = layoutInfo.viewportEndOffset - layoutInfo.afterContentPadding
    val item = layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
    if (item == null) {
        animateScrollToItem(index, -clear / 2)
    } else {
        val over = item.offset + item.size - clear
        if (over > 0) animateScrollBy(over.toFloat())
    }
}

// Use today, Reorder and Select, always in view under the title.
@Composable
private fun ActionGroup(
    canUseToday: Boolean,
    onAction: (RoutineEditorAction) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 4.dp),
    ) {
        Button(
            onClick = { onAction(RoutineEditorAction.UseToday) },
            enabled = canUseToday,
            shape = connectedShape(0, 3),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier.weight(1.3f).height(48.dp),
        ) {
            Icon(painterResource(Res.drawable.ic_play_large), contentDescription = null, modifier = Modifier.size(20.dp))
            Text(stringResource(Res.string.editor_use_today), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
        }
        listOf(
            Res.string.editor_reorder to RoutineEditorAction.SetMode(EditorMode.Reorder),
            Res.string.editor_select to RoutineEditorAction.SetMode(EditorMode.Select),
        ).forEachIndexed { i, (label, action) ->
            FilledTonalButton(
                onClick = { onAction(action) },
                enabled = canUseToday,
                shape = connectedShape(i + 1, 3),
                contentPadding = PaddingValues(horizontal = 12.dp),
                modifier = Modifier.weight(1f).height(48.dp),
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

private val SETS_WIDTH = 64.dp
private val LOAD_WIDTH = 68.dp
private val REST_WIDTH = 48.dp

@Composable
private fun ColumnHeads() {
    val style = MaterialTheme.typography.labelSmall
    val color = MaterialTheme.colorScheme.outline
    Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(Res.string.editor_col_exercise).uppercase(), style = style, color = color, modifier = Modifier.weight(1f))
        Text(
            stringResource(Res.string.editor_col_sets).uppercase(),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(SETS_WIDTH),
        )
        Text(
            stringResource(Res.string.editor_col_load).uppercase(),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(LOAD_WIDTH),
        )
        Text(
            stringResource(Res.string.editor_col_rest).uppercase(),
            style = style,
            color = color,
            textAlign = TextAlign.End,
            modifier = Modifier.width(REST_WIDTH),
        )
    }
}

@Composable
private fun NameField(
    state: RoutineEditorUiState,
    onAction: (RoutineEditorAction) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TextField(
        value = state.name,
        onValueChange = { onAction(RoutineEditorAction.SetName(it)) },
        textStyle = MaterialTheme.typography.headlineSmall,
        placeholder = { Text(stringResource(Res.string.routine_name), style = MaterialTheme.typography.headlineSmall) },
        singleLine = true,
        // Says the title can be edited.
        trailingIcon = { Icon(painterResource(Res.drawable.ic_edit), contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        isError = state.nameError != null,
        supportingText = state.nameError?.let { { Text(stringResource(nameErrorText(it))) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAction(RoutineEditorAction.CommitName) }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        // Leaving the field saves the name, like Done does.
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged {
                if (focused && !it.isFocused) onAction(RoutineEditorAction.CommitName)
                focused = it.isFocused
            },
    )
}

// Dense: name and notes, then sets × reps, load and rest in columns.
@Composable
private fun ItemRow(
    item: RoutineItem,
    index: Int,
    count: Int,
    mode: EditorMode,
    selected: Boolean,
    load: LastLoad?,
    onAction: (RoutineEditorAction) -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val t = item.target
    val tracks = item.tracks
    val fields = item.exercise.fields
    val secondary =
        listOfNotNull(
            t.notes.takeIf { it.isNotBlank() },
            t.warmupSets.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.item_warmup_sets, it, it) },
            t.progressStep?.let { stringResource(Res.string.item_progress, formatNumber(it), fields.weightUnit.symbol) },
        ).joinToString(" · ")
    val up = stringResource(Res.string.editor_move_up)
    val down = stringResource(Res.string.editor_move_down)
    Surface(
        onClick = {
            when (mode) {
                EditorMode.Normal -> onAction(RoutineEditorAction.OpenItem(item.id))
                EditorMode.Select -> onAction(RoutineEditorAction.ToggleSelected(item.id))
                EditorMode.Reorder -> Unit
            }
        },
        enabled = mode != EditorMode.Reorder,
        shape = listRowShape(index, count),
        color = if (selected) colors.secondaryContainer else colors.surfaceContainerHigh,
        modifier =
            modifier.fillMaxWidth().padding(horizontal = 12.dp).semantics {
                if (mode == EditorMode.Reorder) {
                    customActions =
                        listOfNotNull(
                            CustomAccessibilityAction(up) { true.also { onAction(RoutineEditorAction.Move(item.id, -1)) } }.takeIf {
                                index >
                                    0
                            },
                            CustomAccessibilityAction(down) {
                                true.also { onAction(RoutineEditorAction.Move(item.id, 1)) }
                            }.takeIf { index < count - 1 },
                        )
                }
            },
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.heightIn(min = 52.dp).padding(start = if (mode == EditorMode.Select) 4.dp else 16.dp, end = 16.dp),
            ) {
                if (mode == EditorMode.Select) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.size(40.dp))
                Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                    Text(fields.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2)
                    if (secondary.isNotEmpty()) {
                        Text(secondary, style = MaterialTheme.typography.bodySmall, color = colors.outline, maxLines = 2)
                    }
                }
                val value = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End)
                val target =
                    when {
                        tracks.reps -> formatRange(t.reps, t.repsMax)
                        tracks.time -> formatClock(t.duration)
                        else -> null
                    }
                Text(
                    target?.let { stringResource(Res.string.item_sets_target, t.sets, it) } ?: "${t.sets}",
                    style = value,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(SETS_WIDTH),
                )
                // The last load lifted: read-only, blank until the exercise was done with one.
                Text(
                    // A bodyweight set's load is what was added: "+10 kg".
                    load?.let { (if (it.bodyweight) "+" else "") + "${formatNumber(it.weight)} ${it.unit.symbol}" }.orEmpty(),
                    style = value,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(LOAD_WIDTH),
                )
                Text(
                    if (t.rest > Duration.ZERO) formatClock(t.rest) else "–",
                    style = value,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(REST_WIDTH),
                )
                if (mode == EditorMode.Reorder) {
                    val drag = stringResource(Res.string.day_drag)
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier =
                            Modifier.size(40.dp).then(handle).semantics {
                                contentDescription =
                                    drag
                            },
                    ) {
                        Icon(painterResource(Res.drawable.ic_drag_indicator), contentDescription = null, tint = colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// "3 × 8–12 reps · rest 2–4 min · +2.5 kg · 2 warm-up sets"
@Composable
internal fun itemSummary(item: RoutineItem): String {
    val t = item.target
    val tracks = item.tracks
    val fields = item.exercise.fields
    val target =
        listOfNotNull(
            stringResource(Res.string.item_reps, formatRange(t.reps, t.repsMax)).takeIf { tracks.reps },
            formatClock(t.duration).takeIf { tracks.time },
            fields.distanceUnit.symbol.takeIf { tracks.distance },
        ).joinToString(" · ").ifEmpty { typeLabel(tracks) }
    return listOfNotNull(
        stringResource(Res.string.item_sets_target, t.sets, target),
        stringResource(Res.string.item_rest, formatDurationRange(t.rest, t.restMax)).takeIf { t.rest > Duration.ZERO },
        t.progressStep?.let { stringResource(Res.string.item_progress, formatNumber(it), fields.weightUnit.symbol) },
        t.warmupSets.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.item_warmup_sets, it, it) },
    ).joinToString(" · ")
}

@Composable
private fun SelectionBar(
    hasSelection: Boolean,
    onAction: (RoutineEditorAction) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        OutlinedButton(
            onClick = { onAction(RoutineEditorAction.ChooseCopyTarget) },
            enabled = hasSelection,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(Res.string.editor_copy_to)) }
        Button(
            onClick = { onAction(RoutineEditorAction.DeleteSelected) },
            enabled = hasSelection,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.weight(1f),
        ) { Text(stringResource(Res.string.routines_delete)) }
    }
}

@Composable
private fun NotesSheet(
    notes: String,
    onAction: (RoutineEditorAction) -> Unit,
) {
    val sheetState = rememberFullSheetState()
    ModalBottomSheet(
        onDismissRequest = { onAction(RoutineEditorAction.CloseNotes) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp).navigationBarsPadding()) {
            Text(
                stringResource(Res.string.editor_notes),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { onAction(RoutineEditorAction.SetNotes(it)) },
                minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun CopyTargetDialog(
    targets: List<RoutineRow>,
    onAction: (RoutineEditorAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(RoutineEditorAction.CancelCopy) },
        title = { Text(stringResource(Res.string.editor_copy_title)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(Res.string.editor_no_other_routines))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    targets.forEachIndexed { index, target ->
                        SegmentedListItem(
                            onClick = { onAction(RoutineEditorAction.CopySelectedTo(target.id)) },
                            shapes = segmentedShapes(index, targets.size),
                            colors = segmentedColors,
                        ) { Text(target.name) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onAction(RoutineEditorAction.CancelCopy) }) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun ReplaceTodayDialog(onAction: (RoutineEditorAction) -> Unit) {
    AlertDialog(
        onDismissRequest = { onAction(RoutineEditorAction.CancelUseToday) },
        title = { Text(stringResource(Res.string.replace_today_title)) },
        text = { Text(stringResource(Res.string.replace_today_body)) },
        confirmButton = {
            TextButton(onClick = { onAction(RoutineEditorAction.ConfirmUseToday) }) { Text(stringResource(Res.string.replace)) }
        },
        dismissButton = {
            TextButton(
                onClick = { onAction(RoutineEditorAction.CancelUseToday) },
            ) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

@Preview
@Composable
private fun RoutineEditorScreenPreview() {
    FitTheme {
        RoutineEditorScreen(
            state =
                RoutineEditorUiState(
                    loading = false,
                    routine = Routine(RoutineId(1), null, "Legs", "Heavy day.", emptyList()),
                    name = "Legs",
                ),
            onAction = {},
            onBack = {},
        )
    }
}
