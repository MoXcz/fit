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
    tableName = "exercise",
    indices = [Index("contentId", unique = true), Index("name", unique = true)],
)
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    // The content file's id; null for exercises made in the app.
    val contentId: String?,
    // NOCASE, so the unique index rejects "plank" next to "Plank".
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val category: String,
    val tracksWeight: Boolean,
    val tracksReps: Boolean,
    val tracksTime: Boolean,
    val tracksDistance: Boolean,
    val sides: String,
    val weightUnit: String,
    val distanceUnit: String,
    val defaultSets: Int,
    val defaultReps: Int,
    val defaultRepsMax: Int?,
    val defaultRestSeconds: Int,
    val defaultRestMaxSeconds: Int?,
    val defaultDurationSeconds: Int,
    val defaultWeight: Double,
    val notes: String,
    val videoUrl: String,
    val archived: Boolean,
    // Changed in the app: content updates skip it.
    val edited: Boolean,
    val createdAtMillis: Long,
    // The content format's slug; null when not said.
    val equipment: String? = null,
)

@Entity(
    tableName = "exercise_ref",
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("exerciseId")],
)
data class ExerciseRefEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val position: Int,
    val kind: String,
    val title: String,
    val url: String,
)

@Entity(
    tableName = "exercise_area",
    primaryKeys = ["exerciseId", "area"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)],
)
data class ExerciseAreaEntity(
    val exerciseId: Long,
    val area: String,
    val position: Int,
)

@Entity(
    tableName = "exercise_image",
    primaryKeys = ["exerciseId", "position"],
    foreignKeys = [ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"], onDelete = ForeignKey.CASCADE)],
)
data class ExerciseImageEntity(
    val exerciseId: Long,
    val position: Int,
    val path: String,
)

data class ExerciseWithDetails(
    @Embedded val exercise: ExerciseEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["exerciseId"]) val refs: List<ExerciseRefEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["exerciseId"]) val areas: List<ExerciseAreaEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["exerciseId"]) val images: List<ExerciseImageEntity>,
)

// An exercise and its child rows before they are written; the children's exerciseId is filled in then.
data class ExerciseRows(
    val exercise: ExerciseEntity,
    val refs: List<ExerciseRefEntity>,
    val areas: List<ExerciseAreaEntity>,
    val images: List<ExerciseImageEntity>,
)
