// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.content.ExerciseDraft
import app.fit.core.AppDispatchers
import app.fit.core.BodyArea
import app.fit.exercises.Equipment
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseRef
import app.fit.exercises.ExerciseSaveResult
import app.fit.exercises.RefKind
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.Tracks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoomExerciseRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val repo = RoomExerciseRepository(database.exerciseDao(), FixedClock(Instant.fromEpochMilliseconds(5_000)))

    private val plank =
        ExerciseDraft(
            "plank",
            ExerciseFields(
                name = "Plank",
                category = "Abs",
                tracks = Tracks(time = true),
                areas = listOf(BodyArea.LowerBack, BodyArea.FullBody),
                defaults = ExerciseDefaults(sets = 3, rest = 60.seconds, restMax = 90.seconds, duration = 60.seconds),
                images = listOf("images/plank.webp"),
                refs =
                    listOf(
                        ExerciseRef(RefKind.Video, "Form", "https://example.org/v"),
                        ExerciseRef(RefKind.Link, "", "https://example.org/l"),
                    ),
                notes = "Brace.",
                equipment = Equipment.Bodyweight,
            ),
        )

    private suspend fun all(): List<Exercise> = repo.exercises.first()

    private suspend fun byContentId(id: String): Exercise = all().single { it.contentId == id }

    @AfterTest
    fun close() = database.close()

    @Test
    fun `built-in exercises are added with every field`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val stored = byContentId("plank")
            assertEquals(plank.fields, stored.fields)
            assertEquals(false, stored.edited)
        }

    @Test
    fun `a content update overwrites an exercise the user never edited but keeps it archived`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val id = byContentId("plank").id
            repo.setArchived(id, true)
            val updated =
                plank.copy(
                    fields =
                        plank.fields.copy(
                            name = "Front plank",
                            refs = emptyList(),
                            areas = listOf(BodyArea.Hips),
                            equipment = Equipment.Other,
                        ),
                )
            repo.syncBuiltIn(listOf(updated))
            val stored = byContentId("plank")
            assertEquals(id, stored.id)
            assertEquals(updated.fields, stored.fields)
            assertTrue(stored.archived)
        }

    @Test
    fun `an exercise edited in the app is left alone by content updates`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val id = byContentId("plank").id
            val mine = plank.fields.copy(notes = "My cue", defaults = plank.fields.defaults.copy(sets = 5))
            assertEquals(ExerciseSaveResult.Saved, repo.update(id, mine))
            repo.syncBuiltIn(listOf(plank.copy(fields = plank.fields.copy(notes = "New cue", equipment = null))))
            val stored = byContentId("plank")
            assertEquals(mine, stored.fields)
            assertTrue(stored.edited)
        }

    @Test
    fun `names are unique regardless of case`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank, ExerciseDraft("dip", ExerciseFields(name = "Dip"))))
            val dip = byContentId("dip").id
            assertEquals(ExerciseSaveResult.NameTaken, repo.update(dip, ExerciseFields(name = "  PLANK ")))
            assertEquals(ExerciseSaveResult.Saved, repo.update(dip, ExerciseFields(name = "  Ring dip ")))
            assertEquals("Ring dip", byContentId("dip").fields.name)
        }

    @Test
    fun `a blank name or nothing tracked is refused`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val id = byContentId("plank").id
            assertEquals(ExerciseSaveResult.NameBlank, repo.update(id, plank.fields.copy(name = " ")))
            assertEquals(ExerciseSaveResult.NothingTracked, repo.update(id, plank.fields.copy(tracks = Tracks())))
            assertEquals(plank.fields, byContentId("plank").fields)
        }

    @Test
    fun `archiving is undone by unarchiving`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val id = byContentId("plank").id
            repo.setArchived(id, true)
            assertTrue(repo.exercise(id).first()!!.archived)
            repo.setArchived(id, false)
            assertEquals(false, repo.exercise(id).first()!!.archived)
        }

    @Test
    fun `a built-in exercise renamed onto an existing name is skipped, not duplicated`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank, ExerciseDraft("dip", ExerciseFields(name = "Dip"))))
            repo.syncBuiltIn(listOf(plank, ExerciseDraft("dip", ExerciseFields(name = "plank"))))
            assertEquals(listOf("Dip", "Plank"), all().map { it.fields.name })
        }

    @Test
    fun `a new built-in exercise whose name the user took for another is skipped until the name is free`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank, ExerciseDraft("dip", ExerciseFields(name = "Dip"))))
            val dip = byContentId("dip").id
            repo.update(dip, ExerciseFields(name = "Dips"))
            val dips = ExerciseDraft("dips", ExerciseFields(name = "Dips", category = "Triceps"))
            repo.syncBuiltIn(listOf(plank, dips))
            assertEquals(listOf("Dips", "Plank"), all().map { it.fields.name })
            assertEquals(null, all().firstOrNull { it.contentId == "dips" })
            repo.update(dip, ExerciseFields(name = "Ring dip"))
            repo.syncBuiltIn(listOf(plank, dips))
            assertEquals("Triceps", byContentId("dips").fields.category)
        }

    @Test
    fun `saving an exercise that doesn't exist says so`() =
        runBlocking {
            assertEquals(ExerciseSaveResult.NotFound, repo.update(app.fit.exercises.ExerciseId(42), ExerciseFields(name = "Ghost")))
        }

    @Test
    fun `syncing twice changes nothing`() =
        runBlocking {
            repo.syncBuiltIn(listOf(plank))
            val first = all()
            repo.syncBuiltIn(listOf(plank))
            assertEquals(first, all())
        }
}

private class FixedClock(
    private val now: Instant,
) : Clock {
    override fun now(): Instant = now
}
