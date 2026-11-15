// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.exercises

import app.fit.content.ExerciseDraft
import app.fit.core.BodyArea
import app.fit.core.DistanceUnit
import app.fit.core.WeightUnit
import app.fit.data.db.EditOutcome
import app.fit.data.db.ExerciseAreaEntity
import app.fit.data.db.ExerciseDao
import app.fit.data.db.ExerciseEntity
import app.fit.data.db.ExerciseImageEntity
import app.fit.data.db.ExerciseRefEntity
import app.fit.data.db.ExerciseRows
import app.fit.data.db.ExerciseWithDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

sealed interface ExerciseSaveResult {
    data object Saved : ExerciseSaveResult

    data object NameBlank : ExerciseSaveResult

    data object NameTaken : ExerciseSaveResult

    data object NothingTracked : ExerciseSaveResult

    data object NotFound : ExerciseSaveResult
}

/** The exercise library. Single source of truth for exercises, built-in and the user's own. */
interface ExerciseRepository {
    /** Every exercise, archived ones included, by name. */
    val exercises: Flow<List<Exercise>>

    fun exercise(id: ExerciseId): Flow<Exercise?>

    /** Saves the editor's values. An edited built-in exercise is no longer updated by content. */
    suspend fun update(
        id: ExerciseId,
        fields: ExerciseFields,
    ): ExerciseSaveResult

    suspend fun setArchived(
        id: ExerciseId,
        archived: Boolean,
    )

    /** Adds and updates built-in exercises from content; see [ExerciseDao.syncBuiltIn] for the rules. */
    suspend fun syncBuiltIn(drafts: List<ExerciseDraft>)
}

class RoomExerciseRepository(
    private val dao: ExerciseDao,
    private val clock: Clock,
) : ExerciseRepository {
    override val exercises: Flow<List<Exercise>> = dao.all().map { rows -> rows.map { it.toExercise() } }

    override fun exercise(id: ExerciseId): Flow<Exercise?> = dao.byId(id.value).map { it?.toExercise() }

    override suspend fun update(
        id: ExerciseId,
        fields: ExerciseFields,
    ): ExerciseSaveResult {
        val clean = fields.copy(name = fields.name.trim(), category = fields.category.trim())
        return when {
            clean.name.isEmpty() -> {
                ExerciseSaveResult.NameBlank
            }

            !clean.tracks.any -> {
                ExerciseSaveResult.NothingTracked
            }

            else -> {
                when (dao.saveEdit(id.value, clean.toRows(contentId = null, createdAtMillis = 0))) {
                    EditOutcome.Saved -> ExerciseSaveResult.Saved
                    EditOutcome.NotFound -> ExerciseSaveResult.NotFound
                    EditOutcome.NameTaken -> ExerciseSaveResult.NameTaken
                }
            }
        }
    }

    override suspend fun setArchived(
        id: ExerciseId,
        archived: Boolean,
    ) = dao.setArchived(id.value, archived)

    override suspend fun syncBuiltIn(drafts: List<ExerciseDraft>) {
        val now = clock.now().toEpochMilliseconds()
        dao.syncBuiltIn(drafts.map { it.fields.toRows(contentId = it.id, createdAtMillis = now) })
    }
}

internal fun ExerciseFields.toRows(
    contentId: String?,
    createdAtMillis: Long,
): ExerciseRows =
    ExerciseRows(
        exercise =
            ExerciseEntity(
                contentId = contentId,
                name = name,
                category = category,
                tracksWeight = tracks.weight,
                tracksReps = tracks.reps,
                tracksTime = tracks.time,
                tracksDistance = tracks.distance,
                sides = sides.name,
                weightUnit = weightUnit.name,
                distanceUnit = distanceUnit.name,
                defaultSets = defaults.sets,
                defaultReps = defaults.reps,
                defaultRepsMax = defaults.repsMax,
                defaultRestSeconds = defaults.rest.inWholeSeconds.toInt(),
                defaultRestMaxSeconds = defaults.restMax?.inWholeSeconds?.toInt(),
                defaultDurationSeconds = defaults.duration.inWholeSeconds.toInt(),
                defaultWeight = defaults.weight,
                notes = notes,
                videoUrl = videoUrl,
                archived = false,
                edited = false,
                createdAtMillis = createdAtMillis,
                equipment = equipment?.slug,
            ),
        refs =
            refs.mapIndexed {
                i,
                ref,
                ->
                ExerciseRefEntity(exerciseId = 0, position = i, kind = ref.kind.name, title = ref.title, url = ref.url)
            },
        areas = areas.mapIndexed { i, area -> ExerciseAreaEntity(exerciseId = 0, area = area.slug, position = i) },
        images = images.mapIndexed { i, path -> ExerciseImageEntity(exerciseId = 0, position = i, path = path) },
    )

// Unknown enum names (a newer version's data) fall back to the defaults instead of crashing.
internal fun ExerciseWithDetails.toExercise(): Exercise {
    val e = exercise
    val base = ExerciseFields(name = e.name)
    return Exercise(
        id = ExerciseId(e.id),
        contentId = e.contentId,
        archived = e.archived,
        edited = e.edited,
        fields =
            ExerciseFields(
                name = e.name,
                category = e.category,
                tracks = Tracks(e.tracksWeight, e.tracksReps, e.tracksTime, e.tracksDistance),
                sides = Sides.entries.firstOrNull { it.name == e.sides } ?: base.sides,
                areas = areas.sortedBy { it.position }.mapNotNull { BodyArea.fromSlug(it.area) },
                defaults =
                    ExerciseDefaults(
                        sets = e.defaultSets,
                        reps = e.defaultReps,
                        repsMax = e.defaultRepsMax,
                        rest = e.defaultRestSeconds.seconds,
                        restMax = e.defaultRestMaxSeconds?.seconds,
                        duration = e.defaultDurationSeconds.seconds,
                        weight = e.defaultWeight,
                    ),
                weightUnit = WeightUnit.entries.firstOrNull { it.name == e.weightUnit } ?: base.weightUnit,
                distanceUnit = DistanceUnit.entries.firstOrNull { it.name == e.distanceUnit } ?: base.distanceUnit,
                notes = e.notes,
                videoUrl = e.videoUrl,
                images = images.sortedBy { it.position }.map { it.path },
                equipment = e.equipment?.let(Equipment::fromSlug),
                refs =
                    refs.sortedBy { it.position }.map { ref ->
                        ExerciseRef(RefKind.entries.firstOrNull { it.name == ref.kind } ?: RefKind.Link, ref.title, ref.url)
                    },
            ),
    )
}
