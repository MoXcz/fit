// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.lessons

import app.fit.content.LessonDraft
import app.fit.exercises.ExerciseId
import app.fit.guided.DeletedPlan
import app.fit.guided.FakeGuidedRepository
import app.fit.guided.GuidedPlanId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

// [plans] holds the drills: given, a deleted lesson's drill goes with it, as in the database.
class FakeLessonRepository(
    initial: List<Lesson> = emptyList(),
    private val plans: FakeGuidedRepository? = null,
) : LessonRepository {
    val all = MutableStateFlow(initial)
    val synced = mutableListOf<List<LessonDraft>>()

    override val lessons: Flow<List<Lesson>> = all.map { list -> list.sortedBy { it.title.lowercase() } }

    override fun lesson(id: LessonId): Flow<Lesson?> = all.map { list -> list.firstOrNull { it.id == id } }

    override fun lessonsFor(exercise: ExerciseId): Flow<List<Lesson>> =
        lessons.map { list -> list.filter { lesson -> lesson.exercises.any { it.id == exercise } } }

    private class Deleted(
        val lesson: Lesson,
        val drill: DeletedPlan?,
    ) : DeletedLesson {
        override val title: String get() = lesson.title
        override val practice: GuidedPlanId? get() = lesson.practice
    }

    override suspend fun delete(id: LessonId): DeletedLesson? {
        val lesson = all.value.firstOrNull { it.id == id } ?: return null
        all.value -= lesson
        return Deleted(lesson, lesson.practice?.let { plans?.delete(it) })
    }

    override suspend fun restore(deleted: DeletedLesson): Boolean {
        if (deleted !is Deleted) return false
        val lesson = deleted.lesson
        if (all.value.any { it.id == lesson.id }) return false
        deleted.drill?.let { plans?.restore(it) }
        all.value += lesson
        return true
    }

    override suspend fun syncBuiltIn(drafts: List<LessonDraft>) {
        synced += drafts
    }
}
