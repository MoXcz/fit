// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.exercises.ExerciseGroup
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks
import app.fit.exercises.exerciseForPreview
import app.fit.resources.Res
import app.fit.resources.exercises_archived
import app.fit.resources.exercises_no_category
import app.fit.resources.exercises_no_match
import app.fit.resources.exercises_search
import app.fit.resources.ic_keyboard_arrow_right
import app.fit.resources.ic_search
import app.fit.ui.SectionTitle
import app.fit.ui.theme.FitTheme
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Train's Exercises: no top bar of its own.
@Composable
fun ExercisesRoute(
    onOpen: (ExerciseId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExercisesViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ExercisesScreen(state = state, onAction = viewModel::onAction, onOpen = onOpen, modifier = modifier)
}

@Composable
fun ExercisesScreen(
    state: ExercisesUiState,
    onAction: (ExercisesAction) -> Unit,
    onOpen: (ExerciseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        OutlinedTextField(
            value = state.query,
            onValueChange = { onAction(ExercisesAction.Search(it)) },
            placeholder = { Text(stringResource(Res.string.exercises_search)) },
            leadingIcon = { Icon(painterResource(Res.drawable.ic_search), contentDescription = null) },
            singleLine = true,
            shape = MaterialTheme.shapes.large,
            colors =
                OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceBright,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (state.archivedCount > 0) {
            FilterChip(
                selected = state.archived,
                onClick = { onAction(ExercisesAction.ShowArchived(!state.archived)) },
                label = { Text(stringResource(Res.string.exercises_archived, state.archivedCount)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (!state.loading && state.groups.isEmpty()) {
            Text(
                stringResource(Res.string.exercises_no_match),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
            )
        } else {
            ExerciseList(groups = state.groups, onOpen = onOpen)
        }
    }
}

@Composable
private fun ExerciseList(
    groups: List<ExerciseGroup>,
    onOpen: (ExerciseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        groups.forEach { group ->
            item(key = "category:${group.category}") {
                SectionTitle(group.category.ifEmpty { stringResource(Res.string.exercises_no_category) })
            }
            itemsIndexed(group.exercises, key = { _, exercise -> exercise.id.value }) { index, exercise ->
                SegmentedListItem(
                    onClick = { onOpen(exercise.id) },
                    shapes = segmentedShapes(index, group.exercises.size),
                    colors = segmentedColors,
                    supportingContent = { Text(typeLabel(exercise.fields.tracks)) },
                    trailingContent = { Icon(painterResource(Res.drawable.ic_keyboard_arrow_right), contentDescription = null) },
                ) { Text(exercise.fields.name) }
                if (index < group.exercises.lastIndex) Spacer(Modifier.height(2.dp))
            }
        }
    }
}

@Preview
@Composable
private fun ExercisesScreenPreview() {
    val groups =
        listOf(
            ExerciseGroup("Chest", listOf(exerciseForPreview(1, "Bench press"), exerciseForPreview(2, "Push-up", Tracks(reps = true)))),
            ExerciseGroup("Abs", listOf(exerciseForPreview(3, "Plank", Tracks(time = true)))),
        )
    FitTheme {
        ExercisesScreen(
            state = ExercisesUiState(loading = false, archivedCount = 2, groups = groups),
            onAction = {},
            onOpen = {},
        )
    }
}
