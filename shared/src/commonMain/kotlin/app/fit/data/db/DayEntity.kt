// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

// One per local day; [day] is the ISO date ("2026-11-09"), so it sorts and compares as text.
@Entity(tableName = "workout_day", indices = [Index("day", unique = true)])
data class WorkoutDayEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val day: String,
    val name: String,
    val notes: String,
    val startedAtMillis: Long?,
    val finishedAtMillis: Long?,
    // 1–5, entered in the summary.
    val fatigue: Int? = null,
    // A routine was applied with deload loads, so progression skips the day.
    @ColumnInfo(defaultValue = "0") val deload: Boolean = false,
)

// A copy of a routine slot (or an added exercise) for one day. The exercise is only ever archived.
@Entity(
    tableName = "block",
    foreignKeys = [
        ForeignKey(WorkoutDayEntity::class, ["id"], ["dayId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"]),
    ],
    indices = [Index("dayId"), Index("exerciseId")],
)
data class BlockEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val dayId: Long,
    val exerciseId: Long,
    val position: Int,
    val section: String,
    val restSeconds: Int,
    val restMaxSeconds: Int?,
    val durationSeconds: Int,
    // "Record as": all four null, or all four set.
    val tracksWeight: Boolean?,
    val tracksReps: Boolean?,
    val tracksTime: Boolean?,
    val tracksDistance: Boolean?,
    val warmupSets: Int,
    val notes: String,
)

// Targets and what was done live in separate columns, so logging never rewrites the plan.
@Entity(
    tableName = "set_entry",
    foreignKeys = [ForeignKey(BlockEntity::class, ["id"], ["blockId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("blockId")],
)
data class SetEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val blockId: Long,
    val position: Int,
    val warmup: Boolean,
    val targetReps: Int,
    val targetRepsMax: Int?,
    val targetDurationSeconds: Int?,
    val weight: Double?,
    val weightUnit: String,
    val reps: Int?,
    val durationSeconds: Int?,
    val distance: Double?,
    val distanceUnit: String,
    // 5–10 in halves, or none; read by the progression rule's RPE gate.
    val rpe: Double?,
    val doneAtMillis: Long?,
    // [weight] is then what's added to the body weight.
    @ColumnInfo(defaultValue = "0") val bodyweight: Boolean = false,
)

data class BlockWithSets(
    @Embedded val block: BlockEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["blockId"]) val sets: List<SetEntryEntity>,
)

data class DayWithBlocks(
    @Embedded val day: WorkoutDayEntity,
    @Relation(entity = BlockEntity::class, parentColumns = ["id"], entityColumns = ["dayId"]) val blocks: List<BlockWithSets>,
)

// A done set with the day and exercise it belongs to, for progression, records and history.
data class PastSetRow(
    @Embedded val set: SetEntryEntity,
    val day: String,
    val exerciseId: Long,
    val deload: Boolean,
)

// A removed set and its index among the block's warm-ups or working sets, for Undo.
data class RemovedSetRow(
    val set: SetEntryEntity,
    val index: Int,
)

// A new block and its sets before they're written; ids are filled in then.
data class BlockRows(
    val block: BlockEntity,
    val sets: List<SetEntryEntity>,
)

sealed interface CreateBlockOutcome {
    data class Created(
        val exerciseId: Long,
        val blockId: Long,
    ) : CreateBlockOutcome

    data object NameTaken : CreateBlockOutcome
}
