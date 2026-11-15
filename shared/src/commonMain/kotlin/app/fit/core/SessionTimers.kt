// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TimerOwner { Workout, Guided }

/**
 * Only one session times at once: whichever starts or resumes a timer last claims it, and the other
 * gives way (a workout's rest ends and its hold pauses; a guided session pauses).
 */
class SessionTimers {
    private val claimed = MutableStateFlow<TimerOwner?>(null)

    /** Who timed last; each controller watches it and gives way when it's the other. */
    val owner: StateFlow<TimerOwner?> = claimed.asStateFlow()

    fun claim(by: TimerOwner) {
        claimed.value = by
    }
}
