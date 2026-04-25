package com.safeword.android.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.safeword.android.security.KeyStoreHelper
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room database — personal vocabulary and privacy audit storage.
 */
@Database(
    entities = [PersonalVocabularyEntity::class, MicAccessEventEntity::class],
    version = 4,
    exportSchema = true
)
@Singleton
@TypeConverters(VocabularySourceConverter::class)
abstract class SafeWordDatabase : RoomDatabase() {

    abstract fun personalVocabularyDao(): PersonalVocabularyDao
    abstract fun micAccessEventDao(): MicAccessEventDao

    companion object {
        fun getDatabase(context: Context): SafeWordDatabase {
            // Generate a secure passphrase using Android Keystore
            val passphrase = KeyStoreHelper.getDatabasePassphrase(context)

            val passphraseBytes = SQLiteDatabase.getBytes(passphrase.toCharArray())
            val factory = SupportFactory(passphraseBytes)

            return Room.databaseBuilder(
                context.applicationContext,
                SafeWordDatabase::class.java,
                "safeword.db"
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : RoomDatabase.Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        // Tighten WAL auto-checkpoint to reduce .wal file size
                        // Default is 1000 pages, we reduce to 100 for mobile devices
                        db.execSQL("PRAGMA wal_autocheckpoint=100")
                    }
                })
                .build()
        }

        /** v1 → v2: add written_form column, create mic_access_events table, add unique index on phrase. */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE personal_vocabulary ADD COLUMN written_form TEXT")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS mic_access_events " +
                        "(`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`purpose` TEXT NOT NULL, " +
                        "`started_at` INTEGER NOT NULL, " +
                        "`stopped_at` INTEGER, " +
                        "`duration_ms` INTEGER)",
                )
                // De-duplicate any rows that would violate the new unique index before creating it.
                // The v1 schema permitted duplicate phrases; without this, CREATE UNIQUE INDEX would
                // fail and the migration would abort on devices that had learned the same phrase twice.
                // Keeps the row with the highest confirmation_count (ties broken by lowest id).
                db.execSQL(
                    """
                    DELETE FROM personal_vocabulary
                    WHERE id NOT IN (
                        SELECT id FROM (
                            SELECT id,
                                   ROW_NUMBER() OVER (
                                       PARTITION BY phrase
                                       ORDER BY confirmation_count DESC, id ASC
                                   ) AS rn
                            FROM personal_vocabulary
                        )
                        WHERE rn = 1
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_personal_vocabulary_phrase " +
                        "ON personal_vocabulary (phrase)",
                )
            }
        }

        /** v2 → v3: drop the transcriptions table (data moved out of Room). */
        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS transcriptions")
            }
        }

        /** v3 → v4: add composite index for vocabulary correction queries. */
        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "index_personal_vocabulary_is_dormant_source_confirmation_count_last_used_at " +
                        "ON personal_vocabulary (is_dormant, source, confirmation_count, last_used_at)",
                )
            }
        }
    }
}
