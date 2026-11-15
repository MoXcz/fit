// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.android.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.fit.core.ApplicationScope
import app.fit.guided.GuidedController
import app.fit.workout.WorkoutController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.time.Duration.Companion.seconds

// The alarm only wakes us; whether a rest, hold or guided step ended is decided by each controller's
// expireIfDue() from timestamps. Both are asked: each is idempotent and cheap.
class SessionEndReceiver :
    BroadcastReceiver(),
    KoinComponent {
    private val workout: WorkoutController by inject()
    private val guided: GuidedController by inject()
    private val scope: ApplicationScope by inject()

    private companion object {
        val TIMEOUT = 8.seconds
    }

    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        scope.launch {
            // goAsync() gives about 10 s; restore and expiry must fit in it.
            try {
                withTimeout(TIMEOUT) { expireEach({ guided.expireIfDue() }, { workout.expireIfDue() }) }
            } catch (e: TimeoutCancellationException) {
                // The next tick, resume or wakeup ends it.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Storage failed; never crash the process from a wakeup.
            } finally {
                pending.finish()
            }
        }
    }
}
