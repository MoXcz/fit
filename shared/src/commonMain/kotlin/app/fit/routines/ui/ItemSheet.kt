// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import app.fit.exercises.ExerciseType
import app.fit.exercises.ui.typeLabel
import app.fit.resources.Res
import app.fit.resources.edit_none
import app.fit.resources.exercise_notes
import app.fit.resources.exercise_reps
import app.fit.resources.exercise_reps_max
import app.fit.resources.exercise_rest
import app.fit.resources.exercise_rest_max
import app.fit.resources.exercise_set_time
import app.fit.resources.exercise_sets
import app.fit.resources.item_as_exercise
import app.fit.resources.item_less
import app.fit.resources.item_more
import app.fit.resources.item_off
import app.fit.resources.item_progress_rpe
import app.fit.resources.item_progress_step
import app.fit.resources.item_record_as
import app.fit.resources.item_remove
import app.fit.resources.item_section
import app.fit.resources.item_section_name
import app.fit.resources.item_time
import app.fit.resources.item_warmups
import app.fit.routines.ItemField
import app.fit.routines.canStep
import app.fit.ui.SectionTitle
import app.fit.ui.Stepper
import app.fit.ui.formatClock
import app.fit.ui.formatNumber
import app.fit.ui.rememberFullSheetState
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// A routine slot's targets. Changes are written when the sheet closes (Back or a tap outside).
@Composable
internal fun ItemSheet(
    editor: ItemEditor,
    onAction: (RoutineEditorAction) -> Unit,
) {
    val sheetState = rememberFullSheetState()
    ModalBottomSheet(
        onDismissRequest = { onAction(RoutineEditorAction.CloseItem) },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier =
                Modifier
                    .verticalScroll(
                        rememberScrollState(),
                    ).padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
                    .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                editor.item.exercise.fields.name,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, bottom = 12.dp),
            )
            val t = editor.target
            val tracks = t.tracks ?: editor.item.exercise.fields.tracks
            val none = stringResource(Res.string.edit_none)
            val unit = editor.item.exercise.fields.weightUnit
            val basic =
                buildList {
                    add(Triple(ItemField.Sets, Res.string.exercise_sets, "${t.sets}"))
                    if (tracks.reps) add(Triple(ItemField.Reps, Res.string.exercise_reps, "${t.reps}"))
                    if (tracks.time) add(Triple(ItemField.Duration, Res.string.item_time, formatClock(t.duration)))
                    add(Triple(ItemField.Rest, Res.string.exercise_rest, formatClock(t.rest)))
                }
            StepperRows(basic, editor, onAction)

            SectionTitle(stringResource(Res.string.item_section))
            SectionChips(section = t.section.trim(), sections = editor.sectionChoices, onAction = onAction)

            TextButton(onClick = { onAction(RoutineEditorAction.ToggleItemMore) }) {
                Text(stringResource(if (editor.more) Res.string.item_less else Res.string.item_more))
            }
            if (editor.more) {
                val ranges =
                    buildList {
                        if (tracks.reps) add(Triple(ItemField.RepsMax, Res.string.exercise_reps_max, t.repsMax?.toString() ?: none))
                        add(Triple(ItemField.RestMax, Res.string.exercise_rest_max, t.restMax?.let(::formatClock) ?: none))
                        if (!tracks.time) add(Triple(ItemField.Duration, Res.string.exercise_set_time, formatClock(t.duration)))
                    }
                StepperRows(ranges, editor, onAction)

                // For a section that isn't among the chips yet.
                OutlinedTextField(
                    value = t.section,
                    onValueChange = { onAction(RoutineEditorAction.SetItemSection(it)) },
                    label = { Text(stringResource(Res.string.item_section_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.large,
                    colors = sheetFieldColors(),
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )

                SectionTitle(stringResource(Res.string.item_record_as))
                RecordAsField(editor = editor, onAction = onAction)

                if (tracks.weight) {
                    val off = stringResource(Res.string.item_off)
                    val progression =
                        buildList {
                            add(
                                Triple(
                                    ItemField.ProgressStep,
                                    Res.string.item_progress_step,
                                    t.progressStep?.let { "+${formatNumber(it)} ${unit.symbol}" } ?: off,
                                ),
                            )
                            if (t.progressStep !=
                                null
                            ) {
                                add(
                                    Triple(
                                        ItemField.ProgressRpe,
                                        Res.string.item_progress_rpe,
                                        t.progressRpe?.let(::formatNumber) ?: off,
                                    ),
                                )
                            }
                            add(Triple(ItemField.WarmupSets, Res.string.item_warmups, "${t.warmupSets}"))
                        }
                    Column(
                        Modifier.padding(top = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) { StepperRows(progression, editor, onAction) }
                }

                SectionTitle(stringResource(Res.string.exercise_notes))
                OutlinedTextField(
                    value = t.notes,
                    onValueChange = { onAction(RoutineEditorAction.SetItemNotes(it)) },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = MaterialTheme.shapes.large,
                    colors = sheetFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            TextButton(
                onClick = { onAction(RoutineEditorAction.RemoveItem) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.padding(top = 16.dp),
            ) { Text(stringResource(Res.string.item_remove)) }
        }
    }
}

@Composable
private fun StepperRows(
    rows: List<Triple<ItemField, StringResource, String>>,
    editor: ItemEditor,
    onAction: (RoutineEditorAction) -> Unit,
) {
    val unit = editor.item.exercise.fields.weightUnit
    rows.forEachIndexed { index, (field, label, value) ->
        ListItem(
            modifier = Modifier.clip(segmentShape(index, rows.size)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            headlineContent = { Text(stringResource(label)) },
            trailingContent = {
                Stepper(
                    value = value,
                    canDecrease = editor.target.canStep(field, up = false, unit),
                    canIncrease = editor.target.canStep(field, up = true, unit),
                    onStep = { up -> onAction(RoutineEditorAction.StepItem(field, up)) },
                )
            },
        )
    }
}

@Composable
private fun SectionChips(
    section: String,
    sections: List<String>,
    onAction: (RoutineEditorAction) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 8.dp)) {
        sections.forEach { name ->
            FilterChip(
                selected = name == section,
                onClick = { onAction(RoutineEditorAction.SetItemSection(name)) },
                label = { Text(name) },
            )
        }
    }
}

@Composable
private fun RecordAsField(
    editor: ItemEditor,
    onAction: (RoutineEditorAction) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val own = editor.item.exercise.fields.tracks
    val asExercise = stringResource(Res.string.item_as_exercise, typeLabel(own))
    val current = editor.target.tracks
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = if (current == null) asExercise else typeLabel(current),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors = sheetFieldColors(),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(asExercise) }, onClick = {
                expanded = false
                onAction(RoutineEditorAction.SetItemType(null))
            })
            PICKER_TYPES.filter { it.tracks != own }.forEach { type: ExerciseType ->
                DropdownMenuItem(text = { Text(typeLabel(requireNotNull(type.tracks))) }, onClick = {
                    expanded = false
                    onAction(RoutineEditorAction.SetItemType(type))
                })
            }
        }
    }
}

@Composable
internal fun sheetFieldColors(): TextFieldColors =
    OutlinedTextFieldDefaults.colors(
        focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
        unfocusedBorderColor = MaterialTheme.colorScheme.surfaceBright,
    )
