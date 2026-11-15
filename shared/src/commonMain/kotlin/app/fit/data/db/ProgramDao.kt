// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ProgramDao {
    // The active one first, then by name.
    @Transaction
    @Query("SELECT * FROM program ORDER BY active DESC, name")
    fun all(): Flow<List<ProgramWithDays>>

    @Transaction
    @Query("SELECT * FROM program WHERE id = :id")
    fun byId(id: Long): Flow<ProgramWithDays?>

    @Transaction
    @Query("SELECT * FROM program WHERE active = 1 LIMIT 1")
    fun active(): Flow<ProgramWithDays?>

    @Query("SELECT * FROM program WHERE id = :id")
    suspend fun find(id: Long): ProgramEntity?

    @Query("SELECT * FROM program WHERE name = :name")
    suspend fun findByName(name: String): ProgramEntity?

    @Query("SELECT * FROM program WHERE contentId = :contentId")
    suspend fun findByContentId(contentId: String): ProgramEntity?

    @Query("SELECT COUNT(*) FROM program WHERE active = 1")
    suspend fun activeCount(): Int

    @Query("SELECT * FROM program_day WHERE programId = :programId")
    suspend fun daysOf(programId: Long): List<ProgramDayEntity>

    @Query("SELECT * FROM program_day WHERE routineId = :routineId")
    suspend fun daysUsing(routineId: Long): List<ProgramDayEntity>

    @Query("SELECT id FROM routine WHERE contentId = :contentId")
    suspend fun routineByContentId(contentId: String): Long?

    @Query("SELECT COUNT(*) FROM routine WHERE id = :id")
    suspend fun routineExists(id: Long): Int

    @Insert
    suspend fun insert(program: ProgramEntity): Long

    @Update
    suspend fun update(program: ProgramEntity)

    @Query("DELETE FROM program WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE program SET active = 0 WHERE active = 1")
    suspend fun deactivateAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: ProgramDayEntity)

    @Insert
    suspend fun insertDays(days: List<ProgramDayEntity>)

    @Query("DELETE FROM program_day WHERE programId = :programId AND weekday = :weekday")
    suspend fun deleteDay(
        programId: Long,
        weekday: Int,
    )

    @Query("DELETE FROM program_day WHERE programId = :programId")
    suspend fun deleteDaysOf(programId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: ContentTombstoneEntity)

    @Query("DELETE FROM content_tombstone WHERE kind = :kind AND contentId = :contentId")
    suspend fun deleteTombstone(
        kind: String,
        contentId: String,
    )

    @Query("SELECT contentId FROM content_tombstone WHERE kind = :kind")
    suspend fun tombstones(kind: String): List<String>

    // The first of "<base>", "<base> copy", "<base> copy 2", … no program has.
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

    // Null when another program has the name.
    @Transaction
    suspend fun create(program: ProgramEntity): Long? = if (findByName(program.name) != null) null else insert(program)

    // A change to what the program is (name, notes, days, deloads) marks it edited, so content
    // updates leave it alone; the user's own state (its start) doesn't ([content] false). False when
    // it's gone or the name is taken.
    @Transaction
    suspend fun edit(
        id: Long,
        content: Boolean = true,
        change: (ProgramEntity) -> ProgramEntity,
    ): Boolean {
        val current = find(id) ?: return false
        val changed = change(current).copy(id = id)
        if (changed.name != current.name && findByName(changed.name)?.let { it.id != id } == true) return false
        if (changed != current) update(if (content) changed.copy(edited = true) else changed)
        return true
    }

    // One active program or none: activating one deactivates the rest in the same write. A program
    // activated without a start starts on [startsOn]. Neither marks it edited: it's the user's state.
    @Transaction
    suspend fun setActive(
        id: Long,
        active: Boolean,
        startsOn: String,
    ) {
        val current = find(id) ?: return
        if (active) {
            deactivateAll()
            update(current.copy(active = true, startsOn = current.startsOn ?: startsOn))
        } else if (current.active) {
            update(current.copy(active = false))
        }
    }

    // A rest day has no row.
    @Transaction
    suspend fun setDay(
        programId: Long,
        weekday: Int,
        routineId: Long?,
    ) {
        val current = find(programId) ?: return
        if (routineId == null) deleteDay(programId, weekday) else upsertDay(ProgramDayEntity(programId, weekday, routineId))
        update(current.copy(edited = true))
    }

    // A copy is the user's own and isn't active: no content id, named "<name> copy", "<name> copy 2", …
    @Transaction
    suspend fun copy(
        id: Long,
        createdAtMillis: Long,
    ): Long? {
        val source = find(id) ?: return null
        val name = freeName(source.name, allowBase = false)
        val copyId =
            insert(source.copy(id = 0, contentId = null, name = name, active = false, edited = false, createdAtMillis = createdAtMillis))
        insertDays(daysOf(id).map { it.copy(programId = copyId) })
        return copyId
    }

    // Returns what was deleted, for Undo.
    @Transaction
    suspend fun deleteProgram(id: Long): ProgramWithDays? {
        val program = find(id) ?: return null
        val days = daysOf(id)
        delete(id)
        program.contentId?.let { insertTombstone(ContentTombstoneEntity(PROGRAM_KIND, it)) }
        return ProgramWithDays(program, days)
    }

    // Puts a deleted program back with its ids, renamed "… copy" if its name was taken meanwhile.
    // It's active again only if no other program became active since; a day whose routine was
    // deleted meanwhile is a rest day. False if it's already there.
    @Transaction
    suspend fun restore(snapshot: ProgramWithDays): Boolean {
        val program = snapshot.program
        if (find(program.id) != null) return false
        val active = program.active && activeCount() == 0
        insert(program.copy(name = freeName(program.name, allowBase = true), active = active))
        insertDays(snapshot.days.filter { day -> day.routineId?.let { routineExists(it) > 0 } ?: true })
        program.contentId?.let { deleteTombstone(PROGRAM_KIND, it) }
        return true
    }

    // Days a deleted routine left as rest days get it back, unless they were given another since.
    @Transaction
    suspend fun relink(
        days: List<ProgramDayEntity>,
        routineId: Long,
    ) {
        if (routineExists(routineId) == 0) return
        days.forEach { day ->
            if (find(day.programId) == null) return@forEach
            val now = daysOf(day.programId).firstOrNull { it.weekday == day.weekday }
            if (now == null || now.routineId == null) upsertDay(day.copy(routineId = routineId))
        }
    }

    // Built-in programs follow their files as routines do (new added, unedited replaced, edited and
    // deleted left alone, a taken name skipped), keeping the user's start and whether it's active. A
    // day whose routine isn't in the database is a rest day.
    @Transaction
    suspend fun syncBuiltIn(all: List<Pair<ProgramEntity, Map<Int, String>>>) {
        val deleted = tombstones(PROGRAM_KIND).toSet()
        for ((program, schedule) in all.filter { it.first.contentId !in deleted }) {
            val contentId = requireNotNull(program.contentId)
            val existing = findByContentId(contentId)
            val sameName = findByName(program.name)
            val id =
                when {
                    existing == null && sameName == null -> {
                        insert(program)
                    }

                    existing == null || existing.edited || (sameName != null && sameName.id != existing.id) -> {
                        continue
                    }

                    else -> {
                        update(
                            program.copy(
                                id = existing.id,
                                active = existing.active,
                                startsOn = existing.startsOn,
                                createdAtMillis = existing.createdAtMillis,
                            ),
                        )
                        deleteDaysOf(existing.id)
                        existing.id
                    }
                }
            val days = schedule.mapNotNull { (weekday, routine) -> routineByContentId(routine)?.let { ProgramDayEntity(id, weekday, it) } }
            insertDays(days)
        }
    }

    // Imported programs replace the program with their content id, else one made in the app with
    // their name, else are added inactive; they count as the user's own and keep the user's start and
    // whether it's active. False, with nothing to keep, when another program has a name.
    @Transaction
    suspend fun importAll(all: List<Pair<ProgramEntity, Map<Int, String>>>): Boolean {
        for ((draft, _) in all) {
            val contentId = requireNotNull(draft.contentId)
            val old = findByContentId(contentId) ?: continue
            if (!old.name.equals(draft.name, ignoreCase = true)) update(old.copy(name = parkedName(contentId)))
        }
        for ((draft, schedule) in all) {
            val contentId = requireNotNull(draft.contentId)
            val program = draft.copy(edited = true)
            val id =
                when (val target = importTarget(findByContentId(contentId), findByName(program.name), { it.id }, { it.contentId })) {
                    is ImportTarget.NameTaken -> {
                        return false
                    }

                    ImportTarget.New -> {
                        insert(program)
                    }

                    is ImportTarget.Replace -> {
                        val old = target.row
                        update(
                            program.copy(id = old.id, active = old.active, startsOn = old.startsOn, createdAtMillis = old.createdAtMillis),
                        )
                        deleteDaysOf(old.id)
                        old.id
                    }
                }
            insertDays(schedule.mapNotNull { (weekday, routine) -> routineByContentId(routine)?.let { ProgramDayEntity(id, weekday, it) } })
            deleteTombstone(PROGRAM_KIND, contentId)
        }
        return true
    }
}
