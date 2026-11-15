// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.bodyweight.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.BodyWeightEntry
import app.fit.bodyweight.BodyWeightRepository
import app.fit.bodyweight.BodyWeights
import app.fit.core.WeightUnit
import app.fit.day.today
import app.fit.history.ChartPoint
import app.fit.history.Metric
import app.fit.history.Progress
import app.fit.history.ProgressChart
import app.fit.settings.SettingsRepository
import app.fit.ui.formatNumber
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

// The reading being typed: today's when logging, or the one tapped.
@Immutable
data class BodyWeightEdit(
    val date: LocalDate,
    val text: String,
    val unit: WeightUnit,
) {
    // Not a weight, or above the cap: Save keeps the dialog open and the old reading.
    val invalid: Boolean get() = BodyWeights.entry(text) == BodyWeightEntry.Invalid
}

// What Undo would put back: a reading deleted, or the one a save replaced.
sealed interface BodyWeightUndo {
    val before: BodyWeight

    data class Deleted(
        override val before: BodyWeight,
    ) : BodyWeightUndo

    data class Replaced(
        override val before: BodyWeight,
    ) : BodyWeightUndo
}

@Immutable
data class BodyWeightUiState(
    val loading: Boolean = true,
    val today: LocalDate = LocalDate(2026, 1, 1),
    // Newest first.
    val readings: List<BodyWeight> = emptyList(),
    val chart: ProgressChart? = null,
    val edit: BodyWeightEdit? = null,
    // A reading just deleted or replaced, while Undo is offered.
    val undo: BodyWeightUndo? = null,
)

sealed interface BodyWeightAction {
    data object LogToday : BodyWeightAction

    data class Edit(
        val date: LocalDate,
    ) : BodyWeightAction

    data class SetText(
        val text: String,
    ) : BodyWeightAction

    data class SetUnit(
        val unit: WeightUnit,
    ) : BodyWeightAction

    // Blank or zero deletes the reading; text that isn't a weight keeps it.
    data object Save : BodyWeightAction

    data object CloseEdit : BodyWeightAction

    data class Delete(
        val date: LocalDate,
    ) : BodyWeightAction

    data object Undo : BodyWeightAction

    data object UndoSeen : BodyWeightAction
}

private data class BodyWeightLocal(
    val edit: BodyWeightEdit? = null,
    val undo: BodyWeightUndo? = null,
)

// More → Body weight: one reading a day, a line of the last ones, and every reading to edit or delete.
class BodyWeightViewModel(
    private val bodyWeights: BodyWeightRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : ViewModel() {
    private val local = MutableStateFlow(BodyWeightLocal())

    val uiState: StateFlow<BodyWeightUiState> =
        combine(bodyWeights.readings, local) { readings, local ->
            BodyWeightUiState(
                loading = false,
                today = clock.today(zone),
                readings = readings,
                chart = chartOf(readings),
                edit = local.edit,
                undo = local.undo,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BodyWeightUiState())

    fun onAction(action: BodyWeightAction) {
        when (action) {
            BodyWeightAction.LogToday -> {
                open(clock.today(zone))
            }

            is BodyWeightAction.Edit -> {
                open(action.date)
            }

            is BodyWeightAction.SetText -> {
                local.update { it.copy(edit = it.edit?.copy(text = action.text)) }
            }

            is BodyWeightAction.SetUnit -> {
                local.update { it.copy(edit = it.edit?.copy(unit = action.unit)) }
            }

            BodyWeightAction.Save -> {
                save()
            }

            BodyWeightAction.CloseEdit -> {
                local.update { it.copy(edit = null) }
            }

            is BodyWeightAction.Delete -> {
                viewModelScope.launch {
                    val deleted = bodyWeights.delete(action.date) ?: return@launch
                    local.update { it.copy(undo = BodyWeightUndo.Deleted(deleted)) }
                }
            }

            BodyWeightAction.Undo -> {
                val undo = local.value.undo ?: return
                local.update { it.copy(undo = null) }
                viewModelScope.launch { bodyWeights.restore(undo.before) }
            }

            BodyWeightAction.UndoSeen -> {
                local.update { it.copy(undo = null) }
            }
        }
    }

    // Acts at once and offers Undo for the reading it deleted or replaced.
    private fun save() {
        val edit = local.value.edit ?: return
        val entry = BodyWeights.entry(edit.text)
        if (entry == BodyWeightEntry.Invalid) return
        local.update { it.copy(edit = null) }
        viewModelScope.launch {
            val weight = (entry as? BodyWeightEntry.Reading)?.weight
            val before = bodyWeights.set(edit.date, weight, edit.unit) ?: return@launch
            val undo =
                when {
                    weight == null -> BodyWeightUndo.Deleted(before)
                    before.weight != weight || before.unit != edit.unit -> BodyWeightUndo.Replaced(before)
                    else -> null
                }
            local.update { it.copy(undo = undo) }
        }
    }

    // The day's reading if there is one; a new one in the unit last used, else the settings' unit.
    private fun open(date: LocalDate) {
        viewModelScope.launch {
            val readings = bodyWeights.readings.first()
            val own = readings.firstOrNull { it.date == date }
            val unit = own?.unit ?: readings.firstOrNull()?.unit ?: settings.settings.first().weightUnit
            local.update { it.copy(edit = BodyWeightEdit(date, own?.weight?.let(::formatNumber).orEmpty(), unit)) }
        }
    }

    private companion object {
        // Units are never converted: the line is in the latest reading's unit, the others stay off it.
        fun chartOf(readings: List<BodyWeight>): ProgressChart? {
            val unit = readings.firstOrNull()?.unit ?: return null
            val points =
                readings
                    .filter { it.unit == unit }
                    .map { ChartPoint(it.date, it.weight) }
                    .sortedBy { it.date }
                    .takeLast(Progress.CHART_SESSIONS)
            return ProgressChart(Metric.Load, unit, points)
        }
    }
}
