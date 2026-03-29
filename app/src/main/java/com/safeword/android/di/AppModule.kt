package com.safeword.android.di

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.safeword.android.data.db.SafeWordDatabase
import com.safeword.android.data.db.TranscriptionDao
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

    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `user_words` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`word` TEXT NOT NULL, " +
                    "`frequency` INTEGER NOT NULL, " +
                    "`lastUsedAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_user_words_word` ON `user_words` (`word`)",
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `ngrams` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`contextWord` TEXT NOT NULL, " +
                    "`nextWord` TEXT NOT NULL, " +
                    "`count` INTEGER NOT NULL, " +
                    "`lastUsedAt` INTEGER NOT NULL)",
            )
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_ngrams_contextWord_nextWord` " +
                    "ON `ngrams` (`contextWord`, `nextWord`)",
            )
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_ngrams_contextWord` ON `ngrams` (`contextWord`)",
            )
        }
    }

    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("DROP TABLE IF EXISTS `user_words`")
            db.execSQL("DROP TABLE IF EXISTS `ngrams`")
        }
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): SafeWordDatabase {
        Timber.d("[INIT] AppModule.provideDatabase | creating Room database")
        return Room.databaseBuilder(
            context,
            SafeWordDatabase::class.java,
            "safeword.db",
        )
        .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
        .build()
    }

    @Provides
    @Singleton
    fun provideTranscriptionDao(database: SafeWordDatabase): TranscriptionDao {
        Timber.d("[INIT] AppModule.provideTranscriptionDao")
        return database.transcriptionDao()
    }
}
