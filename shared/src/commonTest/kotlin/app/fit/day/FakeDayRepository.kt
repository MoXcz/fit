// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.day

import app.fit.core.moved
import app.fit.data.db.BlockEntity
import app.fit.data.db.BlockWithSets
import app.fit.data.db.DayWithBlocks
import app.fit.data.db.RemovedSetRow
import app.fit.data.db.SetEntryEntity
import app.fit.data.db.WorkoutDayEntity
import app.fit.exercises.ExerciseId
import app.fit.exercises.FakeExerciseRepository
import app.fit.exercises.Tracks
import app.fit.exercises.exercise
import app.fit.exercises.step
import app.fit.programs.ProgramRepository
import app.fit.programs.ProgramSchedule
import app.fit.routines.CreateAndAddResult
import app.fit.routines.NewExercise
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import app.fit.routines.inSectionOrder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Instant

class FixedClock(
    var now: Instant,
) : Clock {
    override fun now(): Instant = now
}

// In memory, with the same rules as the Room one (DayPlan, SessionClock) for what the screens use.
class FakeDayRepository(
    private val exercises: FakeExerciseRepository,
    private val routines: RoutineRepository,
    private val clock: Clock = FixedClock(Instant.parse("2026-11-09T10:00:00Z")),
    private val programs: ProgramRepository? = null,
) : DayRepository {
    val all = MutableStateFlow<Map<LocalDate, WorkoutDay>>(emptyMap())
    private var nextId = 5000L

    override fun day(date: LocalDate): Flow<WorkoutDay?> = all.map { it[date] }

    override fun days(
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<WorkoutDay>> = all.map { map -> map.values.filter { it.date in from..to }.sortedBy { it.date } }

    override fun dayOfBlock(id: BlockId): Flow<WorkoutDay?> =
        all.map { map ->
            map.values.firstOrNull { day ->
                day.blocks.any {
                    it.id == id
                }
            }
        }

    override suspend fun recentDays(
        before: LocalDate,
        limit: Int,
    ): List<WorkoutDay> =
        all.value.values
            .filter { it.date < before && it.blocks.any { block -> block.sets.isNotEmpty() } }
            .sortedByDescending { it.date }
            .take(limit)

    override suspend fun lastLoads(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): Map<ExerciseId, LastLoad> = Progression.lastLoads(history(date).filter { it.exerciseId in exerciseIds })

    fun history(date: LocalDate): List<PastSet> = allSets().filter { it.date < date }

    private fun allSets(): List<PastSet> =
        all.value.values.flatMap { day ->
            day.blocks.flatMap { block ->
                block.sets.withIndex().filter { it.value.working && it.value.done }.map { (index, set) ->
                    set.toPastSet(day.date, block.exercise.id, index, day.deload)
                }
            }
        }

    override fun trainedDays(): Flow<List<WorkoutDay>> =
        all.map { map -> map.values.filter { it.doneSets > 0 }.sortedByDescending { it.date } }

    override fun exerciseSets(id: ExerciseId): Flow<List<PastSet>> = all.map { allSets().filter { it.exerciseId == id } }

    override suspend fun earlierSets(
        exerciseIds: List<ExerciseId>,
        date: LocalDate,
    ): List<PastSet> = history(date).filter { it.exerciseId in exerciseIds }

    override suspend fun setFatigue(
        date: LocalDate,
        fatigue: Int?,
    ) {
        if (Fatigue.isValid(fatigue)) edit(date) { it.copy(fatigue = fatigue) }
    }

    private fun edit(
        date: LocalDate,
        change: (WorkoutDay) -> WorkoutDay,
    ) = all.update { map -> map + (date to change(map[date] ?: WorkoutDay(DayId(nextId++), date))) }

    private fun editBlock(
        id: BlockId,
        change: (Block) -> Block,
    ) = all.update { map ->
        map.mapValues { (_, day) -> day.copy(blocks = day.blocks.map { if (it.id == id) change(it) else it }) }
    }

    private fun editSet(
        id: SetId,
        change: (SetEntry) -> SetEntry,
    ) = all.update { map ->
        map.mapValues { (_, day) ->
            day.copy(blocks = day.blocks.map { block -> block.copy(sets = block.sets.map { if (it.id == id) change(it) else it }) })
        }
    }

    private suspend fun PlannedBlock.toBlock(): Block {
        val exercise = exercises.exercise(exerciseId).first()!!
        return Block(
            id = BlockId(nextId++),
            exercise = exercise,
            section = section,
            rest = rest,
            restMax = restMax,
            duration = duration,
            tracksOverride = tracksOverride,
            warmupSets = warmupSets,
            notes = notes,
            sets = sets.map { it.toSet() },
        )
    }

    private fun PlannedSet.toSet() =
        SetEntry(
            SetId(nextId++),
            warmup,
            targetReps,
            targetRepsMax,
            targetDuration,
            weight,
            weightUnit,
            distanceUnit = distanceUnit,
            bodyweight = bodyweight,
        )

    private suspend fun addBlocks(
        date: LocalDate,
        planned: List<PlannedBlock>,
        replace: Boolean = false,
        name: String? = null,
        nameIfBlank: Boolean = true,
        deload: Boolean = false,
    ): List<BlockId> {
        val made = planned.map { it.toBlock() }
        edit(date) { day ->
            val kept = if (replace) emptyList() else day.blocks
            day.copy(
                deload = if (replace) deload else day.deload || deload,
                blocks = inSectionOrder(kept + made) { it.section },
                name = if (name != null && (!nameIfBlank || day.name.isBlank())) name else day.name,
                startedAt = if (replace) null else day.startedAt,
                finishedAt = if (replace) null else day.finishedAt,
                fatigue = if (replace) null else day.fatigue,
            )
        }
        return made.map { it.id }
    }

    override suspend fun applyRoutine(
        date: LocalDate,
        routineId: RoutineId,
        replace: Boolean,
    ): Boolean {
        val routine = routines.routine(routineId).first() ?: return false
        val deload = ProgramSchedule.deloadPercent(programs?.active?.first(), date)
        addBlocks(
            date,
            DayPlan.fromRoutine(routine.items, history(date), deload),
            replace,
            routine.name,
            nameIfBlank = !replace,
            deload =
                deload != null,
        )
        return true
    }

    override suspend fun copyDay(
        from: LocalDate,
        to: LocalDate,
    ) {
        val source = all.value[from] ?: return
        addBlocks(to, DayPlan.copyOf(source), name = source.name.ifBlank { null }, deload = source.deload)
    }

    override suspend fun addExercise(
        date: LocalDate,
        exerciseId: ExerciseId,
        section: String,
    ): BlockId? {
        val exercise = exercises.exercise(exerciseId).first() ?: return null
        val deload = ProgramSchedule.deloadPercent(programs?.active?.first(), date)
        return addBlocks(date, listOf(DayPlan.adHoc(exercise, history(date), section, deload)), deload = deload != null).single()
    }

    override suspend fun createExerciseAndAdd(
        date: LocalDate,
        exercise: NewExercise,
    ): CreateAndAddResult {
        val name = exercise.name.trim()
        if (name.isEmpty()) return CreateAndAddResult.NameBlank
        if (exercises.all.value.any { it.fields.name.equals(name, ignoreCase = true) }) return CreateAndAddResult.NameTaken
        val created = exercise(nextId++, name, exercise.category, exercise.tracks)
        exercises.all.update { it + created }
        val id = addBlocks(date, listOf(DayPlan.adHoc(created, emptyList(), exercise.section))).single()
        return CreateAndAddResult.Added(id.value, created.id)
    }

    override suspend fun setName(
        date: LocalDate,
        name: String,
    ) = edit(date) { it.copy(name = name.trim()) }

    override suspend fun setNotes(
        date: LocalDate,
        notes: String,
    ) {
        notesWrites++
        edit(date) { it.copy(notes = notes) }
    }

    // Writes made by setNotes.
    var notesWrites = 0

    override suspend fun start(date: LocalDate) =
        edit(date) {
            if (it.startedAt ==
                null
            ) {
                it.copy(startedAt = clock.now(), finishedAt = null)
            } else {
                it
            }
        }

    override suspend fun finish(date: LocalDate) {
        val day = all.value[date] ?: return
        val at = SessionClock.finishAt(day.lastDoneAt, clock.now())
        edit(date) { it.copy(startedAt = it.startedAt ?: at, finishedAt = at) }
    }

    override suspend fun reopen(date: LocalDate) =
        edit(date) { it.copy(startedAt = SessionClock.reopenedStart(it.startedAt, it.finishedAt, clock.now()), finishedAt = null) }

    override suspend fun setTimes(
        date: LocalDate,
        startedAt: Instant?,
        finishedAt: Instant?,
    ) = edit(date) { it.copy(startedAt = startedAt, finishedAt = finishedAt) }

    override suspend fun deleteDay(date: LocalDate): DeletedDay? {
        val day = all.value[date] ?: return null
        all.update { it - date }
        return DeletedDay(date, day.toRows())
    }

    private val deleted = mutableMapOf<Long, WorkoutDay>()
    private val removedBlocks = mutableMapOf<Long, Pair<LocalDate, IndexedValue<Block>>>()
    private val removedSets = mutableMapOf<Long, Pair<BlockId, IndexedValue<SetEntry>>>()

    // The fake keeps the domain objects aside and hands out row snapshots keyed by id.
    private fun WorkoutDay.toRows(): DayWithBlocks {
        deleted[id.value] = this
        return DayWithBlocks(WorkoutDayEntity(id.value, date.toString(), name, notes, null, null), emptyList())
    }

    override suspend fun restoreDay(deleted: DeletedDay): Boolean {
        val day = this.deleted[deleted.rows.day.id] ?: return false
        val current = all.value[day.date]
        if (current != null && current.blocks.isNotEmpty()) return false
        all.update { it + (day.date to day) }
        return true
    }

    override suspend fun moveBlock(
        id: BlockId,
        by: Int,
    ) {
        val day = all.value.values.firstOrNull { d -> d.blocks.any { it.id == id } } ?: return
        val block = day.blocks.first { it.id == id }
        val section = day.blocks.filter { it.section == block.section }
        val moved = section.moved(section.indexOf(block), by).iterator()
        moves++
        edit(day.date) { it.copy(blocks = day.blocks.map { b -> if (b.section == block.section) moved.next() else b }) }
    }

    // Writes made by moveBlock: a drop is one.
    var moves = 0

    override suspend fun removeBlock(id: BlockId): RemovedBlock? {
        val day = all.value.values.firstOrNull { d -> d.blocks.any { it.id == id } } ?: return null
        val index = day.blocks.indexOfFirst { it.id == id }
        val block = day.blocks[index]
        edit(day.date) { it.copy(blocks = it.blocks - block) }
        removedBlocks[id.value] = day.date to IndexedValue(index, block)
        return RemovedBlock(block.exercise.fields.name, BlockWithSets(blockRow(id), emptyList()))
    }

    private fun blockRow(id: BlockId) = BlockEntity(id.value, 0, 0, 0, "", 0, null, 0, null, null, null, null, 0, "")

    override suspend fun restoreBlock(removed: RemovedBlock): Boolean {
        val (date, indexed) = removedBlocks.remove(removed.rows.block.id) ?: return false
        if (date !in all.value) return false
        edit(date) { day ->
            val blocks = day.blocks.toMutableList()
            blocks.add(indexed.index.coerceAtMost(blocks.size), indexed.value)
            day.copy(blocks = blocks)
        }
        return true
    }

    override suspend fun editSet(
        id: SetId,
        edit: SetEdit,
    ) = editSet(id) {
        when (edit) {
            is SetEdit.Reps -> it.copy(reps = edit.reps)
            is SetEdit.Weight -> it.copy(weight = edit.weight)
            is SetEdit.Duration -> it.copy(duration = edit.duration)
            is SetEdit.Distance -> it.copy(distance = edit.distance)
            is SetEdit.Unit -> it.copy(weightUnit = edit.unit)
            is SetEdit.Rpe -> if (Rpe.isValid(edit.rpe)) it.copy(rpe = edit.rpe) else it
            is SetEdit.Bodyweight -> it.copy(bodyweight = edit.on)
        }
    }

    override suspend fun setDone(
        id: SetId,
        done: Boolean,
    ) {
        val day = all.value.values.firstOrNull { d -> d.blocks.any { b -> b.sets.any { it.id == id } } } ?: return
        val tracks: Tracks = day.blocks.first { b -> b.sets.any { it.id == id } }.tracks
        val now = clock.now()
        editSet(id) { if (done) it.ticked(now, tracks) else it.copy(doneAt = null) }
        val today = clock.today(TimeZone.UTC)
        if (done &&
            day.date == today
        ) {
            edit(day.date) { if (it.startedAt == null && it.finishedAt == null) it.copy(startedAt = now) else it }
        }
    }

    override suspend fun logHold(
        id: SetId,
        held: kotlin.time.Duration,
        startedAt: Instant,
        at: Instant,
    ) {
        val day = all.value.values.firstOrNull { d -> d.blocks.any { b -> b.sets.any { it.id == id } } } ?: return
        val block = day.blocks.first { b -> b.sets.any { it.id == id } }
        if (block.sets.first { it.id == id }.done) return
        editSet(id) { it.copy(duration = held).ticked(at, block.tracks) }
        if (day.date == clock.today(TimeZone.UTC)) {
            edit(day.date) { if (it.startedAt == null && it.finishedAt == null) it.copy(startedAt = startedAt) else it }
        }
    }

    override suspend fun stepWeight(
        blockId: BlockId,
        up: Boolean,
    ) = editBlock(blockId) { block ->
        val pending =
            block.workingSets
                .filter { !it.done }
                .map { it.id }
                .toSet()
        block.copy(
            sets =
                block.sets.map { set ->
                    if (set.id in pending) {
                        set.copy(
                            weight = ((set.weight ?: 0.0) + if (up) set.weightUnit.step() else -set.weightUnit.step()).coerceAtLeast(0.0),
                        )
                    } else {
                        set
                    }
                },
        )
    }

    override suspend fun addSet(blockId: BlockId) = editBlock(blockId) { it.copy(sets = it.sets + DayPlan.nextSet(it).toSet()) }

    override suspend fun addWarmups(blockId: BlockId) =
        editBlock(blockId) { block -> block.copy(sets = DayPlan.warmupsFor(block).map { it.toSet() } + block.sets) }

    override suspend fun removeSet(id: SetId): RemovedSet? {
        val block =
            all.value.values
                .flatMap { it.blocks }
                .firstOrNull { b -> b.sets.any { it.id == id } } ?: return null
        val set = block.sets.first { it.id == id }
        val index = block.sets.filter { it.warmup == set.warmup }.indexOf(set)
        removedSets[id.value] = block.id to IndexedValue(index, set)
        editBlock(block.id) { b -> b.copy(sets = b.sets.filter { it.id != id }) }
        return RemovedSet(
            RemovedSetRow(SetEntryEntity(id.value, 0, 0, false, 0, null, null, null, "Kg", null, null, null, "Km", null, null), index),
        )
    }

    override suspend fun restoreSet(removed: RemovedSet) {
        val (blockId, indexed) = removedSets.remove(removed.row.set.id) ?: return
        editBlock(blockId) { b ->
            val (warmups, working) = b.sets.partition { it.warmup }
            val group = (if (indexed.value.warmup) warmups else working).toMutableList()
            group.add(indexed.index.coerceAtMost(group.size), indexed.value)
            b.copy(sets = if (indexed.value.warmup) group + working else warmups + group)
        }
    }
}
