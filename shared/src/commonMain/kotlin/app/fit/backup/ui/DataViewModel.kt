// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import app.fit.backup.BackupCheck
import app.fit.backup.BackupExport
import app.fit.backup.Backups
import app.fit.backup.ReadBackup
import app.fit.core.Documents
import app.fit.core.OpenedDocument
import app.fit.core.SaveResult
import app.fit.day.localDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

// What the user is asked before it happens: none of these can be undone.
sealed interface DataConfirm {
    // A backup that was read and fits; [madeOn] is the day it was exported.
    data class Restore(
        val madeOn: LocalDate,
    ) : DataConfirm

    data object WipeHistory : DataConfirm

    data object WipeEverything : DataConfirm
}

enum class DataNotice {
    Exported,
    ExportFailed,
    ExportTooLarge,
    Restored,
    HistoryWiped,
    EverythingWiped,
    NotABackup,
    NewerBackup,
    TooLarge,
    Unreadable,
    RestoreFailed,
}

@Immutable
data class DataUiState(
    val busy: Boolean = false,
    val confirm: DataConfirm? = null,
    val notice: DataNotice? = null,
)

sealed interface DataAction {
    data object Export : DataAction

    // Picks a backup and reads it; the confirmation follows if it can be restored.
    data object Restore : DataAction

    data object WipeHistory : DataAction

    data object WipeEverything : DataAction

    data object Confirm : DataAction

    data object Cancel : DataAction

    data object NoticeSeen : DataAction
}

// Backup and data: export, restore and the two wipes, each destructive one behind a confirmation.
class DataViewModel(
    private val backups: Backups,
    private val documents: Documents,
    private val clock: Clock,
    private val zone: TimeZone,
    // The app's scope: a restore or wipe under way isn't cut short by leaving the screen.
    private val scope: CoroutineScope,
) : ViewModel() {
    private val state = MutableStateFlow(DataUiState())
    private var picked: ReadBackup? = null

    val uiState: StateFlow<DataUiState> = state.asStateFlow()

    fun onAction(action: DataAction) {
        when (action) {
            DataAction.Export -> {
                work {
                    when (val export = backups.export()) {
                        BackupExport.TooLarge -> {
                            DataNotice.ExportTooLarge
                        }

                        BackupExport.Unwritable -> {
                            DataNotice.ExportFailed
                        }

                        // A file that didn't save must never pass for a backup.
                        is BackupExport.File -> {
                            when (documents.save("fit-backup-${clock.now().localDate(zone)}.zip", export.bytes)) {
                                SaveResult.Saved -> DataNotice.Exported
                                SaveResult.Cancelled -> null
                                SaveResult.Failed -> DataNotice.ExportFailed
                            }
                        }
                    }
                }
            }

            DataAction.Restore -> {
                work {
                    when (val opened = documents.open()) {
                        OpenedDocument.Cancelled -> {
                            null
                        }

                        OpenedDocument.Unreadable -> {
                            DataNotice.Unreadable
                        }

                        OpenedDocument.TooLarge -> {
                            DataNotice.TooLarge
                        }

                        is OpenedDocument.File -> {
                            when (val check = backups.check(opened.bytes)) {
                                BackupCheck.Invalid -> {
                                    DataNotice.NotABackup
                                }

                                BackupCheck.Newer -> {
                                    DataNotice.NewerBackup
                                }

                                BackupCheck.TooLarge -> {
                                    DataNotice.TooLarge
                                }

                                is BackupCheck.Valid -> {
                                    picked = check.backup
                                    state.update { it.copy(confirm = DataConfirm.Restore(check.backup.exportedAt.localDate(zone))) }
                                    null
                                }
                            }
                        }
                    }
                }
            }

            DataAction.WipeHistory -> {
                if (!state.value.busy) state.update { it.copy(confirm = DataConfirm.WipeHistory) }
            }

            DataAction.WipeEverything -> {
                if (!state.value.busy) state.update { it.copy(confirm = DataConfirm.WipeEverything) }
            }

            DataAction.Confirm -> {
                val confirm = state.value.confirm ?: return
                state.update { it.copy(confirm = null) }
                work {
                    when (confirm) {
                        is DataConfirm.Restore -> {
                            val backup = picked
                            picked = null
                            if (backup != null && backups.restore(backup)) DataNotice.Restored else DataNotice.RestoreFailed
                        }

                        DataConfirm.WipeHistory -> {
                            backups.wipeHistory()
                            DataNotice.HistoryWiped
                        }

                        DataConfirm.WipeEverything -> {
                            backups.wipeEverything()
                            DataNotice.EverythingWiped
                        }
                    }
                }
            }

            DataAction.Cancel -> {
                picked = null
                state.update { it.copy(confirm = null) }
            }

            DataAction.NoticeSeen -> {
                state.update { it.copy(notice = null) }
            }
        }
    }

    // One thing at a time; its result, if any, is the notice.
    private fun work(block: suspend () -> DataNotice?) {
        if (state.value.busy) return
        state.update { it.copy(busy = true, notice = null) }
        scope.launch {
            val notice =
                try {
                    block()
                } finally {
                    state.update { it.copy(busy = false) }
                }
            if (notice != null) state.update { it.copy(notice = notice) }
        }
    }
}
