// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.Equipment
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.Tracks
import app.fit.routines.MAIN_SECTION
import kotlinx.datetime.LocalDate
import kotlin.jvm.JvmInline
import kotlin.time.Duration
import kotlin.time.Instant

@JvmInline
value class DayId(
    val value: Long,
)

@JvmInline
value class BlockId(
    val value: Long,
)

@JvmInline
value class SetId(
    val value: Long,
)

// One workout per local day. Its blocks are copies: changing a routine or an exercise's defaults
// never changes a day.
data class WorkoutDay(
    val id: DayId,
    val date: LocalDate,
    val name: String = "",
    val notes: String = "",
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    // How spent the workout left you, 1 (fresh) to 5; entered in the summary.
    val fatigue: Int? = null,
    // A routine was applied with its loads cut for a deload week, so progression doesn't build on it.
    val deload: Boolean = false,
    // In display order: by section (in the order sections first appear), then position.
    val blocks: List<Block> = emptyList(),
) {
    val started: Boolean get() = startedAt != null
    val finished: Boolean get() = finishedAt != null

    val totalSets: Int get() = blocks.sumOf { it.workingSets.size }
    val doneSets: Int get() = blocks.sumOf { block -> block.workingSets.count { it.done } }

    val lastDoneAt: Instant? get() = blocks.flatMap { it.sets }.mapNotNull { it.doneAt }.maxOrNull()

    // The session clock: frozen once finished.
    fun elapsed(now: Instant): Duration {
        val start = startedAt ?: return Duration.ZERO
        return ((finishedAt ?: now) - start).coerceAtLeast(Duration.ZERO)
    }

    // A finished workout's length; unknown until it has both ends.
    val duration: Duration?
        get() {
            val start = startedAt ?: return null
            val end = finishedAt ?: return null
            return (end - start).coerceAtLeast(Duration.ZERO)
        }

    // The routine estimate over the day's sets, so a day applied from a routine reads the same.
    val estimate: Duration get() = blocks.fold(Duration.ZERO) { total, b -> total + (b.duration + b.rest) * b.workingSets.size }

    // Load × reps of the done working sets, per unit: a day can mix kg and lb. A bodyweight set's
    // load is only what was added, so a gain in body weight never shows up as more work.
    val volume: Map<WeightUnit, Double>
        get() =
            blocks
                .flatMap { it.workingSets }
                .filter { it.done && (it.weight ?: 0.0) > 0 && (it.reps ?: 0) > 0 }
                .groupBy { it.weightUnit }
                .mapValues { (_, sets) -> sets.sumOf { it.weight!! * it.reps!! } }

    // Every working set done; a skipped warm-up doesn't keep a workout open.
    val allDone: Boolean get() = blocks.isNotEmpty() && blocks.none { it.hasSetsLeft }

    // What's being worked on: the exercise last open while it has sets left, else the first with sets left.
    fun current(position: BlockId?): Block? =
        blocks.firstOrNull { it.id == position && it.hasSetsLeft } ?: blocks.firstOrNull { it.hasSetsLeft }

    // Where Start and Resume go: the first exercise with a set left, else the first.
    val firstUnfinished: Block? get() = blocks.firstOrNull { it.hasSetsLeft } ?: blocks.firstOrNull()
}

data class Block(
    val id: BlockId,
    val exercise: Exercise,
    val section: String = MAIN_SECTION,
    val rest: Duration,
    val restMax: Duration? = null,
    // A set's time: the hold for timed sets, the estimate's set time for the rest.
    val duration: Duration,
    // "Record as": null records what the exercise tracks.
    val tracksOverride: Tracks? = null,
    val warmupSets: Int = 0,
    val notes: String = "",
    // Warm-up sets first, then working sets, each in position order.
    val sets: List<SetEntry> = emptyList(),
) {
    val tracks: Tracks get() = tracksOverride ?: exercise.fields.tracks

    val workingSets: List<SetEntry> get() = sets.filter { it.working }

    // Warm-ups never block anything: an exercise is done when its working sets are.
    val hasSetsLeft: Boolean get() = workingSets.any { !it.done }

    // The set to do next: the first not done, but a warm-up only until a working set is done, so a
    // skipped warm-up is never what comes next (it stays in All sets).
    val nextSet: SetEntry?
        get() {
            val started = workingSets.any { it.done }
            return sets.firstOrNull { !it.done && (it.working || !started) }
        }

    // The unit most of its loaded sets use (the exercise's on a tie), so a set in another unit stands out.
    val mainUnit: WeightUnit
        get() {
            val counts = sets.filter { it.weight != null }.groupingBy { it.weightUnit }.eachCount()
            val top = counts.values.maxOrNull() ?: return exercise.fields.weightUnit
            val unit = exercise.fields.weightUnit
            return if (counts[unit] == top) unit else counts.entries.first { it.value == top }.key
        }

    fun inOtherUnit(set: SetEntry): Boolean = set.weight != null && set.weightUnit != mainUnit

    // Plates per side only make sense on a bar.
    val showsPlates: Boolean get() = tracks.weight && exercise.fields.equipment == Equipment.Barbell
}

data class SetEntry(
    val id: SetId,
    val warmup: Boolean = false,
    // The plan: copied in when the set is made; logging never changes it.
    val targetReps: Int,
    val targetRepsMax: Int? = null,
    val targetDuration: Duration? = null,
    // The load: planned when the set is made, what was lifted once it's done. Null when the slot
    // doesn't track weight.
    val weight: Double? = null,
    val weightUnit: WeightUnit = WeightUnit.Kg,
    // What was done.
    val reps: Int? = null,
    val duration: Duration? = null,
    val distance: Double? = null,
    // The exercise's distance unit, never a fixed one.
    val distanceUnit: DistanceUnit = DistanceUnit.Km,
    val rpe: Double? = null,
    val doneAt: Instant? = null,
    // A bodyweight set: [weight] is what was added to the body weight, if anything.
    val bodyweight: Boolean = false,
) {
    val done: Boolean get() = doneAt != null

    // Every count, estimate, mark and rule leaves warm-up sets out the same way.
    val working: Boolean get() = !warmup
}

// Ticking fills what the slot records from the targets when nothing was entered; targets stay.
fun SetEntry.ticked(
    at: Instant,
    tracks: Tracks,
): SetEntry =
    copy(
        doneAt = at,
        reps = reps ?: targetReps.takeIf { tracks.reps },
        duration = duration ?: targetDuration.takeIf { tracks.time },
    )

object Fatigue {
    val range: IntRange = 1..5

    // Null clears it; anything outside the range isn't a fatigue.
    fun isValid(value: Int?): Boolean = value == null || value in range
}

// The week strip's mark: warm-ups don't count, and a finished day with nothing done gets none.
enum class DayMark { None, Trained, Finished }

fun WorkoutDay?.mark(): DayMark =
    when {
        this == null || doneSets == 0 -> DayMark.None
        finished -> DayMark.Finished
        else -> DayMark.Trained
    }

// What a new block and its sets will hold before they're written.
data class PlannedBlock(
    val exerciseId: ExerciseId,
    val section: String,
    val rest: Duration,
    val restMax: Duration?,
    val duration: Duration,
    val tracksOverride: Tracks? = null,
    val warmupSets: Int = 0,
    val notes: String = "",
    val sets: List<PlannedSet>,
)

data class PlannedSet(
    val warmup: Boolean = false,
    val targetReps: Int,
    val targetRepsMax: Int? = null,
    val targetDuration: Duration? = null,
    val weight: Double? = null,
    val weightUnit: WeightUnit,
    val distanceUnit: DistanceUnit,
    val bodyweight: Boolean = false,
)
