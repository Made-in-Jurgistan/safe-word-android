package com.safeword.android.di

import android.content.Context
import androidx.room.Room
import com.safeword.android.data.db.PersonalizedEntryDao
import com.safeword.android.data.db.SafeWordDatabase
import com.safeword.android.data.db.TranscriptionDao
import com.safeword.android.data.settings.PersonalizedDictionaryRepository
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
        .addMigrations(SafeWordDatabase.MIGRATION_1_2)
        .build()
    }

    @Provides
    @Singleton
    fun provideTranscriptionDao(database: SafeWordDatabase): TranscriptionDao {
        Timber.d("[INIT] AppModule.provideTranscriptionDao")
        return database.transcriptionDao()
    }

    @Provides
    @Singleton
    fun providePersonalizedEntryDao(database: SafeWordDatabase): PersonalizedEntryDao {
        Timber.d("[INIT] AppModule.providePersonalizedEntryDao")
        return database.personalizedEntryDao()
    }

    @Provides
    @Singleton
    fun providePersonalizedDictionaryRepository(
        dao: PersonalizedEntryDao,
    ): PersonalizedDictionaryRepository {
        Timber.d("[INIT] AppModule.providePersonalizedDictionaryRepository")
        return PersonalizedDictionaryRepository(dao)
    }

}
