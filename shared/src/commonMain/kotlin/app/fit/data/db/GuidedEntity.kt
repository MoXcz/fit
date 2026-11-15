// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Embedded
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey
import androidx.room3.Relation

// A recovery session ([kind] RECOVERY) or a lesson's drill (PRACTICE, no content id: its lesson
// points at it).
@Entity(
    tableName = "guided_plan",
    indices = [Index("contentId", unique = true)],
)
data class GuidedPlanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentId: String?,
    val kind: String,
    val title: String,
    // Body area slugs, comma-separated.
    val areas: String,
    val minutes: Int,
    val transitionSeconds: Int,
    val notes: String,
    // Null: not pinned.
    val pinnedAtMillis: Long?,
    // Imported by the user: content updates skip it.
    val edited: Boolean,
    val createdAtMillis: Long,
)

// An exercise step ([exerciseId]) or an inline one ([name]); timed or counted. The exercise is never
// deleted (only archived), so a step keeps pointing at it.
@Entity(
    tableName = "guided_step",
    foreignKeys = [
        ForeignKey(GuidedPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(ExerciseEntity::class, ["id"], ["exerciseId"]),
    ],
    indices = [Index("planId"), Index("exerciseId")],
)
data class GuidedStepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long,
    val position: Int,
    val exerciseId: Long?,
    val name: String?,
    val durationSeconds: Int?,
    val reps: Int?,
    // BOTH or EACH; null: the exercise's.
    val sides: String?,
    val cue: String?,
    val image: String?,
    val rounds: Int,
)

@Entity(
    tableName = "guided_source",
    primaryKeys = ["planId", "position"],
    foreignKeys = [ForeignKey(GuidedPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
)
data class GuidedSourceEntity(
    val planId: Long,
    val position: Int,
    val title: String,
    val url: String?,
)

// One finished or stopped run. A deleted plan leaves its logs (SET NULL); [title] is the snapshot.
// [startedAtMillis] is unique, so writing the same run twice keeps one row.
@Entity(
    tableName = "guided_log",
    foreignKeys = [ForeignKey(GuidedPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("planId"), Index("day"), Index("startedAtMillis", unique = true)],
)
data class GuidedLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val planId: Long?,
    val kind: String,
    val title: String,
    // The ISO local date it started on.
    val day: String,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    // The time it ran, paused time left out.
    val activeMillis: Long,
    // COMPLETED or STOPPED.
    val outcome: String,
    val stepsDone: Int,
)

data class GuidedPlanRows(
    @Embedded val plan: GuidedPlanEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["planId"]) val steps: List<GuidedStepEntity>,
    @Relation(parentColumns = ["id"], entityColumns = ["planId"]) val sources: List<GuidedSourceEntity>,
)

// A deleted plan, the runs logged against it (their link goes with the plan) and its reminder (which
// goes with it too), for Undo.
class DeletedPlanRows(
    val rows: GuidedPlanRows,
    val logIds: List<Long>,
    val reminder: ReminderEntity? = null,
)

const val RECOVERY_KIND = "recovery"

// A stored step and whose it is: a recovery session ([planContentId]) or a lesson's drill.
data class StoredStep(
    val planTitle: String,
    val planContentId: String?,
    val lessonContentId: String?,
    val lessonTitle: String?,
    val exercise: String,
    val durationSeconds: Int?,
    val reps: Int?,
)
