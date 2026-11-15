// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.data_backup
import app.fit.resources.data_delete
import app.fit.resources.data_everything_text
import app.fit.resources.data_everything_title
import app.fit.resources.data_everything_wiped
import app.fit.resources.data_export
import app.fit.resources.data_export_failed
import app.fit.resources.data_export_summary
import app.fit.resources.data_export_too_large
import app.fit.resources.data_exported
import app.fit.resources.data_history_text
import app.fit.resources.data_history_title
import app.fit.resources.data_history_wiped
import app.fit.resources.data_newer_backup
import app.fit.resources.data_not_a_backup
import app.fit.resources.data_restore
import app.fit.resources.data_restore_action
import app.fit.resources.data_restore_failed
import app.fit.resources.data_restore_summary
import app.fit.resources.data_restore_text
import app.fit.resources.data_restore_title
import app.fit.resources.data_restored
import app.fit.resources.data_title
import app.fit.resources.data_too_large
import app.fit.resources.data_unreadable
import app.fit.resources.data_wipe
import app.fit.resources.data_wipe_everything
import app.fit.resources.data_wipe_everything_summary
import app.fit.resources.data_wipe_history
import app.fit.resources.data_wipe_history_summary
import app.fit.ui.BackButton
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.formatDate
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun DataRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DataViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DataScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// Export and restore a backup, and the two wipes. Each row says what it does; the ones that can't
// be undone ask first.
@Composable
fun DataScreen(
    state: DataUiState,
    onAction: (DataAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.confirm?.let { ConfirmDialog(it, onAction) }
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = UndoBarSpace)) {
            ScreenTopBar(
                title = { ScreenTitle(stringResource(Res.string.data_title)) },
                navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            )
            Rows(
                title = Res.string.data_backup,
                rows =
                    listOf(
                        Triple(Res.string.data_export, Res.string.data_export_summary, DataAction.Export),
                        Triple(Res.string.data_restore, Res.string.data_restore_summary, DataAction.Restore),
                    ),
                enabled = !state.busy,
                onAction = onAction,
            )
            Rows(
                title = Res.string.data_delete,
                rows =
                    listOf(
                        Triple(Res.string.data_wipe_history, Res.string.data_wipe_history_summary, DataAction.WipeHistory),
                        Triple(Res.string.data_wipe_everything, Res.string.data_wipe_everything_summary, DataAction.WipeEverything),
                    ),
                enabled = !state.busy,
                onAction = onAction,
            )
        }
        UndoSnackbar(
            message =
                state.notice?.let {
                    stringResource(
                        when (it) {
                            DataNotice.Exported -> Res.string.data_exported
                            DataNotice.Restored -> Res.string.data_restored
                            DataNotice.HistoryWiped -> Res.string.data_history_wiped
                            DataNotice.EverythingWiped -> Res.string.data_everything_wiped
                            DataNotice.NotABackup -> Res.string.data_not_a_backup
                            DataNotice.NewerBackup -> Res.string.data_newer_backup
                            DataNotice.Unreadable -> Res.string.data_unreadable
                            DataNotice.TooLarge -> Res.string.data_too_large
                            DataNotice.ExportFailed -> Res.string.data_export_failed
                            DataNotice.ExportTooLarge -> Res.string.data_export_too_large
                            DataNotice.RestoreFailed -> Res.string.data_restore_failed
                        },
                    )
                },
            onUndo = {},
            onDismiss = { onAction(DataAction.NoticeSeen) },
            canUndo = false,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun Rows(
    title: StringResource,
    rows: List<Triple<StringResource, StringResource, DataAction>>,
    enabled: Boolean,
    onAction: (DataAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        SectionTitle(stringResource(title), modifier = Modifier.padding(start = 16.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            rows.forEachIndexed { index, (label, summary, action) ->
                SegmentedListItem(
                    onClick = { onAction(action) },
                    enabled = enabled,
                    shapes = segmentedShapes(index, rows.size),
                    colors = segmentedColors,
                    supportingContent = { Text(stringResource(summary)) },
                ) { Text(stringResource(label)) }
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    confirm: DataConfirm,
    onAction: (DataAction) -> Unit,
) {
    val (title, text, action) =
        when (confirm) {
            is DataConfirm.Restore -> {
                Triple(
                    stringResource(Res.string.data_restore_title),
                    stringResource(Res.string.data_restore_text, formatDate(confirm.madeOn, withYear = true)),
                    stringResource(Res.string.data_restore_action),
                )
            }

            DataConfirm.WipeHistory -> {
                Triple(
                    stringResource(Res.string.data_history_title),
                    stringResource(Res.string.data_history_text),
                    stringResource(Res.string.data_wipe),
                )
            }

            DataConfirm.WipeEverything -> {
                Triple(
                    stringResource(Res.string.data_everything_title),
                    stringResource(Res.string.data_everything_text),
                    stringResource(Res.string.data_wipe),
                )
            }
        }
    AlertDialog(
        onDismissRequest = { onAction(DataAction.Cancel) },
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onAction(DataAction.Confirm) }) { Text(action, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = { onAction(DataAction.Cancel) }) { Text(stringResource(Res.string.cancel)) } },
    )
}
