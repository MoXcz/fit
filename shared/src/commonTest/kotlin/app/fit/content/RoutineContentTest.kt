// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.exercises.Tracks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class RoutineContentTest {
    private fun parse(text: String) = ContentParser.parse(ContentFile("routines/legs.md", text.trimIndent()))

    @Test
    fun `a routine file is read with its items and sources`() {
        val draft =
            assertIs<RoutineDraft>(
                assertIs<ContentResult.Parsed>(
                    parse(
                        """
                        ---
                        format: 1
                        kind: routine
                        id: legs
                        name: Legs
                        items:
                          - { exercise: jumping-jacks, section: Warm-up, duration: 30 }
                          - { exercise: squat, sets: 3, reps: 8-15, rest: 2m-4m, progress: { step: 2.5, rpe: 8 }, warmup-sets: 2 }
                          - { exercise: plank, tracks: [reps, time], notes: "slow" }
                        sources:
                          - { title: "A book", url: https://example.org }
                          - { title: Coach }
                        ---
                        Heavy day.
                        """,
                    ),
                ).item,
            )
        assertEquals("Legs", draft.name)
        assertEquals("Heavy day.", draft.notes)
        assertEquals(
            listOf(
                RoutineItemDraft("jumping-jacks", line = 7, section = "Warm-up", duration = 30.seconds),
                RoutineItemDraft(
                    "squat",
                    line = 8,
                    sets = 3,
                    reps = 8 to 15,
                    rest = 2.minutes to 4.minutes,
                    progressStep = 2.5,
                    progressRpe = 8.0,
                    warmupSets = 2,
                ),
                RoutineItemDraft("plank", line = 9, tracks = Tracks(reps = true, time = true), notes = "slow"),
            ),
            draft.items,
        )
        assertEquals(listOf(Source("A book", "https://example.org"), Source("Coach", null)), draft.sources)
    }

    @Test
    fun `a routine needs items and sources`() {
        val problems =
            assertIs<ContentResult.Invalid>(
                parse("---\nformat: 1\nkind: routine\nid: legs\nname: Legs\nitems: []\n---\n"),
            ).problems
        assertEquals(
            listOf("sources" to 2, "items" to 6),
            problems.map {
                when (it) {
                    is ContentProblem.BadValue -> it.field to it.line
                    is ContentProblem.MissingKey -> it.field to it.line
                    else -> error("unexpected $it")
                }
            },
        )
    }

    @Test
    fun `item values are checked`() {
        val problems =
            assertIs<ContentResult.Invalid>(
                parse(
                    """
                    ---
                    format: 1
                    kind: routine
                    id: legs
                    name: Legs
                    items:
                      - { exercise: squat, warmup-sets: 4, progress: { rpe: 11 }, weight: 20 }
                    sources: [{ title: Me }]
                    ---
                    """,
                ),
            ).problems
        assertEquals(
            setOf("items[0].warmup-sets", "items[0].progress.step", "items[0].progress.rpe", "items[0].weight"),
            problems
                .map {
                    when (it) {
                        is ContentProblem.BadValue -> it.field
                        is ContentProblem.MissingKey -> it.field
                        is ContentProblem.UnknownKey -> it.field
                        else -> error("unexpected $it")
                    }
                }.toSet(),
        )
    }

    @Test
    fun `the content check catches a wrong exercise id at its line`() {
        val routine =
            ContentFile(
                "routines/legs.md",
                "---\nformat: 1\nkind: routine\nid: legs\nname: Legs\nitems:\n  - { exercise: squat }\n  - { exercise: sqaut }\nsources: [{ title: Me }]\n---\n",
            )
        val squat = ContentFile("exercises/squat.md", "---\nformat: 1\nkind: exercise\nid: squat\nname: Squat\n---\n")
        assertEquals(
            listOf(ContentProblem.UnknownExercise("routines/legs.md", 8, "sqaut")),
            checkContent(listOf(routine, squat)) { false }.problems,
        )
        assertEquals(
            emptyList(),
            checkContent(
                listOf(routine.copy(text = routine.text.replace("sqaut", "dip"))),
                knownExercises = setOf("squat", "dip"),
            ) {
                false
            }.problems,
        )
    }
}
