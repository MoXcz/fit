// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.ConstructedBy
import androidx.room3.Database
import androidx.room3.RoomDatabase
import androidx.room3.RoomDatabaseConstructor
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import app.fit.core.AppDispatchers

const val DATABASE_FILE_NAME = "fit.db"
const val DATABASE_VERSION = 11

@Database(
    entities = [
        ExerciseEntity::class,
        ExerciseRefEntity::class,
        ExerciseAreaEntity::class,
        ExerciseImageEntity::class,
        RoutineEntity::class,
        RoutineItemEntity::class,
        ContentTombstoneEntity::class,
        WorkoutDayEntity::class,
        BlockEntity::class,
        SetEntryEntity::class,
        ProgramEntity::class,
        ProgramDayEntity::class,
        BodyWeightEntity::class,
        GuidedPlanEntity::class,
        GuidedStepEntity::class,
        GuidedSourceEntity::class,
        GuidedLogEntity::class,
        LessonEntity::class,
        LessonExerciseEntity::class,
        LessonSourceEntity::class,
        ImportedImageEntity::class,
        ReminderEntity::class,
    ],
    version = DATABASE_VERSION,
)
@ConstructedBy(AppDatabaseConstructor::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun exerciseDao(): ExerciseDao

    abstract fun routineDao(): RoutineDao

    abstract fun dayDao(): DayDao

    abstract fun programDao(): ProgramDao

    abstract fun bodyWeightDao(): BodyWeightDao

    abstract fun guidedDao(): GuidedDao

    abstract fun lessonDao(): LessonDao

    abstract fun imageDao(): ImageDao

    abstract fun reminderDao(): ReminderDao
}

@Suppress("KotlinNoActualForExpect")
expect object AppDatabaseConstructor : RoomDatabaseConstructor<AppDatabase> {
    override fun initialize(): AppDatabase
}

// Same driver on every target; only the file location ([builder]) is platform code. Every schema
// change gets a migration, never a destructive fallback. Built-in content is loaded by the content
// sync, not seeded here.
fun buildAppDatabase(
    builder: RoomDatabase.Builder<AppDatabase>,
    dispatchers: AppDispatchers,
): AppDatabase =
    builder
        .setDriver(BundledSQLiteDriver())
        .setQueryCoroutineContext(dispatchers.io)
        .addMigrations(
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
        ).build()
