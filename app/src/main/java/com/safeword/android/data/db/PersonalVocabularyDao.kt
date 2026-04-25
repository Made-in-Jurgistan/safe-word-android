package com.safeword.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the personal vocabulary table.
 *
 * "Confirmed" entries are manual/contacts (always active) or auto-learned entries that
 * have reached the confirmation threshold (≥ 3 corrections by the user).
 */
@Dao
interface PersonalVocabularyDao {

    /** All entries that should influence transcription correction. */
    @Query(
        """
        SELECT * FROM personal_vocabulary
        WHERE (source = 'manual' OR (source = 'auto_learned' AND confirmation_count >= 3))
          AND is_dormant = 0
        ORDER BY last_used_at DESC
        """,
    )
    fun getConfirmedEntries(): Flow<List<PersonalVocabularyEntity>>

    /**
     * Top [limit] confirmed entries by recency — used as the hot vocabulary tier
     * for preloading into the pattern cache before a recording session.
     */
    @Query(
        """
        SELECT * FROM personal_vocabulary
        WHERE (source = 'manual' OR (source = 'auto_learned' AND confirmation_count >= 3))
          AND is_dormant = 0
        ORDER BY last_used_at DESC
        LIMIT :limit
        """,
    )
    suspend fun getTopConfirmedEntries(limit: Int): List<PersonalVocabularyEntity>

    /**
     * All auto-learned entries that have not yet reached [PersonalVocabularyEntity.CONFIRMATION_THRESHOLD].
     * Used by [com.safeword.android.transcription.DormancyWorker] to evaluate dormancy.
     *
     * LIMIT 500: safety cap to prevent unbounded result sets on large vocabularies.
     */
    @Query(
        """
        SELECT * FROM personal_vocabulary
        WHERE source = 'auto_learned' AND confirmation_count < 3
        ORDER BY last_used_at ASC
        LIMIT 500
        """,
    )
    suspend fun getAutoLearnedUnconfirmed(): List<PersonalVocabularyEntity>

    @Query("SELECT * FROM personal_vocabulary ORDER BY last_used_at DESC")
    fun getAll(): Flow<List<PersonalVocabularyEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: PersonalVocabularyEntity): Long

    /**
     * Increment confirmation count and touch [lastUsedAt] for an auto-learned phrase.
     * Returns the number of rows updated (0 if the phrase doesn't exist).
     */
    @Query(
        """
        UPDATE personal_vocabulary
        SET confirmation_count = confirmation_count + 1,
            last_used_at = :now
        WHERE phrase = :phrase COLLATE NOCASE
        """,
    )
    suspend fun incrementConfirmation(phrase: String, now: Long): Int

    /** Mark an entry as dormant so it is excluded from active correction. */
    @Query("UPDATE personal_vocabulary SET is_dormant = 1 WHERE id = :id")
    suspend fun setDormant(id: Long)

    /** Clear the dormant flag so the entry resumes active correction. */
    @Query("UPDATE personal_vocabulary SET is_dormant = 0 WHERE id = :id")
    suspend fun resetDormancy(id: Long)

    /**
     * Decrement confirmation count by 1 (minimum 0) as part of the exponential-decay
     * trust model. Called by [com.safeword.android.transcription.DormancyWorker] when
     * an entry is marked dormant, so repeatedly-unused entries lose trust over time.
     */
    @Query(
        """
        UPDATE personal_vocabulary
        SET confirmation_count = MAX(0, confirmation_count - 1)
        WHERE id = :id
        """,
    )
    suspend fun decrementConfirmationCount(id: Long)

    /**
     * Decrement the confirmation count of an auto-learned entry by its spoken phrase.
     * Used by [CorrectionLearner] when the user rejects a vocabulary substitution.
     * Only targets `source = 'auto_learned'` so manually-added entries are never penalised.
     */
    @Query(
        """
        UPDATE personal_vocabulary
        SET confirmation_count = MAX(0, confirmation_count - 1)
        WHERE phrase = :phrase COLLATE NOCASE
          AND source = 'auto_learned'
        """,
    )
    suspend fun decrementConfirmationByPhrase(phrase: String)

    @Query("DELETE FROM personal_vocabulary WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM personal_vocabulary WHERE source = :source")
    suspend fun deleteBySource(source: VocabularySource)

    @Query("SELECT COUNT(*) FROM personal_vocabulary")
    suspend fun count(): Int

    /** Check if a phrase already exists (case-insensitive). */
    @Query("SELECT COUNT(*) > 0 FROM personal_vocabulary WHERE phrase = :phrase COLLATE NOCASE")
    suspend fun exists(phrase: String): Boolean

    /** Touch [lastUsedAt] when a vocabulary entry fires in the correction pipeline. */
    @Query("UPDATE personal_vocabulary SET last_used_at = :now WHERE phrase = :phrase COLLATE NOCASE")
    suspend fun updateLastUsedAt(phrase: String, now: Long = System.currentTimeMillis())

    /**
     * Auto-learned confirmed entries (≥ threshold) that have not been accessed since
     * [cutoffMillis] and are not already dormant.
     * Used by [com.safeword.android.transcription.DormancyWorker] to find stale entries
     * eligible for dormancy. Manual entries are never auto-dormanted.
     *
     * LIMIT 500: safety cap to prevent unbounded result sets on large vocabularies.
     */
    @Query(
        """
        SELECT * FROM personal_vocabulary
        WHERE source = 'auto_learned'
          AND confirmation_count >= 3
          AND is_dormant = 0
          AND last_used_at < :cutoffMillis
        ORDER BY last_used_at ASC
        LIMIT 500
        """,
    )
    suspend fun getStaleConfirmedEntries(cutoffMillis: Long): List<PersonalVocabularyEntity>
}
