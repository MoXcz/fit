// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import app.fit.content.ExerciseDraft
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

class FakeExerciseRepository(
    initial: List<Exercise> = emptyList(),
) : ExerciseRepository {
    val all = MutableStateFlow(initial)
    val synced = mutableListOf<List<ExerciseDraft>>()

    override val exercises: Flow<List<Exercise>> = all.map { list -> list.sortedBy { it.fields.name.lowercase() } }

    override fun exercise(id: ExerciseId): Flow<Exercise?> = all.map { list -> list.firstOrNull { it.id == id } }

    override suspend fun update(
        id: ExerciseId,
        fields: ExerciseFields,
    ): ExerciseSaveResult {
        val name = fields.name.trim()
        return when {
            name.isEmpty() -> {
                ExerciseSaveResult.NameBlank
            }

            !fields.tracks.any -> {
                ExerciseSaveResult.NothingTracked
            }

            all.value.none { it.id == id } -> {
                ExerciseSaveResult.NotFound
            }

            all.value.any { it.id != id && it.fields.name.equals(name, ignoreCase = true) } -> {
                ExerciseSaveResult.NameTaken
            }

            else -> {
                all.update { list -> list.map { if (it.id == id) it.copy(fields = fields.copy(name = name), edited = true) else it } }
                ExerciseSaveResult.Saved
            }
        }
    }

    override suspend fun setArchived(
        id: ExerciseId,
        archived: Boolean,
    ) {
        all.update { list -> list.map { if (it.id == id) it.copy(archived = archived) else it } }
    }

    override suspend fun syncBuiltIn(drafts: List<ExerciseDraft>) {
        synced += drafts
    }
}

fun exercise(
    id: Long,
    name: String,
    category: String = "",
    tracks: Tracks = Tracks.WeightAndReps,
    archived: Boolean = false,
) = Exercise(
    ExerciseId(id),
    contentId = null,
    fields = ExerciseFields(name = name, category = category, tracks = tracks),
    archived = archived,
)
