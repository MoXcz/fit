// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.today
import app.fit.programs.DeletedProgram
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.programs.ProgramNameResult
import app.fit.programs.ProgramRepository
import app.fit.programs.ProgramSchedule
import app.fit.programs.ProgramWeek
import app.fit.routines.Routine
import app.fit.routines.RoutineRepository
import app.fit.routines.estimate
import app.fit.routines.ui.NameError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration

@Immutable
data class ProgramRow(
    val id: ProgramId,
    val name: String,
    val active: Boolean,
    val trainingDays: Int,
    val weekly: Duration,
    // This week in the program, when it has a start.
    val week: ProgramWeek?,
)

// A program's training days and their estimated time together, from its routines.
fun Program.weekly(routines: List<Routine>): Duration {
    val byId = routines.associateBy { it.id }
    return days.values.fold(Duration.ZERO) { total, id -> total + (byId[id]?.let { estimate(it.items) } ?: Duration.ZERO) }
}

fun Program.toRow(
    routines: List<Routine>,
    today: LocalDate,
) = ProgramRow(id, name, active, days.size, weekly(routines), ProgramSchedule.weekOf(this, today))

@Immutable
data class ProgramsUiState(
    val loading: Boolean = true,
    val programs: List<ProgramRow> = emptyList(),
    // Non-null while the "New program" dialog is open.
    val newName: String? = null,
    val nameError: NameError? = null,
    // The name of a program just deleted, while Undo is offered.
    val deleted: String? = null,
    // A program just created; the screen opens it and acknowledges.
    val opened: ProgramId? = null,
)

sealed interface ProgramsAction {
    data object StartNew : ProgramsAction

    data class SetNewName(
        val name: String,
    ) : ProgramsAction

    data object CancelNew : ProgramsAction

    data object CreateNew : ProgramsAction

    data class Copy(
        val id: ProgramId,
    ) : ProgramsAction

    data class Delete(
        val id: ProgramId,
    ) : ProgramsAction

    data object UndoDelete : ProgramsAction

    data object UndoSeen : ProgramsAction

    data object OpenedSeen : ProgramsAction
}

private data class ProgramsLocal(
    val newName: String? = null,
    val nameError: NameError? = null,
    val deleted: DeletedProgram? = null,
    val opened: ProgramId? = null,
)

// Train's Programs: create, copy, delete with Undo.
class ProgramsViewModel(
    private val programs: ProgramRepository,
    routines: RoutineRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val local = MutableStateFlow(ProgramsLocal())

    val uiState: StateFlow<ProgramsUiState> =
        combine(programs.programs, routines.routines, local) { all, routines, local ->
            val today = clock.today(zone)
            ProgramsUiState(
                loading = false,
                programs = all.map { it.toRow(routines, today) },
                newName = local.newName,
                nameError = local.nameError,
                deleted = local.deleted?.name,
                opened = local.opened,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramsUiState())

    fun onAction(action: ProgramsAction) {
        when (action) {
            ProgramsAction.StartNew -> {
                local.update { it.copy(newName = "", nameError = null) }
            }

            is ProgramsAction.SetNewName -> {
                local.update { it.copy(newName = action.name, nameError = null) }
            }

            ProgramsAction.CancelNew -> {
                local.update { it.copy(newName = null, nameError = null) }
            }

            ProgramsAction.CreateNew -> {
                val name = local.value.newName ?: return
                viewModelScope.launch {
                    when (val result = programs.create(name)) {
                        is ProgramNameResult.Saved -> local.update { it.copy(newName = null, opened = result.id) }
                        ProgramNameResult.NameBlank -> local.update { it.copy(nameError = NameError.Blank) }
                        ProgramNameResult.NameTaken -> local.update { it.copy(nameError = NameError.Taken) }
                    }
                }
            }

            is ProgramsAction.Copy -> {
                viewModelScope.launch { programs.copy(action.id) }
            }

            is ProgramsAction.Delete -> {
                viewModelScope.launch {
                    val deleted = programs.delete(action.id) ?: return@launch
                    local.update { it.copy(deleted = deleted) }
                }
            }

            ProgramsAction.UndoDelete -> {
                val deleted = local.value.deleted ?: return
                local.update { it.copy(deleted = null) }
                viewModelScope.launch { programs.restore(deleted) }
            }

            ProgramsAction.UndoSeen -> {
                local.update { it.copy(deleted = null) }
            }

            ProgramsAction.OpenedSeen -> {
                local.update { it.copy(opened = null) }
            }
        }
    }
}
