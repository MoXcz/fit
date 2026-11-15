// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.bodyweight.BodyWeight
import app.fit.bodyweight.RoomBodyWeightRepository
import app.fit.content.ExerciseDraft
import app.fit.content.ProgramDraft
import app.fit.content.RoutineDraft
import app.fit.content.RoutineItemDraft
import app.fit.content.Source
import app.fit.core.AppDispatchers
import app.fit.core.WeightUnit
import app.fit.exercises.ExerciseFields
import app.fit.exercises.RoomExerciseRepository
import app.fit.programs.Program
import app.fit.programs.ProgramId
import app.fit.programs.ProgramNameResult
import app.fit.programs.RoomProgramRepository
import app.fit.routines.RoomRoutineRepository
import app.fit.routines.RoutineId
import app.fit.routines.RoutineNameResult
import app.fit.settings.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class RoomProgramRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val clock =
        object : Clock {
            override fun now() = Instant.parse("2026-11-11T10:00:00Z")
        }
    private val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    private val routines = RoomRoutineRepository(database.routineDao(), database.exerciseDao(), exercises, FakeSettingsRepository(), clock)
    private val repo = RoomProgramRepository(database.programDao(), clock, TimeZone.UTC)

    @AfterTest
    fun close() = database.close()

    private suspend fun routine(name: String): RoutineId = assertIs<RoutineNameResult.Saved>(routines.create(name)).id

    private suspend fun program(name: String): ProgramId = assertIs<ProgramNameResult.Saved>(repo.create(name)).id

    private suspend fun get(id: ProgramId): Program = repo.program(id).first()!!

    @Test
    fun `a new program has no days, no start, never deloads and isn't active, and names are unique ignoring case`() =
        runBlocking {
            val id = program("Split")
            val split = get(id)
            assertEquals(emptyMap(), split.days)
            assertNull(split.startsOn)
            assertNull(split.deloadEvery)
            assertEquals(10, split.deloadPercent)
            assertFalse(split.active)
            assertEquals(ProgramNameResult.NameTaken, repo.create("split"))
            assertEquals(ProgramNameResult.NameBlank, repo.create("  "))
        }

    @Test
    fun `only one program is active at a time, and activating another deactivates it in the same write`() =
        runBlocking {
            val a = program("A")
            val b = program("B")
            repo.setActive(a, true)
            repo.setActive(b, true)
            assertEquals(b, repo.active.first()?.id)
            assertEquals(1, repo.programs.first().count { it.active })
            assertEquals(listOf("B", "A"), repo.programs.first().map { it.name })
            repo.setActive(b, false)
            assertNull(repo.active.first())
        }

    @Test
    fun `the schedule, start, deload every and percent are kept, and values out of range are ignored`() =
        runBlocking {
            val upper = routine("Upper")
            val id = program("Split")
            repo.setDay(id, DayOfWeek.MONDAY, upper)
            repo.setDay(id, DayOfWeek.FRIDAY, upper)
            repo.setDay(id, DayOfWeek.FRIDAY, null)
            repo.setStart(id, LocalDate(2026, 11, 2))
            repo.setDeloadEvery(id, 4)
            repo.setDeloadEvery(id, 1)
            repo.setDeloadPercent(id, 20)
            repo.setDeloadPercent(id, 90)
            val split = get(id)
            assertEquals(mapOf(DayOfWeek.MONDAY to upper), split.days)
            assertEquals(LocalDate(2026, 11, 2), split.startsOn)
            assertEquals(4, split.deloadEvery)
            assertEquals(20, split.deloadPercent)
            assertTrue(split.edited)
        }

    @Test
    fun `a deleted program comes back with Undo, days and all, active only if no other became active`() =
        runBlocking {
            val upper = routine("Upper")
            val id = program("Split")
            repo.setDay(id, DayOfWeek.MONDAY, upper)
            repo.setActive(id, true)
            val deleted = repo.delete(id)!!
            assertNull(repo.program(id).first())
            assertTrue(repo.restore(deleted))
            assertFalse(repo.restore(deleted))
            assertEquals(mapOf(DayOfWeek.MONDAY to upper), get(id).days)
            assertTrue(get(id).active)
            val again = repo.delete(id)!!
            val other = program("Other")
            repo.setActive(other, true)
            program("Split")
            assertTrue(repo.restore(again))
            assertEquals("Split copy", get(id).name)
            assertFalse(get(id).active)
            assertEquals(other, repo.active.first()?.id)
        }

    @Test
    fun `a copy keeps the schedule and settings, isn't active and is named copy`() =
        runBlocking {
            val upper = routine("Upper")
            val id = program("Split")
            repo.setDay(id, DayOfWeek.MONDAY, upper)
            repo.setDeloadEvery(id, 6)
            repo.setActive(id, true)
            val copy = get(repo.copy(id)!!)
            assertEquals("Split copy", copy.name)
            assertFalse(copy.active)
            assertEquals(mapOf(DayOfWeek.MONDAY to upper), copy.days)
            assertEquals(6, copy.deloadEvery)
            assertEquals("Split copy 2", get(repo.copy(id)!!).name)
        }

    @Test
    fun `deleting a routine leaves the days that planned it as rest days, and relinking gives them back`() =
        runBlocking {
            val upper = routine("Upper")
            val lower = routine("Lower")
            val id = program("Split")
            repo.setDay(id, DayOfWeek.MONDAY, upper)
            repo.setDay(id, DayOfWeek.THURSDAY, upper)
            repo.setDay(id, DayOfWeek.TUESDAY, lower)
            val unlinked = repo.daysUsing(upper)
            assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.THURSDAY), unlinked.days.map { it.weekday }.sorted())
            assertEquals(setOf("Split"), unlinked.days.map { it.programName }.toSet())
            val deleted = routines.delete(upper)!!
            assertEquals(mapOf(DayOfWeek.TUESDAY to lower), get(id).days)
            // A day given another routine meanwhile keeps it.
            repo.setDay(id, DayOfWeek.THURSDAY, lower)
            routines.restore(deleted)
            repo.relink(unlinked)
            assertEquals(mapOf(DayOfWeek.MONDAY to upper, DayOfWeek.TUESDAY to lower, DayOfWeek.THURSDAY to lower), get(id).days)
        }

    @Test
    fun `built-in programs are added with their routines and deload percent, kept when edited, and stay deleted`() =
        runBlocking {
            exercises.syncBuiltIn(listOf(ExerciseDraft("squat", ExerciseFields(name = "Squat"))))
            routines.syncBuiltIn(
                listOf(RoutineDraft("legs", "Legs", "", listOf(RoutineItemDraft("squat", line = 1)), listOf(Source("Me", null)))),
            )
            val draft =
                ProgramDraft(
                    "split",
                    "Split",
                    "Notes",
                    mapOf(DayOfWeek.MONDAY to "legs", DayOfWeek.FRIDAY to "missing"),
                    deloadEvery = 6,
                    deloadPercent = 15,
                )
            repo.syncBuiltIn(listOf(draft))
            val split = repo.programs.first().single()
            assertEquals("split", split.contentId)
            assertEquals(listOf(DayOfWeek.MONDAY), split.days.keys.toList())
            assertEquals(6, split.deloadEvery)
            assertEquals(15, split.deloadPercent)
            assertFalse(split.active)
            assertNull(split.startsOn)
            // Activating it and setting its start are the user's state, not edits: it still follows
            // the file, keeping both.
            repo.setActive(split.id, true)
            repo.setStart(split.id, LocalDate(2026, 11, 2))
            assertFalse(get(split.id).edited)
            repo.syncBuiltIn(listOf(draft.copy(deloadEvery = 4, deloadPercent = null)))
            assertEquals(4, get(split.id).deloadEvery)
            assertEquals(10, get(split.id).deloadPercent)
            assertTrue(get(split.id).active)
            assertEquals(LocalDate(2026, 11, 2), get(split.id).startsOn)
            // Changing what the program is (here its deload percent) is an edit: the file is left out.
            repo.setDeloadPercent(split.id, 20)
            assertTrue(get(split.id).edited)
            repo.syncBuiltIn(listOf(draft.copy(deloadEvery = 8)))
            assertEquals(4, get(split.id).deloadEvery)
            repo.delete(split.id)
            repo.syncBuiltIn(listOf(draft))
            assertEquals(emptyList(), repo.programs.first())
        }

    @Test
    fun `activating a program without a start starts week 1 on this week's Monday, and keeps a start it has`() =
        runBlocking {
            val a = program("A")
            repo.setActive(a, true)
            // The clock says Wednesday 11 Nov 2026.
            assertEquals(LocalDate(2026, 11, 9), get(a).startsOn)
            val b = program("B")
            repo.setStart(b, LocalDate(2026, 10, 5))
            repo.setActive(b, true)
            assertEquals(LocalDate(2026, 10, 5), get(b).startsOn)
            assertFalse(get(a).active)
        }

    @Test
    fun `a deleted program comes back with a rest day where its routine was deleted meanwhile`() =
        runBlocking {
            val upper = routine("Upper")
            val lower = routine("Lower")
            val id = program("Split")
            repo.setDay(id, DayOfWeek.MONDAY, upper)
            repo.setDay(id, DayOfWeek.TUESDAY, lower)
            val deleted = repo.delete(id)!!
            routines.delete(upper)
            assertTrue(repo.restore(deleted))
            assertEquals(mapOf(DayOfWeek.TUESDAY to lower), get(id).days)
        }
}

class RoomBodyWeightRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val repo = RoomBodyWeightRepository(database.bodyWeightDao())
    private val mon = LocalDate(2026, 11, 2)
    private val tue = LocalDate(2026, 11, 3)

    @AfterTest
    fun close() = database.close()

    @Test
    fun `one reading per day, newest first, and saving again replaces it`() =
        runBlocking {
            repo.set(mon, 82.0, WeightUnit.Kg)
            repo.set(tue, 180.0, WeightUnit.Lb)
            assertEquals(BodyWeight(mon, 82.0, WeightUnit.Kg), repo.set(mon, 81.5, WeightUnit.Kg))
            assertEquals(listOf(BodyWeight(tue, 180.0, WeightUnit.Lb), BodyWeight(mon, 81.5, WeightUnit.Kg)), repo.readings.first())
        }

    @Test
    fun `an empty or zero reading deletes the day's, and a deleted one comes back with restore`() =
        runBlocking {
            repo.set(mon, 82.0, WeightUnit.Kg)
            repo.set(tue, 81.0, WeightUnit.Kg)
            repo.set(mon, null, WeightUnit.Kg)
            repo.set(tue, 0.0, WeightUnit.Kg)
            assertEquals(emptyList(), repo.readings.first())
            repo.set(mon, 82.0, WeightUnit.Kg)
            val deleted = repo.delete(mon)!!
            assertNull(repo.delete(mon))
            repo.restore(deleted)
            assertEquals(listOf(deleted), repo.readings.first())
        }
}
