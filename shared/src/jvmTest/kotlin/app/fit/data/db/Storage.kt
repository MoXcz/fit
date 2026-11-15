// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.sqlite.SQLITE_DATA_BLOB
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import app.fit.backup.LiveSessions
import app.fit.backup.RoomBackups
import app.fit.bodyweight.RoomBodyWeightRepository
import app.fit.content.BuiltInContent
import app.fit.content.BuiltInContentSync
import app.fit.content.ContentFile
import app.fit.content.ContentStateStore
import app.fit.content.ImageProbe
import app.fit.content.RoomContentImporter
import app.fit.content.StoredContentImages
import app.fit.core.AppDispatchers
import app.fit.core.DataResets
import app.fit.core.SessionTimers
import app.fit.core.ZoneSource
import app.fit.data.prefs.DataStoreContentStateStore
import app.fit.data.prefs.DataStoreGuidedStateStore
import app.fit.data.prefs.DataStoreWorkoutStateStore
import app.fit.data.prefs.SETTINGS_FILE_NAME
import app.fit.data.prefs.createSettingsDataStore
import app.fit.day.FixedClock
import app.fit.day.RoomDayRepository
import app.fit.exercises.RoomExerciseRepository
import app.fit.guided.FakeArchives
import app.fit.guided.FakeGuidedAlerts
import app.fit.guided.GuidedController
import app.fit.guided.RoomGuidedRepository
import app.fit.lessons.RoomLessonRepository
import app.fit.programs.RoomProgramRepository
import app.fit.reminders.FakeReminderAlarms
import app.fit.reminders.FakeReminderNotices
import app.fit.reminders.Reminders
import app.fit.reminders.RoomReminderRepository
import app.fit.routines.RoomRoutineRepository
import app.fit.settings.DataStoreSettingsRepository
import app.fit.workout.FakeWakeupScheduler
import app.fit.workout.FakeWorkoutAlerts
import app.fit.workout.WorkoutController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.nio.file.Files
import kotlin.time.Instant

class FakeBuiltIn(
    var list: List<ContentFile> = emptyList(),
    var images: Map<String, ByteArray> = emptyMap(),
) : BuiltInContent {
    // Every read throws while set, as an unreadable asset would.
    var failing = false

    override suspend fun files(): List<ContentFile> {
        if (failing) throw IllegalStateException("unreadable")
        return list
    }

    override suspend fun imagePaths() = images.keys

    override suspend fun image(path: String) = images[path]
}

// The app's real storage in a temp folder: Room, DataStore, every repository, both controllers, the
// importer and the backups, with fakes only for the platform (bundled files, zip, alarms, alerts).
class Storage(
    wrapState: (ContentStateStore) -> ContentStateStore = { it },
) {
    private val dir = Files.createTempDirectory("fit-storage")
    val dispatchers = AppDispatchers(io = Dispatchers.IO, default = Dispatchers.Default)
    val clock = FixedClock(Instant.parse("2026-11-11T10:00:00Z"))
    val today = LocalDate(2026, 11, 11)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = buildAppDatabase(Room.databaseBuilder<AppDatabase>(dir.resolve("fit.db").toString()), dispatchers)
    val dataStore = createSettingsDataStore { dir.resolve(SETTINGS_FILE_NAME).toString() }
    val settings = DataStoreSettingsRepository(dataStore)
    val builtIn = FakeBuiltIn()
    val archives = FakeArchives()
    val exercises = RoomExerciseRepository(database.exerciseDao(), clock)
    val routines = RoomRoutineRepository(database.routineDao(), database.exerciseDao(), exercises, settings, clock)
    val programs = RoomProgramRepository(database.programDao(), clock, TimeZone.UTC)
    val days = RoomDayRepository(database.dayDao(), exercises, routines, programs, settings, clock, TimeZone.UTC)
    val bodyWeights = RoomBodyWeightRepository(database.bodyWeightDao())
    val guided = RoomGuidedRepository(database.guidedDao(), exercises, clock)
    val lessons = RoomLessonRepository(database.lessonDao(), exercises, clock)
    val contentState = wrapState(DataStoreContentStateStore(dataStore))
    val sync = BuiltInContentSync(builtIn, contentState, exercises, routines, programs, guided, lessons)
    val images = StoredContentImages(builtIn, database.imageDao())
    val importer =
        RoomContentImporter(
            database,
            database.exerciseDao(),
            database.routineDao(),
            database.programDao(),
            database.guidedDao(),
            database.lessonDao(),
            database.imageDao(),
            builtIn,
            images,
            archives,
            // A picture is anything but the bytes "broken".
            ImageProbe { !it.contentEquals("broken".encodeToByteArray()) },
            dispatchers,
            clock,
        )
    val wakeups = FakeWakeupScheduler()
    val alerts = FakeWorkoutAlerts()
    val timers = SessionTimers()
    val resets = DataResets()
    val workoutStore = DataStoreWorkoutStateStore(dataStore)
    val guidedStore = DataStoreGuidedStateStore(dataStore)
    val workout = WorkoutController(days, settings, workoutStore, wakeups, alerts, timers, clock, TimeZone.UTC, scope)
    val player =
        GuidedController(guided, days, settings, guidedStore, wakeups, FakeGuidedAlerts(), timers, clock, TimeZone.UTC, scope)
    val reminderRepository = RoomReminderRepository(database.reminderDao())
    val reminderAlarms = FakeReminderAlarms()
    val reminderNotices = FakeReminderNotices()
    val reminders =
        Reminders(reminderRepository, guided, player, reminderAlarms, reminderNotices, clock, ZoneSource { TimeZone.UTC }, scope)
    val backups =
        RoomBackups(
            database,
            archives,
            dataStore,
            settings,
            contentState,
            sync,
            LiveSessions(workout, player),
            reminders,
            resets,
            dispatchers,
            clock,
        )

    fun close() {
        scope.cancel()
        database.close()
    }

    suspend fun tables(): List<String> =
        database.useReaderConnection { connection ->
            connection.usePrepared(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name",
            ) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }
        }

    // Every row of every table, as plain values.
    suspend fun dump(): Map<String, List<List<Any?>>> = tables().associateWith { rows("SELECT * FROM `$it` ORDER BY rowid") }

    suspend fun execute(sql: String) = database.useWriterConnection { it.executeSQL(sql) }

    suspend fun rows(sql: String): List<List<Any?>> =
        database.useReaderConnection { connection ->
            connection.usePrepared(sql) { statement ->
                buildList {
                    while (statement.step()) {
                        add(
                            (0 until statement.getColumnCount()).map { i ->
                                when (statement.getColumnType(i)) {
                                    SQLITE_DATA_NULL -> null
                                    SQLITE_DATA_TEXT -> statement.getText(i)
                                    SQLITE_DATA_FLOAT -> statement.getDouble(i)
                                    SQLITE_DATA_BLOB -> statement.getBlob(i).toList()
                                    else -> statement.getLong(i)
                                }
                            },
                        )
                    }
                }
            }
        }
}

// Content files for the tests, as an agent would write them.
object Files10 {
    fun exercise(
        id: String,
        name: String,
        extra: String = "",
    ) = "exercises/$id.md" to "---\nformat: 1\nkind: exercise\nid: $id\nname: $name\n$extra---\nNotes for $name.\n"

    fun routine(
        id: String,
        name: String,
        exercise: String,
    ) = "routines/$id.md" to
        "---\nformat: 1\nkind: routine\nid: $id\nname: $name\nitems:\n  - { exercise: $exercise, sets: 2 }\nsources: [{ title: Me }]\n---\n"

    fun program(
        id: String,
        name: String,
        routine: String,
    ) = "programs/$id.md" to "---\nformat: 1\nkind: program\nid: $id\nname: $name\ndays: { mon: $routine }\n---\n"

    // One 60 s step: 5 s to get ready, so about a minute.
    fun recovery(
        id: String,
        title: String,
        step: String = "{ name: Shake out, duration: 60 }",
    ) = "recovery/$id.md" to
        "---\nformat: 1\nkind: recovery\nid: $id\ntitle: $title\nareas: [wrists]\nminutes: 1\nsteps:\n  - $step\n" +
        "sources: [{ title: NHS, url: \"https://www.nhs.uk/\" }]\n---\nStop if it hurts.\n"

    fun lesson(
        id: String,
        title: String,
        exercise: String,
        body: String = "## Setup\n\nHands under shoulders.\n",
    ) = "lessons/$id.md" to
        "---\nformat: 1\nkind: lesson\nid: $id\ntitle: $title\nexercises: [$exercise]\npractice:\n  steps:\n" +
        "    - { name: Wall version, reps: 5 }\nsources: [{ title: Me }]\n---\n$body"
}
