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
    tableName = "program",
    indices = [Index("contentId", unique = true), Index("name", unique = true)],
)
data class ProgramEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val contentId: String?,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val notes: String,
    // At most one program is active; the DAO keeps it so.
    val active: Boolean,
    // ISO date of any day in week 1; null: no weeks.
    val startsOn: String?,
    val deloadEvery: Int?,
    val deloadPercent: Int,
    // Changed in the app: content updates skip it.
    val edited: Boolean,
    val createdAtMillis: Long,
)

// A training day: [weekday] is ISO (1 Monday … 7 Sunday). A missing row, or a routine deleted since
// (SET NULL), is a rest day.
@Entity(
    tableName = "program_day",
    primaryKeys = ["programId", "weekday"],
    foreignKeys = [
        ForeignKey(ProgramEntity::class, ["id"], ["programId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(RoutineEntity::class, ["id"], ["routineId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("routineId")],
)
data class ProgramDayEntity(
    val programId: Long,
    val weekday: Int,
    val routineId: Long?,
)

data class ProgramWithDays(
    @Embedded val program: ProgramEntity,
    @Relation(parentColumns = ["id"], entityColumns = ["programId"]) val days: List<ProgramDayEntity>,
)

// One reading per day; [day] is the ISO date.
@Entity(tableName = "bodyweight")
data class BodyWeightEntity(
    @PrimaryKey val day: String,
    val weight: Double,
    val unit: String,
)

const val PROGRAM_KIND = "program"
