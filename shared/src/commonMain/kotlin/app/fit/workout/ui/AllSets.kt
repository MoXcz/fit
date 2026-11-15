// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.Loading
import app.fit.day.SetEdit
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.ui.blockSummary
import app.fit.exercises.Tracks
import app.fit.exercises.step
import app.fit.resources.Res
import app.fit.resources.ic_add
import app.fit.resources.ic_check
import app.fit.resources.ic_remove
import app.fit.resources.load_bodyweight
import app.fit.resources.sets_add
import app.fit.resources.sets_add_warmups
import app.fit.resources.sets_bar_only
import app.fit.resources.sets_mark_done
import app.fit.resources.sets_mark_not_done
import app.fit.resources.sets_plates
import app.fit.resources.sets_plates_left
import app.fit.resources.sets_rate
import app.fit.resources.sets_remove
import app.fit.resources.sets_reps
import app.fit.resources.sets_switch_unit
import app.fit.resources.sets_warmup
import app.fit.resources.workout_rpe_value
import app.fit.ui.SwipeToRemove
import app.fit.ui.formatClock
import app.fit.ui.formatNumber
import app.fit.ui.formatRange
import app.fit.ui.parseClock
import app.fit.ui.parseDecimal
import app.fit.ui.theme.segmentShape
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.seconds

// "All sets", in the focus view's sheet.
internal fun LazyListScope.allSets(
    block: Block,
    plates: Loading?,
    onAction: (WorkoutAction) -> Unit,
    // The done set picked for the set options.
    rating: SetId? = null,
) {
    item(key = "summary") {
        Text(
            blockSummary(block),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
    val working = block.sets.filter { it.working }
    itemsIndexed(block.sets, key = { _, set -> set.id.value }) { index, set ->
        SetRow(
            set = set,
            number = if (set.warmup) null else working.indexOf(set) + 1,
            tracks = block.tracks,
            otherUnit = block.inOtherUnit(set),
            rating = set.id == rating,
            index = index,
            count = block.sets.size,
            onAction = onAction,
            modifier = Modifier.padding(bottom = 2.dp),
        )
    }
    item(key = "sets-footer") { Footer(block = block, plates = plates, onAction = onAction) }
}

// Tick to log; swipe away to remove, with Undo.
@Composable
private fun SetRow(
    set: SetEntry,
    number: Int?,
    tracks: Tracks,
    otherUnit: Boolean,
    rating: Boolean,
    index: Int,
    count: Int,
    onAction: (WorkoutAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // A done working set's number picks it for the set options, to rate it after the fact.
    val rateable = set.done && set.working
    val rate = stringResource(Res.string.sets_rate, number ?: 0)
    SwipeToRemove(
        label = stringResource(Res.string.sets_remove),
        onRemove = { onAction(WorkoutAction.Remove(set.id)) },
        shape = segmentShape(index, count),
        modifier = modifier,
    ) {
        Surface(
            shape = segmentShape(index, count),
            color = if (set.done) colors.secondaryContainer else colors.surfaceBright,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier =
                        Modifier
                            .widthIn(min = 32.dp)
                            .heightIn(min = 48.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(if (rating) colors.primaryContainer else Color.Transparent)
                            .then(
                                if (rateable) {
                                    Modifier.clickable(onClickLabel = rate, role = Role.Button) { onAction(WorkoutAction.Rate(set.id)) }
                                } else {
                                    Modifier
                                },
                            ),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        number?.toString() ?: stringResource(Res.string.sets_warmup),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (set.warmup) colors.tertiary else colors.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    set.rpe?.let {
                        Text(
                            stringResource(Res.string.workout_rpe_value, formatNumber(it)),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                ) { SetFields(set = set, tracks = tracks, otherUnit = otherUnit, onAction = onAction) }
                FilledIconToggleButton(
                    checked = set.done,
                    onCheckedChange = { onAction(WorkoutAction.SetDone(set.id, it)) },
                    shapes = IconButtonDefaults.toggleableShapes(),
                ) {
                    Icon(
                        painterResource(Res.drawable.ic_check),
                        contentDescription = stringResource(if (set.done) Res.string.sets_mark_not_done else Res.string.sets_mark_done),
                    )
                }
            }
        }
    }
}

// One field per thing the slot records; empty fields show the target and are filled from it on tick.
@Composable
private fun SetFields(
    set: SetEntry,
    tracks: Tracks,
    otherUnit: Boolean,
    onAction: (WorkoutAction) -> Unit,
) {
    fun edit(edit: SetEdit) = onAction(WorkoutAction.Edit(set.id, edit))
    val bodyweight = stringResource(Res.string.load_bodyweight)
    // A bodyweight set's field is what's added.
    if (set.bodyweight && !tracks.weight && set.weight == null) {
        Field(unit = "") { Text(bodyweight, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 12.dp)) }
    }
    // Added weight on a slot that doesn't track weight shows too.
    if (tracks.weight || set.weight != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (set.bodyweight) Text("$bodyweight +", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
            NumberField(
                value = set.weight?.let(::formatNumber).orEmpty(),
                placeholder = "0",
                sample = "000.0",
                decimal = true,
                onCommit = { edit(SetEdit.Weight(parseDecimal(it))) },
            )
            val other = if (set.weightUnit == WeightUnit.Kg) WeightUnit.Lb else WeightUnit.Kg
            val label = stringResource(Res.string.sets_switch_unit)
            // Narrow, so weight and reps fit on one line.
            TextButton(
                onClick = { edit(SetEdit.Unit(other)) },
                contentPadding = PaddingValues(horizontal = 8.dp),
                modifier =
                    Modifier.widthIn(min = 48.dp).semantics {
                        contentDescription =
                            "$label, ${set.weightUnit.symbol}"
                    },
            ) {
                // A unit unlike the exercise's other sets stands out.
                Text(
                    set.weightUnit.symbol,
                    fontWeight = if (otherUnit) FontWeight.Bold else null,
                    color = if (otherUnit) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                )
            }
        }
    }
    if (tracks.reps) {
        Field(unit = stringResource(Res.string.sets_reps)) {
            NumberField(
                value = set.reps?.toString().orEmpty(),
                placeholder = formatRange(set.targetReps, set.targetRepsMax),
                sample = "000",
                onCommit = { edit(SetEdit.Reps(it.toIntOrNull())) },
            )
        }
    }
    if (tracks.time) {
        // m:ss, or plain seconds that read back as m:ss.
        NumberField(
            value = set.duration?.let(::formatClock).orEmpty(),
            placeholder = set.targetDuration?.let(::formatClock).orEmpty(),
            sample = "00:00",
            time = true,
            onCommit = { edit(SetEdit.Duration(parseClock(it))) },
        )
    }
    if (tracks.distance) {
        Field(unit = set.distanceUnit.symbol) {
            NumberField(
                value = set.distance?.let(::formatNumber).orEmpty(),
                placeholder = "0",
                sample = "00.00",
                decimal = true,
                onCommit = { edit(SetEdit.Distance(parseDecimal(it))) },
            )
        }
    }
}

@Composable
private fun Field(
    unit: String,
    field: @Composable () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        field()
        Text(
            unit,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

// Typing stays local; the value is written on Done or when the field loses focus. The field is as
// wide as its widest realistic value ([sample]) or what it holds, whichever is wider, so nothing is
// clipped and the rows still line up.
@Composable
internal fun NumberField(
    value: String,
    placeholder: String,
    sample: String,
    onCommit: (String) -> Unit,
    decimal: Boolean = false,
    time: Boolean = false,
) {
    val accepts: (String) -> Boolean =
        when {
            time -> { it -> parseClock(it) != null }
            decimal -> { it -> parseDecimal(it) != null }
            else -> { it -> it.toIntOrNull() != null }
        }
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.titleMedium.copy(textAlign = TextAlign.Center, color = colors.onSurface)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val textWidth =
        listOf(sample, text, placeholder).maxOf {
            with(density) {
                measurer
                    .measure(it, style)
                    .size.width
                    .toDp()
            }
        }
    val interactions = remember { MutableInteractionSource() }
    val fieldColors =
        OutlinedTextFieldDefaults.colors(
            unfocusedContainerColor = colors.surfaceContainerHigh,
            focusedContainerColor = colors.surfaceContainerHigh,
            unfocusedBorderColor = colors.surfaceContainerHigh,
        )

    // Empty clears; a value that doesn't read (12x, 1:75) goes back to what was stored.
    fun commit() {
        if (text == value) return
        val clean = text.trim()
        if (clean.isEmpty() || accepts(clean)) onCommit(clean) else text = value
    }
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        textStyle = style,
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions =
            KeyboardOptions(
                // A time needs ":", which the number pads lack.
                keyboardType =
                    when {
                        time -> KeyboardType.Ascii
                        decimal -> KeyboardType.Decimal
                        else -> KeyboardType.Number
                    },
                imeAction = ImeAction.Done,
            ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        interactionSource = interactions,
        modifier =
            Modifier
                .width(textWidth + FIELD_PADDING * 2 + CURSOR_ROOM)
                .heightIn(min = 48.dp)
                .onFocusChanged {
                    if (focused && !it.isFocused) commit()
                    focused = it.isFocused
                },
    ) { inner ->
        OutlinedTextFieldDefaults.DecorationBox(
            value = text,
            innerTextField = inner,
            enabled = true,
            singleLine = true,
            visualTransformation = VisualTransformation.None,
            interactionSource = interactions,
            // The target, faint, so it doesn't read as a value that was entered.
            placeholder = {
                Text(
                    placeholder,
                    style = style,
                    color = colors.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            colors = fieldColors,
            contentPadding = PaddingValues(horizontal = FIELD_PADDING, vertical = 12.dp),
            container = {
                OutlinedTextFieldDefaults.Container(
                    enabled = true,
                    isError = false,
                    interactionSource = interactions,
                    colors = fieldColors,
                    shape = MaterialTheme.shapes.medium,
                )
            },
        )
    }
}

private val FIELD_PADDING = 6.dp
private val CURSOR_ROOM = 4.dp

@Composable
private fun Footer(
    block: Block,
    plates: Loading?,
    onAction: (WorkoutAction) -> Unit,
) {
    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (block.tracks.weight) {
            val unit =
                block.workingSets.firstOrNull { !it.done }?.weightUnit ?: block.workingSets.lastOrNull()?.weightUnit
                    ?: block.exercise.fields.weightUnit
            val step = "${formatNumber(unit.step())} ${unit.symbol}"
            // ± only changes sets still to do; logged sets keep their load.
            val canStep = block.workingSets.any { !it.done }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onAction(WorkoutAction.StepWeight(up = false)) },
                    enabled = canStep,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(Res.drawable.ic_remove), contentDescription = null)
                    Text(step, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
                OutlinedButton(
                    onClick = { onAction(WorkoutAction.StepWeight(up = true)) },
                    enabled = canStep,
                    shapes = ButtonDefaults.shapes(),
                ) {
                    Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
                    Text(step, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                }
            }
            plates?.let {
                Text(
                    platesText(it),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onAction(WorkoutAction.AddSet) }, shapes = ButtonDefaults.shapes()) {
                Icon(painterResource(Res.drawable.ic_add), contentDescription = null)
                Text(stringResource(Res.string.sets_add), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
            val canWarmUp =
                block.tracks.weight && block.sets.none { it.warmup } && block.workingSets.any { (it.weight ?: 0.0) > 0 && !it.bodyweight }
            if (canWarmUp) {
                OutlinedButton(onClick = { onAction(WorkoutAction.AddWarmups) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(Res.string.sets_add_warmups))
                }
            }
        }
    }
}

// "Per side: 20 · 10 · 2.5", with whatever the plates can't make up.
@Composable
private fun platesText(loading: Loading): String {
    val plates = loading.perSide.joinToString(" · ") { formatNumber(it) }.ifEmpty { stringResource(Res.string.sets_bar_only) }
    val text = if (loading.remainder > 0) stringResource(Res.string.sets_plates_left, plates, formatNumber(loading.remainder)) else plates
    return stringResource(Res.string.sets_plates, text)
}
