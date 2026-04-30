package com.safeword.android.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity for the self-learning personalized dictionary.
 *
 * Each row maps a phrase heard by ASR ([fromPhrase]) to the user's preferred
 * replacement ([toPhrase]). The corrector applies these substitutions as the
 * final post-processing phase so user preferences always win.
 *
 * [useCount] is incremented each time the correction fires, enabling future
 * frequency-ranked display and pruning of stale entries.
 */
@Entity(tableName = "personalized_dictionary")
data class PersonalizedEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** The phrase ASR tends to output (matched case-insensitively at word boundaries). */
    val fromPhrase: String,

    /** The phrase the user wants to see instead. */
    val toPhrase: String,

    /** How many times this substitution has been applied. */
    val useCount: Int = 0,

    /** Whether this entry is active. Disabled entries are stored but not applied. */
    val enabled: Boolean = true,

    /** Unix timestamp (ms) when this entry was created. */
    val createdAt: Long = System.currentTimeMillis(),

    /** Unix timestamp (ms) of last successful application; 0 if never applied. */
    val lastUsedAt: Long = 0L,
)
