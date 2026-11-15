// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.programs.ProgramId
import app.fit.programs.ProgramWeek
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.deload_week
import app.fit.resources.ic_add
import app.fit.resources.ic_more_vert
import app.fit.resources.more_options
import app.fit.resources.program_copy
import app.fit.resources.program_days
import app.fit.resources.program_delete
import app.fit.resources.program_name
import app.fit.resources.program_name_taken
import app.fit.resources.program_summary
import app.fit.resources.programs_active
import app.fit.resources.programs_deleted
import app.fit.resources.programs_new
import app.fit.resources.programs_none
import app.fit.resources.routine_name_blank
import app.fit.resources.routines_create
import app.fit.resources.week_n
import app.fit.routines.ui.NameError
import app.fit.ui.Pill
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.formatEstimate
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import kotlin.time.Duration.Companion.minutes

// Train's Programs: no top bar of its own.
@Composable
fun ProgramsRoute(
    onOpen: (ProgramId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgramsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        state.opened?.let {
            viewModel.onAction(ProgramsAction.OpenedSeen)
            onOpen(it)
        }
    }
    ProgramsScreen(state = state, onAction = viewModel::onAction, onOpen = onOpen, modifier = modifier)
}

@Composable
fun ProgramsScreen(
    state: ProgramsUiState,
    onAction: (ProgramsAction) -> Unit,
    onOpen: (ProgramId) -> Unit,
    modifier: Modifier = Modifier,
) {
    state.newName?.let { NewProgramDialog(name = it, error = state.nameError, onAction = onAction) }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        val count = state.programs.size + 1
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = UndoBarSpace)) {
            if (!state.loading && state.programs.isEmpty()) {
                item {
                    Text(
                        stringResource(Res.string.programs_none),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            itemsIndexed(state.programs, key = { _, row -> row.id.value }) { index, row ->
                ProgramRowItem(
                    row = row,
                    index = index,
                    count = count,
                    onOpen = { onOpen(row.id) },
                    onCopy = { onAction(ProgramsAction.Copy(row.id)) },
                    onDelete = { onAction(ProgramsAction.Delete(row.id)) },
                )
                Spacer(Modifier.height(2.dp))
            }
            item(key = "new") {
                SegmentedListItem(
                    onClick = { onAction(ProgramsAction.StartNew) },
                    shapes = segmentedShapes(count - 1, count),
                    colors = segmentedColors,
                    leadingContent = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
                ) { Text(stringResource(Res.string.programs_new)) }
            }
        }
        UndoSnackbar(
            message = state.deleted?.let { stringResource(Res.string.programs_deleted, it) },
            onUndo = { onAction(ProgramsAction.UndoDelete) },
            onDismiss = { onAction(ProgramsAction.UndoSeen) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

// "Week 6 · Deload week", or nothing before week 1 or without a start.
@Composable
internal fun weekText(week: ProgramWeek?): String? =
    week?.let {
        listOfNotNull(stringResource(Res.string.week_n, it.week), stringResource(Res.string.deload_week).takeIf { _ -> it.deload })
            .joinToString(" · ")
    }

// "4 days a week · ~200 min a week".
@Composable
internal fun programSummary(row: ProgramRow): String =
    stringResource(
        Res.string.program_summary,
        pluralStringResource(Res.plurals.program_days, row.trainingDays, row.trainingDays),
        formatEstimate(row.weekly),
    )

@Composable
private fun ProgramRowItem(
    row: ProgramRow,
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
        supportingContent = {
            Text(listOfNotNull(programSummary(row), weekText(row.week).takeIf { row.active }).joinToString(" · "))
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(Res.drawable.ic_more_vert), contentDescription = stringResource(Res.string.more_options))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.program_copy)) }, onClick = {
                        menu = false
                        onCopy()
                    })
                    DropdownMenuItem(text = { Text(stringResource(Res.string.program_delete)) }, onClick = {
                        menu = false
                        onDelete()
                    })
                }
            }
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (row.active) Pill(stringResource(Res.string.programs_active))
        }
    }
}

internal fun programNameError(error: NameError): StringResource =
    when (error) {
        NameError.Blank -> Res.string.routine_name_blank
        NameError.Taken -> Res.string.program_name_taken
    }

@Composable
private fun NewProgramDialog(
    name: String,
    error: NameError?,
    onAction: (ProgramsAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(ProgramsAction.CancelNew) },
        title = { Text(stringResource(Res.string.programs_new)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { onAction(ProgramsAction.SetNewName(it)) },
                label = { Text(stringResource(Res.string.program_name)) },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let { { Text(stringResource(programNameError(it))) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(ProgramsAction.CreateNew) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onAction(ProgramsAction.CreateNew) },
            ) { Text(stringResource(Res.string.routines_create)) }
        },
        dismissButton = { TextButton(onClick = { onAction(ProgramsAction.CancelNew) }) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Preview
@Composable
private fun ProgramsScreenPreview() {
    val rows =
        listOf(
            ProgramRow(ProgramId(1), "4 day upper/lower", true, 4, 200.minutes, ProgramWeek(6, true)),
            ProgramRow(ProgramId(2), "Home", false, 2, 60.minutes, null),
        )
    FitTheme { ProgramsScreen(state = ProgramsUiState(loading = false, programs = rows), onAction = {}, onOpen = {}) }
}
