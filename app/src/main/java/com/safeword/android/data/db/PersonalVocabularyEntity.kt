package com.safeword.android.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

/**
 * Entry origin stored in the `source` column.
 *
 * The backing [dbValue] string is the value persisted in SQLite and must remain stable
 * across app versions — do not change existing values.
 */
enum class VocabularySource(val dbValue: String) {
    /** Added explicitly by the user through the Settings UI. */
    MANUAL("manual"),

    /** Inferred from repeated user corrections; becomes active once confirmed ≥ 3 times. */
    AUTO_LEARNED("auto_learned"),
}

/** Room [TypeConverter] for [VocabularySource] ↔ [String]. */
class VocabularySourceConverter {
    @TypeConverter
    fun toSource(value: String): VocabularySource =
        VocabularySource.entries.firstOrNull { it.dbValue == value } ?: VocabularySource.MANUAL

    @TypeConverter
    fun fromSource(source: VocabularySource): String = source.dbValue
}

/**
 * Room entity for user-managed personal vocabulary.
 *
 * Sources:
 * - `manual` — user added via settings UI
 * - `auto_learned` — observed from user corrections (confirmed at [CONFIRMATION_THRESHOLD])
 */
@Entity(
    tableName = "personal_vocabulary",
    indices = [
        Index(value = ["phrase"], unique = true),
        Index(value = ["is_dormant", "source", "confirmation_count", "last_used_at"]),
    ],
)
data class PersonalVocabularyEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Match pattern — the word or phrase as the ASR engine typically outputs it (spoken form).
     * For entries learned from user corrections this is the raw ASR text (e.g. "cube net ease").
     * For manual entries it is simply the desired word (then [writtenForm] is null).
     */
    val phrase: String,

    /** Entry origin. */
    val source: VocabularySource = VocabularySource.MANUAL,

    /**
     * How many times the user has corrected ASR output to this phrase.
     * Auto-learned entries become active at [CONFIRMATION_THRESHOLD].
     * Manual and contacts entries are always active regardless of this value.
     */
    @ColumnInfo(name = "confirmation_count")
    val confirmationCount: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long = System.currentTimeMillis(),

    /**
     * Optional free-text hint about the context in which this phrase was learned
     * (e.g. the input field hint or app name). When non-null, corrections using this
     * entry are only applied in fields whose hint text contains this value.
     */
    @ColumnInfo(name = "context_hint")
    val contextHint: String? = null,

    /**
     * Package name of the app where this entry was most frequently observed.
     * When non-null, corrections are only applied in the matching app.
     * Null means "apply in any app".
     */
    @ColumnInfo(name = "app_package")
    val appPackage: String? = null,

    /**
     * True when the entry has been marked dormant by [com.safeword.android.transcription.DormancyWorker].
     * Dormant entries are kept in the database but excluded from active correction.
     */
    @ColumnInfo(name = "is_dormant")
    val isDormant: Boolean = false,

    /**
     * The written form to substitute when [phrase] matches in ASR output.
     * When null, [phrase] is used as both the match pattern and the replacement.
     *
     * Example: phrase = "cube net ease", writtenForm = "Kubernetes"
     */
    @ColumnInfo(name = "written_form")
    val writtenForm: String? = null,
) {
    companion object {
        const val CONFIRMATION_THRESHOLD = 3
    }

    /**
     * Score in [0.0, 1.0] reflecting how recently and consistently this entry has been used.
     *
     * Combines a recency component (exponential decay, half-life ≈ 21 days) and a
     * confirmation component (linear ramp up to [CONFIRMATION_THRESHOLD]).
     * Used by [com.safeword.android.transcription.DormancyWorker] to decide dormancy.
     */
    fun activityScore(now: Long = System.currentTimeMillis()): Double {
        val daysSinceUsed = (now - lastUsedAt) / 86_400_000.0
        val recency = kotlin.math.exp(-daysSinceUsed / 30.0)
        val confirmation = minOf(1.0, confirmationCount.toDouble() / CONFIRMATION_THRESHOLD)
        return recency * 0.6 + confirmation * 0.4
    }
}
