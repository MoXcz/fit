// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.day.DayId
import app.fit.day.Fatigue
import app.fit.day.WorkoutDay
import app.fit.day.ui.volumeLines
import app.fit.history.Record
import app.fit.resources.Res
import app.fit.resources.day_no_sets
import app.fit.resources.day_unnamed
import app.fit.resources.status_completed
import app.fit.resources.status_logged
import app.fit.resources.summary_done
import app.fit.resources.summary_fatigue
import app.fit.resources.summary_fatigue_scale
import app.fit.resources.summary_fatigue_value
import app.fit.resources.summary_no_records
import app.fit.resources.summary_notes
import app.fit.resources.summary_notes_hint
import app.fit.resources.summary_records
import app.fit.resources.summary_sets
import app.fit.resources.summary_time
import app.fit.resources.summary_title
import app.fit.resources.summary_volume
import app.fit.routines.ui.sheetFieldColors
import app.fit.ui.BackButton
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.formatClock
import app.fit.ui.formatDate
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.listRowShape
import kotlinx.datetime.LocalDate
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun SummaryRoute(
    date: LocalDate,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SummaryViewModel = koinViewModel(key = "summary:$date") { parametersOf(date.toString()) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SummaryScreen(state = state, onAction = viewModel::onAction, onBack = onBack, modifier = modifier)
}

@Composable
fun SummaryScreen(
    state: SummaryUiState,
    onAction: (SummaryAction) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(stringResource(Res.string.summary_title)) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
        )
        if (state.loading) return@Column
        // The text lives here, so typing never waits on a write; the ViewModel saves it when typing
        // pauses and when the screen goes.
        var notes by rememberSaveable(state.date) { mutableStateOf(state.day?.notes.orEmpty()) }
        DisposableEffect(Unit) { onDispose { onAction(SummaryAction.SaveNotes) } }
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
        ) {
            ResultCard(date = state.date, today = state.today, day = state.day)
            SectionTitle(stringResource(Res.string.summary_records), modifier = Modifier.padding(start = 4.dp))
            Records(state.records)
            SectionTitle(stringResource(Res.string.summary_notes), modifier = Modifier.padding(start = 4.dp))
            OutlinedTextField(
                value = notes,
                onValueChange = {
                    notes = it
                    onAction(SummaryAction.SetNotes(it))
                },
                placeholder = { Text(stringResource(Res.string.summary_notes_hint)) },
                minLines = 2,
                colors = sheetFieldColors(),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            SectionTitle(stringResource(Res.string.summary_fatigue), modifier = Modifier.padding(start = 4.dp))
            FatigueChoice(selected = state.day?.fatigue, onSelect = { onAction(SummaryAction.SetFatigue(it)) })
            Text(
                stringResource(Res.string.summary_fatigue_scale),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, top = 8.dp),
            )
            Button(
                onClick = onBack,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp).height(56.dp),
            ) { Text(stringResource(Res.string.summary_done), style = MaterialTheme.typography.titleMedium) }
        }
    }
}

// The hero card: the day, its name, and time, sets and volume in large numerals.
@Composable
private fun ResultCard(
    date: LocalDate,
    today: LocalDate,
    day: WorkoutDay?,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val status = stringResource(if (day?.finished == true) Res.string.status_completed else Res.string.status_logged)
            Text(
                "$status · ${formatDate(date, today)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                day?.name?.ifBlank { null } ?: stringResource(Res.string.day_unnamed),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = LocalAppFonts.current.title,
                color = colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (day == null || day.doneSets == 0) {
                Text(stringResource(Res.string.day_no_sets), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Stat(stringResource(Res.string.summary_time), listOf(day.duration?.let(::formatClock) ?: "–"), Modifier.weight(1f))
                Stat(stringResource(Res.string.summary_sets), listOf("${day.doneSets}"), Modifier.weight(1f))
                Stat(stringResource(Res.string.summary_volume), volumeLines(day.volume).ifEmpty { listOf("–") }, Modifier.weight(1.3f))
            }
        }
    }
}

@Composable
private fun Stat(
    label: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    // Read as one: "52:10, Time".
    Column(modifier.semantics(mergeDescendants = true) {}) {
        lines.forEach {
            Text(
                it,
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                fontFamily = LocalAppFonts.current.title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun Records(records: List<Record>) {
    val colors = MaterialTheme.colorScheme
    if (records.isEmpty()) {
        Text(
            stringResource(Res.string.summary_no_records),
            style = MaterialTheme.typography.bodyLarge,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    Column { records.forEachIndexed { index, record -> RecordRow(record, index, records.size) } }
}

// "Bench press   65 kg (was 62.5)", as row [index] of [count] in one block.
@Composable
internal fun RecordRow(
    record: Record,
    index: Int,
    count: Int,
) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = listRowShape(index, count), color = colors.surfaceContainerHigh) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                record.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                recordText(record),
                style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                fontWeight = FontWeight.SemiBold,
                color = colors.primary,
            )
        }
    }
}

// 1 to 5 as one connected group; tapping the chosen one clears it.
@Composable
private fun FatigueChoice(
    selected: Int?,
    onSelect: (Int?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        Fatigue.range.forEach { value ->
            val description = stringResource(Res.string.summary_fatigue_value, value)
            ToggleButton(
                checked = value == selected,
                onCheckedChange = { onSelect(if (value == selected) null else value) },
                shapes =
                    when (value) {
                        Fatigue.range.first -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        Fatigue.range.last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = description },
            ) { Text("$value", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Preview
@Composable
private fun SummaryScreenPreview() {
    val date = LocalDate(2026, 11, 9)
    FitTheme {
        SummaryScreen(
            state = SummaryUiState(loading = false, date = date, day = WorkoutDay(DayId(1), date, name = "Legs", fatigue = 3)),
            onAction = {},
            onBack = {},
        )
    }
}
