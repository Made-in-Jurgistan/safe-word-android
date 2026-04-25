package com.safeword.android.data

import com.safeword.android.data.db.PersonalVocabularyDao
import com.safeword.android.data.db.PersonalVocabularyEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Repository wrapping [PersonalVocabularyDao].
 *
 * Intentionally a thin delegate: provides a single Hilt-injectable access point for
 * personal vocabulary storage, decoupling consumers from the Room DAO tier. This
 * layer enables future cross-cutting concerns (caching, analytics, migration) without
 * touching every call site.
 */
@Singleton
class PersonalVocabularyRepository @Inject constructor(
    private val dao: PersonalVocabularyDao,
) {
    fun getConfirmedEntries(): Flow<List<PersonalVocabularyEntity>> = dao.getConfirmedEntries()

    suspend fun getTopConfirmedEntries(limit: Int): List<PersonalVocabularyEntity> {
        require(limit > 0) { "Limit must be positive, got: $limit" }
        require(limit <= 1000) { "Limit must not exceed 1000, got: $limit" }
        return dao.getTopConfirmedEntries(limit)
    }

    suspend fun updateLastUsedAt(phrase: String, now: Long = System.currentTimeMillis()) {
        requirePhraseValid(phrase)
        require(now > 0) { "Timestamp must be positive, got: $now" }
        dao.updateLastUsedAt(phrase, now)
    }

    suspend fun exists(phrase: String): Boolean {
        requirePhraseValid(phrase)
        return dao.exists(phrase)
    }

    suspend fun incrementConfirmation(phrase: String): Int {
        requirePhraseValid(phrase)
        return dao.incrementConfirmation(phrase, System.currentTimeMillis())
    }

    suspend fun insert(entry: PersonalVocabularyEntity): Long {
        validateVocabularyEntry(entry)
        return dao.insert(entry)
    }

    suspend fun getAutoLearnedUnconfirmed(): List<PersonalVocabularyEntity> =
        dao.getAutoLearnedUnconfirmed()

    suspend fun getStaleConfirmedEntries(cutoffMillis: Long): List<PersonalVocabularyEntity> {
        require(cutoffMillis > 0) { "Cutoff timestamp must be positive, got: $cutoffMillis" }
        return dao.getStaleConfirmedEntries(cutoffMillis)
    }

    suspend fun setDormant(id: Long) {
        require(id > 0) { "ID must be positive, got: $id" }
        dao.setDormant(id)
    }

    suspend fun decrementConfirmationCount(id: Long) {
        require(id > 0) { "ID must be positive, got: $id" }
        dao.decrementConfirmationCount(id)
    }

    suspend fun decrementConfirmationByPhrase(phrase: String) {
        requirePhraseValid(phrase)
        dao.decrementConfirmationByPhrase(phrase)
    }

    suspend fun resetDormancy(id: Long) {
        require(id > 0) { "ID must be positive, got: $id" }
        dao.resetDormancy(id)
    }

    private fun requirePhraseValid(phrase: String) {
        require(phrase.isNotBlank()) { "Phrase must not be blank" }
        require(phrase.length <= 500) { "Phrase must not exceed 500 characters, got: ${phrase.length}" }
        require(phrase.matches(Regex("^[\\p{L}\\p{M}\\p{N}\\s'\\-]+$"))) {
            "Phrase contains invalid characters: $phrase"
        }
    }

    private fun validateVocabularyEntry(entry: PersonalVocabularyEntity) {
        requirePhraseValid(entry.phrase)
        
        if (entry.writtenForm != null) {
            require(entry.writtenForm.isNotBlank()) { "Written form must not be blank when provided" }
            require(entry.writtenForm.length <= 500) { 
                "Written form must not exceed 500 characters, got: ${entry.writtenForm.length}" 
            }
            require(entry.writtenForm.matches(Regex("^[\\p{L}\\p{M}\\p{N}\\s'\\-]+$"))) {
                "Written form contains invalid characters: ${entry.writtenForm}"
            }
        }

        require(entry.confirmationCount >= 0) { 
            "Confirmation count must be non-negative, got: ${entry.confirmationCount}" 
        }
        require(entry.confirmationCount <= 1000) { 
            "Confirmation count must not exceed 1000, got: ${entry.confirmationCount}" 
        }

        if (entry.appPackage != null) {
            require(entry.appPackage.isNotBlank()) { "App package must not be blank when provided" }
            require(entry.appPackage.matches(Regex("^[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+$"))) {
                "Invalid app package format: ${entry.appPackage}"
            }
        }
    }
}
