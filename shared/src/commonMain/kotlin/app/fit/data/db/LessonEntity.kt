// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

// [practicePlanId] is its drill (a PRACTICE guided plan); runs are logged against that plan.
@Entity(
    tableName = "lesson",
    foreignKeys = [ForeignKey(GuidedPlanEntity::class, ["id"], ["practicePlanId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("contentId", unique = true), Index("practicePlanId")],
)
data class LessonEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentId: String?,
    val title: String,
    // Markdown.
    val body: String,
    val practicePlanId: Long?,
    val createdAtMillis: Long,
    // Imported by the user: content updates skip it.
    @ColumnInfo(defaultValue = "0") val edited: Boolean = false,
)

// The exercises a lesson teaches. Exercises are never deleted (only archived).
@Entity(
    tableName = "lesson_exercise",
    primaryKeys = ["lessonId", "position"],
    foreignKeys = [
        ForeignKey(LessonEntity::class, ["id"], ["lessonId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"]),
    ],
    indices = [Index("exerciseId")],
)
data class LessonExerciseEntity(
    val lessonId: Long,
    val position: Int,
    val exerciseId: Long,
)

@Entity(
    tableName = "lesson_source",
    primaryKeys = ["lessonId", "position"],
    foreignKeys = [ForeignKey(LessonEntity::class, ["id"], ["lessonId"], onDelete = ForeignKey.CASCADE)],
)
data class LessonSourceEntity(
    val lessonId: Long,
    val position: Int,
    val title: String,
    val url: String?,
)

data class LessonRows(
    @Embedded val lesson: LessonEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["lessonId"]) val exercises: List<LessonExerciseEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["lessonId"]) val sources: List<LessonSourceEntity>,
)

// A deleted lesson with its drill and the runs logged against it, for Undo.
class DeletedLessonRows(
    val lesson: LessonRows,
    val practice: GuidedPlanRows?,
    val logIds: List<Long>,
)

const val LESSON_KIND = "lesson"

// What the sync or an import writes for one lesson; [exercises] and the steps' exercises are content ids.
class LessonSync(
    val lesson: LessonEntity,
    val exercises: List<String>,
    val sources: List<LessonSourceEntity>,
    // Null: the file has no drill.
    val practice: GuidedPlanEntity?,
    val steps: List<Pair<GuidedStepEntity, String?>>,
)
