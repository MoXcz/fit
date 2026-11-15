// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.core.BodyArea
import app.fit.exercises.Sides
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class RecoveryContentTest {
    private fun file(
        text: String,
        path: String = "recovery/wrist-reset.md",
    ) = ContentFile(path, text.trimIndent())

    private fun parse(text: String) = ContentParser.parse(file(text))

    private val stretch =
        file(
            """
            ---
            format: 1
            kind: exercise
            id: wrist-flexor-stretch
            name: Wrist flexor stretch
            tracks: [time]
            sides: each
            ---
            """,
            "exercises/wrist-flexor-stretch.md",
        )

    // 30 s of reps + flexor 4 × 30 s + 15 s, 4 pauses of 5 s and 2 switches of 5 s: 195 s, 3.25 min.
    private fun session(
        minutes: Int = 3,
        extra: String = "",
    ) = """
        ---
        format: 1
        kind: recovery
        id: wrist-reset
        title: Wrist reset
        areas: [wrists, forearms]
        minutes: $minutes
        steps:
          - { name: Wrist bends, reps: 10, cue: "Up, then down" }
          - { exercise: wrist-flexor-stretch, duration: 30, rounds: 2 }
          - { name: Shake out, duration: 15s }$extra
        sources:
          - { title: "NHS", url: https://www.nhs.uk/ }
        ---
        Stop if it hurts.
        """

    private fun check(
        vararg files: ContentFile,
        images: Set<String> = emptySet(),
    ) = checkContent(files.toList()) { it in images }

    @Test
    fun `a recovery file is read with its steps, transition, areas and notes`() {
        val draft = assertIs<RecoveryDraft>(assertIs<ContentResult.Parsed>(parse(session())).item)
        assertEquals("wrist-reset", draft.id)
        assertEquals("Wrist reset", draft.title)
        assertEquals(listOf(BodyArea.Wrists, BodyArea.Forearms), draft.areas)
        // Left out: 5 s.
        assertEquals(5.seconds, draft.transition)
        assertEquals("Stop if it hurts.", draft.notes)
        assertEquals(
            listOf(
                GuidedStepDraft(exercise = null, name = "Wrist bends", line = 9, reps = 10, cue = "Up, then down"),
                GuidedStepDraft(exercise = "wrist-flexor-stretch", name = null, line = 10, duration = 30.seconds, rounds = 2),
                GuidedStepDraft(exercise = null, name = "Shake out", line = 11, duration = 15.seconds),
            ),
            draft.steps,
        )
        assertEquals(listOf(Source("NHS", "https://www.nhs.uk/")), draft.sources)
    }

    @Test
    fun `a step needs an exercise or a name, and a duration or reps, never both`() {
        val result =
            parse(
                session(
                    extra =
                        "\n          - { name: A, exercise: plank, duration: 10 }" +
                            "\n          - { duration: 10 }" +
                            "\n          - { name: B, duration: 10, reps: 5 }" +
                            "\n          - { name: C }",
                ),
            )
        val problems = assertIs<ContentResult.Invalid>(result).problems.map { it.describe() }
        assertEquals(
            listOf(
                "recovery/wrist-reset.md:12: steps[3].name must be left out when the step names an exercise",
                "recovery/wrist-reset.md:13: steps[4].exercise must be an exercise id, or a name for a step that isn't in the library",
                "recovery/wrist-reset.md:14: steps[5].reps must be left out when the step has a duration",
                "recovery/wrist-reset.md:15: steps[6].duration must be a duration, or reps for a counted step",
            ),
            problems,
        )
    }

    @Test
    fun `minutes, steps and sources are required, and unknown keys are errors`() {
        val result = parse("---\nformat: 1\nkind: recovery\nid: x\ntitle: X\nlength: 5\n---\n")
        val problems = assertIs<ContentResult.Invalid>(result).problems.map { it.describe() }
        assertTrue(problems.any { "minutes is required" in it })
        assertTrue(problems.any { "steps is required" in it })
        assertTrue(problems.any { "sources is required" in it })
        assertTrue(problems.any { "unknown key length" in it })
    }

    @Test
    fun `the content check passes minutes within 2 of the steps' total`() {
        // 3.25 min: 2 to 5 pass.
        assertEquals(emptyList(), check(stretch, file(session(minutes = 2))).problems)
        assertEquals(emptyList(), check(stretch, file(session(minutes = 5))).problems)
    }

    @Test
    fun `the content check fails minutes more than 2 away, at the minutes line`() {
        val problems = check(stretch, file(session(minutes = 6))).problems.map { it.describe() }
        assertEquals(listOf("recovery/wrist-reset.md:7: minutes must be within 2 of the steps' total (about 3.3)"), problems)
        assertEquals(1, check(stretch, file(session(minutes = 1))).problems.size)
    }

    @Test
    fun `the minutes check uses the exercise's sides, and a step's own sides win`() {
        // Without the exercise's "each" the stretch runs once a round: two items and two switches fewer, 125 s.
        val draft = assertIs<RecoveryDraft>(assertIs<ContentResult.Parsed>(parse(session())).item)
        assertEquals(3.25, recoveryEstimate(draft) { Sides.Each })
        assertEquals(125.0 / 60, recoveryEstimate(draft) { Sides.Both })
        val own = draft.copy(steps = draft.steps.map { if (it.exercise != null) it.copy(sides = Sides.Both) else it })
        assertEquals(125.0 / 60, recoveryEstimate(own) { Sides.Each })
    }

    @Test
    fun `an unknown exercise is reported with its file and line`() {
        val problems = check(file(session())).problems.map { it.describe() }
        assertEquals(listOf("recovery/wrist-reset.md:10: there is no exercise with the id wrist-flexor-stretch"), problems)
    }

    @Test
    fun `a step's image must exist`() {
        val text = session(extra = "\n          - { name: Prayer, duration: 30, image: images/prayer.webp }", minutes = 4)
        assertEquals(
            listOf("recovery/wrist-reset.md:12: images/prayer.webp doesn't exist"),
            check(stretch, file(text)).problems.map { it.describe() },
        )
        assertEquals(emptyList(), check(stretch, file(text), images = setOf("images/prayer.webp")).problems)
    }

    @Test
    fun `a recovery file lives in recovery and its title is unique`() {
        val moved = file(session(), "routines/wrist-reset.md")
        assertEquals(
            listOf("routines/wrist-reset.md:1: the file must be recovery/wrist-reset.md"),
            check(stretch, moved).problems.map { it.describe() },
        )
    }

    @Test
    fun `a recovery step must fit what its exercise tracks`() {
        val text = session(extra = "\n          - { exercise: wrist-flexor-stretch, reps: 10 }", minutes = 4)
        assertEquals(
            listOf("recovery/wrist-reset.md:12: the step has reps, but wrist-flexor-stretch doesn't track reps (give a duration)"),
            check(stretch, file(text)).problems.map { it.describe() },
        )
    }

    @Test
    fun `an exercise known only by id fits any step`() {
        val problems = checkContent(listOf(file(session())), knownExercises = setOf("wrist-flexor-stretch")) { false }.problems
        assertEquals(emptyList(), problems.filterIsInstance<ContentProblem.TracksMismatch>())
    }

    @Test
    fun `the notes are Markdown in the subset, checked at their lines`() {
        val text = session().replace("Stop if it hurts.", "Stop if it **hurts.\n\n        ![Stretch](images/stretch.webp)")
        val problems = assertIs<ContentResult.Invalid>(parse(text)).problems.map { it.describe() }
        assertEquals(listOf("recovery/wrist-reset.md:15: Markdown: ** isn't closed"), problems)
        val fine = session().replace("Stop if it hurts.", "Stop if it **hurts**.\n\n        ![Stretch](images/stretch.webp)")
        assertEquals(
            listOf("recovery/wrist-reset.md:17: images/stretch.webp doesn't exist"),
            check(stretch, file(fine)).problems.map { it.describe() },
        )
    }
}
