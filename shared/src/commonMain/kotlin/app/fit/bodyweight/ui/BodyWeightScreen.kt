// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.bodyweight.BodyWeight
import app.fit.core.WeightUnit
import app.fit.history.ui.ProgressChartView
import app.fit.resources.Res
import app.fit.resources.bodyweight_deleted
import app.fit.resources.bodyweight_empty_deletes
import app.fit.resources.bodyweight_invalid
import app.fit.resources.bodyweight_log
import app.fit.resources.bodyweight_none
import app.fit.resources.bodyweight_readings
import app.fit.resources.bodyweight_remove
import app.fit.resources.bodyweight_since
import app.fit.resources.bodyweight_title
import app.fit.resources.bodyweight_today
import app.fit.resources.bodyweight_was
import app.fit.resources.bodyweight_weight
import app.fit.resources.cancel
import app.fit.resources.chart_bodyweight
import app.fit.resources.details_save
import app.fit.ui.BackButton
import app.fit.ui.ConnectedChoice
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.SwipeToRemove
import app.fit.ui.UndoBarSpace
import app.fit.ui.UndoSnackbar
import app.fit.ui.formatDate
import app.fit.ui.formatNumber
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun BodyWeightRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BodyWeightViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    BodyWeightScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

// Today's reading and one button, the line of the last readings, then every reading (tap to edit,
// swipe to delete with Undo).
@Composable
fun BodyWeightScreen(
    state: BodyWeightUiState,
    onAction: (BodyWeightAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    state.edit?.let { EditDialog(edit = it, today = state.today, onAction = onAction) }
    Box(modifier = Modifier.fillMaxSize().background(colors.surfaceContainer).then(modifier)) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar(
                title = { ScreenTitle(stringResource(Res.string.bodyweight_title)) },
                navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            )
            if (state.loading) return@Column
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = UndoBarSpace)) {
                item(key = "today") { TodayCard(state = state, onAction = onAction) }
                state.chart?.takeIf { it.points.size > 1 }?.let { chart ->
                    item(key = "chart") {
                        Surface(shape = RoundedCornerShape(28.dp), color = colors.surfaceBright, modifier = Modifier.padding(top = 12.dp)) {
                            ProgressChartView(
                                chart = chart,
                                today = state.today,
                                title = stringResource(Res.string.chart_bodyweight),
                                modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                }
                item(
                    key = "list",
                ) { SectionTitle(stringResource(Res.string.bodyweight_readings), modifier = Modifier.padding(start = 4.dp)) }
                if (state.readings.isEmpty()) {
                    item(key = "none") {
                        Text(
                            stringResource(Res.string.bodyweight_none),
                            style = MaterialTheme.typography.bodyLarge,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                }
                itemsIndexed(state.readings, key = { _, reading -> reading.date.toString() }) { index, reading ->
                    SwipeToRemove(
                        label = stringResource(Res.string.bodyweight_remove),
                        onRemove = { onAction(BodyWeightAction.Delete(reading.date)) },
                        shape = listRowShape(index, state.readings.size),
                    ) {
                        ReadingRow(
                            reading = reading,
                            today = state.today,
                            index = index,
                            count = state.readings.size,
                            onClick = { onAction(BodyWeightAction.Edit(reading.date)) },
                        )
                    }
                }
            }
        }
        UndoSnackbar(
            message =
                state.undo?.let { undo ->
                    when (undo) {
                        is BodyWeightUndo.Deleted -> stringResource(Res.string.bodyweight_deleted, readingText(undo.before))
                        is BodyWeightUndo.Replaced -> stringResource(Res.string.bodyweight_was, readingText(undo.before))
                    }
                },
            onUndo = { onAction(BodyWeightAction.Undo) },
            onDismiss = { onAction(BodyWeightAction.UndoSeen) },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private fun readingText(reading: BodyWeight): String = "${formatNumber(reading.weight)} ${reading.unit.symbol}"

// Today's reading large, or the latest one and when it was.
@Composable
private fun TodayCard(
    state: BodyWeightUiState,
    onAction: (BodyWeightAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val latest = state.readings.firstOrNull()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(
            Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(stringResource(Res.string.bodyweight_today), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            val today = latest?.takeIf { it.date == state.today }
            Text(
                today?.let(::readingText) ?: "–",
                style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                fontFamily = LocalAppFonts.current.clock,
                color = colors.onSurface,
            )
            if (today == null && latest != null) {
                Text(
                    stringResource(Res.string.bodyweight_since, readingText(latest), formatDate(latest.date, state.today)),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Button(
                onClick = { onAction(BodyWeightAction.LogToday) },
                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(56.dp),
            ) { Text(stringResource(Res.string.bodyweight_log), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun ReadingRow(
    reading: BodyWeight,
    today: LocalDate,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = listRowShape(index, count),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            if (index > 0) HorizontalDivider(color = colors.outlineVariant)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp),
            ) {
                Text(formatDate(reading.date, today), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(
                    readingText(reading),
                    style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

// One reading: the number and its unit; empty deletes it.
@Composable
private fun EditDialog(
    edit: BodyWeightEdit,
    today: LocalDate,
    onAction: (BodyWeightAction) -> Unit,
) {
    AlertDialog(
        onDismissRequest = { onAction(BodyWeightAction.CloseEdit) },
        title = { Text(if (edit.date == today) stringResource(Res.string.bodyweight_today) else formatDate(edit.date, today)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = edit.text,
                    onValueChange = { onAction(BodyWeightAction.SetText(it)) },
                    label = { Text(stringResource(Res.string.bodyweight_weight)) },
                    suffix = { Text(edit.unit.symbol) },
                    isError = edit.invalid,
                    supportingText = {
                        Text(stringResource(if (edit.invalid) Res.string.bodyweight_invalid else Res.string.bodyweight_empty_deletes))
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onAction(BodyWeightAction.Save) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                ConnectedChoice(
                    options = WeightUnit.entries,
                    selected = edit.unit,
                    optionLabel = { it.symbol },
                    onSelect = { onAction(BodyWeightAction.SetUnit(it)) },
                )
            }
        },
        confirmButton = { TextButton(onClick = { onAction(BodyWeightAction.Save) }) { Text(stringResource(Res.string.details_save)) } },
        dismissButton = { TextButton(onClick = { onAction(BodyWeightAction.CloseEdit) }) { Text(stringResource(Res.string.cancel)) } },
    )
}

@Preview
@Composable
private fun BodyWeightScreenPreview() {
    val today = LocalDate(2026, 11, 10)
    FitTheme {
        BodyWeightScreen(
            state =
                BodyWeightUiState(
                    loading = false,
                    today = today,
                    readings = listOf(BodyWeight(today, 82.5, WeightUnit.Kg), BodyWeight(LocalDate(2026, 11, 3), 83.0, WeightUnit.Kg)),
                ),
            onAction = {},
            onBack = {},
        )
    }
}
