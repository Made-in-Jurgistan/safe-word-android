package com.safeword.android.data.settings

import com.safeword.android.data.db.PersonalizedEntryDao
import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.data.db.normalizePhrase
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for the self-learning personalized dictionary.
 *
 * Wraps [PersonalizedEntryDao] with logging and convenience operations.
 * The [enabledEntries] flow is the hot path consumed by [DefaultTextProcessor]
 * for real-time correction; [allEntries] is used by the settings UI.
 */
@Singleton
class PersonalizedDictionaryRepository @Inject constructor(
    private val dao: PersonalizedEntryDao,
) {
    /** All entries (enabled + disabled) — for the settings management screen. */
    val allEntries: Flow<List<PersonalizedEntryEntity>> = dao.observeAll()

    /** Only enabled entries — consumed by the text processing pipeline. */
    val enabledEntries: Flow<List<PersonalizedEntryEntity>> = dao.observeEnabled()

    /**
     * Insert or update a personalized substitution. The unique index on the
     * normalized phrase guarantees idempotency — repeat adds with the same
     * `fromPhrase` (case/whitespace insensitive) update the existing row's
     * `toPhrase` rather than creating a duplicate.
     */
    suspend fun add(fromPhrase: String, toPhrase: String) {
        val trimmedFrom = fromPhrase.trim()
        val trimmedTo = toPhrase.trim()
        val normalized = trimmedFrom.normalizePhrase()
        val candidate = PersonalizedEntryEntity(
            fromPhrase = trimmedFrom,
            fromPhraseNormalized = normalized,
            toPhrase = trimmedTo,
        )
        val id = dao.insert(candidate)
        if (id == -1L) {
            // Existing entry — update its toPhrase rather than ignore.
            val existing = dao.findByNormalizedPhrase(normalized)
            if (existing != null && existing.toPhrase != trimmedTo) {
                dao.update(existing.copy(toPhrase = trimmedTo))
                Timber.i("[DICT] add | updated existing entry id=%d fromLen=%d toLen=%d",
                    existing.id, trimmedFrom.length, trimmedTo.length)
            } else {
                Timber.d("[DICT] add | duplicate ignored fromLen=%d", trimmedFrom.length)
            }
            return
        }
        Timber.i("[DICT] add | id=%d fromLen=%d toLen=%d", id, trimmedFrom.length, trimmedTo.length)
    }

    suspend fun delete(entry: PersonalizedEntryEntity) {
        dao.delete(entry)
        Timber.i("[DICT] delete | id=%d fromLen=%d", entry.id, entry.fromPhrase.length)
    }

    suspend fun setEnabled(entry: PersonalizedEntryEntity, enabled: Boolean) {
        dao.update(entry.copy(enabled = enabled))
        Timber.d("[DICT] setEnabled | id=%d enabled=%b", entry.id, enabled)
    }

    /** Called by the text processor when a substitution fires. */
    suspend fun recordUse(id: Long) {
        dao.recordUse(id)
    }
}
