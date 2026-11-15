// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

// Version 2 adds routines. Frozen: it must keep producing version 2's schema. Built-in routines
// arrive with the next content sync, not from here.
val MIGRATION_1_2 =
    object : Migration(1, 2) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contentId` TEXT, " +
                    "`name` TEXT NOT NULL COLLATE NOCASE, `notes` TEXT NOT NULL, `edited` INTEGER NOT NULL, " +
                    "`createdAtMillis` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routine_contentId` ON `routine` (`contentId`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_routine_name` ON `routine` (`name`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `routine_item` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`routineId` INTEGER NOT NULL, `exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`section` TEXT NOT NULL, `sets` INTEGER NOT NULL, `reps` INTEGER NOT NULL, `repsMax` INTEGER, " +
                    "`restSeconds` INTEGER NOT NULL, `restMaxSeconds` INTEGER, `durationSeconds` INTEGER NOT NULL, " +
                    "`tracksWeight` INTEGER, `tracksReps` INTEGER, `tracksTime` INTEGER, `tracksDistance` INTEGER, " +
                    "`progressStep` REAL, `progressRpe` REAL, `warmupSets` INTEGER NOT NULL, `notes` TEXT NOT NULL, " +
                    "FOREIGN KEY(`routineId`) REFERENCES `routine`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_item_routineId` ON `routine_item` (`routineId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_routine_item_exerciseId` ON `routine_item` (`exerciseId`)")
        }
    }

// Version 3 remembers deleted built-in items, so content updates don't bring them back.
val MIGRATION_2_3 =
    object : Migration(2, 3) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `content_tombstone` (`kind` TEXT NOT NULL, `contentId` TEXT NOT NULL, " +
                    "PRIMARY KEY(`kind`, `contentId`))",
            )
        }
    }

// Version 4 adds workout days, their blocks and sets. Frozen, like the others.
val MIGRATION_3_4 =
    object : Migration(3, 4) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `workout_day` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `day` TEXT NOT NULL, " +
                    "`name` TEXT NOT NULL, `notes` TEXT NOT NULL, `startedAtMillis` INTEGER, `finishedAtMillis` INTEGER)",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_day_day` ON `workout_day` (`day`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `block` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `dayId` INTEGER NOT NULL, " +
                    "`exerciseId` INTEGER NOT NULL, `position` INTEGER NOT NULL, `section` TEXT NOT NULL, " +
                    "`restSeconds` INTEGER NOT NULL, " +
                    "`restMaxSeconds` INTEGER, `durationSeconds` INTEGER NOT NULL, `tracksWeight` INTEGER, `tracksReps` INTEGER, " +
                    "`tracksTime` INTEGER, `tracksDistance` INTEGER, `warmupSets` INTEGER NOT NULL, `notes` TEXT NOT NULL, " +
                    "FOREIGN KEY(`dayId`) REFERENCES `workout_day`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_block_dayId` ON `block` (`dayId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_block_exerciseId` ON `block` (`exerciseId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `set_entry` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `blockId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `warmup` INTEGER NOT NULL, `targetReps` INTEGER NOT NULL, `targetRepsMax` INTEGER, " +
                    "`targetDurationSeconds` INTEGER, `weight` REAL, `weightUnit` TEXT NOT NULL, `reps` INTEGER, " +
                    "`durationSeconds` INTEGER, " +
                    "`distance` REAL, `distanceUnit` TEXT NOT NULL, `rpe` REAL, `doneAtMillis` INTEGER, " +
                    "FOREIGN KEY(`blockId`) REFERENCES `block`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_set_entry_blockId` ON `set_entry` (`blockId`)")
        }
    }

// Version 5 says what an exercise is done with (the content format's `equipment`). Frozen, like the others.
val MIGRATION_4_5 =
    object : Migration(4, 5) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `exercise` ADD COLUMN `equipment` TEXT")
        }
    }

// Version 6 keeps how spent a workout left you (the summary's fatigue). Frozen, like the others.
val MIGRATION_5_6 =
    object : Migration(5, 6) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `workout_day` ADD COLUMN `fatigue` INTEGER")
        }
    }

// Version 7 adds programs, body weight, deload days and bodyweight sets. Frozen, like the others.
val MIGRATION_6_7 =
    object : Migration(6, 7) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `program` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contentId` TEXT, " +
                    "`name` TEXT NOT NULL COLLATE NOCASE, `notes` TEXT NOT NULL, `active` INTEGER NOT NULL, `startsOn` TEXT, " +
                    "`deloadEvery` INTEGER, `deloadPercent` INTEGER NOT NULL, `edited` INTEGER NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_program_contentId` ON `program` (`contentId`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_program_name` ON `program` (`name`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `program_day` (`programId` INTEGER NOT NULL, `weekday` INTEGER NOT NULL, " +
                    "`routineId` INTEGER, PRIMARY KEY(`programId`, `weekday`), " +
                    "FOREIGN KEY(`programId`) REFERENCES `program`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`routineId`) REFERENCES `routine`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_program_day_routineId` ON `program_day` (`routineId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `bodyweight` (`day` TEXT NOT NULL, `weight` REAL NOT NULL, `unit` TEXT NOT NULL, " +
                    "PRIMARY KEY(`day`))",
            )
            connection.execSQL("ALTER TABLE `workout_day` ADD COLUMN `deload` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL("ALTER TABLE `set_entry` ADD COLUMN `bodyweight` INTEGER NOT NULL DEFAULT 0")
        }
    }

// Version 8 adds guided plans (recovery sessions), their steps and sources, and the log of their
// runs. Frozen, like the others.
val MIGRATION_7_8 =
    object : Migration(7, 8) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `guided_plan` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contentId` TEXT, " +
                    "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `areas` TEXT NOT NULL, `minutes` INTEGER NOT NULL, " +
                    "`transitionSeconds` INTEGER NOT NULL, `notes` TEXT NOT NULL, `pinnedAtMillis` INTEGER, `edited` INTEGER NOT NULL, " +
                    "`createdAtMillis` INTEGER NOT NULL)",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_guided_plan_contentId` ON `guided_plan` (`contentId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `guided_step` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER NOT NULL, " +
                    "`position` INTEGER NOT NULL, `exerciseId` INTEGER, `name` TEXT, `durationSeconds` INTEGER, `reps` INTEGER, " +
                    "`sides` TEXT, `cue` TEXT, `image` TEXT, `rounds` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`planId`) REFERENCES `guided_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_guided_step_planId` ON `guided_step` (`planId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_guided_step_exerciseId` ON `guided_step` (`exerciseId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `guided_source` (`planId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `url` TEXT, PRIMARY KEY(`planId`, `position`), " +
                    "FOREIGN KEY(`planId`) REFERENCES `guided_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `guided_log` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `planId` INTEGER, " +
                    "`kind` TEXT NOT NULL, `title` TEXT NOT NULL, `day` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, " +
                    "`endedAtMillis` INTEGER NOT NULL, `activeMillis` INTEGER NOT NULL, `outcome` TEXT NOT NULL, " +
                    "`stepsDone` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`planId`) REFERENCES `guided_plan`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_guided_log_planId` ON `guided_log` (`planId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_guided_log_day` ON `guided_log` (`day`)")
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_guided_log_startedAtMillis` ON `guided_log` (`startedAtMillis`)")
        }
    }

// Version 9 adds lessons, the exercises they teach and their sources; a lesson's drill is a
// guided_plan of kind PRACTICE. Frozen: it must keep producing version 9's schema.
val MIGRATION_8_9 =
    object : Migration(8, 9) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `lesson` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `contentId` TEXT, " +
                    "`title` TEXT NOT NULL, `body` TEXT NOT NULL, `practicePlanId` INTEGER, `createdAtMillis` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`practicePlanId`) REFERENCES `guided_plan`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL )",
            )
            connection.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_lesson_contentId` ON `lesson` (`contentId`)")
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_lesson_practicePlanId` ON `lesson` (`practicePlanId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `lesson_exercise` (`lessonId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`exerciseId` INTEGER NOT NULL, PRIMARY KEY(`lessonId`, `position`), " +
                    "FOREIGN KEY(`lessonId`) REFERENCES `lesson`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , " +
                    "FOREIGN KEY(`exerciseId`) REFERENCES `exercise`(`id`) ON UPDATE NO ACTION ON DELETE NO ACTION )",
            )
            connection.execSQL("CREATE INDEX IF NOT EXISTS `index_lesson_exercise_exerciseId` ON `lesson_exercise` (`exerciseId`)")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `lesson_source` (`lessonId` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                    "`title` TEXT NOT NULL, `url` TEXT, PRIMARY KEY(`lessonId`, `position`), " +
                    "FOREIGN KEY(`lessonId`) REFERENCES `lesson`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }

// 0.0.11: imported content. A lesson can be the user's own (content updates skip it), and a pack's
// images are stored with the items that use them.
val MIGRATION_9_10 =
    object : Migration(9, 10) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL("ALTER TABLE `lesson` ADD COLUMN `edited` INTEGER NOT NULL DEFAULT 0")
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `imported_image` (`path` TEXT NOT NULL, `data` BLOB NOT NULL, PRIMARY KEY(`path`))",
            )
        }
    }

// 0.0.12: a recovery session's daily reminder. Frozen, like the others.
val MIGRATION_10_11 =
    object : Migration(10, 11) {
        override suspend fun migrate(connection: SQLiteConnection) {
            connection.execSQL(
                "CREATE TABLE IF NOT EXISTS `reminder` (`planId` INTEGER NOT NULL, `minuteOfDay` INTEGER NOT NULL, " +
                    "`enabled` INTEGER NOT NULL, `shownOn` TEXT, PRIMARY KEY(`planId`), " +
                    "FOREIGN KEY(`planId`) REFERENCES `guided_plan`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
            )
        }
    }
