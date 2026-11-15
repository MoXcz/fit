// SPDX-License-Identifier: GPL-3.0-or-later
package app.fit.data.db

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

// An image that came with imported content; [path] is what content files name (`images/<name>.webp`).
// In the database rather than a folder, so an import, a restore and a wipe change it in the same
// transaction as the items that use it.
@Entity(tableName = "imported_image")
class ImportedImageEntity(
    @PrimaryKey val path: String,
    val data: ByteArray,
)

@Dao
interface ImageDao {
    @Query("SELECT data FROM imported_image WHERE path = :path")
    suspend fun data(path: String): ByteArray?

    @Query("SELECT path FROM imported_image")
    suspend fun paths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(images: List<ImportedImageEntity>)
}
