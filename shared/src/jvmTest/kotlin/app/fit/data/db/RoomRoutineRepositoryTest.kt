// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.content.ExerciseDraft
import app.fit.content.RoutineDraft
import app.fit.content.RoutineItemDraft
import app.fit.content.Source
import app.fit.core.AppDispatchers
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.Tracks
import app.fit.routines.CreateAndAddResult
import app.fit.routines.NewExercise
import app.fit.routines.RoomRoutineRepository
import app.fit.routines.Routine
import app.fit.routines.RoutineId
import app.fit.routines.RoutineNameResult
import app.fit.settings.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoomRoutineRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val clock =
        object : Clock {
            override fun now() = Instant.fromEpochMilliseconds(5_000)
        }
    private val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    private val repo = RoomRoutineRepository(database.routineDao(), database.exerciseDao(), exercises, FakeSettingsRepository(), clock)

    @AfterTest
    fun close() = database.close()

    private suspend fun setUp() {
        exercises.syncBuiltIn(
            listOf(
                ExerciseDraft("squat", ExerciseFields(name = "Squat", defaults = ExerciseDefaults(sets = 4, reps = 6, rest = 180.seconds))),
                ExerciseDraft("jacks", ExerciseFields(name = "Jumping jacks", tracks = Tracks(time = true))),
                ExerciseDraft("stretch", ExerciseFields(name = "Stretch", tracks = Tracks(time = true))),
            ),
        )
    }

    private suspend fun exerciseId(name: String): ExerciseId =
        exercises.exercises
            .first()
            .single { it.fields.name == name }
            .id

    private suspend fun routine(id: RoutineId): Routine = repo.routine(id).first()!!

    private suspend fun newRoutine(name: String): RoutineId = assertIs<RoutineNameResult.Saved>(repo.create(name)).id

    private fun Routine.names() = items.map { "${it.target.section}:${it.exercise.fields.name}" }

    @Test
    fun `routine names are trimmed, required and unique regardless of case`() =
        runBlocking {
            val id = newRoutine("  Legs ")
            assertEquals("Legs", routine(id).name)
            assertEquals(RoutineNameResult.NameTaken, repo.create("LEGS"))
            assertEquals(RoutineNameResult.NameBlank, repo.create(" "))
            val other = newRoutine("Arms")
            assertEquals(RoutineNameResult.NameTaken, repo.rename(other, "legs"))
            assertEquals(RoutineNameResult.Saved(other), repo.rename(other, "Arms day"))
        }

    @Test
    fun `an added exercise starts from its defaults and goes to the end of Main, not the last section`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            val stretch = repo.addExercise(id, exerciseId("Stretch"))!!
            repo.saveItem(
                stretch,
                routine(id)
                    .items
                    .single()
                    .target
                    .copy(section = "Stretch"),
            )
            repo.addExercise(id, exerciseId("Jumping jacks"), section = "Warm-up")
            repo.addExercise(id, exerciseId("Squat"))
            assertEquals(listOf("Stretch:Stretch", "Warm-up:Jumping jacks", "Main:Squat"), routine(id).names())
            val squat = routine(id).items.last().target
            assertEquals(listOf(4, 6, 180), listOf(squat.sets, squat.reps, squat.rest.inWholeSeconds.toInt()))
        }

    @Test
    fun `a move goes several places at once, inside its section only`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            repo.addExercise(id, exerciseId("Jumping jacks"), section = "Warm-up")
            val a = repo.addExercise(id, exerciseId("Squat"))!!
            repo.addExercise(id, exerciseId("Jumping jacks"))
            val c = repo.addExercise(id, exerciseId("Stretch"))!!
            repo.move(c, -2)
            assertEquals(listOf("Warm-up:Jumping jacks", "Main:Stretch", "Main:Squat", "Main:Jumping jacks"), routine(id).names())
            // Past the section's start it stops at the start; past its end, at the end.
            repo.move(c, -5)
            assertEquals(listOf("Warm-up:Jumping jacks", "Main:Stretch", "Main:Squat", "Main:Jumping jacks"), routine(id).names())
            repo.move(a, 9)
            assertEquals(listOf("Warm-up:Jumping jacks", "Main:Stretch", "Main:Jumping jacks", "Main:Squat"), routine(id).names())
        }

    @Test
    fun `removed items come back in their places`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            repo.addExercise(id, exerciseId("Jumping jacks"))
            val squat = repo.addExercise(id, exerciseId("Squat"))!!
            repo.addExercise(id, exerciseId("Stretch"))
            val before = routine(id).items
            val removed = repo.removeItems(id, listOf(squat))
            assertEquals(listOf("Main:Jumping jacks", "Main:Stretch"), routine(id).names())
            repo.restoreItems(removed)
            assertEquals(before, routine(id).items)
        }

    @Test
    fun `several removed items come back in their places`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            val names = listOf("Jumping jacks", "Squat", "Stretch", "Squat", "Jumping jacks")
            val ids = names.map { repo.addExercise(id, exerciseId(it))!! }
            val before = routine(id).items
            val removed = repo.removeItems(id, listOf(ids[1], ids[3]))
            assertEquals(3, routine(id).items.size)
            repo.restoreItems(removed)
            assertEquals(before, routine(id).items)
        }

    @Test
    fun `a deleted routine whose name was taken meanwhile comes back as a copy`() =
        runBlocking {
            val id = newRoutine("Legs")
            val deleted = repo.delete(id)!!
            newRoutine("legs")
            assertTrue(repo.restore(deleted))
            assertEquals("Legs copy", routine(id).name)
        }

    @Test
    fun `a deleted built-in routine stays deleted after content updates, unless Undo brings it back`() =
        runBlocking {
            setUp()
            val draft = RoutineDraft("legs", "Legs", "", listOf(RoutineItemDraft("squat", line = 1)), listOf(Source("A", null)))
            repo.syncBuiltIn(listOf(draft))
            val deleted =
                repo.delete(
                    repo.routines
                        .first()
                        .single()
                        .id,
                )!!
            repo.syncBuiltIn(listOf(draft.copy(notes = "New")))
            assertEquals(emptyList(), repo.routines.first())
            repo.restore(deleted)
            repo.delete(deleted.id)
            repo.restore(deleted)
            repo.syncBuiltIn(listOf(draft.copy(notes = "Newer")))
            assertEquals(
                "Newer",
                repo.routines
                    .first()
                    .single()
                    .notes,
            )
        }

    @Test
    fun `adding to a routine that's gone writes nothing`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            repo.delete(id)
            assertNull(repo.addExercise(id, exerciseId("Squat")))
            val plank = NewExercise("Side plank", Tracks(time = true), sets = 2, reps = 1, duration = 30.seconds, rest = 30.seconds)
            assertEquals(CreateAndAddResult.RoutineGone, repo.createExerciseAndAdd(id, plank))
            assertEquals(3, exercises.exercises.first().size)
        }

    @Test
    fun `editing one routine's targets leaves its copy alone`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            repo.addExercise(id, exerciseId("Squat"))
            val copy = repo.copy(id)!!
            assertEquals("Legs copy", routine(copy).name)
            assertEquals("Legs copy 2", routine(repo.copy(id)!!).name)
            val item = routine(id).items.single()
            repo.saveItem(item.id, item.target.copy(sets = 5, progressStep = 2.5, progressRpe = 8.0, warmupSets = 2))
            assertEquals(
                4,
                routine(copy)
                    .items
                    .single()
                    .target.sets,
            )
            val saved = routine(id).items.single().target
            assertEquals(
                listOf(5.0, 2.5, 8.0, 2.0),
                listOf(saved.sets.toDouble(), saved.progressStep, saved.progressRpe, saved.warmupSets.toDouble()),
            )
        }

    @Test
    fun `a deleted routine comes back with its items`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            repo.addExercise(id, exerciseId("Squat"))
            val before = routine(id)
            val deleted = repo.delete(id)!!
            assertNull(repo.routine(id).first())
            assertTrue(repo.restore(deleted))
            assertEquals(before.copy(edited = routine(id).edited), routine(id))
        }

    @Test
    fun `Create and add writes the exercise and the item together, or nothing`() =
        runBlocking {
            setUp()
            val id = newRoutine("Legs")
            val plank =
                NewExercise(
                    "Side plank",
                    Tracks(time = true),
                    sets = 2,
                    reps = 1,
                    duration = 30.seconds,
                    rest = 30.seconds,
                    section = "Warm-up",
                )
            val added = assertIs<CreateAndAddResult.Added>(repo.createExerciseAndAdd(id, plank))
            assertEquals(listOf("Warm-up:Side plank"), routine(id).names())
            assertEquals(
                Tracks(time = true),
                exercises
                    .exercise(added.exercise)
                    .first()!!
                    .fields.tracks,
            )
            assertEquals(CreateAndAddResult.NameTaken, repo.createExerciseAndAdd(id, plank.copy(name = "squat")))
            assertEquals(4, exercises.exercises.first().size)
            assertEquals(1, routine(id).items.size)
        }

    @Test
    fun `copying items appends them to another routine`() =
        runBlocking {
            setUp()
            val legs = newRoutine("Legs")
            val arms = newRoutine("Arms")
            val squat = repo.addExercise(legs, exerciseId("Squat"))!!
            repo.addExercise(arms, exerciseId("Stretch"))
            repo.copyItemsTo(listOf(squat), arms)
            assertEquals(listOf("Main:Stretch", "Main:Squat"), routine(arms).names())
            assertEquals(1, routine(legs).items.size)
        }

    @Test
    fun `built-in routines fill missing values from the exercise and skip unknown exercises`() =
        runBlocking {
            setUp()
            val draft =
                RoutineDraft(
                    id = "legs",
                    name = "Legs",
                    notes = "Heavy.",
                    items =
                        listOf(
                            RoutineItemDraft("stretch", line = 1, section = "Stretch"),
                            RoutineItemDraft("squat", line = 2, reps = 8 to 12),
                            RoutineItemDraft("missing", line = 3),
                        ),
                    sources = listOf(Source("A", null)),
                )
            repo.syncBuiltIn(listOf(draft))
            val legs = repo.routines.first().single()
            assertEquals("legs", legs.contentId)
            assertEquals(listOf("Stretch:Stretch", "Main:Squat"), legs.names())
            val squat = legs.items.last().target
            assertEquals(listOf(4, 8, 12), listOf(squat.sets, squat.reps, squat.repsMax))
            assertFalse(legs.edited)
        }

    @Test
    fun `a built-in routine the user changed keeps the user's version`() =
        runBlocking {
            setUp()
            val draft = RoutineDraft("legs", "Legs", "", listOf(RoutineItemDraft("squat", line = 1)), listOf(Source("A", null)))
            repo.syncBuiltIn(listOf(draft))
            val id =
                repo.routines
                    .first()
                    .single()
                    .id
            repo.setNotes(id, "Mine")
            repo.syncBuiltIn(listOf(draft.copy(notes = "New", items = listOf(RoutineItemDraft("stretch", line = 1)))))
            assertEquals("Mine", routine(id).notes)
            assertEquals(listOf("Main:Squat"), routine(id).names())
            assertTrue(routine(id).edited)
        }

    @Test
    fun `an unchanged built-in routine is replaced by the new content`() =
        runBlocking {
            setUp()
            val draft = RoutineDraft("legs", "Legs", "", listOf(RoutineItemDraft("squat", line = 1)), listOf(Source("A", null)))
            repo.syncBuiltIn(listOf(draft))
            repo.syncBuiltIn(listOf(draft.copy(items = listOf(RoutineItemDraft("stretch", line = 1)))))
            assertEquals(
                listOf("Main:Stretch"),
                repo.routines
                    .first()
                    .single()
                    .names(),
            )
        }
}
