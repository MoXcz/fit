// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.settings.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.core.WeightUnit
import app.fit.settings.Settings
import app.fit.settings.SettingsRepository
import app.fit.settings.steppedBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@Immutable
data class SettingsUiState(
    val loading: Boolean = true,
    val settings: Settings = Settings(),
)

sealed interface SettingsAction {
    data class SetUnit(
        val unit: WeightUnit,
    ) : SettingsAction

    data class StepBar(
        val unit: WeightUnit,
        val up: Boolean,
    ) : SettingsAction

    data class SetAlarm(
        val on: Boolean,
    ) : SettingsAction

    data class SetKeepScreenOn(
        val on: Boolean,
    ) : SettingsAction

    data class SetAutoAdvance(
        val on: Boolean,
    ) : SettingsAction
}

class SettingsViewModel(
    private val repository: SettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<SettingsUiState> =
        repository.settings
            .map { SettingsUiState(loading = false, settings = it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.SetUnit -> {
                viewModelScope.launch { repository.setWeightUnit(action.unit) }
            }

            is SettingsAction.StepBar -> {
                viewModelScope.launch {
                    val settings = repository.settings.first()
                    repository.setBarWeight(action.unit, settings.steppedBar(action.unit, action.up))
                }
            }

            is SettingsAction.SetAlarm -> {
                viewModelScope.launch { repository.setAlarm(action.on) }
            }

            is SettingsAction.SetKeepScreenOn -> {
                viewModelScope.launch { repository.setKeepScreenOn(action.on) }
            }

            is SettingsAction.SetAutoAdvance -> {
                viewModelScope.launch { repository.setAutoAdvance(action.on) }
            }
        }
    }
}
