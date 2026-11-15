// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.programs.ProgramRepository
import app.fit.programs.UnlinkedDays
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineNameResult
import app.fit.routines.RoutineRepository
import app.fit.routines.estimate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Duration

enum class NameError { Blank, Taken }

@Immutable
data class RoutineRow(
    val id: RoutineId,
    val name: String,
    val exerciseCount: Int,
    val estimate: Duration,
)

fun Routine.toRow() = RoutineRow(id, name, items.size, estimate(items))

@Immutable
data class RoutinesUiState(
    val loading: Boolean = true,
    val routines: List<RoutineRow> = emptyList(),
    // Non-null while the "New routine" dialog is open.
    val newName: String? = null,
    val nameError: NameError? = null,
    // The name of a routine just deleted, while Undo is offered.
    val deleted: String? = null,
    // The programs whose days it left as rest days.
    val restIn: List<String> = emptyList(),
    // A routine just created; the screen opens it and acknowledges.
    val opened: RoutineId? = null,
)

sealed interface RoutinesAction {
    data object StartNew : RoutinesAction

    data class SetNewName(
        val name: String,
    ) : RoutinesAction

    data object CancelNew : RoutinesAction

    data object CreateNew : RoutinesAction

    data class Copy(
        val id: RoutineId,
    ) : RoutinesAction

    data class Delete(
        val id: RoutineId,
    ) : RoutinesAction

    data object UndoDelete : RoutinesAction

    data object UndoSeen : RoutinesAction

    data object OpenedSeen : RoutinesAction
}

private data class ListLocal(
    val newName: String? = null,
    val nameError: NameError? = null,
    val deleted: Routine? = null,
    val unlinked: UnlinkedDays? = null,
    val opened: RoutineId? = null,
)

// A routine a program uses leaves those days as rest days when deleted; the message says so and Undo
// gives them back.
class RoutinesViewModel(
    private val repository: RoutineRepository,
    private val programs: ProgramRepository,
) : ViewModel() {
    private val local = MutableStateFlow(ListLocal())

    val uiState: StateFlow<RoutinesUiState> =
        combine(repository.routines, local) { routines, local ->
            RoutinesUiState(
                loading = false,
                routines = routines.map { it.toRow() },
                newName = local.newName,
                nameError = local.nameError,
                deleted = local.deleted?.name,
                restIn =
                    local.unlinked
                        ?.days
                        .orEmpty()
                        .map { it.programName }
                        .distinct(),
                opened = local.opened,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutinesUiState())

    fun onAction(action: RoutinesAction) {
        when (action) {
            RoutinesAction.StartNew -> {
                local.update { it.copy(newName = "", nameError = null) }
            }

            is RoutinesAction.SetNewName -> {
                local.update { it.copy(newName = action.name, nameError = null) }
            }

            RoutinesAction.CancelNew -> {
                local.update { it.copy(newName = null, nameError = null) }
            }

            RoutinesAction.CreateNew -> {
                val name = local.value.newName ?: return
                viewModelScope.launch {
                    when (val result = repository.create(name)) {
                        is RoutineNameResult.Saved -> local.update { it.copy(newName = null, opened = result.id) }
                        RoutineNameResult.NameBlank -> local.update { it.copy(nameError = NameError.Blank) }
                        RoutineNameResult.NameTaken -> local.update { it.copy(nameError = NameError.Taken) }
                    }
                }
            }

            is RoutinesAction.Copy -> {
                viewModelScope.launch { repository.copy(action.id) }
            }

            is RoutinesAction.Delete -> {
                viewModelScope.launch {
                    val unlinked = programs.daysUsing(action.id)
                    val deleted = repository.delete(action.id) ?: return@launch
                    local.update { it.copy(deleted = deleted, unlinked = unlinked.takeIf { u -> u.days.isNotEmpty() }) }
                }
            }

            RoutinesAction.UndoDelete -> {
                val deleted = local.value.deleted ?: return
                val unlinked = local.value.unlinked
                local.update { it.copy(deleted = null, unlinked = null) }
                viewModelScope.launch {
                    if (repository.restore(deleted)) unlinked?.let { programs.relink(it) }
                }
            }

            RoutinesAction.UndoSeen -> {
                local.update { it.copy(deleted = null, unlinked = null) }
            }

            RoutinesAction.OpenedSeen -> {
                local.update { it.copy(opened = null) }
            }
        }
    }
}
