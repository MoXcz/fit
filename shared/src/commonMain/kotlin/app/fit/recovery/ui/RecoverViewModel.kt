// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.core.BodyArea
import app.fit.guided.GuidedController
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedRepository
import app.fit.guided.GuidedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class RecoverUiState(
    val loading: Boolean = true,
    // Every recovery session, by title.
    val plans: List<GuidedPlan> = emptyList(),
    // Null shows every session.
    val area: BodyArea? = null,
    // The player to open after a start; the screen navigates and acknowledges.
    val opened: Boolean = false,
    // A session that's running (or paused), shown first with Resume so it's never lost.
    val running: GuidedState? = null,
) {
    // The latest pin first.
    val pinned: List<GuidedPlan> get() = plans.filter { it.pinned }.sortedByDescending { it.pinnedAt }

    // Only areas some session helps, in the content format's order.
    val areas: List<BodyArea> get() = BodyArea.entries.filter { area -> plans.any { area in it.areas } }

    val shown: List<GuidedPlan> get() = if (area == null) plans else plans.filter { area in it.areas }
}

sealed interface RecoverAction {
    // Picking the chosen area again shows every session.
    data class SelectArea(
        val area: BodyArea,
    ) : RecoverAction

    data class Start(
        val id: GuidedPlanId,
    ) : RecoverAction

    data object OpenedSeen : RecoverAction

    // Back to the running session.
    data object Resume : RecoverAction
}

private data class RecoverLocal(
    val area: BodyArea? = null,
    val opened: Boolean = false,
)

class RecoverViewModel(
    plans: GuidedRepository,
    private val controller: GuidedController,
) : ViewModel() {
    private val local = MutableStateFlow(RecoverLocal())

    val uiState: StateFlow<RecoverUiState> =
        combine(plans.plans(GuidedKind.Recovery), local, controller.state) { plans, local, guided ->
            RecoverUiState(
                loading = false,
                plans = plans,
                area =
                    local.area?.takeIf { area ->
                        plans.any { area in it.areas }
                    },
                opened = local.opened,
                running = guided.takeIf { it.active },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecoverUiState())

    fun onAction(action: RecoverAction) {
        when (action) {
            is RecoverAction.SelectArea -> {
                local.update { it.copy(area = action.area.takeIf { area -> area != it.area }) }
            }

            is RecoverAction.Start -> {
                viewModelScope.launch { if (controller.startPlan(action.id)) local.update { it.copy(opened = true) } }
            }

            RecoverAction.Resume -> {
                local.update { it.copy(opened = true) }
            }

            RecoverAction.OpenedSeen -> {
                local.update { it.copy(opened = false) }
            }
        }
    }
}
