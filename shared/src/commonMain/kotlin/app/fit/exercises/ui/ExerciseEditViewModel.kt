// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.exercises.DefaultField
import app.fit.exercises.EXERCISE_CATEGORIES
import app.fit.exercises.Equipment
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRef
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.ExerciseSaveResult
import app.fit.exercises.RefKind
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import app.fit.exercises.step
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class EditError { NameBlank, NameTaken, NothingTracked, BadVideoUrl, BadRefUrl, NotFound }

enum class Metric { Weight, Reps, Time, Distance }

@Immutable
data class NewRef(
    val kind: RefKind = RefKind.Link,
    val title: String = "",
    val url: String = "",
)

@Immutable
data class ExerciseEditUiState(
    // Null while loading.
    val fields: ExerciseFields? = null,
    val categories: List<String> = EXERCISE_CATEGORIES,
    val newRef: NewRef = NewRef(),
    val error: EditError? = null,
    val saving: Boolean = false,
    // Set once saved; the screen leaves and acknowledges it.
    val saved: Boolean = false,
)

sealed interface ExerciseEditAction {
    data class SetName(
        val name: String,
    ) : ExerciseEditAction

    data class SetCategory(
        val category: String,
    ) : ExerciseEditAction

    data class ToggleMetric(
        val metric: Metric,
    ) : ExerciseEditAction

    data class SetEachSide(
        val each: Boolean,
    ) : ExerciseEditAction

    data class SetWeightUnit(
        val unit: WeightUnit,
    ) : ExerciseEditAction

    data class SetDistanceUnit(
        val unit: DistanceUnit,
    ) : ExerciseEditAction

    // Null: not said.
    data class SetEquipment(
        val equipment: Equipment?,
    ) : ExerciseEditAction

    data class Step(
        val field: DefaultField,
        val up: Boolean,
    ) : ExerciseEditAction

    data class SetNotes(
        val notes: String,
    ) : ExerciseEditAction

    data class SetVideoUrl(
        val url: String,
    ) : ExerciseEditAction

    data class RemoveRef(
        val index: Int,
    ) : ExerciseEditAction

    data class EditNewRef(
        val ref: NewRef,
    ) : ExerciseEditAction

    data object AddRef : ExerciseEditAction

    data object Save : ExerciseEditAction

    data object SavedSeen : ExerciseEditAction
}

// Edits a copy of the exercise; nothing is written until Save, and leaving discards the copy.
class ExerciseEditViewModel(
    id: Long,
    private val repository: ExerciseRepository,
) : ViewModel() {
    private val id = ExerciseId(id)
    private val state = MutableStateFlow(ExerciseEditUiState())
    val uiState: StateFlow<ExerciseEditUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            val exercise = repository.exercise(this@ExerciseEditViewModel.id).first()
            if (exercise == null) {
                state.update { it.copy(error = EditError.NotFound) }
            } else {
                val category = exercise.fields.category
                val categories =
                    if (category.isBlank() ||
                        category in EXERCISE_CATEGORIES
                    ) {
                        EXERCISE_CATEGORIES
                    } else {
                        EXERCISE_CATEGORIES + category
                    }
                state.update { it.copy(fields = exercise.fields, categories = categories) }
            }
        }
    }

    fun onAction(action: ExerciseEditAction) {
        when (action) {
            is ExerciseEditAction.SetName -> {
                edit(clears = setOf(EditError.NameBlank, EditError.NameTaken)) { copy(name = action.name) }
            }

            is ExerciseEditAction.SetCategory -> {
                edit { copy(category = action.category) }
            }

            is ExerciseEditAction.ToggleMetric -> {
                edit(clears = setOf(EditError.NothingTracked)) { copy(tracks = tracks.toggled(action.metric)) }
            }

            is ExerciseEditAction.SetEachSide -> {
                edit { copy(sides = if (action.each) Sides.Each else Sides.Both) }
            }

            is ExerciseEditAction.SetWeightUnit -> {
                edit { copy(weightUnit = action.unit) }
            }

            is ExerciseEditAction.SetDistanceUnit -> {
                edit { copy(distanceUnit = action.unit) }
            }

            is ExerciseEditAction.SetEquipment -> {
                edit { copy(equipment = action.equipment) }
            }

            is ExerciseEditAction.Step -> {
                edit { copy(defaults = defaults.step(action.field, action.up, weightUnit)) }
            }

            is ExerciseEditAction.SetNotes -> {
                edit { copy(notes = action.notes) }
            }

            is ExerciseEditAction.SetVideoUrl -> {
                edit(clears = setOf(EditError.BadVideoUrl)) { copy(videoUrl = action.url) }
            }

            is ExerciseEditAction.RemoveRef -> {
                edit { copy(refs = refs.filterIndexed { i, _ -> i != action.index }) }
            }

            is ExerciseEditAction.EditNewRef -> {
                state.update {
                    it.copy(
                        newRef = action.ref,
                        error =
                            it.error.takeUnless { e ->
                                e ==
                                    EditError.BadRefUrl
                            },
                    )
                }
            }

            ExerciseEditAction.AddRef -> {
                addRef()
            }

            ExerciseEditAction.Save -> {
                save()
            }

            ExerciseEditAction.SavedSeen -> {
                state.update { it.copy(saved = false) }
            }
        }
    }

    private fun edit(
        clears: Set<EditError> = emptySet(),
        change: ExerciseFields.() -> ExerciseFields,
    ) {
        state.update { current ->
            val fields = current.fields ?: return@update current
            current.copy(fields = fields.change(), error = current.error.takeUnless { it in clears })
        }
    }

    private fun addRef() {
        state.update { current ->
            val fields = current.fields ?: return@update current
            val ref = current.newRef
            val url = ref.url.trim()
            if (!url.isWebUrl()) {
                current.copy(error = EditError.BadRefUrl)
            } else {
                current.copy(fields = fields.copy(refs = fields.refs + ExerciseRef(ref.kind, ref.title.trim(), url)), newRef = NewRef())
            }
        }
    }

    private fun save() {
        val current = state.value
        val fields = current.fields ?: return
        if (current.saving) return
        val video = fields.videoUrl.trim()
        if (video.isNotEmpty() && !video.isWebUrl()) {
            state.update { it.copy(error = EditError.BadVideoUrl) }
            return
        }
        state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = repository.update(id, fields.copy(videoUrl = video))
            state.update {
                when (result) {
                    ExerciseSaveResult.Saved -> it.copy(saving = false, saved = true, error = null)
                    ExerciseSaveResult.NameBlank -> it.copy(saving = false, error = EditError.NameBlank)
                    ExerciseSaveResult.NameTaken -> it.copy(saving = false, error = EditError.NameTaken)
                    ExerciseSaveResult.NothingTracked -> it.copy(saving = false, error = EditError.NothingTracked)
                    ExerciseSaveResult.NotFound -> it.copy(saving = false, error = EditError.NotFound)
                }
            }
        }
    }
}

private fun String.isWebUrl(): Boolean = startsWith("https://") || startsWith("http://")

private fun Tracks.toggled(metric: Metric): Tracks =
    when (metric) {
        Metric.Weight -> copy(weight = !weight)
        Metric.Reps -> copy(reps = !reps)
        Metric.Time -> copy(time = !time)
        Metric.Distance -> copy(distance = !distance)
    }
