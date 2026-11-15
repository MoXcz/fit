// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.data.db.BlockEntity
import app.fit.data.db.BlockRows
import app.fit.data.db.BlockWithSets
import app.fit.data.db.CreateBlockOutcome
import app.fit.data.db.DayDao
import app.fit.data.db.DayWithBlocks
import app.fit.data.db.ExerciseEntity
import app.fit.data.db.PastSetRow
import app.fit.data.db.RemovedSetRow
import app.fit.data.db.SetEntryEntity
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.Tracks
import app.fit.exercises.step
import app.fit.programs.ProgramRepository
import app.fit.programs.ProgramSchedule
import app.fit.routines.CreateAndAddResult
import app.fit.routines.MAIN_SECTION
import app.fit.routines.NewExercise
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import app.fit.routines.toEntity
import app.fit.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// A block taken out of a day, for Undo.
data class RemovedBlock(
    val name: String,
    internal val rows: BlockWithSets,
)

// A day deleted, for Undo.
data class DeletedDay(
    val date: LocalDate,
    internal val rows: DayWithBlocks,
)

// A set taken out of a block, for Undo.
data class RemovedSet(
    internal val row: RemovedSetRow,
)

// What a set's inline fields can change; null clears a value.
sealed interface SetEdit {
    data class Reps(
        val reps: Int?,
    ) : SetEdit

    data class Weight(
        val weight: Double?,
    ) : SetEdit

    data class Duration(
        val duration: kotlin.time.Duration?,
    ) : SetEdit

    data class Distance(
        val distance: Double?,
    ) : SetEdit

    data class Unit(
        val unit: WeightUnit,
    ) : SetEdit

    // 5–10 in halves (see [Rpe]); null clears it, anything else is ignored.
    data class Rpe(
        val rpe: Double?,
    ) : SetEdit

    data class Bodyweight(
        val on: Boolean,
    ) : SetEdit
}

/** Workout days: one per local date, with blocks copied from routines, exercises or other days. */
interface DayRepository {
    /** The day on [date], or null while nothing was added to it. */
    fun day(date: LocalDate): Flow<WorkoutDay?>

    /** Every day from [from] to [to] that exists, by date. */
    fun days(
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<WorkoutDay>>

    /** The day a block belongs to, or null once the block or its day is gone. */
    fun dayOfBlock(id: BlockId): Flow<WorkoutDay?>

    /** Up to [limit] days before [date] that have sets, newest first. */
    suspend fun recentDays(
        before: LocalDate,
        limit: Int,
    ): List<WorkoutDay>

    /** Every day with a done working set, newest first. */
    fun trainedDays(): Flow<List<WorkoutDay>>

    /** The exercise's done working sets on every day. */
    fun exerciseSets(id: ExerciseId): Flow<List<PastSet>>

    /** Done working sets of these exercises on days before [date], for records. */
    suspend fun earlierSets(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): List<PastSet>

    /** Each exercise's last load lifted on a day before [date] (see [Progression.lastLoads]). */
    suspend fun lastLoads(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): Map<ExerciseId, LastLoad>

    /**
     * Copies a routine into the day (see [DayPlan.fromRoutine]); [replace] drops the day's blocks first.
     * In the active program's deload week the loads are cut, and the day is marked as a deload day.
     */
    suspend fun applyRoutine(
        date: LocalDate,
        routineId: RoutineId,
        replace: Boolean,
    ): Boolean

    /** Appends a copy of another day's blocks (see [DayPlan.copyOf]); a deload day's copy is one too. */
    suspend fun copyDay(
        from: LocalDate,
        to: LocalDate,
    )

    /** Adds an exercise at the end of its section (see [DayPlan.adHoc]); in a deload week its load is cut. */
    suspend fun addExercise(
        date: LocalDate,
        exerciseId: ExerciseId,
        section: String = MAIN_SECTION,
    ): BlockId?

    /** Creates the exercise and adds it in one transaction, or writes nothing. */
    suspend fun createExerciseAndAdd(
        date: LocalDate,
        exercise: NewExercise,
    ): CreateAndAddResult

    suspend fun setName(
        date: LocalDate,
        name: String,
    )

    suspend fun setNotes(
        date: LocalDate,
        notes: String,
    )

    /** Sets the day's fatigue (see [Fatigue]); null clears it, a value outside the range is ignored. */
    suspend fun setFatigue(
        date: LocalDate,
        fatigue: Int?,
    )

    /** Starts the clock unless it's running. */
    suspend fun start(date: LocalDate)

    /** Stops the clock now, or at the last set if that was long ago (see [SessionClock.finishAt]). */
    suspend fun finish(date: LocalDate)

    /** Clears the finish and moves the start on by the pause (see [SessionClock.reopenedStart]). */
    suspend fun reopen(date: LocalDate)

    suspend fun setTimes(
        date: LocalDate,
        startedAt: Instant?,
        finishedAt: Instant?,
    )

    /** Deletes the day and returns it, for [restoreDay]. */
    suspend fun deleteDay(date: LocalDate): DeletedDay?

    /** False when the date has exercises again, so nothing was restored. */
    suspend fun restoreDay(deleted: DeletedDay): Boolean

    /** Moves the block [by] places within its section (negative is up), in one write. */
    suspend fun moveBlock(
        id: BlockId,
        by: Int,
    )

    suspend fun removeBlock(id: BlockId): RemovedBlock?

    /** False when its day is gone, so nothing was restored. */
    suspend fun restoreBlock(removed: RemovedBlock): Boolean

    suspend fun editSet(
        id: SetId,
        edit: SetEdit,
    )

    /** Ticks or unticks a set; today's first tick starts its clock, another day's never does. */
    suspend fun setDone(
        id: SetId,
        done: Boolean,
    )

    /**
     * Logs a timed set as held for [held] and done [at]; a set already done is left alone, so a hold
     * is never logged twice. Like a tick, today's first one starts its clock, at [startedAt].
     */
    suspend fun logHold(
        id: SetId,
        held: Duration,
        startedAt: Instant,
        at: Instant,
    )

    /** Changes the load of the block's working sets still to do by one step; done sets keep theirs. */
    suspend fun stepWeight(
        blockId: BlockId,
        up: Boolean,
    )

    /** Adds a set like the last working set (see [DayPlan.nextSet]). */
    suspend fun addSet(blockId: BlockId)

    /** Adds warm-up sets before the first working load (see [DayPlan.warmupsFor]). */
    suspend fun addWarmups(blockId: BlockId)

    suspend fun removeSet(id: SetId): RemovedSet?

    suspend fun restoreSet(removed: RemovedSet)
}

class RoomDayRepository(
    private val dao: DayDao,
    private val exercises: ExerciseRepository,
    private val routines: RoutineRepository,
    private val programs: ProgramRepository,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val zone: TimeZone,
) : DayRepository {
    override fun day(date: LocalDate): Flow<WorkoutDay?> =
        combine(dao.byDay(date.toString()), exercises.exercises) { row, all -> row?.toDay(all.associateBy { it.id }) }

    override fun days(
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<WorkoutDay>> =
        combine(dao.between(from.toString(), to.toString()), exercises.exercises) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.map { it.toDay(byId) }
        }

    override fun dayOfBlock(id: BlockId): Flow<WorkoutDay?> =
        combine(dao.byBlock(id.value), exercises.exercises) { row, all -> row?.toDay(all.associateBy { it.id }) }

    override suspend fun recentDays(
        before: LocalDate,
        limit: Int,
    ): List<WorkoutDay> {
        val byId = exerciseMap()
        return dao.recentBefore(before.toString(), limit).map { it.toDay(byId) }
    }

    private suspend fun exerciseMap(): Map<ExerciseId, Exercise> = exercises.exercises.first().associateBy { it.id }

    override fun trainedDays(): Flow<List<WorkoutDay>> =
        combine(dao.trainedDays(), exercises.exercises) { rows, all ->
            val byId = all.associateBy { it.id }
            rows.map { it.toDay(byId) }
        }

    override fun exerciseSets(id: ExerciseId): Flow<List<PastSet>> =
        dao.doneWorkingSets(id.value).map { rows ->
            rows.mapNotNull {
                it.toPastSet()
            }
        }

    // Done working sets of these exercises on days before [date], for progression, targets and records.
    private suspend fun history(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): List<PastSet> = dao.doneSets(exerciseIds.map { it.value }.distinct(), date.toString()).mapNotNull { it.toPastSet() }

    override suspend fun earlierSets(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): List<PastSet> = if (exerciseIds.isEmpty()) emptyList() else history(exerciseIds, date)

    override suspend fun lastLoads(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): Map<ExerciseId, LastLoad> = if (exerciseIds.isEmpty()) emptyMap() else Progression.lastLoads(history(exerciseIds, date))

    override suspend fun applyRoutine(
        date: LocalDate,
        routineId: RoutineId,
        replace: Boolean,
    ): Boolean {
        val routine = routines.routine(routineId).first() ?: return false
        val history = history(routine.items.map { it.exercise.id }, date)
        // Any routine applied in a deload week is cut, the plan or one chosen by hand: the week is a deload.
        val deload = ProgramSchedule.deloadPercent(programs.active.first(), date)
        val blocks = DayPlan.fromRoutine(routine.items, history, deload)
        // Replacing makes it the routine's day; adding names only an unnamed day.
        dao.addBlocks(
            date.toString(),
            blocks.map { it.toRows() },
            replace = replace,
            name = routine.name,
            nameIfBlank = !replace,
            deload = deload != null,
        )
        return true
    }

    override suspend fun copyDay(
        from: LocalDate,
        to: LocalDate,
    ) {
        val source = dao.load(from.toString())?.toDay(exerciseMap()) ?: return
        dao.addBlocks(
            to.toString(),
            DayPlan.copyOf(source).map { it.toRows() },
            name = source.name.ifBlank { null },
            deload = source.deload,
        )
    }

    override suspend fun addExercise(
        date: LocalDate,
        exerciseId: ExerciseId,
        section: String,
    ): BlockId? {
        val exercise = exercises.exercise(exerciseId).first() ?: return null
        val deload = ProgramSchedule.deloadPercent(programs.active.first(), date)
        val block = DayPlan.adHoc(exercise, history(listOf(exerciseId), date), section, deload)
        return dao.addBlocks(date.toString(), listOf(block.toRows()), deload = deload != null).singleOrNull()?.let(::BlockId)
    }

    override suspend fun createExerciseAndAdd(
        date: LocalDate,
        exercise: NewExercise,
    ): CreateAndAddResult {
        val name = exercise.name.trim()
        if (name.isEmpty()) return CreateAndAddResult.NameBlank
        val unit = settings.settings.first().weightUnit
        val entity = exercise.toEntity(unit, now().toEpochMilliseconds())
        val set =
            PlannedSet(
                targetReps = exercise.reps,
                targetDuration = exercise.duration.takeIf { exercise.tracks.time },
                weight = 0.0.takeIf { exercise.tracks.weight },
                weightUnit = unit,
                distanceUnit = DistanceUnit.Km,
            )
        val block =
            PlannedBlock(
                exerciseId = ExerciseId(0),
                section = exercise.section.trim().ifEmpty { MAIN_SECTION },
                rest = exercise.rest,
                restMax = null,
                duration = exercise.duration,
                sets = List(exercise.sets.coerceAtLeast(1)) { set },
            )
        return when (val outcome = dao.createExerciseAndAdd(date.toString(), entity, block.toRows())) {
            is CreateBlockOutcome.Created -> CreateAndAddResult.Added(outcome.blockId, ExerciseId(outcome.exerciseId))
            CreateBlockOutcome.NameTaken -> CreateAndAddResult.NameTaken
        }
    }

    override suspend fun setName(
        date: LocalDate,
        name: String,
    ) = dao.editDay(date.toString()) { it.copy(name = name.trim()) }

    override suspend fun setNotes(
        date: LocalDate,
        notes: String,
    ) = dao.editDay(date.toString()) { it.copy(notes = notes) }

    override suspend fun setFatigue(
        date: LocalDate,
        fatigue: Int?,
    ) {
        if (!Fatigue.isValid(fatigue)) return
        dao.editDay(date.toString()) { it.copy(fatigue = fatigue) }
    }

    override suspend fun start(date: LocalDate) {
        val now = now().toEpochMilliseconds()
        dao.editDay(date.toString()) { if (it.startedAtMillis == null) it.copy(startedAtMillis = now, finishedAtMillis = null) else it }
    }

    override suspend fun finish(date: LocalDate) {
        val now = now()
        dao.finish(date.toString()) { last -> SessionClock.finishAt(last?.let(Instant::fromEpochMilliseconds), now).toEpochMilliseconds() }
    }

    override suspend fun reopen(date: LocalDate) {
        val now = now()
        dao.editDay(date.toString()) {
            val start =
                SessionClock.reopenedStart(
                    it.startedAtMillis?.let(Instant::fromEpochMilliseconds),
                    it.finishedAtMillis?.let(Instant::fromEpochMilliseconds),
                    now,
                )
            it.copy(startedAtMillis = start?.toEpochMilliseconds(), finishedAtMillis = null)
        }
    }

    override suspend fun setTimes(
        date: LocalDate,
        startedAt: Instant?,
        finishedAt: Instant?,
    ) = dao.editDay(date.toString()) {
        it.copy(startedAtMillis = startedAt?.toEpochMilliseconds(), finishedAtMillis = finishedAt?.toEpochMilliseconds())
    }

    override suspend fun deleteDay(date: LocalDate): DeletedDay? = dao.deleteDay(date.toString())?.let { DeletedDay(date, it) }

    override suspend fun restoreDay(deleted: DeletedDay): Boolean = dao.restoreDay(deleted.rows)

    override suspend fun moveBlock(
        id: BlockId,
        by: Int,
    ) = dao.moveBlock(id.value, by)

    override suspend fun removeBlock(id: BlockId): RemovedBlock? {
        val rows = dao.removeBlock(id.value) ?: return null
        val name =
            exercises
                .exercise(ExerciseId(rows.block.exerciseId))
                .first()
                ?.fields
                ?.name
                .orEmpty()
        return RemovedBlock(name, rows)
    }

    override suspend fun restoreBlock(removed: RemovedBlock): Boolean = dao.restoreBlock(removed.rows)

    override suspend fun editSet(
        id: SetId,
        edit: SetEdit,
    ) = dao.changeSet(id.value) { row, _, _ ->
        when (edit) {
            is SetEdit.Reps -> {
                row.copy(reps = edit.reps?.coerceAtLeast(0))
            }

            is SetEdit.Weight -> {
                row.copy(weight = edit.weight?.coerceAtLeast(0.0)?.let(::roundToHundredths))
            }

            is SetEdit.Duration -> {
                row.copy(
                    durationSeconds =
                        edit.duration
                            ?.inWholeSeconds
                            ?.toInt()
                            ?.coerceAtLeast(0),
                )
            }

            is SetEdit.Distance -> {
                row.copy(distance = edit.distance?.coerceAtLeast(0.0))
            }

            is SetEdit.Unit -> {
                row.copy(weightUnit = edit.unit.name)
            }

            is SetEdit.Rpe -> {
                if (Rpe.isValid(edit.rpe)) row.copy(rpe = edit.rpe) else row
            }

            is SetEdit.Bodyweight -> {
                row.copy(bodyweight = edit.on)
            }
        }
    }

    override suspend fun setDone(
        id: SetId,
        done: Boolean,
    ) {
        val now = now()
        dao.changeSet(id.value, startAtMillis = now.toEpochMilliseconds(), today = clock.today(zone).toString()) { row, exercise, block ->
            val set = row.toSetEntry()
            when {
                set.done == done -> row
                done -> set.ticked(now, block.tracks(exercise)).toEntity(row.blockId, row.position)
                else -> row.copy(doneAtMillis = null)
            }
        }
    }

    override suspend fun logHold(
        id: SetId,
        held: Duration,
        startedAt: Instant,
        at: Instant,
    ) = dao.changeSet(
        id.value,
        startAtMillis = startedAt.toEpochMilliseconds(),
        today = clock.today(zone).toString(),
    ) { row, exercise, block ->
        if (row.doneAtMillis != null) return@changeSet row
        row
            .toSetEntry()
            .copy(duration = held)
            .ticked(at, block.tracks(exercise))
            .toEntity(row.blockId, row.position)
    }

    // Logged sets keep the load they were done with.
    override suspend fun stepWeight(
        blockId: BlockId,
        up: Boolean,
    ) = dao.changeSets(blockId.value) { sets ->
        sets.map { row ->
            if (row.warmup || row.doneAtMillis != null) return@map row
            val step = row.toSetEntry().weightUnit.step()
            row.copy(weight = roundToHundredths(((row.weight ?: 0.0) + if (up) step else -step).coerceAtLeast(0.0)))
        }
    }

    override suspend fun addSet(blockId: BlockId) {
        val block = blockOf(blockId) ?: return
        dao.addSets(blockId.value, listOf(DayPlan.nextSet(block).toEntity()))
    }

    override suspend fun addWarmups(blockId: BlockId) {
        val block = blockOf(blockId) ?: return
        dao.addSets(blockId.value, DayPlan.warmupsFor(block).map { it.toEntity() })
    }

    private suspend fun blockOf(id: BlockId): Block? =
        dayOfBlock(id)
            .first()
            ?.blocks
            ?.firstOrNull { it.id == id }

    override suspend fun removeSet(id: SetId): RemovedSet? = dao.removeSet(id.value)?.let(::RemovedSet)

    override suspend fun restoreSet(removed: RemovedSet) {
        dao.restoreSet(removed.row)
    }

    private fun now(): Instant = clock.now()
}

private fun PlannedBlock.toRows(): BlockRows =
    BlockRows(
        block =
            BlockEntity(
                dayId = 0,
                exerciseId = exerciseId.value,
                position = 0,
                section = section,
                restSeconds = rest.inWholeSeconds.toInt(),
                restMaxSeconds = restMax?.inWholeSeconds?.toInt(),
                durationSeconds = duration.inWholeSeconds.toInt(),
                tracksWeight = tracksOverride?.weight,
                tracksReps = tracksOverride?.reps,
                tracksTime = tracksOverride?.time,
                tracksDistance = tracksOverride?.distance,
                warmupSets = warmupSets,
                notes = notes,
            ),
        sets = sets.map { it.toEntity() },
    )

private fun PlannedSet.toEntity() =
    SetEntryEntity(
        blockId = 0,
        position = 0,
        warmup = warmup,
        targetReps = targetReps,
        targetRepsMax = targetRepsMax,
        targetDurationSeconds = targetDuration?.inWholeSeconds?.toInt(),
        weight = weight,
        weightUnit = weightUnit.name,
        reps = null,
        durationSeconds = null,
        distance = null,
        distanceUnit = distanceUnit.name,
        rpe = null,
        doneAtMillis = null,
        bodyweight = bodyweight,
    )

private fun SetEntry.toEntity(
    blockId: Long,
    position: Int,
) = SetEntryEntity(
    id = id.value,
    blockId = blockId,
    position = position,
    warmup = warmup,
    targetReps = targetReps,
    targetRepsMax = targetRepsMax,
    targetDurationSeconds = targetDuration?.inWholeSeconds?.toInt(),
    weight = weight,
    weightUnit = weightUnit.name,
    reps = reps,
    durationSeconds = duration?.inWholeSeconds?.toInt(),
    distance = distance,
    distanceUnit = distanceUnit.name,
    rpe = rpe,
    doneAtMillis = doneAt?.toEpochMilliseconds(),
    bodyweight = bodyweight,
)

// Warm-ups are left out here, the one place every history read from the database goes through.
private fun PastSetRow.toPastSet(): PastSet? {
    val entry = set.toSetEntry()
    if (!entry.working) return null
    return entry.toPastSet(LocalDate.parse(day), ExerciseId(exerciseId), set.position, deload)
}

// Unknown enum names (a newer version's data) fall back to the defaults instead of crashing.
private fun SetEntryEntity.toSetEntry(): SetEntry =
    SetEntry(
        id = SetId(id),
        warmup = warmup,
        targetReps = targetReps,
        targetRepsMax = targetRepsMax,
        targetDuration = targetDurationSeconds?.seconds,
        weight = weight,
        weightUnit = WeightUnit.entries.firstOrNull { it.name == weightUnit } ?: WeightUnit.Kg,
        reps = reps,
        duration = durationSeconds?.seconds,
        distance = distance,
        distanceUnit = DistanceUnit.entries.firstOrNull { it.name == distanceUnit } ?: DistanceUnit.Km,
        rpe = rpe,
        doneAt = doneAtMillis?.let(Instant::fromEpochMilliseconds),
        bodyweight = bodyweight,
    )

private fun BlockEntity.override(): Tracks? {
    val w = tracksWeight ?: return null
    val r = tracksReps ?: return null
    val t = tracksTime ?: return null
    val d = tracksDistance ?: return null
    return Tracks(w, r, t, d)
}

private fun BlockEntity.tracks(exercise: ExerciseEntity?): Tracks =
    override() ?: exercise?.let { Tracks(it.tracksWeight, it.tracksReps, it.tracksTime, it.tracksDistance) } ?: Tracks.WeightAndReps

// Blocks whose exercise is unknown (never expected: exercises are only archived) are dropped.
private fun DayWithBlocks.toDay(exercises: Map<ExerciseId, Exercise>): WorkoutDay =
    WorkoutDay(
        id = DayId(day.id),
        date = LocalDate.parse(day.day),
        deload = day.deload,
        name = day.name,
        notes = day.notes,
        startedAt = day.startedAtMillis?.let(Instant::fromEpochMilliseconds),
        finishedAt = day.finishedAtMillis?.let(Instant::fromEpochMilliseconds),
        fatigue = day.fatigue?.takeIf { it in Fatigue.range },
        blocks =
            blocks.sortedBy { it.block.position }.mapNotNull { (block, sets) ->
                val exercise = exercises[ExerciseId(block.exerciseId)] ?: return@mapNotNull null
                Block(
                    id = BlockId(block.id),
                    exercise = exercise,
                    section = block.section,
                    rest = block.restSeconds.seconds,
                    restMax = block.restMaxSeconds?.seconds,
                    duration = block.durationSeconds.seconds,
                    tracksOverride = block.override(),
                    warmupSets = block.warmupSets,
                    notes = block.notes,
                    sets = sets.sortedWith(compareBy({ !it.warmup }, { it.position })).map { it.toSetEntry() },
                )
            },
    )
