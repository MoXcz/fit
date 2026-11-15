// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.fit.core.WeightUnit
import app.fit.day.localTime
import app.fit.history.CalendarMonth
import app.fit.history.ui.MonthCalendar
import app.fit.resources.Res
import app.fit.resources.action_add_exercise
import app.fit.resources.action_choose_routine
import app.fit.resources.bodyweight_empty_deletes
import app.fit.resources.bodyweight_invalid
import app.fit.resources.bodyweight_title
import app.fit.resources.bodyweight_weight
import app.fit.resources.cancel
import app.fit.resources.chooser_add
import app.fit.resources.chooser_none
import app.fit.resources.chooser_replace
import app.fit.resources.copy_none
import app.fit.resources.day_options
import app.fit.resources.day_unnamed
import app.fit.resources.deload_week
import app.fit.resources.details_clear_end
import app.fit.resources.details_end_at_last
import app.fit.resources.details_ended
import app.fit.resources.details_name
import app.fit.resources.details_notes
import app.fit.resources.details_routine_name
import app.fit.resources.details_save
import app.fit.resources.details_save_routine
import app.fit.resources.details_start_after_end
import app.fit.resources.details_started
import app.fit.resources.details_time
import app.fit.resources.edit_none
import app.fit.resources.exercise_count
import app.fit.resources.ic_add
import app.fit.resources.ic_content_copy
import app.fit.resources.ic_delete
import app.fit.resources.ic_edit
import app.fit.resources.ic_flag
import app.fit.resources.ic_playlist_add
import app.fit.resources.ic_replay
import app.fit.resources.ic_save
import app.fit.resources.ic_swap_vert
import app.fit.resources.ok
import app.fit.resources.options_copy_day
import app.fit.resources.options_delete
import app.fit.resources.options_details
import app.fit.resources.options_finish
import app.fit.resources.options_reopen
import app.fit.resources.options_reorder
import app.fit.resources.replace
import app.fit.resources.replace_day_body
import app.fit.resources.replace_day_title
import app.fit.resources.week_n
import app.fit.routines.ui.nameErrorText
import app.fit.routines.ui.routineSummary
import app.fit.routines.ui.sheetFieldColors
import app.fit.ui.ConnectedChoice
import app.fit.ui.SectionTitle
import app.fit.ui.formatDate
import app.fit.ui.formatNumber
import app.fit.ui.formatTime
import app.fit.ui.rememberFullSheetState
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import kotlinx.datetime.LocalTime
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun TodaySheets(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
) {
    when (val sheet = state.sheet) {
        TodaySheet.Options -> OptionsSheet(state = state, onAction = onAction)
        is TodaySheet.ChooseRoutine -> ChooseRoutineSheet(sheet = sheet, populated = state.hasBlocks, onAction = onAction)
        is TodaySheet.CopyDay -> CopyDaySheet(sheet = sheet, onAction = onAction)
        is TodaySheet.Details -> DetailsSheet(sheet = sheet, state = state, onAction = onAction)
        is TodaySheet.Calendar -> state.calendar?.let { CalendarSheet(calendar = it, onAction = onAction) }
        null -> Unit
    }
    state.confirmReplace?.let { row ->
        AlertDialog(
            onDismissRequest = { onAction(TodayAction.CancelReplace) },
            title = { Text(stringResource(Res.string.replace_day_title)) },
            text = { Text(stringResource(Res.string.replace_day_body, row.name)) },
            confirmButton = { TextButton(onClick = { onAction(TodayAction.ConfirmReplace) }) { Text(stringResource(Res.string.replace)) } },
            dismissButton = { TextButton(onClick = { onAction(TodayAction.CancelReplace) }) { Text(stringResource(Res.string.cancel)) } },
        )
    }
    state.timePicker?.let { TimeDialog(edit = it, onAction = onAction) }
    state.saveAs?.let { SaveAsDialog(dialog = it, onAction = onAction) }
}

// Full height, closed with Back or a tap outside, like the routine editor's sheets.
@Composable
private fun TodaySheet(
    title: String,
    onAction: (TodayAction) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onAction(TodayAction.CloseSheet) },
        sheetState = rememberFullSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .imePadding()
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp, bottom = 12.dp))
            content()
        }
    }
}

private data class Option(
    val icon: DrawableResource,
    val label: StringResource,
    val action: TodayAction,
    val danger: Boolean = false,
)

// Grouped: what's on the day, this session, and deleting it.
@Composable
private fun OptionsSheet(
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
) {
    val day = state.day
    val plan =
        buildList {
            add(Option(Res.drawable.ic_add, Res.string.action_add_exercise, TodayAction.OpenPicker))
            add(Option(Res.drawable.ic_playlist_add, Res.string.action_choose_routine, TodayAction.OpenChooser))
            add(Option(Res.drawable.ic_content_copy, Res.string.options_copy_day, TodayAction.OpenCopyDay))
            if (state.hasBlocks) add(Option(Res.drawable.ic_save, Res.string.details_save_routine, TodayAction.OpenSaveAs))
        }
    val session =
        buildList {
            if ((day?.blocks?.size ?: 0) > 1) {
                add(Option(Res.drawable.ic_swap_vert, Res.string.options_reorder, TodayAction.SetMode(TodayMode.Reorder)))
            }
            add(Option(Res.drawable.ic_edit, Res.string.options_details, TodayAction.OpenDetails))
            if (day != null && day.started &&
                !day.finished
            ) {
                add(Option(Res.drawable.ic_flag, Res.string.options_finish, TodayAction.Finish))
            }
            if (day != null && day.finished) add(Option(Res.drawable.ic_replay, Res.string.options_reopen, TodayAction.Reopen))
        }
    val delete = listOfNotNull(day?.let { Option(Res.drawable.ic_delete, Res.string.options_delete, TodayAction.DeleteDay, danger = true) })
    TodaySheet(title = stringResource(Res.string.day_options), onAction = onAction) {
        listOf(plan, session, delete).filter { it.isNotEmpty() }.forEachIndexed { group, options ->
            if (group > 0) Spacer(Modifier.height(12.dp))
            options.forEachIndexed { index, option ->
                val color = if (option.danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                SegmentedListItem(
                    onClick = { onAction(option.action) },
                    shapes = segmentedShapes(index, options.size),
                    colors = segmentedColors,
                    leadingContent = {
                        Icon(
                            painterResource(option.icon),
                            contentDescription = null,
                            tint = if (option.danger) color else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                ) { Text(stringResource(option.label), color = color) }
            }
        }
    }
}

@Composable
private fun SaveAsDialog(
    dialog: SaveAsRoutine,
    onAction: (TodayAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(TodayAction.CancelSaveAs) },
        title = { Text(stringResource(Res.string.details_save_routine)) },
        text = {
            OutlinedTextField(
                value = dialog.name,
                onValueChange = { onAction(TodayAction.SetSaveAsName(it)) },
                label = { Text(stringResource(Res.string.details_routine_name)) },
                singleLine = true,
                isError = dialog.error != null,
                supportingText = dialog.error?.let { { Text(stringResource(nameErrorText(it))) } },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAction(TodayAction.SaveAsRoutine) }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onAction(TodayAction.SaveAsRoutine) }) { Text(stringResource(Res.string.details_save)) } },
        dismissButton = { TextButton(onClick = { onAction(TodayAction.CancelSaveAs) }) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Composable
private fun ChooseRoutineSheet(
    sheet: TodaySheet.ChooseRoutine,
    populated: Boolean,
    onAction: (TodayAction) -> Unit,
) {
    TodaySheet(title = stringResource(Res.string.action_choose_routine), onAction = onAction) {
        if (populated) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            ) {
                listOf(true to Res.string.chooser_replace, false to Res.string.chooser_add).forEachIndexed { i, (replace, label) ->
                    ToggleButton(
                        checked = sheet.replace == replace,
                        onCheckedChange = { onAction(TodayAction.SetReplace(replace)) },
                        shapes =
                            if (i ==
                                0
                            ) {
                                ButtonGroupDefaults.connectedLeadingButtonShapes()
                            } else {
                                ButtonGroupDefaults.connectedTrailingButtonShapes()
                            },
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(label)) }
                }
            }
        }
        if (sheet.routines.isEmpty()) EmptyLine(stringResource(Res.string.chooser_none))
        sheet.routines.forEachIndexed { index, row ->
            SegmentedListItem(
                onClick = { onAction(TodayAction.ChooseRoutine(row.id)) },
                shapes = segmentedShapes(index, sheet.routines.size),
                colors = segmentedColors,
                supportingContent = { Text(routineSummary(row)) },
            ) { Text(row.name) }
        }
    }
}

@Composable
private fun CopyDaySheet(
    sheet: TodaySheet.CopyDay,
    onAction: (TodayAction) -> Unit,
) {
    TodaySheet(title = stringResource(Res.string.options_copy_day), onAction = onAction) {
        if (sheet.days.isEmpty()) EmptyLine(stringResource(Res.string.copy_none))
        val unnamed = stringResource(Res.string.day_unnamed)
        sheet.days.forEachIndexed { index, day ->
            SegmentedListItem(
                onClick = { onAction(TodayAction.CopyDay(day.date)) },
                shapes = segmentedShapes(index, sheet.days.size),
                colors = segmentedColors,
                supportingContent = {
                    Text(
                        "${day.name.ifBlank { unnamed }} · ${pluralStringResource(
                            Res.plurals.exercise_count,
                            day.blocks.size,
                            day.blocks.size,
                        )}",
                    )
                },
            ) { Text(formatDate(day.date)) }
        }
    }
}

@Composable
private fun DetailsSheet(
    sheet: TodaySheet.Details,
    state: TodayUiState,
    onAction: (TodayAction) -> Unit,
) {
    val day = state.day
    val zone = state.zone
    TodaySheet(title = stringResource(Res.string.options_details), onAction = onAction) {
        // Which program week this day is in, and whether its loads are cut.
        state.plan?.let { plan ->
            Text(
                listOfNotNull(
                    plan.program.name,
                    plan.week?.let { stringResource(Res.string.week_n, it.week) },
                    stringResource(Res.string.deload_week).takeIf { state.deload },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
        }
        OutlinedTextField(
            value = sheet.name,
            onValueChange = { onAction(TodayAction.SetName(it)) },
            label = { Text(stringResource(Res.string.details_name)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = sheet.notes,
            onValueChange = { onAction(TodayAction.SetNotes(it)) },
            label = { Text(stringResource(Res.string.details_notes)) },
            minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        SectionTitle(stringResource(Res.string.bodyweight_title))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = sheet.bodyWeight,
                onValueChange = { onAction(TodayAction.SetBodyWeight(it)) },
                label = { Text(stringResource(Res.string.bodyweight_weight)) },
                // The reading the day has without one of its own.
                placeholder =
                    sheet.earlier?.let { earlier ->
                        { Text("${formatNumber(earlier.weight)} (${formatDate(earlier.date)})") }
                    },
                isError = sheet.bodyWeightInvalid,
                supportingText = {
                    Text(
                        stringResource(if (sheet.bodyWeightInvalid) Res.string.bodyweight_invalid else Res.string.bodyweight_empty_deletes),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                shape = MaterialTheme.shapes.large,
                colors = sheetFieldColors(),
                modifier = Modifier.weight(1f),
            )
            ConnectedChoice(
                options = WeightUnit.entries,
                selected = sheet.bodyWeightUnit,
                optionLabel = { it.symbol },
                onSelect = { onAction(TodayAction.SetBodyWeightUnit(it)) },
                modifier = Modifier.width(128.dp).padding(bottom = 20.dp),
            )
        }

        SectionTitle(stringResource(Res.string.details_time))
        val none = stringResource(Res.string.edit_none)
        TimeRow(
            label = stringResource(Res.string.details_started),
            value = day?.startedAt?.let { formatTime(it.localTime(zone)) } ?: none,
            index = 0,
            onClick = { onAction(TodayAction.EditTime(TimeField.Start)) },
        )
        TimeRow(
            label = stringResource(Res.string.details_ended),
            value = day?.finishedAt?.let { formatTime(it.localTime(zone)) } ?: none,
            index = 1,
            enabled = day?.started == true,
            onClick = { onAction(TodayAction.EditTime(TimeField.End)) },
        )
        day?.lastDoneAt?.let { last ->
            FilledTonalButton(
                onClick = { onAction(TodayAction.EndAtLastSet) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text(stringResource(Res.string.details_end_at_last, formatTime(last.localTime(zone)))) }
        }
        if (day?.finished == true) {
            TextButton(onClick = { onAction(TodayAction.ClearEnd) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.details_clear_end))
            }
        }
    }
}

@Composable
private fun TimeRow(
    label: String,
    value: String,
    index: Int,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    SegmentedListItem(
        onClick = onClick,
        enabled = enabled,
        shapes = segmentedShapes(index, 2),
        colors = segmentedColors,
        trailingContent = { Text(value, style = MaterialTheme.typography.titleMedium) },
    ) { Text(label) }
}

@Composable
private fun TimeDialog(
    edit: TimeEdit,
    onAction: (TodayAction) -> Unit,
) {
    val picker = rememberTimePickerState(initialHour = edit.initial.hour, initialMinute = edit.initial.minute)
    TimePickerDialog(
        onDismissRequest = { onAction(TodayAction.CloseTimePicker) },
        title = {
            Text(stringResource(if (edit.field == TimeField.Start) Res.string.details_started else Res.string.details_ended))
        },
        confirmButton = {
            TextButton(onClick = { onAction(TodayAction.SetTime(LocalTime(picker.hour, picker.minute))) }) {
                Text(stringResource(Res.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = { onAction(TodayAction.CloseTimePicker) }) { Text(stringResource(Res.string.cancel)) } },
    ) {
        TimePicker(state = picker)
        if (edit.startAfterEnd) {
            Text(
                stringResource(Res.string.details_start_after_end),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

// A cell opens its day here; the sheet closes with it.
@Composable
private fun CalendarSheet(
    calendar: CalendarMonth,
    onAction: (TodayAction) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onAction(TodayAction.CloseSheet) },
        sheetState = rememberFullSheetState(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        MonthCalendar(
            month = calendar.month,
            weeks = calendar.weeks,
            onPrevious = { onAction(TodayAction.ShowMonth(-1)) },
            onNext = { onAction(TodayAction.ShowMonth(1)) },
            onSelect = { onAction(TodayAction.Select(it)) },
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 16.dp).navigationBarsPadding(),
        )
    }
}

@Composable
private fun EmptyLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
