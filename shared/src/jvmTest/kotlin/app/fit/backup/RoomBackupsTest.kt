// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup

import app.fit.content.ArchiveEntry
import app.fit.content.ArchiveResult
import app.fit.content.ContentFile
import app.fit.content.ContentStateStore
import app.fit.content.ImportPreview
import app.fit.core.WeightUnit
import app.fit.data.db.DATABASE_VERSION
import app.fit.data.db.Files10
import app.fit.data.db.ImportedImageEntity
import app.fit.data.db.Storage
import app.fit.guided.FakeArchives
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedLog
import app.fit.guided.GuidedOutcome
import app.fit.settings.Settings
import app.fit.workout.WorkoutSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.lang.reflect.Modifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

// Lets a test stand for the process dying right after a restore's rows are committed: the first
// thing written after them is the backup's fingerprint.
private class DyingState(
    private val real: ContentStateStore,
) : ContentStateStore by real {
    var dieOnFingerprint = false

    override suspend fun setLoadedFingerprint(fingerprint: String?) {
        if (dieOnFingerprint && fingerprint != null) throw IllegalStateException("process died")
        real.setLoadedFingerprint(fingerprint)
    }
}

// Backup, restore and the wipes over the real database and settings file.
class RoomBackupsTest {
    private lateinit var state: DyingState
    private val s = Storage { DyingState(it).also { wrapped -> state = wrapped } }

    @AfterTest
    fun close() = s.close()

    private val custom =
        Settings(WeightUnit.Lb, barKg = 15.0, barLb = 35.0, alarm = false, keepScreenOn = false, autoAdvance = false)

    // A row in every table: built-in and imported content with an image, a deleted built-in routine,
    // a program, a workout day with a done set, recovery and practice logs, body weight, a reminder.
    private suspend fun fill() {
        s.builtIn.list =
            listOf(
                Files10.exercise("curl", "Curl", "areas: [elbows]\nrefs:\n  - { title: A guide, url: \"https://example.org/\" }\n"),
                Files10.routine("old", "Old routine", "curl"),
            ).map { ContentFile(it.first, it.second) }
        s.sync.syncIfChanged()
        s.routines.delete(
            s.routines.routines
                .first()
                .single()
                .id,
        )
        val pack =
            FakeArchives.zip(
                Files10.exercise("plank", "Plank", "tracks: [time]\nimages: [images/plank.png]\n"),
                Files10.routine("legs", "Legs", "curl"),
                Files10.program("split", "Split", "legs"),
                Files10.recovery("wrist-reset", "Wrist reset"),
                Files10.lesson("plank", "Plank basics", "plank"),
                "images/plank.png" to "png bytes",
            )
        s.importer.import(assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", pack)))
        val routine =
            s.routines.routines
                .first()
                .single()
        val program =
            s.programs.programs
                .first()
                .single()
        s.programs.setActive(program.id, true)
        assertTrue(s.days.applyRoutine(s.today, routine.id, replace = false))
        val set =
            s.days
                .day(s.today)
                .first()!!
                .blocks
                .first()
                .sets
                .first()
        s.days.setDone(set.id, true)
        s.bodyWeights.set(s.today, 81.5, WeightUnit.Kg)
        val plan =
            s.guided
                .plans(GuidedKind.Recovery)
                .first()
                .single()
        s.guided.setPinned(plan.id, true)
        s.reminders.set(plan.id, LocalTime(7, 30), enabled = true)
        val start = s.clock.now()
        s.guided.log(GuidedLog(plan.id, GuidedKind.Recovery, plan.title, s.today, start, start + 1.minutes, GuidedOutcome.Completed, 1))
        s.settings.replace(custom)
    }

    private suspend fun export(): ByteArray = assertIs<BackupExport.File>(s.backups.export()).bytes

    private fun entries(zip: ByteArray): List<ArchiveEntry> = (s.archives.read(zip, BackupFormat.LIMITS) as ArchiveResult.Entries).entries

    private fun json(zip: ByteArray): String = entries(zip).single { it.name == BackupFormat.FILE }.bytes.decodeToString()

    // The same backup with its JSON text changed.
    private fun edited(
        zip: ByteArray,
        change: (String) -> String,
    ): ByteArray =
        s.archives.write(
            entries(zip).map {
                if (it.name == BackupFormat.FILE) ArchiveEntry(it.name, change(it.bytes.decodeToString()).encodeToByteArray()) else it
            },
        )

    // The same backup with one column of a table's first row changed.
    private fun withValue(
        zip: ByteArray,
        table: String,
        column: String,
        value: JsonElement,
    ): ByteArray =
        edited(zip) { text ->
            val root = Json.parseToJsonElement(text).jsonObject
            val tables = root.getValue("tables").jsonObject
            val rows = tables.getValue(table).jsonArray
            val first = JsonObject(rows.first().jsonObject + (column to value))
            val changed = JsonObject(tables + (table to JsonArray(listOf(first) + rows.drop(1))))
            Json.encodeToString(JsonElement.serializer(), JsonObject(root + ("tables" to changed)))
        }

    private suspend fun restore(zip: ByteArray) = assertTrue(s.backups.restore(assertIs<BackupCheck.Valid>(s.backups.check(zip)).backup))

    // Everything every screen reads; it throws if a stored value can't be read.
    private suspend fun readEverything() {
        val exercises = s.exercises.exercises.first()
        s.routines.routines.first()
        s.programs.programs.first()
        s.programs.active.first()
        s.days.day(s.today).first()
        s.days.trainedDays().first()
        exercises.forEach { s.days.exerciseSets(it.id).first() }
        s.bodyWeights.readings.first()
        GuidedKind.entries.forEach { kind ->
            s.guided.plans(kind).first()
            s.guided.logs(kind).first()
            s.guided.logs(kind, s.today, s.today).first()
        }
        s.guided.pinned.first()
        s.lessons.lessons.first()
        s.reminderRepository.all()
    }

    @Test
    fun `the fixture fills every table, so one added later fails here until backup, restore and the wipes cover it`(): Unit =
        runBlocking {
            fill()
            assertEquals(emptySet(), s.dump().filterValues { it.isEmpty() }.keys)
        }

    @Test
    fun `a backup holds every table and every setting, with the imported images as files it lists`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            val root = assertNotNull(BackupFormat.read(json(zip)))
            assertEquals(JsonPrimitive(1), root["format"])
            assertEquals(JsonPrimitive(DATABASE_VERSION), root["schema"])
            val tables = root.getValue("tables").jsonObject
            assertEquals(s.tables().toSet() - BackupFormat.IMAGE_TABLE, tables.keys)
            s.dump().forEach { (table, rows) ->
                if (table != BackupFormat.IMAGE_TABLE) assertEquals(rows.size, tables.getValue(table).jsonArray.size, table)
            }
            // One key per field of Settings: a setting added later fails here until the backup carries it.
            val fields = Settings::class.java.declaredFields.count { !Modifier.isStatic(it.modifiers) }
            assertEquals(fields, root.getValue("settings").jsonObject.size)
            assertEquals(custom, BackupFormat.settings(root["settings"]))
            assertEquals(listOf("backup.json", "images/plank.png"), entries(zip).map { it.name })
            assertEquals("[{\"path\":\"images/plank.png\",\"size\":9}]", root["images"].toString())
        }

    @Test
    fun `a backup, wipe everything and restore give back identical data, images and settings`(): Unit =
        runBlocking {
            fill()
            val before = s.dump()
            val zip = export()

            val alarm = assertNotNull(s.reminderAlarms.scheduled)
            s.backups.wipeEverything()
            val wiped = s.dump()
            assertNotEquals(before, wiped)
            assertEquals(Settings(), s.settings.settings.first())
            // No reminder is left, so no alarm is.
            assertNull(s.reminderAlarms.scheduled)

            val check = assertIs<BackupCheck.Valid>(s.backups.check(zip))
            assertEquals(s.clock.now(), check.backup.exportedAt)
            // Checking it wrote nothing, in any table.
            assertEquals(wiped, s.dump())
            assertEquals(Settings(), s.settings.settings.first())
            assertTrue(s.backups.restore(check.backup))

            assertEquals(before, s.dump())
            assertEquals(custom, s.settings.settings.first())
            // The restored reminder is scheduled again.
            assertEquals(alarm, s.reminderAlarms.scheduled)
            assertContentEquals("png bytes".encodeToByteArray(), s.images.load("images/plank.png"))
            // The built-in routine deleted before the backup is still deleted.
            assertEquals(
                listOf("Legs"),
                s.routines.routines
                    .first()
                    .map { it.name },
            )
            // A backup of the restored app is the same file.
            assertEquals(json(zip), json(export()))
        }

    @Test
    fun `a backup written from schema 10 still restores`(): Unit =
        runBlocking {
            // Committed when schema 10 was current. A later schema must read it (BackupFormat says how),
            // or old backups would turn into "not a Fit backup" without anyone noticing.
            fun resource(name: String) = checkNotNull(javaClass.getResourceAsStream("/backups/schema-10/$name")) { name }.readBytes()
            val zip =
                s.archives.write(
                    listOf(ArchiveEntry("backup.json", resource("backup.json")), ArchiveEntry("images/plank.png", resource("plank.png"))),
                )
            restore(zip)
            assertEquals(
                listOf("Curl", "Plank"),
                s.exercises.exercises
                    .first()
                    .map { it.fields.name },
            )
            assertEquals(
                listOf("Legs"),
                s.routines.routines
                    .first()
                    .map { it.name },
            )
            assertEquals(
                81.5,
                s.bodyWeights.readings
                    .first()
                    .single()
                    .weight,
            )
            assertEquals(
                1,
                s.days
                    .trainedDays()
                    .first()
                    .size,
            )
            // Schema 10 had no reminders.
            assertEquals(custom, s.settings.settings.first())
            assertEquals(emptyList(), s.reminderRepository.all())
            assertNull(s.reminderAlarms.scheduled)
            assertContentEquals("png bytes".encodeToByteArray(), s.images.load("images/plank.png"))
            readEverything()
        }

    @Test
    fun `a restore replaces what is in the app, not adds to it`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            val before = s.dump()
            s.bodyWeights.set(s.today, 90.0, WeightUnit.Kg)
            val pack = FakeArchives.zip(Files10.exercise("squat", "Squat"), "images/squat.png" to "x")
            s.importer.import(assertIs<ImportPreview.Ready>(s.importer.preview("pack.zip", pack)))
            restore(zip)
            assertEquals(before, s.dump())
        }

    @Test
    fun `a file that is damaged, not a backup, or fails validation leaves the app exactly as it was`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            s.bodyWeights.set(s.today, 90.0, WeightUnit.Kg)
            val before = s.dump()
            val epoch = s.resets.epoch.value
            val reading = Regex("\\{\"day\":\"2026-11-11\",\"weight\":81.5[^}]*\\}")
            val text = JsonPrimitive("not a date")
            val bad =
                mapOf(
                    "not a zip" to "hello".encodeToByteArray(),
                    "a content pack" to FakeArchives.zip(Files10.exercise("squat", "Squat")),
                    "cut short" to edited(zip) { it.dropLast(40) },
                    "text after the end" to edited(zip) { "$it x" },
                    "rearranged by hand" to edited(zip) { it.replace("{\"app\":", "{ \"app\":") },
                    "a key given twice" to edited(zip) { it.replace("\"format\":1,", "\"format\":1,\"format\":1,") },
                    "another app's file" to edited(zip) { it.replace("\"app\":\"fit\"", "\"app\":\"other\"") },
                    "a table missing" to edited(zip) { it.replace("\"bodyweight\":", "\"bodywait\":") },
                    "a column missing" to edited(zip) { it.replace(",\"unit\":\"Kg\"}", "}") },
                    "an unknown column" to edited(zip) { it.replace(",\"unit\":\"Kg\"}", ",\"unit\":\"Kg\",\"mood\":1}") },
                    "text where a number goes" to edited(zip) { it.replace("\"weight\":81.5", "\"weight\":\"heavy\"") },
                    "a number that isn't one" to edited(zip) { it.replace("\"weight\":81.5", "\"weight\":NaN") },
                    "a number past any weight" to edited(zip) { it.replace("\"weight\":81.5", "\"weight\":1e999") },
                    "a decimal where a whole number goes" to edited(zip) { it.replace("\"schema\":$DATABASE_VERSION", "\"schema\":10.5") },
                    "null where a value is needed" to edited(zip) { it.replace("\"weight\":81.5", "\"weight\":null") },
                    "a row pointing at nothing" to edited(zip) { it.replace(Regex("\"exerciseId\":\\d+"), "\"exerciseId\":999") },
                    "two rows with one key" to edited(zip) { all -> reading.find(all)!!.value.let { all.replace(it, "$it,$it") } },
                    "a setting missing" to edited(zip) { it.replace("\"alarm\":false,", "") },
                    "a stray file" to s.archives.write(entries(zip) + ArchiveEntry("../evil.sh", byteArrayOf(1))),
                    // Text of the right kind that the app can't read as a date.
                    "a body weight on no date" to withValue(zip, "bodyweight", "day", text),
                    "a workout on no date" to withValue(zip, "workout_day", "day", text),
                    "a log on no date" to withValue(zip, "guided_log", "day", text),
                    "a program starting on no date" to withValue(zip, "program", "startsOn", text),
                    "a date with a time" to withValue(zip, "bodyweight", "day", JsonPrimitive("2026-11-11T10:00")),
                    // A zip cut off after backup.json, an image it doesn't list, an image of another size.
                    "its image missing" to s.archives.write(entries(zip).filter { it.name == BackupFormat.FILE }),
                    "an image it doesn't list" to s.archives.write(entries(zip) + ArchiveEntry("images/extra.png", byteArrayOf(1))),
                    "an image cut short" to
                        s.archives.write(
                            entries(zip).map {
                                if (it.name ==
                                    BackupFormat.FILE
                                ) {
                                    it
                                } else {
                                    ArchiveEntry(it.name, it.bytes.copyOf(4))
                                }
                            },
                        ),
                )
            bad.forEach { (what, bytes) ->
                assertNotEquals(zip.toList(), bytes.toList(), what)
                assertEquals(BackupCheck.Invalid, s.backups.check(bytes), what)
                assertEquals(before, s.dump(), what)
            }
            assertEquals(custom, s.settings.settings.first())
            assertEquals(epoch, s.resets.epoch.value)
            readEverything()
        }

    @Test
    fun `text a restore accepts in any column is text the app can read back`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            // Every text column in turn holds something it never would. Either the backup is refused, or
            // it restores and every screen's data still reads: a column a mapper trips over fails here.
            for (table in s.tables() - BackupFormat.IMAGE_TABLE) {
                for (column in s.rows("PRAGMA table_info(`$table`)").filter { it[2] == "TEXT" }.map { it[1] as String }) {
                    val check = s.backups.check(withValue(zip, table, column, JsonPrimitive("?")))
                    if (check is BackupCheck.Valid) {
                        assertTrue(s.backups.restore(check.backup), "$table.$column")
                        try {
                            readEverything()
                        } catch (e: IllegalArgumentException) {
                            throw AssertionError("$table.$column was restored as \"?\" and can't be read: validate it in BackupFormat", e)
                        }
                    }
                }
            }
            restore(zip)
        }

    @Test
    fun `a backup from a newer format or schema is refused and changes nothing`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            val before = s.dump()
            val schema = "\"schema\":$DATABASE_VERSION"
            assertEquals(BackupCheck.Newer, s.backups.check(edited(zip) { it.replace("\"format\":1", "\"format\":2") }))
            assertEquals(BackupCheck.Newer, s.backups.check(edited(zip) { it.replace(schema, "\"schema\":${DATABASE_VERSION + 1}") }))
            // No backup was ever written from a schema before the first one.
            assertEquals(BackupCheck.Invalid, s.backups.check(edited(zip) { it.replace(schema, "\"schema\":9") }))
            assertEquals(before, s.dump())
        }

    @Test
    fun `a backup past what a phone can hold is refused as too large, and one that would be isn't written`(): Unit =
        runBlocking {
            fill()
            val before = s.dump()
            val huge =
                s.archives.write(
                    listOf(ArchiveEntry(BackupFormat.FILE, ByteArray(BackupFormat.MAX_JSON_BYTES + 1) { ' '.code.toByte() })),
                )
            assertEquals(BackupCheck.TooLarge, s.backups.check(huge))
            val heavy = entries(export()) + ArchiveEntry("images/big.png", ByteArray(BackupFormat.LIMITS.entryBytes.toInt() + 1))
            assertEquals(BackupCheck.TooLarge, s.backups.check(s.archives.write(heavy)))
            assertEquals(before, s.dump())

            // An image larger than a restore takes (it can't come from an import): said at export.
            s.database.imageDao().upsert(
                listOf(ImportedImageEntity("images/big.png", ByteArray(BackupFormat.LIMITS.entryBytes.toInt() + 1))),
            )
            assertEquals(BackupExport.TooLarge, s.backups.export())
        }

    @Test
    fun `data holding a number that isn't finite can't be exported, and says so instead of failing`(): Unit =
        runBlocking {
            fill()
            s.execute("INSERT INTO bodyweight (day, weight, unit) VALUES ('2026-01-01', 9e999, 'Kg')")
            assertEquals(BackupExport.Unwritable, s.backups.export())
        }

    @Test
    fun `wipe history clears days, sets, logs and body weight, and keeps content and settings`(): Unit =
        runBlocking {
            fill()
            val before = s.dump()
            val epoch = s.resets.epoch.value
            s.backups.wipeHistory()
            val after = s.dump()
            val history = setOf("workout_day", "block", "set_entry", "guided_log", "bodyweight")
            assertEquals(history, BackupFormat.HISTORY_TABLES.toSet())
            history.forEach { assertEquals(emptyList(), after.getValue(it), it) }
            assertEquals(before - history, after - history)
            // The session stays, so its reminder and alarm do.
            assertNotNull(s.reminderAlarms.scheduled)
            assertEquals(custom, s.settings.settings.first())
            assertTrue(
                s.guided
                    .plans(GuidedKind.Recovery)
                    .first()
                    .single()
                    .pinned,
            )
            assertEquals(epoch + 1, s.resets.epoch.value)
        }

    @Test
    fun `wipe everything clears every table and the settings, then loads the built-in content again as a fresh install would`(): Unit =
        runBlocking {
            fill()
            // A screen that's open sees the wipe.
            val shown = MutableStateFlow<List<String>?>(null)
            val watch = s.scope.launch { s.exercises.exercises.collect { list -> shown.value = list.map { it.fields.name } } }
            withTimeout(5.seconds) { shown.first { it == listOf("Curl", "Plank") } }
            val epoch = s.resets.epoch.value

            s.backups.wipeEverything()

            // Only what the bundled files hold: the routine deleted before is back, with the first id.
            assertEquals(
                listOf("Old routine"),
                s.routines.routines
                    .first()
                    .map { it.name },
            )
            assertEquals(
                1L,
                s.routines.routines
                    .first()
                    .single()
                    .id.value,
            )
            assertEquals(
                listOf("Curl"),
                s.exercises.exercises
                    .first()
                    .map { it.fields.name },
            )
            val content = setOf("exercise", "exercise_ref", "exercise_area", "routine", "routine_item")
            s.dump().forEach { (table, rows) -> assertEquals(table in content, rows.isNotEmpty(), table) }
            assertEquals(Settings(), s.settings.settings.first())
            assertNull(s.images.load("images/plank.png"))
            assertEquals(epoch + 1, s.resets.epoch.value)
            withTimeout(5.seconds) { shown.first { it == listOf("Curl") } }
            watch.cancel()
        }

    @Test
    fun `a wipe everything cut off before the built-in content is back loads it at the next start`(): Unit =
        runBlocking {
            fill()
            // The bundled files can't be read, as if the process died right after the rows went.
            s.builtIn.failing = true
            s.backups.wipeEverything()
            assertTrue(s.dump().values.all { it.isEmpty() })
            assertNull(s.contentState.loadedFingerprint())

            s.builtIn.failing = false
            assertTrue(s.sync.syncIfChanged())
            assertEquals(
                listOf("Curl"),
                s.exercises.exercises
                    .first()
                    .map { it.fields.name },
            )
        }

    @Test
    fun `a restore cut off right after its rows are written leaves the backup's settings with them and syncs at the next start`(): Unit =
        runBlocking {
            fill()
            val before = s.dump()
            val zip = export()
            s.backups.wipeEverything()
            val check = assertIs<BackupCheck.Valid>(s.backups.check(zip))

            state.dieOnFingerprint = true
            assertFailsWith<IllegalStateException> { s.backups.restore(check.backup) }
            state.dieOnFingerprint = false

            assertEquals(before, s.dump())
            assertEquals(custom, s.settings.settings.first())
            // No fingerprint, so the next start checks the built-in content against the restored rows.
            assertNull(s.contentState.loadedFingerprint())
            assertTrue(s.sync.syncIfChanged())
            assertEquals(
                listOf("Curl", "Plank"),
                s.exercises.exercises
                    .first()
                    .map { it.fields.name },
            )
            assertEquals(
                listOf("Legs"),
                s.routines.routines
                    .first()
                    .map { it.name },
            )
        }

    @Test
    fun `restore and the wipes end a running rest and guided session, and leave no timer state`(): Unit =
        runBlocking {
            fill()

            suspend fun startBoth() {
                if (s.days.day(s.today).first() == null) {
                    val routine =
                        s.routines.routines
                            .first()
                            .first()
                    s.days.applyRoutine(s.today, routine.id, replace = false)
                }

                suspend fun block() =
                    s.days
                        .day(s.today)
                        .first()!!
                        .blocks
                        .first()
                if (block().sets.all { it.done }) s.days.addSet(block().id)
                val block = block()
                val next = block.sets.first { !it.done }
                s.workout.open(s.today, block.id)
                // A done working set starts a rest; the session then takes the timers over, and both are saved.
                s.workout.setDone(block.id, next.id, true)
                assertNotNull(s.workout.state.value.snapshot.rest, "a rest runs")
                val drill =
                    s.lessons.lessons
                        .first()
                        .single()
                        .practice
                assertTrue(s.player.startPlan(drill!!))
                assertNotNull(s.workoutStore.load())
                assertNotNull(s.guidedStore.load())
            }

            suspend fun assertNothingRuns(what: String) {
                assertEquals(WorkoutSnapshot(), s.workout.state.value.snapshot, what)
                assertNull(s.player.state.value.snapshot, what)
                assertNull(s.workoutStore.load(), what)
                assertNull(s.guidedStore.load(), what)
                assertNull(s.wakeups.scheduled, what)
                assertNull(s.wakeups.guided, what)
            }

            startBoth()
            // A backup made while they run holds none of it.
            val zip = export()
            assertFalse(json(zip).contains("workout_rest"))
            assertFalse(json(zip).contains("guided_phase"))
            val logs = s.dump().getValue("guided_log")

            s.backups.wipeHistory()
            assertNothingRuns("wipe history")

            startBoth()
            restore(zip)
            assertNothingRuns("restore")
            // The session ended for the restore wasn't logged into the restored data.
            assertEquals(logs, s.dump().getValue("guided_log"))

            startBoth()
            // A file that can't be restored ends nothing.
            assertEquals(BackupCheck.Invalid, s.backups.check("nope".encodeToByteArray()))
            assertEquals(s.today, s.workout.state.value.snapshot.date)
            assertNotNull(s.player.state.value.snapshot)
            assertNotNull(s.guidedStore.load())

            s.backups.wipeEverything()
            assertNothingRuns("wipe everything")
            // Nothing writes the old session back afterwards.
            delay(300)
            assertNull(s.workoutStore.load())
            assertNull(s.guidedStore.load())
        }

    @Test
    fun `a restored backup from before a content update gets the update, as the app would have`(): Unit =
        runBlocking {
            fill()
            val zip = export()
            // The app is updated: the bundled exercise changes.
            s.builtIn.list = s.builtIn.list.map { it.copy(text = it.text.replace("name: Curl", "name: Biceps curl")) }
            s.sync.syncIfChanged()
            val epoch = s.resets.epoch.value
            restore(zip)
            assertEquals(
                listOf("Biceps curl", "Plank"),
                s.exercises.exercises
                    .first()
                    .map { it.fields.name },
            )
            assertEquals(epoch + 1, s.resets.epoch.value)
        }
}
