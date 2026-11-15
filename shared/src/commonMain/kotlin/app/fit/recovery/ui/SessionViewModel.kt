// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.recovery.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.content.Markdown
import app.fit.content.MdBlock
import app.fit.guided.ContentImages
import app.fit.guided.DeletedPlan
import app.fit.guided.GuidedController
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedRepository
import app.fit.guided.loadAll
import app.fit.reminders.Reminder
import app.fit.reminders.ReminderNotices
import app.fit.reminders.ReminderRepository
import app.fit.reminders.ReminderRules
import app.fit.reminders.Reminders
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalTime

@Immutable
data class SessionUiState(
    val loading: Boolean = true,
    val plan: GuidedPlan? = null,
    // The notes as Markdown blocks, and their images by path.
    val notes: List<MdBlock> = emptyList(),
    val images: Map<String, ByteArray?> = emptyMap(),
    // The player to open after Start; the screen navigates and acknowledges.
    val opened: Boolean = false,
    // The title of the session just deleted, while Undo is offered.
    val deleted: String? = null,
    val undoFailed: Boolean = false,
    // The offer ran out with the session gone: the screen leaves and acknowledges.
    val closed: Boolean = false,
    // The daily reminder: on or off, and its time (the default until one is picked).
    val reminderOn: Boolean = false,
    val reminderTime: LocalTime = ReminderRules.DEFAULT_TIME,
    // It's on, but notifications aren't allowed, so it can't show.
    val reminderBlocked: Boolean = false,
    val pickingTime: Boolean = false,
)

sealed interface SessionAction {
    data object TogglePin : SessionAction

    data object Start : SessionAction

    data object OpenedSeen : SessionAction

    data object Delete : SessionAction

    data object UndoDelete : SessionAction

    data object UndoSeen : SessionAction

    data class SetReminder(
        val on: Boolean,
    ) : SessionAction

    data object PickTime : SessionAction

    // Null: the picker was closed without a time.
    data class TimePicked(
        val time: LocalTime?,
    ) : SessionAction

    // The user's tap on Allow, after notifications were refused.
    data object AllowNotifications : SessionAction

    // Back on the screen: notifications may have been allowed or switched off meanwhile.
    data object Refresh : SessionAction
}

private data class SessionLocal(
    val images: Map<String, ByteArray?> = emptyMap(),
    val opened: Boolean = false,
    val deleted: DeletedPlan? = null,
    val undoFailed: Boolean = false,
    val closed: Boolean = false,
    val notificationsAllowed: Boolean = true,
    val pickingTime: Boolean = false,
)

// A recovery session's page: its steps, notes and sources, Pin, Start, its daily reminder, and
// Delete with Undo. A session that's running ends before it's deleted.
class SessionViewModel(
    id: Long,
    private val plans: GuidedRepository,
    private val controller: GuidedController,
    private val contentImages: ContentImages,
    reminderRepository: ReminderRepository,
    private val reminders: Reminders,
    private val notices: ReminderNotices,
) : ViewModel() {
    private val planId = GuidedPlanId(id)
    private val local = MutableStateFlow(SessionLocal(notificationsAllowed = notices.allowed()))
    private val reminder: StateFlow<Reminder?> =
        reminderRepository.reminder(planId).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val notes: Flow<List<MdBlock>> =
        plans
            .plan(planId)
            .map { it?.notes.orEmpty() }
            .distinctUntilChanged()
            .map { Markdown.parse(it).blocks }

    val uiState: StateFlow<SessionUiState> =
        combine(plans.plan(planId), notes, local, reminder) { plan, notes, local, reminder ->
            SessionUiState(
                loading = false,
                plan = plan,
                notes = notes,
                images = local.images,
                opened = local.opened,
                deleted = local.deleted?.title,
                undoFailed = local.undoFailed,
                closed = local.closed,
                reminderOn = reminder?.enabled == true,
                reminderTime = reminder?.time ?: ReminderRules.DEFAULT_TIME,
                reminderBlocked = reminder?.enabled == true && !local.notificationsAllowed,
                pickingTime = local.pickingTime,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SessionUiState())

    init {
        viewModelScope.launch {
            notes.collect { blocks ->
                val loaded = contentImages.loadAll(blocks.filterIsInstance<MdBlock.Image>().map { it.path })
                local.update { it.copy(images = loaded) }
            }
        }
    }

    fun onAction(action: SessionAction) {
        when (action) {
            SessionAction.TogglePin -> {
                val plan = uiState.value.plan ?: return
                viewModelScope.launch { plans.setPinned(planId, !plan.pinned) }
            }

            SessionAction.Start -> {
                viewModelScope.launch { if (controller.startPlan(planId)) local.update { it.copy(opened = true) } }
            }

            SessionAction.OpenedSeen -> {
                local.update { it.copy(opened = false) }
            }

            SessionAction.Delete -> {
                viewModelScope.launch {
                    controller.close(planId)
                    val deleted = plans.delete(planId) ?: return@launch
                    local.update { it.copy(deleted = deleted, undoFailed = false) }
                }
            }

            SessionAction.UndoDelete -> {
                val deleted = local.value.deleted ?: return
                local.update { it.copy(deleted = null) }
                viewModelScope.launch { if (!plans.restore(deleted)) local.update { it.copy(undoFailed = true) } }
            }

            // The offer or the "couldn't undo" notice went; with the session gone there's nothing to show.
            SessionAction.UndoSeen -> {
                local.update { it.copy(deleted = null, undoFailed = false, closed = uiState.value.plan == null) }
            }

            // Switching it on is when notifications are asked for; refused, the reminder stays on and
            // the row says why it can't show.
            is SessionAction.SetReminder -> {
                viewModelScope.launch {
                    reminders.set(planId, time = null, enabled = action.on)
                    if (action.on) allowed(notices.request())
                }
            }

            SessionAction.PickTime -> {
                local.update { it.copy(pickingTime = true) }
            }

            is SessionAction.TimePicked -> {
                local.update { it.copy(pickingTime = false) }
                val time = action.time ?: return
                viewModelScope.launch { reminders.set(planId, time, enabled = true) }
            }

            SessionAction.AllowNotifications -> {
                viewModelScope.launch { allowed(notices.request(orSettings = true)) }
            }

            SessionAction.Refresh -> {
                viewModelScope.launch { allowed(notices.allowed()) }
            }
        }
    }

    // Once notifications are allowed, a reminder that was waiting for that can show.
    private suspend fun allowed(now: Boolean) {
        val before = local.value.notificationsAllowed
        local.update { it.copy(notificationsAllowed = now) }
        if (now && !before) reminders.check()
    }
}
