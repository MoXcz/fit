// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.DefaultField
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.RefKind
import app.fit.exercises.Sides
import app.fit.exercises.canStep
import app.fit.resources.Res
import app.fit.resources.edit_add_ref
import app.fit.resources.edit_each_side_summary
import app.fit.resources.edit_equipment_summary
import app.fit.resources.edit_name
import app.fit.resources.edit_no_category
import app.fit.resources.edit_none
import app.fit.resources.edit_ref_title
import app.fit.resources.edit_ref_url
import app.fit.resources.edit_remove_ref
import app.fit.resources.edit_save
import app.fit.resources.edit_title
import app.fit.resources.edit_tracks
import app.fit.resources.edit_video
import app.fit.resources.error_bad_url
import app.fit.resources.error_name_blank
import app.fit.resources.error_name_taken
import app.fit.resources.error_nothing_tracked
import app.fit.resources.exercise_category
import app.fit.resources.exercise_defaults
import app.fit.resources.exercise_duration
import app.fit.resources.exercise_each_side
import app.fit.resources.exercise_equipment
import app.fit.resources.exercise_links
import app.fit.resources.exercise_not_found
import app.fit.resources.exercise_notes
import app.fit.resources.exercise_reps
import app.fit.resources.exercise_reps_max
import app.fit.resources.exercise_rest
import app.fit.resources.exercise_rest_max
import app.fit.resources.exercise_set_time
import app.fit.resources.exercise_sets
import app.fit.resources.exercise_units
import app.fit.resources.exercise_weight
import app.fit.resources.ic_check
import app.fit.resources.ic_close
import app.fit.ui.BackButton
import app.fit.ui.ChoiceRow
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.Stepper
import app.fit.ui.TopBarAction
import app.fit.ui.formatClock
import app.fit.ui.formatNumber
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExerciseEditRoute(
    id: ExerciseId,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseEditViewModel = koinViewModel(key = "exercise-edit:${id.value}") { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.onAction(ExerciseEditAction.SavedSeen)
            onDone()
        }
    }
    ExerciseEditScreen(state = state, onAction = viewModel::onAction, onBack = onDone, modifier = modifier)
}

@Composable
fun ExerciseEditScreen(
    state: ExerciseEditUiState,
    onAction: (ExerciseEditAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.edit_title)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            actions = {
                TopBarAction(
                    icon = Res.drawable.ic_check,
                    description = stringResource(Res.string.edit_save),
                    onClick = { onAction(ExerciseEditAction.Save) },
                    enabled = state.fields != null && !state.saving,
                )
            },
        )
        val fields = state.fields
        when {
            fields != null -> {
                EditForm(fields = fields, state = state, onAction = onAction)
            }

            state.error == EditError.NotFound -> {
                Text(
                    stringResource(Res.string.exercise_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
        }
    }
}

@Composable
private fun EditForm(
    fields: ExerciseFields,
    state: ExerciseEditUiState,
    onAction: (ExerciseEditAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val nameError =
            when (state.error) {
                EditError.NameBlank -> Res.string.error_name_blank
                EditError.NameTaken -> Res.string.error_name_taken
                else -> null
            }
        OutlinedTextField(
            value = fields.name,
            onValueChange = { onAction(ExerciseEditAction.SetName(it)) },
            label = { Text(stringResource(Res.string.edit_name)) },
            singleLine = true,
            isError = nameError != null,
            supportingText = nameError?.let { { Text(stringResource(it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        CategoryField(
            category = fields.category,
            categories = state.categories,
            onSelect = { onAction(ExerciseEditAction.SetCategory(it)) },
        )

        SectionTitle(stringResource(Res.string.edit_tracks))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            Metric.entries.forEach { metric ->
                FilterChip(
                    selected = fields.tracks.has(metric),
                    onClick = { onAction(ExerciseEditAction.ToggleMetric(metric)) },
                    label = { Text(stringResource(metric.label)) },
                )
            }
        }
        if (state.error == EditError.NothingTracked) ErrorText(stringResource(Res.string.error_nothing_tracked))
        val each = fields.sides == Sides.Each
        val unitRows = listOf(fields.tracks.weight, fields.tracks.distance).count { it }
        SegmentedListItem(
            checked = each,
            onCheckedChange = { onAction(ExerciseEditAction.SetEachSide(it)) },
            shapes = segmentedShapes(0, 1 + unitRows),
            colors = segmentedColors,
            supportingContent = { Text(stringResource(Res.string.edit_each_side_summary)) },
            trailingContent = {
                Switch(
                    checked = each,
                    onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedIconColor = MaterialTheme.colorScheme.primary),
                    thumbContent = {
                        if (each) {
                            Icon(
                                painterResource(Res.drawable.ic_check),
                                contentDescription = null,
                                modifier = Modifier.size(SwitchDefaults.IconSize),
                            )
                        }
                    },
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text(stringResource(Res.string.exercise_each_side)) }
        if (fields.tracks.weight) {
            ChoiceRow(
                label = stringResource(Res.string.exercise_units),
                options = WeightUnit.entries,
                selected = fields.weightUnit,
                optionLabel = { it.symbol },
                onSelect = { onAction(ExerciseEditAction.SetWeightUnit(it)) },
                index = 1,
                count = 1 + unitRows,
            )
        }
        if (fields.tracks.distance) {
            ChoiceRow(
                label = stringResource(Res.string.exercise_units),
                options = DistanceUnit.entries,
                selected = fields.distanceUnit,
                optionLabel = { it.symbol },
                onSelect = { onAction(ExerciseEditAction.SetDistanceUnit(it)) },
                index = unitRows,
                count = 1 + unitRows,
            )
        }

        // One or none: tapping the chosen one again clears it.
        SectionTitle(stringResource(Res.string.exercise_equipment))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
            Equipment.entries.forEach { equipment ->
                val selected = fields.equipment == equipment
                FilterChip(
                    selected = selected,
                    onClick = { onAction(ExerciseEditAction.SetEquipment(equipment.takeIf { !selected })) },
                    label = { Text(stringResource(equipment.label)) },
                )
            }
        }
        Text(
            stringResource(Res.string.edit_equipment_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        SectionTitle(stringResource(Res.string.exercise_defaults))
        DefaultsRows(fields = fields, onAction = onAction)

        SectionTitle(stringResource(Res.string.exercise_notes))
        OutlinedTextField(
            value = fields.notes,
            onValueChange = { onAction(ExerciseEditAction.SetNotes(it)) },
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )

        SectionTitle(stringResource(Res.string.exercise_links))
        LinksEditor(fields = fields, state = state, onAction = onAction)
    }
}

@Composable
private fun CategoryField(
    category: String,
    categories: List<String>,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(Res.string.edit_no_category)
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = category.ifBlank { none },
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(Res.string.exercise_category)) },
            shape = MaterialTheme.shapes.large,
            colors = fieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            (listOf("") + categories).forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.ifBlank { none }) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun DefaultsRows(
    fields: ExerciseFields,
    onAction: (ExerciseEditAction) -> Unit,
) {
    val d = fields.defaults
    val none = stringResource(Res.string.edit_none)
    val rows =
        buildList {
            add(Triple(DefaultField.Sets, Res.string.exercise_sets, "${d.sets}"))
            if (fields.tracks.reps) {
                add(Triple(DefaultField.Reps, Res.string.exercise_reps, "${d.reps}"))
                add(Triple(DefaultField.RepsMax, Res.string.exercise_reps_max, d.repsMax?.toString() ?: none))
            }
            add(
                Triple(
                    DefaultField.Duration,
                    if (fields.tracks.time) Res.string.exercise_duration else Res.string.exercise_set_time,
                    formatClock(d.duration),
                ),
            )
            add(Triple(DefaultField.Rest, Res.string.exercise_rest, formatClock(d.rest)))
            add(Triple(DefaultField.RestMax, Res.string.exercise_rest_max, d.restMax?.let(::formatClock) ?: none))
            if (fields.tracks.weight) {
                add(Triple(DefaultField.Weight, Res.string.exercise_weight, "${formatNumber(d.weight)} ${fields.weightUnit.symbol}"))
            }
        }
    rows.forEachIndexed { index, (field, label, value) ->
        ListItem(
            modifier = Modifier.clip(segmentShape(index, rows.size)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            headlineContent = { Text(stringResource(label)) },
            trailingContent = {
                Stepper(
                    value = value,
                    canDecrease = d.canStep(field, up = false, fields.weightUnit),
                    canIncrease = d.canStep(field, up = true, fields.weightUnit),
                    onStep = { up -> onAction(ExerciseEditAction.Step(field, up)) },
                )
            },
        )
    }
}

@Composable
private fun LinksEditor(
    fields: ExerciseFields,
    state: ExerciseEditUiState,
    onAction: (ExerciseEditAction) -> Unit,
) {
    OutlinedTextField(
        value = fields.videoUrl,
        onValueChange = { onAction(ExerciseEditAction.SetVideoUrl(it)) },
        label = { Text(stringResource(Res.string.edit_video)) },
        singleLine = true,
        isError = state.error == EditError.BadVideoUrl,
        supportingText = if (state.error == EditError.BadVideoUrl) ({ Text(stringResource(Res.string.error_bad_url)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = MaterialTheme.shapes.large,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    fields.refs.forEachIndexed { index, ref ->
        ListItem(
            modifier = Modifier.padding(top = if (index == 0) 8.dp else 0.dp).clip(segmentShape(index, fields.refs.size)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            headlineContent = { Text(ref.title.ifBlank { ref.url }, maxLines = 1) },
            supportingContent = { Text(stringResource(ref.kind.label)) },
            trailingContent = {
                IconButton(onClick = { onAction(ExerciseEditAction.RemoveRef(index)) }) {
                    Icon(painterResource(Res.drawable.ic_close), contentDescription = stringResource(Res.string.edit_remove_ref))
                }
            },
        )
    }
    val newRef = state.newRef
    Row(
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        modifier = Modifier.padding(top = 12.dp),
    ) {
        RefKind.entries.forEachIndexed { i, kind ->
            ToggleButton(
                checked = newRef.kind == kind,
                onCheckedChange = { onAction(ExerciseEditAction.EditNewRef(newRef.copy(kind = kind))) },
                shapes =
                    when (i) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        RefKind.entries.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(kind.label), maxLines = 1) }
        }
    }
    OutlinedTextField(
        value = newRef.title,
        onValueChange = { onAction(ExerciseEditAction.EditNewRef(newRef.copy(title = it))) },
        label = { Text(stringResource(Res.string.edit_ref_title)) },
        singleLine = true,
        shape = MaterialTheme.shapes.large,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = newRef.url,
        onValueChange = { onAction(ExerciseEditAction.EditNewRef(newRef.copy(url = it))) },
        label = { Text(stringResource(Res.string.edit_ref_url)) },
        singleLine = true,
        isError = state.error == EditError.BadRefUrl,
        supportingText = if (state.error == EditError.BadRefUrl) ({ Text(stringResource(Res.string.error_bad_url)) }) else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        shape = MaterialTheme.shapes.large,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
    FilledTonalButton(
        onClick = { onAction(ExerciseEditAction.AddRef) },
        enabled = newRef.url.isNotBlank(),
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.padding(top = 4.dp),
    ) { Text(stringResource(Res.string.edit_add_ref)) }
}

@Composable
private fun ErrorText(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

@Composable
private fun fieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceBright,
    )

@Preview
@Composable
private fun ExerciseEditScreenPreview() {
    val fields = ExerciseFields(name = "Bench press", category = "Chest", defaults = ExerciseDefaults(sets = 4, reps = 8, repsMax = 12))
    FitTheme { ExerciseEditScreen(state = ExerciseEditUiState(fields = fields), onAction = {}, onBack = {}) }
}
