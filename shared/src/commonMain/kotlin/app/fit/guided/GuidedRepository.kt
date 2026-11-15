// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.guided

import app.fit.content.GuidedStepDraft
import app.fit.content.RecoveryDraft
import app.fit.content.Source
import app.fit.core.BodyArea
import app.fit.data.db.DeletedPlanRows
import app.fit.data.db.GuidedDao
import app.fit.data.db.GuidedLogEntity
import app.fit.data.db.GuidedPlanEntity
import app.fit.data.db.GuidedPlanRows
import app.fit.data.db.GuidedSourceEntity
import app.fit.data.db.GuidedStepEntity
import app.fit.exercises.Exercise
import app.fit.exercises.ExerciseId
import app.fit.exercises.ExerciseRepository
import app.fit.exercises.Sides
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Guided plans (recovery sessions and lesson drills) and the log of their runs. */
interface GuidedRepository {
    /** Plans of [kind] by title, their steps resolved against the exercise library. */
    fun plans(kind: GuidedKind): Flow<List<GuidedPlan>>

    fun plan(id: GuidedPlanId): Flow<GuidedPlan?>

    /** Pinned recovery sessions, the latest pin first. */
    val pinned: Flow<List<GuidedPlan>>

    suspend fun setPinned(
        id: GuidedPlanId,
        pinned: Boolean,
    )

    /** Writes one run; the same run (same start) written again is kept once. */
    suspend fun log(log: GuidedLog)

    /** Runs of [kind] that started on a day from [from] to [to], oldest first. */
    fun logs(
        kind: GuidedKind,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<GuidedLog>>

    /** Every run of [kind], oldest first. */
    fun logs(kind: GuidedKind): Flow<List<GuidedLog>>

    /** Deletes a recovery session and returns it, for [restore]; its logs stay. Null if it's gone. */
    suspend fun delete(id: GuidedPlanId): DeletedPlan?

    /** Puts a deleted session back with its pin and its logs' link; false if it can't (it's there, or its id was imported since). */
    suspend fun restore(deleted: DeletedPlan): Boolean

    /** Adds and updates built-in recovery sessions from content (see [GuidedDao.writeContent]). */
    suspend fun syncBuiltIn(drafts: List<RecoveryDraft>)
}

/** A session that was just deleted; only the repository that made it can put it back. */
interface DeletedPlan {
    val title: String
}

private class RoomDeletedPlan(
    val rows: DeletedPlanRows,
) : DeletedPlan {
    override val title: String get() = rows.rows.plan.title
}

class RoomGuidedRepository(
    private val dao: GuidedDao,
    exercises: ExerciseRepository,
    private val clock: Clock,
) : GuidedRepository {
    private val library: Flow<Map<ExerciseId, Exercise>> = exercises.exercises.map { list -> list.associateBy { it.id } }

    override fun plans(kind: GuidedKind): Flow<List<GuidedPlan>> =
        combine(dao.plans(kind.column), library) { rows, library -> rows.map { it.toPlan(library) } }

    override fun plan(id: GuidedPlanId): Flow<GuidedPlan?> = combine(dao.byId(id.value), library) { row, library -> row?.toPlan(library) }

    override val pinned: Flow<List<GuidedPlan>> =
        plans(GuidedKind.Recovery).map { plans -> plans.filter { it.pinned }.sortedByDescending { it.pinnedAt } }

    override suspend fun setPinned(
        id: GuidedPlanId,
        pinned: Boolean,
    ) = dao.setPinned(id.value, if (pinned) clock.now().toEpochMilliseconds() else null)

    override suspend fun log(log: GuidedLog) =
        dao.insertLog(
            GuidedLogEntity(
                planId = log.planId?.value,
                kind = log.kind.column,
                title = log.title,
                day = log.day.toString(),
                startedAtMillis = log.startedAt.toEpochMilliseconds(),
                endedAtMillis = log.endedAt.toEpochMilliseconds(),
                activeMillis = log.duration.inWholeMilliseconds,
                outcome = log.outcome.name.uppercase(),
                stepsDone = log.stepsDone,
            ),
        )

    override fun logs(
        kind: GuidedKind,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<GuidedLog>> = dao.logs(kind.column, from.toString(), to.toString()).map { rows -> rows.mapNotNull { it.toLog() } }

    override fun logs(kind: GuidedKind): Flow<List<GuidedLog>> = dao.logs(kind.column).map { rows -> rows.mapNotNull { it.toLog() } }

    override suspend fun delete(id: GuidedPlanId): DeletedPlan? = dao.deletePlan(id.value)?.let(::RoomDeletedPlan)

    override suspend fun restore(deleted: DeletedPlan): Boolean = deleted is RoomDeletedPlan && dao.restorePlan(deleted.rows)

    override suspend fun syncBuiltIn(drafts: List<RecoveryDraft>) {
        val created = clock.now().toEpochMilliseconds()
        dao.writeContent(drafts.map { it.toRows(created) }, imported = false)
    }
}

// A recovery session file as rows; its steps' exercises are content ids for the DAO to resolve.
internal fun RecoveryDraft.toRows(
    createdAtMillis: Long,
): Triple<GuidedPlanEntity, List<Pair<GuidedStepEntity, String?>>, List<GuidedSourceEntity>> =
    Triple(
        GuidedPlanEntity(
            contentId = id,
            kind = GuidedKind.Recovery.column,
            title = title,
            areas = areas.joinToString(",") { it.slug },
            minutes = minutes,
            transitionSeconds = transition.inWholeSeconds.toInt(),
            notes = notes,
            pinnedAtMillis = null,
            edited = false,
            createdAtMillis = createdAtMillis,
        ),
        steps.map { it.toEntity() },
        sources.map { GuidedSourceEntity(planId = 0, position = 0, title = it.title, url = it.url) },
    )

// A step to store, with its exercise's content id for the DAO to resolve.
internal fun GuidedStepDraft.toEntity(): Pair<GuidedStepEntity, String?> =
    GuidedStepEntity(
        planId = 0,
        position = 0,
        exerciseId = null,
        name = name,
        durationSeconds = duration?.inWholeSeconds?.toInt(),
        reps = reps,
        sides = sides?.name?.uppercase(),
        cue = cue,
        image = image,
        rounds = rounds,
    ) to exercise

// How a kind is stored: RECOVERY, PRACTICE.
internal val GuidedKind.column: String get() = name.uppercase()

private fun enumOf(text: String?): Sides? = Sides.entries.firstOrNull { it.name.equals(text, ignoreCase = true) }

// A step whose exercise is missing (it can't be: exercises are only archived) falls back to its own name.
private fun GuidedPlanRows.toPlan(library: Map<ExerciseId, Exercise>): GuidedPlan =
    GuidedPlan(
        id = GuidedPlanId(plan.id),
        contentId = plan.contentId,
        kind = GuidedKind.entries.firstOrNull { it.name.equals(plan.kind, ignoreCase = true) } ?: GuidedKind.Recovery,
        title = plan.title,
        areas = plan.areas.split(',').mapNotNull { BodyArea.fromSlug(it.trim()) },
        minutes = plan.minutes,
        transition = plan.transitionSeconds.coerceAtLeast(0).seconds,
        notes = plan.notes,
        steps =
            steps.sortedBy { it.position }.map { step ->
                val exercise = step.exerciseId?.let { library[ExerciseId(it)] }?.fields
                GuidedStep(
                    name = exercise?.name ?: step.name.orEmpty(),
                    duration = step.durationSeconds?.seconds,
                    reps = step.reps.takeIf { step.durationSeconds == null },
                    sides = enumOf(step.sides) ?: exercise?.sides ?: Sides.Both,
                    cue = step.cue ?: exercise?.notes.orEmpty(),
                    image = step.image ?: exercise?.images?.firstOrNull(),
                    rounds = step.rounds.coerceAtLeast(1),
                    exerciseId = step.exerciseId?.let(::ExerciseId),
                )
            },
        sources = sources.sortedBy { it.position }.map { Source(it.title, it.url) },
        pinnedAt = plan.pinnedAtMillis?.let(Instant::fromEpochMilliseconds),
        edited = plan.edited,
    )

private fun GuidedLogEntity.toLog(): GuidedLog? {
    val kind = GuidedKind.entries.firstOrNull { it.name.equals(kind, ignoreCase = true) } ?: return null
    val outcome = GuidedOutcome.entries.firstOrNull { it.name.equals(outcome, ignoreCase = true) } ?: return null
    return GuidedLog(
        planId = planId?.let(::GuidedPlanId),
        kind = kind,
        title = title,
        day = LocalDate.parse(day),
        startedAt = Instant.fromEpochMilliseconds(startedAtMillis),
        endedAt = Instant.fromEpochMilliseconds(endedAtMillis),
        outcome = outcome,
        stepsDone = stepsDone,
        duration = activeMillis.coerceAtLeast(0).milliseconds,
    )
}
