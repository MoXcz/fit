// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.core.BodyArea
import app.fit.exercises.ExerciseFields
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import kotlinx.datetime.DayOfWeek
import kotlin.math.roundToLong
import kotlin.time.Duration

// [path] is relative to the content root for built-in files ("exercises/plank.md"), else the file name.
data class ContentFile(
    val path: String,
    val text: String,
)

sealed interface ContentResult {
    data class Parsed(
        val item: ContentItem,
    ) : ContentResult

    data class Invalid(
        val problems: List<ContentProblem>,
    ) : ContentResult
}

sealed interface ContentItem {
    val id: String
}

data class ExerciseDraft(
    override val id: String,
    val fields: ExerciseFields,
) : ContentItem

data class RoutineDraft(
    override val id: String,
    val name: String,
    val notes: String,
    val items: List<RoutineItemDraft>,
    val sources: List<Source>,
) : ContentItem

// A routine slot as written; values left out come from the exercise's defaults when it's loaded.
data class RoutineItemDraft(
    // An exercise content id.
    val exercise: String,
    // Where the item starts in its file, for problems found later (an unknown exercise).
    val line: Int,
    val section: String? = null,
    val sets: Int? = null,
    val reps: Pair<Int, Int?>? = null,
    val rest: Pair<Duration, Duration?>? = null,
    val duration: Duration? = null,
    val tracks: Tracks? = null,
    val progressStep: Double? = null,
    val progressRpe: Double? = null,
    val warmupSets: Int? = null,
    val notes: String? = null,
)

data class ProgramDraft(
    override val id: String,
    val name: String,
    val notes: String,
    // Weekday to routine content id; weekdays left out are rest days.
    val days: Map<DayOfWeek, String>,
    val deloadEvery: Int? = null,
    // Null: the app's default.
    val deloadPercent: Int? = null,
    // Where each day is written, for problems found later (an unknown routine).
    val dayLines: Map<DayOfWeek, Int> = emptyMap(),
) : ContentItem

// A recovery session as written; exercise steps are resolved when it's loaded.
data class RecoveryDraft(
    override val id: String,
    val title: String,
    val notes: String,
    val areas: List<BodyArea>,
    val minutes: Int,
    val transition: Duration,
    val steps: List<GuidedStepDraft>,
    val sources: List<Source>,
    // Where `minutes` is written, for the check against the steps.
    val minutesLine: Int = 1,
    // Images in the notes, for the check that they exist.
    val noteImages: List<MdBlock.Image> = emptyList(),
) : ContentItem

// A lesson as written: notes for teaching yourself the movements in [exercises], with an optional drill.
data class LessonDraft(
    override val id: String,
    val title: String,
    // Markdown in the documented subset.
    val body: String,
    // Exercise content ids of the movements it teaches, each with its line.
    val exercises: List<Pair<String, Int>>,
    val practice: PracticeDraft?,
    val sources: List<Source>,
    val bodyImages: List<MdBlock.Image> = emptyList(),
) : ContentItem

// A lesson's drill: steps as in a recovery session, with no minutes (the app works the length out).
data class PracticeDraft(
    val transition: Duration,
    val steps: List<GuidedStepDraft>,
)

// A step of a recovery session or a lesson's drill: an exercise or an inline name, timed or counted.
data class GuidedStepDraft(
    // An exercise content id, or null for an inline step named [name].
    val exercise: String?,
    val name: String?,
    val line: Int,
    val duration: Duration? = null,
    val reps: Int? = null,
    // Null: the exercise's, or both for an inline step.
    val sides: Sides? = null,
    val cue: String? = null,
    val image: String? = null,
    val rounds: Int = 1,
)

data class Source(
    val title: String,
    val url: String?,
)

// One problem in a content file; [field] is a path such as "defaults.reps" or "refs[1].url".
sealed interface ContentProblem {
    val file: String
    val line: Int

    data class NoHeader(
        override val file: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    data class BadYaml(
        override val file: String,
        override val line: Int,
        val detail: String,
    ) : ContentProblem

    data class MissingKey(
        override val file: String,
        override val line: Int,
        val field: String,
    ) : ContentProblem

    data class UnknownKey(
        override val file: String,
        override val line: Int,
        val field: String,
    ) : ContentProblem

    data class DuplicateKey(
        override val file: String,
        override val line: Int,
        val field: String,
    ) : ContentProblem

    data class BadValue(
        override val file: String,
        override val line: Int,
        val field: String,
        val expected: String,
    ) : ContentProblem

    data class UnsupportedFormat(
        override val file: String,
        override val line: Int,
        val format: String,
    ) : ContentProblem

    data class UnsupportedKind(
        override val file: String,
        override val line: Int,
        val kind: String,
    ) : ContentProblem

    // The checks below look at a whole content folder, so they point at the header.
    data class WrongPlace(
        override val file: String,
        val expected: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    data class DuplicateId(
        override val file: String,
        val id: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    data class DuplicateName(
        override val file: String,
        val name: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    // An exercise's images are reported at the header; a step's at the step.
    data class MissingImage(
        override val file: String,
        val path: String,
        override val line: Int = 1,
    ) : ContentProblem

    data class UnknownExercise(
        override val file: String,
        override val line: Int,
        val id: String,
    ) : ContentProblem

    data class UnknownRoutine(
        override val file: String,
        override val line: Int,
        val id: String,
    ) : ContentProblem

    // [estimate] is the steps' total in minutes: items, rep time and pauses.
    data class MinutesOff(
        override val file: String,
        override val line: Int,
        val minutes: Int,
        val estimate: Double,
    ) : ContentProblem

    // Something in the Markdown body outside the documented subset.
    data class BadMarkdown(
        override val file: String,
        override val line: Int,
        val detail: String,
    ) : ContentProblem

    // A step's duration or reps that its exercise doesn't track.
    data class TracksMismatch(
        override val file: String,
        override val line: Int,
        val exercise: String,
        // "duration" or "reps".
        val field: String,
        // What the exercise does track of the two; null when it tracks neither.
        val instead: String?,
    ) : ContentProblem

    // An import whose name another item in the app already has; [kind] is "exercise", "routine" or "program".
    data class NameTaken(
        override val file: String,
        val kind: String,
        val name: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    // An imported exercise that stops tracking what a step already in the app gives it; [owner] is
    // e.g. "the recovery session Wrist reset", [field] "duration" or "reps".
    data class BreaksStep(
        override val file: String,
        val exercise: String,
        val owner: String,
        val field: String,
    ) : ContentProblem {
        override val line: Int get() = 1
    }

    // A lesson with nothing after its header, reported at the header's closing line.
    data class NoBody(
        override val file: String,
        override val line: Int,
    ) : ContentProblem
}

fun ContentProblem.describe(): String {
    val what =
        when (this) {
            is ContentProblem.NoHeader -> {
                "the file must start with a YAML header between --- lines"
            }

            is ContentProblem.BadYaml -> {
                "YAML: $detail"
            }

            is ContentProblem.MissingKey -> {
                "$field is required"
            }

            is ContentProblem.UnknownKey -> {
                "unknown key $field"
            }

            is ContentProblem.DuplicateKey -> {
                "$field is given twice"
            }

            is ContentProblem.BadValue -> {
                "$field must be $expected"
            }

            is ContentProblem.UnsupportedFormat -> {
                "format $format isn't supported (this app reads format 1)"
            }

            is ContentProblem.UnsupportedKind -> {
                "kind $kind isn't supported"
            }

            is ContentProblem.WrongPlace -> {
                "the file must be $expected"
            }

            is ContentProblem.DuplicateId -> {
                "another file has the id $id"
            }

            is ContentProblem.DuplicateName -> {
                "another file has the name $name"
            }

            is ContentProblem.MissingImage -> {
                "$path doesn't exist"
            }

            is ContentProblem.UnknownExercise -> {
                "there is no exercise with the id $id"
            }

            is ContentProblem.UnknownRoutine -> {
                "there is no routine with the id $id"
            }

            is ContentProblem.MinutesOff -> {
                "minutes must be within 2 of the steps' total (about ${oneDecimal(estimate)})"
            }

            is ContentProblem.BadMarkdown -> {
                "Markdown: $detail"
            }

            is ContentProblem.TracksMismatch -> {
                tracksMismatch()
            }

            is ContentProblem.NameTaken -> {
                "the app already has another $kind named $name"
            }

            is ContentProblem.BreaksStep -> {
                val tracked = if (field == "duration") "time" else "reps"
                "$exercise would stop tracking $tracked, which a step of $owner uses (import that one with the change too)"
            }

            is ContentProblem.NoBody -> {
                "a lesson needs a body: its notes go after the header"
            }
        }
    return "$file:$line: $what"
}

private fun ContentProblem.TracksMismatch.tracksMismatch(): String {
    val has = if (field == "duration") "a duration" else "reps"
    return when (instead) {
        null -> "the step has $has, but $exercise tracks neither time nor reps (name the step instead of the exercise)"
        "reps" -> "the step has $has, but $exercise doesn't track time (give reps)"
        else -> "the step has $has, but $exercise doesn't track reps (give a duration)"
    }
}

private fun oneDecimal(value: Double): String {
    val tenths = (value * 10).roundToLong()
    return if (tenths % 10 == 0L) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}
