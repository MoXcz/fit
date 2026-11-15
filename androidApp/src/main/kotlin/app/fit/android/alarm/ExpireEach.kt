// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.alarm

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

// Runs each controller's expiry at once and on its own, so one that fails (storage) or is slow can't
// skip or hold up the other.
internal suspend fun expireEach(vararg expire: suspend () -> Unit) =
    coroutineScope {
        expire.forEach { block ->
            launch {
                try {
                    block()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Storage failed; the ticker, the next wakeup or resume tries again.
                }
            }
        }
    }
