// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.exercise_count
import app.fit.resources.ic_add
import app.fit.resources.ic_more_vert
import app.fit.resources.more_options
import app.fit.resources.routine_name
import app.fit.resources.routine_name_blank
import app.fit.resources.routine_name_taken
import app.fit.resources.routine_summary
import app.fit.resources.routines_copy
import app.fit.resources.routines_create
import app.fit.resources.routines_delete
import app.fit.resources.routines_deleted
import app.fit.resources.routines_deleted_rest
import app.fit.resources.routines_new
import app.fit.resources.routines_none
import app.fit.routines.RoutineId
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.formatEstimate
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.minutes

// Train's Routines: no top bar of its own.
@Composable
fun RoutinesRoute(
    onOpen: (RoutineId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RoutinesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.onAction(RoutinesAction.OpenedSeen)
            onOpen(it)
        }
    }
    RoutinesScreen(state = state, onAction = viewModel::onAction, onOpen = onOpen, modifier = modifier)
}

@Composable
fun RoutinesScreen(
    state: RoutinesUiState,
    onAction: (RoutinesAction) -> Unit,
    onOpen: (RoutineId) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.newName?.let { NewRoutineDialog(name = it, error = state.nameError, onAction = onAction) }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize()) {
            val count = state.routines.size + 1
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = UndoBarSpace)) {
                if (!state.loading && state.routines.isEmpty()) {
                    item {
                        Text(
                            stringResource(Res.string.routines_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                        )
                    }
                }
                itemsIndexed(state.routines, key = { _, row -> row.id.value }) { index, row ->
                    RoutineRowItem(
                        row = row,
                        index = index,
                        count = count,
                        onOpen = { onOpen(row.id) },
                        onCopy = { onAction(RoutinesAction.Copy(row.id)) },
                        onDelete = { onAction(RoutinesAction.Delete(row.id)) },
                    )
                    Spacer(Modifier.height(2.dp))
                }
                item(key = "new") {
                    SegmentedListItem(
                        onClick = { onAction(RoutinesAction.StartNew) },
                        shapes = segmentedShapes(count - 1, count),
                        colors = segmentedColors,
                        leadingContent = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                    ) { Text(stringResource(Res.string.routines_new)) }
                }
            }
        }
        UndoSnackbar(
            message =
                state.deleted?.let {
                    if (state.restIn.isEmpty()) {
                        stringResource(Res.string.routines_deleted, it)
                    } else {
                        stringResource(Res.string.routines_deleted_rest, it, state.restIn.joinToString(", "))
                    }
                },
            onUndo = { onAction(RoutinesAction.UndoDelete) },
            onDismiss = { onAction(RoutinesAction.UndoSeen) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
internal fun routineSummary(row: RoutineRow): String =
    stringResource(
        Res.string.routine_summary,
        pluralStringResource(Res.plurals.exercise_count, row.exerciseCount, row.exerciseCount),
        formatEstimate(row.estimate),
    )

@Composable
private fun RoutineRowItem(
    row: RoutineRow,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onOpen,
        shapes = segmentedShapes(index, count),
        colors = segmentedColors,
        supportingContent = { Text(routineSummary(row)) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.routines_copy)) }, onClick = {
                        menu = false
                        onCopy()
                    })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.routines_delete)) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        },
    ) { Text(row.name) }
}

@Composable
private fun NewRoutineDialog(
    name: String,
    error: NameError?,
    onAction: (RoutinesAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(RoutinesAction.CancelNew) },
        title = { Text(stringResource(Res.string.routines_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { onAction(RoutinesAction.SetNewName(it)) },
                label = { Text(stringResource(Res.string.routine_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(nameErrorText(it))) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(RoutinesAction.CreateNew) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(RoutinesAction.CreateNew) },
            ) { Text(stringResource(Res.string.routines_create)) }
        },
        dismissButton = { TextButton(onClick = { onAction(RoutinesAction.CancelNew) }) { Text(stringResource(Res.string.cancel)) } },
    )
}

internal fun nameErrorText(error: NameError) =
    when (error) {
        NameError.Blank -> Res.string.routine_name_blank
        NameError.Taken -> Res.string.routine_name_taken
    }

@Preview
@Composable
private fun RoutinesScreenPreview() {
    val rows = listOf(RoutineRow(RoutineId(1), "Monday — upper", 8, 52.minutes), RoutineRow(RoutineId(2), "Legs", 5, 40.minutes))
    FitTheme { RoutinesScreen(state = RoutinesUiState(loading = false, routines = rows), onAction = {}, onOpen = {}) }
}
