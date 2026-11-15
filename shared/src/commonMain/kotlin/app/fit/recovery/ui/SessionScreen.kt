// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.content.Source
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedStep
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.ic_open_in_new
import app.fit.resources.ic_play_large
import app.fit.resources.ok
import app.fit.resources.recover_delete
import app.fit.resources.recover_deleted
import app.fit.resources.recover_gone
import app.fit.resources.recover_kind
import app.fit.resources.recover_notes
import app.fit.resources.recover_pin
import app.fit.resources.recover_pinned
import app.fit.resources.recover_sources
import app.fit.resources.recover_start
import app.fit.resources.recover_steps
import app.fit.resources.recover_unpin
import app.fit.resources.reminder_allow
import app.fit.resources.reminder_blocked
import app.fit.resources.reminder_pick
import app.fit.resources.reminder_time
import app.fit.resources.reminder_title
import app.fit.resources.undo_failed
import app.fit.ui.BackButton
import app.fit.ui.MarkdownText
import app.fit.ui.MenuItem
import app.fit.ui.OverflowMenu
import app.fit.ui.Pill
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.UndoSnackbar
import app.fit.ui.formatTime
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import app.fit.ui.uses24HourClock
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SessionRoute(
    id: Long,
    onBack: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionViewModel = koinViewModel(key = "session:$id") { parametersOf(id) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        if (state.opened) {
            viewModel.onAction(SessionAction.OpenedSeen)
            onOpenPlayer()
        }
    }
    LaunchedEffect(state.closed) { if (state.closed) onBack() }
    LifecycleResumeEffect(Unit) {
        viewModel.onAction(SessionAction.Refresh)
        onPauseOrDispose {}
    }
    SessionScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// One session: Start in the card, its steps, notes and sources; Pin in the bar.
@Composable
fun SessionScreen(
    state: SessionUiState,
    onAction: (SessionAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        SessionPage(state, onAction, onBack)
        UndoSnackbar(
            message =
                when {
                    state.undoFailed -> stringResource(Res.string.undo_failed)
                    else -> state.deleted?.let { stringResource(Res.string.recover_deleted, it) }
                },
            onUndo = { onAction(SessionAction.UndoDelete) },
            onDismiss = { onAction(SessionAction.UndoSeen) },
            canUndo = !state.undoFailed,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
    if (state.pickingTime) ReminderTimeDialog(state.reminderTime, onPicked = { onAction(SessionAction.TimePicked(it)) })
}

// The daily reminder: a switch, and its time once it's on. Refused notifications are said here,
// with the way to allow them.
@Composable
private fun ReminderRow(
    state: SessionUiState,
    onAction: (SessionAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = segmentShape(0, 1), color = colors.surfaceBright, modifier = modifier.fillMaxWidth()) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .toggleable(
                            value = state.reminderOn,
                            role = Role.Switch,
                            onValueChange = { onAction(SessionAction.SetReminder(it)) },
                        ).heightIn(min = 56.dp)
                        .padding(start = 16.dp, end = 16.dp),
            ) {
                Text(stringResource(Res.string.reminder_title), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                if (state.reminderOn) {
                    val time = formatTime(state.reminderTime)
                    val label = stringResource(Res.string.reminder_time, time)
                    TextButton(
                        onClick = { onAction(SessionAction.PickTime) },
                        modifier = Modifier.padding(end = 4.dp).semantics { contentDescription = label },
                    ) { Text(time, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")) }
                }
                Switch(checked = state.reminderOn, onCheckedChange = null)
            }
            if (state.reminderBlocked) {
                HorizontalDivider(color = colors.outlineVariant)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 8.dp)) {
                    Text(
                        stringResource(Res.string.reminder_blocked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    )
                    TextButton(onClick = { onAction(SessionAction.AllowNotifications) }) { Text(stringResource(Res.string.reminder_allow)) }
                }
            }
        }
    }
}

@Composable
private fun ReminderTimeDialog(
    time: LocalTime,
    onPicked: (LocalTime?) -> Unit,
) {
    val picker = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = uses24HourClock())
    TimePickerDialog(
        onDismissRequest = { onPicked(null) },
        title = { Text(stringResource(Res.string.reminder_pick)) },
        confirmButton = {
            TextButton(onClick = { onPicked(LocalTime(picker.hour, picker.minute)) }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = { TextButton(onClick = { onPicked(null) }) { Text(stringResource(Res.string.cancel)) } },
    ) { TimePicker(state = picker) }
}

@Composable
private fun SessionPage(
    state: SessionUiState,
    onAction: (SessionAction) -> Unit,
    onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val plan = state.plan
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = {},
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            actions = {
                if (plan != null) {
                    OverflowMenu(
                        listOf(
                            MenuItem(
                                stringResource(if (plan.pinned) Res.string.recover_unpin else Res.string.recover_pin),
                                { onAction(SessionAction.TogglePin) },
                            ),
                            MenuItem(stringResource(Res.string.recover_delete), { onAction(SessionAction.Delete) }),
                        ),
                    )
                }
            },
        )
        if (state.loading) return@Column
        if (plan == null) {
            // Just deleted: the Undo offer says so.
            if (state.deleted == null) {
                Text(
                    stringResource(Res.string.recover_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero") {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Column(
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(Res.string.recover_kind),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            plan.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = LocalAppFonts.current.title,
                            color = colors.onSurface,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(planMeta(plan), style = MaterialTheme.typography.bodyMedium)
                            if (plan.pinned) Pill(stringResource(Res.string.recover_pinned))
                        }
                        Button(
                            onClick = { onAction(SessionAction.Start) },
                            shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(64.dp),
                        ) {
                            Icon(painterResource(Res.drawable.ic_play_large), contentDescription = null, modifier = Modifier.size(24.dp))
                            Text(
                                stringResource(Res.string.recover_start),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                    }
                }
            }
            // Recovery sessions only: a lesson's drill has no reminder.
            if (plan.kind == GuidedKind.Recovery) {
                item(
                    key = "reminder",
                ) { ReminderRow(state, onAction, modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp)) }
            }
            item(key = "steps-title") { SectionTitle(stringResource(Res.string.recover_steps), modifier = Modifier.padding(start = 16.dp)) }
            itemsIndexed(plan.steps) { index, step -> StepRow(step, index, plan.steps.size) }
            if (state.notes.isNotEmpty()) {
                item(key = "notes") {
                    Column {
                        SectionTitle(stringResource(Res.string.recover_notes), modifier = Modifier.padding(start = 16.dp))
                        MarkdownText(
                            state.notes,
                            images = state.images,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .clip(segmentShape(0, 1))
                                    .background(colors.surfaceBright)
                                    .padding(16.dp),
                        )
                    }
                }
            }
            if (plan.sources.isNotEmpty()) {
                item(
                    key = "sources-title",
                ) { SectionTitle(stringResource(Res.string.recover_sources), modifier = Modifier.padding(start = 16.dp)) }
                itemsIndexed(plan.sources) { index, source -> SourceRow(source, index, plan.sources.size) }
            }
        }
    }
}

// A step of a session or a drill: its name, then its amount.
@Composable
internal fun StepRow(
    step: GuidedStep,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp),
            ) {
                Text(
                    step.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                )
                Text(
                    stepAmount(step),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

// A source opens in the browser; one without an address is only named.
@Composable
internal fun SourceRow(
    source: Source,
    index: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val url = source.url
    SegmentedListItem(
        onClick = { if (url != null) uriHandler.openUri(url) },
        enabled = url != null,
        shapes = segmentedShapes(index, count),
        colors = segmentedColors,
        supportingContent = url?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        trailingContent = url?.let { { Icon(painterResource(Res.drawable.ic_open_in_new), contentDescription = null) } },
        modifier = modifier.padding(horizontal = 12.dp),
    ) { Text(source.title, maxLines = 2) }
}
