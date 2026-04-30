package com.safeword.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database — transcription history + personalized dictionary.
 *
 * Version history:
 *   1 — initial schema (transcriptions table)
 *   2 — added personalized_dictionary table
 */
@Database(
    entities = [
        TranscriptionEntity::class,
        PersonalizedEntryEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class SafeWordDatabase : RoomDatabase() {
    abstract fun transcriptionDao(): TranscriptionDao
    abstract fun personalizedEntryDao(): PersonalizedEntryDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `personalized_dictionary` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `fromPhrase` TEXT NOT NULL,
                        `toPhrase` TEXT NOT NULL,
                        `useCount` INTEGER NOT NULL DEFAULT 0,
                        `enabled` INTEGER NOT NULL DEFAULT 1,
                        `createdAt` INTEGER NOT NULL,
                        `lastUsedAt` INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
            }
        }
    }
}
