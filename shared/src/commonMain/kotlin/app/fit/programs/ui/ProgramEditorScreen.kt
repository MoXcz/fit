// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.programs.Deload
import app.fit.programs.ProgramId
import app.fit.programs.ProgramSchedule
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.ic_check
import app.fit.resources.ic_edit
import app.fit.resources.ok
import app.fit.resources.program_active
import app.fit.resources.program_active_summary
import app.fit.resources.program_clear_start
import app.fit.resources.program_deload_every
import app.fit.resources.program_deload_percent
import app.fit.resources.program_name
import app.fit.resources.program_never
import app.fit.resources.program_not_set
import app.fit.resources.program_notes
import app.fit.resources.program_percent
import app.fit.resources.program_rest
import app.fit.resources.program_schedule
import app.fit.resources.program_starts
import app.fit.resources.program_this_week
import app.fit.resources.program_weeks
import app.fit.resources.program_weeks_count
import app.fit.routines.ui.RoutineRow
import app.fit.routines.ui.routineSummary
import app.fit.routines.ui.sheetFieldColors
import app.fit.ui.BackButton
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.Stepper
import app.fit.ui.formatDate
import app.fit.ui.formatEstimate
import app.fit.ui.rememberFullSheetState
import app.fit.ui.theme.listRowShape
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import app.fit.ui.weekdayName
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ProgramEditorRoute(
    id: ProgramId,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgramEditorViewModel = koinViewModel(key = "program:${id.value}") { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProgramEditorScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// Compact, like the routine editor: the name, whether it's the plan, the week, then weeks and deloads.
@Composable
fun ProgramEditorScreen(
    state: ProgramEditorUiState,
    onAction: (ProgramEditorAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    state.choosing?.let { day -> DaySheet(state = state, weekday = day, onAction = onAction) }
    if (state.pickingStart) StartDialog(state = state, onAction = onAction)
    val program = state.program
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = {},
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        if (program == null) return@Column
        LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "name") { NameField(state = state, onAction = onAction) }
            item(key = "summary") {
                Text(
                    programSummary(ProgramRow(program.id, program.name, program.active, state.trainingDays, state.weekly, state.thisWeek)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
            item(key = "active") {
                ListItem(
                    modifier =
                        Modifier
                            .padding(horizontal = 16.dp)
                            .padding(top = 16.dp)
                            .clip(segmentShape(0, 1))
                            .toggleable(
                                value = program.active,
                                role = Role.Switch,
                                onValueChange = { onAction(ProgramEditorAction.SetActive(it)) },
                            ),
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                    headlineContent = { Text(stringResource(Res.string.program_active)) },
                    supportingContent = { Text(stringResource(Res.string.program_active_summary)) },
                    trailingContent = { Switch(checked = program.active, onCheckedChange = null) },
                )
            }
            item(key = "schedule") { SectionTitle(stringResource(Res.string.program_schedule), modifier = Modifier.padding(start = 4.dp)) }
            itemsIndexed(state.days, key = { _, day -> "day:${day.weekday}" }) { index, day ->
                DayRow(day = day, index = index, count = state.days.size, onClick = { onAction(ProgramEditorAction.OpenDay(day.weekday)) })
            }
            item(key = "weeks") { Column { Weeks(state = state, onAction = onAction) } }
            item(key = "notes") { Column { Notes(state = state, onAction = onAction) } }
        }
    }
}

@Composable
private fun NameField(
    state: ProgramEditorUiState,
    onAction: (ProgramEditorAction) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    TextField(
        value = state.name,
        onValueChange = { onAction(ProgramEditorAction.SetName(it)) },
        textStyle = MaterialTheme.typography.headlineSmall,
        placeholder = { Text(stringResource(Res.string.program_name), style = MaterialTheme.typography.headlineSmall) },
        singleLine = true,
        // Says the title can be edited.
        trailingIcon = { Icon(painterResource(Res.drawable.ic_edit), contentDescription = null, tint = MaterialTheme.colorScheme.outline) },
        isError = state.nameError != null,
        supportingText = state.nameError?.let { { Text(stringResource(programNameError(it))) } },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onAction(ProgramEditorAction.CommitName) }),
        colors =
            TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
        modifier =
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).onFocusChanged {
                if (focused && !it.isFocused) onAction(ProgramEditorAction.CommitName)
                focused = it.isFocused
            },
    )
}

// Dense: the weekday, its routine (or Rest, quieter) and the routine's estimate.
@Composable
private fun DayRow(
    day: ScheduleDay,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp),
            ) {
                Text(
                    weekdayName(day.weekday),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.width(96.dp),
                )
                val routine = day.routine
                Text(
                    routine?.name ?: stringResource(Res.string.program_rest),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (routine != null) FontWeight.Medium else FontWeight.Normal,
                    color = if (routine != null) colors.onSurface else colors.outline,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                routine?.let {
                    Text(
                        "~${formatEstimate(it.estimate)}",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

// Week 1's start, how often a deload week comes and how much it takes off.
@Composable
private fun Weeks(
    state: ProgramEditorUiState,
    onAction: (ProgramEditorAction) -> Unit,
) {
    val program = state.program ?: return
    val every = program.deloadEvery
    val count = if (every != null) 3 else 2
    SectionTitle(stringResource(Res.string.program_weeks), modifier = Modifier.padding(start = 4.dp))
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        SegmentedListItem(
            onClick = { onAction(ProgramEditorAction.OpenStart) },
            shapes = segmentedShapes(0, count),
            colors = segmentedColors,
            supportingContent = weekText(state.thisWeek)?.let { { Text(stringResource(Res.string.program_this_week, it)) } },
            trailingContent = {
                Text(
                    program.startsOn?.let { formatDate(it, state.today) } ?: stringResource(Res.string.program_not_set),
                    style = MaterialTheme.typography.titleMedium,
                )
            },
        ) { Text(stringResource(Res.string.program_starts)) }
        ListItem(
            modifier = Modifier.clip(segmentShape(1, count)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            headlineContent = { Text(stringResource(Res.string.program_deload_every)) },
            trailingContent = {
                Stepper(
                    value =
                        every?.let { pluralStringResource(Res.plurals.program_weeks_count, it, it) }
                            ?: stringResource(Res.string.program_never),
                    canDecrease = every != null,
                    canIncrease = every == null || every < ProgramSchedule.DELOAD_EVERY.last,
                    onStep = { up -> onAction(ProgramEditorAction.StepDeloadEvery(up)) },
                )
            },
        )
        if (every != null) {
            ListItem(
                modifier = Modifier.clip(segmentShape(2, count)),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                headlineContent = { Text(stringResource(Res.string.program_deload_percent)) },
                trailingContent = {
                    Stepper(
                        value = stringResource(Res.string.program_percent, program.deloadPercent),
                        canDecrease = program.deloadPercent > Deload.PERCENTS.first,
                        canIncrease = program.deloadPercent < Deload.PERCENTS.last,
                        onStep = { up -> onAction(ProgramEditorAction.StepDeloadPercent(up)) },
                    )
                },
            )
        }
    }
}

@Composable
private fun Notes(
    state: ProgramEditorUiState,
    onAction: (ProgramEditorAction) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    SectionTitle(stringResource(Res.string.program_notes), modifier = Modifier.padding(start = 4.dp))
    Column(Modifier.padding(horizontal = 16.dp)) {
        OutlinedTextField(
            value = state.notes,
            onValueChange = { onAction(ProgramEditorAction.SetNotes(it)) },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier =
                Modifier.fillMaxWidth().onFocusChanged {
                    if (focused && !it.isFocused) onAction(ProgramEditorAction.CommitNotes)
                    focused = it.isFocused
                },
        )
    }
}

// Rest, then every routine; the day's own is ticked.
@Composable
private fun DaySheet(
    state: ProgramEditorUiState,
    weekday: DayOfWeek,
    onAction: (ProgramEditorAction) -> Unit,
) {
    val current =
        state.days
            .firstOrNull { it.weekday == weekday }
            ?.routine
            ?.id
    ModalBottomSheet(
        onDismissRequest = { onAction(ProgramEditorAction.CloseDay) },
        sheetState = rememberFullSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                weekdayName(weekday),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            val options: List<RoutineRow?> = listOf(null) + state.routines
            options.forEachIndexed { index, row ->
                SegmentedListItem(
                    onClick = { onAction(ProgramEditorAction.ChooseRoutine(row?.id)) },
                    shapes = segmentedShapes(index, options.size),
                    colors = segmentedColors,
                    supportingContent = row?.let { { Text(routineSummary(it)) } },
                    trailingContent =
                        if (row?.id == current) {
                            {
                                Icon(
                                    painterResource(Res.drawable.ic_check),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        } else {
                            null
                        },
                ) { Text(row?.name ?: stringResource(Res.string.program_rest)) }
            }
        }
    }
}

private const val DAY_MILLIS = 86_400_000L

// The picker speaks UTC midnights; only the date is kept.
@Composable
private fun StartDialog(
    state: ProgramEditorUiState,
    onAction: (ProgramEditorAction) -> Unit,
) {
    val start = state.program?.startsOn ?: state.today
    val picker = rememberDatePickerState(initialSelectedDateMillis = start.toEpochDays() * DAY_MILLIS)
    DatePickerDialog(
        onDismissRequest = { onAction(ProgramEditorAction.CloseStart) },
        confirmButton = {
            TextButton(onClick = {
                val picked = picker.selectedDateMillis?.let { LocalDate.fromEpochDays(it / DAY_MILLIS) }
                onAction(ProgramEditorAction.SetStart(picked))
            }) { Text(stringResource(Res.string.ok)) }
        },
        dismissButton = {
            Row {
                TextButton(
                    onClick = { onAction(ProgramEditorAction.SetStart(null)) },
                ) { Text(stringResource(Res.string.program_clear_start)) }
                TextButton(onClick = { onAction(ProgramEditorAction.CloseStart) }) { Text(stringResource(Res.string.cancel)) }
            }
        },
    ) {
        DatePicker(state = picker)
    }
}
