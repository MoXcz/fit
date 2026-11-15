// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons

import app.fit.content.LessonDraft
import app.fit.content.Source
import app.fit.data.db.DeletedLessonRows
import app.fit.data.db.GuidedPlanEntity
import app.fit.data.db.LessonDao
import app.fit.data.db.LessonEntity
import app.fit.data.db.LessonRows
import app.fit.data.db.LessonSourceEntity
import app.fit.data.db.LessonSync
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.guided.GuidedKind
import app.fit.guided.GuidedPlanId
import app.fit.guided.column
import app.fit.guided.toEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlin.time.Clock

/** Lessons, from built-in or imported content. */
interface LessonRepository {
    /** Every lesson by title. */
    val lessons: Flow<List<Lesson>>

    fun lesson(id: LessonId): Flow<Lesson?>

    /** The lessons that teach [exercise] (it's in their `exercises:`), by title. */
    fun lessonsFor(exercise: ExerciseId): Flow<List<Lesson>>

    /** Deletes a lesson with its drill and returns it, for [restore]. Null if it's gone. */
    suspend fun delete(id: LessonId): DeletedLesson?

    /** Puts a deleted lesson back with its drill and practice count; false if it can't (it's there, or its id was imported since). */
    suspend fun restore(deleted: DeletedLesson): Boolean

    /** Adds and updates built-in lessons and their drills from content (see [LessonDao.writeContent]). */
    suspend fun syncBuiltIn(drafts: List<LessonDraft>)
}

/** A lesson that was just deleted; only the repository that made it can put it back. */
interface DeletedLesson {
    val title: String

    /** Its drill, which went with it. */
    val practice: GuidedPlanId?
}

private class RoomDeletedLesson(
    val rows: DeletedLessonRows,
) : DeletedLesson {
    override val title: String get() = rows.lesson.lesson.title
    override val practice: GuidedPlanId? get() = rows.practice?.let { GuidedPlanId(it.plan.id) }
}

class RoomLessonRepository(
    private val dao: LessonDao,
    exercises: ExerciseRepository,
    private val clock: Clock,
) : LessonRepository {
    private val library: Flow<Map<ExerciseId, Exercise>> = exercises.exercises.map { list -> list.associateBy { it.id } }

    override val lessons: Flow<List<Lesson>> = combine(dao.lessons(), library) { rows, library -> rows.map { it.toLesson(library) } }

    override fun lesson(id: LessonId): Flow<Lesson?> = combine(dao.byId(id.value), library) { row, library -> row?.toLesson(library) }

    override fun lessonsFor(exercise: ExerciseId): Flow<List<Lesson>> =
        combine(dao.forExercise(exercise.value), library) { rows, library -> rows.map { it.toLesson(library) } }

    override suspend fun delete(id: LessonId): DeletedLesson? = dao.deleteLesson(id.value)?.let(::RoomDeletedLesson)

    override suspend fun restore(deleted: DeletedLesson): Boolean = deleted is RoomDeletedLesson && dao.restoreLesson(deleted.rows)

    override suspend fun syncBuiltIn(drafts: List<LessonDraft>) {
        val created = clock.now().toEpochMilliseconds()
        dao.writeContent(drafts.map { it.toSync(created) }, imported = false)
    }
}

// A lesson file as rows; its exercises and its drill's are content ids for the DAO to resolve.
internal fun LessonDraft.toSync(createdAtMillis: Long): LessonSync =
    LessonSync(
        lesson = LessonEntity(contentId = id, title = title, body = body, practicePlanId = null, createdAtMillis = createdAtMillis),
        exercises = exercises.map { it.first },
        sources = sources.map { LessonSourceEntity(lessonId = 0, position = 0, title = it.title, url = it.url) },
        practice =
            practice?.let {
                GuidedPlanEntity(
                    contentId = null,
                    kind = GuidedKind.Practice.column,
                    title = title,
                    areas = "",
                    minutes = 0,
                    transitionSeconds = it.transition.inWholeSeconds.toInt(),
                    notes = "",
                    pinnedAtMillis = null,
                    edited = false,
                    createdAtMillis = createdAtMillis,
                )
            },
        steps = practice?.steps.orEmpty().map { it.toEntity() },
    )

// An exercise that went (it can't: exercises are only archived) is left out.
private fun LessonRows.toLesson(library: Map<ExerciseId, Exercise>): Lesson =
    Lesson(
        id = LessonId(lesson.id),
        contentId = lesson.contentId,
        title = lesson.title,
        body = lesson.body,
        exercises =
            exercises.sortedBy { it.position }.mapNotNull { row ->
                library[ExerciseId(row.exerciseId)]?.let { LessonExercise(it.id, it.fields.name) }
            },
        practice = lesson.practicePlanId?.let(::GuidedPlanId),
        sources = sources.sortedBy { it.position }.map { Source(it.title, it.url) },
    )
