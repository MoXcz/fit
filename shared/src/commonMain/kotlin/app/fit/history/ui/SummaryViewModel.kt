// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.history.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.DayRepository
import app.fit.day.Fatigue
import app.fit.day.WorkoutDay
import app.fit.day.today
import app.fit.history.Record
import app.fit.history.Records
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds

@Immutable
data class SummaryUiState(
    val loading: Boolean = true,
    val date: LocalDate = LocalDate(2026, 1, 1),
    val today: LocalDate = date,
    // Null once loaded: nothing was ever added to the day.
    val day: WorkoutDay? = null,
    val records: List<Record> = emptyList(),
)

sealed interface SummaryAction {
    // The notes as typed; written once typing pauses.
    data class SetNotes(
        val notes: String,
    ) : SummaryAction

    // Writes notes still waiting for the pause (the screen is leaving).
    data object SaveNotes : SummaryAction

    // Null clears it.
    data class SetFatigue(
        val fatigue: Int?,
    ) : SummaryAction
}

// A day's result, records against earlier days, its notes and fatigue. The screen holds the notes'
// text; they're written here when typing pauses and when the screen or this ViewModel goes, on
// [scope] (the app's), so leaving the summary right after typing still saves them.
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SummaryViewModel(
    date: String,
    private val days: DayRepository,
    private val clock: Clock,
    private val zone: TimeZone,
    private val scope: CoroutineScope,
) : ViewModel() {
    private val date = LocalDate.parse(date)
    private val pending = MutableStateFlow<String?>(null)
    private var lastWrite: Job? = null

    // One read of the day for both the screen and the records.
    private val day = days.day(this.date).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    // Only the sets decide the records, so a note or fatigue written doesn't run them again.
    private val records =
        day
            .map { it?.let { d -> WorkoutDay(d.id, d.date, blocks = d.blocks) } }
            .distinctUntilChanged()
            .mapLatest { it?.let { d -> Records.of(d, days.earlierSets(d.blocks.map { b -> b.exercise.id }, d.date)) }.orEmpty() }

    val uiState: StateFlow<SummaryUiState> =
        combine(day, records) { day, records ->
            SummaryUiState(loading = false, date = this.date, today = clock.today(zone), day = day, records = records)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SummaryUiState(date = this.date))

    init {
        viewModelScope.launch { pending.filterNotNull().debounce(NOTES_PAUSE).collect { saveNotes() } }
    }

    fun onAction(action: SummaryAction) {
        when (action) {
            is SummaryAction.SetNotes -> {
                pending.value = action.notes
            }

            SummaryAction.SaveNotes -> {
                saveNotes()
            }

            is SummaryAction.SetFatigue -> {
                if (Fatigue.isValid(action.fatigue)) viewModelScope.launch { days.setFatigue(date, action.fatigue) }
            }
        }
    }

    // Writes run one after another, so an older draft never lands after a newer one.
    private fun saveNotes() {
        val notes = pending.value ?: return
        pending.value = null
        val previous = lastWrite
        lastWrite =
            scope.launch {
                previous?.join()
                days.setNotes(date, notes)
            }
    }

    override fun onCleared() = saveNotes()

    private companion object {
        val NOTES_PAUSE = 500.milliseconds
    }
}
