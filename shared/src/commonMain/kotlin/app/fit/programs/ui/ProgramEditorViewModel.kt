// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.today
import app.fit.programs.Deload
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.programs.ProgramNameResult
import app.fit.programs.ProgramRepository
import app.fit.programs.ProgramSchedule
import app.fit.programs.ProgramWeek
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import app.fit.routines.ui.NameError
import app.fit.routines.ui.RoutineRow
import app.fit.routines.ui.toRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration

// A weekday of the schedule: its routine, or null for rest.
@Immutable
data class ScheduleDay(
    val weekday: DayOfWeek,
    val routine: RoutineRow?,
)

@Immutable
data class ProgramEditorUiState(
    val loading: Boolean = true,
    val program: Program? = null,
    val name: String = "",
    val nameError: NameError? = null,
    val notes: String = "",
    // Monday to Sunday.
    val days: List<ScheduleDay> = emptyList(),
    val weekly: Duration = Duration.ZERO,
    // Every routine, for a day's sheet.
    val routines: List<RoutineRow> = emptyList(),
    // The day whose routine is being chosen.
    val choosing: DayOfWeek? = null,
    val pickingStart: Boolean = false,
    val today: LocalDate = LocalDate(2026, 1, 1),
    // Where today falls in the program.
    val thisWeek: ProgramWeek? = null,
) {
    val trainingDays: Int get() = days.count { it.routine != null }
}

sealed interface ProgramEditorAction {
    data class SetName(
        val name: String,
    ) : ProgramEditorAction

    data object CommitName : ProgramEditorAction

    data class SetNotes(
        val notes: String,
    ) : ProgramEditorAction

    data object CommitNotes : ProgramEditorAction

    data class SetActive(
        val active: Boolean,
    ) : ProgramEditorAction

    data class OpenDay(
        val weekday: DayOfWeek,
    ) : ProgramEditorAction

    // Null is a rest day.
    data class ChooseRoutine(
        val routine: RoutineId?,
    ) : ProgramEditorAction

    data object CloseDay : ProgramEditorAction

    data object OpenStart : ProgramEditorAction

    // Null: no start, so no weeks.
    data class SetStart(
        val date: LocalDate?,
    ) : ProgramEditorAction

    data object CloseStart : ProgramEditorAction

    data class StepDeloadEvery(
        val up: Boolean,
    ) : ProgramEditorAction

    data class StepDeloadPercent(
        val up: Boolean,
    ) : ProgramEditorAction
}

private data class EditorLocal(
    // Drafts while typing; null shows what's saved.
    val name: String? = null,
    val nameError: NameError? = null,
    val notes: String? = null,
    val choosing: DayOfWeek? = null,
    val pickingStart: Boolean = false,
)

// Changes are written as they're made; the name and notes when their field is left, or the screen.
class ProgramEditorViewModel(
    id: Long,
    private val programs: ProgramRepository,
    routines: RoutineRepository,
    private val clock: Clock,
    private val zone: TimeZone,
    // Outlives the screen, so a name or notes still being typed when it closes is kept.
    private val scope: CoroutineScope,
) : ViewModel() {
    private val id = ProgramId(id)
    private val local = MutableStateFlow(EditorLocal())

    // Steps take turns, so two quick taps read each other's result.
    private val writes = Mutex()

    val uiState: StateFlow<ProgramEditorUiState> =
        combine(programs.program(this.id), routines.routines, local) { program, routines, local ->
            val rows = routines.map { it.toRow() }
            val byId = rows.associateBy { it.id }
            val today = clock.today(zone)
            ProgramEditorUiState(
                loading = false,
                program = program,
                name = local.name ?: program?.name.orEmpty(),
                nameError = local.nameError,
                notes = local.notes ?: program?.notes.orEmpty(),
                days = DayOfWeek.entries.map { day -> ScheduleDay(day, program?.days?.get(day)?.let(byId::get)) },
                weekly = program?.weekly(routines) ?: Duration.ZERO,
                routines = rows,
                choosing = local.choosing,
                pickingStart = local.pickingStart,
                today = today,
                thisWeek = program?.let { ProgramSchedule.weekOf(it, today) },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProgramEditorUiState())

    // Every write runs on the app scope, one at a time, so Back never cancels one half done and two
    // quick taps read each other's result.
    private fun write(block: suspend (Program) -> Unit) {
        scope.launch {
            writes.withLock {
                val program = programs.program(id).first() ?: return@withLock
                block(program)
            }
        }
    }

    fun onAction(action: ProgramEditorAction) {
        when (action) {
            is ProgramEditorAction.SetName -> {
                setName(action.name)
            }

            ProgramEditorAction.CommitName -> {
                commitName()
            }

            is ProgramEditorAction.SetNotes -> {
                local.update { it.copy(notes = action.notes) }
            }

            ProgramEditorAction.CommitNotes -> {
                commitNotes()
            }

            is ProgramEditorAction.SetActive -> {
                write { programs.setActive(id, action.active) }
            }

            is ProgramEditorAction.OpenDay -> {
                local.update { it.copy(choosing = action.weekday) }
            }

            is ProgramEditorAction.ChooseRoutine -> {
                val day = local.value.choosing ?: return
                local.update { it.copy(choosing = null) }
                write { programs.setDay(id, day, action.routine) }
            }

            ProgramEditorAction.CloseDay -> {
                local.update { it.copy(choosing = null) }
            }

            ProgramEditorAction.OpenStart -> {
                local.update { it.copy(pickingStart = true) }
            }

            is ProgramEditorAction.SetStart -> {
                local.update { it.copy(pickingStart = false) }
                write { programs.setStart(id, action.date) }
            }

            ProgramEditorAction.CloseStart -> {
                local.update { it.copy(pickingStart = false) }
            }

            is ProgramEditorAction.StepDeloadEvery -> {
                write { programs.setDeloadEvery(id, ProgramSchedule.steppedDeloadEvery(it.deloadEvery, action.up)) }
            }

            is ProgramEditorAction.StepDeloadPercent -> {
                write { programs.setDeloadPercent(id, Deload.steppedPercent(it.deloadPercent, action.up)) }
            }
        }
    }

    override fun onCleared() {
        commitName()
        commitNotes()
    }

    // A name another program has says so while it's typed.
    private fun setName(name: String) {
        local.update { it.copy(name = name, nameError = null) }
        viewModelScope.launch {
            val clean = name.trim()
            val taken = programs.programs.first().any { it.id != id && it.name.equals(clean, ignoreCase = true) }
            if (taken) local.update { if (it.name == name) it.copy(nameError = NameError.Taken) else it }
        }
    }

    private fun commitNotes() {
        val notes = local.value.notes ?: return
        write { program ->
            if (notes != program.notes) programs.setNotes(id, notes)
            local.update { if (it.notes == notes) it.copy(notes = null) else it }
        }
    }

    // A blank or taken name stays in the field with its error and the saved name is kept (also when
    // the screen closes). The draft goes only if it wasn't typed over meanwhile.
    private fun commitName() {
        val name = local.value.name ?: return
        write { program ->
            val result = if (name.trim() == program.name) ProgramNameResult.Saved(id) else programs.rename(id, name)
            when (result) {
                is ProgramNameResult.Saved -> local.update { if (it.name == name) it.copy(name = null, nameError = null) else it }
                ProgramNameResult.NameBlank -> local.update { if (it.name == name) it.copy(nameError = NameError.Blank) else it }
                ProgramNameResult.NameTaken -> local.update { if (it.name == name) it.copy(nameError = NameError.Taken) else it }
            }
        }
    }
}
