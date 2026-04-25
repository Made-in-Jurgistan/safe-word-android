package com.safeword.android.di

import android.content.Context
import android.content.pm.PackageManager
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.safeword.android.data.db.MicAccessEventDao
import com.safeword.android.data.db.PersonalVocabularyDao
import com.safeword.android.data.db.SafeWordDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Singleton

/**
 * Hilt module providing singleton dependencies.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafeWordDatabase {
        Timber.d("[INIT] AppModule.provideDatabase | creating Room database")

        return Room.databaseBuilder(
            context,
            SafeWordDatabase::class.java,
            "safeword.db",
        )
        .addMigrations(
            SafeWordDatabase.MIGRATION_1_2,
            SafeWordDatabase.MIGRATION_2_3,
            SafeWordDatabase.MIGRATION_3_4,
        )
        // Destructive fallback handles downgrade scenarios (e.g. installing an older build
        // over a dev build at a higher schema version that was never shipped).
        // This is safe — all Room tables are rebuilt from scratch and re-populated from the
        // network/assets on next use. User personal vocabulary is lost, which is acceptable
        // compared to a crash on every launch.
        .fallbackToDestructiveMigration(dropAllTables = true)
        .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
        .addCallback(object : RoomDatabase.Callback() {
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                // Tighten WAL auto-checkpoint from SQLite's default 1000 pages to 100
                // so the WAL file is truncated aggressively for this small-schema database.
                // Use rawQuery instead of execSQL to avoid context issues in onOpen callback.
                db.query("PRAGMA wal_autocheckpoint=100").close()
            }
        })
        .build()
    }

    @Provides
    @Singleton
    fun providePersonalVocabularyDao(database: SafeWordDatabase): PersonalVocabularyDao =
        database.personalVocabularyDao()

    @Provides
    @Singleton
    fun provideMicAccessEventDao(database: SafeWordDatabase): MicAccessEventDao =
        database.micAccessEventDao()
}
