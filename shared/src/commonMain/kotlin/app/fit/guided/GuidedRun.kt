// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.day.SetId
import app.fit.day.WorkoutDay
import app.fit.exercises.Sides
import app.fit.routines.WARMUP_SECTION
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// One thing the player runs: a step, one round of it, on one side.
data class GuidedItem(
    val name: String,
    // Timed; null when it's counted.
    val length: Duration? = null,
    val reps: Int? = null,
    val repsMax: Int? = null,
    val cue: String = "",
    val image: String? = null,
    // 1 or 2 on a step done on each side; null otherwise.
    val side: Int? = null,
    val round: Int = 1,
    val rounds: Int = 1,
    // A warm-up item ticks this set when it's done (on its last side).
    val setId: SetId? = null,
    // Already done when the run was built (a warm-up set ticked earlier).
    val done: Boolean = false,
) {
    val timed: Boolean get() = length != null

    // What the minutes check and the estimate count it as.
    val time: Duration get() = length ?: (GuidedRun.REP_TIME * (reps ?: 0))
}

// What comes before an item: getting into position, or switching sides.
enum class Interstitial { Transition, SwitchSides }

// The items of one run in order, with what comes before each.
data class GuidedRun(
    val title: String,
    val items: List<GuidedItem>,
    // Before each step and round, the first included.
    val transition: Duration,
    // A warm-up never runs a done set again, so a revisit can't overwrite what it logged.
    val keepsDone: Boolean = false,
) {
    fun interstitial(index: Int): Interstitial =
        if (items.getOrNull(index)?.side ==
            2
        ) {
            Interstitial.SwitchSides
        } else {
            Interstitial.Transition
        }

    // How long the pause before [index] is; 0 means none.
    fun pauseBefore(index: Int): Duration =
        when (interstitial(index)) {
            Interstitial.SwitchSides -> SWITCH_SIDES
            Interstitial.Transition -> transition
        }

    // Every item and every pause before one: what the minutes check compares with.
    val total: Duration get() = items.indices.fold(Duration.ZERO) { sum, i -> sum + items[i].time + pauseBefore(i) }

    companion object {
        // A short pause to switch sides, whatever the plan's transition (a warm-up has none).
        val SWITCH_SIDES: Duration = 5.seconds

        // A counted step's time, for the estimate and the minutes check.
        val REP_TIME: Duration = 3.seconds

        // Each step, then each of its rounds, then each side: L, R, L, R.
        fun of(
            title: String,
            steps: List<GuidedStep>,
            transition: Duration,
        ): GuidedRun =
            GuidedRun(
                title = title,
                items =
                    steps.flatMap { step ->
                        (1..step.rounds).flatMap { round ->
                            sidesOf(step.sides).map { side ->
                                GuidedItem(
                                    name = step.name,
                                    length = step.duration,
                                    reps = step.reps.takeIf { step.duration == null },
                                    cue = step.cue,
                                    image = step.image,
                                    side = side,
                                    round = round,
                                    rounds = step.rounds,
                                )
                            }
                        }
                    },
                transition = transition,
            )

        fun of(plan: GuidedPlan): GuidedRun = of(plan.title, plan.steps, plan.transition)

        // The day's Warm-up section, one item per set (per side), with no pause between items. A
        // timed slot counts down; any other, or a timed one with no length, waits for Done with its
        // target reps.
        fun warmup(
            day: WorkoutDay,
            title: String,
        ): GuidedRun {
            val items =
                day.blocks.filter { it.section == WARMUP_SECTION }.flatMap { block ->
                    val fields = block.exercise.fields
                    block.sets.flatMap { set ->
                        val length = (set.targetDuration ?: block.duration).takeIf { block.tracks.time && it > Duration.ZERO }
                        val sides = sidesOf(fields.sides)
                        sides.map { side ->
                            GuidedItem(
                                name = fields.name,
                                length = length,
                                reps = if (length != null) null else set.targetReps,
                                repsMax = if (length != null) null else set.targetRepsMax,
                                cue = block.notes.ifBlank { fields.notes },
                                image = fields.images.firstOrNull(),
                                side = side,
                                setId = set.id.takeIf { side == sides.last() },
                                done = set.done,
                            )
                        }
                    }
                }
            return GuidedRun(title = title, items = items, transition = Duration.ZERO, keepsDone = true)
        }

        private fun sidesOf(sides: Sides): List<Int?> = if (sides == Sides.Each) listOf(1, 2) else listOf(null)
    }
}
