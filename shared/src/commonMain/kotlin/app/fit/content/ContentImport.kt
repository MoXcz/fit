// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.content

import androidx.room3.withWriteTransaction
import app.fit.core.AppDispatchers
import app.fit.core.OpenedDocument
import app.fit.data.db.AppDatabase
import app.fit.data.db.ExerciseDao
import app.fit.data.db.GuidedDao
import app.fit.data.db.ImageDao
import app.fit.data.db.ImportTarget
import app.fit.data.db.ImportedImageEntity
import app.fit.data.db.LessonDao
import app.fit.data.db.ProgramDao
import app.fit.data.db.RoutineDao
import app.fit.data.db.importTarget
import app.fit.exercises.toExercise
import app.fit.exercises.toRows
import app.fit.guided.ContentImages
import app.fit.guided.toRows
import app.fit.lessons.toSync
import app.fit.programs.toRows
import app.fit.routines.toRows
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.time.Clock

enum class ContentKind { Exercise, Routine, Program, Recovery, Lesson }

data class ImportItem(
    val kind: ContentKind,
    val id: String,
    val name: String,
    // The name of the item in the app it replaces; null for a new one.
    val replaces: String?,
)

sealed interface ImportPreview {
    val file: String

    // A file that can be imported: what it adds and what it replaces.
    class Ready(
        override val file: String,
        val items: List<ImportItem>,
        // The images it brings that the app doesn't have yet, and the ones it replaces.
        val newImages: List<String>,
        val replacedImages: List<String>,
        val pack: ContentPack,
    ) : ImportPreview {
        val replaces: Boolean get() = items.any { it.replaces != null } || replacedImages.isNotEmpty()
    }

    // A file with problems, each a line `file:line: what's wrong`; nothing of it can be imported.
    data class Invalid(
        override val file: String,
        val problems: List<String>,
    ) : ImportPreview
}

sealed interface ImportResult {
    data class Imported(
        val items: Int,
    ) : ImportResult

    // The app's content changed after the preview, so it would no longer do what was shown; nothing
    // was written.
    data object Changed : ImportResult
}

/**
 * Imports content files: a `.md` file or a `.zip` pack (see [ContentPacks]). The pack is checked as
 * `content/` is, together with what the app already holds, and imported whole or not at all. An
 * item replaces the one with its id within its kind (for exercises, routines and programs also one
 * made in the app with its name), even one changed in the app. Imported items are the user's own:
 * updates of the built-in content leave them alone.
 */
interface ContentImporter {
    /** What importing the file would do. Nothing is written. */
    suspend fun preview(
        name: String,
        bytes: ByteArray,
    ): ImportPreview

    /** Writes a previewed import in one transaction, if it still does what the preview showed. */
    suspend fun import(preview: ImportPreview.Ready): ImportResult
}

/** Says whether the platform can show an image, so a pack's broken or mis-named one is refused. */
fun interface ImageProbe {
    fun readable(bytes: ByteArray): Boolean
}

// What a pack would do to the app as it is now.
private class Assessment(
    val problems: List<String>,
    val items: List<ImportItem>,
    val newImages: List<String>,
    val replacedImages: List<String>,
    val content: List<ContentItem>,
    val imageData: Map<String, ByteArray>,
)

class RoomContentImporter(
    private val database: AppDatabase,
    private val exerciseDao: ExerciseDao,
    private val routineDao: RoutineDao,
    private val programDao: ProgramDao,
    private val guidedDao: GuidedDao,
    private val lessonDao: LessonDao,
    private val imageDao: ImageDao,
    private val builtIn: BuiltInContent,
    private val images: ContentImages,
    private val archives: Archives,
    private val probe: ImageProbe,
    private val dispatchers: AppDispatchers,
    private val clock: Clock,
) : ContentImporter {
    override suspend fun preview(
        name: String,
        bytes: ByteArray,
    ): ImportPreview =
        withContext(dispatchers.default) {
            val pack =
                when (val read = ContentPacks.read(name, bytes, archives)) {
                    is PackResult.Unreadable -> return@withContext ImportPreview.Invalid(name, read.problems)
                    is PackResult.Read -> read.pack
                }
            val unreadable =
                pack.images
                    .filterNot { probe.readable(it.value) }
                    .keys
                    .sorted()
            if (unreadable.isNotEmpty()) {
                return@withContext ImportPreview.Invalid(name, unreadable.map { "$it: isn't an image the app can show" })
            }
            val assessed = assess(pack)
            if (assessed.problems.isNotEmpty()) return@withContext ImportPreview.Invalid(name, assessed.problems)
            ImportPreview.Ready(name, assessed.items, assessed.newImages, assessed.replacedImages, pack)
        }

    private suspend fun assess(pack: ContentPack): Assessment {
        val library = exerciseDao.withContentId().associate { requireNotNull(it.exercise.contentId) to it.toExercise().fields }
        val knownImages = imageDao.paths().toSet() + builtIn.imagePaths()
        val check =
            checkContent(
                files = pack.files,
                knownRoutines = routineDao.contentIds().toSet(),
                library = library,
                checkPlace = !pack.single,
            ) { it in pack.images || it in knownImages }
        val problems = check.problems.toMutableList()
        val renamed = renamed(check.items)
        val items =
            check.items.mapIndexed { i, item ->
                val (target, kind, itemName) = targetOf(item, renamed)
                if (target is ImportTarget.NameTaken) problems += ContentProblem.NameTaken(check.paths[i], kind.name.lowercase(), itemName)
                ImportItem(kind, item.id, itemName, (target as? ImportTarget.Replace)?.row)
            }
        problems += brokenSteps(check)
        // An image the app already shows under that path, byte for byte, isn't imported again.
        val current = pack.images.mapValues { (path, _) -> images.load(path) }
        val changed = pack.images.filter { (path, data) -> current[path]?.contentEquals(data) != true }
        return Assessment(
            problems = problems.map { it.describe() },
            items = items.sortedWith(compareBy({ it.kind }, { it.name.lowercase() })),
            newImages = changed.keys.filter { current[it] == null }.sorted(),
            replacedImages = changed.keys.filter { current[it] != null }.sorted(),
            content = check.items,
            imageData = changed,
        )
    }

    // The content ids, by kind, of rows this pack gives another name: their old names are free for
    // the pack's other items.
    private suspend fun renamed(items: List<ContentItem>): Map<ContentKind, Set<String>> {
        val renamed = mutableMapOf<ContentKind, MutableSet<String>>()
        for (item in items) {
            val (kind, old, new) =
                when (item) {
                    is ExerciseDraft -> Triple(ContentKind.Exercise, exerciseDao.findByContentId(item.id)?.name, item.fields.name)
                    is RoutineDraft -> Triple(ContentKind.Routine, routineDao.findByContentId(item.id)?.name, item.name)
                    is ProgramDraft -> Triple(ContentKind.Program, programDao.findByContentId(item.id)?.name, item.name)
                    else -> continue
                }
            if (old != null && !old.equals(new, ignoreCase = true)) renamed.getOrPut(kind) { mutableSetOf() } += item.id
        }
        return renamed
    }

    // Steps already in the app that a replaced exercise would no longer fit: the session or lesson
    // they belong to has to come in the same pack, or the exercise has to keep tracking it.
    private suspend fun brokenSteps(check: ContentCheck): List<ContentProblem> {
        val exercises =
            check.items
                .withIndex()
                .filter { it.value is ExerciseDraft }
                .associate { it.value.id to it.index }
        if (exercises.isEmpty()) return emptyList()
        val sessions =
            check.items
                .filterIsInstance<RecoveryDraft>()
                .map { it.id }
                .toSet()
        val lessons =
            check.items
                .filterIsInstance<LessonDraft>()
                .map { it.id }
                .toSet()
        return guidedDao
            .exerciseSteps()
            .filter { it.exercise in exercises && it.planContentId !in sessions && it.lessonContentId !in lessons }
            .mapNotNull { step ->
                val index = exercises.getValue(step.exercise)
                val tracks = (check.items[index] as ExerciseDraft).fields.tracks
                val field =
                    when {
                        step.durationSeconds != null && !tracks.time -> "duration"
                        step.durationSeconds == null && step.reps != null && !tracks.reps -> "reps"
                        else -> return@mapNotNull null
                    }
                val owner = if (step.lessonTitle != null) "the lesson ${step.lessonTitle}" else "the recovery session ${step.planTitle}"
                ContentProblem.BreaksStep(check.paths[index], step.exercise, owner, field)
            }.distinct()
    }

    // What [item] does to the app's rows, as the name of the row it replaces.
    private suspend fun targetOf(
        item: ContentItem,
        renamed: Map<ContentKind, Set<String>>,
    ): Triple<ImportTarget<String>, ContentKind, String> =
        when (item) {
            is ExerciseDraft -> {
                val name = item.fields.name
                val target =
                    importTarget(
                        exerciseDao.findByContentId(item.id),
                        exerciseDao.findByName(name),
                        { it.id },
                        { it.contentId },
                        renamed[ContentKind.Exercise].orEmpty(),
                    )
                Triple(target.named { it.name }, ContentKind.Exercise, name)
            }

            is RoutineDraft -> {
                val target =
                    importTarget(
                        routineDao.findByContentId(item.id),
                        routineDao.findByName(item.name),
                        { it.id },
                        { it.contentId },
                        renamed[ContentKind.Routine].orEmpty(),
                    )
                Triple(target.named { it.name }, ContentKind.Routine, item.name)
            }

            is ProgramDraft -> {
                val target =
                    importTarget(
                        programDao.findByContentId(item.id),
                        programDao.findByName(item.name),
                        { it.id },
                        { it.contentId },
                        renamed[ContentKind.Program].orEmpty(),
                    )
                Triple(target.named { it.name }, ContentKind.Program, item.name)
            }

            is RecoveryDraft -> {
                Triple(replacing(guidedDao.findByContentId(item.id)?.title), ContentKind.Recovery, item.title)
            }

            is LessonDraft -> {
                Triple(replacing(lessonDao.findByContentId(item.id)?.title), ContentKind.Lesson, item.title)
            }
        }

    override suspend fun import(preview: ImportPreview.Ready): ImportResult {
        val now = clock.now().toEpochMilliseconds()
        val written =
            database.withWriteTransaction {
                // Checked again where nothing can change under it: a routine or exercise it named may
                // have gone, or an item that was new may now replace one the user wasn't asked about.
                val assessed = assess(preview.pack)
                val same =
                    assessed.problems.isEmpty() &&
                        assessed.items == preview.items &&
                        assessed.newImages == preview.newImages &&
                        assessed.replacedImages == preview.replacedImages
                if (!same) rollback(-1)
                val content = assessed.content
                // Exercises first, then routines: each kind looks up the ones before it by content id.
                val fits =
                    exerciseDao.importAll(content.filterIsInstance<ExerciseDraft>().map { it.fields.toRows(it.id, now) }) &&
                        routineDao.importAll(content.filterIsInstance<RoutineDraft>().map { it.toRows(exerciseDao, now) }) &&
                        programDao.importAll(content.filterIsInstance<ProgramDraft>().map { it.toRows(now) })
                if (!fits) rollback(-1)
                guidedDao.writeContent(content.filterIsInstance<RecoveryDraft>().map { it.toRows(now) }, imported = true)
                lessonDao.writeContent(content.filterIsInstance<LessonDraft>().map { it.toSync(now) }, imported = true)
                imageDao.upsert(assessed.imageData.map { (path, data) -> ImportedImageEntity(path, data) })
                content.size
            }
        return if (written >= 0) ImportResult.Imported(written) else ImportResult.Changed
    }
}

private fun <T> ImportTarget<T>.named(name: (T) -> String): ImportTarget<String> =
    when (this) {
        ImportTarget.New -> ImportTarget.New
        is ImportTarget.Replace -> ImportTarget.Replace(name(row))
        is ImportTarget.NameTaken -> ImportTarget.NameTaken(name(row))
    }

private fun replacing(name: String?): ImportTarget<String> = if (name == null) ImportTarget.New else ImportTarget.Replace(name)

// Files handed to the app from outside (the share sheet, "Open with"), each waiting under its own
// id for the import screen that was opened for it: another screen, or another activity, can't take it.
class ImportInbox {
    private val waiting = MutableStateFlow<Map<String, OpenedDocument>>(emptyMap())
    private val ids = MutableStateFlow(0)

    fun offer(document: OpenedDocument): String {
        var id = 0
        ids.update { (it + 1).also { next -> id = next } }
        waiting.update { it + ("$id" to document) }
        return "$id"
    }

    // The document offered as [id], once.
    fun take(id: String): OpenedDocument? {
        var taken: OpenedDocument? = null
        waiting.update { all ->
            taken = all[id]
            all - id
        }
        return taken
    }
}

// Imported images first: a pack's image replaces a bundled one with its name.
class StoredContentImages(
    private val builtIn: BuiltInContent,
    private val imageDao: ImageDao,
) : ContentImages {
    override suspend fun load(path: String): ByteArray? = imageDao.data(path) ?: builtIn.image(path)
}
