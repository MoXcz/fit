// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import app.fit.core.moved
import app.fit.routines.inSectionOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface RoutineDao {
    @Transaction
    @Query("SELECT * FROM routine ORDER BY name")
    fun all(): Flow<List<RoutineWithItems>>

    @Transaction
    @Query("SELECT * FROM routine WHERE id = :id")
    fun byId(id: Long): Flow<RoutineWithItems?>

    @Query("SELECT * FROM routine WHERE id = :id")
    suspend fun find(id: Long): RoutineEntity?

    @Query("SELECT * FROM routine WHERE name = :name")
    suspend fun findByName(name: String): RoutineEntity?

    @Query("SELECT * FROM routine WHERE contentId = :contentId")
    suspend fun findByContentId(contentId: String): RoutineEntity?

    @Query("SELECT contentId FROM routine WHERE contentId IS NOT NULL")
    suspend fun contentIds(): List<String>

    @Query("SELECT * FROM routine_item WHERE routineId = :routineId ORDER BY position")
    suspend fun itemsOf(routineId: Long): List<RoutineItemEntity>

    @Query("SELECT * FROM routine_item WHERE id IN (:ids) ORDER BY routineId, position")
    suspend fun items(ids: List<Long>): List<RoutineItemEntity>

    @Insert
    suspend fun insert(routine: RoutineEntity): Long

    @Update
    suspend fun update(routine: RoutineEntity)

    @Query("DELETE FROM routine WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert
    suspend fun insertItem(item: RoutineItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<RoutineItemEntity>)

    @Update
    suspend fun updateItem(item: RoutineItemEntity)

    @Query("DELETE FROM routine_item WHERE id IN (:ids)")
    suspend fun deleteItems(ids: List<Long>)

    @Query("DELETE FROM routine_item WHERE routineId = :routineId")
    suspend fun deleteItemsOf(routineId: Long)

    @Query("UPDATE routine_item SET position = :position WHERE id = :id")
    suspend fun setPosition(
        id: Long,
        position: Int,
    )

    @Query("UPDATE routine SET edited = 1 WHERE id = :id")
    suspend fun markEdited(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: ContentTombstoneEntity)

    @Query("DELETE FROM content_tombstone WHERE kind = :kind AND contentId = :contentId")
    suspend fun deleteTombstone(
        kind: String,
        contentId: String,
    )

    @Query("SELECT contentId FROM content_tombstone WHERE kind = :kind")
    suspend fun tombstones(kind: String): List<String>

    // The picker's Create & add writes the exercise here, so both rows are one transaction.
    @Query("SELECT COUNT(*) FROM exercise WHERE name = :name")
    suspend fun exercisesNamed(name: String): Int

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntity): Long

    // Positions follow the display order: sections where they first appear, items in order inside.
    private suspend fun renumber(routineId: Long) {
        inSectionOrder(itemsOf(routineId)) { it.section }.forEachIndexed { index, item ->
            if (item.position != index) setPosition(item.id, index)
        }
    }

    private suspend fun nextPosition(routineId: Long): Int = (itemsOf(routineId).maxOfOrNull { it.position } ?: -1) + 1

    // The first of "<base>", "<base> copy", "<base> copy 2", … no routine has.
    private suspend fun freeName(
        base: String,
        allowBase: Boolean,
    ): String =
        generateSequence(if (allowBase) 0 else 1) { it + 1 }
            .map { n ->
                when (n) {
                    0 -> base
                    1 -> "$base copy"
                    else -> "$base copy $n"
                }
            }.first { findByName(it) == null }

    // Null when another routine has the name.
    @Transaction
    suspend fun create(routine: RoutineEntity): Long? = if (findByName(routine.name) != null) null else insert(routine)

    // Null when another routine has the name; then nothing is written.
    @Transaction
    suspend fun createWithItems(
        routine: RoutineEntity,
        items: List<RoutineItemEntity>,
    ): Long? {
        val id = create(routine) ?: return null
        insertItems(inSectionOrder(items) { it.section }.mapIndexed { i, item -> item.copy(id = 0, routineId = id, position = i) })
        return id
    }

    @Transaction
    suspend fun rename(
        id: Long,
        name: String,
    ): Boolean {
        val current = find(id) ?: return false
        val other = findByName(name)
        if (other != null && other.id != id) return false
        update(current.copy(name = name, edited = true))
        return true
    }

    @Transaction
    suspend fun setNotes(
        id: Long,
        notes: String,
    ) {
        val current = find(id) ?: return
        update(current.copy(notes = notes, edited = true))
    }

    // A copy is the user's own routine: no content id, named "<name> copy", "<name> copy 2", …
    @Transaction
    suspend fun copy(
        id: Long,
        createdAtMillis: Long,
    ): Long? {
        val source = find(id) ?: return null
        val name = freeName(source.name, allowBase = false)
        val copyId = insert(source.copy(id = 0, contentId = null, name = name, edited = false, createdAtMillis = createdAtMillis))
        insertItems(itemsOf(id).map { it.copy(id = 0, routineId = copyId) })
        return copyId
    }

    // Returns what was deleted, for Undo.
    @Transaction
    suspend fun deleteRoutine(id: Long): RoutineWithItems? {
        val routine = find(id) ?: return null
        val items = itemsOf(id)
        delete(id)
        routine.contentId?.let { insertTombstone(ContentTombstoneEntity(ROUTINE_KIND, it)) }
        return RoutineWithItems(routine, items)
    }

    // Puts a deleted routine back with its ids, renamed "… copy" if its name was taken meanwhile;
    // false only if it's already there.
    @Transaction
    suspend fun restore(snapshot: RoutineWithItems): Boolean {
        val routine = snapshot.routine
        if (find(routine.id) != null) return false
        insert(routine.copy(name = freeName(routine.name, allowBase = true)))
        insertItems(snapshot.items)
        routine.contentId?.let { deleteTombstone(ROUTINE_KIND, it) }
        return true
    }

    // New items go to the end of their section, never into whichever section happens to be last.
    // Null when the routine is gone.
    @Transaction
    suspend fun addItem(item: RoutineItemEntity): Long? {
        if (find(item.routineId) == null) return null
        val id = insertItem(item.copy(id = 0, position = nextPosition(item.routineId)))
        renumber(item.routineId)
        markEdited(item.routineId)
        return id
    }

    // Writes nothing unless both the exercise and the item can be written.
    @Transaction
    suspend fun createExerciseAndAdd(
        exercise: ExerciseEntity,
        item: RoutineItemEntity,
    ): CreateOutcome {
        if (find(item.routineId) == null) return CreateOutcome.RoutineGone
        if (exercisesNamed(exercise.name) > 0) return CreateOutcome.NameTaken
        val exerciseId = insertExercise(exercise)
        val itemId = requireNotNull(addItem(item.copy(exerciseId = exerciseId)))
        return CreateOutcome.Created(exerciseId, itemId)
    }

    @Transaction
    suspend fun saveItem(item: RoutineItemEntity) {
        val current = items(listOf(item.id)).singleOrNull() ?: return
        updateItem(item.copy(routineId = current.routineId, exerciseId = current.exerciseId, position = current.position))
        renumber(current.routineId)
        markEdited(current.routineId)
    }

    // Moves the item [by] places within its section in one go, like a day's blocks.
    @Transaction
    suspend fun move(
        id: Long,
        by: Int,
    ) {
        val item = items(listOf(id)).singleOrNull() ?: return
        val section = itemsOf(item.routineId).filter { it.section == item.section }
        val order = section.moved(section.indexOfFirst { it.id == id }, by)
        if (order == section) return
        order.forEachIndexed { i, moved -> if (moved.position != section[i].position) setPosition(moved.id, section[i].position) }
        markEdited(item.routineId)
    }

    // Returns what was deleted, for Undo.
    @Transaction
    suspend fun removeItems(ids: List<Long>): List<RoutineItemEntity> {
        val removed = items(ids)
        deleteItems(ids)
        removed.map { it.routineId }.distinct().forEach {
            renumber(it)
            markEdited(it)
        }
        return removed
    }

    @Transaction
    suspend fun restoreItems(items: List<RoutineItemEntity>) {
        val routines = items.map { it.routineId }.distinct().filter { find(it) != null }
        val kept = items.filter { it.routineId in routines }
        // Each removed row goes back to the index it had, lowest first, so several rows removed at
        // once land where they were.
        for (routineId in routines) {
            val back = kept.filter { it.routineId == routineId }.sortedBy { it.position }
            val order = itemsOf(routineId).toMutableList()
            insertItems(back)
            back.forEach { order.add(it.position.coerceIn(0, order.size), it) }
            order.forEachIndexed { index, item -> setPosition(item.id, index) }
            renumber(routineId)
        }
    }

    // Appends copies to [routineId]; returns the new item ids.
    @Transaction
    suspend fun copyItemsTo(
        ids: List<Long>,
        routineId: Long,
    ): List<Long> {
        if (find(routineId) == null) return emptyList()
        return items(ids).mapNotNull { addItem(it.copy(id = 0, routineId = routineId)) }
    }

    /**
     * Brings built-in routines up to date, with the same rules as exercises: new ones are added,
     * ones the user never changed are replaced, changed ones are left alone, ones the user deleted
     * stay deleted, and a name another routine already has is skipped rather than duplicated.
     */
    @Transaction
    suspend fun syncBuiltIn(all: List<RoutineWithItems>) {
        val deleted = tombstones(ROUTINE_KIND).toSet()
        for ((routine, items) in all.filter { it.routine.contentId !in deleted }) {
            val contentId = requireNotNull(routine.contentId)
            val existing = findByContentId(contentId)
            val sameName = findByName(routine.name)
            when {
                existing == null && sameName == null -> {
                    val id = insert(routine)
                    insertItems(items.mapIndexed { i, item -> item.copy(id = 0, routineId = id, position = i) })
                }

                existing == null || existing.edited || (sameName != null && sameName.id != existing.id) -> {}

                else -> {
                    update(routine.copy(id = existing.id, createdAtMillis = existing.createdAtMillis))
                    deleteItemsOf(existing.id)
                    insertItems(items.mapIndexed { i, item -> item.copy(id = 0, routineId = existing.id, position = i) })
                }
            }
        }
    }

    // Imported routines replace the routine with their content id, else one made in the app with
    // their name, else are added; they count as the user's own. A routine the user deleted comes
    // back. Renamed rows give their names up first, so two can swap. False, with nothing to keep,
    // when another routine has a name.
    @Transaction
    suspend fun importAll(all: List<RoutineWithItems>): Boolean {
        for ((draft, _) in all) {
            val contentId = requireNotNull(draft.contentId)
            val old = findByContentId(contentId) ?: continue
            if (!old.name.equals(draft.name, ignoreCase = true)) update(old.copy(name = parkedName(contentId)))
        }
        for ((draft, items) in all) {
            val contentId = requireNotNull(draft.contentId)
            val routine = draft.copy(edited = true)
            val id =
                when (val target = importTarget(findByContentId(contentId), findByName(routine.name), { it.id }, { it.contentId })) {
                    is ImportTarget.NameTaken -> {
                        return false
                    }

                    ImportTarget.New -> {
                        insert(routine)
                    }

                    is ImportTarget.Replace -> {
                        update(routine.copy(id = target.row.id, createdAtMillis = target.row.createdAtMillis))
                        deleteItemsOf(target.row.id)
                        target.row.id
                    }
                }
            insertItems(items.mapIndexed { i, item -> item.copy(id = 0, routineId = id, position = i) })
            deleteTombstone(ROUTINE_KIND, contentId)
        }
        return true
    }
}
