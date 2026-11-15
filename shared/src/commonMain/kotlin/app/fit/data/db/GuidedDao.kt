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
interface GuidedDao {
    @Transaction
    @Query("SELECT * FROM guided_plan WHERE kind = :kind ORDER BY title COLLATE NOCASE")
    fun plans(kind: String): Flow<List<GuidedPlanRows>>

    @Transaction
    @Query("SELECT * FROM guided_plan WHERE id = :id")
    fun byId(id: Long): Flow<GuidedPlanRows?>

    @Query("SELECT * FROM guided_plan WHERE id = :id")
    suspend fun find(id: Long): GuidedPlanEntity?

    @Query("SELECT * FROM guided_plan WHERE contentId = :contentId")
    suspend fun findByContentId(contentId: String): GuidedPlanEntity?

    @Query("SELECT id FROM exercise WHERE contentId = :contentId")
    suspend fun exerciseByContentId(contentId: String): Long?

    @Insert
    suspend fun insert(plan: GuidedPlanEntity): Long

    @Update
    suspend fun update(plan: GuidedPlanEntity)

    @Insert
    suspend fun insertSteps(steps: List<GuidedStepEntity>)

    @Insert
    suspend fun insertSources(sources: List<GuidedSourceEntity>)

    @Query("DELETE FROM guided_step WHERE planId = :planId")
    suspend fun deleteSteps(planId: Long)

    @Query("DELETE FROM guided_source WHERE planId = :planId")
    suspend fun deleteSources(planId: Long)

    // Pinning is the user's state, not a change to the plan: it never marks it edited.
    @Query("UPDATE guided_plan SET pinnedAtMillis = :at WHERE id = :id")
    suspend fun setPinned(
        id: Long,
        at: Long?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertLog(log: GuidedLogEntity)

    @Query("SELECT * FROM guided_log WHERE kind = :kind AND day BETWEEN :from AND :to ORDER BY startedAtMillis")
    fun logs(
        kind: String,
        from: String,
        to: String,
    ): Flow<List<GuidedLogEntity>>

    @Query("SELECT * FROM guided_log WHERE kind = :kind ORDER BY startedAtMillis")
    fun logs(kind: String): Flow<List<GuidedLogEntity>>

    @Transaction
    @Query("SELECT * FROM guided_plan WHERE id = :id")
    suspend fun rows(id: Long): GuidedPlanRows?

    // Every stored step that runs a content exercise, with the session or lesson it belongs to.
    @Query(
        "SELECT p.title AS planTitle, p.contentId AS planContentId, l.contentId AS lessonContentId, l.title AS lessonTitle, " +
            "e.contentId AS exercise, s.durationSeconds AS durationSeconds, s.reps AS reps " +
            "FROM guided_step s JOIN guided_plan p ON p.id = s.planId JOIN exercise e ON e.id = s.exerciseId " +
            "LEFT JOIN lesson l ON l.practicePlanId = p.id WHERE e.contentId IS NOT NULL ORDER BY p.id, s.position",
    )
    suspend fun exerciseSteps(): List<StoredStep>

    @Query("DELETE FROM guided_plan WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT id FROM guided_log WHERE planId = :planId")
    suspend fun logIdsOf(planId: Long): List<Long>

    @Query("UPDATE guided_log SET planId = :planId WHERE id IN (:ids)")
    suspend fun relinkLogs(
        planId: Long,
        ids: List<Long>,
    )

    @Query("SELECT COUNT(*) FROM exercise WHERE id = :id")
    suspend fun exerciseExists(id: Long): Int

    @Query("SELECT * FROM reminder WHERE planId = :planId")
    suspend fun reminderOf(planId: Long): ReminderEntity?

    @Insert
    suspend fun insertReminder(reminder: ReminderEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: ContentTombstoneEntity)

    @Query("DELETE FROM content_tombstone WHERE kind = :kind AND contentId = :contentId")
    suspend fun deleteTombstone(
        kind: String,
        contentId: String,
    )

    @Query("SELECT contentId FROM content_tombstone WHERE kind = :kind")
    suspend fun tombstones(kind: String): List<String>

    // Returns what was deleted, for Undo. Its logs stay, unlinked (SET NULL).
    @Transaction
    suspend fun deletePlan(id: Long): DeletedPlanRows? {
        val rows = rows(id) ?: return null
        val logs = logIdsOf(id)
        val reminder = reminderOf(id)
        delete(id)
        rows.plan.contentId?.let { insertTombstone(ContentTombstoneEntity(RECOVERY_KIND, it)) }
        return DeletedPlanRows(rows, logs, reminder)
    }

    // Puts a deleted plan back with its id, pin, logs and reminder. False if it's already there or an import
    // took its content id meanwhile. A step whose exercise went (a wipe) is left out.
    @Transaction
    suspend fun restorePlan(deleted: DeletedPlanRows): Boolean {
        val plan = deleted.rows.plan
        if (find(plan.id) != null || plan.contentId?.let { findByContentId(it) } != null) return false
        insert(plan)
        insertSteps(deleted.rows.steps.filter { step -> step.exerciseId?.let { exerciseExists(it) > 0 } ?: true })
        insertSources(deleted.rows.sources)
        relinkLogs(plan.id, deleted.logIds)
        deleted.reminder?.let { insertReminder(it) }
        plan.contentId?.let { deleteTombstone(RECOVERY_KIND, it) }
        return true
    }

    // Built-in sessions follow their files: new ones added, the rest replaced keeping the pin; ones
    // the user deleted or imported over are left alone. An import replaces whatever has its id.
    // A step whose exercise isn't in the database is left out.
    @Transaction
    suspend fun writeContent(
        all: List<Triple<GuidedPlanEntity, List<Pair<GuidedStepEntity, String?>>, List<GuidedSourceEntity>>>,
        imported: Boolean,
    ) {
        val deleted = if (imported) emptySet() else tombstones(RECOVERY_KIND).toSet()
        for ((draft, steps, sources) in all) {
            val contentId = requireNotNull(draft.contentId)
            val existing = findByContentId(contentId)
            if (!imported && (contentId in deleted || existing?.edited == true)) continue
            val plan = draft.copy(edited = imported)
            val id =
                when {
                    existing == null -> {
                        insert(plan)
                    }

                    else -> {
                        update(
                            plan.copy(
                                id = existing.id,
                                pinnedAtMillis = existing.pinnedAtMillis,
                                createdAtMillis = existing.createdAtMillis,
                            ),
                        )
                        deleteSteps(existing.id)
                        deleteSources(existing.id)
                        existing.id
                    }
                }
            val rows =
                steps.mapNotNull { (step, exercise) ->
                    if (exercise == null) return@mapNotNull step
                    exerciseByContentId(exercise)?.let { step.copy(exerciseId = it) }
                }
            insertSteps(rows.mapIndexed { i, step -> step.copy(id = 0, planId = id, position = i) })
            insertSources(sources.mapIndexed { i, source -> source.copy(planId = id, position = i) })
            if (imported) deleteTombstone(RECOVERY_KIND, contentId)
        }
    }
}
