// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.content.Markdown
import app.fit.content.MdBlock
import app.fit.guided.ContentImages
import app.fit.guided.GuidedController
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedRepository
import app.fit.guided.loadAll
import app.fit.lessons.DeletedLesson
import app.fit.lessons.Lesson
import app.fit.lessons.LessonId
import app.fit.lessons.LessonRepository
import app.fit.lessons.Practice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@Immutable
data class LessonUiState(
    val loading: Boolean = true,
    // Null once loaded means it doesn't exist.
    val lesson: Lesson? = null,
    val body: List<MdBlock> = emptyList(),
    // The body's images by path; null when one can't be read.
    val images: Map<String, ByteArray?> = emptyMap(),
    // The drill, when the lesson has one with steps.
    val practice: GuidedPlan? = null,
    val practised: Int = 0,
    // The player to open after Practice; the screen navigates and acknowledges.
    val opened: Boolean = false,
    // The title of the lesson just deleted, while Undo is offered.
    val deleted: String? = null,
    val undoFailed: Boolean = false,
    // The offer ran out with the lesson gone: the screen leaves and acknowledges.
    val closed: Boolean = false,
) {
    val practiceMinutes: Int? get() = practice?.let(Practice::minutes)
}

sealed interface LessonAction {
    // Runs the drill in the guided player.
    data object Practice : LessonAction

    data object OpenedSeen : LessonAction

    data object Delete : LessonAction

    data object UndoDelete : LessonAction

    data object UndoSeen : LessonAction
}

private data class LessonLocal(
    val images: Map<String, ByteArray?> = emptyMap(),
    val opened: Boolean = false,
    val deleted: DeletedLesson? = null,
    val undoFailed: Boolean = false,
    val closed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LessonViewModel(
    id: Long,
    private val lessons: LessonRepository,
    guided: GuidedRepository,
    private val controller: GuidedController,
    private val images: ContentImages,
) : ViewModel() {
    private val local = MutableStateFlow(LessonLocal())
    private val lessonId = LessonId(id)
    private val lesson = lessons.lesson(lessonId).shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    private val body: Flow<List<MdBlock>> =
        lesson.map { it?.body.orEmpty() }.distinctUntilChanged().map { Markdown.parse(it).blocks }

    private val plan =
        lesson
            .map { it?.practice }
            .distinctUntilChanged()
            .flatMapLatest { planId -> planId?.let(guided::plan) ?: flowOf(null) }

    val uiState: StateFlow<LessonUiState> =
        combine(lesson, body, plan, guided.logs(GuidedKind.Practice), local) { lesson, body, plan, logs, local ->
            LessonUiState(
                loading = false,
                lesson = lesson,
                body = body,
                images = local.images,
                practice = plan?.takeIf { it.steps.isNotEmpty() },
                practised = plan?.let { Practice.counts(logs)[it.id] } ?: 0,
                opened = local.opened,
                deleted = local.deleted?.title,
                undoFailed = local.undoFailed,
                closed = local.closed,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LessonUiState())

    init {
        viewModelScope.launch {
            body.collect { blocks ->
                val loaded = images.loadAll(blocks.filterIsInstance<MdBlock.Image>().map { it.path })
                local.update { it.copy(images = loaded) }
            }
        }
    }

    fun onAction(action: LessonAction) {
        when (action) {
            LessonAction.Practice -> {
                val plan = uiState.value.practice ?: return
                viewModelScope.launch { if (controller.startPlan(plan.id)) local.update { it.copy(opened = true) } }
            }

            LessonAction.OpenedSeen -> {
                local.update { it.copy(opened = false) }
            }

            // Its drill goes with it, so a run of it ends first (logged like any other end).
            LessonAction.Delete -> {
                val practice = uiState.value.lesson?.practice
                viewModelScope.launch {
                    practice?.let { controller.close(it) }
                    val deleted = lessons.delete(lessonId) ?: return@launch
                    local.update { it.copy(deleted = deleted, undoFailed = false) }
                }
            }

            LessonAction.UndoDelete -> {
                val deleted = local.value.deleted ?: return
                local.update { it.copy(deleted = null) }
                viewModelScope.launch { if (!lessons.restore(deleted)) local.update { it.copy(undoFailed = true) } }
            }

            LessonAction.UndoSeen -> {
                local.update { it.copy(deleted = null, undoFailed = false, closed = uiState.value.lesson == null) }
            }
        }
    }
}
