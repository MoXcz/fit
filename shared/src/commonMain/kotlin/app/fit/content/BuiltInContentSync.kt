// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import app.fit.exercises.ExerciseRepository
import app.fit.guided.GuidedRepository
import app.fit.lessons.LessonRepository
import app.fit.programs.ProgramRepository
import app.fit.routines.RoutineRepository

/** The content files that ship with the app (`content/` in the repo). */
interface BuiltInContent {
    suspend fun files(): List<ContentFile>

    /** The bundled images, as content files name them (`images/<name>.webp`). */
    suspend fun imagePaths(): Set<String>

    /** A bundled image's bytes, or null when it isn't there. */
    suspend fun image(path: String): ByteArray?
}

/** Remembers which built-in content was loaded last. */
interface ContentStateStore {
    suspend fun loadedFingerprint(): String?

    /** Null forgets it, so the next start loads the built-in content again. */
    suspend fun setLoadedFingerprint(fingerprint: String?)
}

// Raise when the sync rules change, so every install syncs once more.
private const val SYNC_RULES_VERSION = 1

// Loads built-in content on first start and whenever the bundled files change (an app update).
class BuiltInContentSync(
    private val content: BuiltInContent,
    private val store: ContentStateStore,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val programs: ProgramRepository,
    private val guided: GuidedRepository,
    private val lessons: LessonRepository,
) {
    // True when it synced, false when nothing had changed.
    suspend fun syncIfChanged(): Boolean {
        val files = content.files()
        val fingerprint = fingerprintOf(files)
        if (store.loadedFingerprint() == fingerprint) return false
        // The content check keeps bundled files valid; a file that still fails is skipped.
        val items = files.mapNotNull { (ContentParser.parse(it) as? ContentResult.Parsed)?.item }
        // Exercises first: routines, sessions and lessons look their exercises up by content id, and
        // programs their routines.
        exercises.syncBuiltIn(items.filterIsInstance<ExerciseDraft>())
        routines.syncBuiltIn(items.filterIsInstance<RoutineDraft>())
        programs.syncBuiltIn(items.filterIsInstance<ProgramDraft>())
        guided.syncBuiltIn(items.filterIsInstance<RecoveryDraft>())
        lessons.syncBuiltIn(items.filterIsInstance<LessonDraft>())
        store.setLoadedFingerprint(fingerprint)
        return true
    }
}

// FNV-1a (64 bit) over every path and text in path order: the same on every platform and run.
internal fun fingerprintOf(files: List<ContentFile>): String {
    var hash = 0xcbf29ce484222325UL

    fun add(text: String) {
        for (byte in text.encodeToByteArray()) {
            hash = (hash xor byte.toUByte().toULong()) * 0x100000001b3UL
        }
        // A 0 byte between fields, so "ab" + "c" differs from "a" + "bc".
        hash *= 0x100000001b3UL
    }
    add("rules $SYNC_RULES_VERSION")
    for (file in files.sortedBy { it.path }) {
        add(file.path)
        add(file.text)
    }
    return hash.toString(16)
}
