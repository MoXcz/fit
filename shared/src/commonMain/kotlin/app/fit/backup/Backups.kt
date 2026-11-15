// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.backup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLITE_DATA_FLOAT
import androidx.sqlite.SQLITE_DATA_INTEGER
import androidx.sqlite.SQLITE_DATA_NULL
import androidx.sqlite.SQLITE_DATA_TEXT
import androidx.sqlite.SQLiteException
import app.fit.content.ArchiveEntry
import app.fit.content.ArchiveLimits
import app.fit.content.ArchiveResult
import app.fit.content.Archives
import app.fit.content.BuiltInContentSync
import app.fit.content.ContentStateStore
import app.fit.content.decodeUtf8
import app.fit.content.isImagePath
import app.fit.core.AppDispatchers
import app.fit.core.DataResets
import app.fit.core.WeightUnit
import app.fit.data.db.AppDatabase
import app.fit.data.db.DATABASE_VERSION
import app.fit.guided.GuidedController
import app.fit.reminders.Reminders
import app.fit.settings.Settings
import app.fit.settings.SettingsRepository
import app.fit.workout.WorkoutController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Clock
import kotlin.time.Instant

/** A backup file that was read and can be restored. */
interface ReadBackup {
    val exportedAt: Instant
}

sealed interface BackupCheck {
    class Valid(
        val backup: ReadBackup,
    ) : BackupCheck

    // Not a backup, damaged, or holding data the app can't take.
    data object Invalid : BackupCheck

    // Written by a later version of the app.
    data object Newer : BackupCheck

    // Over what a backup may hold ([BackupFormat.LIMITS]); it isn't unpacked further.
    data object TooLarge : BackupCheck
}

sealed interface BackupExport {
    class File(
        val bytes: ByteArray,
    ) : BackupExport

    // More than a restore would accept, so it's said now rather than when it's needed.
    data object TooLarge : BackupExport

    // The data holds a number JSON can't carry (not finite).
    data object Unwritable : BackupExport
}

/**
 * The app's own backup and the wipes. A backup is one `.zip`: `backup.json` (every table and the
 * settings) and the imported images under `images/`. Restoring replaces everything in one
 * transaction; a file that can't be restored whole changes nothing. Timer state is never part of
 * it: a running workout or guided session is ended first, and none comes back. Reminders follow the
 * data: after a restore the restored ones are scheduled, after wipe everything none is.
 */
interface Backups {
    suspend fun export(): BackupExport

    /** Reads a backup and tries it against the database without keeping anything. */
    suspend fun check(bytes: ByteArray): BackupCheck

    /** Replaces everything with [backup]; false, with the data unchanged, if it no longer fits. */
    suspend fun restore(backup: ReadBackup): Boolean

    /** Deletes what was logged: workout days with their sets, recovery and practice runs, body weight. */
    suspend fun wipeHistory()

    /** Deletes everything and loads the built-in content again, as after a fresh install. */
    suspend fun wipeEverything()
}

// The running workout and guided session, ended together before their data is wiped or replaced:
// nothing is logged, and their saved state, wakeups and alert go.
class LiveSessions(
    private val workout: WorkoutController,
    private val guided: GuidedController,
) {
    suspend fun endAll() {
        guided.reset()
        workout.reset()
    }
}

object BackupFormat {
    const val FILE = "backup.json"
    const val VERSION = 1

    // The first schema a backup was written from. A later version that changes the schema has to say
    // here how it reads rows of the schemas before it; the committed schema-10 backup's test fails
    // until it does.
    const val FIRST_SCHEMA = 10

    // How rows of earlier schemas are read. Schema 11 added the `reminder` table: a backup from
    // before has no reminders.
    val ADDED_TABLES = mapOf("reminder" to 11)

    // The one table that isn't in backup.json: its rows are the files under images/.
    const val IMAGE_TABLE = "imported_image"

    val HISTORY_TABLES = listOf("set_entry", "block", "workout_day", "guided_log", "bodyweight")

    // Text the app reads as an ISO date and can't show otherwise; restore refuses anything else in
    // them. Other text columns (units, kinds, sides, areas) fall back when they don't know a value.
    val DATE_COLUMNS = mapOf("workout_day" to "day", "bodyweight" to "day", "guided_log" to "day", "program" to "startsOn")

    // What a phone's memory holds comfortably while it's read: backup.json is parsed whole (8 MB is
    // some 30,000 logged sets), an image is at most what an import takes, and the picked file itself
    // is capped at 32 MB by the platform reader.
    const val MAX_JSON_BYTES = 8 * 1024 * 1024
    val LIMITS = ArchiveLimits(entries = 4_000, entryBytes = 8L * 1024 * 1024, totalBytes = 48L * 1024 * 1024)

    fun settings(settings: Settings): JsonObject =
        JsonObject(
            mapOf(
                "weightUnit" to JsonPrimitive(settings.weightUnit.name),
                "barKg" to JsonPrimitive(settings.barKg),
                "barLb" to JsonPrimitive(settings.barLb),
                "alarm" to JsonPrimitive(settings.alarm),
                "keepScreenOn" to JsonPrimitive(settings.keepScreenOn),
                "autoAdvance" to JsonPrimitive(settings.autoAdvance),
            ),
        )

    // Null when a value is missing, of the wrong type, or there's a key this version doesn't know.
    fun settings(json: JsonElement?): Settings? {
        val fields = json as? JsonObject ?: return null
        if (fields.size != 6) return null
        return Settings(
            weightUnit = fields["weightUnit"].text()?.let { unit -> WeightUnit.entries.firstOrNull { it.name == unit } } ?: return null,
            barKg = fields["barKg"].decimal() ?: return null,
            barLb = fields["barLb"].decimal() ?: return null,
            alarm = fields["alarm"].flag() ?: return null,
            keepScreenOn = fields["keepScreenOn"].flag() ?: return null,
            autoAdvance = fields["autoAdvance"].flag() ?: return null,
        )
    }

    // backup.json as a tree, or null when it isn't exactly what the app writes. The library merges a
    // key given twice, so the text must read back to itself: a doubled key, or anything rearranged
    // by hand, is a damaged file.
    fun read(text: String): JsonObject? =
        try {
            // Writing it again also refuses what the library read but JSON has no number for (NaN).
            (Json.parseToJsonElement(text) as? JsonObject)?.takeIf { Json.encodeToString(JsonElement.serializer(), it) == text }
        } catch (_: SerializationException) {
            null
        }
}

private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

private fun JsonElement?.number(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { !it.isString && it !is JsonNull }

private fun JsonElement?.whole(): Long? = number()?.content?.toLongOrNull()

// The library reads NaN and 1e999 as numbers; a backup has neither.
private fun JsonElement?.decimal(): Double? = number()?.content?.toDoubleOrNull()?.takeIf { it.isFinite() }

private fun JsonElement?.flag(): Boolean? = number()?.content?.toBooleanStrictOrNull()

private fun isDate(text: String): Boolean =
    try {
        LocalDate.parse(text).toString() == text
    } catch (_: IllegalArgumentException) {
        false
    }

private class Column(
    val name: String,
    val type: String,
    val notNull: Boolean,
)

private class ParsedBackup(
    val schema: Int,
    override val exportedAt: Instant,
    val settings: Settings,
    val fingerprint: String?,
    val tables: Map<String, List<JsonObject>>,
    val images: Map<String, ByteArray>,
) : ReadBackup

// A REAL that isn't finite: the export stops.
private class NotFinite : Exception()

class RoomBackups(
    private val database: AppDatabase,
    private val archives: Archives,
    private val dataStore: DataStore<Preferences>,
    private val settings: SettingsRepository,
    private val contentState: ContentStateStore,
    private val sync: BuiltInContentSync,
    private val sessions: LiveSessions,
    private val reminders: Reminders,
    private val resets: DataResets,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : Backups {
    override suspend fun export(): BackupExport {
        val dump: Pair<Map<String, JsonArray>, Map<String, ByteArray>> =
            try {
                database.withReadTransaction {
                    val tables = tables().filter { it != BackupFormat.IMAGE_TABLE }.associateWith { JsonArray(rows(it)) }
                    tables to images()
                }
            } catch (_: NotFinite) {
                return BackupExport.Unwritable
            }
        val (tables, images) = dump
        val json =
            JsonObject(
                mapOf(
                    "app" to JsonPrimitive("fit"),
                    "format" to JsonPrimitive(BackupFormat.VERSION),
                    "schema" to JsonPrimitive(DATABASE_VERSION),
                    "exportedAt" to JsonPrimitive(clock.now().toString()),
                    "content" to (contentState.loadedFingerprint()?.let(::JsonPrimitive) ?: JsonNull),
                    "settings" to BackupFormat.settings(settings.settings.first()),
                    "images" to
                        JsonArray(
                            images.map { (path, data) ->
                                JsonObject(
                                    mapOf(
                                        "path" to JsonPrimitive(path),
                                        "size" to JsonPrimitive(data.size),
                                    ),
                                )
                            },
                        ),
                    "tables" to JsonObject(tables),
                ),
            )
        return withContext(dispatchers.default) {
            val file = Json.encodeToString(JsonElement.serializer(), json).encodeToByteArray()
            val limits = BackupFormat.LIMITS
            val fits =
                file.size <= BackupFormat.MAX_JSON_BYTES &&
                    images.size < limits.entries &&
                    images.values.all { it.size <= limits.entryBytes } &&
                    file.size + images.values.sumOf { it.size.toLong() } <= limits.totalBytes
            if (!fits) return@withContext BackupExport.TooLarge
            BackupExport.File(
                archives.write(listOf(ArchiveEntry(BackupFormat.FILE, file)) + images.map { (path, data) -> ArchiveEntry(path, data) }),
            )
        }
    }

    override suspend fun check(bytes: ByteArray): BackupCheck {
        val parsed =
            when (val read = withContext(dispatchers.default) { parse(bytes) }) {
                is BackupCheck.Valid -> read.backup as ParsedBackup
                else -> return read
            }
        // Every table the backup's schema had, and no other.
        val expected =
            database
                .withReadTransaction { tables() }
                .filter { it != BackupFormat.IMAGE_TABLE && (BackupFormat.ADDED_TABLES[it] ?: 0) <= parsed.schema }
        if (parsed.tables.keys != expected.toSet()) return BackupCheck.Invalid
        // Written and rolled back: whatever the real restore would trip over is found now, before
        // the confirmation, and before a running session is ended for it.
        val fits =
            try {
                database.withWriteTransaction { rollback(replaceAll(parsed)) }
            } catch (_: SQLiteException) {
                false
            }
        return if (fits) BackupCheck.Valid(parsed) else BackupCheck.Invalid
    }

    private fun parse(bytes: ByteArray): BackupCheck {
        val entries =
            when (val read = archives.read(bytes, BackupFormat.LIMITS)) {
                is ArchiveResult.Entries -> read.entries
                ArchiveResult.NotAnArchive -> return BackupCheck.Invalid
                ArchiveResult.TooManyEntries, is ArchiveResult.TooLarge -> return BackupCheck.TooLarge
            }
        val file = entries.singleOrNull { it.name == BackupFormat.FILE } ?: return BackupCheck.Invalid
        if (file.bytes.size > BackupFormat.MAX_JSON_BYTES) return BackupCheck.TooLarge
        val images = entries.filter { it !== file }
        if (images.any { !isImagePath(it.name) } || images.distinctBy { it.name }.size != images.size) return BackupCheck.Invalid
        val root = decodeUtf8(file.bytes)?.let(BackupFormat::read) ?: return BackupCheck.Invalid
        if (root["app"].text() != "fit") return BackupCheck.Invalid
        val format = root["format"].whole() ?: return BackupCheck.Invalid
        val schema = root["schema"].whole() ?: return BackupCheck.Invalid
        if (format > BackupFormat.VERSION || schema > DATABASE_VERSION) return BackupCheck.Newer
        if (format < BackupFormat.VERSION || schema < BackupFormat.FIRST_SCHEMA) return BackupCheck.Invalid
        val exportedAt =
            try {
                Instant.parse(root["exportedAt"].text() ?: return BackupCheck.Invalid)
            } catch (_: IllegalArgumentException) {
                return BackupCheck.Invalid
            }
        val fingerprint =
            when (val content = root["content"]) {
                JsonNull -> null
                else -> content.text() ?: return BackupCheck.Invalid
            }
        val settings = BackupFormat.settings(root["settings"]) ?: return BackupCheck.Invalid
        // Every image the backup was written with, and no other: a zip cut short is refused.
        val listed =
            (root["images"] as? JsonArray)?.map { image ->
                val fields = image as? JsonObject ?: return BackupCheck.Invalid
                (fields["path"].text() ?: return BackupCheck.Invalid) to (fields["size"].whole() ?: return BackupCheck.Invalid)
            } ?: return BackupCheck.Invalid
        if (listed.sortedBy { it.first } !=
            images.map { it.name to it.bytes.size.toLong() }.sortedBy { it.first }
        ) {
            return BackupCheck.Invalid
        }
        val tables =
            (root["tables"] as? JsonObject)?.mapValues { (_, rows) ->
                (rows as? JsonArray)?.map { it as? JsonObject ?: return BackupCheck.Invalid } ?: return BackupCheck.Invalid
            } ?: return BackupCheck.Invalid
        val files = images.associate { it.name to it.bytes }
        return BackupCheck.Valid(ParsedBackup(schema.toInt(), exportedAt, settings, fingerprint, tables, files))
    }

    override suspend fun restore(backup: ReadBackup): Boolean {
        if (backup !is ParsedBackup) return false
        sessions.endAll()
        val settingsBefore = settings.settings.first()
        val fingerprintBefore = contentState.loadedFingerprint()
        // Before the rows, so the process dying right after they're written leaves the backup's
        // settings with them, and no fingerprint: the next start syncs the built-in content.
        contentState.setLoadedFingerprint(null)
        settings.replace(backup.settings)
        val written =
            try {
                database.withWriteTransaction { if (replaceAll(backup)) true else rollback(false) }
            } catch (_: SQLiteException) {
                false
            }
        if (!written) {
            settings.replace(settingsBefore)
            contentState.setLoadedFingerprint(fingerprintBefore)
            return false
        }
        resets.bump()
        database.invalidationTracker.refreshAsync()
        // The backup's fingerprint: built-in content that changed since it was made is synced as
        // after an app update, and nothing is touched when it's the same.
        contentState.setLoadedFingerprint(backup.fingerprint)
        syncBuiltIn()
        checkReminders()
        return true
    }

    override suspend fun wipeHistory() {
        sessions.endAll()
        database.withWriteTransaction { BackupFormat.HISTORY_TABLES.forEach { executeSQL("DELETE FROM `$it`") } }
        resets.bump()
        database.invalidationTracker.refreshAsync()
    }

    override suspend fun wipeEverything() {
        sessions.endAll()
        // Settings, the saved sessions and the content fingerprint: every key goes, and before the
        // rows, so the process dying in between still loads the built-in content at the next start.
        dataStore.edit { it.clear() }
        database.withWriteTransaction { deleteAll() }
        resets.bump()
        database.invalidationTracker.refreshAsync()
        syncBuiltIn()
        checkReminders()
    }

    // The reminders are now the restored ones, or none: the alarm and what's showing follow at once.
    private suspend fun checkReminders() {
        try {
            reminders.check()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    // An unreadable bundled file leaves the fingerprint unset, so the next start loads it again.
    private suspend fun syncBuiltIn() {
        try {
            sync.syncIfChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    private suspend fun PooledConnection.deleteAll() {
        // Checked when the transaction ends, so the order of the tables doesn't matter.
        executeSQL("PRAGMA defer_foreign_keys = ON")
        tables().forEach { executeSQL("DELETE FROM `$it`") }
        // New rows count from 1 again.
        executeSQL("DELETE FROM sqlite_sequence")
    }

    // False when a row doesn't fit its table, holds text the app can't read, or points at a row that
    // isn't there.
    private suspend fun PooledConnection.replaceAll(backup: ParsedBackup): Boolean {
        deleteAll()
        for ((table, rows) in backup.tables) {
            val columns = columns(table)
            val names = columns.map { it.name }.toSet()
            if (rows.any { it.keys != names }) return false
            if (rows.isEmpty()) continue
            val date = BackupFormat.DATE_COLUMNS[table]
            val sql = "INSERT INTO `$table` (${columns.joinToString { "`${it.name}`" }}) VALUES (${columns.joinToString { "?" }})"
            val fits =
                usePrepared(sql) { statement ->
                    for (row in rows) {
                        columns.forEachIndexed { i, column ->
                            val value = row.getValue(column.name)
                            val text = value.text()
                            when {
                                value is JsonNull && !column.notNull -> statement.bindNull(i + 1)

                                column.type == "INTEGER" && value.whole() != null -> statement.bindLong(i + 1, value.whole()!!)

                                column.type == "REAL" && value.decimal() != null -> statement.bindDouble(i + 1, value.decimal()!!)

                                column.type == "TEXT" && text != null && (
                                    column.name != date ||
                                        isDate(
                                            text,
                                        )
                                ) -> statement.bindText(i + 1, text)

                                else -> return@usePrepared false
                            }
                        }
                        statement.step()
                        statement.reset()
                    }
                    true
                }
            if (!fits) return false
        }
        if (backup.images.isNotEmpty()) {
            usePrepared("INSERT INTO `${BackupFormat.IMAGE_TABLE}` (`path`, `data`) VALUES (?, ?)") { statement ->
                for ((path, data) in backup.images) {
                    statement.bindText(1, path)
                    statement.bindBlob(2, data)
                    statement.step()
                    statement.reset()
                }
            }
        }
        return usePrepared("PRAGMA foreign_key_check") { !it.step() }
    }

    private suspend fun PooledConnection.tables(): List<String> =
        usePrepared(
            "SELECT name FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' " +
                "AND name NOT IN ('room_master_table', 'android_metadata') ORDER BY name",
        ) { statement -> buildList { while (statement.step()) add(statement.getText(0)) } }

    private suspend fun PooledConnection.columns(table: String): List<Column> =
        usePrepared("PRAGMA table_info(`$table`)") { statement ->
            buildList { while (statement.step()) add(Column(statement.getText(1), statement.getText(2), statement.getLong(3) != 0L)) }
        }

    private suspend fun PooledConnection.rows(table: String): List<JsonElement> =
        usePrepared("SELECT * FROM `$table` ORDER BY rowid") { statement ->
            buildList {
                while (statement.step()) {
                    val row = LinkedHashMap<String, JsonElement>()
                    for (i in 0 until statement.getColumnCount()) {
                        row[statement.getColumnName(i)] =
                            when (statement.getColumnType(i)) {
                                SQLITE_DATA_NULL -> {
                                    JsonNull
                                }

                                SQLITE_DATA_INTEGER -> {
                                    JsonPrimitive(statement.getLong(i))
                                }

                                SQLITE_DATA_FLOAT -> {
                                    statement
                                        .getDouble(
                                            i,
                                        ).let { if (it.isFinite()) JsonPrimitive(it) else throw NotFinite() }
                                }

                                SQLITE_DATA_TEXT -> {
                                    JsonPrimitive(statement.getText(i))
                                }

                                else -> {
                                    error("$table.${statement.getColumnName(i)} is a blob: it needs its own place in the backup")
                                }
                            }
                    }
                    add(JsonObject(row))
                }
            }
        }

    private suspend fun PooledConnection.images(): Map<String, ByteArray> =
        usePrepared("SELECT `path`, `data` FROM `${BackupFormat.IMAGE_TABLE}` ORDER BY `path`") { statement ->
            buildMap { while (statement.step()) put(statement.getText(0), statement.getBlob(1)) }
        }
}
