// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

@Entity(
    tableName = "routine",
    indices = [Index("contentId", unique = true), Index("name", unique = true)],
)
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentId: String?,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val notes: String,
    // Changed in the app: content updates skip it.
    val edited: Boolean,
    val createdAtMillis: Long,
)

// The exercise is never deleted (only archived), so items keep pointing at it.
@Entity(
    tableName = "routine_item",
    foreignKeys = [
        ForeignKey(RoutineEntity::class, ["id"], ["routineId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"]),
    ],
    indices = [Index("routineId"), Index("exerciseId")],
)
data class RoutineItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val exerciseId: Long,
    val position: Int,
    val section: String,
    val sets: Int,
    val reps: Int,
    val repsMax: Int?,
    val restSeconds: Int,
    val restMaxSeconds: Int?,
    val durationSeconds: Int,
    // "Record as": all four null, or all four set.
    val tracksWeight: Boolean?,
    val tracksReps: Boolean?,
    val tracksTime: Boolean?,
    val tracksDistance: Boolean?,
    val progressStep: Double?,
    val progressRpe: Double?,
    val warmupSets: Int,
    val notes: String,
)

data class RoutineWithItems(
    @Embedded val routine: RoutineEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["routineId"]) val items: List<RoutineItemEntity>,
)

sealed interface CreateOutcome {
    data class Created(
        val exerciseId: Long,
        val itemId: Long,
    ) : CreateOutcome

    data object NameTaken : CreateOutcome

    data object RoutineGone : CreateOutcome
}

// A built-in item the user deleted, so content updates don't bring it back.
@Entity(tableName = "content_tombstone", primaryKeys = ["kind", "contentId"])
data class ContentTombstoneEntity(
    val kind: String,
    val contentId: String,
)

const val ROUTINE_KIND = "routine"
