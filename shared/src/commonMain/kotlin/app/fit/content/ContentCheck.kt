// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.exercises.ExerciseFields
import app.fit.exercises.Sides
import app.fit.guided.GuidedRun
import app.fit.guided.GuidedStep
import kotlin.math.abs
import kotlin.time.DurationUnit

// How far a recovery session's `minutes` may be from its steps' total.
const val MINUTES_SLACK = 2.0

// The steps' total in minutes, as the player runs them: each item, a counted one at 3 s a rep, and
// every pause before one.
fun recoveryEstimate(
    draft: RecoveryDraft,
    sidesOf: (exercise: String) -> Sides,
): Double {
    val steps =
        draft.steps.map { step ->
            GuidedStep(
                name = step.name ?: step.exercise.orEmpty(),
                duration = step.duration,
                reps = step.reps,
                sides = step.sides ?: step.exercise?.let(sidesOf) ?: Sides.Both,
                rounds = step.rounds,
            )
        }
    return GuidedRun.of(draft.title, steps, draft.transition).total.toDouble(DurationUnit.MINUTES)
}

data class ContentCheck(
    val items: List<ContentItem>,
    val problems: List<ContentProblem>,
    // Each item's file, in the order of [items].
    val paths: List<String> = emptyList(),
)

private fun folderOf(item: ContentItem): String =
    when (item) {
        is ExerciseDraft -> "exercises"
        is RoutineDraft -> "routines"
        is ProgramDraft -> "programs"
        is RecoveryDraft -> "recovery"
        is LessonDraft -> "lessons"
    }

private fun nameOf(item: ContentItem): String =
    when (item) {
        is ExerciseDraft -> item.fields.name
        is RoutineDraft -> item.name
        is ProgramDraft -> item.name
        is RecoveryDraft -> item.title
        is LessonDraft -> item.title
    }

/**
 * Checks a content folder as a whole: every file parses, lives at `<kind folder>/<id>.md`, ids and
 * names are unique within a kind, every image exists, every exercise or routine a file names is in
 * the folder or in [knownExercises] / [knownRoutines], a guided step's duration or reps is something
 * its exercise tracks, and a recovery session's `minutes` is within [MINUTES_SLACK] of its steps'
 * total. An exercise known only by id counts as done on both sides and fits any step; one in
 * [library] (the app's exercises by content id, for an import) is checked like one in the folder.
 * A single shared file has no folder, so [checkPlace] is false for it.
 */
fun checkContent(
    files: List<ContentFile>,
    knownExercises: Set<String> = emptySet(),
    knownRoutines: Set<String> = emptySet(),
    library: Map<String, ExerciseFields> = emptyMap(),
    checkPlace: Boolean = true,
    imageExists: (String) -> Boolean,
): ContentCheck {
    val parsed = mutableListOf<Pair<ContentFile, ContentItem>>()
    val problems = mutableListOf<ContentProblem>()
    val ids = HashSet<Pair<String, String>>()
    val names = HashSet<Pair<String, String>>()
    for (file in files.sortedBy { it.path }) {
        when (val result = ContentParser.parse(file)) {
            is ContentResult.Invalid -> {
                problems += result.problems
            }

            is ContentResult.Parsed -> {
                val item = result.item
                parsed += file to item
                val folder = folderOf(item)
                val expected = "$folder/${item.id}.md"
                if (checkPlace && file.path != expected) problems += ContentProblem.WrongPlace(file.path, expected)
                if (!ids.add(folder to item.id)) problems += ContentProblem.DuplicateId(file.path, item.id)
                // Names are unique within a kind regardless of case, as in the database.
                if (!names.add(folder to nameOf(item).lowercase())) problems += ContentProblem.DuplicateName(file.path, nameOf(item))
                val images =
                    when (item) {
                        is ExerciseDraft -> {
                            item.fields.images.map { it to 1 }
                        }

                        is RecoveryDraft -> {
                            item.steps.stepImages() + item.noteImages.map { it.path to it.line }
                        }

                        is LessonDraft -> {
                            item.practice
                                ?.steps
                                .orEmpty()
                                .stepImages() + item.bodyImages.map { it.path to it.line }
                        }

                        else -> {
                            emptyList()
                        }
                    }
                images.distinct().filterNot { imageExists(it.first) }.forEach { (path, line) ->
                    problems += ContentProblem.MissingImage(file.path, path, line)
                }
            }
        }
    }
    // The folder's own exercises win over the library's: they're what it will hold.
    val fields = library + parsed.map { it.second }.filterIsInstance<ExerciseDraft>().associate { it.id to it.fields }
    val exercises = knownExercises + fields.keys
    val routines = knownRoutines + parsed.map { it.second }.filterIsInstance<RoutineDraft>().map { it.id }
    val sides = fields.mapValues { it.value.sides }
    val tracks = fields.mapValues { it.value.tracks }

    // An exercise step's own amount must be something the exercise tracks.
    fun checkSteps(
        file: ContentFile,
        steps: List<GuidedStepDraft>,
    ): Boolean {
        val unknown = steps.filter { it.exercise != null && it.exercise !in exercises }
        unknown.forEach { problems += ContentProblem.UnknownExercise(file.path, it.line, it.exercise!!) }
        for (step in steps) {
            val tracked = step.exercise?.let(tracks::get) ?: continue
            if (step.duration != null && !tracked.time) {
                problems += ContentProblem.TracksMismatch(file.path, step.line, step.exercise, "duration", "reps".takeIf { tracked.reps })
            }
            if (step.reps != null && !tracked.reps) {
                problems += ContentProblem.TracksMismatch(file.path, step.line, step.exercise, "reps", "duration".takeIf { tracked.time })
            }
        }
        return unknown.isEmpty()
    }
    for ((file, item) in parsed) {
        if (item is RoutineDraft) {
            item.items
                .filter { it.exercise !in exercises }
                .forEach { problems += ContentProblem.UnknownExercise(file.path, it.line, it.exercise) }
        }
        if (item is LessonDraft) {
            item.exercises
                .filter { (id, _) -> id !in exercises }
                .forEach { (id, line) -> problems += ContentProblem.UnknownExercise(file.path, line, id) }
            item.practice?.let { checkSteps(file, it.steps) }
        }
        if (item is RecoveryDraft) {
            if (checkSteps(file, item.steps)) {
                val estimate = recoveryEstimate(item) { sides[it] ?: Sides.Both }
                if (abs(item.minutes - estimate) > MINUTES_SLACK) {
                    problems += ContentProblem.MinutesOff(file.path, item.minutesLine, item.minutes, estimate)
                }
            }
        }
        if (item is ProgramDraft) {
            item.days
                .filter { (_, routine) -> routine !in routines }
                .forEach { (weekday, routine) ->
                    problems += ContentProblem.UnknownRoutine(file.path, item.dayLines[weekday] ?: 1, routine)
                }
        }
    }
    return ContentCheck(parsed.map { it.second }, problems, parsed.map { it.first.path })
}

private fun List<GuidedStepDraft>.stepImages(): List<Pair<String, Int>> = mapNotNull { step -> step.image?.let { it to step.line } }
