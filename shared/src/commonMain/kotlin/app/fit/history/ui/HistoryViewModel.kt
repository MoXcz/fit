// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.DayRepository
import app.fit.day.WorkoutDay
import app.fit.day.today
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedRepository
import app.fit.history.CalendarCell
import app.fit.history.HistoryStats
import app.fit.history.MonthGrid
import app.fit.history.Record
import app.fit.history.Records
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

// The day picked in the calendar; [workout] is null unless a working set was done.
@Immutable
data class PickedDay(
    val date: LocalDate,
    val workout: WorkoutDay? = null,
    val records: List<Record> = emptyList(),
    val recovery: List<GuidedLog> = emptyList(),
)

@Immutable
data class HistoryUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate(2026, 1, 1),
    // The first of the month the calendar shows.
    val month: LocalDate = today,
    val weeks: List<List<CalendarCell?>> = emptyList(),
    val stats: HistoryStats = HistoryStats(),
    // Days with a done working set, newest first.
    val days: List<WorkoutDay> = emptyList(),
    // Today until a day is tapped.
    val picked: PickedDay = PickedDay(today),
)

sealed interface HistoryAction {
    // [by] months: negative is back.
    data class ShowMonth(
        val by: Int,
    ) : HistoryAction

    // A day tapped in the calendar: shown under it, not opened.
    data class Pick(
        val date: LocalDate,
    ) : HistoryAction
}

// Recovery sessions get their own mark on the calendar and nothing else: they never count as
// workouts, in the streak or in the last 7 days.
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val days: DayRepository,
    private val logs: GuidedRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    // Months away from today's; the calendar follows today across midnight.
    private val monthOffset = MutableStateFlow(0)

    // Today's date, checked every second so the streak and the outline move on at midnight.
    private val today: Flow<LocalDate> =
        flow {
            while (true) {
                emit(clock.today(zone))
                delay(1.seconds)
            }
        }.distinctUntilChanged().shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val trained: Flow<List<WorkoutDay>> =
        days.trainedDays().shareIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            replay = 1,
        )

    private val month: Flow<Pair<LocalDate, LocalDate>> =
        combine(today, monthOffset) { today, offset -> today to MonthGrid.monthOf(today).plus(offset, DateTimeUnit.MONTH) }

    // Null until a day is tapped: History then shows today and moves on with it at midnight.
    private val tapped = MutableStateFlow<LocalDate?>(null)

    private val pickedDate: Flow<LocalDate> = combine(today, tapped) { today, tapped -> tapped ?: today }.distinctUntilChanged()

    // Only the sets decide the records, so a note or fatigue written doesn't run them again.
    private val pickedRecords: Flow<Pair<LocalDate, List<Record>>> =
        combine(trained, pickedDate) { trained, date ->
            date to trained.firstOrNull { it.date == date }?.let { WorkoutDay(it.id, it.date, blocks = it.blocks) }
        }.distinctUntilChanged().mapLatest { (date, day) ->
            date to day?.let { Records.of(it, days.earlierSets(it.blocks.map { b -> b.exercise.id }, it.date)) }.orEmpty()
        }

    private val pickedRecovery: Flow<Pair<LocalDate, List<GuidedLog>>> =
        pickedDate.flatMapLatest { date -> logs.logs(GuidedKind.Recovery, date, date).map { date to it } }

    private val recovery: Flow<Set<LocalDate>> =
        month.map { it.second }.distinctUntilChanged().flatMapLatest { month ->
            logs.logs(GuidedKind.Recovery, month, MonthGrid.lastDay(month)).map { list ->
                list.mapTo(mutableSetOf()) { it.day }
            }
        }

    val uiState: StateFlow<HistoryUiState> =
        combine(trained, month, recovery, pickedDate, combine(pickedRecords, pickedRecovery, ::Pair)) {
            trained,
            (today, month),
            recovery,
            date,
            (records, logs),
            ->
            HistoryUiState(
                loading = false,
                today = today,
                month = month,
                weeks = MonthGrid.cells(month, today, selected = date, days = trained, recovery = recovery),
                stats = HistoryStats.of(trained.mapTo(mutableSetOf()) { it.date }, today),
                days = trained,
                // What was read for another day is left out until this day's arrives.
                picked =
                    PickedDay(
                        date = date,
                        workout = trained.firstOrNull { it.date == date },
                        records = records.second.takeIf { records.first == date }.orEmpty(),
                        recovery = logs.second.takeIf { logs.first == date }.orEmpty(),
                    ),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun onAction(action: HistoryAction) {
        when (action) {
            is HistoryAction.ShowMonth -> monthOffset.update { it + action.by }
            is HistoryAction.Pick -> tapped.value = action.date
        }
    }
}
