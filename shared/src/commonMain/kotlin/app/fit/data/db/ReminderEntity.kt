// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

// A recovery session's daily reminder; it goes with its session. [shownOn] is the ISO local day it
// last came up (shown, or left out because the session was done), so a day never gets two.
@Entity(
    tableName = "reminder",
    foreignKeys = [ForeignKey(GuidedPlanEntity::class, ["id"], ["planId"], onDelete = ForeignKey.CASCADE)],
)
data class ReminderEntity(
    @PrimaryKey val planId: Long,
    // Minutes after local midnight.
    val minuteOfDay: Int,
    val enabled: Boolean,
    val shownOn: String?,
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminder ORDER BY planId")
    fun all(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminder ORDER BY planId")
    suspend fun list(): List<ReminderEntity>

    @Query("SELECT * FROM reminder WHERE planId = :planId")
    fun byPlan(planId: Long): Flow<ReminderEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reminder: ReminderEntity)

    @Query("UPDATE reminder SET shownOn = :day WHERE planId = :planId")
    suspend fun setShownOn(
        planId: Long,
        day: String,
    )
}
