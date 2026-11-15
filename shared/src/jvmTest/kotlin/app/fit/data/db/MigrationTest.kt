// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (c) 2026 Nishant Mishra (Tomato: jvmTest/.../data/MigrationTest.kt), adapted.
package app.fit.data.db

import androidx.room3.Room
import androidx.room3.testing.MigrationTestHelper
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import app.fit.core.AppDispatchers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private fun SQLiteConnection.selectRows(sql: String): List<List<Any?>> =
    prepare(sql).use { statement ->
        buildList {
            while (statement.step()) {
                add(
                    (0 until statement.getColumnCount()).map { i ->
                        when (statement.getColumnType(i)) {
                            SQLITE_DATA_NULL -> null
                            SQLITE_DATA_TEXT -> statement.getText(i)
                            SQLITE_DATA_FLOAT -> statement.getDouble(i)
                            else -> statement.getLong(i)
                        }
                    },
                )
            }
        }
    }

// Every version bump gets a migration test (docs/engineering.md §11).
class MigrationTest {
    private val directory: Path = Files.createTempDirectory("migration")
    private val file = directory.resolve("migration-test.db")

    private val helper =
        MigrationTestHelper(
            schemaDirectoryPath = Path.of(System.getProperty("room.schemaDir")),
            databasePath = file,
            driver = BundledSQLiteDriver(),
            databaseClass = AppDatabase::class,
        )

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun deleteDatabase() {
        directory.deleteRecursively()
    }

    @Test
    fun `migrating 1 to 2 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(1).close()
            helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).close()
        }

    @Test
    fun `migrating 1 to 2 keeps every exercise`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (7, 'plank', 'Plank', 'Abs', 0, 0, 1, 0, 'Both', 'Kg', 'Km', 3, 10, NULL, 60, NULL, 60, 0.0, '', '', 1, 1, 5)
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO exercise_area (exerciseId, area, position) VALUES (7, 'lower-back', 0)")
            }
            helper.runMigrationsAndValidate(2, listOf(MIGRATION_1_2)).use { db ->
                assertEquals(
                    listOf(listOf(7L, "plank", "Plank", 1L, 1L)),
                    db.selectRows("SELECT id, contentId, name, archived, edited FROM exercise"),
                )
                assertEquals(listOf(listOf(7L, "lower-back")), db.selectRows("SELECT exerciseId, area FROM exercise_area"))
            }
        }

    @Test
    fun `migrating 2 to 3 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(2).close()
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_2_3)).close()
        }

    @Test
    fun `migrating 1 to 3 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (3, NULL, 'Mine', '', 1, 1, 0, 0, 'Both', 'Lb', 'Mi', 4, 8, 12, 90, 120, 40, 20.0, 'n', '', 0, 1, 9)
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(3, listOf(MIGRATION_1_2, MIGRATION_2_3)).use { db ->
                assertEquals(
                    listOf(listOf(3L, null, "Mine", "Lb", 12L, 120L, 20.0)),
                    db.selectRows(
                        "SELECT id, contentId, name, weightUnit, defaultRepsMax, defaultRestMaxSeconds, defaultWeight FROM exercise",
                    ),
                )
            }
        }

    @Test
    fun `migrating 3 to 4 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(3).close()
            helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).close()
        }

    @Test
    fun `migrating 3 to 4 keeps routines and their items, and starts with no days`(): Unit =
        runBlocking {
            helper.createDatabase(3).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (1, 'squat', 'Squat', 'Quads', 1, 1, 0, 0, 'Both', 'Kg', 'Km', 3, 5, NULL, 180, NULL, 45, 0.0, '', '', 0, 0, 1)
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO routine (id, contentId, name, notes, edited, createdAtMillis) VALUES (2, 'legs', 'Legs', '', 0, 1)")
                db.execSQL(
                    """
                    INSERT INTO routine_item (id, routineId, exerciseId, position, section, sets, reps, repsMax, restSeconds,
                        restMaxSeconds, durationSeconds, tracksWeight, tracksReps, tracksTime, tracksDistance, progressStep,
                        progressRpe, warmupSets, notes)
                    VALUES (3, 2, 1, 0, 'Main', 3, 5, NULL, 180, NULL, 45, NULL, NULL, NULL, NULL, 2.5, 8.0, 2, '')
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO content_tombstone (kind, contentId) VALUES ('routine', 'old')")
            }
            helper.runMigrationsAndValidate(4, listOf(MIGRATION_3_4)).use { db ->
                assertEquals(listOf(listOf(2L, "Legs")), db.selectRows("SELECT id, name FROM routine"))
                assertEquals(
                    listOf(listOf(3L, 2.5, 8.0, 2L)),
                    db.selectRows("SELECT id, progressStep, progressRpe, warmupSets FROM routine_item"),
                )
                assertEquals(listOf(listOf("old")), db.selectRows("SELECT contentId FROM content_tombstone"))
                assertEquals(listOf(listOf(0L)), db.selectRows("SELECT COUNT(*) FROM workout_day"))
            }
        }

    @Test
    fun `migrating 1 to 4 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(4, listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)).use { db ->
                assertEquals(
                    listOf(listOf(4L, "Walk", "Mi", 1800L)),
                    db.selectRows("SELECT id, name, distanceUnit, defaultDurationSeconds FROM exercise"),
                )
            }
        }

    @Test
    fun `migrating 4 to 5 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(4).close()
            helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).close()
        }

    @Test
    fun `migrating 4 to 5 keeps the exercises and days, with no equipment said`(): Unit =
        runBlocking {
            helper.createDatabase(4).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (1, 'squat', 'Squat', 'Quads', 1, 1, 0, 0, 'Both', 'Kg', 'Km', 3, 5, NULL, 180, NULL, 45, 0.0, '', '', 0, 1, 1)
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO workout_day (id, day, name, notes, startedAtMillis, finishedAtMillis) VALUES (7, '2026-11-09', 'Legs', '', 1, NULL)",
                )
            }
            helper.runMigrationsAndValidate(5, listOf(MIGRATION_4_5)).use { db ->
                assertEquals(listOf(listOf(1L, "Squat", 1L, null)), db.selectRows("SELECT id, name, edited, equipment FROM exercise"))
                assertEquals(listOf(listOf(7L, "2026-11-09")), db.selectRows("SELECT id, day FROM workout_day"))
            }
        }

    @Test
    fun `migrating 1 to 5 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            helper.runMigrationsAndValidate(5, listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)).use { db ->
                assertEquals(listOf(listOf(4L, "Walk", null)), db.selectRows("SELECT id, name, equipment FROM exercise"))
            }
        }

    @Test
    fun `migrating 5 to 6 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(5).close()
            helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).close()
        }

    @Test
    fun `migrating 5 to 6 keeps the days and their sets, with no fatigue entered`(): Unit =
        runBlocking {
            helper.createDatabase(5).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis,
                        equipment)
                    VALUES (1, 'squat', 'Squat', 'Quads', 1, 1, 0, 0, 'Both', 'Kg', 'Km', 3, 5, NULL, 180, NULL, 45, 0.0, '', '', 0, 0, 1,
                        'Barbell')
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO workout_day (id, day, name, notes, startedAtMillis, finishedAtMillis) " +
                        "VALUES (7, '2026-11-09', 'Legs', 'Good', 1, 2)",
                )
                db.execSQL(
                    "INSERT INTO block (id, dayId, exerciseId, position, section, restSeconds, restMaxSeconds, durationSeconds, " +
                        "tracksWeight, tracksReps, tracksTime, tracksDistance, warmupSets, notes) " +
                        "VALUES (3, 7, 1, 0, 'Main', 90, NULL, 45, NULL, NULL, NULL, NULL, 0, '')",
                )
                db.execSQL(
                    "INSERT INTO set_entry (id, blockId, position, warmup, targetReps, targetRepsMax, targetDurationSeconds, weight, " +
                        "weightUnit, reps, durationSeconds, distance, distanceUnit, rpe, doneAtMillis) " +
                        "VALUES (9, 3, 0, 0, 5, NULL, NULL, 60.0, 'Kg', 5, NULL, NULL, 'Km', NULL, 2)",
                )
            }
            helper.runMigrationsAndValidate(6, listOf(MIGRATION_5_6)).use { db ->
                assertEquals(
                    listOf(listOf(7L, "2026-11-09", "Good", 2L, null)),
                    db.selectRows("SELECT id, day, notes, finishedAtMillis, fatigue FROM workout_day"),
                )
                assertEquals(listOf(listOf(9L, 60.0, 5L)), db.selectRows("SELECT id, weight, reps FROM set_entry"))
            }
        }

    @Test
    fun `migrating 1 to 6 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            val all = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            helper.runMigrationsAndValidate(6, all).use { db ->
                assertEquals(listOf(listOf(4L, "Walk")), db.selectRows("SELECT id, name FROM exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM workout_day"))
            }
        }

    @Test
    fun `migrating 6 to 7 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(6).close()
            helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7)).close()
        }

    @Test
    fun `migrating 6 to 7 keeps routines, days and sets, none a deload or bodyweight, with no programs or readings`(): Unit =
        runBlocking {
            helper.createDatabase(6).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis,
                        equipment)
                    VALUES (1, 'squat', 'Squat', 'Quads', 1, 1, 0, 0, 'Both', 'Kg', 'Km', 3, 5, NULL, 180, NULL, 45, 0.0, '', '', 0, 0, 1,
                        'Barbell')
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO routine (id, contentId, name, notes, edited, createdAtMillis) VALUES (4, NULL, 'Legs', '', 0, 1)")
                db.execSQL(
                    "INSERT INTO workout_day (id, day, name, notes, startedAtMillis, finishedAtMillis, fatigue) " +
                        "VALUES (7, '2026-11-09', 'Legs', '', 1, 2, 3)",
                )
                db.execSQL(
                    "INSERT INTO block (id, dayId, exerciseId, position, section, restSeconds, restMaxSeconds, durationSeconds, " +
                        "tracksWeight, tracksReps, tracksTime, tracksDistance, warmupSets, notes) " +
                        "VALUES (3, 7, 1, 0, 'Main', 90, NULL, 45, NULL, NULL, NULL, NULL, 0, '')",
                )
                db.execSQL(
                    "INSERT INTO set_entry (id, blockId, position, warmup, targetReps, targetRepsMax, targetDurationSeconds, weight, " +
                        "weightUnit, reps, durationSeconds, distance, distanceUnit, rpe, doneAtMillis) " +
                        "VALUES (9, 3, 0, 0, 5, NULL, NULL, 60.0, 'Kg', 5, NULL, NULL, 'Km', 8.0, 2)",
                )
            }
            helper.runMigrationsAndValidate(7, listOf(MIGRATION_6_7)).use { db ->
                assertEquals(listOf(listOf(7L, 3L, 0L)), db.selectRows("SELECT id, fatigue, deload FROM workout_day"))
                assertEquals(listOf(listOf(9L, 60.0, 8.0, 0L)), db.selectRows("SELECT id, weight, rpe, bodyweight FROM set_entry"))
                assertEquals(listOf(listOf(4L, "Legs")), db.selectRows("SELECT id, name FROM routine"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM program"))
                assertEquals(emptyList(), db.selectRows("SELECT day FROM bodyweight"))
                // A program day loses its routine when the routine goes (SET NULL), and goes with its program.
                db.execSQL(
                    "INSERT INTO program (id, contentId, name, notes, active, startsOn, deloadEvery, deloadPercent, edited, " +
                        "createdAtMillis) " +
                        "VALUES (1, NULL, 'Split', '', 1, '2026-11-02', 4, 10, 0, 1)",
                )
                db.execSQL("INSERT INTO program_day (programId, weekday, routineId) VALUES (1, 1, 4)")
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("DELETE FROM routine WHERE id = 4")
                assertEquals(listOf(listOf(1L, null)), db.selectRows("SELECT weekday, routineId FROM program_day"))
                db.execSQL("DELETE FROM program WHERE id = 1")
                assertEquals(emptyList(), db.selectRows("SELECT weekday FROM program_day"))
            }
        }

    @Test
    fun `migrating 1 to 7 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            val all = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            helper.runMigrationsAndValidate(7, all).use { db ->
                assertEquals(listOf(listOf(4L, "Walk")), db.selectRows("SELECT id, name FROM exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM program"))
            }
        }

    @Test
    fun `migrating 7 to 8 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(7).close()
            helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).close()
        }

    @Test
    fun `migrating 7 to 8 keeps exercises and days, with no plans or logs, and a log outlives its plan`(): Unit =
        runBlocking {
            helper.createDatabase(7).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis,
                        equipment)
                    VALUES (1, 'wrist-flexor-stretch', 'Wrist flexor stretch', 'Mobility', 0, 0, 1, 0, 'Each', 'Kg', 'Km', 1, 10, NULL,
                        0, NULL, 30, 0.0, '', '', 0, 0, 1, 'bodyweight')
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO workout_day (id, day, name, notes, startedAtMillis, finishedAtMillis, fatigue, deload) " +
                        "VALUES (7, '2026-11-09', 'Legs', '', 1, 2, NULL, 0)",
                )
            }
            helper.runMigrationsAndValidate(8, listOf(MIGRATION_7_8)).use { db ->
                assertEquals(listOf(listOf(1L, "Each")), db.selectRows("SELECT id, sides FROM exercise"))
                assertEquals(listOf(listOf(7L, "Legs")), db.selectRows("SELECT id, name FROM workout_day"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM guided_plan"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM guided_log"))
                db.execSQL(
                    "INSERT INTO guided_plan (id, contentId, kind, title, areas, minutes, transitionSeconds, notes, pinnedAtMillis, " +
                        "edited, createdAtMillis) VALUES (3, 'wrist-reset', 'RECOVERY', 'Wrist reset', 'wrists', 6, 5, '', NULL, 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO guided_step (id, planId, position, exerciseId, name, durationSeconds, reps, sides, cue, image, rounds) " +
                        "VALUES (1, 3, 0, 1, NULL, 30, NULL, NULL, NULL, NULL, 2)",
                )
                db.execSQL("INSERT INTO guided_source (planId, position, title, url) VALUES (3, 0, 'NHS', NULL)")
                db.execSQL(
                    "INSERT INTO guided_log (id, planId, kind, title, day, startedAtMillis, endedAtMillis, activeMillis, outcome, " +
                        "stepsDone) VALUES (1, 3, 'RECOVERY', 'Wrist reset', '2026-11-09', 10, 20, 10, 'COMPLETED', 9)",
                )
                // Steps and sources go with their plan; the log stays, with its title, and loses the plan (SET NULL).
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("DELETE FROM guided_plan WHERE id = 3")
                assertEquals(emptyList(), db.selectRows("SELECT id FROM guided_step"))
                assertEquals(emptyList(), db.selectRows("SELECT planId FROM guided_source"))
                assertEquals(listOf(listOf(null, "Wrist reset")), db.selectRows("SELECT planId, title FROM guided_log"))
            }
        }

    @Test
    fun `migrating 1 to 8 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            val all = listOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            helper.runMigrationsAndValidate(8, all).use { db ->
                assertEquals(listOf(listOf(4L, "Walk")), db.selectRows("SELECT id, name FROM exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM guided_plan"))
            }
        }

    @Test
    fun `migrating 8 to 9 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(8).close()
            helper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9)).close()
        }

    @Test
    fun `migrating 8 to 9 keeps sessions and their logs, with no lessons, and a lesson outlives its drill`(): Unit =
        runBlocking {
            helper.createDatabase(8).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis,
                        equipment)
                    VALUES (1, 'push-up', 'Push-up', 'Chest', 0, 1, 0, 0, 'Both', 'Kg', 'Km', 3, 15, NULL,
                        75, NULL, 40, 0.0, '', '', 0, 0, 1, 'bodyweight')
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT INTO guided_plan (id, contentId, kind, title, areas, minutes, transitionSeconds, notes, pinnedAtMillis, " +
                        "edited, createdAtMillis) VALUES (3, 'wrist-reset', 'RECOVERY', 'Wrist reset', 'wrists', 6, 5, '', 7, 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO guided_log (id, planId, kind, title, day, startedAtMillis, endedAtMillis, activeMillis, outcome, " +
                        "stepsDone) VALUES (1, 3, 'RECOVERY', 'Wrist reset', '2026-11-09', 10, 20, 10, 'COMPLETED', 9)",
                )
            }
            helper.runMigrationsAndValidate(9, listOf(MIGRATION_8_9)).use { db ->
                assertEquals(listOf(listOf(3L, "Wrist reset", 7L)), db.selectRows("SELECT id, title, pinnedAtMillis FROM guided_plan"))
                assertEquals(listOf(listOf(1L, 3L)), db.selectRows("SELECT id, planId FROM guided_log"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM lesson"))
                db.execSQL(
                    "INSERT INTO guided_plan (id, contentId, kind, title, areas, minutes, transitionSeconds, notes, pinnedAtMillis, " +
                        "edited, createdAtMillis) VALUES (4, NULL, 'PRACTICE', 'Push-up', '', 0, 20, '', NULL, 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO lesson (id, contentId, title, body, practicePlanId, createdAtMillis) " +
                        "VALUES (5, 'push-up', 'Push-up', '## Setup', 4, 1)",
                )
                db.execSQL("INSERT INTO lesson_exercise (lessonId, position, exerciseId) VALUES (5, 0, 1)")
                db.execSQL("INSERT INTO lesson_source (lessonId, position, title, url) VALUES (5, 0, 'E3 Rehab', NULL)")
                db.execSQL("PRAGMA foreign_keys = ON")
                // The drill's plan going leaves the lesson without one (SET NULL).
                db.execSQL("DELETE FROM guided_plan WHERE id = 4")
                assertEquals(listOf(listOf(5L, null)), db.selectRows("SELECT id, practicePlanId FROM lesson"))
                // Its exercises and sources go with the lesson.
                db.execSQL("DELETE FROM lesson WHERE id = 5")
                assertEquals(emptyList(), db.selectRows("SELECT lessonId FROM lesson_exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT lessonId FROM lesson_source"))
                assertEquals(listOf(listOf(1L)), db.selectRows("SELECT id FROM exercise"))
            }
        }

    @Test
    fun `migrating 1 to 9 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            val all =
                listOf(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                )
            helper.runMigrationsAndValidate(9, all).use { db ->
                assertEquals(listOf(listOf(4L, "Walk")), db.selectRows("SELECT id, name FROM exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT id FROM lesson"))
            }
        }

    @Test
    fun `migrating 9 to 10 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(9).close()
            helper.runMigrationsAndValidate(10, listOf(MIGRATION_9_10)).close()
        }

    @Test
    fun `migrating 9 to 10 keeps lessons as built-in ones, with no imported images`(): Unit =
        runBlocking {
            helper.createDatabase(9).use { db ->
                db.execSQL(
                    "INSERT INTO guided_plan (id, contentId, kind, title, areas, minutes, transitionSeconds, notes, pinnedAtMillis, " +
                        "edited, createdAtMillis) VALUES (4, NULL, 'PRACTICE', 'Push-up', '', 0, 20, '', NULL, 0, 1)",
                )
                db.execSQL(
                    "INSERT INTO lesson (id, contentId, title, body, practicePlanId, createdAtMillis) " +
                        "VALUES (5, 'push-up', 'Push-up', '## Setup', 4, 1)",
                )
                db.execSQL("INSERT INTO content_tombstone (kind, contentId) VALUES ('routine', 'legs')")
            }
            helper.runMigrationsAndValidate(10, listOf(MIGRATION_9_10)).use { db ->
                assertEquals(
                    listOf(listOf(5L, "push-up", "Push-up", 4L, 0L)),
                    db.selectRows("SELECT id, contentId, title, practicePlanId, edited FROM lesson"),
                )
                assertEquals(listOf(listOf<Any?>("routine", "legs")), db.selectRows("SELECT kind, contentId FROM content_tombstone"))
                assertEquals(emptyList(), db.selectRows("SELECT path FROM imported_image"))
            }
        }

    @Test
    fun `migrating 10 to 11 matches the exported schema`(): Unit =
        runBlocking {
            helper.createDatabase(10).close()
            helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).close()
        }

    @Test
    fun `migrating 10 to 11 keeps the sessions, with no reminders, and a reminder goes with its session`(): Unit =
        runBlocking {
            helper.createDatabase(10).use { db ->
                db.execSQL(
                    "INSERT INTO guided_plan (id, contentId, kind, title, areas, minutes, transitionSeconds, notes, pinnedAtMillis, " +
                        "edited, createdAtMillis) VALUES (4, 'wrist-reset', 'RECOVERY', 'Wrist reset', 'wrists', 6, 5, '', 7, 0, 1)",
                )
            }
            helper.runMigrationsAndValidate(11, listOf(MIGRATION_10_11)).use { db ->
                assertEquals(listOf(listOf(4L, "Wrist reset", 7L)), db.selectRows("SELECT id, title, pinnedAtMillis FROM guided_plan"))
                assertEquals(emptyList(), db.selectRows("SELECT planId FROM reminder"))
                db.execSQL("PRAGMA foreign_keys = ON")
                db.execSQL("INSERT INTO reminder (planId, minuteOfDay, enabled, shownOn) VALUES (4, 540, 1, NULL)")
                db.execSQL("DELETE FROM guided_plan WHERE id = 4")
                assertEquals(emptyList(), db.selectRows("SELECT planId FROM reminder"))
            }
        }

    @Test
    fun `migrating 1 to 11 keeps the exercises`(): Unit =
        runBlocking {
            helper.createDatabase(1).use { db ->
                db.execSQL(
                    """
                    INSERT INTO exercise (id, contentId, name, category, tracksWeight, tracksReps, tracksTime, tracksDistance,
                        sides, weightUnit, distanceUnit, defaultSets, defaultReps, defaultRepsMax, defaultRestSeconds,
                        defaultRestMaxSeconds, defaultDurationSeconds, defaultWeight, notes, videoUrl, archived, edited, createdAtMillis)
                    VALUES (4, 'walk', 'Walk', 'Cardio', 0, 0, 1, 1, 'Both', 'Kg', 'Mi', 1, 10, NULL, 0, NULL, 1800, 0.0, '', '', 0, 0, 2)
                    """.trimIndent(),
                )
            }
            val all =
                listOf(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11,
                )
            helper.runMigrationsAndValidate(11, all).use { db ->
                assertEquals(listOf(listOf(4L, "Walk")), db.selectRows("SELECT id, name FROM exercise"))
                assertEquals(emptyList(), db.selectRows("SELECT path FROM imported_image"))
                assertEquals(emptyList(), db.selectRows("SELECT planId FROM reminder"))
            }
        }

    @Test
    fun `the latest exported schema matches the entities`(): Unit =
        runBlocking {
            helper.createDatabase(DATABASE_VERSION).close()
            // Room refuses to open a file whose schema differs from the entities.
            val database =
                buildAppDatabase(
                    Room.databaseBuilder<AppDatabase>(file.toString()),
                    AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default),
                )
            assertEquals(emptyList(), database.exerciseDao().all().first())
            database.close()
        }
}
