// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.exercises.ExerciseGroup
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.groupExercises
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Immutable
data class ExercisesUiState(
    val loading: Boolean = true,
    val query: String = "",
    // Showing the archived exercises instead of the active ones.
    val archived: Boolean = false,
    val archivedCount: Int = 0,
    val groups: List<ExerciseGroup> = emptyList(),
)

sealed interface ExercisesAction {
    data class Search(
        val query: String,
    ) : ExercisesAction

    data class ShowArchived(
        val archived: Boolean,
    ) : ExercisesAction
}

class ExercisesViewModel(
    repository: ExerciseRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val showArchived = MutableStateFlow(false)

    val uiState: StateFlow<ExercisesUiState> =
        combine(repository.exercises, query, showArchived) { all, query, showArchived ->
            val archivedCount = all.count { it.archived }
            // Unarchiving the last one goes back to the active list.
            val archived = showArchived && archivedCount > 0
            ExercisesUiState(
                loading = false,
                query = query,
                archived = archived,
                archivedCount = archivedCount,
                groups = groupExercises(all, query, archived),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisesUiState())

    fun onAction(action: ExercisesAction) {
        when (action) {
            is ExercisesAction.Search -> query.value = action.query
            is ExercisesAction.ShowArchived -> showArchived.value = action.archived
        }
    }
}
