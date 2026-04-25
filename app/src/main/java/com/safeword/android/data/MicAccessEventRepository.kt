package com.safeword.android.data

import com.safeword.android.data.db.MicAccessEventDao
import com.safeword.android.data.db.MicAccessEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository wrapping [MicAccessEventDao].
 *
 * Keeps the DAO out of [com.safeword.android.transcription.TranscriptionCoordinator]'s
 * constructor so the coordinator depends on a domain type instead of the raw DAO.
 */
@Singleton
class MicAccessEventRepository @Inject constructor(
    private val dao: MicAccessEventDao,
) {
    suspend fun recordStart(purpose: String, startedAt: Long): Long {
        require(purpose.isNotBlank()) { "Purpose must not be blank" }
        require(purpose.length <= 200) { "Purpose must not exceed 200 characters, got: ${purpose.length}" }
        require(startedAt > 0) { "Started timestamp must be positive, got: $startedAt" }
        return dao.insert(MicAccessEventEntity(purpose = purpose, startedAt = startedAt))
    }

    suspend fun recordStop(id: Long, stoppedAt: Long, durationMs: Long) {
        require(id > 0) { "ID must be positive, got: $id" }
        require(stoppedAt > 0) { "Stopped timestamp must be positive, got: $stoppedAt" }
        require(durationMs >= 0) { "Duration must be non-negative, got: $durationMs" }
        require(stoppedAt >= System.currentTimeMillis() - 86400000L) { 
            "Stopped timestamp too far in the past, got: $stoppedAt" 
        }
        dao.markStopped(id, stoppedAt, durationMs)
    }

    /** Seal any sessions that were left open by a prior crash. Call once at app startup. */
    suspend fun closeOrphanedSessions() = dao.closeOrphanedSessions(System.currentTimeMillis())
}
