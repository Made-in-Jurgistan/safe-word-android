package com.safeword.android.data.settings

import com.safeword.android.data.db.PersonalizedEntryDao
import com.safeword.android.data.db.PersonalizedEntryEntity
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

    suspend fun add(fromPhrase: String, toPhrase: String) {
        val entry = PersonalizedEntryEntity(
            fromPhrase = fromPhrase.trim(),
            toPhrase = toPhrase.trim(),
        )
        val id = dao.insert(entry)
        Timber.i("[DICT] add | id=%d from=\"%s\" to=\"%s\"", id, fromPhrase, toPhrase)
    }

    suspend fun delete(entry: PersonalizedEntryEntity) {
        dao.delete(entry)
        Timber.i("[DICT] delete | id=%d from=\"%s\"", entry.id, entry.fromPhrase)
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
