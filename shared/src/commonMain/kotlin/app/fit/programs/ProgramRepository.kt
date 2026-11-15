// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.content.ProgramDraft
import app.fit.data.db.ProgramDao
import app.fit.data.db.ProgramDayEntity
import app.fit.data.db.ProgramEntity
import app.fit.data.db.ProgramWithDays
import app.fit.day.today
import app.fit.routines.RoutineId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.time.Clock

sealed interface ProgramNameResult {
    data class Saved(
        val id: ProgramId,
    ) : ProgramNameResult

    data object NameBlank : ProgramNameResult

    data object NameTaken : ProgramNameResult
}

// A program deleted, for Undo.
data class DeletedProgram(
    val name: String,
    internal val rows: ProgramWithDays,
)

// The days a deleted routine left as rest days, for Undo.
data class UnlinkedDays(
    val routineId: RoutineId,
    val days: List<ProgramDayRef>,
)

/** Programs: weekly schedules of routines. At most one is active; its plan fills empty days. */
interface ProgramRepository {
    /** Every program, the active one first, then by name. */
    val programs: Flow<List<Program>>

    fun program(id: ProgramId): Flow<Program?>

    /** The active program, or null. */
    val active: Flow<Program?>

    suspend fun create(name: String): ProgramNameResult

    suspend fun rename(
        id: ProgramId,
        name: String,
    ): ProgramNameResult

    suspend fun setNotes(
        id: ProgramId,
        notes: String,
    )

    /** Week 1 is the week of [date]; null: no weeks and no deloads. */
    suspend fun setStart(
        id: ProgramId,
        date: LocalDate?,
    )

    /** Null is never; otherwise within [ProgramSchedule.DELOAD_EVERY]. */
    suspend fun setDeloadEvery(
        id: ProgramId,
        every: Int?,
    )

    /** Within [Deload.PERCENTS]. */
    suspend fun setDeloadPercent(
        id: ProgramId,
        percent: Int,
    )

    /** The routine for [weekday]; null makes it a rest day. */
    suspend fun setDay(
        id: ProgramId,
        weekday: DayOfWeek,
        routine: RoutineId?,
    )

    /** Activating one deactivates any other; one without a start starts this week (its Monday). */
    suspend fun setActive(
        id: ProgramId,
        active: Boolean,
    )

    /** A copy named "<name> copy" (or "copy 2", …), not active; null if the program is gone. */
    suspend fun copy(id: ProgramId): ProgramId?

    /** Deletes the program and returns it, for [restore]. */
    suspend fun delete(id: ProgramId): DeletedProgram?

    /** Puts a deleted program back (see [ProgramDao.restore]); false if it's already there. */
    suspend fun restore(deleted: DeletedProgram): Boolean

    /** The days that plan [routine], before it's deleted (its days then become rest days). */
    suspend fun daysUsing(routine: RoutineId): UnlinkedDays

    /** Gives the days back their routine once it's restored, unless they were given another since. */
    suspend fun relink(unlinked: UnlinkedDays)

    /** Adds and updates built-in programs from content (see [ProgramDao.syncBuiltIn]). */
    suspend fun syncBuiltIn(drafts: List<ProgramDraft>)
}

class RoomProgramRepository(
    private val dao: ProgramDao,
    private val clock: Clock,
    private val zone: TimeZone,
) : ProgramRepository {
    override val programs: Flow<List<Program>> = dao.all().map { rows -> rows.map { it.toProgram() } }

    override fun program(id: ProgramId): Flow<Program?> = dao.byId(id.value).map { it?.toProgram() }

    override val active: Flow<Program?> = dao.active().map { it?.toProgram() }

    override suspend fun create(name: String): ProgramNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return ProgramNameResult.NameBlank
        val entity =
            ProgramEntity(
                contentId = null,
                name = clean,
                notes = "",
                active = false,
                startsOn = null,
                deloadEvery = null,
                deloadPercent = Deload.DEFAULT_PERCENT,
                edited = false,
                createdAtMillis = now(),
            )
        val id = dao.create(entity) ?: return ProgramNameResult.NameTaken
        return ProgramNameResult.Saved(ProgramId(id))
    }

    override suspend fun rename(
        id: ProgramId,
        name: String,
    ): ProgramNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return ProgramNameResult.NameBlank
        return if (dao.edit(id.value) { it.copy(name = clean) }) ProgramNameResult.Saved(id) else ProgramNameResult.NameTaken
    }

    override suspend fun setNotes(
        id: ProgramId,
        notes: String,
    ) {
        dao.edit(id.value) { it.copy(notes = notes) }
    }

    override suspend fun setStart(
        id: ProgramId,
        date: LocalDate?,
    ) {
        dao.edit(id.value, content = false) { it.copy(startsOn = date?.toString()) }
    }

    override suspend fun setDeloadEvery(
        id: ProgramId,
        every: Int?,
    ) {
        if (every != null && every !in ProgramSchedule.DELOAD_EVERY) return
        dao.edit(id.value) { it.copy(deloadEvery = every) }
    }

    override suspend fun setDeloadPercent(
        id: ProgramId,
        percent: Int,
    ) {
        if (percent !in Deload.PERCENTS) return
        dao.edit(id.value) { it.copy(deloadPercent = percent) }
    }

    override suspend fun setDay(
        id: ProgramId,
        weekday: DayOfWeek,
        routine: RoutineId?,
    ) = dao.setDay(id.value, weekday.isoDayNumber, routine?.value)

    override suspend fun setActive(
        id: ProgramId,
        active: Boolean,
    ) = dao.setActive(id.value, active, ProgramSchedule.monday(clock.today(zone)).toString())

    override suspend fun copy(id: ProgramId): ProgramId? = dao.copy(id.value, now())?.let(::ProgramId)

    override suspend fun delete(id: ProgramId): DeletedProgram? = dao.deleteProgram(id.value)?.let { DeletedProgram(it.program.name, it) }

    override suspend fun restore(deleted: DeletedProgram): Boolean = dao.restore(deleted.rows)

    override suspend fun daysUsing(routine: RoutineId): UnlinkedDays {
        val days =
            dao.daysUsing(routine.value).mapNotNull { day ->
                val program = dao.find(day.programId) ?: return@mapNotNull null
                ProgramDayRef(ProgramId(program.id), program.name, weekdayOf(day.weekday))
            }
        return UnlinkedDays(routine, days)
    }

    override suspend fun relink(unlinked: UnlinkedDays) =
        dao.relink(
            unlinked.days.map { ProgramDayEntity(it.programId.value, it.weekday.isoDayNumber, unlinked.routineId.value) },
            unlinked.routineId.value,
        )

    override suspend fun syncBuiltIn(drafts: List<ProgramDraft>) {
        val created = now()
        dao.syncBuiltIn(drafts.map { it.toRows(created) })
    }

    private fun now(): Long = clock.now().toEpochMilliseconds()
}

// A program file as rows: inactive, without a start, its days by ISO weekday and routine content id.
internal fun ProgramDraft.toRows(createdAtMillis: Long): Pair<ProgramEntity, Map<Int, String>> =
    ProgramEntity(
        contentId = id,
        name = name,
        notes = notes,
        active = false,
        startsOn = null,
        deloadEvery = deloadEvery,
        deloadPercent = deloadPercent ?: Deload.DEFAULT_PERCENT,
        edited = false,
        createdAtMillis = createdAtMillis,
    ) to days.mapKeys { it.key.isoDayNumber }

private fun weekdayOf(iso: Int): DayOfWeek = DayOfWeek.entries[(iso - 1).coerceIn(0, 6)]

// A day whose routine was deleted (SET NULL) is a rest day, like a missing one.
private fun ProgramWithDays.toProgram(): Program =
    Program(
        id = ProgramId(program.id),
        contentId = program.contentId,
        name = program.name,
        notes = program.notes,
        active = program.active,
        startsOn = program.startsOn?.let(LocalDate::parse),
        deloadEvery = program.deloadEvery?.takeIf { it in ProgramSchedule.DELOAD_EVERY },
        deloadPercent = program.deloadPercent.takeIf { it in Deload.PERCENTS } ?: Deload.DEFAULT_PERCENT,
        days = days.mapNotNull { day -> day.routineId?.let { weekdayOf(day.weekday) to RoutineId(it) } }.toMap(),
        edited = program.edited,
    )
