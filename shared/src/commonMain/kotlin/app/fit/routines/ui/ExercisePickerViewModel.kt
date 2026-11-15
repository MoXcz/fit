// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines.ui

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.fit.core.WeightUnit
import app.fit.day.DayRepository
import app.fit.exercises.DefaultField
import app.fit.exercises.EXERCISE_CATEGORIES
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseDefaults
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.ExerciseType
import app.fit.exercises.step
import app.fit.routines.CreateAndAddResult
import app.fit.routines.MAIN_SECTION
import app.fit.routines.NewExercise
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

// The presets the inline form offers.
val PICKER_TYPES = ExerciseType.entries.filter { it != ExerciseType.Custom }

@Immutable
data class NewExerciseForm(
    val name: String = "",
    val type: ExerciseType = ExerciseType.WeightAndReps,
    val defaults: ExerciseDefaults = ExerciseDefaults(),
    val category: String = "",
    val section: String = MAIN_SECTION,
    val more: Boolean = false,
)

// Koin qualifiers: the picker for a routine (id parameter) and for a day (ISO date parameter).
const val ROUTINE_PICKER = "routine-picker"
const val DAY_PICKER = "day-picker"

/** Where the picker adds: a routine or a day. */
interface PickerTarget {
    /** Adds an existing exercise; returns the new row's id, or null if the target is gone. */
    suspend fun add(exerciseId: ExerciseId): Long?

    /** Creates the exercise and adds it in one transaction, or writes nothing. */
    suspend fun createAndAdd(exercise: NewExercise): CreateAndAddResult
}

class RoutinePickerTarget(
    private val id: RoutineId,
    private val routines: RoutineRepository,
) : PickerTarget {
    override suspend fun add(exerciseId: ExerciseId): Long? = routines.addExercise(id, exerciseId)?.value

    override suspend fun createAndAdd(exercise: NewExercise): CreateAndAddResult = routines.createExerciseAndAdd(id, exercise)
}

class DayPickerTarget(
    private val date: LocalDate,
    private val days: DayRepository,
) : PickerTarget {
    override suspend fun add(exerciseId: ExerciseId): Long? = days.addExercise(date, exerciseId)?.value

    override suspend fun createAndAdd(exercise: NewExercise): CreateAndAddResult = days.createExerciseAndAdd(date, exercise)
}

// [id] is the new routine item's or day block's.
@Immutable
data class PickerAdded(
    val id: Long,
    val name: String,
)

@Immutable
data class ExercisePickerUiState(
    val query: String = "",
    val category: String? = null,
    val categories: List<String> = emptyList(),
    val exercises: List<Exercise> = emptyList(),
    // Offer "Create '<query>'" when nothing has exactly that name.
    val canCreateQuery: Boolean = false,
    // Non-null while the inline form is open; closing the picker discards it.
    val form: NewExerciseForm? = null,
    val formError: NameError? = null,
    val busy: Boolean = false,
    // Set once something was added; the editor takes it over and the picker closes.
    val added: PickerAdded? = null,
)

sealed interface ExercisePickerAction {
    data object Reset : ExercisePickerAction

    data class Search(
        val query: String,
    ) : ExercisePickerAction

    data class Category(
        val category: String?,
    ) : ExercisePickerAction

    data class Add(
        val exercise: Exercise,
    ) : ExercisePickerAction

    data object StartCreate : ExercisePickerAction

    data class SetFormName(
        val name: String,
    ) : ExercisePickerAction

    data class SetFormType(
        val type: ExerciseType,
    ) : ExercisePickerAction

    data class StepForm(
        val field: DefaultField,
        val up: Boolean,
    ) : ExercisePickerAction

    data class SetFormCategory(
        val category: String,
    ) : ExercisePickerAction

    data class SetFormSection(
        val section: String,
    ) : ExercisePickerAction

    data object ToggleFormMore : ExercisePickerAction

    data object CancelCreate : ExercisePickerAction

    data object CreateAndAdd : ExercisePickerAction

    data object AddedSeen : ExercisePickerAction
}

private data class PickerLocal(
    val query: String = "",
    val category: String? = null,
    val form: NewExerciseForm? = null,
    val formError: NameError? = null,
    val busy: Boolean = false,
    val added: PickerAdded? = null,
)

class ExercisePickerViewModel(
    private val target: PickerTarget,
    exercises: ExerciseRepository,
) : ViewModel() {
    private val local = MutableStateFlow(PickerLocal())

    val uiState: StateFlow<ExercisePickerUiState> =
        combine(exercises.exercises, local) { all, local ->
            val active = all.filter { !it.archived }
            val needle = local.query.trim()
            val categories = active.map { it.fields.category }.filter { it.isNotBlank() }.distinct()
            ExercisePickerUiState(
                query = local.query,
                category = local.category,
                categories = EXERCISE_CATEGORIES.filter { it in categories } + categories.filter { it !in EXERCISE_CATEGORIES }.sorted(),
                exercises =
                    active
                        .filter { local.category == null || it.fields.category == local.category }
                        .filter { needle.isEmpty() || it.fields.name.contains(needle, ignoreCase = true) },
                canCreateQuery = needle.isNotEmpty() && all.none { it.fields.name.equals(needle, ignoreCase = true) },
                form = local.form,
                formError = local.formError,
                busy = local.busy,
                added = local.added,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExercisePickerUiState())

    fun onAction(action: ExercisePickerAction) {
        when (action) {
            ExercisePickerAction.Reset -> {
                local.value = PickerLocal()
            }

            is ExercisePickerAction.Search -> {
                local.update { it.copy(query = action.query) }
            }

            is ExercisePickerAction.Category -> {
                local.update { it.copy(category = action.category.takeIf { c -> c != it.category }) }
            }

            is ExercisePickerAction.Add -> {
                add(action.exercise)
            }

            ExercisePickerAction.StartCreate -> {
                local.update {
                    it.copy(form = NewExerciseForm(name = it.query.trim(), category = it.category.orEmpty()))
                }
            }

            is ExercisePickerAction.SetFormName -> {
                editForm(clearError = true) { it.copy(name = action.name) }
            }

            is ExercisePickerAction.SetFormType -> {
                editForm { it.copy(type = action.type) }
            }

            is ExercisePickerAction.StepForm -> {
                editForm { it.copy(defaults = it.defaults.step(action.field, action.up, WeightUnit.Kg)) }
            }

            is ExercisePickerAction.SetFormCategory -> {
                editForm { it.copy(category = action.category) }
            }

            is ExercisePickerAction.SetFormSection -> {
                editForm { it.copy(section = action.section) }
            }

            ExercisePickerAction.ToggleFormMore -> {
                editForm { it.copy(more = !it.more) }
            }

            ExercisePickerAction.CancelCreate -> {
                local.update { it.copy(form = null, formError = null) }
            }

            ExercisePickerAction.CreateAndAdd -> {
                createAndAdd()
            }

            ExercisePickerAction.AddedSeen -> {
                local.value = PickerLocal()
            }
        }
    }

    private fun editForm(
        clearError: Boolean = false,
        change: (NewExerciseForm) -> NewExerciseForm,
    ) {
        local.update { state ->
            state.form?.let { state.copy(form = change(it), formError = if (clearError) null else state.formError) }
                ?: state
        }
    }

    // One add per tap: a second tap while the first is being written does nothing.
    private fun add(exercise: Exercise) {
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val id = target.add(exercise.id)
            local.update { it.copy(busy = false, added = id?.let { PickerAdded(it, exercise.fields.name) }) }
        }
    }

    private fun createAndAdd() {
        val form = local.value.form ?: return
        if (local.value.busy) return
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            val tracks = requireNotNull(form.type.tracks)
            val d = form.defaults
            val result =
                target.createAndAdd(
                    NewExercise(form.name, tracks, d.sets, d.reps, d.duration, d.rest, form.category, form.section),
                )
            local.update {
                when (result) {
                    is CreateAndAddResult.Added -> it.copy(busy = false, form = null, added = PickerAdded(result.id, form.name.trim()))
                    CreateAndAddResult.NameBlank -> it.copy(busy = false, formError = NameError.Blank)
                    CreateAndAddResult.NameTaken -> it.copy(busy = false, formError = NameError.Taken)
                    CreateAndAddResult.RoutineGone -> it.copy(busy = false, form = null)
                }
            }
        }
    }
}
