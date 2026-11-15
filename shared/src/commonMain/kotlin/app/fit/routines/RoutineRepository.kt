// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.routines

import app.fit.content.RoutineDraft
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.data.db.CreateOutcome
import app.fit.data.db.ExerciseDao
import app.fit.data.db.ExerciseEntity
import app.fit.data.db.RoutineDao
import app.fit.data.db.RoutineEntity
import app.fit.data.db.RoutineItemEntity
import app.fit.data.db.RoutineWithItems
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.Sides
import app.fit.exercises.Tracks
import app.fit.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

sealed interface RoutineNameResult {
    data class Saved(
        val id: RoutineId,
    ) : RoutineNameResult

    data object NameBlank : RoutineNameResult

    data object NameTaken : RoutineNameResult
}

sealed interface CreateAndAddResult {
    // [id] is the new routine item's or day block's.
    data class Added(
        val id: Long,
        val exercise: ExerciseId,
    ) : CreateAndAddResult

    data object NameBlank : CreateAndAddResult

    data object NameTaken : CreateAndAddResult

    data object RoutineGone : CreateAndAddResult
}

// The picker's inline "new exercise": its defaults become the new routine item's target.
data class NewExercise(
    val name: String,
    val tracks: Tracks,
    val sets: Int,
    val reps: Int,
    val duration: Duration,
    val rest: Duration,
    val category: String = "",
    val section: String = MAIN_SECTION,
)

// An exercise made in the picker: what it records and its defaults; units from the settings.
internal fun NewExercise.toEntity(
    unit: WeightUnit,
    createdAtMillis: Long,
) = ExerciseEntity(
    contentId = null,
    name = name.trim(),
    category = category.trim(),
    tracksWeight = tracks.weight,
    tracksReps = tracks.reps,
    tracksTime = tracks.time,
    tracksDistance = tracks.distance,
    sides = Sides.Both.name,
    weightUnit = unit.name,
    distanceUnit = DistanceUnit.Km.name,
    defaultSets = sets,
    defaultReps = reps,
    defaultRepsMax = null,
    defaultRestSeconds = rest.inWholeSeconds.toInt(),
    defaultRestMaxSeconds = null,
    defaultDurationSeconds = duration.inWholeSeconds.toInt(),
    defaultWeight = 0.0,
    notes = "",
    videoUrl = "",
    archived = false,
    edited = false,
    createdAtMillis = createdAtMillis,
)

// Items taken out of one routine, with the places they had, for Undo.
data class RemovedItems(
    val routineId: RoutineId,
    val items: List<IndexedValue<RoutineItem>>,
)

/** Routines and their items. Every change to a built-in routine marks it edited. */
interface RoutineRepository {
    /** Every routine by name, items in display order. */
    val routines: Flow<List<Routine>>

    fun routine(id: RoutineId): Flow<Routine?>

    suspend fun create(name: String): RoutineNameResult

    /** A new routine holding [items] (exercise and target), written in one transaction or not at all. */
    suspend fun createWithItems(
        name: String,
        items: List<Pair<ExerciseId, ItemTarget>>,
    ): RoutineNameResult

    suspend fun rename(
        id: RoutineId,
        name: String,
    ): RoutineNameResult

    suspend fun setNotes(
        id: RoutineId,
        notes: String,
    )

    /** A copy named "<name> copy" (or "copy 2", …); null if the routine is gone. */
    suspend fun copy(id: RoutineId): RoutineId?

    /** Deletes the routine and returns it, for [restore]. */
    suspend fun delete(id: RoutineId): Routine?

    /** Puts a deleted routine back, renamed "… copy" if its name was taken meanwhile; false if it's already there. */
    suspend fun restore(routine: Routine): Boolean

    /** Adds the exercise at the end of its section with the exercise's defaults. */
    suspend fun addExercise(
        routineId: RoutineId,
        exerciseId: ExerciseId,
        section: String = MAIN_SECTION,
    ): RoutineItemId?

    /** Creates the exercise and adds it in one transaction, or writes nothing. */
    suspend fun createExerciseAndAdd(
        routineId: RoutineId,
        exercise: NewExercise,
    ): CreateAndAddResult

    suspend fun saveItem(
        id: RoutineItemId,
        target: ItemTarget,
    )

    /** Moves the item [by] places within its section (negative is up), in one write. */
    suspend fun move(
        id: RoutineItemId,
        by: Int,
    )

    suspend fun removeItems(
        routineId: RoutineId,
        ids: List<RoutineItemId>,
    ): RemovedItems

    suspend fun restoreItems(removed: RemovedItems)

    /** Appends copies to another routine; returns the new items' ids. */
    suspend fun copyItemsTo(
        ids: List<RoutineItemId>,
        routineId: RoutineId,
    ): List<RoutineItemId>

    /** Adds and updates built-in routines from content; items whose exercise is missing are left out. */
    suspend fun syncBuiltIn(drafts: List<RoutineDraft>)
}

class RoomRoutineRepository(
    private val dao: RoutineDao,
    private val exerciseDao: ExerciseDao,
    private val exercises: ExerciseRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
) : RoutineRepository {
    override val routines: Flow<List<Routine>> =
        combine(dao.all(), exercises.exercises) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.map { it.toRoutine(byId) }
        }

    override fun routine(id: RoutineId): Flow<Routine?> =
        combine(dao.byId(id.value), exercises.exercises) { row, all ->
            row?.toRoutine(all.associateBy { it.id })
        }

    override suspend fun create(name: String): RoutineNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return RoutineNameResult.NameBlank
        val id = dao.create(RoutineEntity(contentId = null, name = clean, notes = "", edited = false, createdAtMillis = now()))
        return if (id == null) RoutineNameResult.NameTaken else RoutineNameResult.Saved(RoutineId(id))
    }

    override suspend fun createWithItems(
        name: String,
        items: List<Pair<ExerciseId, ItemTarget>>,
    ): RoutineNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return RoutineNameResult.NameBlank
        val id =
            dao.createWithItems(
                RoutineEntity(contentId = null, name = clean, notes = "", edited = false, createdAtMillis = now()),
                items.map { (exerciseId, target) -> target.toEntity(id = 0, routineId = 0, exerciseId = exerciseId.value, position = 0) },
            )
        return if (id == null) RoutineNameResult.NameTaken else RoutineNameResult.Saved(RoutineId(id))
    }

    override suspend fun rename(
        id: RoutineId,
        name: String,
    ): RoutineNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return RoutineNameResult.NameBlank
        return if (dao.rename(id.value, clean)) RoutineNameResult.Saved(id) else RoutineNameResult.NameTaken
    }

    override suspend fun setNotes(
        id: RoutineId,
        notes: String,
    ) = dao.setNotes(id.value, notes)

    override suspend fun copy(id: RoutineId): RoutineId? = dao.copy(id.value, now())?.let(::RoutineId)

    override suspend fun delete(id: RoutineId): Routine? {
        val all = exercises.exercises.first().associateBy { it.id }
        return dao.deleteRoutine(id.value)?.toRoutine(all)
    }

    override suspend fun restore(routine: Routine): Boolean =
        dao.restore(
            RoutineWithItems(
                RoutineEntity(
                    id = routine.id.value,
                    contentId = routine.contentId,
                    name = routine.name,
                    notes = routine.notes,
                    edited = routine.edited,
                    createdAtMillis = now(),
                ),
                routine.items.mapIndexed { position, item -> item.toEntity(routine.id, position) },
            ),
        )

    override suspend fun addExercise(
        routineId: RoutineId,
        exerciseId: ExerciseId,
        section: String,
    ): RoutineItemId? {
        val exercise = exercises.exercise(exerciseId).first() ?: return null
        val target = exercise.defaultTarget(section)
        return dao
            .addItem(
                target.toEntity(id = 0, routineId = routineId.value, exerciseId = exerciseId.value, position = 0),
            )?.let(::RoutineItemId)
    }

    override suspend fun createExerciseAndAdd(
        routineId: RoutineId,
        exercise: NewExercise,
    ): CreateAndAddResult {
        val name = exercise.name.trim()
        if (name.isEmpty()) return CreateAndAddResult.NameBlank
        val entity = exercise.toEntity(settings.settings.first().weightUnit, now())
        val target =
            ItemTarget(
                section = exercise.section.trim().ifEmpty { MAIN_SECTION },
                sets = exercise.sets,
                reps = exercise.reps,
                rest = exercise.rest,
                duration = exercise.duration,
            )
        return when (
            val outcome =
                dao.createExerciseAndAdd(
                    entity,
                    target.toEntity(id = 0, routineId = routineId.value, exerciseId = 0, position = 0),
                )
        ) {
            is CreateOutcome.Created -> CreateAndAddResult.Added(outcome.itemId, ExerciseId(outcome.exerciseId))
            CreateOutcome.NameTaken -> CreateAndAddResult.NameTaken
            CreateOutcome.RoutineGone -> CreateAndAddResult.RoutineGone
        }
    }

    override suspend fun saveItem(
        id: RoutineItemId,
        target: ItemTarget,
    ) = dao.saveItem(target.copy(section = target.section.trim().ifEmpty { MAIN_SECTION }).toEntity(id.value, 0, 0, 0))

    override suspend fun move(
        id: RoutineItemId,
        by: Int,
    ) = dao.move(id.value, by)

    override suspend fun removeItems(
        routineId: RoutineId,
        ids: List<RoutineItemId>,
    ): RemovedItems {
        val current = routine(routineId).first()?.items.orEmpty()
        val wanted = ids.toSet()
        val removed = current.withIndex().filter { it.value.id in wanted }
        dao.removeItems(removed.map { it.value.id.value })
        return RemovedItems(routineId, removed)
    }

    override suspend fun restoreItems(removed: RemovedItems) =
        dao.restoreItems(removed.items.map { (position, item) -> item.toEntity(removed.routineId, position) })

    override suspend fun copyItemsTo(
        ids: List<RoutineItemId>,
        routineId: RoutineId,
    ): List<RoutineItemId> = dao.copyItemsTo(ids.map { it.value }, routineId.value).map(::RoutineItemId)

    override suspend fun syncBuiltIn(drafts: List<RoutineDraft>) {
        val created = now()
        dao.syncBuiltIn(drafts.map { it.toRows(exerciseDao, created) })
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()
}

// A routine file as rows: values an item leaves out come from its exercise, and an item whose exercise
// isn't in the database is left out.
internal suspend fun RoutineDraft.toRows(
    exerciseDao: ExerciseDao,
    createdAtMillis: Long,
): RoutineWithItems {
    val items =
        items.mapNotNull { item ->
            val exercise = exerciseDao.findByContentId(item.exercise) ?: return@mapNotNull null
            val d = exercise.defaults()
            ItemTarget(
                section = item.section ?: MAIN_SECTION,
                sets = item.sets ?: d.sets,
                reps = item.reps?.first ?: d.reps,
                repsMax = if (item.reps != null) item.reps.second else d.repsMax,
                rest = item.rest?.first ?: d.rest,
                restMax = if (item.rest != null) item.rest.second else d.restMax,
                duration = item.duration ?: d.duration,
                tracks = item.tracks,
                progressStep = item.progressStep,
                progressRpe = item.progressRpe,
                warmupSets = item.warmupSets ?: 0,
                notes = item.notes.orEmpty(),
            ).toEntity(id = 0, routineId = 0, exerciseId = exercise.id, position = 0)
        }
    return RoutineWithItems(
        RoutineEntity(contentId = id, name = name, notes = notes, edited = false, createdAtMillis = createdAtMillis),
        inSectionOrder(items) { it.section },
    )
}

private data class Defaults(
    val sets: Int,
    val reps: Int,
    val repsMax: Int?,
    val rest: Duration,
    val restMax: Duration?,
    val duration: Duration,
)

private fun ExerciseEntity.defaults() =
    Defaults(
        sets = defaultSets,
        reps = defaultReps,
        repsMax = defaultRepsMax,
        rest = defaultRestSeconds.seconds,
        restMax = defaultRestMaxSeconds?.seconds,
        duration = defaultDurationSeconds.seconds,
    )

private fun RoutineItem.toEntity(
    routineId: RoutineId,
    position: Int,
): RoutineItemEntity = target.toEntity(id.value, routineId.value, exercise.id.value, position)

private fun ItemTarget.toEntity(
    id: Long,
    routineId: Long,
    exerciseId: Long,
    position: Int,
) = RoutineItemEntity(
    id = id,
    routineId = routineId,
    exerciseId = exerciseId,
    position = position,
    section = section,
    sets = sets,
    reps = reps,
    repsMax = repsMax,
    restSeconds = rest.inWholeSeconds.toInt(),
    restMaxSeconds = restMax?.inWholeSeconds?.toInt(),
    durationSeconds = duration.inWholeSeconds.toInt(),
    tracksWeight = tracks?.weight,
    tracksReps = tracks?.reps,
    tracksTime = tracks?.time,
    tracksDistance = tracks?.distance,
    progressStep = progressStep,
    progressRpe = progressRpe,
    warmupSets = warmupSets,
    notes = notes,
)

// Items whose exercise is unknown (never expected: exercises are only archived) are dropped.
private fun RoutineWithItems.toRoutine(exercises: Map<ExerciseId, Exercise>): Routine =
    Routine(
        id = RoutineId(routine.id),
        contentId = routine.contentId,
        name = routine.name,
        notes = routine.notes,
        edited = routine.edited,
        items =
            items.sortedBy { it.position }.mapNotNull { item ->
                val exercise = exercises[ExerciseId(item.exerciseId)] ?: return@mapNotNull null
                RoutineItem(RoutineItemId(item.id), exercise, item.toTarget())
            },
    )

private fun RoutineItemEntity.toTarget(): ItemTarget =
    ItemTarget(
        section = section,
        sets = sets,
        reps = reps,
        repsMax = repsMax,
        rest = restSeconds.seconds,
        restMax = restMaxSeconds?.seconds,
        duration = durationSeconds.seconds,
        tracks =
            if (tracksWeight == null || tracksReps == null || tracksTime == null || tracksDistance == null) {
                null
            } else {
                Tracks(tracksWeight, tracksReps, tracksTime, tracksDistance)
            },
        progressStep = progressStep,
        progressRpe = progressRpe,
        warmupSets = warmupSets,
        notes = notes,
    )
