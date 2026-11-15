// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content.ui

import app.fit.content.ContentKind
import app.fit.content.FakeContentImporter
import app.fit.content.ImportInbox
import app.fit.content.ImportItem
import app.fit.content.ImportResult
import app.fit.core.OpenedDocument
import app.fit.guided.FakeDocuments
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ImportViewModelTest {
    @BeforeTest
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterTest
    fun tearDown() = Dispatchers.resetMain()

    private val plank = ImportItem(ContentKind.Exercise, "plank", "Plank", null)
    private val legs = ImportItem(ContentKind.Routine, "legs", "Legs", replaces = "Leg day")
    private val importer =
        FakeContentImporter(
            mutableMapOf(
                "new.zip" to FakeContentImporter.ready("new.zip", plank, newImages = listOf("images/plank.png")),
                "update.zip" to FakeContentImporter.ready("update.zip", plank, legs, replacedImages = listOf("images/legs.png")),
                "images.zip" to FakeContentImporter.ready("images.zip", newImages = listOf("images/a.png", "images/b.png")),
            ),
        )
    private val documents = FakeDocuments()
    private val inbox = ImportInbox()

    private fun TestScope.viewModel(share: String = "") = ImportViewModel(share, importer, documents, inbox, backgroundScope)

    private fun ImportViewModel.choose(name: String) {
        documents.next = OpenedDocument.File(name, ByteArray(0))
        onAction(ImportAction.Choose)
    }

    @Test
    fun `a chosen file is previewed by kind before anything is imported`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            assertNull(vm.uiState.value.file)
            vm.choose("update.zip")
            val state = vm.uiState.value
            assertEquals("update.zip", state.file)
            assertEquals(listOf(ContentKind.Exercise to listOf(plank), ContentKind.Routine to listOf(legs)), state.groups)
            assertEquals(1, state.replacedImages)
            assertTrue(state.canImport)
            assertEquals(emptyList(), importer.imported)
        }

    @Test
    fun `a pack of only new items imports without a confirmation`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.choose("new.zip")
            vm.onAction(ImportAction.Import)
            assertFalse(vm.uiState.value.confirming)
            assertEquals(listOf("new.zip"), importer.imported.map { it.file })
            // Done: the screen is ready for the next file and says what was imported.
            assertEquals(ImportUiState(notice = ImportNotice.Imported(items = 1, images = 1)), vm.uiState.value)
        }

    @Test
    fun `a pack that replaces content asks first, says how much, and imports only when confirmed`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.choose("update.zip")
            vm.onAction(ImportAction.Import)
            assertTrue(vm.uiState.value.confirming)
            // The routine and the image.
            assertEquals(2, vm.uiState.value.replaces)
            assertEquals(emptyList(), importer.imported)

            vm.onAction(ImportAction.CancelReplace)
            assertFalse(vm.uiState.value.confirming)
            assertEquals(emptyList(), importer.imported)
            assertEquals("update.zip", vm.uiState.value.file)

            vm.onAction(ImportAction.Import)
            vm.onAction(ImportAction.ConfirmReplace)
            assertEquals(listOf("update.zip"), importer.imported.map { it.file })
            assertEquals(ImportNotice.Imported(items = 2, images = 1), vm.uiState.value.notice)
        }

    @Test
    fun `a file with problems lists them and can't be imported`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.choose("notes.md")
            val state = vm.uiState.value
            assertEquals(listOf("notes.md:1: the file must start with a YAML header between --- lines"), state.problems)
            assertFalse(state.canImport)
            vm.onAction(ImportAction.Import)
            assertEquals(emptyList(), importer.imported)
            // Another file can be chosen from there.
            vm.choose("new.zip")
            assertEquals(emptyList(), vm.uiState.value.problems)
            assertTrue(vm.uiState.value.canImport)
        }

    @Test
    fun `a shared file is shown by the screen opened for it, and by no other`() =
        runTest(UnconfinedTestDispatcher()) {
            // An import screen is already open, in this activity or another.
            val open = viewModel()
            open.choose("update.zip")
            val id = inbox.offer(OpenedDocument.File("new.zip", ByteArray(0)))
            assertEquals("update.zip", open.uiState.value.file)

            val forShare = viewModel(id)
            assertEquals("new.zip", forShare.uiState.value.file)
            assertEquals("update.zip", open.uiState.value.file)
            // Taken once: the same id opens empty afterwards (a restored back stack).
            assertNull(viewModel(id).uiState.value.file)
        }

    @Test
    fun `a shared file that couldn't be read or is too large says so`() =
        runTest(UnconfinedTestDispatcher()) {
            assertEquals(ImportNotice.Unreadable, viewModel(inbox.offer(OpenedDocument.Unreadable)).uiState.value.notice)
            assertEquals(ImportNotice.TooLarge, viewModel(inbox.offer(OpenedDocument.TooLarge)).uiState.value.notice)
        }

    @Test
    fun `while the picker is open the screen is busy, so a second tap opens no second picker`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            val picker = CompletableDeferred<OpenedDocument>()
            documents.held = picker
            vm.onAction(ImportAction.Choose)
            assertTrue(vm.uiState.value.busy)
            vm.onAction(ImportAction.Choose)
            assertEquals(1, documents.opens)
            picker.complete(OpenedDocument.Cancelled)
            assertFalse(vm.uiState.value.busy)
        }

    @Test
    fun `a cancelled picker changes nothing, and an unreadable file says so`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.onAction(ImportAction.Choose)
            assertEquals(ImportUiState(), vm.uiState.value)
            documents.next = OpenedDocument.Unreadable
            vm.onAction(ImportAction.Choose)
            assertEquals(ImportNotice.Unreadable, vm.uiState.value.notice)
            vm.onAction(ImportAction.NoticeSeen)
            assertNull(vm.uiState.value.notice)
        }

    @Test
    fun `an import that no longer fits says so and drops the preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.choose("new.zip")
            importer.result = ImportResult.Changed
            vm.onAction(ImportAction.Import)
            assertEquals(ImportNotice.Changed, vm.uiState.value.notice)
            assertNull(vm.uiState.value.file)
        }

    @Test
    fun `a pack of only images imports them`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = viewModel()
            vm.choose("images.zip")
            assertEquals(2, vm.uiState.value.newImages)
            vm.onAction(ImportAction.Import)
            assertEquals(ImportNotice.Imported(items = 0, images = 2), vm.uiState.value.notice)
        }
}
