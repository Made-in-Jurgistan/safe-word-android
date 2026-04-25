package com.safeword.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Audit trail for microphone access — supports a user-facing Privacy Dashboard.
 *
 * Every mic recording session creates one row with [startedAt] on open and
 * [stoppedAt] + [durationMs] filled in on close.
 */
@Entity(tableName = "mic_access_events")
data class MicAccessEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** Purpose of the mic access (e.g. "dictation", "voice_command", "transcription"). */
    val purpose: String,

    /** Epoch millis when the mic was opened. */
    @ColumnInfo(name = "started_at") val startedAt: Long,

    /** Epoch millis when the mic was closed, or null if still open. */
    @ColumnInfo(name = "stopped_at") val stoppedAt: Long? = null,

    /** Recording duration in millis, filled on stop. */
    @ColumnInfo(name = "duration_ms") val durationMs: Long? = null,
) {
    companion object {
        const val PURPOSE_TRANSCRIPTION = "transcription"
    }
}
