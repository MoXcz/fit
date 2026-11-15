// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import app.fit.content.ExerciseDraft
import app.fit.core.AppDispatchers
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.day.RoomDayRepository
import app.fit.day.SetEdit
import app.fit.day.WorkoutDay
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.RoomExerciseRepository
import app.fit.exercises.Tracks
import app.fit.programs.ProgramNameResult
import app.fit.programs.RoomProgramRepository
import app.fit.routines.CreateAndAddResult
import app.fit.routines.ItemTarget
import app.fit.routines.NewExercise
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RoomDayRepositoryTest {
    private val database =
        buildAppDatabase(
            Room.databaseBuilder<AppDatabase>(Files.createTempDirectory("db").resolve("test.db").toString()),
            AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
        )
    private val clock =
        object : Clock {
            var now = Instant.parse("2026-11-09T10:00:00Z")

            override fun now() = now
        }
    private val settings = FakeSettingsRepository()
    private val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    private val routines = RoomRoutineRepository(database.routineDao(), database.exerciseDao(), exercises, settings, clock)
    private val programs = RoomProgramRepository(database.programDao(), clock, TimeZone.UTC)
    private val repo = RoomDayRepository(database.dayDao(), exercises, routines, programs, settings, clock, TimeZone.UTC)
    private val mon = LocalDate(2026, 11, 2)
    private val today = LocalDate(2026, 11, 9)

    @AfterTest
    fun close() = database.close()

    private suspend fun setUp(): RoutineId {
        exercises.syncBuiltIn(
            listOf(
                ExerciseDraft("squat", ExerciseFields(name = "Squat", defaults = ExerciseDefaults(sets = 2, reps = 5, weight = 20.0))),
                ExerciseDraft("plank", ExerciseFields(name = "Plank", tracks = Tracks(time = true))),
                ExerciseDraft(
                    "walk",
                    ExerciseFields(name = "Walk", tracks = Tracks(distance = true, time = true), distanceUnit = DistanceUnit.Mi),
                ),
            ),
        )
        val id = assertIs<RoutineNameResult.Saved>(routines.create("Legs")).id
        routines.addExercise(id, exerciseId("Squat"))
        routines.addExercise(id, exerciseId("Plank"), section = "Stretch")
        val squat =
            routines
                .routine(id)
                .first()!!
                .items
                .first()
        routines.saveItem(squat.id, squat.target.copy(sets = 3, reps = 5, progressStep = 2.5, warmupSets = 2))
        return id
    }

    private suspend fun exerciseId(name: String): ExerciseId =
        exercises.exercises
            .first()
            .single { it.fields.name == name }
            .id

    private suspend fun day(date: LocalDate = today): WorkoutDay? = repo.day(date).first()

    // A logged Monday: every squat set done at 60 kg × 5, plus a heavier warm-up that must not count.
    private suspend fun logMonday(routine: RoutineId) {
        repo.applyRoutine(mon, routine, replace = false)
        val block = day(mon)!!.blocks.first()
        block.sets.forEach { set ->
            repo.editSet(set.id, SetEdit.Weight(if (set.warmup) 100.0 else 60.0))
            repo.setDone(set.id, true)
        }
    }

    @Test
    fun `applying a routine copies a snapshot, so editing the routine later changes nothing in the day`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            val before = day()!!
            assertEquals("Legs", before.name)
            assertEquals(listOf("Main:Squat", "Stretch:Plank"), before.blocks.map { "${it.section}:${it.exercise.fields.name}" })
            val squat =
                routines
                    .routine(routine)
                    .first()!!
                    .items
                    .first()
            routines.saveItem(squat.id, squat.target.copy(sets = 6, reps = 1))
            routines.rename(routine, "Legs v2")
            assertEquals(before, day())
        }

    @Test
    fun `progression reads the last done working sets in the exercise's unit, never warm-ups`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.applyRoutine(today, routine, replace = false)
            val squat = day()!!.blocks.first()
            assertEquals(listOf(32.5, 47.5, 62.5, 62.5, 62.5), squat.sets.map { it.weight })
            assertEquals(listOf(true, true, false, false, false), squat.sets.map { it.warmup })
        }

    @Test
    fun `progression skips a last workout logged in another unit`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.applyRoutine(LocalDate(2026, 11, 5), routine, replace = false)
            day(LocalDate(2026, 11, 5))!!.blocks.first().workingSets.forEach {
                repo.editSet(it.id, SetEdit.Unit(WeightUnit.Lb))
                repo.editSet(it.id, SetEdit.Weight(200.0))
                repo.setDone(it.id, true)
            }
            repo.applyRoutine(today, routine, replace = false)
            assertEquals(
                62.5,
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
                    .weight,
            )
        }

    @Test
    fun `one day per date, and an added exercise lands at the end of its section with its targets from last time`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.applyRoutine(today, routine, replace = false)
            val added = repo.addExercise(today, exerciseId("Squat"))!!
            val day = day()!!
            assertEquals(listOf("Main", "Main", "Stretch"), day.blocks.map { it.section })
            val block = day.blocks[1]
            assertEquals(added, block.id)
            assertEquals(List(2) { 60.0 }, block.sets.map { it.weight })
            assertEquals(List(2) { 5 }, block.sets.map { it.targetReps })
            assertEquals(1, repo.days(today, today).first().size)
        }

    @Test
    fun `a drop moves a block several places in one write, inside its section only`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            repo.addExercise(today, exerciseId("Squat"))
            repo.addExercise(today, exerciseId("Squat"))
            val (a, b, c, stretch) = day()!!.blocks
            repo.moveBlock(c.id, -2)
            assertEquals(listOf(c.id, a.id, b.id, stretch.id), day()!!.blocks.map { it.id })
            // Main can't take it past its end into Stretch.
            repo.moveBlock(c.id, 7)
            assertEquals(listOf(a.id, b.id, c.id, stretch.id), day()!!.blocks.map { it.id })
            repo.moveBlock(stretch.id, -3)
            assertEquals(listOf(a.id, b.id, c.id, stretch.id), day()!!.blocks.map { it.id })
        }

    @Test
    fun `Copy a previous day lists only earlier days with sets`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.setName(LocalDate(2026, 11, 4), "Rest")
            repo.applyRoutine(LocalDate(2026, 11, 10), routine, replace = false)
            assertEquals(listOf(mon), repo.recentDays(today, 8).map { it.date })
            repo.copyDay(mon, today)
            val copy = day()!!
            assertEquals(
                listOf(true, true, false, false, false),
                copy.blocks
                    .first()
                    .sets
                    .map { it.warmup },
            )
            assertTrue(copy.blocks.flatMap { it.sets }.none { it.done || it.reps != null })
            assertEquals(
                60.0,
                copy.blocks
                    .first()
                    .workingSets
                    .first()
                    .weight,
            )
        }

    @Test
    fun `a deleted day comes back with Undo unless the date has exercises again`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            val before = day()!!
            val deleted = repo.deleteDay(today)!!
            assertNull(day())
            repo.setNotes(today, "empty again")
            assertTrue(repo.restoreDay(deleted))
            assertEquals(before, day())
            val again = repo.deleteDay(today)!!
            repo.addExercise(today, exerciseId("Plank"))
            assertFalse(repo.restoreDay(again))
            assertEquals(listOf("Plank"), day()!!.blocks.map { it.exercise.fields.name })
        }

    @Test
    fun `a removed exercise and a removed set come back where they were`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            repo.addExercise(today, exerciseId("Squat"))
            val before = day()!!
            val removed = repo.removeBlock(before.blocks.first().id)!!
            assertEquals("Squat", removed.name)
            repo.restoreBlock(removed)
            assertEquals(before.blocks.map { it.id }, day()!!.blocks.map { it.id })
            val sets = before.blocks.first().sets
            val set = repo.removeSet(sets[2].id)!!
            repo.restoreSet(set)
            assertEquals(
                sets.map { it.id },
                day()!!
                    .blocks
                    .first()
                    .sets
                    .map { it.id },
            )
        }

    @Test
    fun `the first ticked set starts the clock, Finish ends at a stale last set and Reopen shifts the start`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            val set =
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
            repo.setDone(set.id, true)
            val ticked =
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
            assertEquals(clock.now, day()!!.startedAt)
            assertEquals(5, ticked.reps)
            assertEquals(5, ticked.targetReps)
            val lastSet = clock.now
            clock.now += 30.minutes
            repo.finish(today)
            assertEquals(lastSet, day()!!.finishedAt)
            clock.now += 10.minutes
            repo.reopen(today)
            assertNull(day()!!.finishedAt)
            assertEquals(lastSet + 40.minutes, day()!!.startedAt)
        }

    @Test
    fun `sets of exercises without weight still get units, and distance uses the exercise's unit`() =
        runBlocking {
            setUp()
            repo.addExercise(today, exerciseId("Walk"))
            repo.addExercise(today, exerciseId("Plank"))
            val (walk, plank) = day()!!.blocks
            assertTrue(walk.sets.all { it.weight == null && it.weightUnit == WeightUnit.Kg && it.distanceUnit == DistanceUnit.Mi })
            assertEquals(45.seconds, plank.sets.first().targetDuration)
            repo.setDone(plank.sets.first().id, true)
            val done = day()!!.blocks[1].sets.first()
            assertEquals(45.seconds, done.duration)
            assertNull(done.reps)
        }

    @Test
    fun `Create and add on a day writes the exercise and its block together, and a taken name writes nothing`() =
        runBlocking {
            setUp()
            val new =
                NewExercise(name = "Dead hang", tracks = Tracks(time = true), sets = 2, reps = 1, duration = 30.seconds, rest = 60.seconds)
            assertIs<CreateAndAddResult.Added>(repo.createExerciseAndAdd(today, new))
            assertEquals(listOf("Dead hang"), day()!!.blocks.map { it.exercise.fields.name })
            assertEquals(CreateAndAddResult.NameTaken, repo.createExerciseAndAdd(today, new.copy(name = "squat")))
            assertEquals(1, day()!!.blocks.size)
            assertEquals(4, exercises.exercises.first().size)
        }

    @Test
    fun `save as routine with a taken name writes nothing`() =
        runBlocking {
            val routine = setUp()
            val items = listOf(exerciseId("Squat") to ItemTarget(sets = 2, reps = 5, rest = 90.seconds, duration = 45.seconds))
            assertEquals(RoutineNameResult.NameTaken, routines.createWithItems("legs", items))
            assertEquals(1, routines.routines.first().size)
            val saved = assertIs<RoutineNameResult.Saved>(routines.createWithItems("Copy of legs", items))
            assertEquals(
                1,
                routines
                    .routine(saved.id)
                    .first()!!
                    .items.size,
            )
            assertEquals(
                routine,
                routines.routines
                    .first()
                    .first { it.name == "Legs" }
                    .id,
            )
        }

    @Test
    fun `warm-ups added by hand and new sets keep warm-ups first and working sets in order`() =
        runBlocking {
            setUp()
            val id = repo.addExercise(today, exerciseId("Squat"))!!
            repo.editSet(
                day()!!
                    .blocks
                    .single()
                    .sets
                    .last()
                    .id,
                SetEdit.Weight(80.0),
            )
            repo.addSet(id)
            repo.addWarmups(id)
            val sets = day()!!.blocks.single().sets
            assertEquals(listOf(true, true, false, false, false), sets.map { it.warmup })
            assertEquals(listOf(10.0, 15.0, 20.0, 80.0, 80.0), sets.map { it.weight })
        }

    @Test
    fun `a day never progresses from its own sets, so replacing it again doesn't raise the load`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.applyRoutine(today, routine, replace = true)
            day()!!
                .blocks
                .first()
                .workingSets
                .forEach { repo.setDone(it.id, true) }
            repo.applyRoutine(today, routine, replace = true)
            repo.applyRoutine(today, routine, replace = true)
            assertEquals(
                List(3) { 62.5 },
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .map { it.weight },
            )
        }

    @Test
    fun `replacing a day starts it over, while adding keeps its clock`() =
        runBlocking {
            val routine = setUp()
            repo.applyRoutine(today, routine, replace = false)
            repo.setDone(
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
                    .id,
                true,
            )
            repo.finish(today)
            repo.applyRoutine(today, routine, replace = false)
            assertTrue(day()!!.finished)
            repo.applyRoutine(today, routine, replace = true)
            assertNull(day()!!.startedAt)
            assertNull(day()!!.finishedAt)
        }

    @Test
    fun `plus and minus never change a logged set`() =
        runBlocking {
            setUp()
            val id = repo.addExercise(today, exerciseId("Squat"))!!
            val sets = day()!!.blocks.single().sets
            repo.setDone(sets[0].id, true)
            repo.stepWeight(id, up = true)
            assertEquals(
                listOf(20.0, 22.5),
                day()!!
                    .blocks
                    .single()
                    .sets
                    .map { it.weight },
            )
            repo.setDone(sets[1].id, true)
            repo.stepWeight(id, up = true)
            assertEquals(
                listOf(20.0, 22.5),
                day()!!
                    .blocks
                    .single()
                    .sets
                    .map { it.weight },
            )
        }

    @Test
    fun `Undo puts a removed set back in its place after a set was added`() =
        runBlocking {
            setUp()
            val id = repo.addExercise(today, exerciseId("Squat"))!!
            repo.addSet(id)
            val before =
                day()!!
                    .blocks
                    .single()
                    .sets
                    .map { it.id }
            val removed = repo.removeSet(before.last())!!
            repo.addSet(id)
            repo.restoreSet(removed)
            val after =
                day()!!
                    .blocks
                    .single()
                    .sets
                    .map { it.id }
            assertEquals(before, after.take(3))
            assertEquals(4, after.size)
        }

    @Test
    fun `Undo puts a removed working set back in its place after warm-ups were added`() =
        runBlocking {
            setUp()
            val id = repo.addExercise(today, exerciseId("Squat"))!!
            repo.addSet(id)
            val working =
                day()!!
                    .blocks
                    .single()
                    .sets
                    .map { it.id }
            val removed = repo.removeSet(working[1])!!
            repo.addWarmups(id)
            repo.restoreSet(removed)
            val sets = day()!!.blocks.single().sets
            assertEquals(listOf(true, true, false, false, false), sets.map { it.warmup })
            assertEquals(working, sets.drop(2).map { it.id })
        }

    @Test
    fun `a set ticked on another day is recorded but doesn't start that day's clock`() =
        runBlocking {
            val routine = setUp()
            val yesterday = LocalDate(2026, 11, 8)
            repo.applyRoutine(yesterday, routine, replace = false)
            repo.setDone(
                day(yesterday)!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
                    .id,
                true,
            )
            assertNull(day(yesterday)!!.startedAt)
            assertEquals(1, day(yesterday)!!.doneSets)
        }

    @Test
    fun `a hold is logged with the time held at its end, starts today's clock at its start, and only once`() =
        runBlocking {
            setUp()
            val block = repo.addExercise(today, exerciseId("Plank"))!!
            val set =
                day()!!
                    .blocks
                    .single { it.id == block }
                    .sets
                    .first()
            val start = clock.now
            repo.logHold(set.id, 25.seconds, startedAt = start, at = start + 25.seconds)
            val logged =
                day()!!
                    .blocks
                    .single { it.id == block }
                    .sets
                    .first()
            assertEquals(25.seconds, logged.duration)
            assertEquals(start + 25.seconds, logged.doneAt)
            assertEquals(start, day()!!.startedAt)
            repo.logHold(set.id, 40.seconds, startedAt = start, at = start + 40.seconds)
            assertEquals(
                logged,
                day()!!
                    .blocks
                    .single { it.id == block }
                    .sets
                    .first(),
            )
        }

    @Test
    fun `trained days are the days with a done working set, newest first`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            // Today: only a warm-up done. Tomorrow: finished with nothing done. Neither is a workout.
            repo.applyRoutine(today, routine, replace = false)
            repo.setDone(
                day()!!
                    .blocks
                    .first()
                    .sets
                    .first { it.warmup }
                    .id,
                true,
            )
            val tomorrow = LocalDate(2026, 11, 10)
            repo.applyRoutine(tomorrow, routine, replace = false)
            repo.finish(tomorrow)
            val walk = repo.addExercise(LocalDate(2026, 11, 5), exerciseId("Walk"))!!
            repo.setDone(
                repo
                    .dayOfBlock(walk)
                    .first()!!
                    .blocks
                    .single()
                    .sets
                    .first()
                    .id,
                true,
            )
            assertEquals(listOf(LocalDate(2026, 11, 5), mon), repo.trainedDays().first().map { it.date })
        }

    @Test
    fun `an exercise's history and earlier sets leave out warm-ups, and earlier sets stop before the day`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            val squat = exerciseId("Squat")
            val all = repo.exerciseSets(squat).first()
            assertEquals(List(3) { 60.0 }, all.map { it.weight })
            assertTrue(all.all { it.date == mon })
            assertEquals(emptyList(), repo.earlierSets(listOf(squat), mon))
            assertEquals(List(3) { 60.0 }, repo.earlierSets(listOf(squat), today).map { it.weight })
            // A distance keeps its unit.
            val walk = repo.addExercise(mon, exerciseId("Walk"))!!
            val set =
                repo
                    .dayOfBlock(walk)
                    .first()!!
                    .blocks
                    .single { it.id == walk }
                    .sets
                    .first()
            repo.editSet(set.id, SetEdit.Distance(3.1))
            repo.setDone(set.id, true)
            val walked = repo.exerciseSets(exerciseId("Walk")).first().single()
            assertEquals(3.1 to DistanceUnit.Mi, walked.distance to walked.distanceUnit)
        }

    @Test
    fun `fatigue is kept from 1 to 5, cleared with null, ignored outside, and gone when a routine replaces the day`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            repo.setFatigue(mon, 3)
            assertEquals(3, day(mon)!!.fatigue)
            repo.setFatigue(mon, 6)
            repo.setFatigue(mon, 0)
            assertEquals(3, day(mon)!!.fatigue)
            repo.setFatigue(mon, null)
            assertNull(day(mon)!!.fatigue)
            repo.setFatigue(mon, 5)
            repo.applyRoutine(mon, routine, replace = true)
            assertNull(day(mon)!!.fatigue)
        }

    // Week 2 of a program from 2 Nov, with a deload every 2 weeks: 9 Nov is a deload day.
    private suspend fun deloadProgram(routine: RoutineId) {
        val id = assertIs<ProgramNameResult.Saved>(programs.create("Split")).id
        programs.setDay(id, DayOfWeek.MONDAY, routine)
        programs.setStart(id, mon)
        programs.setDeloadEvery(id, 2)
        programs.setActive(id, true)
    }

    @Test
    fun `a routine applied in a deload week is cut and rounded, warm-ups follow, and the day is marked`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            deloadProgram(routine)
            repo.applyRoutine(today, routine, replace = false)
            val day = day()!!
            assertTrue(day.deload)
            // 60 kg, no step in a deload week, less 10 %: 54, rounded to 55.
            assertEquals(
                listOf(27.5, 42.5, 55.0, 55.0, 55.0),
                day.blocks
                    .first()
                    .sets
                    .map { it.weight },
            )
        }

    @Test
    fun `progression skips the deload day, so the week after builds on the last full week`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            deloadProgram(routine)
            repo.applyRoutine(today, routine, replace = false)
            day()!!
                .blocks
                .first()
                .workingSets
                .forEach { repo.setDone(it.id, true) }
            val next = LocalDate(2026, 11, 16)
            repo.applyRoutine(next, routine, replace = false)
            assertFalse(day(next)!!.deload)
            assertEquals(
                62.5,
                day(next)!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
                    .weight,
            )
        }

    @Test
    fun `a bodyweight set and its RPE are kept, its load is what was added, and a bad RPE is ignored`() =
        runBlocking {
            setUp()
            val block = repo.addExercise(today, exerciseId("Squat"))!!
            val set =
                day()!!
                    .blocks
                    .single { it.id == block }
                    .workingSets
                    .first()
            repo.editSet(set.id, SetEdit.Bodyweight(true))
            repo.editSet(set.id, SetEdit.Weight(10.0))
            repo.editSet(set.id, SetEdit.Rpe(8.5))
            repo.editSet(set.id, SetEdit.Rpe(11.0))
            repo.editSet(set.id, SetEdit.Reps(5))
            repo.setDone(set.id, true)
            val done =
                day()!!
                    .blocks
                    .single()
                    .workingSets
                    .first()
            assertTrue(done.bodyweight)
            assertEquals(8.5, done.rpe)
            assertEquals(mapOf(WeightUnit.Kg to 50.0), day()!!.volume)
            val history = repo.exerciseSets(exerciseId("Squat")).first().single()
            assertTrue(history.bodyweight)
            assertEquals(10.0, history.weight)
        }

    @Test
    fun `copying a deload day makes a deload day, and a deleted one comes back marked`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            deloadProgram(routine)
            repo.applyRoutine(today, routine, replace = false)
            val tue = LocalDate(2026, 11, 10)
            repo.copyDay(today, tue)
            assertTrue(day(tue)!!.deload)
            val deleted = repo.deleteDay(today)!!
            assertTrue(repo.restoreDay(deleted))
            assertTrue(day()!!.deload)
        }

    @Test
    fun `Replace with a routine outside a deload week clears the mark`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            deloadProgram(routine)
            repo.applyRoutine(today, routine, replace = false)
            assertTrue(day()!!.deload)
            programs.setDeloadEvery(
                programs.programs
                    .first()
                    .single()
                    .id,
                null,
            )
            repo.applyRoutine(today, routine, replace = true)
            assertFalse(day()!!.deload)
            assertEquals(
                62.5,
                day()!!
                    .blocks
                    .first()
                    .workingSets
                    .first()
                    .weight,
            )
        }

    @Test
    fun `an exercise added in a deload week is cut and marks the day`() =
        runBlocking {
            val routine = setUp()
            logMonday(routine)
            deloadProgram(routine)
            repo.addExercise(today, exerciseId("Squat"))
            assertTrue(day()!!.deload)
            assertEquals(
                55.0,
                day()!!
                    .blocks
                    .single()
                    .workingSets
                    .first()
                    .weight,
            )
        }
}
