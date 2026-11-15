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
interface LessonDao {
    @Transaction
    @Query("SELECT * FROM lesson ORDER BY title COLLATE NOCASE")
    fun lessons(): Flow<List<LessonRows>>

    @Transaction
    @Query("SELECT * FROM lesson WHERE id = :id")
    fun byId(id: Long): Flow<LessonRows?>

    @Transaction
    @Query(
        "SELECT * FROM lesson WHERE id IN (SELECT lessonId FROM lesson_exercise WHERE exerciseId = :exerciseId) " +
            "ORDER BY title COLLATE NOCASE",
    )
    fun forExercise(exerciseId: Long): Flow<List<LessonRows>>

    @Query("SELECT * FROM lesson WHERE contentId = :contentId")
    suspend fun findByContentId(contentId: String): LessonEntity?

    @Query("SELECT * FROM guided_plan WHERE id = :id")
    suspend fun findPlan(id: Long): GuidedPlanEntity?

    @Query("SELECT id FROM exercise WHERE contentId = :contentId")
    suspend fun exerciseByContentId(contentId: String): Long?

    @Insert
    suspend fun insert(lesson: LessonEntity): Long

    @Update
    suspend fun update(lesson: LessonEntity)

    @Insert
    suspend fun insertPlan(plan: GuidedPlanEntity): Long

    @Update
    suspend fun updatePlan(plan: GuidedPlanEntity)

    @Insert
    suspend fun insertSteps(steps: List<GuidedStepEntity>)

    @Query("DELETE FROM guided_step WHERE planId = :planId")
    suspend fun deleteSteps(planId: Long)

    @Insert
    suspend fun insertExercises(exercises: List<LessonExerciseEntity>)

    @Query("DELETE FROM lesson_exercise WHERE lessonId = :lessonId")
    suspend fun deleteExercises(lessonId: Long)

    @Insert
    suspend fun insertSources(sources: List<LessonSourceEntity>)

    @Query("DELETE FROM lesson_source WHERE lessonId = :lessonId")
    suspend fun deleteSources(lessonId: Long)

    @Transaction
    @Query("SELECT * FROM lesson WHERE id = :id")
    suspend fun rows(id: Long): LessonRows?

    @Transaction
    @Query("SELECT * FROM guided_plan WHERE id = :id")
    suspend fun planRows(id: Long): GuidedPlanRows?

    @Query("DELETE FROM lesson WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM guided_plan WHERE id = :id")
    suspend fun deletePlan(id: Long)

    @Query("SELECT id FROM guided_log WHERE planId = :planId")
    suspend fun logIdsOf(planId: Long): List<Long>

    @Query("UPDATE guided_log SET planId = :planId WHERE id IN (:ids)")
    suspend fun relinkLogs(
        planId: Long,
        ids: List<Long>,
    )

    @Query("SELECT COUNT(*) FROM exercise WHERE id = :id")
    suspend fun exerciseExists(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTombstone(tombstone: ContentTombstoneEntity)

    @Query("DELETE FROM content_tombstone WHERE kind = :kind AND contentId = :contentId")
    suspend fun deleteTombstone(
        kind: String,
        contentId: String,
    )

    @Query("SELECT contentId FROM content_tombstone WHERE kind = :kind")
    suspend fun tombstones(kind: String): List<String>

    // Returns what was deleted, for Undo: the lesson, its drill, and the runs that counted as practice.
    @Transaction
    suspend fun deleteLesson(id: Long): DeletedLessonRows? {
        val rows = rows(id) ?: return null
        val practice = rows.lesson.practicePlanId?.let { planRows(it) }
        val logs = practice?.let { logIdsOf(it.plan.id) }.orEmpty()
        delete(id)
        practice?.let { deletePlan(it.plan.id) }
        rows.lesson.contentId?.let { insertTombstone(ContentTombstoneEntity(LESSON_KIND, it)) }
        return DeletedLessonRows(rows, practice, logs)
    }

    // Puts a deleted lesson back with its drill and practice count. False if it's already there or an
    // import took its content id meanwhile. An exercise that went (a wipe) is left out.
    @Transaction
    suspend fun restoreLesson(deleted: DeletedLessonRows): Boolean {
        val lesson = deleted.lesson.lesson
        if (rows(lesson.id) != null || lesson.contentId?.let { findByContentId(it) } != null) return false
        val practice = deleted.practice?.takeIf { findPlan(it.plan.id) == null }
        if (practice != null) {
            insertPlan(practice.plan)
            insertSteps(practice.steps.filter { step -> step.exerciseId?.let { exerciseExists(it) > 0 } ?: true })
            relinkLogs(practice.plan.id, deleted.logIds)
        }
        insert(lesson.copy(practicePlanId = practice?.plan?.id))
        insertExercises(deleted.lesson.exercises.filter { exerciseExists(it.exerciseId) > 0 })
        insertSources(deleted.lesson.sources)
        lesson.contentId?.let { deleteTombstone(LESSON_KIND, it) }
        return true
    }

    // Built-in lessons follow their files; ones the user deleted or imported over are left alone. An
    // import replaces whatever has its id. A lesson keeps its drill's plan (left without steps when
    // its file drops the drill), so the runs logged against it still count.
    @Transaction
    suspend fun writeContent(
        all: List<LessonSync>,
        imported: Boolean,
    ) {
        val deleted = if (imported) emptySet() else tombstones(LESSON_KIND).toSet()
        for (sync in all) {
            val contentId = requireNotNull(sync.lesson.contentId)
            val existing = findByContentId(contentId)
            if (!imported && (contentId in deleted || existing?.edited == true)) continue
            val kept = existing?.practicePlanId?.let { findPlan(it) }
            val planId =
                when {
                    kept != null -> {
                        deleteSteps(kept.id)
                        sync.practice?.let { updatePlan(it.copy(id = kept.id, createdAtMillis = kept.createdAtMillis)) }
                        kept.id
                    }

                    sync.practice != null -> {
                        insertPlan(sync.practice)
                    }

                    else -> {
                        null
                    }
                }
            if (planId != null && sync.practice != null) {
                val steps =
                    sync.steps.mapNotNull { (step, exercise) ->
                        if (exercise == null) return@mapNotNull step
                        exerciseByContentId(exercise)?.let { step.copy(exerciseId = it) }
                    }
                insertSteps(steps.mapIndexed { i, step -> step.copy(id = 0, planId = planId, position = i) })
            }
            val lesson = sync.lesson.copy(practicePlanId = planId, edited = imported)
            val id =
                if (existing == null) {
                    insert(lesson)
                } else {
                    update(lesson.copy(id = existing.id, createdAtMillis = existing.createdAtMillis))
                    deleteExercises(existing.id)
                    deleteSources(existing.id)
                    existing.id
                }
            val exercises = sync.exercises.mapNotNull { exerciseByContentId(it) }.distinct()
            insertExercises(
                exercises.mapIndexed {
                    i,
                    exercise,
                    ->
                    LessonExerciseEntity(lessonId = id, position = i, exerciseId = exercise)
                },
            )
            insertSources(sync.sources.mapIndexed { i, source -> source.copy(lessonId = id, position = i) })
            if (imported) deleteTombstone(LESSON_KIND, contentId)
        }
    }
}
