// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.content.ContentImporter
import app.fit.content.ContentKind
import app.fit.content.ImportInbox
import app.fit.content.ImportItem
import app.fit.content.ImportPreview
import app.fit.content.ImportResult
import app.fit.core.Documents
import app.fit.core.OpenedDocument
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ImportNotice {
    data class Imported(
        val items: Int,
        val images: Int,
    ) : ImportNotice

    data object Unreadable : ImportNotice

    data object TooLarge : ImportNotice

    // The app's content changed after the preview; it's shown again.
    data object Changed : ImportNotice
}

@Immutable
data class ImportUiState(
    val busy: Boolean = false,
    // The file being previewed; null before one is chosen.
    val file: String? = null,
    // What it holds, by kind, when it can be imported.
    val groups: List<Pair<ContentKind, List<ImportItem>>> = emptyList(),
    val newImages: Int = 0,
    val replacedImages: Int = 0,
    // Why it can't be imported, each `file:line: what's wrong`.
    val problems: List<String> = emptyList(),
    val canImport: Boolean = false,
    // How many items and images it replaces; the confirmation says so.
    val replaces: Int = 0,
    val confirming: Boolean = false,
    val notice: ImportNotice? = null,
)

sealed interface ImportAction {
    data object Choose : ImportAction

    // Asks first when it replaces something.
    data object Import : ImportAction

    data object ConfirmReplace : ImportAction

    data object CancelReplace : ImportAction

    data object NoticeSeen : ImportAction
}

private data class ImportLocal(
    val busy: Boolean = false,
    val preview: ImportPreview? = null,
    val confirming: Boolean = false,
    val notice: ImportNotice? = null,
)

// Import content: choose a file (or get one shared), see what it holds, then import it whole.
class ImportViewModel(
    // The inbox id of the file this screen was opened for; empty when it was opened from More.
    share: String,
    private val importer: ContentImporter,
    private val documents: Documents,
    private val inbox: ImportInbox,
    // The app's scope: an import under way isn't cut short by leaving the screen.
    private val scope: CoroutineScope,
) : ViewModel() {
    private val local = MutableStateFlow(ImportLocal())
    private val state = MutableStateFlow(ImportUiState())

    val uiState: StateFlow<ImportUiState> = state.asStateFlow()

    init {
        viewModelScope.launch { local.collect { state.value = it.toUiState() } }
        inbox.take(share)?.let { document -> viewModelScope.launch { opened(document) } }
    }

    private suspend fun opened(document: OpenedDocument) {
        when (document) {
            is OpenedDocument.File -> preview(document.name, document.bytes)
            OpenedDocument.Unreadable -> local.update { it.copy(notice = ImportNotice.Unreadable) }
            OpenedDocument.TooLarge -> local.update { it.copy(notice = ImportNotice.TooLarge) }
            OpenedDocument.Cancelled -> Unit
        }
    }

    fun onAction(action: ImportAction) {
        when (action) {
            ImportAction.Choose -> {
                if (local.value.busy) return
                // Busy while the picker is open too, so a second tap can't open another.
                local.update { it.copy(busy = true) }
                viewModelScope.launch {
                    try {
                        opened(documents.open())
                    } finally {
                        local.update { it.copy(busy = false) }
                    }
                }
            }

            ImportAction.Import -> {
                val ready = local.value.preview as? ImportPreview.Ready ?: return
                if (local.value.busy) return
                if (ready.replaces) local.update { it.copy(confirming = true) } else import(ready)
            }

            ImportAction.ConfirmReplace -> {
                val ready = local.value.preview as? ImportPreview.Ready ?: return
                local.update { it.copy(confirming = false) }
                import(ready)
            }

            ImportAction.CancelReplace -> {
                local.update { it.copy(confirming = false) }
            }

            ImportAction.NoticeSeen -> {
                local.update { it.copy(notice = null) }
            }
        }
    }

    private suspend fun preview(
        name: String,
        bytes: ByteArray,
    ) {
        local.update { it.copy(busy = true, confirming = false) }
        val preview = importer.preview(name, bytes)
        local.update { it.copy(busy = false, preview = preview) }
    }

    private fun import(ready: ImportPreview.Ready) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        scope.launch {
            when (val result = importer.import(ready)) {
                is ImportResult.Imported -> {
                    val images = ready.newImages.size + ready.replacedImages.size
                    local.update { ImportLocal(notice = ImportNotice.Imported(result.items, images)) }
                }

                ImportResult.Changed -> {
                    local.update { it.copy(busy = false, preview = null, notice = ImportNotice.Changed) }
                }
            }
        }
    }
}

private fun ImportLocal.toUiState(): ImportUiState {
    val ready = preview as? ImportPreview.Ready
    return ImportUiState(
        busy = busy,
        file = preview?.file,
        groups =
            ready
                ?.items
                .orEmpty()
                .groupBy { it.kind }
                .toList(),
        newImages = ready?.newImages?.size ?: 0,
        replacedImages = ready?.replacedImages?.size ?: 0,
        problems = (preview as? ImportPreview.Invalid)?.problems.orEmpty(),
        canImport = ready != null && !busy,
        replaces = ready?.let { r -> r.items.count { it.replaces != null } + r.replacedImages.size } ?: 0,
        confirming = confirming,
        notice = notice,
    )
}
