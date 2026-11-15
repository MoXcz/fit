// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.programs

import app.fit.content.ProgramDraft
import app.fit.data.db.ProgramEntity
import app.fit.data.db.ProgramWithDays
import app.fit.routines.RoutineId
import app.fit.routines.RoutineRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate

// In memory, with the same rules as the Room one for what the screens use. A day whose routine is
// gone reads as a rest day, as SET NULL makes it there.
class FakeProgramRepository(
    private val routines: RoutineRepository? = null,
    initial: List<Program> = emptyList(),
    // What activating a program without a start takes as this week.
    private val today: LocalDate = LocalDate(2026, 11, 9),
) : ProgramRepository {
    val all = MutableStateFlow(initial)
    private var nextId = 2000L
    private val deleted = mutableMapOf<Long, Program>()

    private val visible: Flow<List<Program>> =
        if (routines == null) {
            all
        } else {
            combine(all, routines.routines) { list, existing ->
                val ids = existing.map { it.id }.toSet()
                val kept = list.map { program -> program.copy(days = program.days.filterValues { it in ids }) }
                // As SET NULL does: a deleted routine's days stay rest days when it comes back.
                if (kept != list) all.value = kept
                kept
            }
        }

    override val programs: Flow<List<Program>> = visible.map { list -> list.sortedWith(compareBy({ !it.active }, { it.name.lowercase() })) }

    override fun program(id: ProgramId): Flow<Program?> = visible.map { list -> list.firstOrNull { it.id == id } }

    override val active: Flow<Program?> = visible.map { list -> list.firstOrNull { it.active } }

    private fun taken(
        name: String,
        except: ProgramId? = null,
    ) = all.value.any { it.id != except && it.name.equals(name, ignoreCase = true) }

    private fun change(
        id: ProgramId,
        block: (Program) -> Program,
    ) = all.update { list -> list.map { if (it.id == id) block(it).copy(edited = true) else it } }

    override suspend fun create(name: String): ProgramNameResult {
        val clean = name.trim()
        return when {
            clean.isEmpty() -> {
                ProgramNameResult.NameBlank
            }

            taken(clean) -> {
                ProgramNameResult.NameTaken
            }

            else -> {
                val id = ProgramId(nextId++)
                all.update { it + Program(id, name = clean) }
                ProgramNameResult.Saved(id)
            }
        }
    }

    override suspend fun rename(
        id: ProgramId,
        name: String,
    ): ProgramNameResult {
        val clean = name.trim()
        if (clean.isEmpty()) return ProgramNameResult.NameBlank
        if (taken(clean, except = id)) return ProgramNameResult.NameTaken
        change(id) { it.copy(name = clean) }
        return ProgramNameResult.Saved(id)
    }

    override suspend fun setNotes(
        id: ProgramId,
        notes: String,
    ) = change(id) { it.copy(notes = notes) }

    override suspend fun setStart(
        id: ProgramId,
        date: LocalDate?,
    ) = all.update { list -> list.map { if (it.id == id) it.copy(startsOn = date) else it } }

    override suspend fun setDeloadEvery(
        id: ProgramId,
        every: Int?,
    ) {
        if (every == null || every in ProgramSchedule.DELOAD_EVERY) change(id) { it.copy(deloadEvery = every) }
    }

    override suspend fun setDeloadPercent(
        id: ProgramId,
        percent: Int,
    ) {
        if (percent in Deload.PERCENTS) change(id) { it.copy(deloadPercent = percent) }
    }

    override suspend fun setDay(
        id: ProgramId,
        weekday: DayOfWeek,
        routine: RoutineId?,
    ) = change(id) { it.copy(days = if (routine == null) it.days - weekday else it.days + (weekday to routine)) }

    override suspend fun setActive(
        id: ProgramId,
        active: Boolean,
    ) = all.update { list ->
        list.map {
            when {
                it.id == id && active -> it.copy(active = true, startsOn = it.startsOn ?: ProgramSchedule.monday(today))
                it.id == id -> it.copy(active = false)
                active -> it.copy(active = false)
                else -> it
            }
        }
    }

    override suspend fun copy(id: ProgramId): ProgramId? {
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
        val copy = source.copy(id = ProgramId(nextId++), contentId = null, name = name, active = false, edited = false)
        all.update { it + copy }
        return copy.id
    }

    override suspend fun delete(id: ProgramId): DeletedProgram? {
        val program = all.value.firstOrNull { it.id == id } ?: return null
        all.update { list -> list.filter { it.id != id } }
        deleted[id.value] = program
        return DeletedProgram(program.name, ProgramWithDays(row(program), emptyList()))
    }

    override suspend fun restore(deleted: DeletedProgram): Boolean {
        val program = this.deleted.remove(deleted.rows.program.id) ?: return false
        if (all.value.any { it.id == program.id }) return false
        val active = program.active && all.value.none { it.active }
        val name =
            generateSequence(0) { it + 1 }
                .map {
                    if (it ==
                        0
                    ) {
                        program.name
                    } else {
                        "${program.name} copy" + if (it > 1) " $it" else ""
                    }
                }.first { !taken(it) }
        all.update { it + program.copy(name = name, active = active) }
        return true
    }

    override suspend fun daysUsing(routine: RoutineId): UnlinkedDays =
        UnlinkedDays(
            routine,
            visible.first().flatMap { program ->
                program.days
                    .filterValues { it == routine }
                    .keys
                    .map { ProgramDayRef(program.id, program.name, it) }
            },
        )

    override suspend fun relink(unlinked: UnlinkedDays) {
        val current = visible.first()
        unlinked.days.forEach { ref ->
            val program = current.firstOrNull { it.id == ref.programId } ?: return@forEach
            if (ref.weekday !in program.days) {
                all.update { list ->
                    list.map {
                        if (it.id ==
                            ref.programId
                        ) {
                            it.copy(days = it.days + (ref.weekday to unlinked.routineId))
                        } else {
                            it
                        }
                    }
                }
            }
        }
    }

    val synced = mutableListOf<List<ProgramDraft>>()

    override suspend fun syncBuiltIn(drafts: List<ProgramDraft>) {
        synced += drafts
    }

    private fun row(program: Program) =
        ProgramEntity(program.id.value, program.contentId, program.name, program.notes, program.active, null, null, 0, false, 0)
}
