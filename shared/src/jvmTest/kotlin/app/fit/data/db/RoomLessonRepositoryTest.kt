// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.content.ExerciseDraft
import app.fit.content.GuidedStepDraft
import app.fit.content.LessonDraft
import app.fit.content.PracticeDraft
import app.fit.content.RecoveryDraft
import app.fit.content.Source
import app.fit.core.AppDispatchers
import app.fit.exercises.ExerciseFields
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.Tracks
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedStep
import app.fit.guided.RoomGuidedRepository
import app.fit.lessons.Lesson
import app.fit.lessons.RoomLessonRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoomLessonRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = this@RoomLessonRepositoryTest.now
        }
    private val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    private val guided = RoomGuidedRepository(database.guidedDao(), exercises, clock)
    private val repo = RoomLessonRepository(database.lessonDao(), exercises, clock)

    @AfterTest
    fun close() = database.close()

    private fun exercise(
        id: String,
        name: String,
        tracks: Tracks,
    ) = ExerciseDraft(id, ExerciseFields(name = name, tracks = tracks, notes = "$name notes."))

    private val library =
        listOf(
            exercise("push-up", "Push-up", Tracks(reps = true)),
            exercise("wall-push-up", "Wall push-up", Tracks(time = true)),
            exercise("plank", "Plank", Tracks(time = true)),
        )

    private fun draft(
        title: String = "Push-up",
        practice: PracticeDraft? =
            PracticeDraft(
                transition = 20.seconds,
                steps =
                    listOf(
                        GuidedStepDraft(exercise = "plank", name = null, line = 9, duration = 20.seconds, rounds = 2),
                        GuidedStepDraft(exercise = null, name = "Incline push-up", line = 10, reps = 6, cue = "Bench"),
                    ),
            ),
    ) = LessonDraft(
        id = "push-up",
        title = title,
        body = "## Setup\n\n- Hands under your shoulders.",
        exercises = listOf("push-up" to 6, "wall-push-up" to 6, "no-such-exercise" to 6),
        practice = practice,
        sources = listOf(Source("E3 Rehab", "https://e3rehab.com/"), Source("A book", null)),
    )

    private suspend fun lesson(): Lesson = repo.lessons.first().single()

    private suspend fun id(contentId: String) =
        exercises.exercises
            .first()
            .single { it.contentId == contentId }
            .id

    @Test
    fun `a synced lesson keeps its body, sources and the exercises it teaches, without missing ones`() =
        runBlocking {
            exercises.syncBuiltIn(library)
            repo.syncBuiltIn(listOf(draft()))
            val lesson = lesson()
            assertEquals("push-up", lesson.contentId)
            assertEquals("Push-up", lesson.title)
            assertEquals("## Setup\n\n- Hands under your shoulders.", lesson.body)
            assertEquals(listOf("Push-up", "Wall push-up"), lesson.exercises.map { it.name })
            assertEquals(listOf(Source("E3 Rehab", "https://e3rehab.com/"), Source("A book", null)), lesson.sources)
        }

    @Test
    fun `its drill is a practice plan, apart from the recovery sessions, with steps read through the library`() =
        runBlocking {
            exercises.syncBuiltIn(library)
            repo.syncBuiltIn(listOf(draft()))
            val plan = assertNotNull(guided.plan(assertNotNull(lesson().practice)).first())
            assertEquals(GuidedKind.Practice, plan.kind)
            assertEquals("Push-up", plan.title)
            assertEquals(20.seconds, plan.transition)
            assertEquals(
                listOf(
                    GuidedStep("Plank", duration = 20.seconds, cue = "Plank notes.", rounds = 2, exerciseId = id("plank")),
                    GuidedStep("Incline push-up", reps = 6, cue = "Bench"),
                ),
                plan.steps,
            )
            assertEquals(emptyList(), guided.plans(GuidedKind.Recovery).first())
            assertEquals(emptyList(), guided.pinned.first())
        }

    @Test
    fun `an exercise page's lessons are the ones that teach it, not the ones that only drill it`() =
        runBlocking {
            exercises.syncBuiltIn(library)
            repo.syncBuiltIn(listOf(draft()))
            assertEquals(listOf("Push-up"), repo.lessonsFor(id("push-up")).first().map { it.title })
            assertEquals(listOf("Push-up"), repo.lessonsFor(id("wall-push-up")).first().map { it.title })
            // Plank is in the drill only.
            assertEquals(emptyList(), repo.lessonsFor(id("plank")).first())
        }

    @Test
    fun `a content update replaces a lesson and keeps its drill's plan, so its runs still count`() =
        runBlocking {
            exercises.syncBuiltIn(library)
            repo.syncBuiltIn(listOf(draft()))
            val before = lesson()
            val plan = assertNotNull(before.practice)
            val start = Instant.parse("2026-11-10T07:00:00Z")
            guided.log(
                GuidedLog(
                    plan,
                    GuidedKind.Practice,
                    "Push-up",
                    LocalDate(2026, 11, 10),
                    start,
                    start + 5.minutes,
                    GuidedOutcome.Completed,
                    3,
                ),
            )

            repo.syncBuiltIn(listOf(draft(title = "Push-ups")))
            val after = lesson()
            assertEquals(before.id, after.id)
            assertEquals(plan, after.practice)
            assertEquals("Push-ups", guided.plan(plan).first()?.title)
            assertEquals(
                2,
                guided
                    .plan(plan)
                    .first()
                    ?.steps
                    ?.size,
            )
            assertEquals(listOf(plan), guided.logs(GuidedKind.Practice).first().map { it.planId })

            // A file that drops its drill leaves the plan with no steps; adding it back reuses it.
            repo.syncBuiltIn(listOf(draft(practice = null)))
            assertEquals(plan, lesson().practice)
            assertEquals(emptyList(), guided.plan(plan).first()?.steps)
            repo.syncBuiltIn(listOf(draft()))
            assertEquals(plan, lesson().practice)
            assertEquals(
                2,
                guided
                    .plan(plan)
                    .first()
                    ?.steps
                    ?.size,
            )
        }

    @Test
    fun `a lesson and a recovery session may share an id`() =
        runBlocking {
            exercises.syncBuiltIn(library)
            guided.syncBuiltIn(
                listOf(
                    RecoveryDraft(
                        id = "push-up",
                        title = "Push-up reset",
                        notes = "",
                        areas = emptyList(),
                        minutes = 1,
                        transition = 5.seconds,
                        steps = listOf(GuidedStepDraft(exercise = null, name = "Shake out", line = 9, duration = 15.seconds)),
                        sources = listOf(Source("Me", null)),
                    ),
                ),
            )
            repo.syncBuiltIn(listOf(draft()))
            assertEquals(listOf("Push-up reset"), guided.plans(GuidedKind.Recovery).first().map { it.title })
            assertEquals("Push-up", lesson().title)
        }
}
