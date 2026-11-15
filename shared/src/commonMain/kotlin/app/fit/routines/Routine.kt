// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines

import app.fit.exercises.Exercise
import app.fit.exercises.Tracks
import kotlin.jvm.JvmInline
import kotlin.time.Duration

@JvmInline
value class RoutineId(
    val value: Long,
)

@JvmInline
value class RoutineItemId(
    val value: Long,
)

const val MAIN_SECTION = "Main"

// Today shows this section as one row.
const val WARMUP_SECTION = "Warm-up"

// Sections the item sheet offers besides the routine's own.
val COMMON_SECTIONS = listOf(WARMUP_SECTION, MAIN_SECTION, "Stretch")

data class Routine(
    val id: RoutineId,
    // The content file's id for built-in routines; null for ones made in the app.
    val contentId: String?,
    val name: String,
    val notes: String,
    // In display order: by section (in the order sections first appear), then position.
    val items: List<RoutineItem>,
    val edited: Boolean = false,
)

data class RoutineItem(
    val id: RoutineItemId,
    val exercise: Exercise,
    val target: ItemTarget,
) {
    // What the slot records: its own override, else the exercise's.
    val tracks: Tracks get() = target.tracks ?: exercise.fields.tracks
}

// A routine slot's plan. Starts from the exercise's defaults and is the routine's own from then on.
data class ItemTarget(
    val section: String = MAIN_SECTION,
    val sets: Int,
    val reps: Int,
    val repsMax: Int? = null,
    val rest: Duration,
    val restMax: Duration? = null,
    val duration: Duration,
    // "Record as": null records what the exercise tracks.
    val tracks: Tracks? = null,
    // Weight added when the last workout hit every target, applied with the routine.
    val progressStep: Double? = null,
    // ... and only if that workout's hardest set was at most this RPE.
    val progressRpe: Double? = null,
    val warmupSets: Int = 0,
    val notes: String = "",
)

fun Exercise.defaultTarget(section: String = MAIN_SECTION): ItemTarget {
    val d = fields.defaults
    return ItemTarget(
        section = section,
        sets = d.sets,
        reps = d.reps,
        repsMax = d.repsMax,
        rest = d.rest,
        restMax = d.restMax,
        duration = d.duration,
    )
}

// The old app's estimate: every set takes its set time plus its rest.
fun estimate(items: List<RoutineItem>): Duration =
    items.fold(Duration.ZERO) { total, item ->
        total +
            (item.target.duration + item.target.rest) * item.target.sets
    }

data class Section(
    val name: String,
    val items: List<RoutineItem>,
)

// Sections in the order they first appear; the items keep their order inside each.
fun sectionsOf(items: List<RoutineItem>): List<Section> = items.groupBy { it.target.section }.map { (name, list) -> Section(name, list) }

// Groups items given in position order by section, sections in the order they first appear.
fun <T> inSectionOrder(
    items: List<T>,
    section: (T) -> String,
): List<T> = items.groupBy(section).values.flatten()

// [base] if no routine has it (names are compared ignoring case), else "<base> 2", "<base> 3", …
fun freeRoutineName(
    base: String,
    taken: Collection<String>,
): String {
    val names = taken.mapTo(HashSet()) { it.lowercase() }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it.lowercase() !in names }
}
