// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.day.BlockId
import app.fit.day.DayRepository
import app.fit.guided.ContentImages
import app.fit.guided.GuidedController
import app.fit.guided.GuidedEngine
import app.fit.guided.GuidedItem
import app.fit.guided.GuidedPhase
import app.fit.guided.GuidedSnapshot
import app.fit.guided.GuidedSource
import app.fit.guided.GuidedState
import app.fit.guided.Interstitial
import app.fit.guided.StepClock
import app.fit.routines.WARMUP_SECTION
import app.fit.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// The one big button in the middle of the group.
enum class PlayerPrimary { Pause, Resume, Done, Next, Close }

@Immutable
data class PlayerUiState(
    val loading: Boolean = true,
    val guided: GuidedState = GuidedState(),
    val now: Instant = Instant.fromEpochMilliseconds(0),
    val keepScreenOn: Boolean = false,
    // The current item's image, read from content.
    val image: ByteArray? = null,
    // A finished warm-up goes on to this exercise's dial; the screen navigates and acknowledges.
    val continueTo: BlockId? = null,
    // The session is gone (closed, or a warm-up with nothing after it): the screen goes back.
    val leave: Boolean = false,
) {
    val snapshot: GuidedSnapshot? get() = guided.snapshot

    val item: GuidedItem? get() = guided.item

    val index: Int get() = snapshot?.index ?: 0

    val count: Int get() = guided.run?.items?.size ?: 0

    val phase: GuidedPhase? get() = snapshot?.phase

    val warmup: Boolean get() = snapshot?.source is GuidedSource.Warmup

    val paused: Boolean get() = snapshot?.clock is StepClock.Paused

    val left: Duration get() = snapshot?.let { GuidedEngine.left(it, now) } ?: Duration.ZERO

    // What the pause before the current item is: getting into position, or switching sides.
    val interstitial: Interstitial? get() = guided.run?.interstitial(index)?.takeIf { phase == GuidedPhase.Transition }

    // How long the current countdown is, for the ring.
    val length: Duration
        get() =
            when (phase) {
                GuidedPhase.Transition -> guided.run?.pauseBefore(index) ?: Duration.ZERO
                GuidedPhase.Step -> item?.length ?: Duration.ZERO
                else -> Duration.ZERO
            }

    val itemDone: Boolean
        get() {
            val snapshot = snapshot ?: return false
            val run = guided.run ?: return false
            return GuidedEngine.isDone(snapshot, run, snapshot.index)
        }

    // Waiting on a timed item: it ran to 0 (auto-advance off), or a warm-up item already done.
    val waitingAtEnd: Boolean
        get() =
            phase == GuidedPhase.Step &&
                snapshot?.clock == StepClock.Waiting &&
                (item?.timed == true || (guided.run?.keepsDone == true && itemDone))

    val primary: PlayerPrimary
        get() =
            when {
                snapshot == null || phase == GuidedPhase.Finished -> PlayerPrimary.Close
                paused -> PlayerPrimary.Resume
                snapshot?.clock is StepClock.Running -> PlayerPrimary.Pause
                waitingAtEnd -> PlayerPrimary.Next
                else -> PlayerPrimary.Done
            }

    val canPrevious: Boolean get() = phase == GuidedPhase.Step || (phase == GuidedPhase.Transition && index > 0)

    val canNext: Boolean get() = phase == GuidedPhase.Transition || phase == GuidedPhase.Step

    // The session clock: from Start, frozen at the end.
    val elapsed: Duration? get() = snapshot?.let { ((it.endedAt ?: now) - it.startedAt).coerceAtLeast(Duration.ZERO) }

    override fun equals(other: Any?): Boolean =
        other is PlayerUiState &&
            loading == other.loading &&
            guided == other.guided &&
            now == other.now &&
            keepScreenOn == other.keepScreenOn &&
            (image?.contentEquals(other.image) ?: (other.image == null)) &&
            continueTo == other.continueTo &&
            leave == other.leave

    override fun hashCode(): Int = guided.hashCode() * 31 + now.hashCode()
}

sealed interface PlayerAction {
    data object Pause : PlayerAction

    data object Resume : PlayerAction

    data object Done : PlayerAction

    data object Next : PlayerAction

    data object Previous : PlayerAction

    // Ends a session (logged as stopped), or skips the rest of a warm-up.
    data object End : PlayerAction

    // The finished screen's Done.
    data object Close : PlayerAction

    data object ContinueSeen : PlayerAction

    // The notice that a start ended another session went.
    data object ReplacedSeen : PlayerAction
}

private data class PlayerLocal(
    val image: Pair<String, ByteArray?>? = null,
    val continueTo: BlockId? = null,
    val leave: Boolean = false,
)

// The guided player: the running session, one item at a time. The session belongs to the
// GuidedController, so leaving the screen never stops it.
class PlayerViewModel(
    private val controller: GuidedController,
    private val days: DayRepository,
    settings: SettingsRepository,
    private val images: ContentImages,
    private val clock: Clock,
    // Outlives the screen, so leaving a finished session dismisses it.
    private val appScope: CoroutineScope,
) : ViewModel() {
    private val local = MutableStateFlow(PlayerLocal())

    // The session clock counts up even when nothing counts down.
    private val ticker: Flow<Unit> =
        flow {
            while (true) {
                emit(Unit)
                delay(1.seconds)
            }
        }

    val uiState: StateFlow<PlayerUiState> =
        combine(controller.state, settings.settings, local, ticker) { guided, settings, local, _ ->
            PlayerUiState(
                loading = false,
                guided = guided,
                now = clock.now(),
                keepScreenOn = settings.keepScreenOn,
                image = local.image?.takeIf { it.first == guided.item?.image }?.second,
                continueTo = local.continueTo,
                leave = local.leave,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    init {
        viewModelScope.launch {
            controller.state
                .map { it.item?.image }
                .distinctUntilChanged()
                .collect { path ->
                    if (path == null) return@collect
                    // An unreadable image just isn't shown.
                    val bytes =
                        try {
                            images.load(path)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            null
                        }
                    local.update { it.copy(image = path to bytes) }
                }
        }
        // A warm-up that ends, by its last item or Skip warm-up, goes on to the workout.
        viewModelScope.launch {
            controller.state
                .filter { it.snapshot?.finished == true && it.snapshot.source is GuidedSource.Warmup }
                .collect { state ->
                    val date = (state.snapshot?.source as GuidedSource.Warmup).date
                    val blocks =
                        days
                            .day(date)
                            .first()
                            ?.blocks
                            .orEmpty()
                            .filter { it.section != WARMUP_SECTION }
                    val next = blocks.firstOrNull { it.hasSetsLeft } ?: blocks.firstOrNull()
                    controller.dismiss()
                    local.update { it.copy(continueTo = next?.id, leave = next == null) }
                }
        }
    }

    // Leaving the end screen any way (Back too) counts as seeing it.
    override fun onCleared() {
        if (controller.state.value.snapshot
                ?.finished == true
        ) {
            appScope.launch { controller.dismiss() }
        }
    }

    fun onAction(action: PlayerAction) {
        viewModelScope.launch {
            when (action) {
                PlayerAction.Pause -> {
                    controller.pause()
                }

                PlayerAction.Resume -> {
                    controller.resume()
                }

                PlayerAction.Done -> {
                    controller.done()
                }

                PlayerAction.Next -> {
                    controller.next()
                }

                PlayerAction.Previous -> {
                    controller.previous()
                }

                PlayerAction.End -> {
                    controller.end()
                }

                PlayerAction.Close -> {
                    controller.dismiss()
                    local.update { it.copy(leave = true) }
                }

                PlayerAction.ContinueSeen -> {
                    local.update { it.copy(continueTo = null, leave = false) }
                }

                PlayerAction.ReplacedSeen -> {
                    controller.replacedSeen()
                }
            }
        }
    }
}
