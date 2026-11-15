// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.content.ContentKind
import app.fit.content.ImportItem
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.import_changed
import app.fit.resources.import_choose
import app.fit.resources.import_choose_other
import app.fit.resources.import_confirm_text
import app.fit.resources.import_confirm_title
import app.fit.resources.import_done
import app.fit.resources.import_done_images
import app.fit.resources.import_hint
import app.fit.resources.import_images
import app.fit.resources.import_images_new
import app.fit.resources.import_images_replaced
import app.fit.resources.import_items
import app.fit.resources.import_kind_exercises
import app.fit.resources.import_kind_lessons
import app.fit.resources.import_kind_programs
import app.fit.resources.import_kind_recovery
import app.fit.resources.import_kind_routines
import app.fit.resources.import_new
import app.fit.resources.import_only_images
import app.fit.resources.import_problems
import app.fit.resources.import_problems_hint
import app.fit.resources.import_replace
import app.fit.resources.import_replaces
import app.fit.resources.import_title
import app.fit.resources.import_too_large
import app.fit.resources.import_unreadable
import app.fit.ui.BackButton
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.theme.listRowShape
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ImportRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    // The inbox id of a shared file to open on; empty when opened from More.
    share: String = "",
    viewModel: ImportViewModel = koinViewModel(key = "import:$share") { parametersOf(share) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ImportScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// Choose a file, see what it holds (or what's wrong with it), import it whole.
@Composable
fun ImportScreen(
    state: ImportUiState,
    onAction: (ImportAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    if (state.confirming) {
        AlertDialog(
            onDismissRequest = { onAction(ImportAction.CancelReplace) },
            title = { Text(stringResource(Res.string.import_confirm_title)) },
            text = { Text(pluralStringResource(Res.plurals.import_confirm_text, state.replaces, state.replaces)) },
            confirmButton = {
                TextButton(onClick = { onAction(ImportAction.ConfirmReplace) }) { Text(stringResource(Res.string.import_replace)) }
            },
            dismissButton = { TextButton(onClick = { onAction(ImportAction.CancelReplace) }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = { ScreenTitle(stringResource(Res.string.import_title)) },
                navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            )
            LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = UndoBarSpace)) {
                if (state.file == null) {
                    item(key = "hint") {
                        Text(
                            stringResource(Res.string.import_hint),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 28.dp, vertical = 12.dp),
                        )
                    }
                    item(key = "choose") {
                        Button(
                            onClick = { onAction(ImportAction.Choose) },
                            enabled = !state.busy,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(56.dp),
                        ) { Text(stringResource(Res.string.import_choose), style = MaterialTheme.typography.titleMedium) }
                    }
                    return@LazyColumn
                }
                item(key = "file") {
                    Text(
                        state.file,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                    )
                }
                if (state.problems.isNotEmpty()) {
                    item(key = "problems-title") {
                        Column(Modifier.padding(horizontal = 28.dp)) {
                            Text(
                                pluralStringResource(Res.plurals.import_problems, state.problems.size, state.problems.size),
                                style = MaterialTheme.typography.titleSmall,
                                color = colors.error,
                            )
                            Text(
                                stringResource(Res.string.import_problems_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp),
                            )
                        }
                    }
                    itemsIndexed(state.problems) { index, problem ->
                        Surface(
                            shape = listRowShape(index, state.problems.size),
                            color = colors.surfaceContainerHigh,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                        ) {
                            Text(
                                problem,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                    }
                }
                state.groups.forEach { (kind, items) ->
                    item(key = "kind-$kind") { SectionTitle(kindTitle(kind), modifier = Modifier.padding(start = 16.dp)) }
                    itemsIndexed(items, key = { _, item -> "$kind-${item.id}" }) { index, item -> ItemRow(item, index, items.size) }
                }
                if (state.newImages + state.replacedImages > 0) {
                    item(key = "images") {
                        Column {
                            SectionTitle(stringResource(Res.string.import_images), modifier = Modifier.padding(start = 16.dp))
                            val parts =
                                listOfNotNull(
                                    state.newImages.takeIf { it > 0 }?.let { pluralStringResource(Res.plurals.import_images_new, it, it) },
                                    state.replacedImages.takeIf { it > 0 }?.let {
                                        pluralStringResource(Res.plurals.import_images_replaced, it, it)
                                    },
                                )
                            Text(
                                parts.joinToString(" · "),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp)
                                        .clip(segmentShape(0, 1))
                                        .background(colors.surfaceContainerHigh)
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                            )
                        }
                    }
                }
                item(key = "actions") {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (state.problems.isEmpty()) {
                            val count = state.groups.sumOf { it.second.size }
                            Button(
                                onClick = { onAction(ImportAction.Import) },
                                enabled = state.canImport,
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
                                modifier = Modifier.fillMaxWidth().height(56.dp),
                            ) {
                                Text(
                                    if (count == 0) {
                                        stringResource(Res.string.import_only_images)
                                    } else {
                                        pluralStringResource(Res.plurals.import_items, count, count)
                                    },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        OutlinedButton(
                            onClick = { onAction(ImportAction.Choose) },
                            enabled = !state.busy,
                            shapes = ButtonDefaults.shapes(),
                            modifier = Modifier.fillMaxWidth().height(48.dp),
                        ) { Text(stringResource(Res.string.import_choose_other)) }
                    }
                }
            }
        }
        UndoSnackbar(
            message =
                when (val notice = state.notice) {
                    is ImportNotice.Imported -> {
                        if (notice.items == 0) {
                            pluralStringResource(Res.plurals.import_done_images, notice.images, notice.images)
                        } else {
                            pluralStringResource(Res.plurals.import_done, notice.items, notice.items)
                        }
                    }

                    ImportNotice.Unreadable -> {
                        stringResource(Res.string.import_unreadable)
                    }

                    ImportNotice.TooLarge -> {
                        stringResource(Res.string.import_too_large)
                    }

                    ImportNotice.Changed -> {
                        stringResource(Res.string.import_changed)
                    }

                    null -> {
                        null
                    }
                },
            onUndo = {},
            onDismiss = { onAction(ImportAction.NoticeSeen) },
            canUndo = false,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun kindTitle(kind: ContentKind): String =
    stringResource(
        when (kind) {
            ContentKind.Exercise -> Res.string.import_kind_exercises
            ContentKind.Routine -> Res.string.import_kind_routines
            ContentKind.Program -> Res.string.import_kind_programs
            ContentKind.Recovery -> Res.string.import_kind_recovery
            ContentKind.Lesson -> Res.string.import_kind_lessons
        },
    )

// An item's name, and whether it's new or which one in the app it replaces.
@Composable
private fun ItemRow(
    item: ImportItem,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 2.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            val replaces = item.replaces
            Text(
                if (replaces == null) stringResource(Res.string.import_new) else stringResource(Res.string.import_replaces, replaces),
                style = MaterialTheme.typography.bodyMedium,
                color = if (replaces == null) colors.onSurfaceVariant else colors.tertiary,
            )
        }
    }
}
