package com.safeword.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MicAccessEventDao {

    @Insert
    suspend fun insert(event: MicAccessEventEntity): Long

    /** Close out an open event by filling stop timestamp and duration. */
    @Query(
        """
        UPDATE mic_access_events
        SET stopped_at = :stoppedAt,
            duration_ms = :durationMs
        WHERE id = :id
        """,
    )
    suspend fun markStopped(id: Long, stoppedAt: Long, durationMs: Long)

    /** Recent events for the Privacy Dashboard (newest first). */
    @Query("SELECT * FROM mic_access_events ORDER BY started_at DESC LIMIT :limit")
    fun getRecent(limit: Int): Flow<List<MicAccessEventEntity>>

    /** Events since a given timestamp — for GDPR export or dashboard filters. */
    @Query("SELECT * FROM mic_access_events WHERE started_at >= :since ORDER BY started_at DESC LIMIT :limit")
    fun getSince(since: Long, limit: Int = 1000): Flow<List<MicAccessEventEntity>>

    @Query("DELETE FROM mic_access_events WHERE started_at < :before")
    suspend fun deleteOlderThan(before: Long)

    @Query("SELECT COUNT(*) FROM mic_access_events")
    suspend fun count(): Int

    /**
     * Seal any sessions left open by a prior crash (stoppedAt IS NULL).
     * Sets duration_ms to 0 since the actual duration is unknown.
     * Call once at app startup.
     */
    @Query("UPDATE mic_access_events SET stopped_at = :now, duration_ms = 0 WHERE stopped_at IS NULL")
    suspend fun closeOrphanedSessions(now: Long)
}
