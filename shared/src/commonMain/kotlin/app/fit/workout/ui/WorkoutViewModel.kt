// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.day.Loading
import app.fit.day.Plates
import app.fit.day.RemovedSet
import app.fit.day.SetEdit
import app.fit.day.SetEntry
import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.day.today
import app.fit.exercises.MAX_DURATION
import app.fit.exercises.MAX_WEIGHT
import app.fit.exercises.MIN_DURATION
import app.fit.exercises.step
import app.fit.exercises.stepped
import app.fit.settings.Settings
import app.fit.settings.SettingsRepository
import app.fit.ui.formatNumber
import app.fit.ui.parseDecimal
import app.fit.workout.Hold
import app.fit.workout.Rest
import app.fit.workout.WorkoutController
import app.fit.workout.WorkoutEngine
import app.fit.workout.WorkoutState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The current set's large values.
enum class SetField { Weight, Reps, Seconds, Distance }

// The set after the current one or the rest: on this exercise, else the next exercise with sets left.
@Immutable
data class UpNext(
    val block: Block,
    val set: SetEntry,
)

@Immutable
data class WorkoutUiState(
    val loading: Boolean = true,
    val day: WorkoutDay? = null,
    val index: Int = 0,
    val block: Block? = null,
    val now: Instant = Instant.fromEpochMilliseconds(0),
    val today: LocalDate = LocalDate(2026, 1, 1),
    // The day's rest and hold: one of each at most, shown on every exercise.
    val rest: Rest? = null,
    val hold: Hold? = null,
    // What's typed into the current set, until Complete set or Start timer writes it.
    val draft: Map<SetField, String> = emptyMap(),
    // The sets sheet. Null follows the mode: open in review, peeking during a workout.
    val sheetOpen: Boolean? = null,
    // For the next set still to lift (the last one once all are done).
    val plates: Loading? = null,
    val plateUnit: WeightUnit = WeightUnit.Kg,
    // How many sets Undo would bring back; 0 when it isn't offered.
    val removed: Int = 0,
    // A done set picked in All sets to rate; it wins over the rules in [optionsSet].
    val rating: SetId? = null,
    val keepScreenOn: Boolean = false,
    // Finish was pressed: the screen goes back to Today and acknowledges.
    val finished: Boolean = false,
) {
    // A finished day, or any day but today: edits and ticks, no rest or hold.
    val review: Boolean get() = day == null || day.finished || day.date != today

    val sheetExpanded: Boolean get() = sheetOpen ?: review

    // The held set, else the set to do next.
    val current: SetEntry?
        get() {
            val sets = block?.sets ?: return null
            return hold?.let { h -> sets.firstOrNull { it.id == h.setId } } ?: block.nextSet
        }

    // What the set options (RPE, bodyweight, added weight) change: a set picked in All sets; during a
    // rest on this exercise, the set just done, since that's the one to rate; else the set to do
    // next; with none (all done, or review), the last working set done.
    val optionsSet: SetEntry?
        get() {
            val sets = block?.sets ?: return null
            rating?.let { id -> sets.firstOrNull { it.id == id }?.let { return it } }
            rest?.let { r -> sets.firstOrNull { it.id == r.setId }?.let { return it } }
            if (!review) current?.let { return it }
            return block.workingSets.lastOrNull { it.done } ?: current
        }

    // A running hold keeps the screen on its exercise.
    val canMove: Boolean get() = hold == null

    // None once this exercise is gone.
    val previous: Block? get() = block?.let { day?.blocks?.getOrNull(index - 1) }

    val next: Block? get() = block?.let { day?.blocks?.getOrNull(index + 1) }

    val upNext: UpNext?
        get() {
            val block = block ?: return null
            current?.let { return UpNext(block, it) }
            val following = day?.blocks?.drop(index + 1)?.firstOrNull { it.hasSetsLeft } ?: return null
            return UpNext(following, following.nextSet ?: return null)
        }

    // The session clock runs only for today; a finished day shows its frozen length.
    val elapsed: Duration?
        get() = day?.takeIf { it.started && (it.finished || it.date == today) }?.elapsed(now)

    val restLeft: Duration get() = rest?.let { WorkoutEngine.restLeft(it, now) } ?: Duration.ZERO

    val restOver: Duration get() = rest?.let { WorkoutEngine.restOver(it, now) } ?: Duration.ZERO

    val holdLeft: Duration get() = hold?.let { WorkoutEngine.holdLeft(it, now) } ?: Duration.ZERO

    val allDone: Boolean get() = block != null && !block.hasSetsLeft

    // What a large value shows: what's typed, else what the set holds, else what ticking it would log.
    fun value(field: SetField): String {
        val set = current ?: return ""
        return draft[field] ?: set.stored(field) ?: when (field) {
            SetField.Reps -> set.targetReps.toString()
            SetField.Seconds -> (set.targetDuration ?: block?.duration)?.inWholeSeconds?.toString().orEmpty()
            SetField.Weight, SetField.Distance -> ""
        }
    }
}

sealed interface WorkoutAction {
    data class Draft(
        val field: SetField,
        val text: String,
    ) : WorkoutAction

    data object CompleteSet : WorkoutAction

    data object StartTimer : WorkoutAction

    data object PauseHold : WorkoutAction

    data object ResumeHold : WorkoutAction

    data object StopHold : WorkoutAction

    data object CompleteHold : WorkoutAction

    data object AddRest : WorkoutAction

    data object SkipRest : WorkoutAction

    data object Previous : WorkoutAction

    data object Next : WorkoutAction

    data object Finish : WorkoutAction

    data object FinishedSeen : WorkoutAction

    data class SetSheet(
        val open: Boolean,
    ) : WorkoutAction

    // − or + on the current set's value.
    data class Step(
        val field: SetField,
        val up: Boolean,
    ) : WorkoutAction

    // − or + on the next set during a rest; written at once.
    data class StepNext(
        val field: SetField,
        val up: Boolean,
    ) : WorkoutAction

    data class AddedWeight(
        val on: Boolean,
    ) : WorkoutAction

    // On the options set; null clears it.
    data class SetRpe(
        val rpe: Double?,
    ) : WorkoutAction

    // On the options set and the working sets after it still to do.
    data class SetBodyweight(
        val on: Boolean,
    ) : WorkoutAction

    // Picks a done set in All sets for the set options.
    data class Rate(
        val id: SetId,
    ) : WorkoutAction

    // All sets.
    data class Edit(
        val id: SetId,
        val edit: SetEdit,
    ) : WorkoutAction

    data class SetDone(
        val id: SetId,
        val done: Boolean,
    ) : WorkoutAction

    data class StepWeight(
        val up: Boolean,
    ) : WorkoutAction

    data object AddSet : WorkoutAction

    data object AddWarmups : WorkoutAction

    data class Remove(
        val id: SetId,
    ) : WorkoutAction

    data object Undo : WorkoutAction

    data object UndoSeen : WorkoutAction
}

private data class WorkoutLocal(
    val draftSet: SetId? = null,
    val draft: Map<SetField, String> = emptyMap(),
    val sheetOpen: Boolean? = null,
    val removed: Int = 0,
    val finished: Boolean = false,
    val rating: SetId? = null,
)

// The focus view: one exercise of a day at a time, with previous and next. The rest and hold belong
// to the WorkoutController, so moving between exercises or leaving the screen never touches them.
@OptIn(ExperimentalCoroutinesApi::class)
class WorkoutViewModel(
    blockId: Long,
    private val days: DayRepository,
    private val controller: WorkoutController,
    settings: SettingsRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val selected = MutableStateFlow(BlockId(blockId))
    private val local = MutableStateFlow(WorkoutLocal())

    // Every set removed while the current Undo is offered, so quick swipes all come back.
    private var removedSets: List<RemovedSet> = emptyList()

    // Writes take turns, so a tick right after an edit sees the edit.
    private val writes = Mutex()

    private val date: Flow<LocalDate?> = flow { emit(days.dayOfBlock(BlockId(blockId)).first()?.date) }

    private val day: Flow<WorkoutDay?> = date.flatMapLatest { date -> if (date == null) flowOf(null) else days.day(date) }

    // The session clock counts up even when no timer runs.
    private val ticker: Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(1.seconds)
            }
        }

    private val view: Flow<Triple<WorkoutDay?, BlockId, WorkoutLocal>> = combine(day, selected, local, ::Triple)

    val uiState: StateFlow<WorkoutUiState> =
        combine(view, controller.state, settings.settings, ticker) { (day, selected, local), workout, settings, _ ->
            state(day, selected, local, workout, settings)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WorkoutUiState())

    init {
        // A running hold keeps the focus on its exercise, also when another one was opened.
        viewModelScope.launch {
            combine(day, controller.state) { day, workout ->
                workout.hold?.blockId?.takeIf { id -> day != null && workout.snapshot.date == day.date && day.blocks.any { it.id == id } }
            }.filterNotNull()
                .distinctUntilChanged()
                .collect { selected.value = it }
        }
        // Resume comes back to the exercise last open.
        viewModelScope.launch {
            combine(day, selected) { day, id -> day?.takeIf { d -> d.blocks.any { it.id == id } }?.let { it.date to id } }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { (date, id) -> controller.open(date, id) }
        }
    }

    private fun state(
        day: WorkoutDay?,
        selected: BlockId,
        local: WorkoutLocal,
        workout: WorkoutState,
        settings: Settings,
    ): WorkoutUiState {
        // A deleted exercise shows as gone, not as another one.
        val index = day?.blocks?.indexOfFirst { it.id == selected } ?: -1
        val block = day?.blocks?.getOrNull(index)
        val load = block?.loadSet()
        val plates =
            load?.weight?.takeIf { block.showsPlates && it > 0 && !load.bodyweight }?.let {
                Plates.perSide(it, load.weightUnit, settings.bar(load.weightUnit))
            }
        // The day's rest and hold show on every exercise, as in the notification, past midnight too.
        val ours = day != null && workout.snapshot.date == day.date
        val state =
            WorkoutUiState(
                loading = false,
                day = day,
                index = index,
                block = block,
                now = clock.now(),
                today = clock.today(zone),
                rest = workout.rest.takeIf { ours },
                hold = workout.hold.takeIf { ours },
                sheetOpen = local.sheetOpen,
                plates = plates,
                plateUnit = load?.weightUnit ?: WeightUnit.Kg,
                removed = local.removed,
                rating = local.rating,
                keepScreenOn = settings.keepScreenOn,
                finished = local.finished,
            )
        // A draft belongs to one set; another set starts empty.
        return state.copy(draft = if (local.draftSet == state.current?.id) local.draft else emptyMap())
    }

    private fun write(block: suspend () -> Unit) {
        viewModelScope.launch { writes.withLock { block() } }
    }

    fun onAction(action: WorkoutAction) {
        when (action) {
            is WorkoutAction.Draft -> {
                draft(action.field, action.text)
            }

            WorkoutAction.CompleteSet -> {
                completeSet()
            }

            WorkoutAction.StartTimer -> {
                startTimer()
            }

            WorkoutAction.PauseHold -> {
                write { controller.pauseHold() }
            }

            WorkoutAction.ResumeHold -> {
                write { controller.resumeHold() }
            }

            WorkoutAction.StopHold -> {
                write { controller.stopHold() }
            }

            WorkoutAction.CompleteHold -> {
                write { controller.completeHold() }
            }

            WorkoutAction.AddRest -> {
                write { controller.addRest() }
            }

            WorkoutAction.SkipRest -> {
                write { controller.skipRest() }
            }

            WorkoutAction.Previous -> {
                move(uiState.value.previous)
            }

            WorkoutAction.Next -> {
                move(uiState.value.next)
            }

            WorkoutAction.Finish -> {
                finish()
            }

            WorkoutAction.FinishedSeen -> {
                local.update { it.copy(finished = false) }
            }

            is WorkoutAction.SetSheet -> {
                local.update { it.copy(sheetOpen = action.open) }
            }

            is WorkoutAction.Step -> {
                step(action.field, action.up)
            }

            is WorkoutAction.StepNext -> {
                stepNext(action.field, action.up)
            }

            is WorkoutAction.AddedWeight -> {
                val set = uiState.value.optionsSet ?: return
                local.update { it.copy(draft = it.draft - SetField.Weight) }
                write { days.editSet(set.id, SetEdit.Weight(if (action.on) 0.0 else null)) }
            }

            is WorkoutAction.SetRpe -> {
                val set = uiState.value.optionsSet ?: return
                write { days.editSet(set.id, SetEdit.Rpe(action.rpe)) }
            }

            is WorkoutAction.Rate -> {
                local.update { it.copy(rating = action.id.takeIf { id -> id != it.rating }) }
            }

            is WorkoutAction.SetBodyweight -> {
                val state = uiState.value
                val block = state.block ?: return
                val set = state.optionsSet ?: return
                // The rest of the exercise is usually done the same way.
                val later =
                    block.workingSets
                        .dropWhile { it.id != set.id }
                        .drop(1)
                        .filter { !it.done }
                write { (listOf(set) + later).forEach { days.editSet(it.id, SetEdit.Bodyweight(action.on)) } }
            }

            is WorkoutAction.Edit -> {
                write { days.editSet(action.id, action.edit) }
            }

            is WorkoutAction.SetDone -> {
                val block = uiState.value.block ?: return
                write { controller.setDone(block.id, action.id, action.done) }
            }

            is WorkoutAction.StepWeight -> {
                val block = uiState.value.block ?: return
                write { days.stepWeight(block.id, action.up) }
            }

            WorkoutAction.AddSet -> {
                val block = uiState.value.block ?: return
                write { days.addSet(block.id) }
            }

            WorkoutAction.AddWarmups -> {
                val block = uiState.value.block ?: return
                write { days.addWarmups(block.id) }
            }

            is WorkoutAction.Remove -> {
                write {
                    days.removeSet(action.id)?.let { removed ->
                        removedSets = removedSets + removed
                        local.update { it.copy(removed = removedSets.size) }
                    }
                }
            }

            // Latest first, so each set goes back to the place it had.
            WorkoutAction.Undo -> {
                val sets = removedSets.takeIf { it.isNotEmpty() } ?: return
                removedSets = emptyList()
                local.update { it.copy(removed = 0) }
                write { sets.asReversed().forEach { days.restoreSet(it) } }
            }

            WorkoutAction.UndoSeen -> {
                removedSets = emptyList()
                local.update { it.copy(removed = 0) }
            }
        }
    }

    private fun draft(
        field: SetField,
        text: String,
    ) {
        val set = uiState.value.current ?: return
        local.update { state ->
            val draft = if (state.draftSet == set.id) state.draft else emptyMap()
            state.copy(draftSet = set.id, draft = draft + (field to text))
        }
    }

    private fun step(
        field: SetField,
        up: Boolean,
    ) {
        val state = uiState.value
        val set = state.current ?: return
        val block = state.block ?: return
        draft(field, stepped(field, state.value(field), up, set, block))
    }

    // The next set's value moves one step and is written; a draft of it no longer applies.
    private fun stepNext(
        field: SetField,
        up: Boolean,
    ) {
        val state = uiState.value
        val (block, set) = state.upNext ?: return
        val shown =
            if (set.id == state.current?.id) {
                state.value(field)
            } else {
                set.stored(field) ?: if (field == SetField.Reps) set.targetReps.toString() else ""
            }
        val text = stepped(field, shown, up, set, block)
        val edit =
            when (field) {
                SetField.Weight -> SetEdit.Weight(parseDecimal(text))
                SetField.Reps -> SetEdit.Reps(text.toIntOrNull())
                SetField.Seconds -> SetEdit.Duration(text.toIntOrNull()?.seconds)
                SetField.Distance -> SetEdit.Distance(parseDecimal(text))
            }
        local.update { if (it.draftSet == set.id) it.copy(draft = it.draft - field) else it }
        write { days.editSet(set.id, edit) }
    }

    private fun completeSet() {
        val state = uiState.value
        val block = state.block ?: return
        val set = state.current ?: return
        val draft = state.draft
        local.update { it.copy(draft = emptyMap(), rating = null) }
        write {
            writeDraft(set, draft)
            controller.setDone(block.id, set.id, true)
        }
    }

    // The hold runs for the time shown on the dial (holdLength), which a rest's − / + may have set.
    private fun startTimer() {
        val state = uiState.value
        val block = state.block ?: return
        val set = state.current ?: return
        val draft = state.draft
        val length = holdLength(set, block, draft)
        local.update { it.copy(draft = emptyMap(), rating = null) }
        write {
            writeDraft(set, draft - SetField.Seconds)
            controller.startHold(block.id, set.id, length)
        }
    }

    private suspend fun writeDraft(
        set: SetEntry,
        draft: Map<SetField, String>,
    ) {
        draft.forEach { (field, text) ->
            val edit =
                when (field) {
                    SetField.Weight -> {
                        parseDecimal(text).takeIf { it != set.weight }?.let(SetEdit::Weight)
                    }

                    SetField.Reps -> {
                        text
                            .trim()
                            .toIntOrNull()
                            .takeIf { it != set.reps }
                            ?.let(SetEdit::Reps)
                    }

                    SetField.Seconds -> {
                        text
                            .trim()
                            .toIntOrNull()
                            ?.seconds
                            .takeIf { it != set.duration }
                            ?.let(SetEdit::Duration)
                    }

                    SetField.Distance -> {
                        parseDecimal(text).takeIf { it != set.distance }?.let(SetEdit::Distance)
                    }
                }
            if (edit != null) days.editSet(set.id, edit)
        }
    }

    private fun move(to: Block?) {
        to ?: return
        if (!uiState.value.canMove) return
        selected.value = to.id
        local.update { it.copy(sheetOpen = null, rating = null) }
    }

    private fun finish() {
        val date = uiState.value.day?.date ?: return
        write {
            days.finish(date)
            local.update { it.copy(finished = true) }
        }
    }
}

// The quick ± and the plates follow the working set still to lift.
private fun Block.loadSet() = workingSets.firstOrNull { !it.done } ?: workingSets.lastOrNull()

// What a hold runs for: what was set on the dial, else what the set holds (a rest's − / + writes
// there), else its target, else the exercise's set time.
internal fun holdLength(
    set: SetEntry,
    block: Block,
    draft: Map<SetField, String>,
): Duration = draft[SetField.Seconds]?.trim()?.toIntOrNull()?.seconds ?: set.duration ?: set.targetDuration ?: block.duration

// One step of a large value: the load by the unit's plate step, reps by one, time by the editor's
// time steps, distance by a tenth (50 m in metres).
internal fun stepped(
    field: SetField,
    shown: String,
    up: Boolean,
    set: SetEntry,
    block: Block,
): String =
    when (field) {
        SetField.Weight -> {
            val step = set.weightUnit.step()
            formatNumber(((parseDecimal(shown) ?: 0.0) + if (up) step else -step).coerceIn(0.0, MAX_WEIGHT))
        }

        SetField.Reps -> {
            ((shown.toIntOrNull() ?: set.targetReps) + if (up) 1 else -1).coerceAtLeast(0).toString()
        }

        SetField.Seconds -> {
            val now = shown.toIntOrNull()?.seconds ?: set.targetDuration ?: block.duration
            now
                .stepped(up)
                .coerceIn(MIN_DURATION, MAX_DURATION)
                .inWholeSeconds
                .toString()
        }

        SetField.Distance -> {
            val step = if (set.distanceUnit == DistanceUnit.M) 50.0 else 0.1
            formatNumber(((parseDecimal(shown) ?: 0.0) + if (up) step else -step).coerceAtLeast(0.0))
        }
    }

internal fun SetEntry.stored(field: SetField): String? =
    when (field) {
        SetField.Weight -> weight?.let(::formatNumber)
        SetField.Reps -> reps?.toString()
        SetField.Seconds -> duration?.inWholeSeconds?.toString()
        SetField.Distance -> distance?.let(::formatNumber)
    }
