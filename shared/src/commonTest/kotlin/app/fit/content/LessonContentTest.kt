// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class LessonContentTest {
    private fun file(
        text: String,
        path: String = "lessons/push-up.md",
    ) = ContentFile(path, text.trimIndent())

    private fun exercise(
        id: String,
        tracks: String,
    ) = file("---\nformat: 1\nkind: exercise\nid: $id\nname: $id\ntracks: [$tracks]\n---\n", "exercises/$id.md")

    private val library = arrayOf(exercise("push-up", "reps"), exercise("wall-push-up", "time"), exercise("plank", "time"))

    private fun lesson(
        exercises: String = "[push-up, wall-push-up]",
        steps: String = "    - { exercise: wall-push-up, duration: 40 }\n    - { name: Incline push-up, reps: 6, rounds: 3 }",
        body: String = "## Setup\n\n- Hands under your shoulders.\n\n![Top](images/push-up-top.webp)",
        extra: String = "",
    ) = file(
        "---\nformat: 1\nkind: lesson\nid: push-up\ntitle: Push-up\nexercises: $exercises\npractice:\n  transition: 20\n" +
            "  steps:\n$steps\nsources:\n  - { title: \"E3 Rehab\", url: https://e3rehab.com/ }$extra\n---\n$body",
    )

    private fun parse(file: ContentFile) = ContentParser.parse(file)

    private fun problems(
        vararg files: ContentFile,
        images: Set<String> = setOf("images/push-up-top.webp"),
    ) = checkContent(files.toList()) { it in images }.problems

    @Test
    fun `a lesson file is read with its exercises, drill, sources and body`() {
        val draft = assertIs<LessonDraft>(assertIs<ContentResult.Parsed>(parse(lesson())).item)
        assertEquals("push-up", draft.id)
        assertEquals("Push-up", draft.title)
        assertEquals(listOf("push-up" to 6, "wall-push-up" to 6), draft.exercises)
        assertEquals(
            PracticeDraft(
                transition = 20.seconds,
                steps =
                    listOf(
                        GuidedStepDraft(exercise = "wall-push-up", name = null, line = 10, duration = 40.seconds),
                        GuidedStepDraft(exercise = null, name = "Incline push-up", line = 11, reps = 6, rounds = 3),
                    ),
            ),
            draft.practice,
        )
        assertEquals(listOf(Source("E3 Rehab", "https://e3rehab.com/")), draft.sources)
        assertEquals("## Setup\n\n- Hands under your shoulders.\n\n![Top](images/push-up-top.webp)", draft.body)
        // The body starts on line 15, after the closing ---.
        assertEquals(listOf(MdBlock.Image("Top", "images/push-up-top.webp", 19)), draft.bodyImages)
    }

    @Test
    fun `exercises and practice are optional, and practice's transition defaults to 5 s`() {
        val bare =
            file("---\nformat: 1\nkind: lesson\nid: bracing\ntitle: Bracing\nsources: [{ title: Me }]\n---\nBrace.", "lessons/bracing.md")
        val draft = assertIs<LessonDraft>(assertIs<ContentResult.Parsed>(parse(bare)).item)
        assertEquals(emptyList(), draft.exercises)
        assertNull(draft.practice)
        val drill =
            file(
                "---\nformat: 1\nkind: lesson\nid: b\ntitle: B\npractice:\n  steps: [{ name: Hold, duration: 10 }]\nsources: [{ title: Me }]\n---\nHold.",
                "lessons/b.md",
            )
        assertEquals(5.seconds, assertIs<LessonDraft>(assertIs<ContentResult.Parsed>(parse(drill)).item).practice?.transition)
    }

    @Test
    fun `title and sources are required, a drill needs steps, and practice has no minutes`() {
        val text = "---\nformat: 1\nkind: lesson\nid: b\npractice:\n  minutes: 5\nsources: []\n---\n"
        val problems = assertIs<ContentResult.Invalid>(parse(file(text, "lessons/b.md"))).problems
        assertEquals(
            listOf(
                ContentProblem.MissingKey("lessons/b.md", 2, "title"),
                ContentProblem.MissingKey("lessons/b.md", 6, "practice.steps"),
                ContentProblem.UnknownKey("lessons/b.md", 6, "practice.minutes"),
                ContentProblem.BadValue("lessons/b.md", 7, "sources", "a list of at least one source"),
                ContentProblem.NoBody("lessons/b.md", 8),
            ),
            problems.sortedBy { it.line },
        )
    }

    @Test
    fun `a step in a drill follows the recovery step rules`() {
        val problems =
            assertIs<ContentResult.Invalid>(
                parse(lesson(steps = "    - { exercise: plank, name: Plank, duration: 20, reps: 5 }")),
            ).problems
        assertEquals(
            listOf(
                ContentProblem.BadValue("lessons/push-up.md", 10, "practice.steps[0].name", "left out when the step names an exercise"),
                ContentProblem.BadValue("lessons/push-up.md", 10, "practice.steps[0].reps", "left out when the step has a duration"),
            ),
            problems,
        )
    }

    @Test
    fun `Markdown outside the subset is reported at its line in the file`() {
        val problems = assertIs<ContentResult.Invalid>(parse(lesson(body = "## Setup\n\n> quoted\n\n- a\n  - b"))).problems
        assertEquals(
            listOf(
                ContentProblem.BadMarkdown("lessons/push-up.md", 17, "quotes aren't supported"),
                ContentProblem.BadMarkdown("lessons/push-up.md", 20, "lists can't be nested"),
            ),
            problems,
        )
        assertEquals(
            "lessons/push-up.md:20: Markdown: lists can't be nested",
            problems.last().describe(),
        )
    }

    @Test
    fun `the body may hold any Markdown in the subset, sections or not`() {
        assertIs<ContentResult.Parsed>(parse(lesson(body = "Just a paragraph with **bold** text.\n\n1. One\n2. Two")))
    }

    @Test
    fun `the content check passes a lesson whose exercises, steps and images exist`() {
        assertEquals(emptyList(), problems(lesson(), *library))
    }

    @Test
    fun `an unknown exercise in exercises or in a step is reported at its line`() {
        val text = lesson(exercises = "\n  - push-up\n  - pistol-squat", steps = "    - { exercise: box-pistol, reps: 5 }")
        assertEquals(
            listOf(
                ContentProblem.UnknownExercise("lessons/push-up.md", 8, "pistol-squat"),
                ContentProblem.UnknownExercise("lessons/push-up.md", 12, "box-pistol"),
            ),
            problems(text, *library),
        )
    }

    @Test
    fun `a missing image in the body or a step is reported at its line`() {
        val text = lesson(steps = "    - { name: Hold, duration: 10, image: images/hold.webp }")
        assertEquals(
            listOf(
                ContentProblem.MissingImage("lessons/push-up.md", "images/hold.webp", 10),
                ContentProblem.MissingImage("lessons/push-up.md", "images/push-up-top.webp", 18),
            ),
            problems(text, *library, images = emptySet()).sortedBy { it.line },
        )
    }

    @Test
    fun `a drill step must fit what its exercise tracks`() {
        val text = lesson(steps = "    - { exercise: wall-push-up, reps: 10 }\n    - { exercise: push-up, duration: 30 }")
        val found = problems(text, *library)
        assertEquals(
            listOf(
                ContentProblem.TracksMismatch("lessons/push-up.md", 10, "wall-push-up", "reps", instead = "duration"),
                ContentProblem.TracksMismatch("lessons/push-up.md", 11, "push-up", "duration", instead = "reps"),
            ),
            found,
        )
        assertEquals(
            "lessons/push-up.md:10: the step has reps, but wall-push-up doesn't track reps (give a duration)",
            found.first().describe(),
        )
    }

    @Test
    fun `a step naming an exercise that tracks neither time nor reps is told to name the step instead`() {
        val text = lesson(steps = "    - { exercise: farmer-carry, reps: 10 }\n    - { exercise: farmer-carry, duration: 30 }")
        assertEquals(
            listOf(
                "lessons/push-up.md:10: the step has reps, but farmer-carry tracks neither time nor reps (name the step instead of the exercise)",
                "lessons/push-up.md:11: the step has a duration, but farmer-carry tracks neither time nor reps " +
                    "(name the step instead of the exercise)",
            ),
            problems(text, exercise("farmer-carry", "weight, distance"), *library).map { it.describe() },
        )
    }

    @Test
    fun `a lesson needs a body`() {
        listOf("", "\n  \n").forEach { body ->
            val problems = assertIs<ContentResult.Invalid>(parse(lesson(body = body))).problems
            // Line 14 is the header's closing ---.
            assertEquals(listOf<ContentProblem>(ContentProblem.NoBody("lessons/push-up.md", 14)), problems)
            assertEquals("lessons/push-up.md:14: a lesson needs a body: its notes go after the header", problems.single().describe())
        }
    }

    @Test
    fun `a lesson lives in lessons, and its title is unique among lessons`() {
        val misplaced = lesson().copy(path = "recovery/push-up.md")
        assertTrue(problems(misplaced, *library).contains(ContentProblem.WrongPlace("recovery/push-up.md", "lessons/push-up.md")))
        val twin = lesson().copy(path = "lessons/push-up-2.md", text = lesson().text.replace("id: push-up", "id: push-up-2"))
        assertEquals(listOf(ContentProblem.DuplicateName("lessons/push-up.md", "Push-up")), problems(twin, lesson(), *library))
    }

    @Test
    fun `a lesson and an exercise may share an id`() {
        assertEquals(emptyList(), problems(lesson(), *library).filterIsInstance<ContentProblem.DuplicateId>())
    }
}
