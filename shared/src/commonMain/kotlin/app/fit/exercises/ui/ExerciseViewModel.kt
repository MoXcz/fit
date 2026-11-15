// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.DayRepository
import app.fit.day.today
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.history.ExerciseDay
import app.fit.history.Progress
import app.fit.history.ProgressChart
import app.fit.lessons.Lesson
import app.fit.lessons.LessonRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

@Immutable
data class ExerciseUiState(
    val loading: Boolean = true,
    // Null once loaded means it doesn't exist.
    val exercise: Exercise? = null,
    val today: LocalDate = LocalDate(2026, 1, 1),
    // Its done working sets by day, newest first (the latest [HISTORY_DAYS] until Show all), how many
    // days are left out, and their best per day.
    val history: List<ExerciseDay> = emptyList(),
    val moreDays: Int = 0,
    val chart: ProgressChart? = null,
    // The lessons that teach it.
    val lessons: List<Lesson> = emptyList(),
)

// Days of history shown before Show all, so a long history isn't laid out at once.
const val HISTORY_DAYS = 30

sealed interface ExerciseAction {
    data object Archive : ExerciseAction

    data object Unarchive : ExerciseAction

    data object ShowAllHistory : ExerciseAction
}

class ExerciseViewModel(
    id: Long,
    private val repository: ExerciseRepository,
    days: DayRepository,
    lessons: LessonRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val id = ExerciseId(id)
    private val showAll = MutableStateFlow(false)

    val uiState: StateFlow<ExerciseUiState> =
        combine(
            repository.exercise(this.id),
            days.exerciseSets(this.id),
            lessons.lessonsFor(this.id),
            showAll,
        ) { exercise, sets, lessons, showAll ->
            val history = Progress.byDay(sets)
            val shown = if (showAll) history else history.take(HISTORY_DAYS)
            ExerciseUiState(
                loading = false,
                exercise = exercise,
                today = clock.today(zone),
                history = shown,
                moreDays = history.size - shown.size,
                chart = exercise?.let { Progress.chart(sets, it.fields) },
                lessons = lessons,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExerciseUiState())

    fun onAction(action: ExerciseAction) {
        when (action) {
            ExerciseAction.Archive -> viewModelScope.launch { repository.setArchived(id, true) }
            ExerciseAction.Unarchive -> viewModelScope.launch { repository.setArchived(id, false) }
            ExerciseAction.ShowAllHistory -> showAll.value = true
        }
    }
}
