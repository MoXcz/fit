// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BodyWeightDao {
    @Query("SELECT * FROM bodyweight ORDER BY day DESC")
    fun all(): Flow<List<BodyWeightEntity>>

    @Query("SELECT * FROM bodyweight WHERE day = :day")
    suspend fun find(day: String): BodyWeightEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(reading: BodyWeightEntity)

    @Query("DELETE FROM bodyweight WHERE day = :day")
    suspend fun delete(day: String)
}
