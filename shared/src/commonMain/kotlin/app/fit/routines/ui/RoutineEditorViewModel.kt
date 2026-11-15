// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.DayRepository
import app.fit.day.LastLoad
import app.fit.day.today
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseType
import app.fit.routines.COMMON_SECTIONS
import app.fit.routines.ItemField
import app.fit.routines.ItemTarget
import app.fit.routines.RemovedItems
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineItem
import app.fit.routines.RoutineItemId
import app.fit.routines.RoutineNameResult
import app.fit.routines.RoutineRepository
import app.fit.routines.sectionsOf
import app.fit.routines.step
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

enum class EditorMode { Normal, Reorder, Select }

@Immutable
data class ItemEditor(
    val item: RoutineItem,
    val target: ItemTarget,
    val more: Boolean = false,
    // The routine's own sections, then the usual ones.
    val sections: List<String> = COMMON_SECTIONS,
) {
    // With the item's own section, so one just typed can be seen and picked again.
    val sectionChoices: List<String>
        get() = (sections + target.section.trim()).filter { it.isNotBlank() }.distinct()
}

// What Undo would take back; the screen shows it until it's used or dismissed.
sealed interface EditorUndo {
    data class Added(
        val name: String,
    ) : EditorUndo

    data class Removed(
        val count: Int,
    ) : EditorUndo

    data class Copied(
        val count: Int,
        val to: String,
    ) : EditorUndo
}

@Immutable
data class RoutineEditorUiState(
    val loading: Boolean = true,
    val routine: Routine? = null,
    val name: String = "",
    val nameError: NameError? = null,
    val mode: EditorMode = EditorMode.Normal,
    val selected: Set<RoutineItemId> = emptySet(),
    // Non-null while the notes sheet is open.
    val notes: String? = null,
    val item: ItemEditor? = null,
    val pickerOpen: Boolean = false,
    // Non-null while choosing where to copy the selected items.
    val copyTargets: List<RoutineRow>? = null,
    val undo: EditorUndo? = null,
    // Just added: the screen brings it into view and acknowledges.
    val shown: RoutineItemId? = null,
    // Today already has exercises: Use today asks before replacing them.
    val confirmUseToday: Boolean = false,
    // The routine was copied into today; the screen goes to Today and acknowledges.
    val usedToday: Boolean = false,
    // The editor's Load column: what each exercise was last lifted with.
    val lastLoads: Map<ExerciseId, LastLoad> = emptyMap(),
)

sealed interface RoutineEditorAction {
    data class SetName(
        val name: String,
    ) : RoutineEditorAction

    data object ShownSeen : RoutineEditorAction

    data object CommitName : RoutineEditorAction

    data object OpenNotes : RoutineEditorAction

    data class SetNotes(
        val notes: String,
    ) : RoutineEditorAction

    data object CloseNotes : RoutineEditorAction

    data class SetMode(
        val mode: EditorMode,
    ) : RoutineEditorAction

    // [by] places: negative is up. Stays within the item's section.
    data class Move(
        val id: RoutineItemId,
        val by: Int,
    ) : RoutineEditorAction

    data class ToggleSelected(
        val id: RoutineItemId,
    ) : RoutineEditorAction

    data object DeleteSelected : RoutineEditorAction

    data object ChooseCopyTarget : RoutineEditorAction

    data class CopySelectedTo(
        val id: RoutineId,
    ) : RoutineEditorAction

    data object CancelCopy : RoutineEditorAction

    data class OpenItem(
        val id: RoutineItemId,
    ) : RoutineEditorAction

    data class StepItem(
        val field: ItemField,
        val up: Boolean,
    ) : RoutineEditorAction

    data class SetItemSection(
        val section: String,
    ) : RoutineEditorAction

    // Null records what the exercise tracks.
    data class SetItemType(
        val type: ExerciseType?,
    ) : RoutineEditorAction

    data class SetItemNotes(
        val notes: String,
    ) : RoutineEditorAction

    data object ToggleItemMore : RoutineEditorAction

    data object CloseItem : RoutineEditorAction

    data object RemoveItem : RoutineEditorAction

    data object OpenPicker : RoutineEditorAction

    data object ClosePicker : RoutineEditorAction

    // The picker added an exercise.
    data class Added(
        val id: RoutineItemId,
        val name: String,
    ) : RoutineEditorAction

    data object Undo : RoutineEditorAction

    data object UndoSeen : RoutineEditorAction

    data object UseToday : RoutineEditorAction

    data object ConfirmUseToday : RoutineEditorAction

    data object CancelUseToday : RoutineEditorAction

    data object UsedTodaySeen : RoutineEditorAction
}

private data class EditorLocal(
    val name: String? = null,
    val nameError: NameError? = null,
    val mode: EditorMode = EditorMode.Normal,
    val selected: Set<RoutineItemId> = emptySet(),
    val notes: String? = null,
    val item: ItemEditor? = null,
    val pickerOpen: Boolean = false,
    val copyTargets: List<RoutineRow>? = null,
    val undo: EditorUndo? = null,
    val shown: RoutineItemId? = null,
    val confirmUseToday: Boolean = false,
    val usedToday: Boolean = false,
)

// Changes are written as they're made (act first, offer Undo); the item sheet writes when it closes.
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineEditorViewModel(
    id: Long,
    private val repository: RoutineRepository,
    private val days: DayRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val id = RoutineId(id)
    private val local = MutableStateFlow(EditorLocal())

    // What Undo would run; kept out of the UI state.
    private var undoAction: (suspend () -> Unit)? = null

    // Item saves and loads take turns, so reopening an item right after closing it shows the save.
    private val writes = Mutex()

    // Loaded again when the routine's exercises change.
    private val lastLoads: Flow<Map<ExerciseId, LastLoad>> =
        repository
            .routine(this.id)
            .map { routine ->
                routine
                    ?.items
                    .orEmpty()
                    .map { it.exercise.id }
                    .distinct()
            }.distinctUntilChanged()
            .mapLatest { ids -> days.lastLoads(ids, clock.today(zone)) }
            .onStart { emit(emptyMap()) }

    val uiState: StateFlow<RoutineEditorUiState> =
        combine(repository.routine(this.id), local, lastLoads) { routine, local, lastLoads ->
            RoutineEditorUiState(
                loading = false,
                routine = routine,
                name = local.name ?: routine?.name.orEmpty(),
                nameError = local.nameError,
                mode = local.mode,
                selected = local.selected.filterTo(HashSet()) { selected -> routine?.items.orEmpty().any { it.id == selected } },
                notes = local.notes,
                item = local.item,
                pickerOpen = local.pickerOpen,
                copyTargets = local.copyTargets,
                undo = local.undo,
                shown = local.shown,
                confirmUseToday = local.confirmUseToday,
                usedToday = local.usedToday,
                lastLoads = lastLoads,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RoutineEditorUiState())

    private suspend fun current(): Routine? = repository.routine(id).first()

    private fun offerUndo(
        offer: EditorUndo,
        action: suspend () -> Unit,
    ) {
        undoAction = action
        local.update { it.copy(undo = offer) }
    }

    fun onAction(action: RoutineEditorAction) {
        when (action) {
            is RoutineEditorAction.SetName -> {
                local.update { it.copy(name = action.name, nameError = null) }
            }

            RoutineEditorAction.CommitName -> {
                commitName()
            }

            RoutineEditorAction.OpenNotes -> {
                viewModelScope.launch { local.update { it.copy(notes = current()?.notes.orEmpty()) } }
            }

            is RoutineEditorAction.SetNotes -> {
                local.update { it.copy(notes = action.notes) }
            }

            RoutineEditorAction.CloseNotes -> {
                closeNotes()
            }

            is RoutineEditorAction.SetMode -> {
                local.update { it.copy(mode = action.mode, selected = emptySet()) }
            }

            is RoutineEditorAction.Move -> {
                viewModelScope.launch { repository.move(action.id, action.by) }
            }

            is RoutineEditorAction.ToggleSelected -> {
                local.update { it.copy(selected = it.selected.toggled(action.id)) }
            }

            RoutineEditorAction.DeleteSelected -> {
                removeItems(local.value.selected.toList())
            }

            RoutineEditorAction.ChooseCopyTarget -> {
                chooseCopyTarget()
            }

            is RoutineEditorAction.CopySelectedTo -> {
                copySelectedTo(action.id)
            }

            RoutineEditorAction.CancelCopy -> {
                local.update { it.copy(copyTargets = null) }
            }

            is RoutineEditorAction.OpenItem -> {
                openItem(action.id)
            }

            is RoutineEditorAction.StepItem -> {
                editItem { item -> item.copy(target = item.target.step(action.field, action.up, item.item.exercise.fields.weightUnit)) }
            }

            is RoutineEditorAction.SetItemSection -> {
                editItem { it.copy(target = it.target.copy(section = action.section)) }
            }

            is RoutineEditorAction.SetItemType -> {
                editItem { item ->
                    item.copy(
                        target =
                            item.target.copy(
                                tracks =
                                    action.type?.tracks?.takeIf {
                                        it !=
                                            item.item.exercise.fields.tracks
                                    },
                            ),
                    )
                }
            }

            is RoutineEditorAction.SetItemNotes -> {
                editItem { it.copy(target = it.target.copy(notes = action.notes)) }
            }

            RoutineEditorAction.ToggleItemMore -> {
                editItem { it.copy(more = !it.more) }
            }

            RoutineEditorAction.CloseItem -> {
                closeItem()
            }

            RoutineEditorAction.RemoveItem -> {
                removeOpenItem()
            }

            RoutineEditorAction.OpenPicker -> {
                local.update { it.copy(pickerOpen = true) }
            }

            RoutineEditorAction.ClosePicker -> {
                local.update { it.copy(pickerOpen = false) }
            }

            is RoutineEditorAction.Added -> {
                local.update { it.copy(pickerOpen = false, shown = action.id) }
                offerUndo(EditorUndo.Added(action.name)) { repository.removeItems(id, listOf(action.id)) }
            }

            RoutineEditorAction.ShownSeen -> {
                local.update { it.copy(shown = null) }
            }

            RoutineEditorAction.Undo -> {
                undo()
            }

            RoutineEditorAction.UndoSeen -> {
                undoAction = null
                local.update { it.copy(undo = null) }
            }

            RoutineEditorAction.UseToday -> {
                useToday()
            }

            RoutineEditorAction.ConfirmUseToday -> {
                local.update { it.copy(confirmUseToday = false) }
                viewModelScope.launch { applyToday() }
            }

            RoutineEditorAction.CancelUseToday -> {
                local.update { it.copy(confirmUseToday = false) }
            }

            RoutineEditorAction.UsedTodaySeen -> {
                local.update { it.copy(usedToday = false) }
            }
        }
    }

    // As the old app: the routine replaces today's exercises, asking first if there are any.
    private fun useToday() {
        viewModelScope.launch {
            if (current()?.items.isNullOrEmpty()) return@launch
            val today = days.day(clock.today(zone)).first()
            if (today?.blocks.isNullOrEmpty()) applyToday() else local.update { it.copy(confirmUseToday = true) }
        }
    }

    private suspend fun applyToday() {
        if (days.applyRoutine(clock.today(zone), id, replace = true)) local.update { it.copy(usedToday = true) }
    }

    private fun commitName() {
        val name = local.value.name ?: return
        viewModelScope.launch {
            when (repository.rename(id, name)) {
                is RoutineNameResult.Saved -> local.update { it.copy(name = null, nameError = null) }
                RoutineNameResult.NameBlank -> local.update { it.copy(nameError = NameError.Blank) }
                RoutineNameResult.NameTaken -> local.update { it.copy(nameError = NameError.Taken) }
            }
        }
    }

    private fun closeNotes() {
        val notes = local.value.notes ?: return
        local.update { it.copy(notes = null) }
        viewModelScope.launch { if (current()?.notes != notes) repository.setNotes(id, notes) }
    }

    private fun openItem(itemId: RoutineItemId) {
        viewModelScope.launch {
            val routine = writes.withLock { current() } ?: return@launch
            val item = routine.items.firstOrNull { it.id == itemId } ?: return@launch
            val sections = (sectionsOf(routine.items).map { it.name } + COMMON_SECTIONS).distinct()
            local.update { it.copy(item = ItemEditor(item, item.target, sections = sections)) }
        }
    }

    private fun editItem(change: (ItemEditor) -> ItemEditor) {
        local.update { state -> state.item?.let { state.copy(item = change(it)) } ?: state }
    }

    private fun closeItem() {
        val editor = local.value.item ?: return
        local.update { it.copy(item = null) }
        if (editor.target !=
            editor.item.target
        ) {
            viewModelScope.launch { writes.withLock { repository.saveItem(editor.item.id, editor.target) } }
        }
    }

    private fun removeOpenItem() {
        val editor = local.value.item ?: return
        local.update { it.copy(item = null) }
        removeItems(listOf(editor.item.id))
    }

    private fun removeItems(ids: List<RoutineItemId>) {
        if (ids.isEmpty()) return
        local.update { it.copy(selected = emptySet(), mode = EditorMode.Normal) }
        viewModelScope.launch {
            val removed: RemovedItems = repository.removeItems(id, ids)
            // A second removal before the first offer goes joins it; the latest comes back first.
            val earlier = (local.value.undo as? EditorUndo.Removed)?.count ?: 0
            val restoreEarlier = undoAction.takeIf { earlier > 0 }
            offerUndo(EditorUndo.Removed(earlier + removed.items.size)) {
                repository.restoreItems(removed)
                restoreEarlier?.invoke()
            }
        }
    }

    private fun chooseCopyTarget() {
        viewModelScope.launch {
            val others =
                repository.routines
                    .first()
                    .filter { it.id != id }
                    .map { it.toRow() }
            local.update { it.copy(copyTargets = others) }
        }
    }

    private fun copySelectedTo(target: RoutineId) {
        val ids =
            uiState.value.routine
                ?.items
                .orEmpty()
                .map { it.id }
                .filter { it in local.value.selected }
        val name =
            local.value.copyTargets
                ?.firstOrNull { it.id == target }
                ?.name
                .orEmpty()
        local.update { it.copy(copyTargets = null, selected = emptySet(), mode = EditorMode.Normal) }
        viewModelScope.launch {
            val copies = repository.copyItemsTo(ids, target)
            if (copies.isEmpty()) return@launch
            offerUndo(EditorUndo.Copied(copies.size, name)) { repository.removeItems(target, copies) }
        }
    }

    private fun undo() {
        val action = undoAction ?: return
        undoAction = null
        local.update { it.copy(undo = null) }
        viewModelScope.launch { action() }
    }
}

private fun <T> Set<T>.toggled(value: T): Set<T> = if (value in this) this - value else this + value
