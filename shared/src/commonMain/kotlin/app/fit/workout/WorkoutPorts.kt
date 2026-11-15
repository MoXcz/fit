// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.workout

import kotlin.time.Instant

/** Persists the running workout so a rest or hold survives process death. */
interface WorkoutStateStore {
    suspend fun load(): WorkoutSnapshot?

    suspend fun save(snapshot: WorkoutSnapshot)
}

/**
 * Wakes the app even while it sleeps or is dead, when a rest or hold ends ([WorkoutController.expireIfDue])
 * or a guided step does (GuidedController.expireIfDue). One alarm for each: scheduling again replaces
 * it. A wakeup never decides anything itself.
 */
interface WakeupScheduler {
    fun scheduleWorkoutWakeup(at: Instant)

    fun cancelWorkoutWakeup()

    fun scheduleGuidedWakeup(at: Instant)

    fun cancelGuidedWakeup()
}

/** Tells the user a rest or hold ran out (notification, beep, vibration). Only called with the alarm on. */
interface WorkoutAlerts {
    /** [next] is the exercise of the next set, or null when every set is done. */
    fun restEnded(next: String?)

    fun holdEnded(exercise: String)

    /** Removes an end alert that is still showing. */
    fun clear()
}
