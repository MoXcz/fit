// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.seconds

// Keeps the launcher's dynamic shortcuts the same as the pinned sessions, the latest pin first. A
// list the launcher refused (rate-limited) is offered again on the next [retry].
class PinnedShortcutSync(
    private val plans: GuidedRepository,
    private val shortcuts: LauncherShortcuts,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var refused: List<PinnedShortcut>? = null

    fun start() {
        scope.launch {
            // Storage that fails starts the watch again a second later instead of ending it.
            while (true) {
                try {
                    plans.pinned
                        .map { pinned -> pinned.map { PinnedShortcut(it.id, it.title) } }
                        .distinctUntilChanged()
                        .collect { list -> mutex.withLock { publish(list) } }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(1.seconds)
                }
            }
        }
    }

    /** Offers the list the launcher refused last time, if any (the app calls it when it comes back). */
    fun retry() {
        scope.launch { mutex.withLock { refused?.let { publish(it) } } }
    }

    private fun publish(list: List<PinnedShortcut>) {
        // A launcher that throws must not take the app down; it's tried again too.
        val taken =
            try {
                shortcuts.setPinned(list)
            } catch (_: RuntimeException) {
                false
            }
        refused = if (taken) null else list
    }
}
