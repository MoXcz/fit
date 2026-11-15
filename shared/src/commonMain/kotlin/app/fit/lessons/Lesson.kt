// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons

import app.fit.content.Source
import app.fit.exercises.ExerciseId
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedPlanId
import app.fit.guided.GuidedRun
import kotlin.jvm.JvmInline
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.DurationUnit

@JvmInline
value class LessonId(
    val value: Long,
)

// Notes for teaching yourself the movements in [exercises], with an optional drill.
data class Lesson(
    val id: LessonId,
    val contentId: String?,
    val title: String,
    // Markdown in the content format's subset.
    val body: String,
    // The movements it teaches, in the file's order; their pages link back to it.
    val exercises: List<LessonExercise>,
    // The drill's plan; it has no steps when the lesson's file dropped its drill, so its count stays.
    val practice: GuidedPlanId?,
    val sources: List<Source> = emptyList(),
)

data class LessonExercise(
    val id: ExerciseId,
    val name: String,
)

object Practice {
    // Every logged run counts, completed or stopped: a run is logged only once a step is done, and a
    // drill stopped halfway was still practice.
    fun counts(logs: List<GuidedLog>): Map<GuidedPlanId, Int> =
        logs
            .filter { it.kind == GuidedKind.Practice && it.stepsDone > 0 }
            .mapNotNull { it.planId }
            .groupingBy { it }
            .eachCount()

    // How long the drill takes by the recovery rules: items, 3 s a rep, and every pause.
    fun estimate(plan: GuidedPlan): Duration = GuidedRun.of(plan).total

    // The estimate in whole minutes, at least 1.
    fun minutes(plan: GuidedPlan): Int =
        estimate(plan)
            .toDouble(DurationUnit.MINUTES)
            .roundToInt()
            .coerceAtLeast(1)
}
