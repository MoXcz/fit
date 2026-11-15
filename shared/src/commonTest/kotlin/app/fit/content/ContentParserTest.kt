// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.core.BodyArea
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseRef
import app.fit.exercises.RefKind
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ContentParserTest {
    private fun parse(text: String) = ContentParser.parse(ContentFile("exercises/test.md", text.trimIndent()))

    private fun problems(text: String): List<ContentProblem> = assertIs<ContentResult.Invalid>(parse(text)).problems

    private fun exercise(text: String): ExerciseDraft = assertIs<ExerciseDraft>(assertIs<ContentResult.Parsed>(parse(text)).item)

    @Test
    fun `a full exercise file is read into a draft`() {
        val draft =
            exercise(
                """
                ---
                format: 1
                kind: exercise
                id: wrist-flexor-stretch
                name: Wrist flexor stretch
                category: Mobility
                tracks: [time]            # a comment
                sides: each
                areas: [wrists, forearms]
                equipment: band
                defaults:
                  sets: 2
                  reps: 8-12
                  rest: 90-120
                  duration: 1m30s
                  weight: 2.5
                units: { weight: lb, distance: mi }
                video: https://www.youtube.com/watch?v=abc
                images: [images/wrist-flexor-stretch.webp]
                refs:
                  - { kind: video, title: "How to", url: https://example.org/a }
                  - { url: https://example.org/b }
                ---
                Arm straight, palm up.

                No bouncing.
                """,
            )
        assertEquals("wrist-flexor-stretch", draft.id)
        assertEquals(
            ExerciseFields(
                name = "Wrist flexor stretch",
                category = "Mobility",
                tracks = Tracks(time = true),
                sides = Sides.Each,
                areas = listOf(BodyArea.Wrists, BodyArea.Forearms),
                defaults =
                    ExerciseDefaults(
                        sets = 2,
                        reps = 8,
                        repsMax = 12,
                        rest = 90.seconds,
                        restMax = 120.seconds,
                        duration = 90.seconds,
                        weight = 2.5,
                    ),
                weightUnit = WeightUnit.Lb,
                distanceUnit = DistanceUnit.Mi,
                notes = "Arm straight, palm up.\n\nNo bouncing.",
                videoUrl = "https://www.youtube.com/watch?v=abc",
                images = listOf("images/wrist-flexor-stretch.webp"),
                refs =
                    listOf(
                        ExerciseRef(RefKind.Video, "How to", "https://example.org/a"),
                        ExerciseRef(RefKind.Link, "", "https://example.org/b"),
                    ),
                equipment = Equipment.Band,
            ),
            draft.fields,
        )
    }

    @Test
    fun `missing values come from the exercise defaults`() {
        val draft =
            exercise(
                """
                ---
                format: 1
                kind: exercise
                id: push-up
                name: Push-up
                ---
                """,
            )
        assertEquals(ExerciseFields(name = "Push-up"), draft.fields)
    }

    @Test
    fun `durations take seconds, minutes or both`() {
        assertEquals(45.seconds, parseDuration("45"))
        assertEquals(45.seconds, parseDuration("45s"))
        assertEquals(2.minutes, parseDuration("2m"))
        assertEquals(90.seconds, parseDuration("1m30s"))
        assertEquals(null, parseDuration("1h"))
        assertEquals(null, parseDuration(""))
        assertEquals(null, parseDuration("-5"))
    }

    @Test
    fun `a range must not go down and a single value has no maximum`() {
        assertEquals(8 to 12, parseRange("8-12", String::toIntOrNull))
        assertEquals(10 to null, parseRange("10", String::toIntOrNull))
        assertEquals(10 to null, parseRange("10-10", String::toIntOrNull))
        assertEquals(null, parseRange("12-8", String::toIntOrNull))
        assertEquals(2.minutes to 4.minutes, parseRange("2m-4m", ::parseDuration))
    }

    @Test
    fun `a byte order mark before the header is ignored`() {
        val draft = exercise("\uFEFF---\nformat: 1\nkind: exercise\nid: a\nname: A\n---\n")
        assertEquals("A", draft.fields.name)
    }

    @Test
    fun `an empty header is reported at its first line`() {
        assertEquals(listOf(ContentProblem.MissingKey("exercises/test.md", 2, "format")), problems("---\n---\nBody"))
    }

    @Test
    fun `a weight must be a finite number up to 1000`() {
        listOf(".inf", "Infinity", "1000.5", "-1").forEach { weight ->
            val found = problems("---\nformat: 1\nkind: exercise\nid: a\nname: A\ndefaults: { weight: $weight }\n---\n")
            assertEquals(listOf("defaults.weight"), found.map { assertIs<ContentProblem.BadValue>(it).field }, weight)
        }
    }

    @Test
    fun `a file without a header is reported at line 1`() {
        assertEquals(listOf(ContentProblem.NoHeader("exercises/test.md")), problems("name: Push-up"))
    }

    @Test
    fun `unknown keys are errors with their line, also inside maps`() {
        val found =
            problems(
                """
                ---
                format: 1
                kind: exercise
                id: push-up
                name: Push-up
                defaults:
                  set: 3
                catgory: Chest
                ---
                """,
            )
        assertEquals(
            listOf(
                ContentProblem.UnknownKey("exercises/test.md", 7, "defaults.set"),
                ContentProblem.UnknownKey("exercises/test.md", 8, "catgory"),
            ),
            found,
        )
    }

    @Test
    fun `required keys are reported when missing`() {
        val found = problems("---\nformat: 1\nkind: exercise\n---\n")
        assertEquals(setOf("id", "name"), found.filterIsInstance<ContentProblem.MissingKey>().map { it.field }.toSet())
    }

    @Test
    fun `bad values name the field, its line and what was expected`() {
        val found =
            problems(
                """
                ---
                format: 1
                kind: exercise
                id: Push Up
                name: Push-up
                tracks: [weight, speed]
                sides: left
                areas: [wrist]
                defaults: { reps: 12-8, rest: soon }
                refs:
                  - { kind: tweet, url: example.org }
                ---
                """,
            )
        assertEquals(
            listOf(
                "id" to 4,
                "tracks[1]" to 6,
                "sides" to 7,
                "areas[0]" to 8,
                "defaults.reps" to 9,
                "defaults.rest" to 9,
                "refs[0].kind" to 11,
                "refs[0].url" to 11,
            ),
            found.map { assertIs<ContentProblem.BadValue>(it).field to it.line },
        )
    }

    @Test
    fun `an exercise must track something`() {
        val found = problems("---\nformat: 1\nkind: exercise\nid: a\nname: A\ntracks: []\n---\n")
        assertEquals(listOf("tracks"), found.map { assertIs<ContentProblem.BadValue>(it).field })
    }

    @Test
    fun `a key given twice is an error`() {
        val found = problems("---\nformat: 1\nkind: exercise\nid: a\nname: A\nname: B\n---\n")
        assertEquals(listOf(ContentProblem.DuplicateKey("exercises/test.md", 6, "name")), found)
    }

    @Test
    fun `broken YAML is reported with its line`() {
        val found = problems("---\nformat: 1\nkind: exercise\nid: a\nname: [A\n---\n")
        val problem = assertIs<ContentProblem.BadYaml>(found.single())
        assertEquals(5, problem.line)
    }

    @Test
    fun `another format or an unsupported kind stops the check there`() {
        assertEquals(
            listOf(ContentProblem.UnsupportedFormat("exercises/test.md", 2, "2")),
            problems("---\nformat: 2\nkind: exercise\nid: a\nanything: x\n---\n"),
        )
        assertEquals(
            listOf(ContentProblem.UnsupportedKind("exercises/test.md", 2, "cardio")),
            problems("---\nformat: 1\nkind: cardio\nid: a\n---\n"),
        )
    }

    @Test
    fun `problems read as file, line and message`() {
        assertEquals(
            "exercises/test.md:7: unknown key defaults.set",
            ContentProblem.UnknownKey("exercises/test.md", 7, "defaults.set").describe(),
        )
    }

    @Test
    fun `equipment takes one of its slugs, is none by default, and an unknown one is reported with its line`() {
        val base =
            """
            ---
            format: 1
            kind: exercise
            id: curl
            name: Curl
            """.trimIndent()
        assertEquals(null, exercise("$base\n---").fields.equipment)
        Equipment.entries.forEach {
            assertEquals(it, exercise("$base\nequipment: ${it.slug}\n---").fields.equipment)
        }
        val problem = assertIs<ContentProblem.BadValue>(problems("$base\nequipment: smith\n---").single())
        assertEquals("equipment" to 6, problem.field to problem.line)
        assertEquals(
            "exercises/test.md:6: equipment must be barbell, dumbbell, kettlebell, cable, machine, band, bodyweight or other",
            problem.describe(),
        )
    }
}
