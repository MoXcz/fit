// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.lessons.LessonId
import app.fit.resources.Res
import app.fit.resources.lesson_practised
import app.fit.resources.lessons_none
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Train's Lessons: no top bar of its own.
@Composable
fun LessonsRoute(
    onOpen: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LessonsViewModel = koinViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LessonsScreen(state = state, onOpen = onOpen, modifier = modifier)
}

@Composable
fun LessonsScreen(
    state: LessonsUiState,
    onOpen: (LessonId) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    ) {
        if (!state.loading && state.lessons.isEmpty()) {
            item {
                Text(
                    stringResource(Res.string.lessons_none),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
        itemsIndexed(state.lessons, key = { _, row -> row.id.value }) { index, row ->
            val meta = lessonMeta(row.exercises, row.practised)
            SegmentedListItem(
                onClick = { onOpen(row.id) },
                shapes = segmentedShapes(index, state.lessons.size),
                colors = segmentedColors,
                supportingContent = meta?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            ) { Text(row.title) }
            Spacer(Modifier.height(2.dp))
        }
    }
}

// "Push-up, Wall push-up · Practised 2 times"; a lesson never practised says nothing about it.
@Composable
internal fun lessonMeta(
    exercises: List<String>,
    practised: Int,
): String? =
    listOfNotNull(
        exercises.joinToString(", ").ifEmpty { null },
        if (practised > 0) pluralStringResource(Res.plurals.lesson_practised, practised, practised) else null,
    ).joinToString(" · ").ifEmpty { null }
