package com.safeword.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for transcription history.
 * Mirrors HistoryManager queries in desktop Safe Word's rusqlite layer.
 */
@Dao
interface TranscriptionDao {
    @Query("SELECT * FROM transcriptions ORDER BY createdAt DESC")
    fun getAllTranscriptions(): Flow<List<TranscriptionEntity>>

    @Query("SELECT * FROM transcriptions ORDER BY createdAt DESC LIMIT :limit")
    fun getRecentTranscriptions(limit: Int = 50): Flow<List<TranscriptionEntity>>

    @Insert
    suspend fun insert(transcription: TranscriptionEntity): Long

    @Query("DELETE FROM transcriptions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM transcriptions")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM transcriptions")
    suspend fun count(): Int
}
