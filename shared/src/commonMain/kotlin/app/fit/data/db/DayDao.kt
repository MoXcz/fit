// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import app.fit.core.moved
import app.fit.routines.inSectionOrder
import kotlinx.coroutines.flow.Flow

@Dao
interface DayDao {
    @Transaction
    @Query("SELECT * FROM workout_day WHERE day = :day")
    fun byDay(day: String): Flow<DayWithBlocks?>

    @Transaction
    @Query("SELECT * FROM workout_day WHERE day BETWEEN :from AND :to ORDER BY day")
    fun between(
        from: String,
        to: String,
    ): Flow<List<DayWithBlocks>>

    @Transaction
    @Query("SELECT * FROM workout_day WHERE id = (SELECT dayId FROM block WHERE id = :blockId)")
    fun byBlock(blockId: Long): Flow<DayWithBlocks?>

    // Days with at least one set: an empty day has nothing to copy.
    @Transaction
    @Query(
        "SELECT * FROM workout_day WHERE day < :day AND EXISTS (SELECT 1 FROM block b JOIN set_entry s ON s.blockId = b.id " +
            "WHERE b.dayId = workout_day.id) ORDER BY day DESC LIMIT :limit",
    )
    suspend fun recentBefore(
        day: String,
        limit: Int,
    ): List<DayWithBlocks>

    @Transaction
    @Query("SELECT * FROM workout_day WHERE day = :day")
    suspend fun load(day: String): DayWithBlocks?

    // Days with a done working set, newest first: the workouts History counts and lists.
    @Transaction
    @Query(
        "SELECT * FROM workout_day WHERE EXISTS (SELECT 1 FROM block b JOIN set_entry s ON s.blockId = b.id " +
            "WHERE b.dayId = workout_day.id AND s.doneAtMillis IS NOT NULL AND s.warmup = 0) ORDER BY day DESC",
    )
    fun trainedDays(): Flow<List<DayWithBlocks>>

    @Query(
        "SELECT s.*, d.day AS day, b.exerciseId AS exerciseId, d.deload AS deload FROM set_entry s JOIN block b ON b.id = s.blockId " +
            "JOIN workout_day d ON d.id = b.dayId WHERE b.exerciseId = :exerciseId AND s.doneAtMillis IS NOT NULL AND s.warmup = 0 " +
            "ORDER BY d.day, s.doneAtMillis, s.position",
    )
    fun doneWorkingSets(exerciseId: Long): Flow<List<PastSetRow>>

    // Earlier days only, so a day never progresses from its own sets.
    @Query(
        "SELECT s.*, d.day AS day, b.exerciseId AS exerciseId, d.deload AS deload FROM set_entry s JOIN block b ON b.id = s.blockId " +
            "JOIN workout_day d ON d.id = b.dayId WHERE b.exerciseId IN (:exerciseIds) AND s.doneAtMillis IS NOT NULL AND d.day < :day",
    )
    suspend fun doneSets(
        exerciseIds: List<Long>,
        day: String,
    ): List<PastSetRow>

    @Query("SELECT * FROM workout_day WHERE day = :day")
    suspend fun find(day: String): WorkoutDayEntity?

    @Query("SELECT * FROM workout_day WHERE id = :id")
    suspend fun findById(id: Long): WorkoutDayEntity?

    @Insert
    suspend fun insertDay(day: WorkoutDayEntity): Long

    @Update
    suspend fun updateDay(day: WorkoutDayEntity)

    @Query("DELETE FROM workout_day WHERE id = :id")
    suspend fun deleteDayRow(id: Long)

    @Query("SELECT * FROM block WHERE dayId = :dayId ORDER BY position")
    suspend fun blocksOf(dayId: Long): List<BlockEntity>

    @Query("SELECT * FROM block WHERE id = :id")
    suspend fun findBlock(id: Long): BlockEntity?

    @Insert
    suspend fun insertBlock(block: BlockEntity): Long

    @Insert
    suspend fun insertBlocks(blocks: List<BlockEntity>)

    @Query("UPDATE block SET position = :position WHERE id = :id")
    suspend fun setBlockPosition(
        id: Long,
        position: Int,
    )

    @Query("DELETE FROM block WHERE id = :id")
    suspend fun deleteBlockRow(id: Long)

    @Query("DELETE FROM block WHERE dayId = :dayId")
    suspend fun deleteBlocksOf(dayId: Long)

    @Query("SELECT * FROM set_entry WHERE blockId = :blockId ORDER BY position")
    suspend fun setsOf(blockId: Long): List<SetEntryEntity>

    @Query("SELECT * FROM set_entry WHERE id = :id")
    suspend fun findSet(id: Long): SetEntryEntity?

    @Insert
    suspend fun insertSets(sets: List<SetEntryEntity>)

    @Update
    suspend fun updateSets(sets: List<SetEntryEntity>)

    @Query("UPDATE set_entry SET position = :position WHERE id = :id")
    suspend fun setSetPosition(
        id: Long,
        position: Int,
    )

    @Query("DELETE FROM set_entry WHERE id = :id")
    suspend fun deleteSetRow(id: Long)

    // The picker's Create & add writes the exercise here, so both rows are one transaction.
    @Query("SELECT COUNT(*) FROM exercise WHERE name = :name")
    suspend fun exercisesNamed(name: String): Int

    @Insert
    suspend fun insertExercise(exercise: ExerciseEntity): Long

    @Query("SELECT * FROM exercise WHERE id = :id")
    suspend fun findExercise(id: Long): ExerciseEntity?

    private suspend fun ensureDay(day: String): WorkoutDayEntity =
        find(day) ?: WorkoutDayEntity(day = day, name = "", notes = "", startedAtMillis = null, finishedAtMillis = null).let {
            it.copy(id = insertDay(it))
        }

    // Positions follow the display order: sections where they first appear, blocks in order inside.
    private suspend fun renumberBlocks(dayId: Long) {
        inSectionOrder(blocksOf(dayId)) { it.section }.forEachIndexed { index, block ->
            if (block.position != index) setBlockPosition(block.id, index)
        }
    }

    // Warm-up sets first, then working sets, each keeping its order.
    private suspend fun renumberSets(blockId: Long) {
        orderedSets(blockId).forEachIndexed { index, set ->
            if (set.position != index) setSetPosition(set.id, index)
        }
    }

    private suspend fun insertBlockRows(
        dayId: Long,
        rows: BlockRows,
    ): Long {
        val position = (blocksOf(dayId).maxOfOrNull { it.position } ?: -1) + 1
        val id = insertBlock(rows.block.copy(id = 0, dayId = dayId, position = position))
        insertSets(rows.sets.mapIndexed { index, set -> set.copy(id = 0, blockId = id, position = index) })
        return id
    }

    // Replace starts the day over: its blocks, its clock and its fatigue go, and [deload] says what
    // the day now is. Adding deload loads makes the day a deload day; other adds leave it as it was.
    @Transaction
    suspend fun addBlocks(
        day: String,
        blocks: List<BlockRows>,
        replace: Boolean = false,
        name: String? = null,
        nameIfBlank: Boolean = true,
        deload: Boolean = false,
    ): List<Long> {
        val row = ensureDay(day)
        if (replace) deleteBlocksOf(row.id)
        val ids = blocks.map { insertBlockRows(row.id, it) }
        renumberBlocks(row.id)
        val named = if (name != null && (!nameIfBlank || row.name.isBlank())) row.copy(name = name) else row
        val marked = named.copy(deload = if (replace) deload else named.deload || deload)
        val changed = if (replace) marked.copy(startedAtMillis = null, finishedAtMillis = null, fatigue = null) else marked
        if (changed != row) updateDay(changed)
        return ids
    }

    // Writes nothing unless both the exercise and the block can be written.
    @Transaction
    suspend fun createExerciseAndAdd(
        day: String,
        exercise: ExerciseEntity,
        rows: BlockRows,
    ): CreateBlockOutcome {
        if (exercisesNamed(exercise.name) > 0) return CreateBlockOutcome.NameTaken
        val exerciseId = insertExercise(exercise)
        val blockId = addBlocks(day, listOf(rows.copy(block = rows.block.copy(exerciseId = exerciseId)))).single()
        return CreateBlockOutcome.Created(exerciseId, blockId)
    }

    @Transaction
    suspend fun removeBlock(id: Long): BlockWithSets? {
        val block = findBlock(id) ?: return null
        val sets = setsOf(id)
        deleteBlockRow(id)
        renumberBlocks(block.dayId)
        return BlockWithSets(block, sets)
    }

    // Puts a removed block back where it was; false if its day is gone or it's already there.
    @Transaction
    suspend fun restoreBlock(snapshot: BlockWithSets): Boolean {
        val block = snapshot.block
        if (findById(block.dayId) == null || findBlock(block.id) != null) return false
        val order = blocksOf(block.dayId).toMutableList()
        insertBlock(block)
        insertSets(snapshot.sets)
        order.add(block.position.coerceIn(0, order.size), block)
        order.forEachIndexed { index, it -> setBlockPosition(it.id, index) }
        renumberBlocks(block.dayId)
        return true
    }

    // Moves the block [by] places within its section in one go: the section's positions are handed
    // out again in the new order.
    @Transaction
    suspend fun moveBlock(
        id: Long,
        by: Int,
    ) {
        val block = findBlock(id) ?: return
        val section = blocksOf(block.dayId).filter { it.section == block.section }
        section.moved(section.indexOfFirst { it.id == id }, by).forEachIndexed { i, moved ->
            if (moved.position != section[i].position) setBlockPosition(moved.id, section[i].position)
        }
    }

    @Transaction
    suspend fun deleteDay(day: String): DayWithBlocks? {
        val snapshot = load(day) ?: return null
        deleteDayRow(snapshot.day.id)
        return snapshot
    }

    // Puts a deleted day back with its ids. An empty day made for the date since is replaced; one
    // with exercises in it is kept, and nothing is restored.
    @Transaction
    suspend fun restoreDay(snapshot: DayWithBlocks): Boolean {
        val current = load(snapshot.day.day)
        if (current != null) {
            if (current.blocks.isNotEmpty()) return false
            deleteDayRow(current.day.id)
        }
        insertDay(snapshot.day)
        insertBlocks(snapshot.blocks.map { it.block })
        insertSets(snapshot.blocks.flatMap { it.sets })
        return true
    }

    @Transaction
    suspend fun editDay(
        day: String,
        change: (WorkoutDayEntity) -> WorkoutDayEntity,
    ) {
        val row = ensureDay(day)
        val changed = change(row)
        if (changed != row) updateDay(changed.copy(id = row.id, day = row.day))
    }

    // Read, change and write in one transaction, so a tick and an edit can't overwrite each other.
    // A tick on [today] when it hasn't started starts its clock; other days get their times by hand.
    @Transaction
    suspend fun changeSet(
        id: Long,
        startAtMillis: Long? = null,
        today: String? = null,
        change: (SetEntryEntity, ExerciseEntity?, BlockEntity) -> SetEntryEntity,
    ) {
        val set = findSet(id) ?: return
        val block = findBlock(set.blockId) ?: return
        val changed = change(set, findExercise(block.exerciseId), block).copy(id = set.id, blockId = set.blockId, position = set.position)
        if (changed == set) return
        updateSets(listOf(changed))
        if (startAtMillis == null || changed.doneAtMillis == null) return
        val day = findById(block.dayId) ?: return
        if (day.day == today && day.startedAtMillis == null && day.finishedAtMillis == null) {
            updateDay(day.copy(startedAtMillis = startAtMillis))
        }
    }

    @Transaction
    suspend fun changeSets(
        blockId: Long,
        change: (List<SetEntryEntity>) -> List<SetEntryEntity>,
    ) {
        val sets = setsOf(blockId)
        val changed = change(sets).filter { it !in sets }
        if (changed.isNotEmpty()) updateSets(changed)
    }

    // [finishAt] gets the last done set's time, if any.
    @Transaction
    suspend fun finish(
        day: String,
        finishAt: (lastDoneMillis: Long?) -> Long,
    ) {
        val snapshot = load(day) ?: return
        val at =
            finishAt(
                snapshot.blocks
                    .flatMap { it.sets }
                    .mapNotNull { it.doneAtMillis }
                    .maxOrNull(),
            )
        updateDay(snapshot.day.copy(startedAtMillis = snapshot.day.startedAtMillis ?: at, finishedAtMillis = at))
    }

    @Transaction
    suspend fun addSets(
        blockId: Long,
        sets: List<SetEntryEntity>,
    ) {
        if (findBlock(blockId) == null) return
        val next = (setsOf(blockId).maxOfOrNull { it.position } ?: -1) + 1
        insertSets(sets.mapIndexed { index, set -> set.copy(id = 0, blockId = blockId, position = next + index) })
        renumberSets(blockId)
    }

    // Remembers the set's place among the warm-ups or the working sets, which later adds don't shift.
    @Transaction
    suspend fun removeSet(id: Long): RemovedSetRow? {
        val set = findSet(id) ?: return null
        val index = orderedSets(set.blockId).filter { it.warmup == set.warmup }.indexOfFirst { it.id == id }
        deleteSetRow(id)
        renumberSets(set.blockId)
        return RemovedSetRow(set, index)
    }

    @Transaction
    suspend fun restoreSet(removed: RemovedSetRow): Boolean {
        val set = removed.set
        if (findBlock(set.blockId) == null || findSet(set.id) != null) return false
        val (warmups, working) = orderedSets(set.blockId).partition { it.warmup }
        val group = (if (set.warmup) warmups else working).toMutableList()
        group.add(removed.index.coerceIn(0, group.size), set)
        insertSets(listOf(set))
        (if (set.warmup) group + working else warmups + group).forEachIndexed { index, it -> setSetPosition(it.id, index) }
        return true
    }

    private suspend fun orderedSets(blockId: Long) = setsOf(blockId).sortedWith(compareBy({ !it.warmup }, { it.position }))
}
