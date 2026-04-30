package com.safeword.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for transcription history.
 * Mirrors rusqlite table in desktop Safe Word.
 */
@Entity(tableName = "transcriptions")
data class TranscriptionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The transcribed text. */
    val text: String,

    /** Audio duration in milliseconds. */
    val audioDurationMs: Long,

    /** Inference time in milliseconds. */
    val inferenceDurationMs: Long,

    /** Detected language code. */
    val language: String = "en",

    /** Unix timestamp of creation. */
    val createdAt: Long = System.currentTimeMillis(),
)
