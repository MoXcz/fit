// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.core.BodyArea
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRef
import app.fit.exercises.RefKind
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import app.fit.history.ui.ProgressChartView
import app.fit.history.ui.pastSetText
import app.fit.lessons.LessonId
import app.fit.resources.Res
import app.fit.resources.exercise_archive
import app.fit.resources.exercise_archived
import app.fit.resources.exercise_areas
import app.fit.resources.exercise_category
import app.fit.resources.exercise_defaults
import app.fit.resources.exercise_details
import app.fit.resources.exercise_duration
import app.fit.resources.exercise_each_side
import app.fit.resources.exercise_edit
import app.fit.resources.exercise_equipment
import app.fit.resources.exercise_history
import app.fit.resources.exercise_lessons
import app.fit.resources.exercise_links
import app.fit.resources.exercise_no_history
import app.fit.resources.exercise_not_found
import app.fit.resources.exercise_notes
import app.fit.resources.exercise_progress
import app.fit.resources.exercise_reps
import app.fit.resources.exercise_rest
import app.fit.resources.exercise_set_time
import app.fit.resources.exercise_sets
import app.fit.resources.exercise_show_all
import app.fit.resources.exercise_type
import app.fit.resources.exercise_unarchive
import app.fit.resources.exercise_units
import app.fit.resources.exercise_video
import app.fit.resources.exercise_weight
import app.fit.resources.ic_edit
import app.fit.resources.ic_open_in_new
import app.fit.ui.BackButton
import app.fit.ui.ScreenTitle
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.formatClock
import app.fit.ui.formatDate
import app.fit.ui.formatDurationRange
import app.fit.ui.formatNumber
import app.fit.ui.formatRange
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ExerciseRoute(
    id: ExerciseId,
    onEdit: () -> Unit,
    onOpenLesson: (LessonId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExerciseViewModel = koinViewModel(key = "exercise:${id.value}") { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExerciseScreen(
        state = state,
        onAction = viewModel::onAction,
        onEdit = onEdit,
        onOpenLesson = onOpenLesson,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun ExerciseScreen(
    state: ExerciseUiState,
    onAction: (ExerciseAction) -> Unit,
    onEdit: () -> Unit,
    onOpenLesson: (LessonId) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val exercise = state.exercise
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        ScreenTopBar(
            title = { ScreenTitle(exercise?.fields?.name.orEmpty()) },
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            actions = {
                if (exercise != null) {
                    FilledTonalIconButton(onClick = onEdit, shapes = IconButtonDefaults.shapes(), modifier = Modifier.padding(end = 8.dp)) {
                        Icon(painterResource(Res.drawable.ic_edit), contentDescription = stringResource(Res.string.exercise_edit))
                    }
                }
            },
        )
        when {
            state.loading -> {}

            exercise == null -> {
                Text(
                    stringResource(Res.string.exercise_not_found),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }

            else -> {
                ExerciseDetails(exercise = exercise, state = state, onAction = onAction, onOpenLesson = onOpenLesson)
            }
        }
    }
}

@Composable
private fun ExerciseDetails(
    exercise: Exercise,
    state: ExerciseUiState,
    onAction: (ExerciseAction) -> Unit,
    onOpenLesson: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fields = exercise.fields
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (exercise.archived) {
            Text(
                stringResource(Res.string.exercise_archived),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        SectionTitle(stringResource(Res.string.exercise_details))
        val details =
            buildList {
                if (fields.category.isNotBlank()) add(stringResource(Res.string.exercise_category) to fields.category)
                val type = typeLabel(fields.tracks)
                add(
                    stringResource(Res.string.exercise_type) to
                        if (fields.sides == Sides.Each) "$type · ${stringResource(Res.string.exercise_each_side)}" else type,
                )
                unitsLabel(fields)?.let { add(stringResource(Res.string.exercise_units) to it) }
                fields.equipment?.let { add(stringResource(Res.string.exercise_equipment) to stringResource(it.label)) }
                if (fields.areas.isNotEmpty()) {
                    add(stringResource(Res.string.exercise_areas) to fields.areas.map { stringResource(it.label) }.joinToString(", "))
                }
            }
        StaticRows(details)

        SectionTitle(stringResource(Res.string.exercise_defaults))
        StaticRows(defaultRows(fields))

        if (fields.notes.isNotBlank()) {
            SectionTitle(stringResource(Res.string.exercise_notes))
            Text(
                fields.notes,
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(segmentShape(0, 1))
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .padding(16.dp),
            )
        }

        if (state.lessons.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.exercise_lessons))
            state.lessons.forEachIndexed { index, lesson ->
                SegmentedListItem(
                    onClick = { onOpenLesson(lesson.id) },
                    shapes = segmentedShapes(index, state.lessons.size),
                    colors = segmentedColors,
                ) { Text(lesson.title) }
            }
        }

        val links =
            buildList {
                if (fields.videoUrl.isNotBlank()) {
                    add(
                        ExerciseRef(RefKind.Video, stringResource(Res.string.exercise_video), fields.videoUrl),
                    )
                }
                addAll(fields.refs)
            }
        if (links.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.exercise_links))
            val uriHandler = LocalUriHandler.current
            links.forEachIndexed { index, link ->
                SegmentedListItem(
                    onClick = { uriHandler.openUri(link.url) },
                    shapes = segmentedShapes(index, links.size),
                    colors = segmentedColors,
                    supportingContent = { Text(if (link.title.isBlank()) stringResource(link.kind.label) else link.url, maxLines = 1) },
                    trailingContent = { Icon(painterResource(Res.drawable.ic_open_in_new), contentDescription = null) },
                ) { Text(link.title.ifBlank { link.url }, maxLines = 2) }
            }
        }

        val chart = state.chart
        if (chart != null && chart.points.isNotEmpty()) {
            SectionTitle(stringResource(Res.string.exercise_progress))
            ProgressChartView(
                chart = chart,
                today = state.today,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(segmentShape(0, 1))
                        .background(MaterialTheme.colorScheme.surfaceBright)
                        .padding(16.dp),
            )
        }
        SectionTitle(stringResource(Res.string.exercise_history))
        if (state.history.isEmpty()) {
            Text(
                stringResource(Res.string.exercise_no_history),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val rows = state.history.size + if (state.moreDays > 0) 1 else 0
        state.history.forEachIndexed { index, day ->
            ListItem(
                modifier = Modifier.clip(segmentShape(index, rows)),
                colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
                headlineContent = { Text(formatDate(day.date, state.today)) },
                supportingContent = {
                    Text(
                        day.sets.map { pastSetText(it) }.joinToString("  ·  "),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    )
                },
            )
        }
        if (state.moreDays > 0) {
            SegmentedListItem(
                onClick = { onAction(ExerciseAction.ShowAllHistory) },
                shapes = segmentedShapes(rows - 1, rows),
                colors = segmentedColors,
            ) {
                Text(
                    pluralStringResource(Res.plurals.exercise_show_all, state.moreDays, state.moreDays),
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        if (exercise.archived) {
            Button(
                onClick = { onAction(ExerciseAction.Unarchive) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text(stringResource(Res.string.exercise_unarchive)) }
        } else {
            OutlinedButton(
                onClick = { onAction(ExerciseAction.Archive) },
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text(stringResource(Res.string.exercise_archive)) }
        }
    }
}

@Composable
private fun StaticRows(rows: List<Pair<String, String>>) {
    rows.forEachIndexed { index, (label, value) ->
        ListItem(
            modifier = Modifier.clip(segmentShape(index, rows.size)),
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceBright),
            headlineContent = { Text(label) },
            trailingContent = { Text(value, style = MaterialTheme.typography.bodyLarge) },
        )
    }
}

private fun unitsLabel(fields: ExerciseFields): String? =
    listOfNotNull(
        fields.weightUnit.symbol.takeIf { fields.tracks.weight },
        fields.distanceUnit.symbol.takeIf { fields.tracks.distance },
    ).joinToString(" · ").ifEmpty { null }

@Composable
private fun defaultRows(fields: ExerciseFields): List<Pair<String, String>> {
    val d = fields.defaults
    return buildList {
        add(stringResource(Res.string.exercise_sets) to "${d.sets}")
        if (fields.tracks.reps) add(stringResource(Res.string.exercise_reps) to formatRange(d.reps, d.repsMax))
        add(
            stringResource(if (fields.tracks.time) Res.string.exercise_duration else Res.string.exercise_set_time) to
                formatClock(d.duration),
        )
        add(stringResource(Res.string.exercise_rest) to formatDurationRange(d.rest, d.restMax))
        if (fields.tracks.weight) add(stringResource(Res.string.exercise_weight) to "${formatNumber(d.weight)} ${fields.weightUnit.symbol}")
    }
}

@Preview
@Composable
private fun ExerciseScreenPreview() {
    val exercise =
        Exercise(
            id = ExerciseId(1),
            contentId = "wrist-flexor-stretch",
            fields =
                ExerciseFields(
                    name = "Wrist flexor stretch",
                    category = "Mobility",
                    tracks = Tracks(time = true),
                    sides = Sides.Each,
                    areas = listOf(BodyArea.Wrists, BodyArea.Forearms),
                    defaults = ExerciseDefaults(sets = 1, rest = kotlin.time.Duration.ZERO),
                    notes = "Arm straight, palm up. Pull the fingers back until you feel a stretch.",
                ),
        )
    FitTheme {
        ExerciseScreen(
            state = ExerciseUiState(loading = false, exercise = exercise),
            onAction = {},
            onEdit = {},
            onOpenLesson = {},
            onBack = {},
        )
    }
}
