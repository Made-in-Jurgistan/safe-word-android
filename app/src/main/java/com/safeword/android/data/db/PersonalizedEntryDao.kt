package com.safeword.android.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PersonalizedEntryDao {

    /** Observe all entries ordered by use frequency then recency. */
    @Query("SELECT * FROM personalized_dictionary ORDER BY useCount DESC, createdAt DESC")
    fun observeAll(): Flow<List<PersonalizedEntryEntity>>

    /** Observe only enabled entries — used by the correction pipeline. */
    @Query("SELECT * FROM personalized_dictionary WHERE enabled = 1 ORDER BY useCount DESC")
    fun observeEnabled(): Flow<List<PersonalizedEntryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: PersonalizedEntryEntity): Long

    @Update
    suspend fun update(entry: PersonalizedEntryEntity)

    @Delete
    suspend fun delete(entry: PersonalizedEntryEntity)

    @Query("DELETE FROM personalized_dictionary WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Increment use count and update last-used timestamp atomically. */
    @Query(
        "UPDATE personalized_dictionary SET useCount = useCount + 1, lastUsedAt = :now WHERE id = :id"
    )
    suspend fun recordUse(id: Long, now: Long = System.currentTimeMillis())
}
