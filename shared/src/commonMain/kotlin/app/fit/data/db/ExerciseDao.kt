// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

enum class EditOutcome { Saved, NotFound, NameTaken }

@Dao
interface ExerciseDao {
    @Transaction
    @Query("SELECT * FROM exercise ORDER BY name")
    fun all(): Flow<List<ExerciseWithDetails>>

    @Transaction
    @Query("SELECT * FROM exercise WHERE id = :id")
    fun byId(id: Long): Flow<ExerciseWithDetails?>

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun find(id: Long): ExerciseEntity?

    @Query("SELECT * FROM exercise WHERE contentId = :contentId")
    suspend fun findByContentId(contentId: String): ExerciseEntity?

    // The column is NOCASE, so this ignores case.
    @Query("SELECT * FROM exercise WHERE name = :name")
    suspend fun findByName(name: String): ExerciseEntity?

    @Insert
    suspend fun insert(exercise: ExerciseEntity): Long

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Query("UPDATE exercise SET archived = :archived WHERE id = :id")
    suspend fun setArchived(
        id: Long,
        archived: Boolean,
    )

    @Query("UPDATE exercise SET contentId = :contentId, edited = 1 WHERE id = :id")
    suspend fun adopt(
        id: Long,
        contentId: String,
    )

    @Query("DELETE FROM exercise_ref WHERE exerciseId = :id")
    suspend fun deleteRefs(id: Long)

    @Query("DELETE FROM exercise_area WHERE exerciseId = :id")
    suspend fun deleteAreas(id: Long)

    @Query("DELETE FROM exercise_image WHERE exerciseId = :id")
    suspend fun deleteImages(id: Long)

    @Insert
    suspend fun insertRefs(refs: List<ExerciseRefEntity>)

    @Insert
    suspend fun insertAreas(areas: List<ExerciseAreaEntity>)

    @Insert
    suspend fun insertImages(images: List<ExerciseImageEntity>)

    private suspend fun replaceChildren(
        id: Long,
        rows: ExerciseRows,
    ) {
        deleteRefs(id)
        deleteAreas(id)
        deleteImages(id)
        insertRefs(rows.refs.map { it.copy(exerciseId = id) })
        insertAreas(rows.areas.map { it.copy(exerciseId = id) })
        insertImages(rows.images.map { it.copy(exerciseId = id) })
    }

    // Writes the editor's values unless the exercise is gone or another one has the name.
    @Transaction
    suspend fun saveEdit(
        id: Long,
        rows: ExerciseRows,
    ): EditOutcome {
        val current = find(id) ?: return EditOutcome.NotFound
        val sameName = findByName(rows.exercise.name)
        if (sameName != null && sameName.id != id) return EditOutcome.NameTaken
        update(
            rows.exercise.copy(
                id = id,
                contentId = current.contentId,
                archived = current.archived,
                edited = true,
                createdAtMillis = current.createdAtMillis,
            ),
        )
        replaceChildren(id, rows)
        return EditOutcome.Saved
    }

    /**
     * Brings built-in exercises up to date in one transaction. New ones are added; ones the user never
     * edited are overwritten; edited ones are left alone. A user's exercise that already has a built-in
     * name takes that content id instead of getting a duplicate, and keeps the user's values.
     */
    @Transaction
    suspend fun syncBuiltIn(all: List<ExerciseRows>) {
        for (rows in all) {
            val contentId = requireNotNull(rows.exercise.contentId)
            val existing = findByContentId(contentId)
            val sameName = findByName(rows.exercise.name)
            when {
                existing == null && sameName == null -> {
                    replaceChildren(insert(rows.exercise), rows)
                }

                existing == null -> {
                    // Same skip when the name belongs to another built-in exercise.
                    if (sameName?.contentId == null) adopt(requireNotNull(sameName).id, contentId)
                }

                // A name another exercise already has (the user may have renamed one to it) is
                // skipped rather than duplicated; the next content update tries again.
                existing.edited || (sameName != null && sameName.id != existing.id) -> {}

                else -> {
                    update(rows.exercise.copy(id = existing.id, archived = existing.archived, createdAtMillis = existing.createdAtMillis))
                    replaceChildren(existing.id, rows)
                }
            }
        }
    }

    @Query("UPDATE exercise SET name = :name WHERE id = :id")
    suspend fun setName(
        id: Long,
        name: String,
    )

    @Transaction
    @Query("SELECT * FROM exercise WHERE contentId IS NOT NULL")
    suspend fun withContentId(): List<ExerciseWithDetails>

    // Imported exercises each replace the one with their content id, else one made in the app with
    // their name (which takes the id), else are added. They count as the user's own, and an archived
    // one comes back. Rows that get another name give theirs up first, so two can swap. False, with
    // nothing to keep, when another exercise has a name.
    @Transaction
    suspend fun importAll(all: List<ExerciseRows>): Boolean {
        for (rows in all) {
            val contentId = requireNotNull(rows.exercise.contentId)
            val old = findByContentId(contentId) ?: continue
            if (!old.name.equals(rows.exercise.name, ignoreCase = true)) setName(old.id, parkedName(contentId))
        }
        for (rows in all) {
            val contentId = requireNotNull(rows.exercise.contentId)
            when (val target = importTarget(findByContentId(contentId), findByName(rows.exercise.name), { it.id }, { it.contentId })) {
                is ImportTarget.NameTaken -> {
                    return false
                }

                ImportTarget.New -> {
                    replaceChildren(insert(rows.exercise.copy(edited = true)), rows)
                }

                is ImportTarget.Replace -> {
                    val old = target.row
                    update(rows.exercise.copy(id = old.id, edited = true, createdAtMillis = old.createdAtMillis))
                    replaceChildren(old.id, rows)
                }
            }
        }
        return true
    }
}

// What an imported item does to the rows already there.
sealed interface ImportTarget<out T> {
    data object New : ImportTarget<Nothing>

    data class Replace<T>(
        val row: T,
    ) : ImportTarget<T>

    // Another item, which isn't the one being replaced, has the name.
    data class NameTaken<T>(
        val row: T,
    ) : ImportTarget<T>
}

// An import replaces the row with its content id ([byId]), else a row made in the app that has its
// name ([byName] without a content id). Names are unique, so any other row with the name is a clash,
// unless the same import gives that row another name ([renamed], by content id).
fun <T> importTarget(
    byId: T?,
    byName: T?,
    idOf: (T) -> Long,
    contentIdOf: (T) -> String?,
    renamed: Set<String> = emptySet(),
): ImportTarget<T> {
    val other = byName?.takeUnless { (byId != null && idOf(it) == idOf(byId)) || contentIdOf(it) in renamed }
    return when {
        byId != null -> if (other == null) ImportTarget.Replace(byId) else ImportTarget.NameTaken(other)
        other == null -> ImportTarget.New
        contentIdOf(other) == null -> ImportTarget.Replace(other)
        else -> ImportTarget.NameTaken(other)
    }
}

// Sits in a row's name between the two passes of an import that renames it; no real name starts so.
internal fun parkedName(contentId: String): String = "\u0001$contentId"
