package com.safeword.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        TranscriptionEntity::class,
        PersonalizedEntryEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class SafeWordDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun personalizedEntryDao(): PersonalizedEntryDao
}
