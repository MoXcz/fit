// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.fit.exercises.ExerciseId
import app.fit.lessons.LessonId
import app.fit.recovery.ui.SourceRow
import app.fit.recovery.ui.StepRow
import app.fit.resources.Res
import app.fit.resources.ic_delete
import app.fit.resources.ic_play_large
import app.fit.resources.lesson_delete
import app.fit.resources.lesson_deleted
import app.fit.resources.lesson_exercises
import app.fit.resources.lesson_gone
import app.fit.resources.lesson_kind
import app.fit.resources.lesson_practice
import app.fit.resources.lesson_practice_about
import app.fit.resources.lesson_practised
import app.fit.resources.lesson_sources
import app.fit.resources.undo_failed
import app.fit.ui.BackButton
import app.fit.ui.MarkdownText
import app.fit.ui.ScreenTopBar
import app.fit.ui.SectionTitle
import app.fit.ui.TopBarAction
import app.fit.ui.UndoSnackbar
import app.fit.ui.theme.LocalAppFonts
import app.fit.ui.theme.segmentShape
import app.fit.ui.theme.segmentedColors
import app.fit.ui.theme.segmentedShapes
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LessonRoute(
    id: LessonId,
    onBack: () -> Unit,
    onOpenExercise: (ExerciseId) -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LessonViewModel = koinViewModel(key = "lesson:${id.value}") { parametersOf(id.value) },
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(state.opened) {
        if (state.opened) {
            viewModel.onAction(LessonAction.OpenedSeen)
            onOpenPlayer()
        }
    }
    LessonScreen(state = state, onAction = viewModel::onAction, onBack = onBack, onOpenExercise = onOpenExercise, modifier = modifier)
}

// One lesson: Practice in the card, the notes, the exercises it teaches, the drill and the sources.
@Composable
fun LessonScreen(
    state: LessonUiState,
    onAction: (LessonAction) -> Unit,
    onBack: () -> Unit,
    onOpenExercise: (ExerciseId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).then(modifier)) {
        LessonPage(state, onAction, onBack, onOpenExercise)
        UndoSnackbar(
            message =
                when {
                    state.undoFailed -> stringResource(Res.string.undo_failed)
                    else -> state.deleted?.let { stringResource(Res.string.lesson_deleted, it) }
                },
            onUndo = { onAction(LessonAction.UndoDelete) },
            onDismiss = { onAction(LessonAction.UndoSeen) },
            canUndo = !state.undoFailed,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun LessonPage(
    state: LessonUiState,
    onAction: (LessonAction) -> Unit,
    onBack: () -> Unit,
    onOpenExercise: (ExerciseId) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val lesson = state.lesson
    Column(Modifier.fillMaxSize()) {
        ScreenTopBar(
            title = {},
            navigationIcon = { BackButton(onClick = onBack, modifier = Modifier.padding(start = 8.dp)) },
            actions = {
                if (lesson != null) {
                    TopBarAction(
                        icon = Res.drawable.ic_delete,
                        description = stringResource(Res.string.lesson_delete),
                        onClick = { onAction(LessonAction.Delete) },
                    )
                }
            },
        )
        if (state.loading) return@Column
        if (lesson == null) {
            // Just deleted: the Undo offer says so.
            if (state.deleted == null) {
                Text(
                    stringResource(Res.string.lesson_gone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 24.dp),
                )
            }
            return@Column
        }
        val practice = state.practice
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
            item(key = "hero") {
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Column(
                        Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = if (practice != null) 14.dp else 18.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            stringResource(Res.string.lesson_kind),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            lesson.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontFamily = LocalAppFonts.current.title,
                            color = colors.onSurface,
                        )
                        val meta =
                            listOfNotNull(
                                state.practiceMinutes?.let { stringResource(Res.string.lesson_practice_about, it) },
                                if (state.practised >
                                    0
                                ) {
                                    pluralStringResource(Res.plurals.lesson_practised, state.practised, state.practised)
                                } else {
                                    null
                                },
                            ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium)
                        if (practice != null) {
                            Button(
                                onClick = { onAction(LessonAction.Practice) },
                                shapes = ButtonDefaults.shapes(shape = RoundedCornerShape(22.dp), pressedShape = RoundedCornerShape(32.dp)),
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(64.dp),
                            ) {
                                Icon(
                                    painterResource(Res.drawable.ic_play_large),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                                Text(
                                    stringResource(Res.string.lesson_practice),
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                                )
                            }
                        }
                    }
                }
            }
            if (state.body.isNotEmpty()) {
                item(key = "body") {
                    MarkdownText(
                        state.body,
                        images = state.images,
                        modifier =
                            Modifier
                                .padding(top = 12.dp)
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .clip(segmentShape(0, 1))
                                .background(colors.surfaceBright)
                                .padding(16.dp),
                    )
                }
            }
            if (lesson.exercises.isNotEmpty()) {
                item(key = "exercises-title") {
                    SectionTitle(stringResource(Res.string.lesson_exercises), modifier = Modifier.padding(start = 16.dp))
                }
                itemsIndexed(lesson.exercises, key = { _, exercise -> "exercise-${exercise.id.value}" }) { index, exercise ->
                    SegmentedListItem(
                        onClick = { onOpenExercise(exercise.id) },
                        shapes = segmentedShapes(index, lesson.exercises.size),
                        colors = segmentedColors,
                        modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 2.dp),
                    ) { Text(exercise.name) }
                }
            }
            if (practice != null) {
                item(key = "practice-title") {
                    SectionTitle(stringResource(Res.string.lesson_practice), modifier = Modifier.padding(start = 16.dp))
                }
                itemsIndexed(practice.steps) { index, step -> StepRow(step, index, practice.steps.size) }
            }
            if (lesson.sources.isNotEmpty()) {
                item(key = "sources-title") {
                    SectionTitle(stringResource(Res.string.lesson_sources), modifier = Modifier.padding(start = 16.dp))
                }
                itemsIndexed(lesson.sources) { index, source -> SourceRow(source, index, lesson.sources.size) }
            }
        }
    }
}
