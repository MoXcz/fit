// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.fit.core.WeightUnit
import app.fit.exercises.DefaultField
import app.fit.exercises.EXERCISE_CATEGORIES
import app.fit.exercises.canStep
import app.fit.exercises.ui.typeLabel
import app.fit.resources.Res
import app.fit.resources.cancel
import app.fit.resources.edit_name
import app.fit.resources.edit_no_category
import app.fit.resources.exercise_category
import app.fit.resources.exercise_reps
import app.fit.resources.exercise_rest
import app.fit.resources.exercise_set_time
import app.fit.resources.exercise_sets
import app.fit.resources.exercise_type
import app.fit.resources.exercises_search
import app.fit.resources.ic_add
import app.fit.resources.item_less
import app.fit.resources.item_more
import app.fit.resources.item_section
import app.fit.resources.item_time
import app.fit.resources.picker_create_and_add
import app.fit.resources.picker_create_named
import app.fit.resources.picker_name_taken
import app.fit.resources.picker_new
import app.fit.resources.picker_title
import app.fit.resources.picker_type_category
import app.fit.resources.routine_name_blank
import app.fit.routines.COMMON_SECTIONS
import app.fit.ui.SectionTitle
import app.fit.ui.Stepper
import app.fit.ui.formatClock
import app.fit.ui.rememberFullSheetState
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

// Full height so the list and the inline form have room; Back closes it and drops any draft.
@Composable
internal fun ExercisePickerSheet(
    state: ExercisePickerUiState,
    onAction: (ExercisePickerAction) -> Unit,
    onAdded: (PickerAdded) -> Unit,
    onClose: () -> Unit,
) {
    LaunchedEffect(Unit) { onAction(ExercisePickerAction.Reset) }
    LaunchedEffect(state.added) {
        state.added?.let {
            onAction(ExercisePickerAction.AddedSeen)
            onAdded(it)
        }
    }
    val sheetState = rememberFullSheetState()
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.fillMaxHeight().imePadding().navigationBarsPadding()) {
            Text(
                stringResource(Res.string.picker_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 32.dp, bottom = 8.dp),
            )
            val form = state.form
            if (form == null) PickerList(state, onAction) else NewExerciseFormView(form, state, onAction)
        }
    }
}

@Composable
private fun PickerList(
    state: ExercisePickerUiState,
    onAction: (ExercisePickerAction) -> Unit,
) {
    OutlinedTextField(
        value = state.query,
        onValueChange = { onAction(ExercisePickerAction.Search(it)) },
        placeholder = { Text(stringResource(Res.string.exercises_search)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Search),
        shape = MaterialTheme.shapes.large,
        colors = sheetFieldColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
    )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(state.categories) { category ->
            FilterChip(
                selected = category == state.category,
                onClick = { onAction(ExercisePickerAction.Category(category)) },
                label = { Text(category) },
            )
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        item(key = "new") {
            val label =
                if (state.canCreateQuery) {
                    stringResource(
                        Res.string.picker_create_named,
                        state.query.trim(),
                    )
                } else {
                    stringResource(Res.string.picker_new)
                }
            SegmentedListItem(
                onClick = { onAction(ExercisePickerAction.StartCreate) },
                shapes = segmentedShapes(0, 1),
                colors = segmentedColors,
                leadingContent = { Icon(painterResource(Res.drawable.ic_add), contentDescription = null) },
            ) { Text(label) }
            Spacer(Modifier.height(12.dp))
        }
        itemsIndexed(state.exercises, key = { _, exercise -> exercise.id.value }) { index, exercise ->
            val type = typeLabel(exercise.fields.tracks)
            SegmentedListItem(
                onClick = { onAction(ExercisePickerAction.Add(exercise)) },
                enabled = !state.busy,
                shapes = segmentedShapes(index, state.exercises.size),
                colors = segmentedColors,
                supportingContent = {
                    Text(
                        if (exercise.fields.category.isBlank()) {
                            type
                        } else {
                            stringResource(
                                Res.string.picker_type_category,
                                type,
                                exercise.fields.category,
                            )
                        },
                    )
                },
            ) { Text(exercise.fields.name) }
            if (index < state.exercises.lastIndex) Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun NewExerciseFormView(
    form: NewExerciseForm,
    state: ExercisePickerUiState,
    onAction: (ExercisePickerAction) -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val error =
            when (state.formError) {
                NameError.Blank -> Res.string.routine_name_blank
                NameError.Taken -> Res.string.picker_name_taken
                null -> null
            }
        OutlinedTextField(
            value = form.name,
            onValueChange = { onAction(ExercisePickerAction.SetFormName(it)) },
            label = { Text(stringResource(Res.string.edit_name)) },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(stringResource(it)) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Dropdown(
            label = stringResource(Res.string.exercise_type),
            value = typeLabel(requireNotNull(form.type.tracks)),
            options = PICKER_TYPES.map { typeLabel(requireNotNull(it.tracks)) to { onAction(ExercisePickerAction.SetFormType(it)) } },
        )
        Spacer(Modifier.height(8.dp))
        val d = form.defaults
        val tracks = requireNotNull(form.type.tracks)
        val rows =
            buildList {
                add(Triple(DefaultField.Sets, Res.string.exercise_sets, "${d.sets}"))
                if (tracks.reps) add(Triple(DefaultField.Reps, Res.string.exercise_reps, "${d.reps}"))
                add(
                    Triple(
                        DefaultField.Duration,
                        if (tracks.time) Res.string.item_time else Res.string.exercise_set_time,
                        formatClock(d.duration),
                    ),
                )
                add(Triple(DefaultField.Rest, Res.string.exercise_rest, formatClock(d.rest)))
            }
        rows.forEachIndexed { index, (field, label, value) ->
            ListItem(
                modifier = Modifier.clip(segmentShape(index, rows.size)),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                headlineContent = { Text(stringResource(label)) },
                trailingContent = {
                    Stepper(
                        value = value,
                        canDecrease = d.canStep(field, up = false, WeightUnit.Kg),
                        canIncrease = d.canStep(field, up = true, WeightUnit.Kg),
                        onStep = { up -> onAction(ExercisePickerAction.StepForm(field, up)) },
                    )
                },
            )
        }
        TextButton(onClick = { onAction(ExercisePickerAction.ToggleFormMore) }) {
            Text(stringResource(if (form.more) Res.string.item_less else Res.string.item_more))
        }
        if (form.more) {
            val none = stringResource(Res.string.edit_no_category)
            Dropdown(
                label = stringResource(Res.string.exercise_category),
                value = form.category.ifBlank { none },
                options =
                    (listOf("") + EXERCISE_CATEGORIES).map {
                        it.ifBlank { none } to
                            { onAction(ExercisePickerAction.SetFormCategory(it)) }
                    },
            )
            SectionTitle(stringResource(Res.string.item_section))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
                COMMON_SECTIONS.forEach { section ->
                    FilterChip(
                        selected = section == form.section,
                        onClick = { onAction(ExercisePickerAction.SetFormSection(section)) },
                        label = { Text(section) },
                    )
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
            OutlinedButton(
                onClick = { onAction(ExercisePickerAction.CancelCreate) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.cancel)) }
            Button(
                onClick = { onAction(ExercisePickerAction.CreateAndAdd) },
                enabled = !state.busy,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.picker_create_and_add)) }
        }
    }
}

@Composable
private fun Dropdown(
    label: String,
    value: String,
    options: List<Pair<String, () -> Unit>>,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = Modifier.padding(top = 4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (text, select) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    expanded = false
                    select()
                })
            }
        }
    }
}
