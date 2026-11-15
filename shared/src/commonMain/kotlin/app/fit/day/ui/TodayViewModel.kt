// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.BodyWeightEntry
import app.fit.bodyweight.BodyWeightRepository
import app.fit.bodyweight.BodyWeights
import app.fit.core.WeightUnit
import app.fit.day.Block
import app.fit.day.BlockId
import app.fit.day.DayMark
import app.fit.day.DayPlan
import app.fit.day.DayRepository
import app.fit.day.SessionClock
import app.fit.day.WorkoutDay
import app.fit.day.localTime
import app.fit.day.mark
import app.fit.day.today
import app.fit.day.week
import app.fit.guided.GuidedController
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedRepository
import app.fit.guided.GuidedSource
import app.fit.history.CalendarMonth
import app.fit.history.MonthGrid
import app.fit.programs.PlannedDay
import app.fit.programs.ProgramRepository
import app.fit.programs.ProgramSchedule
import app.fit.routines.RoutineId
import app.fit.routines.RoutineNameResult
import app.fit.routines.RoutineRepository
import app.fit.routines.WARMUP_SECTION
import app.fit.routines.freeRoutineName
import app.fit.routines.ui.NameError
import app.fit.routines.ui.RoutineRow
import app.fit.routines.ui.toRow
import app.fit.settings.SettingsRepository
import app.fit.ui.formatNumber
import app.fit.workout.Rest
import app.fit.workout.WorkoutController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Logged: another day with sets done but never finished.
enum class DayStatus { Planned, InProgress, Logged, Completed }

// The hero card's one big button. Only today starts or resumes; a finished day, or another day with
// sets done, opens its summary; other days review. An empty day with a plan applies it (and today
// starts it); without one, exercises are added by hand.
enum class PrimaryAction { Start, Resume, Finish, Summary, Review, StartPlan, UsePlan, AddExercise }

enum class TodayMode { Normal, Reorder }

enum class TimeField { Start, End }

// The time dialog: which end it sets, starting at the stored time or now.
@Immutable
data class TimeEdit(
    val field: TimeField,
    val initial: LocalTime,
    // A start after the finish was picked; the dialog stays open.
    val startAfterEnd: Boolean = false,
)

@Immutable
data class WeekCell(
    val date: LocalDate,
    val mark: DayMark,
    val today: Boolean,
    val selected: Boolean,
    // A recovery session ran that day.
    val recovery: Boolean = false,
)

// One sheet at a time.
sealed interface TodaySheet {
    data object Options : TodaySheet

    data class ChooseRoutine(
        val routines: List<RoutineRow>,
        val replace: Boolean = true,
    ) : TodaySheet

    data class CopyDay(
        val days: List<WorkoutDay>,
    ) : TodaySheet

    // Name, notes and the day's body weight are drafts written when the sheet closes.
    data class Details(
        val name: String,
        val notes: String,
        val bodyWeight: String = "",
        val bodyWeightUnit: WeightUnit = WeightUnit.Kg,
        // The reading the day has without its own: the latest before it.
        val earlier: BodyWeight? = null,
    ) : TodaySheet {
        // Not a weight, or above the cap: closing keeps the old reading.
        val bodyWeightInvalid: Boolean get() = BodyWeights.entry(bodyWeight) == BodyWeightEntry.Invalid
    }

    // The month shown, as its first day.
    data class Calendar(
        val month: LocalDate,
    ) : TodaySheet
}

// The Save as routine dialog.
@Immutable
data class SaveAsRoutine(
    val name: String,
    val error: NameError? = null,
)

// What Undo would take back; the screen shows it until it's used or dismissed.
sealed interface TodayUndo {
    data class Added(
        val name: String,
    ) : TodayUndo

    // Everything removed while this offer stands: Undo brings all of it back.
    data class Removed(
        val names: List<String>,
    ) : TodayUndo

    data class Deleted(
        val date: LocalDate,
    ) : TodayUndo

    // Undo found nothing to take back (the day was filled again, or the row is gone); no action.
    data object Failed : TodayUndo

    // The day's body weight reading the details sheet deleted or replaced; Undo puts it back.
    data class BodyWeightChanged(
        val before: BodyWeight,
        val deleted: Boolean,
    ) : TodayUndo

    // A notice without Undo: the day was saved as a routine.
    data class Saved(
        val name: String,
    ) : TodayUndo
}

@Immutable
data class TodayUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate(2026, 1, 1),
    val today: LocalDate = date,
    val now: Instant = Instant.fromEpochMilliseconds(0),
    // The zone the day's times are shown in.
    val zone: TimeZone = TimeZone.UTC,
    val week: List<WeekCell> = emptyList(),
    val day: WorkoutDay? = null,
    val mode: TodayMode = TodayMode.Normal,
    val sheet: TodaySheet? = null,
    // A routine waiting for "Replace" because the day has exercises.
    val confirmReplace: RoutineRow? = null,
    val timePicker: TimeEdit? = null,
    val pickerOpen: Boolean = false,
    val undo: TodayUndo? = null,
    // A block to open; the screen navigates and acknowledges.
    val opened: BlockId? = null,
    val saveAs: SaveAsRoutine? = null,
    // This day's rest, and the exercise last open in the focus view.
    val rest: Rest? = null,
    val position: BlockId? = null,
    // The calendar sheet's month once its days are read; the sheet waits for it, so it never shows
    // a month without its marks.
    val calendar: CalendarMonth? = null,
    // A day whose summary to open (after Finish, or View summary); the screen navigates and acknowledges.
    val summary: LocalDate? = null,
    // What the active program says about the date, and its routine when it plans one.
    val plan: PlannedDay? = null,
    val planned: RoutineRow? = null,
    // Every routine, for an empty day.
    val routines: List<RoutineRow> = emptyList(),
    // Recovery sessions run on the date: a quiet line each under the workout.
    val recovery: List<GuidedLog> = emptyList(),
    // The player to open (the warm-up runs on it); the screen navigates and acknowledges.
    val player: Boolean = false,
) {
    val hasBlocks: Boolean get() = !day?.blocks.isNullOrEmpty()

    private val isToday: Boolean get() = date == today

    // The routines an empty day lists under its plan.
    val otherRoutines: List<RoutineRow> get() = routines.filter { it.id != planned?.id }

    // A day with exercises says whether its loads were cut; an empty one whether the plan's would be.
    val deload: Boolean get() = if (hasBlocks) day?.deload == true else plan?.deload == true

    val programWeek: Int? get() = plan?.week?.week

    val status: DayStatus?
        get() =
            when {
                day == null || !hasBlocks -> null
                day.finished -> DayStatus.Completed
                isToday && day.started -> DayStatus.InProgress
                !isToday && (day.started || day.doneSets > 0) -> DayStatus.Logged
                else -> DayStatus.Planned
            }

    // The exercise the hero card names and Resume opens.
    val current: Block? get() = day?.current(position)

    val primary: PrimaryAction
        get() =
            when {
                day == null || !hasBlocks -> {
                    if (planned ==
                        null
                    ) {
                        PrimaryAction.AddExercise
                    } else if (isToday) {
                        PrimaryAction.StartPlan
                    } else {
                        PrimaryAction.UsePlan
                    }
                }

                day.finished || (!isToday && day.doneSets > 0) -> {
                    PrimaryAction.Summary
                }

                !isToday -> {
                    PrimaryAction.Review
                }

                day.started && day.allDone -> {
                    PrimaryAction.Finish
                }

                day.started -> {
                    PrimaryAction.Resume
                }

                else -> {
                    PrimaryAction.Start
                }
            }
}

sealed interface TodayAction {
    data object Previous : TodayAction

    data object Next : TodayAction

    data class Select(
        val date: LocalDate,
    ) : TodayAction

    data object ShowToday : TodayAction

    data object OpenOptions : TodayAction

    data object OpenCalendar : TodayAction

    // [by] months: negative is back.
    data class ShowMonth(
        val by: Int,
    ) : TodayAction

    data object OpenSummary : TodayAction

    data object SummarySeen : TodayAction

    data object CloseSheet : TodayAction

    data object OpenChooser : TodayAction

    data class SetReplace(
        val replace: Boolean,
    ) : TodayAction

    data class ChooseRoutine(
        val id: RoutineId,
    ) : TodayAction

    data object ConfirmReplace : TodayAction

    data object CancelReplace : TodayAction

    data object OpenCopyDay : TodayAction

    data class CopyDay(
        val from: LocalDate,
    ) : TodayAction

    data class SetMode(
        val mode: TodayMode,
    ) : TodayAction

    // [by] places: negative is up. Stays within the block's section.
    data class Move(
        val id: BlockId,
        val by: Int,
    ) : TodayAction

    data class Remove(
        val id: BlockId,
    ) : TodayAction

    data object OpenDetails : TodayAction

    data class SetName(
        val name: String,
    ) : TodayAction

    data class SetNotes(
        val notes: String,
    ) : TodayAction

    data object OpenSaveAs : TodayAction

    data class SetSaveAsName(
        val name: String,
    ) : TodayAction

    data object SaveAsRoutine : TodayAction

    data object CancelSaveAs : TodayAction

    data class EditTime(
        val field: TimeField,
    ) : TodayAction

    data class SetTime(
        val time: LocalTime,
    ) : TodayAction

    data object CloseTimePicker : TodayAction

    data object EndAtLastSet : TodayAction

    data object ClearEnd : TodayAction

    data object Start : TodayAction

    // An empty day's planned routine: applied, and today also started.
    data object StartPlan : TodayAction

    data object UsePlan : TodayAction

    // One of the routines an empty day lists, applied without starting.
    data class ApplyRoutine(
        val id: RoutineId,
    ) : TodayAction

    data class SetBodyWeight(
        val text: String,
    ) : TodayAction

    data class SetBodyWeightUnit(
        val unit: WeightUnit,
    ) : TodayAction

    data object Resume : TodayAction

    // Opens the day's exercises without starting anything.
    data object Review : TodayAction

    // Today's workout from the launcher: Start or Resume when today has exercises left, else just Today.
    data object StartFromShortcut : TodayAction

    // Today's warm-up section, run on the player.
    data object Warmup : TodayAction

    data object PlayerSeen : TodayAction

    data class Open(
        val id: BlockId,
    ) : TodayAction

    data object OpenedSeen : TodayAction

    data object Finish : TodayAction

    data object Reopen : TodayAction

    data object DeleteDay : TodayAction

    data object OpenPicker : TodayAction

    data object ClosePicker : TodayAction

    // The picker added an exercise.
    data class Added(
        val id: BlockId,
        val name: String,
    ) : TodayAction

    data object Undo : TodayAction

    data object UndoSeen : TodayAction
}

private data class TodayLocal(
    val mode: TodayMode = TodayMode.Normal,
    val sheet: TodaySheet? = null,
    val confirmReplace: RoutineRow? = null,
    val timePicker: TimeEdit? = null,
    val pickerOpen: Boolean = false,
    val undo: TodayUndo? = null,
    val opened: BlockId? = null,
    val saveAs: SaveAsRoutine? = null,
    val summary: LocalDate? = null,
    val player: Boolean = false,
)

// Changes are written as they're made (act first, offer Undo); the details sheet writes when it closes.
@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModel(
    private val days: DayRepository,
    private val routines: RoutineRepository,
    private val programs: ProgramRepository,
    private val bodyWeights: BodyWeightRepository,
    private val settings: SettingsRepository,
    private val workout: WorkoutController,
    private val guided: GuidedController,
    private val logs: GuidedRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    // Null follows the calendar, so the screen moves on at midnight.
    private val selected = MutableStateFlow<LocalDate?>(null)
    private val local = MutableStateFlow(TodayLocal())

    // What Undo would run; kept out of the UI state.
    private var undoAction: (suspend () -> Boolean)? = null

    // The elapsed time counts up while a workout runs.
    private val ticker: Flow<Instant> =
        flow {
            while (true) {
                emit(clock.now())
                delay(1.seconds)
            }
        }

    private val dates: Flow<Pair<LocalDate, LocalDate>> =
        combine(selected, ticker.map { clock.today(zone) }.distinctUntilChanged()) { selected, today -> (selected ?: today) to today }
            .distinctUntilChanged()

    private val dayAndWeek: Flow<Triple<WorkoutDay?, List<WorkoutDay>, List<GuidedLog>>> =
        dates.map { it.first }.distinctUntilChanged().flatMapLatest { date ->
            val week = date.week()
            combine(
                days.day(date),
                days.days(week.first(), week.last()),
                logs.logs(GuidedKind.Recovery, week.first(), week.last()),
                ::Triple,
            )
        }

    // Only while the calendar sheet is open.
    private val calendarDays: Flow<Triple<LocalDate, List<WorkoutDay>, List<GuidedLog>>?> =
        local
            .map { (it.sheet as? TodaySheet.Calendar)?.month }
            .distinctUntilChanged()
            .flatMapLatest { month ->
                if (month == null) {
                    flowOf(null)
                } else {
                    combine(
                        days.days(month, MonthGrid.lastDay(month)),
                        logs.logs(GuidedKind.Recovery, month, MonthGrid.lastDay(month)),
                    ) { monthDays, monthLogs ->
                        Triple(month, monthDays, monthLogs)
                    }
                }
            }

    private val plans = combine(programs.active, routines.routines) { active, all -> active to all.map { it.toRow() } }

    private val timersAndCalendar =
        combine(workout.state, calendarDays, plans) { timers, calendar, plans -> Triple(timers, calendar, plans) }

    val uiState: StateFlow<TodayUiState> =
        combine(
            ticker,
            dates,
            dayAndWeek,
            local,
            timersAndCalendar,
        ) { now, (date, today), (day, weekDays, weekLogs), local, (timers, calendar, plans) ->
            val byDate = weekDays.associateBy { it.date }
            val recoveryDays = weekLogs.recoveryDays()
            val ours = timers.snapshot.date == date
            val (active, rows) = plans
            val plan = active?.let { ProgramSchedule.planFor(it, date) }
            TodayUiState(
                loading = false,
                date = date,
                today = today,
                now = now,
                zone = zone,
                week = date.week().map { WeekCell(it, byDate[it].mark(), it == today, it == date, it in recoveryDays) },
                day = day?.takeIf { it.date == date },
                mode = local.mode,
                sheet = local.sheet,
                confirmReplace = local.confirmReplace,
                timePicker = local.timePicker,
                pickerOpen = local.pickerOpen,
                undo = local.undo,
                opened = local.opened,
                saveAs = local.saveAs,
                rest = timers.rest.takeIf { ours },
                position = timers.snapshot.blockId.takeIf { ours },
                calendar =
                    calendar?.let { (month, monthDays, monthLogs) ->
                        CalendarMonth(month, MonthGrid.cells(month, today, date, monthDays, monthLogs.recoveryDays()))
                    },
                summary = local.summary,
                plan = plan,
                planned = plan?.routineId?.let { id -> rows.firstOrNull { it.id == id } },
                routines = rows,
                recovery = weekLogs.filter { it.day == date },
                player = local.player,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayUiState())

    private fun date(): LocalDate = selected.value ?: clock.today(zone)

    private suspend fun day(): WorkoutDay? = days.day(date()).first()

    private fun select(date: LocalDate) {
        selected.value = date.takeIf { it != clock.today(zone) }
        local.update { it.copy(mode = TodayMode.Normal, sheet = null) }
    }

    private fun offerUndo(
        offer: TodayUndo,
        action: suspend () -> Boolean,
    ) {
        undoAction = action
        local.update { it.copy(undo = offer) }
    }

    private fun closeSheet() = local.update { it.copy(sheet = null) }

    fun onAction(action: TodayAction) {
        when (action) {
            TodayAction.Previous -> {
                select(date().minus(1, DateTimeUnit.DAY))
            }

            TodayAction.Next -> {
                select(date().plus(1, DateTimeUnit.DAY))
            }

            is TodayAction.Select -> {
                select(action.date)
            }

            TodayAction.ShowToday -> {
                select(clock.today(zone))
            }

            TodayAction.OpenOptions -> {
                local.update { it.copy(sheet = TodaySheet.Options) }
            }

            TodayAction.OpenCalendar -> {
                local.update { it.copy(sheet = TodaySheet.Calendar(MonthGrid.monthOf(date()))) }
            }

            is TodayAction.ShowMonth -> {
                editSheet<TodaySheet.Calendar> { it.copy(month = it.month.plus(action.by, DateTimeUnit.MONTH)) }
            }

            TodayAction.OpenSummary -> {
                local.update { it.copy(summary = date()) }
            }

            TodayAction.SummarySeen -> {
                local.update { it.copy(summary = null) }
            }

            TodayAction.CloseSheet -> {
                closeCurrentSheet()
            }

            TodayAction.OpenChooser -> {
                openChooser()
            }

            is TodayAction.SetReplace -> {
                editSheet<TodaySheet.ChooseRoutine> { it.copy(replace = action.replace) }
            }

            is TodayAction.ChooseRoutine -> {
                chooseRoutine(action.id)
            }

            TodayAction.ConfirmReplace -> {
                confirmReplace()
            }

            TodayAction.CancelReplace -> {
                local.update { it.copy(confirmReplace = null) }
            }

            TodayAction.OpenCopyDay -> {
                openCopyDay()
            }

            is TodayAction.CopyDay -> {
                copyDay(action.from)
            }

            is TodayAction.SetMode -> {
                local.update { it.copy(mode = action.mode, sheet = null) }
            }

            is TodayAction.Move -> {
                viewModelScope.launch { days.moveBlock(action.id, action.by) }
            }

            is TodayAction.Remove -> {
                removeBlock(action.id)
            }

            TodayAction.OpenDetails -> {
                openDetails()
            }

            is TodayAction.SetName -> {
                editSheet<TodaySheet.Details> { it.copy(name = action.name) }
            }

            is TodayAction.SetNotes -> {
                editSheet<TodaySheet.Details> { it.copy(notes = action.notes) }
            }

            // Starts from the day's name, made free ("Legs 2"), so Save works at once.
            TodayAction.OpenSaveAs -> {
                local.update { it.copy(sheet = null) }
                viewModelScope.launch {
                    val base = day()?.name?.trim().orEmpty()
                    val name = if (base.isEmpty()) "" else freeRoutineName(base, routines.routines.first().map { it.name })
                    local.update { it.copy(saveAs = SaveAsRoutine(name)) }
                }
            }

            is TodayAction.SetSaveAsName -> {
                local.update { state -> state.copy(saveAs = state.saveAs?.copy(name = action.name, error = null)) }
            }

            TodayAction.SaveAsRoutine -> {
                saveAsRoutine()
            }

            TodayAction.CancelSaveAs -> {
                local.update { it.copy(saveAs = null) }
            }

            is TodayAction.EditTime -> {
                editTime(action.field)
            }

            is TodayAction.SetTime -> {
                setTime(action.time)
            }

            TodayAction.CloseTimePicker -> {
                local.update { it.copy(timePicker = null) }
            }

            TodayAction.EndAtLastSet -> {
                endAtLastSet()
            }

            TodayAction.ClearEnd -> {
                clearEnd()
            }

            TodayAction.Start -> {
                start()
            }

            TodayAction.StartPlan -> {
                applyPlan(andStart = true)
            }

            TodayAction.UsePlan -> {
                applyPlan(andStart = false)
            }

            is TodayAction.ApplyRoutine -> {
                applyToEmpty(action.id)
            }

            is TodayAction.SetBodyWeight -> {
                editSheet<TodaySheet.Details> { it.copy(bodyWeight = action.text) }
            }

            is TodayAction.SetBodyWeightUnit -> {
                editSheet<TodaySheet.Details> { it.copy(bodyWeightUnit = action.unit) }
            }

            TodayAction.Resume -> {
                resume()
            }

            TodayAction.Review -> {
                review()
            }

            TodayAction.StartFromShortcut -> {
                startFromShortcut()
            }

            TodayAction.Warmup -> {
                viewModelScope.launch { runWarmup(date()) }
            }

            TodayAction.PlayerSeen -> {
                local.update { it.copy(player = false) }
            }

            is TodayAction.Open -> {
                local.update { it.copy(opened = action.id) }
            }

            TodayAction.OpenedSeen -> {
                local.update { it.copy(opened = null) }
            }

            TodayAction.Finish -> {
                finish()
            }

            TodayAction.Reopen -> {
                reopen()
            }

            TodayAction.DeleteDay -> {
                deleteDay()
            }

            TodayAction.OpenPicker -> {
                local.update { it.copy(sheet = null, pickerOpen = true) }
            }

            TodayAction.ClosePicker -> {
                local.update { it.copy(pickerOpen = false) }
            }

            is TodayAction.Added -> {
                added(action.id, action.name)
            }

            TodayAction.Undo -> {
                undo()
            }

            TodayAction.UndoSeen -> {
                undoAction = null
                local.update { it.copy(undo = null) }
            }
        }
    }

    private inline fun <reified T : TodaySheet> editSheet(crossinline change: (T) -> T) {
        local.update { state -> (state.sheet as? T)?.let { state.copy(sheet = change(it)) } ?: state }
    }

    // The details sheet writes its name, notes and body weight when it closes. A blank body weight
    // deletes the day's own reading and text that isn't a weight changes nothing; a reading deleted or
    // replaced is offered back.
    private fun closeCurrentSheet() {
        val details = local.value.sheet as? TodaySheet.Details
        closeSheet()
        if (details == null) return
        val date = date()
        viewModelScope.launch {
            val day = days.day(date).first()
            if ((day?.name ?: "") != details.name.trim()) days.setName(date, details.name)
            if ((day?.notes ?: "") != details.notes) days.setNotes(date, details.notes)
            val own = bodyWeights.readings.first().firstOrNull { it.date == date }
            val weight =
                when (val entry = BodyWeights.entry(details.bodyWeight)) {
                    BodyWeightEntry.Invalid -> return@launch
                    BodyWeightEntry.Delete -> null
                    is BodyWeightEntry.Reading -> entry.weight
                }
            if (weight == own?.weight && (own == null || details.bodyWeightUnit == own.unit)) return@launch
            val before = bodyWeights.set(date, weight, details.bodyWeightUnit) ?: return@launch
            offerUndo(TodayUndo.BodyWeightChanged(before, deleted = weight == null)) {
                bodyWeights.restore(before)
                true
            }
        }
    }

    private fun openChooser() {
        viewModelScope.launch {
            val rows = routines.routines.first().map { it.toRow() }
            local.update { it.copy(sheet = TodaySheet.ChooseRoutine(rows)) }
        }
    }

    // Replacing a day that has exercises asks first; adding, or filling an empty day, doesn't.
    private fun chooseRoutine(id: RoutineId) {
        val sheet = local.value.sheet as? TodaySheet.ChooseRoutine ?: return
        val row = sheet.routines.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            val populated = !day()?.blocks.isNullOrEmpty()
            if (populated && sheet.replace) {
                local.update { it.copy(sheet = null, confirmReplace = row) }
            } else {
                closeSheet()
                days.applyRoutine(date(), id, replace = sheet.replace && populated)
            }
        }
    }

    private fun confirmReplace() {
        val row = local.value.confirmReplace ?: return
        local.update { it.copy(confirmReplace = null) }
        viewModelScope.launch { days.applyRoutine(date(), row.id, replace = true) }
    }

    private fun openCopyDay() {
        viewModelScope.launch {
            val recent = days.recentDays(before = date(), limit = COPY_DAYS)
            local.update { it.copy(sheet = TodaySheet.CopyDay(recent)) }
        }
    }

    private fun copyDay(from: LocalDate) {
        closeSheet()
        viewModelScope.launch { days.copyDay(from, date()) }
    }

    private fun removeBlock(id: BlockId) {
        viewModelScope.launch {
            val removed = days.removeBlock(id) ?: return@launch
            if (day()?.blocks.isNullOrEmpty()) local.update { it.copy(mode = TodayMode.Normal) }
            // A second removal before the first offer goes joins it; the latest comes back first.
            val earlier = (local.value.undo as? TodayUndo.Removed)?.names.orEmpty()
            val restoreEarlier = undoAction.takeIf { earlier.isNotEmpty() }
            offerUndo(TodayUndo.Removed(earlier + removed.name)) {
                val restored = days.restoreBlock(removed)
                (restoreEarlier?.invoke() ?: true) && restored
            }
        }
    }

    private fun openDetails() {
        viewModelScope.launch {
            val day = day()
            val date = date()
            val readings = bodyWeights.readings.first()
            val own = readings.firstOrNull { it.date == date }
            val earlier = BodyWeights.on(readings, date)
            val unit = earlier?.unit ?: readings.firstOrNull()?.unit ?: settings.settings.first().weightUnit
            local.update {
                it.copy(
                    sheet =
                        TodaySheet.Details(
                            name = day?.name.orEmpty(),
                            notes = day?.notes.orEmpty(),
                            bodyWeight = own?.weight?.let(::formatNumber).orEmpty(),
                            bodyWeightUnit = unit,
                            earlier = earlier.takeIf { own == null },
                        ),
                )
            }
        }
    }

    private fun saveAsRoutine() {
        val dialog = local.value.saveAs ?: return
        viewModelScope.launch {
            val day = day() ?: return@launch
            val name = dialog.name.trim()
            when (routines.createWithItems(name, DayPlan.routineItems(day))) {
                is RoutineNameResult.Saved -> local.update { it.copy(saveAs = null, undo = TodayUndo.Saved(name)) }
                RoutineNameResult.NameBlank -> local.update { it.copy(saveAs = dialog.copy(error = NameError.Blank)) }
                RoutineNameResult.NameTaken -> local.update { it.copy(saveAs = dialog.copy(error = NameError.Taken)) }
            }
        }
    }

    // An end earlier than the start means the workout ran past midnight.
    private fun editTime(field: TimeField) {
        viewModelScope.launch {
            val day = day()
            val at = (if (field == TimeField.Start) day?.startedAt else day?.finishedAt) ?: clock.now()
            local.update { it.copy(timePicker = TimeEdit(field, at.localTime(zone))) }
        }
    }

    private fun setTime(time: LocalTime) {
        val edit = local.value.timePicker ?: return
        val date = date()
        viewModelScope.launch {
            val day = days.day(date).first()
            when (edit.field) {
                TimeField.Start -> {
                    val start = SessionClock.atLocal(date, time, zone)
                    val end = day?.finishedAt
                    if (end != null && start > end) {
                        local.update { it.copy(timePicker = edit.copy(initial = time, startAfterEnd = true)) }
                        return@launch
                    }
                    local.update { it.copy(timePicker = null) }
                    days.setTimes(date, start, end)
                }

                TimeField.End -> {
                    local.update { it.copy(timePicker = null) }
                    val start = day?.startedAt ?: return@launch
                    days.setTimes(date, start, SessionClock.atLocal(date, time, zone, notBefore = start))
                }
            }
        }
    }

    // A plain time edit; Reopen is the "keep going" that moves the start on.
    private fun clearEnd() {
        viewModelScope.launch {
            val day = day() ?: return@launch
            days.setTimes(day.date, day.startedAt, null)
        }
    }

    private fun endAtLastSet() {
        viewModelScope.launch {
            val day = day() ?: return@launch
            val last = day.lastDoneAt ?: return@launch
            days.setTimes(day.date, day.startedAt ?: last, last)
        }
    }

    // The active program's routine for the date, if it plans one that still exists.
    private suspend fun plannedRoutine(date: LocalDate): RoutineId? {
        val id = programs.active.first()?.let { ProgramSchedule.planFor(it, date).routineId } ?: return null
        return id.takeIf { routines.routine(it).first() != null }
    }

    // Only an empty day takes the plan, and only from this one tap: adding an exercise never does.
    // Only today starts: other days get their times by hand.
    private fun applyPlan(andStart: Boolean) {
        val date = date()
        viewModelScope.launch {
            if (!day()?.blocks.isNullOrEmpty()) return@launch
            val routine = plannedRoutine(date) ?: return@launch
            days.applyRoutine(date, routine, replace = false)
            if (andStart && date == clock.today(zone)) start()
        }
    }

    private fun applyToEmpty(id: RoutineId) {
        val date = date()
        viewModelScope.launch {
            if (!day()?.blocks.isNullOrEmpty()) return@launch
            days.applyRoutine(date, id, replace = false)
        }
    }

    // Start applies nothing; it starts the clock and opens the work: the warm-up on the player while it
    // has sets left, else the first exercise with sets left.
    private fun start() {
        viewModelScope.launch {
            val day = day() ?: return@launch
            days.start(day.date)
            if (day.blocks.any { it.section == WARMUP_SECTION && it.hasSetsLeft } && runWarmup(day.date)) return@launch
            day.firstUnfinished?.let { block -> local.update { it.copy(opened = block.id) } }
        }
    }

    // Only today's unfinished workout runs its warm-up on the player.
    private suspend fun runWarmup(date: LocalDate): Boolean {
        val day = days.day(date).first() ?: return false
        if (date != clock.today(zone) || day.finished) return false
        if (!guided.startWarmup(date)) return false
        local.update { it.copy(player = true) }
        return true
    }

    // The exercise the hero card names: last open while it has sets left, else the first with sets left.
    // A warm-up still running, or a warm-up block that's next, resumes on the player. Once past the
    // warm-up (the last exercise open is a working one), a skipped warm-up isn't what's next.
    private fun resume() {
        viewModelScope.launch {
            val running = guided.state.value
            if (running.active && running.snapshot?.source == GuidedSource.Warmup(date())) {
                local.update { it.copy(player = true) }
                return@launch
            }
            val day = day() ?: return@launch
            val saved = workout.position(day.date)?.let { id -> day.blocks.firstOrNull { it.id == id } }
            val work = day.blocks.filter { it.section != WARMUP_SECTION }
            val target =
                if (saved != null && saved.section != WARMUP_SECTION) {
                    saved.takeIf { it.hasSetsLeft } ?: work.firstOrNull { it.hasSetsLeft } ?: day.current(saved.id)
                } else {
                    day.current(saved?.id)
                }
            if (target?.section == WARMUP_SECTION && runWarmup(day.date)) return@launch
            (target ?: saved ?: day.firstUnfinished)?.let { block -> local.update { it.copy(opened = block.id) } }
        }
    }

    private fun review() {
        viewModelScope.launch {
            val day = day() ?: return@launch
            day.firstUnfinished?.let { block -> local.update { it.copy(opened = block.id) } }
        }
    }

    private fun startFromShortcut() {
        select(clock.today(zone))
        viewModelScope.launch {
            val day = day()
            when {
                // Start workout is the one tap that applies the plan to an empty today.
                day == null || day.blocks.isEmpty() -> applyPlan(andStart = true)

                day.finished -> Unit

                day.started -> resume()

                else -> start()
            }
        }
    }

    // Fatigue and notes are easiest to say right after the last set, so finishing opens the summary.
    private fun finish() {
        closeSheet()
        val date = date()
        viewModelScope.launch {
            days.finish(date)
            local.update { it.copy(summary = date) }
        }
    }

    private fun reopen() {
        closeSheet()
        viewModelScope.launch { days.reopen(date()) }
    }

    private fun deleteDay() {
        closeSheet()
        val date = date()
        local.update { it.copy(mode = TodayMode.Normal) }
        viewModelScope.launch {
            val deleted = days.deleteDay(date) ?: return@launch
            offerUndo(TodayUndo.Deleted(date)) { days.restoreDay(deleted) }
        }
    }

    private fun added(
        id: BlockId,
        name: String,
    ) {
        local.update { it.copy(pickerOpen = false) }
        offerUndo(TodayUndo.Added(name)) { days.removeBlock(id) != null }
    }

    private fun undo() {
        val action = undoAction ?: return
        undoAction = null
        local.update { it.copy(undo = null) }
        viewModelScope.launch { if (!action()) local.update { it.copy(undo = TodayUndo.Failed) } }
    }

    private companion object {
        const val COPY_DAYS = 8
    }
}

// Days with a recovery session: the calendar's own mark.
private fun List<GuidedLog>.recoveryDays(): Set<LocalDate> = mapTo(mutableSetOf()) { it.day }
