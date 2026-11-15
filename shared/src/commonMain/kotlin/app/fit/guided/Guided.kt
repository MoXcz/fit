// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.content.Source
import app.fit.core.BodyArea
import app.fit.exercises.ExerciseId
import app.fit.exercises.Sides
import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

@JvmInline
value class GuidedPlanId(
    val value: Long,
)

// What a plan is: a recovery session, or a lesson's practice drill.
enum class GuidedKind { Recovery, Practice }

// A follow-along plan of steps, run one at a time in the player.
data class GuidedPlan(
    val id: GuidedPlanId,
    val contentId: String?,
    val kind: GuidedKind,
    val title: String,
    val areas: List<BodyArea> = emptyList(),
    // What the content says it takes; the content check keeps it near the steps' total. A drill has
    // none (0): its length is worked out from the steps.
    val minutes: Int,
    // Seconds to get into position before each step and round.
    val transition: Duration,
    val notes: String = "",
    val steps: List<GuidedStep>,
    val sources: List<Source> = emptyList(),
    // Pinned sessions show first and become launcher shortcuts; the latest pin comes first.
    val pinnedAt: Instant? = null,
    val edited: Boolean = false,
) {
    val pinned: Boolean get() = pinnedAt != null
}

// A step as the player runs it: the exercise's name, sides and notes already applied.
data class GuidedStep(
    val name: String,
    // Timed, or counted ([reps]) and waiting for Done.
    val duration: Duration? = null,
    val reps: Int? = null,
    val sides: Sides = Sides.Both,
    // The step's own cue, else the exercise's notes.
    val cue: String = "",
    val image: String? = null,
    val rounds: Int = 1,
    val exerciseId: ExerciseId? = null,
)

enum class GuidedOutcome { Completed, Stopped }

// One finished or stopped run. Separate from workouts: never in volume, records, streaks or counts.
data class GuidedLog(
    val planId: GuidedPlanId?,
    val kind: GuidedKind,
    // The title when it ran, so the log reads the same after the plan changes or goes.
    val title: String,
    // The local day it started on.
    val day: LocalDate,
    val startedAt: Instant,
    val endedAt: Instant,
    val outcome: GuidedOutcome,
    val stepsDone: Int,
    // The time it ran, paused time left out.
    val duration: Duration = (endedAt - startedAt).coerceAtLeast(Duration.ZERO),
)
