// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup.ui

import app.fit.backup.BackupExport
import app.fit.content.FakeBackups
import app.fit.core.OpenedDocument
import app.fit.core.SaveResult
import app.fit.guided.FakeDocuments
import app.fit.workout.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class DataViewModelTest {
    private val backups = FakeBackups()
    private val documents = FakeDocuments()

    private fun TestScope.viewModel() = DataViewModel(backups, documents, TestClock(testScheduler), TimeZone.UTC, backgroundScope)

    private fun DataViewModel.pick(text: String) {
        documents.next = OpenedDocument.File("file.zip", text.encodeToByteArray())
        onAction(DataAction.Restore)
    }

    @Test
    fun `Export saves the backup under a name with today's date`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.onAction(DataAction.Export)
            assertEquals(listOf("fit-backup-2026-11-09.zip"), documents.saved.map { it.first })
            assertEquals(
                "backup 2026-11-09T10:00:00Z",
                documents.saved
                    .single()
                    .second
                    .decodeToString(),
            )
            assertEquals(DataNotice.Exported, vm.uiState.value.notice)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `backing out of the save dialog says nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            documents.saving = SaveResult.Cancelled
            vm.onAction(DataAction.Export)
            assertEquals(DataUiState(), vm.uiState.value)
        }

    @Test
    fun `a backup that couldn't be written says so, and never says saved`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            documents.saving = SaveResult.Failed
            vm.onAction(DataAction.Export)
            assertEquals(DataUiState(notice = DataNotice.ExportFailed), vm.uiState.value)
            assertEquals(emptyList(), documents.saved)
        }

    @Test
    fun `data that can't go into a backup says so before anything is saved`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            backups.exportFails = BackupExport.TooLarge
            vm.onAction(DataAction.Export)
            assertEquals(DataNotice.ExportTooLarge, vm.uiState.value.notice)
            backups.exportFails = BackupExport.Unwritable
            vm.onAction(DataAction.Export)
            assertEquals(DataNotice.ExportFailed, vm.uiState.value.notice)
            assertEquals(emptyList(), documents.saved)
        }

    @Test
    fun `Restore reads the file first, then asks with the backup's date, and restores only when confirmed`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.pick("backup 2026-10-31T22:00:00Z")
            assertEquals(DataConfirm.Restore(LocalDate(2026, 10, 31)), vm.uiState.value.confirm)
            assertEquals(emptyList(), backups.calls)

            vm.onAction(DataAction.Cancel)
            assertNull(vm.uiState.value.confirm)
            vm.onAction(DataAction.Confirm)
            assertEquals(emptyList(), backups.calls)

            vm.pick("backup 2026-10-31T22:00:00Z")
            vm.onAction(DataAction.Confirm)
            assertEquals(listOf("restore 2026-10-31T22:00:00Z"), backups.calls)
            assertEquals(DataUiState(notice = DataNotice.Restored), vm.uiState.value)
        }

    @Test
    fun `a file that isn't a backup, a newer backup and an unreadable file say so without asking`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.pick("holiday photo")
            assertEquals(DataUiState(notice = DataNotice.NotABackup), vm.uiState.value)
            vm.pick("newer")
            assertEquals(DataUiState(notice = DataNotice.NewerBackup), vm.uiState.value)
            vm.pick("huge")
            assertEquals(DataUiState(notice = DataNotice.TooLarge), vm.uiState.value)
            documents.next = OpenedDocument.TooLarge
            vm.onAction(DataAction.Restore)
            assertEquals(DataUiState(notice = DataNotice.TooLarge), vm.uiState.value)
            documents.next = OpenedDocument.Unreadable
            vm.onAction(DataAction.Restore)
            assertEquals(DataUiState(notice = DataNotice.Unreadable), vm.uiState.value)
            // A cancelled picker clears the old notice and says nothing new.
            vm.onAction(DataAction.Restore)
            assertEquals(DataUiState(), vm.uiState.value)
            assertEquals(emptyList(), backups.calls)
        }

    @Test
    fun `a restore that no longer fits says nothing changed`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.pick("backup 2026-10-31T22:00:00Z")
            backups.restoreFits = false
            vm.onAction(DataAction.Confirm)
            assertEquals(DataNotice.RestoreFailed, vm.uiState.value.notice)
        }

    @Test
    fun `each wipe asks first and does nothing when cancelled`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.onAction(DataAction.WipeHistory)
            assertEquals(DataConfirm.WipeHistory, vm.uiState.value.confirm)
            vm.onAction(DataAction.Cancel)
            vm.onAction(DataAction.WipeEverything)
            assertEquals(DataConfirm.WipeEverything, vm.uiState.value.confirm)
            vm.onAction(DataAction.Cancel)
            assertEquals(emptyList(), backups.calls)

            vm.onAction(DataAction.WipeHistory)
            vm.onAction(DataAction.Confirm)
            assertEquals(DataNotice.HistoryWiped, vm.uiState.value.notice)
            vm.onAction(DataAction.WipeEverything)
            vm.onAction(DataAction.Confirm)
            assertEquals(DataNotice.EverythingWiped, vm.uiState.value.notice)
            assertEquals(listOf("wipe history", "wipe everything"), backups.calls)
        }
}
