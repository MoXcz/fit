// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.data.db.Files10
import app.fit.data.db.Storage
import app.fit.exercises.ExerciseSaveResult
import app.fit.guided.FakeArchives
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.lessons.Practice
import app.fit.routines.RoutineNameResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Import over the real database: preview, all or nothing, update by id, and what survives a sync.
class RoomContentImporterTest {
    private val s = Storage()

    @AfterTest
    fun close() = s.close()

    private val plank = Files10.exercise("plank", "Plank", "tracks: [time]\n")
    private val legs = Files10.routine("legs", "Legs", "plank")
    private val split = Files10.program("split", "Split", "legs")
    private val reset = Files10.recovery("wrist-reset", "Wrist reset")
    private val lesson = Files10.lesson("plank", "Plank basics", "plank")

    private fun zip(vararg files: Pair<String, String>) = FakeArchives.zip(*files)

    private suspend fun ready(
        bytes: ByteArray,
        name: String = "pack.zip",
    ) = assertIs<ImportPreview.Ready>(s.importer.preview(name, bytes))

    private suspend fun import(vararg files: Pair<String, String>) =
        assertEquals(ImportResult.Imported(files.count { it.first.endsWith(".md") }), s.importer.import(ready(zip(*files))))

    private suspend fun exercise(name: String) =
        s.exercises.exercises
            .first()
            .single { it.fields.name == name }

    @Test
    fun `a pack previews what is new by kind and writes nothing until it is imported`(): Unit =
        runBlocking {
            val preview = ready(zip(plank, legs, split, reset, lesson, "images/top.png" to "png"))
            assertEquals(
                listOf(
                    ImportItem(ContentKind.Exercise, "plank", "Plank", null),
                    ImportItem(ContentKind.Routine, "legs", "Legs", null),
                    ImportItem(ContentKind.Program, "split", "Split", null),
                    ImportItem(ContentKind.Recovery, "wrist-reset", "Wrist reset", null),
                    ImportItem(ContentKind.Lesson, "plank", "Plank basics", null),
                ),
                preview.items,
            )
            assertEquals(listOf("images/top.png"), preview.newImages)
            assertFalse(preview.replaces)
            assertTrue(s.dump().values.all { it.isEmpty() })
        }

    @Test
    fun `a pack of new items imports whole, each item the user's own`(): Unit =
        runBlocking {
            import(plank, legs, split, reset, lesson, "images/top.png" to "png")
            assertTrue(exercise("Plank").edited)
            val routine =
                s.routines.routines
                    .first()
                    .single()
            assertEquals(listOf("Plank"), routine.items.map { it.exercise.fields.name })
            assertEquals(
                2,
                routine.items
                    .single()
                    .target.sets,
            )
            val program =
                s.programs.programs
                    .first()
                    .single()
            assertEquals(listOf(routine.id), program.days.values.toList())
            assertFalse(program.active)
            assertEquals(
                "Wrist reset",
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
                    .title,
            )
            val imported =
                s.lessons.lessons
                    .first()
                    .single()
            assertEquals(listOf("Plank"), imported.exercises.map { it.name })
            assertNotNull(imported.practice)
            assertContentEquals("png".encodeToByteArray(), s.images.load("images/top.png"))
        }

    @Test
    fun `a routine in a pack may name an exercise already in the library, and a wrong exercise id is caught`(): Unit =
        runBlocking {
            import(plank)
            assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", zip(legs)))
            val bad = s.importer.preview("pack.zip", zip(Files10.routine("arms", "Arms", "curll")))
            assertEquals(
                ImportPreview.Invalid("pack.zip", listOf("routines/arms.md:7: there is no exercise with the id curll")),
                bad,
            )
        }

    @Test
    fun `an import with one bad file imports nothing and lists every problem with its file and line`(): Unit =
        runBlocking {
            val preview =
                s.importer.preview(
                    "pack.zip",
                    zip(plank, legs, "exercises/bad.md" to "---\nformat: 1\nkind: exercise\nid: bad\nnam: Bad\n---\n", reset),
                )
            val problems = assertIs<ImportPreview.Invalid>(preview).problems
            assertEquals(listOf("exercises/bad.md:2: name is required", "exercises/bad.md:5: unknown key nam"), problems.sorted())
            assertTrue(s.dump().values.all { it.isEmpty() })
        }

    @Test
    fun `a step is checked against what a library exercise tracks`(): Unit =
        runBlocking {
            import(plank)
            val preview =
                s.importer.preview("pack.zip", zip(Files10.recovery("hold", "Hold", "{ exercise: plank, reps: 20 }")))
            val problems = assertIs<ImportPreview.Invalid>(preview).problems
            assertEquals(listOf("recovery/hold.md:9: the step has reps, but plank doesn't track reps (give a duration)"), problems)
        }

    @Test
    fun `an import replaces an item by id, also one changed in the app, and the preview says which`(): Unit =
        runBlocking {
            import(plank, legs)
            val before = exercise("Plank")
            assertEquals(ExerciseSaveResult.Saved, s.exercises.update(before.id, before.fields.copy(name = "My plank", notes = "Mine")))
            val routine =
                s.routines.routines
                    .first()
                    .single()
            s.routines.setNotes(routine.id, "My notes")

            val preview = ready(zip(Files10.exercise("plank", "Front plank"), Files10.routine("legs", "Leg day", "plank")))
            assertEquals(listOf("My plank", "Legs"), preview.items.map { it.replaces })
            assertTrue(preview.replaces)
            s.importer.import(preview)

            val after = exercise("Front plank")
            assertEquals(before.id, after.id)
            assertEquals("Notes for Front plank.", after.fields.notes)
            val replaced =
                s.routines.routines
                    .first()
                    .single()
            assertEquals(routine.id, replaced.id)
            assertEquals("Leg day", replaced.name)
            assertEquals("", replaced.notes)
        }

    @Test
    fun `an item takes over one made in the app with its name, and a name another content item has is a problem`(): Unit =
        runBlocking {
            val made = assertIs<RoutineNameResult.Saved>(s.routines.create("Legs")).id
            import(plank)
            val preview = ready(zip(legs))
            assertEquals("Legs", preview.items.single().replaces)
            s.importer.import(preview)
            val routine =
                s.routines.routines
                    .first()
                    .single()
            assertEquals(made, routine.id)
            assertEquals("legs", routine.contentId)

            val clash = s.importer.preview("pack.zip", zip(Files10.routine("lower", "legs", "plank")))
            assertEquals(
                ImportPreview.Invalid("pack.zip", listOf("routines/lower.md:1: the app already has another routine named legs")),
                clash,
            )
        }

    @Test
    fun `an imported item survives a built-in sync, also one that took over a built-in id`(): Unit =
        runBlocking {
            s.builtIn.list =
                listOf(plank, legs, split, reset, lesson).map { ContentFile(it.first, it.second) }
            s.sync.syncIfChanged()
            import(
                Files10.exercise("plank", "My plank"),
                Files10.routine("legs", "My legs", "plank"),
                Files10.program("split", "My split", "legs"),
                Files10.recovery("wrist-reset", "My reset"),
                Files10.lesson("plank", "My lesson", "plank"),
                Files10.recovery("neck", "Neck"),
            )
            // An app update changes every bundled file.
            s.builtIn.list = s.builtIn.list.map { it.copy(text = it.text + "\nUpdated.\n") }
            assertTrue(s.sync.syncIfChanged())

            assertEquals(
                "My plank",
                s.exercises.exercises
                    .first()
                    .single()
                    .fields.name,
            )
            assertEquals(
                "My legs",
                s.routines.routines
                    .first()
                    .single()
                    .name,
            )
            assertEquals(
                "My split",
                s.programs.programs
                    .first()
                    .single()
                    .name,
            )
            assertEquals(
                listOf("My reset", "Neck"),
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .map { it.title },
            )
            assertEquals(
                "My lesson",
                s.lessons.lessons
                    .first()
                    .single()
                    .title,
            )
        }

    @Test
    fun `a session keeps its pin and a lesson its practice count when an import replaces them`(): Unit =
        runBlocking {
            import(plank, reset, lesson)
            val plan =
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
            s.guided.setPinned(plan.id, true)
            val drill =
                s.lessons.lessons
                    .first()
                    .single()
                    .practice!!
            val start = s.clock.now()
            s.guided.log(
                GuidedLog(drill, GuidedKind.Practice, "Plank basics", s.today, start, start + 1.minutes, GuidedOutcome.Completed, 1),
            )

            import(Files10.recovery("wrist-reset", "Wrist reset 2"), Files10.lesson("plank", "Plank again", "plank"))

            val replaced =
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
            assertEquals(plan.id, replaced.id)
            assertTrue(replaced.pinned)
            assertEquals(
                drill,
                s.lessons.lessons
                    .first()
                    .single()
                    .practice,
            )
            assertEquals(1, Practice.counts(s.guided.logs(GuidedKind.Practice).first())[drill])
        }

    @Test
    fun `a pack's image replaces a bundled one with its name, the preview says so, and the same bytes are not imported again`(): Unit =
        runBlocking {
            s.builtIn.images = mapOf("images/top.png" to "bundled".encodeToByteArray())
            val same = ready(zip(plank, "images/top.png" to "bundled"))
            assertEquals(emptyList(), same.newImages + same.replacedImages)

            val other = ready(zip(plank, "images/top.png" to "mine"))
            assertEquals(listOf("images/top.png"), other.replacedImages)
            assertTrue(other.replaces)
            s.importer.import(other)
            assertContentEquals("mine".encodeToByteArray(), s.images.load("images/top.png"))

            // An earlier import's image is replaced the same way.
            assertEquals(listOf("images/top.png"), ready(zip("images/top.png" to "newer")).replacedImages)
        }

    @Test
    fun `an image a file names must be in the pack or already in the app`(): Unit =
        runBlocking {
            val withImage = Files10.exercise("plank", "Plank", "images: [images/plank.png]\n")
            val missing = assertIs<ImportPreview.Invalid>(s.importer.preview("pack.zip", zip(withImage)))
            assertEquals(listOf("exercises/plank.md:1: images/plank.png doesn't exist"), missing.problems)
            assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", zip(withImage, "images/plank.png" to "png")))
            s.builtIn.images = mapOf("images/plank.png" to "bundled".encodeToByteArray())
            assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", zip(withImage)))
        }

    @Test
    fun `a single md file is imported by the kind in its header, whatever it is called`(): Unit =
        runBlocking {
            import(plank)
            val preview = ready(reset.second.encodeToByteArray(), name = "my session (2).md")
            assertEquals(listOf(ImportItem(ContentKind.Recovery, "wrist-reset", "Wrist reset", null)), preview.items)
            assertEquals(ImportResult.Imported(1), s.importer.import(preview))
            assertEquals(
                "Wrist reset",
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
                    .title,
            )

            val bad = s.importer.preview("notes.md", "just some notes".encodeToByteArray())
            assertEquals(
                ImportPreview.Invalid("notes.md", listOf("notes.md:1: the file must start with a YAML header between --- lines")),
                bad,
            )
        }

    @Test
    fun `an import that no longer fits when it is written changes nothing`(): Unit =
        runBlocking {
            val preview = ready(zip(plank, legs))
            // Meanwhile another import brings a routine with the same name under another id.
            import(Files10.exercise("squat", "Squat"), Files10.routine("lower", "Legs", "squat"))
            val before = s.dump()

            assertEquals(ImportResult.Changed, s.importer.import(preview))
            assertEquals(before, s.dump())
            assertNull(
                s.exercises.exercises
                    .first()
                    .firstOrNull { it.fields.name == "Plank" },
            )
        }

    @Test
    fun `an archived exercise comes back when it is imported`(): Unit =
        runBlocking {
            import(plank)
            s.exercises.setArchived(exercise("Plank").id, true)
            import(plank)
            assertFalse(exercise("Plank").archived)
        }

    @Test
    fun `a deleted built-in session or lesson stays deleted after a sync, and Undo or an import brings it back`(): Unit =
        runBlocking {
            s.builtIn.list = listOf(plank, reset, lesson).map { ContentFile(it.first, it.second) }
            s.sync.syncIfChanged()
            val plan =
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
            s.guided.setPinned(plan.id, true)
            val start = s.clock.now()
            s.guided.log(GuidedLog(plan.id, GuidedKind.Recovery, plan.title, s.today, start, start + 1.minutes, GuidedOutcome.Completed, 1))
            val taught =
                s.lessons.lessons
                    .first()
                    .single()
            val drill = taught.practice!!
            s.guided.log(
                GuidedLog(
                    drill,
                    GuidedKind.Practice,
                    taught.title,
                    s.today,
                    start + 1.seconds,
                    start + 1.minutes,
                    GuidedOutcome.Stopped,
                    1,
                ),
            )

            val deletedPlan = assertNotNull(s.guided.delete(plan.id))
            val deletedLesson = assertNotNull(s.lessons.delete(taught.id))
            assertEquals(drill, deletedLesson.practice)
            // The runs stay in the log, without their plan.
            assertNull(
                s.guided
                    .logs(GuidedKind.Recovery)
                    .first()
                    .single()
                    .planId,
            )
            assertNull(s.guided.plan(drill).first())

            s.builtIn.list = s.builtIn.list.map { it.copy(text = it.text + "\nUpdated.\n") }
            assertTrue(s.sync.syncIfChanged())
            assertEquals(emptyList(), s.guided.plans(GuidedKind.Recovery).first())
            assertEquals(emptyList(), s.lessons.lessons.first())

            assertTrue(s.guided.restore(deletedPlan))
            assertTrue(s.lessons.restore(deletedLesson))
            val back =
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
            assertEquals(plan.id, back.id)
            assertTrue(back.pinned)
            assertEquals(plan.steps, back.steps)
            assertEquals(
                plan.id,
                s.guided
                    .logs(GuidedKind.Recovery)
                    .first()
                    .single()
                    .planId,
            )
            val lessonBack =
                s.lessons.lessons
                    .first()
                    .single()
            assertEquals(taught, lessonBack)
            assertEquals(1, Practice.counts(s.guided.logs(GuidedKind.Practice).first())[drill])
            // Restored, it follows its file again.
            assertFalse(s.guided.restore(deletedPlan))
            s.builtIn.list = s.builtIn.list.map { it.copy(text = it.text.replace("Wrist reset", "Wrist reset 2")) }
            s.sync.syncIfChanged()
            assertEquals(
                "Wrist reset 2",
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
                    .title,
            )

            // Deleted again and imported: it's back as the user's own.
            s.guided.delete(back.id)
            import(Files10.recovery("wrist-reset", "Mine"))
            assertEquals(
                "Mine",
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
                    .title,
            )
        }

    @Test
    fun `Undo of a delete gives way to an import that took the id meanwhile`(): Unit =
        runBlocking {
            import(plank, reset, lesson)
            val deletedPlan =
                assertNotNull(
                    s.guided.delete(
                        s.guided
                            .plans(GuidedKind.Recovery)
                            .first()
                            .single()
                            .id,
                    ),
                )
            val deletedLesson =
                assertNotNull(
                    s.lessons.delete(
                        s.lessons.lessons
                            .first()
                            .single()
                            .id,
                    ),
                )
            import(reset, lesson)
            assertFalse(s.guided.restore(deletedPlan))
            assertFalse(s.lessons.restore(deletedLesson))
            assertEquals(
                1,
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .size,
            )
            assertEquals(
                1,
                s.lessons.lessons
                    .first()
                    .size,
            )
        }

    @Test
    fun `an image the app can't show is a problem at its file, and nothing is imported`(): Unit =
        runBlocking {
            val preview = s.importer.preview("pack.zip", zip(plank, "images/ok.png" to "png", "images/cut.png" to "broken"))
            assertEquals(ImportPreview.Invalid("pack.zip", listOf("images/cut.png: isn't an image the app can show")), preview)
            assertTrue(s.dump().values.all { it.isEmpty() })
        }

    @Test
    fun `an exercise that would stop tracking what a stored step gives it is a problem, unless the pack updates that step too`(): Unit =
        runBlocking {
            val hold = Files10.recovery("hold", "Plank hold", "{ exercise: plank, duration: 60 }")
            val drill =
                "lessons/planking.md" to
                    "---\nformat: 1\nkind: lesson\nid: planking\ntitle: Planking\npractice:\n  steps:\n" +
                    "    - { exercise: plank, duration: 20 }\nsources: [{ title: Me }]\n---\n## Setup\n"
            import(plank, hold, drill)
            val counted = Files10.exercise("plank", "Plank", "tracks: [reps]\n")

            val broken = assertIs<ImportPreview.Invalid>(s.importer.preview("pack.zip", zip(counted)))
            assertEquals(
                listOf(
                    "exercises/plank.md:1: plank would stop tracking time, which a step of the lesson Planking uses " +
                        "(import that one with the change too)",
                    "exercises/plank.md:1: plank would stop tracking time, which a step of the recovery session Plank hold uses " +
                        "(import that one with the change too)",
                ),
                broken.problems.sorted(),
            )
            // Updating only one of the two still leaves the other broken.
            val half = zip(counted, Files10.recovery("hold", "Plank hold", "{ exercise: plank, reps: 20 }"))
            assertEquals(1, assertIs<ImportPreview.Invalid>(s.importer.preview("pack.zip", half)).problems.size)
            val whole =
                zip(
                    counted,
                    Files10.recovery("hold", "Plank hold", "{ exercise: plank, reps: 20 }"),
                    drill.first to drill.second.replace("duration: 20", "reps: 5"),
                )
            assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", whole))
            // A change that keeps time tracked breaks nothing.
            assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", zip(Files10.exercise("plank", "Plank", "tracks: [time, reps]\n"))))
        }

    @Test
    fun `a pack may rename an item and give its old name to another, and swap two names`(): Unit =
        runBlocking {
            import(Files10.exercise("curl", "Curl"), Files10.exercise("row", "Row"))
            import(Files10.routine("day-a", "Day A", "curl"), Files10.routine("day-b", "Day B", "row"))
            import(Files10.program("one", "One", "day-a"), Files10.program("two", "Two", "day-b"))
            val ids =
                s.exercises.exercises
                    .first()
                    .associate { it.contentId.orEmpty() to it.id }

            import(Files10.exercise("curl", "Biceps curl"), Files10.exercise("curl2", "Curl"))
            assertEquals(
                mapOf("curl" to "Biceps curl", "curl2" to "Curl", "row" to "Row"),
                s.exercises.exercises
                    .first()
                    .associate { it.contentId.orEmpty() to it.fields.name },
            )
            import(Files10.exercise("curl", "Row"), Files10.exercise("row", "Biceps curl"))
            val swapped = s.exercises.exercises.first()
            assertEquals(
                mapOf("curl" to "Row", "curl2" to "Curl", "row" to "Biceps curl"),
                swapped.associate {
                    it.contentId.orEmpty() to
                        it.fields.name
                },
            )
            assertEquals(ids["curl"], swapped.single { it.contentId == "curl" }.id)
            assertEquals(ids["row"], swapped.single { it.contentId == "row" }.id)

            import(Files10.routine("day-a", "Day B", "curl"), Files10.routine("day-b", "Day A", "row"))
            assertEquals(
                mapOf("day-a" to "Day B", "day-b" to "Day A"),
                s.routines.routines.first().associate {
                    it.contentId.orEmpty() to
                        it.name
                },
            )
            import(Files10.program("one", "Two", "day-a"), Files10.program("two", "One", "day-b"))
            assertEquals(
                mapOf("one" to "Two", "two" to "One"),
                s.programs.programs
                    .first()
                    .associate { it.contentId.orEmpty() to it.name },
            )

            // A name that a row outside the pack keeps is still a clash.
            val clash = s.importer.preview("pack.zip", zip(Files10.exercise("curl", "Curl")))
            assertEquals(
                ImportPreview.Invalid("pack.zip", listOf("exercises/curl.md:1: the app already has another exercise named Curl")),
                clash,
            )
        }

    @Test
    fun `an import whose references went missing after the preview changes nothing`(): Unit =
        runBlocking {
            import(plank, legs)
            val preview = ready(zip(split))
            // The routine the program's Monday names is deleted meanwhile.
            s.routines.delete(
                s.routines.routines
                    .first()
                    .single()
                    .id,
            )
            val before = s.dump()
            assertEquals(ImportResult.Changed, s.importer.import(preview))
            assertEquals(before, s.dump())
        }

    @Test
    fun `an import that would now replace something it showed as new changes nothing`(): Unit =
        runBlocking {
            import(plank)
            val preview = ready(zip(legs))
            assertNull(preview.items.single().replaces)
            // Meanwhile a routine with that name is made in the app: importing would take it over unasked.
            s.routines.create("Legs")
            val before = s.dump()
            assertEquals(ImportResult.Changed, s.importer.import(preview))
            assertEquals(before, s.dump())
        }
}
