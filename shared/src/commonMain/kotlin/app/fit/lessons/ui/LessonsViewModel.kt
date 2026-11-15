// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedRepository
import app.fit.lessons.LessonId
import app.fit.lessons.LessonRepository
import app.fit.lessons.Practice
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class LessonRow(
    val id: LessonId,
    val title: String,
    // The names of the exercises it teaches.
    val exercises: List<String>,
    val practised: Int,
)

@Immutable
data class LessonsUiState(
    val loading: Boolean = true,
    // By title.
    val lessons: List<LessonRow> = emptyList(),
)

// Train's Lessons: every lesson with what it teaches and how often its drill ran.
class LessonsViewModel(
    lessons: LessonRepository,
    guided: GuidedRepository,
) : ViewModel() {
    val uiState: StateFlow<LessonsUiState> =
        combine(lessons.lessons, guided.logs(GuidedKind.Practice)) { lessons, logs ->
            val counts = Practice.counts(logs)
            LessonsUiState(
                loading = false,
                lessons =
                    lessons.map { lesson ->
                        LessonRow(lesson.id, lesson.title, lesson.exercises.map { it.name }, lesson.practice?.let(counts::get) ?: 0)
                    },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonsUiState())
}
