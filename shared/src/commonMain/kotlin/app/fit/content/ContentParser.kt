// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.core.BodyArea
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseRef
import app.fit.exercises.MAX_WEIGHT
import app.fit.exercises.RefKind
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import app.fit.programs.Deload
import app.fit.programs.ProgramSchedule
import it.krzeminski.snakeyaml.engine.kmp.api.LoadSettings
import it.krzeminski.snakeyaml.engine.kmp.api.lowlevel.Compose
import it.krzeminski.snakeyaml.engine.kmp.exceptions.MarkedYamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.exceptions.YamlEngineException
import it.krzeminski.snakeyaml.engine.kmp.nodes.MappingNode
import it.krzeminski.snakeyaml.engine.kmp.nodes.Node
import kotlinx.datetime.DayOfWeek
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

const val CONTENT_FORMAT = 1

// The header starts on the file's second line, after the opening "---".
private const val FIRST_YAML_LINE = 2

private val SUPPORTED_KINDS = setOf("exercise", "routine", "program", "recovery", "lesson")

// Reads content files (docs/content-format.md). The only place that parses content.
object ContentParser {
    fun parse(file: ContentFile): ContentResult {
        val split = splitHeader(file.text) ?: return ContentResult.Invalid(listOf(ContentProblem.NoHeader(file.path)))
        val context = ParseContext(file.path, firstYamlLine = FIRST_YAML_LINE)
        val root = compose(split.yaml, context) ?: return ContentResult.Invalid(context.problems)
        if (root !is MappingNode) {
            context.problems += ContentProblem.BadYaml(file.path, context.line(root), "the header must be a map of keys")
            return ContentResult.Invalid(context.problems)
        }
        val header = YamlMap(context, root, path = "")
        val item = readItem(header, split, context)
        // An unknown format or kind means the other keys can't be judged.
        val unreadable = context.problems.filter { it is ContentProblem.UnsupportedFormat || it is ContentProblem.UnsupportedKind }
        if (unreadable.isNotEmpty()) return ContentResult.Invalid(unreadable)
        header.finish()
        return if (item != null && context.problems.isEmpty()) {
            ContentResult.Parsed(item)
        } else {
            ContentResult.Invalid(context.problems.sortedBy { it.line })
        }
    }

    private fun readItem(
        header: YamlMap,
        split: Header,
        context: ParseContext,
    ): ContentItem? {
        val body = split.body
        val format = header.string("format", required = true)
        val kind = header.string("kind", required = true)
        val id = header.parsed("id", "a lowercase slug such as wrist-reset", required = true) { it.takeIf(::isSlug) }
        if (format != null && format != CONTENT_FORMAT.toString()) {
            context.problems += ContentProblem.UnsupportedFormat(context.file, header.line, format)
            return null
        }
        if (kind != null && kind !in SUPPORTED_KINDS) {
            context.problems += ContentProblem.UnsupportedKind(context.file, header.line, kind)
            return null
        }
        val item =
            when (kind) {
                "exercise" -> {
                    readExercise(header, body)?.let { fields -> id?.let { ExerciseDraft(it, fields) } }
                }

                "routine" -> {
                    readRoutine(header, body)?.let { routine -> id?.let { routine.copy(id = it) } }
                }

                "program" -> {
                    readProgram(header, body)?.let { program -> id?.let { program.copy(id = it) } }
                }

                "recovery" -> {
                    val notes = markdown(split, context)
                    readRecovery(header, body)?.let { recovery -> id?.let { recovery.copy(id = it, noteImages = notes.images) } }
                }

                "lesson" -> {
                    val doc = markdown(split, context)
                    if (body.isBlank()) context.problems += ContentProblem.NoBody(context.file, split.endLine)
                    readLesson(header, body)?.let { lesson -> id?.let { lesson.copy(id = it, bodyImages = doc.images) } }
                }

                else -> {
                    null
                }
            }
        return item
    }

    // The caller sets the id, so a bad id doesn't hide the other problems.
    private fun readRoutine(
        header: YamlMap,
        body: String,
    ): RoutineDraft? {
        val name = header.parsed("name", "a name", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val items = header.mapList("items", required = true)?.mapNotNull(::readRoutineItem)
        if (items != null && items.isEmpty()) header.bad("items", "a list of at least one exercise")
        val sources = header.mapList("sources", required = true)?.mapNotNull(::readSource)
        if (sources != null && sources.isEmpty()) header.bad("sources", "a list of at least one source")
        if (name == null || items.isNullOrEmpty() || sources.isNullOrEmpty()) return null
        return RoutineDraft(id = "", name = name, notes = body, items = items, sources = sources)
    }

    private fun readProgram(
        header: YamlMap,
        body: String,
    ): ProgramDraft? {
        val name = header.parsed("name", "a name", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val schedule = header.map("days", required = true)
        val days = LinkedHashMap<DayOfWeek, String>()
        val lines = HashMap<DayOfWeek, Int>()
        if (schedule != null) {
            WEEKDAYS.forEach { (key, weekday) ->
                schedule.parsed(key, "a routine id") { it.takeIf(::isSlug) }?.let {
                    days[weekday] = it
                    lines[weekday] = schedule.lineOf(key)
                }
            }
            schedule.finish()
            if (days.isEmpty()) header.bad("days", "a map from mon … sun to a routine id, with at least one day")
        }
        val every = header.int("deload-every", ProgramSchedule.DELOAD_EVERY)
        val percent = header.int("deload-percent", Deload.PERCENTS)
        if (name == null || days.isEmpty()) return null
        return ProgramDraft(id = "", name = name, notes = body, days = days, deloadEvery = every, deloadPercent = percent, dayLines = lines)
    }

    private fun readRecovery(
        header: YamlMap,
        body: String,
    ): RecoveryDraft? {
        val title = header.parsed("title", "a title", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val areas = header.scalarList("areas", "one of the body areas in docs/content-format.md", BodyArea::fromSlug)
        val minutes = header.int("minutes", MINUTES, required = true)
        val transition = transition(header)
        val steps = header.mapList("steps", required = true)?.mapNotNull(::readGuidedStep)
        if (steps != null && steps.isEmpty()) header.bad("steps", "a list of at least one step")
        val sources = header.mapList("sources", required = true)?.mapNotNull(::readSource)
        if (sources != null && sources.isEmpty()) header.bad("sources", "a list of at least one source")
        if (title == null || minutes == null || steps.isNullOrEmpty() || sources.isNullOrEmpty()) return null
        return RecoveryDraft(
            id = "",
            title = title,
            notes = body,
            areas = areas.orEmpty().distinct(),
            minutes = minutes,
            transition = transition ?: DEFAULT_TRANSITION,
            steps = steps,
            sources = sources,
            minutesLine = header.lineOf("minutes"),
        )
    }

    private fun readLesson(
        header: YamlMap,
        body: String,
    ): LessonDraft? {
        val title = header.parsed("title", "a title", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val exercises = header.scalarLines("exercises", "an exercise id") { it.takeIf(::isSlug) }
        val practice = header.map("practice")?.let(::readPractice)
        val sources = header.mapList("sources", required = true)?.mapNotNull(::readSource)
        if (sources != null && sources.isEmpty()) header.bad("sources", "a list of at least one source")
        if (title == null || sources.isNullOrEmpty() || (header.has("practice") && practice == null)) return null
        return LessonDraft(
            id = "",
            title = title,
            body = body,
            exercises = exercises.orEmpty().distinctBy { it.first },
            practice = practice,
            sources = sources,
        )
    }

    private fun readPractice(map: YamlMap): PracticeDraft? {
        val transition = transition(map)
        val steps = map.mapList("steps", required = true)?.mapNotNull(::readGuidedStep)
        if (steps != null && steps.isEmpty()) map.bad("steps", "a list of at least one step")
        map.finish()
        if (steps.isNullOrEmpty()) return null
        return PracticeDraft(transition = transition ?: DEFAULT_TRANSITION, steps = steps)
    }

    private fun transition(map: YamlMap): Duration? =
        map.parsed("transition", "a duration from 0 to 2m, such as 5 or 10s") { text ->
            parseDuration(text)?.takeIf { it <= MAX_TRANSITION }
        }

    // The body's Markdown issues are the file's problems, at their lines.
    private fun markdown(
        split: Header,
        context: ParseContext,
    ): MarkdownDoc {
        val doc = Markdown.parse(split.body, split.bodyLine)
        doc.issues.forEach { context.problems += ContentProblem.BadMarkdown(context.file, it.line, it.detail) }
        return doc
    }

    // An exercise or an inline name, a duration or reps: one of each.
    private fun readGuidedStep(map: YamlMap): GuidedStepDraft? {
        val exercise = map.parsed("exercise", "an exercise id") { it.takeIf(::isSlug) }
        val name = map.parsed("name", "a name") { it.trim().takeIf(String::isNotEmpty) }
        val duration = map.parsed("duration", "a duration such as 45, 45s or 1m30s") { text -> parseDuration(text)?.takeIf { it in TIMED } }
        val reps = map.parsed("reps", "a number of reps from 1 to 1000", parse = ::repCount)
        val sides = map.parsed("sides", "both or each") { SIDES[it] }
        val cue = map.string("cue")?.trim()?.takeIf(String::isNotEmpty)
        val image = map.parsed("image", "a path such as images/name.webp") { it.takeIf(::isImagePath) }
        val rounds = map.int("rounds", 1..20)
        map.finish()
        val named = map.has("exercise") || map.has("name")
        if (map.has("exercise") && map.has("name")) map.badHere("name", "left out when the step names an exercise")
        if (!named) map.badHere("exercise", "an exercise id, or a name for a step that isn't in the library")
        val timed = map.has("duration") || map.has("reps")
        if (map.has("duration") && map.has("reps")) map.badHere("reps", "left out when the step has a duration")
        if (!timed) map.badHere("duration", "a duration, or reps for a counted step")
        if ((exercise == null && name == null) || (duration == null && reps == null)) return null
        return GuidedStepDraft(
            exercise = exercise,
            name = name.takeIf { exercise == null },
            line = map.line,
            duration = duration,
            reps = reps.takeIf { duration == null },
            sides = sides,
            cue = cue,
            image = image,
            rounds = rounds ?: 1,
        )
    }

    private fun readRoutineItem(map: YamlMap): RoutineItemDraft? {
        val exercise = map.parsed("exercise", "an exercise id", required = true) { it.takeIf(::isSlug) }
        val section = map.parsed("section", "a section name") { it.trim().takeIf(String::isNotEmpty) }
        val sets = map.int("sets", 1..20)
        val reps = map.parsed("reps", "a number of reps or a range such as 8-12") { text -> parseRange(text, ::repCount) }
        val rest = map.parsed("rest", "seconds or a range such as 90-120") { text -> parseRange(text, ::restLength) }
        val duration = map.parsed("duration", "a duration such as 45, 45s or 1m30s") { text -> parseDuration(text)?.takeIf { it in TIMED } }
        val tracks = map.scalarList("tracks", "weight, reps, time or distance") { it.takeIf { it in TRACKS } }?.let(::tracksOf)
        if (tracks != null && !tracks.any) map.bad("tracks", "at least one of weight, reps, time or distance")
        val progress = map.map("progress")
        val step =
            progress?.parsed("step", "a weight from 0.25 to 100", required = true) { text ->
                text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.25..100.0 }
            }
        val rpe =
            progress?.parsed("rpe", "an RPE from 5 to 10 in steps of 0.5") { text ->
                text.toDoubleOrNull()?.takeIf { it in 5.0..10.0 && (it * 2) % 1.0 == 0.0 }
            }
        progress?.finish()
        val warmupSets = map.int("warmup-sets", 0..3)
        val notes = map.string("notes")?.trim()
        map.finish()
        exercise ?: return null
        return RoutineItemDraft(
            exercise = exercise,
            line = map.line,
            section = section,
            sets = sets,
            reps = reps,
            rest = rest,
            duration = duration,
            tracks = tracks?.takeIf { it.any },
            progressStep = step,
            progressRpe = rpe,
            warmupSets = warmupSets,
            notes = notes,
        )
    }

    private fun readSource(map: YamlMap): Source? {
        val title = map.parsed("title", "a title", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val url = map.parsed("url", "a web address (https://…)") { it.takeIf(::isWebUrl) }
        map.finish()
        return title?.let { Source(it, url) }
    }

    private fun readExercise(
        header: YamlMap,
        body: String,
    ): ExerciseFields? {
        val name = header.parsed("name", "a name", required = true) { it.trim().takeIf(String::isNotEmpty) }
        val category = header.string("category")?.trim().orEmpty()
        val tracks = header.scalarList("tracks", "weight, reps, time or distance") { it.takeIf { it in TRACKS } }?.let(::tracksOf)
        if (tracks != null && !tracks.any) header.bad("tracks", "at least one of weight, reps, time or distance")
        val sides = header.parsed("sides", "both or each") { SIDES[it] }
        val areas = header.scalarList("areas", "one of the body areas in docs/content-format.md", BodyArea::fromSlug)
        val defaults = header.map("defaults")?.let(::readDefaults)
        val units = header.map("units")
        val weightUnit = units?.parsed("weight", "kg or lb") { text -> WeightUnit.entries.firstOrNull { it.symbol == text } }
        val distanceUnit = units?.parsed("distance", "km, mi or m") { text -> DistanceUnit.entries.firstOrNull { it.symbol == text } }
        units?.finish()
        val video = header.parsed("video", "a web address (https://…)") { it.takeIf(::isWebUrl) }
        val images = header.scalarList("images", "a path such as images/name.webp") { it.takeIf(::isImagePath) }
        val refs = header.mapList("refs")?.mapNotNull(::readRef)
        val equipment = header.parsed("equipment", EQUIPMENT_VALUES, parse = Equipment::fromSlug)
        name ?: return null
        return ExerciseFields(
            name = name,
            category = category,
            tracks = tracks ?: Tracks.WeightAndReps,
            sides = sides ?: Sides.Both,
            areas = areas.orEmpty().distinct(),
            defaults = defaults ?: ExerciseDefaults(),
            weightUnit = weightUnit ?: WeightUnit.Kg,
            distanceUnit = distanceUnit ?: DistanceUnit.Km,
            notes = body,
            videoUrl = video.orEmpty(),
            images = images.orEmpty(),
            refs = refs.orEmpty(),
            equipment = equipment,
        )
    }

    private fun readDefaults(map: YamlMap): ExerciseDefaults {
        val base = ExerciseDefaults()
        val sets = map.int("sets", 1..20)
        val reps = map.parsed("reps", "a number of reps or a range such as 8-12") { text -> parseRange(text, ::repCount) }
        val rest = map.parsed("rest", "seconds or a range such as 90-120") { text -> parseRange(text, ::restLength) }
        val duration = map.parsed("duration", "a duration such as 45, 45s or 1m30s") { text -> parseDuration(text)?.takeIf { it in TIMED } }
        val weight =
            map.parsed("weight", "a weight from 0 to ${MAX_WEIGHT.toInt()}") { text ->
                text.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..MAX_WEIGHT }
            }
        map.finish()
        return ExerciseDefaults(
            sets = sets ?: base.sets,
            reps = reps?.first ?: base.reps,
            repsMax = if (reps != null) reps.second else base.repsMax,
            rest = rest?.first ?: base.rest,
            restMax = if (rest != null) rest.second else base.restMax,
            duration = duration ?: base.duration,
            weight = weight ?: base.weight,
        )
    }

    private fun readRef(map: YamlMap): ExerciseRef? {
        val kind = map.parsed("kind", "link, video, blog or paper") { text -> RefKind.entries.firstOrNull { it.name.lowercase() == text } }
        val title = map.string("title")?.trim().orEmpty()
        val url = map.parsed("url", "a web address (https://…)", required = true) { it.takeIf(::isWebUrl) }
        map.finish()
        return url?.let { ExerciseRef(kind ?: RefKind.Link, title, it) }
    }

    private fun compose(
        yaml: String,
        context: ParseContext,
    ): Node? =
        try {
            val node = Compose(LoadSettings()).compose(yaml)
            if (node == null) context.problems += ContentProblem.MissingKey(context.file, FIRST_YAML_LINE, "format")
            node
        } catch (e: MarkedYamlEngineException) {
            val line = (e.problemMark?.line ?: 0) + FIRST_YAML_LINE
            context.problems += ContentProblem.BadYaml(context.file, line, e.problem)
            null
        } catch (e: YamlEngineException) {
            context.problems += ContentProblem.BadYaml(context.file, FIRST_YAML_LINE, e.message ?: "can't be read")
            null
        }
}

private val TRACKS = setOf("weight", "reps", "time", "distance")
private val WEEKDAYS =
    listOf(
        "mon" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY,
    )
internal val EQUIPMENT_VALUES = Equipment.entries.dropLast(1).joinToString(", ") { it.slug } + " or " + Equipment.entries.last().slug
private val MINUTES = 1..120
private val MAX_TRANSITION = 2.minutes
private val DEFAULT_TRANSITION = 5.seconds
private val SIDES = mapOf("both" to Sides.Both, "each" to Sides.Each)
private val TIMED = 1.seconds..2.hours

private fun tracksOf(names: List<String>) =
    Tracks(weight = "weight" in names, reps = "reps" in names, time = "time" in names, distance = "distance" in names)

private fun repCount(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..1000 }

private fun restLength(text: String): Duration? = parseDuration(text)?.takeIf { it <= 1.hours }

private class Header(
    val yaml: String,
    val body: String,
    // The file line the trimmed body starts on.
    val bodyLine: Int,
    // The file line of the closing "---".
    val endLine: Int,
)

// The header is everything between a first line of "---" and the next "---" line.
private fun splitHeader(text: String): Header? {
    val lines = text.removePrefix("\uFEFF").replace("\r\n", "\n").split('\n')
    if (lines.firstOrNull()?.trimEnd() != "---") return null
    val end =
        lines
            .drop(1)
            .indexOfFirst { it.trimEnd() == "---" }
            .takeIf { it >= 0 }
            ?.plus(1) ?: return null
    val rest = lines.drop(end + 1)
    val firstText = rest.indexOfFirst { it.isNotBlank() }.coerceAtLeast(0)
    return Header(
        yaml = lines.subList(1, end).joinToString("\n"),
        body = rest.joinToString("\n").trim(),
        bodyLine = end + 2 + firstText,
        endLine = end + 1,
    )
}
