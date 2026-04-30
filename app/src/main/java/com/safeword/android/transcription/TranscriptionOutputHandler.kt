package com.safeword.android.transcription

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import com.safeword.android.R
import com.safeword.android.data.db.TranscriptionDao
import com.safeword.android.data.db.TranscriptionEntity
import com.safeword.android.service.AccessibilityBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles post-transcription output actions: text insertion via accessibility,
 * clipboard copy, and database persistence.
 *
 * Extracted from [TranscriptionCoordinator] to reduce god-class complexity
 * and improve testability (each action can be verified independently).
 */
@Singleton
class TranscriptionOutputHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transcriptionDao: TranscriptionDao,
    private val a11y: AccessibilityBridge,
) {

    /**
     * Insert text into the currently focused field via the accessibility service.
     * @return `true` if insertion succeeded.
     */
    fun insertText(text: String): Boolean {
        if (!a11y.isActive()) return false
        return a11y.insertText(text)
    }

    /** Copy text to the system clipboard. */
    fun copyToClipboard(text: String) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText(context.getString(R.string.clipboard_label_transcription), text),
            )
            Timber.i("[CLIPBOARD] copyToClipboard | auto-copied %d chars", text.length)
        } catch (e: Exception) {
            Timber.e(e, "[CLIPBOARD] copyToClipboard | failed")
        }
    }

    /** Persist a transcription result to the Room database. */
    suspend fun saveToDatabase(result: TranscriptionResult) {
        try {
            transcriptionDao.insert(
                TranscriptionEntity(
                    text = result.text,
                    audioDurationMs = result.audioDurationMs,
                    inferenceDurationMs = result.inferenceDurationMs,
                    language = result.language,
                    createdAt = result.timestamp,
                ),
            )
            Timber.i("[DB] saveToDatabase | saved transcription lang=%s textLen=%d", result.language, result.text.length)
        } catch (e: Exception) {
            Timber.e(e, "[DB] saveToDatabase | failed")
        }
    }
}
