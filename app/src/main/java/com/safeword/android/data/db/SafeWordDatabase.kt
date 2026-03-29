package com.safeword.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Room database — transcription history storage.
 */
@Database(
    entities = [
        TranscriptionEntity::class,
    ],
    version = 3,
    exportSchema = true,
)
abstract class SafeWordDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
}
