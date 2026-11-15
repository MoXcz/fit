// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines

import app.fit.content.RoutineDraft
import app.fit.core.moved
import app.fit.exercises.ExerciseFields
import app.fit.exercises.ExerciseId
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.exercise
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

// In memory, with the same rules as the Room one for what the screens use.
class FakeRoutineRepository(
    private val exercises: FakeExerciseRepository,
    initial: List<Routine> = emptyList(),
) : RoutineRepository {
    val all = MutableStateFlow(initial)
    val synced = mutableListOf<List<RoutineDraft>>()
    private var nextId = 1000L

    override val routines: Flow<List<Routine>> = all.map { list -> list.sortedBy { it.name.lowercase() } }

    override fun routine(id: RoutineId): Flow<Routine?> = all.map { list -> list.firstOrNull { it.id == id } }

    private fun taken(
        name: String,
        except: RoutineId? = null,
    ) = all.value.any { it.id != except && it.name.equals(name, ignoreCase = true) }

    private fun change(
        id: RoutineId,
        block: (Routine) -> Routine,
    ) = all.update { list -> list.map { if (it.id == id) block(it).copy(edited = true) else it } }

    override suspend fun create(name: String): RoutineNameResult {
        val clean = name.trim()
        return when {
            clean.isEmpty() -> {
                RoutineNameResult.NameBlank
            }

            taken(clean) -> {
                RoutineNameResult.NameTaken
            }

            else -> {
                val id = RoutineId(nextId++)
                all.update { it + Routine(id, null, clean, "", emptyList()) }
                RoutineNameResult.Saved(id)
            }
        }
    }

    override suspend fun createWithItems(
        name: String,
        items: List<Pair<ExerciseId, ItemTarget>>,
    ): RoutineNameResult {
        val result = create(name)
        if (result !is RoutineNameResult.Saved) return result
        val made =
            items.map { (exerciseId, target) ->
                RoutineItem(RoutineItemId(nextId++), exercises.exercise(exerciseId).first()!!, target)
            }
        all.update { list ->
            list.map {
                if (it.id ==
                    result.id
                ) {
                    it.copy(items = inSectionOrder(made) { item -> item.target.section })
                } else {
                    it
                }
            }
        }
        return result
    }

    override suspend fun rename(
        id: RoutineId,
        name: String,
    ): RoutineNameResult {
        val clean = name.trim()
        return when {
            clean.isEmpty() -> {
                RoutineNameResult.NameBlank
            }

            taken(clean, except = id) -> {
                RoutineNameResult.NameTaken
            }

            else -> {
                change(id) { it.copy(name = clean) }
                RoutineNameResult.Saved(id)
            }
        }
    }

    override suspend fun setNotes(
        id: RoutineId,
        notes: String,
    ) = change(id) { it.copy(notes = notes) }

    override suspend fun copy(id: RoutineId): RoutineId? {
        val source = all.value.firstOrNull { it.id == id } ?: return null
        val name =
            generateSequence(1) { it + 1 }
                .map {
                    if (it ==
                        1
                    ) {
                        "${source.name} copy"
                    } else {
                        "${source.name} copy $it"
                    }
                }.first { !taken(it) }
        val copyId = RoutineId(nextId++)
        all.update {
            it +
                source.copy(
                    id = copyId,
                    contentId = null,
                    name = name,
                    edited = false,
                    items =
                        source.items.map { item ->
                            item.copy(id = RoutineItemId(nextId++))
                        },
                )
        }
        return copyId
    }

    override suspend fun delete(id: RoutineId): Routine? {
        val routine = all.value.firstOrNull { it.id == id } ?: return null
        all.update { list -> list.filter { it.id != id } }
        return routine
    }

    override suspend fun restore(routine: Routine): Boolean {
        if (all.value.any { it.id == routine.id }) return false
        val name =
            generateSequence(0) { it + 1 }
                .map {
                    if (it ==
                        0
                    ) {
                        routine.name
                    } else if (it == 1) {
                        "${routine.name} copy"
                    } else {
                        "${routine.name} copy $it"
                    }
                }.first { !taken(it) }
        all.update { it + routine.copy(name = name) }
        return true
    }

    private fun insertAtSectionEnd(
        items: List<RoutineItem>,
        item: RoutineItem,
    ) = inSectionOrder(items + item) { it.target.section }

    override suspend fun addExercise(
        routineId: RoutineId,
        exerciseId: ExerciseId,
        section: String,
    ): RoutineItemId? {
        val exercise = exercises.exercise(exerciseId).first() ?: return null
        val item = RoutineItem(RoutineItemId(nextId++), exercise, exercise.defaultTarget(section))
        change(routineId) { it.copy(items = insertAtSectionEnd(it.items, item)) }
        return item.id
    }

    override suspend fun createExerciseAndAdd(
        routineId: RoutineId,
        exercise: NewExercise,
    ): CreateAndAddResult {
        val name = exercise.name.trim()
        if (name.isEmpty()) return CreateAndAddResult.NameBlank
        if (exercises.all.value.any { it.fields.name.equals(name, ignoreCase = true) }) return CreateAndAddResult.NameTaken
        val created = exercise(nextId++, name, exercise.category, exercise.tracks)
        exercises.all.update {
            it +
                created.copy(fields = created.fields.copy(defaults = ExerciseFields(name).defaults.copy(sets = exercise.sets)))
        }
        val item =
            RoutineItem(
                RoutineItemId(nextId++),
                created,
                ItemTarget(exercise.section, exercise.sets, exercise.reps, rest = exercise.rest, duration = exercise.duration),
            )
        change(routineId) { it.copy(items = insertAtSectionEnd(it.items, item)) }
        return CreateAndAddResult.Added(item.id.value, created.id)
    }

    override suspend fun saveItem(
        id: RoutineItemId,
        target: ItemTarget,
    ) {
        val owner = all.value.firstOrNull { r -> r.items.any { it.id == id } } ?: return
        change(owner.id) { r ->
            r.copy(
                items =
                    inSectionOrder(
                        r.items.map {
                            if (it.id ==
                                id
                            ) {
                                it.copy(target = target)
                            } else {
                                it
                            }
                        },
                    ) { it.target.section },
            )
        }
    }

    override suspend fun move(
        id: RoutineItemId,
        by: Int,
    ) {
        val owner = all.value.firstOrNull { r -> r.items.any { it.id == id } } ?: return
        val item = owner.items.first { it.id == id }
        val section = owner.items.filter { it.target.section == item.target.section }
        val moved = section.moved(section.indexOf(item), by).iterator()
        change(owner.id) { it.copy(items = owner.items.map { i -> if (i.target.section == item.target.section) moved.next() else i }) }
    }

    override suspend fun removeItems(
        routineId: RoutineId,
        ids: List<RoutineItemId>,
    ): RemovedItems {
        val routine = all.value.first { it.id == routineId }
        val removed = routine.items.withIndex().filter { it.value.id in ids }
        change(routineId) { r -> r.copy(items = r.items.filter { it.id !in ids }) }
        return RemovedItems(routineId, removed)
    }

    override suspend fun restoreItems(removed: RemovedItems) {
        change(removed.routineId) { r ->
            val items = r.items.toMutableList()
            removed.items.sortedBy { it.index }.forEach { (index, item) -> items.add(index.coerceAtMost(items.size), item) }
            r.copy(items = items)
        }
    }

    override suspend fun copyItemsTo(
        ids: List<RoutineItemId>,
        routineId: RoutineId,
    ): List<RoutineItemId> {
        val copies =
            all.value
                .flatMap { it.items }
                .filter { it.id in ids }
                .map { it.copy(id = RoutineItemId(nextId++)) }
        change(routineId) { r -> r.copy(items = copies.fold(r.items, ::insertAtSectionEnd)) }
        return copies.map { it.id }
    }

    override suspend fun syncBuiltIn(drafts: List<RoutineDraft>) {
        synced += drafts
    }
}
