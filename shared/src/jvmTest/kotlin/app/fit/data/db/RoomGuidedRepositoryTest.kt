// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.content.ExerciseDraft
import app.fit.content.GuidedStepDraft
import app.fit.content.RecoveryDraft
import app.fit.content.Source
import app.fit.core.AppDispatchers
import app.fit.core.BodyArea
import app.fit.exercises.ExerciseFields
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.guided.GuidedPlan
import app.fit.guided.GuidedStep
import app.fit.guided.RoomGuidedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoomGuidedRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private var now = Instant.parse("2026-11-11T10:00:00Z")
    private val clock =
        object : Clock {
            override fun now() = this@RoomGuidedRepositoryTest.now
        }
    private val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    private val repo = RoomGuidedRepository(database.guidedDao(), exercises, clock)

    @AfterTest
    fun close() = database.close()

    private val stretch =
        ExerciseDraft(
            "wrist-flexor-stretch",
            ExerciseFields(
                name = "Wrist flexor stretch",
                tracks = Tracks(time = true),
                sides = Sides.Each,
                notes = "Arm straight, palm up.",
                images = listOf("images/flexor.webp"),
            ),
        )

    private fun draft(
        title: String = "Wrist reset",
        minutes: Int = 4,
    ) = RecoveryDraft(
        id = "wrist-reset",
        title = title,
        notes = "Stop if it hurts.",
        areas = listOf(BodyArea.Wrists, BodyArea.Forearms),
        minutes = minutes,
        transition = 5.seconds,
        steps =
            listOf(
                GuidedStepDraft(exercise = null, name = "Wrist bends", line = 9, reps = 10, cue = "Up, then down"),
                GuidedStepDraft(exercise = "wrist-flexor-stretch", name = null, line = 10, duration = 30.seconds, rounds = 2),
                GuidedStepDraft(exercise = "no-such-exercise", name = null, line = 11, duration = 20.seconds),
                GuidedStepDraft(exercise = null, name = "Shake out", line = 12, duration = 15.seconds, sides = Sides.Both, cue = "Loose"),
            ),
        sources = listOf(Source("NHS", "https://www.nhs.uk/"), Source("A book", null)),
    )

    private suspend fun plans(): List<GuidedPlan> = repo.plans(GuidedKind.Recovery).first()

    @Test
    fun `a synced session reads its steps through the library, without steps whose exercise is missing`() =
        runBlocking {
            exercises.syncBuiltIn(listOf(stretch))
            repo.syncBuiltIn(listOf(draft()))
            val plan = plans().single()
            assertEquals("wrist-reset", plan.contentId)
            assertEquals(listOf(BodyArea.Wrists, BodyArea.Forearms), plan.areas)
            assertEquals(5.seconds, plan.transition)
            assertEquals(
                listOf(
                    GuidedStep("Wrist bends", reps = 10, cue = "Up, then down"),
                    // The exercise's name, sides (each), notes as the cue and first image.
                    GuidedStep(
                        "Wrist flexor stretch",
                        duration = 30.seconds,
                        sides = Sides.Each,
                        cue = "Arm straight, palm up.",
                        image = "images/flexor.webp",
                        rounds = 2,
                        exerciseId = plan.steps[1].exerciseId,
                    ),
                    GuidedStep("Shake out", duration = 15.seconds, cue = "Loose"),
                ),
                plan.steps,
            )
            assertNotNull(plan.steps[1].exerciseId)
            assertEquals(listOf(Source("NHS", "https://www.nhs.uk/"), Source("A book", null)), plan.sources)
        }

    @Test
    fun `a content update replaces a built-in session and keeps its pin`() =
        runBlocking {
            repo.syncBuiltIn(listOf(draft()))
            val id = plans().single().id
            repo.setPinned(id, true)
            repo.syncBuiltIn(listOf(draft(title = "Wrist reset 2", minutes = 5)))
            val plan = plans().single()
            assertEquals(id, plan.id)
            assertEquals("Wrist reset 2", plan.title)
            assertEquals(now, plan.pinnedAt)
            // The library has no stretch here, so its step is left out.
            assertEquals(listOf("Wrist bends", "Shake out"), plan.steps.map { it.name })
        }

    @Test
    fun `pinning orders the latest pin first`() =
        runBlocking {
            repo.syncBuiltIn(listOf(draft(), draft(title = "Neck").copy(id = "neck")))
            val (neck, wrist) = plans()
            repo.setPinned(wrist.id, true)
            now += 1.minutes
            repo.setPinned(neck.id, true)
            assertEquals(listOf("Neck", "Wrist reset"), repo.pinned.first().map { it.title })
            repo.setPinned(neck.id, false)
            assertEquals(listOf("Wrist reset"), repo.pinned.first().map { it.title })
        }

    @Test
    fun `a run written twice is kept once, and logs are read by the day they started`() =
        runBlocking {
            repo.syncBuiltIn(listOf(draft()))
            val plan = plans().single()
            val start = Instant.parse("2026-11-09T07:00:00Z")
            val log =
                GuidedLog(
                    plan.id,
                    GuidedKind.Recovery,
                    "Wrist reset",
                    LocalDate(2026, 11, 9),
                    start,
                    start + 7.minutes,
                    GuidedOutcome.Completed,
                    9,
                )
            repo.log(log)
            repo.log(log)
            repo.log(log.copy(day = LocalDate(2026, 11, 12), startedAt = start + (72 * 60).minutes, outcome = GuidedOutcome.Stopped))
            assertEquals(listOf(log), repo.logs(GuidedKind.Recovery, LocalDate(2026, 11, 9), LocalDate(2026, 11, 11)).first())
            assertEquals(2, repo.logs(GuidedKind.Recovery, LocalDate(2026, 11, 1), LocalDate(2026, 11, 30)).first().size)
            assertNull(repo.logs(GuidedKind.Recovery, LocalDate(2026, 10, 1), LocalDate(2026, 10, 31)).first().firstOrNull())
        }

    @Test
    fun `logs are read by kind, so a practice run is never a recovery one`() =
        runBlocking {
            val start = Instant.parse("2026-11-09T07:00:00Z")
            val recovery =
                GuidedLog(null, GuidedKind.Recovery, "Wrist reset", LocalDate(2026, 11, 9), start, start, GuidedOutcome.Completed, 3)
            val practice =
                recovery.copy(
                    kind = GuidedKind.Practice,
                    title = "Push-up",
                    startedAt = start + 1.minutes,
                    endedAt =
                        start + 1.minutes,
                )
            repo.log(recovery)
            repo.log(practice)
            val day = LocalDate(2026, 11, 9)
            assertEquals(listOf(recovery), repo.logs(GuidedKind.Recovery, day, day).first())
            assertEquals(listOf(practice), repo.logs(GuidedKind.Practice, day, day).first())
            assertEquals(listOf(practice), repo.logs(GuidedKind.Practice).first())
        }
}
