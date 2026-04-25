package com.safeword.android.transcription

import java.util.concurrent.atomic.AtomicReference
import timber.log.Timber

/**
 * Hallucination guard — cross-segment repeat detection plus targeted removal of
 * ASR training-data artifact strings that Moonshine occasionally leaks into output.
 *
 * The regex filter targets only high-confidence artifact patterns (bracket/parenthetical
 * media annotations) that never appear in legitimate dictation. The former broad pattern
 * set was removed because it caused false-positive removals of real content.
 */
internal object HallucinationFilter {

    /** Triple-or-more word/phrase repetition (e.g. "the the the"). */
    internal val TRIPLE_REPEAT_PATTERN = Regex("(?i)\\b(\\w+(?:\\s+\\w+){0,3})(?:\\s+\\1){2,}\\b")

    // Bracket-style media annotations leaked from ASR training data: [Music], [Applause], etc.
    private val BRACKET_ARTIFACT_PATTERN = Regex(
        """\[(?:Music|Applause|Laughter|Noise|inaudible|unintelligible|BLANK_AUDIO|silence|crosstalk|sighing|coughing|clapping)\]""",
        RegexOption.IGNORE_CASE,
    )

    // Parenthetical media annotations: (laughing), (applause), etc.
    private val PAREN_ARTIFACT_PATTERN = Regex(
        """\((?:laughing|laughter|applause|music|crosstalk|indistinct|unintelligible|sighing|coughing)\)""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Normalized text of the last accepted completed segment.
     * Used by [isRepeatOfLastSegment] to detect cross-segment repetition.
     *
     * Thread-safety: [AtomicReference] ensures that writes from [resetSession] (ApplicationScope)
     * are immediately visible to reads in [isRepeatOfLastSegment] (feedDispatcher), eliminating
     * the TOCTOU window that existed with a plain @Volatile var.
     */
    private val lastAcceptedSegment = AtomicReference<String>("")

    /**
     * Check if [text] is an exact repeat of the previous completed segment.
     *
     * Cross-segment repetition is a common Moonshine artefact where the decoder
     * replays the previous segment's content during silence or low-energy audio.
     *
     * @param text The segment text to check
     * @param isComplete Whether this is a completed (final) segment. Only complete
     *        segments update the lastAcceptedSegment state to avoid partial results
     *        corrupting the repeat detection.
     */
    fun isRepeatOfLastSegment(text: String, isComplete: Boolean = false): Boolean {
        val normalized = text.trim().lowercase()
        if (normalized.isEmpty()) return false
        val prev = lastAcceptedSegment.get()
        val isRepeat = normalized == prev
        // Only update state for complete segments — partial segments should not
        // corrupt the cross-segment repeat detection state.
        if (!isRepeat && isComplete) {
            lastAcceptedSegment.set(normalized)
        }
        if (isRepeat) {
            Timber.d("[POSTPROCESS] HallucinationFilter | cross-segment repeat discarded text=\"%s\"", text.trim())
        }
        return isRepeat
    }

    /**
     * Reset cross-segment state between dictation sessions.
     * Call from [TranscriptionCoordinator] when a new recording session begins.
     */
    fun resetSession() {
        lastAcceptedSegment.set("")
    }

    /**
     * Remove ASR training-data artifact strings from [text].
     *
     * Only bracket/parenthetical media annotations are targeted — patterns that
     * Moonshine occasionally leaks from subtitle/caption training corpora and that
     * cannot appear in legitimate voice dictation.
     */
    fun filter(text: String): String {
        if (text.isBlank()) return text
        var result = BRACKET_ARTIFACT_PATTERN.replace(text, "")
        result = PAREN_ARTIFACT_PATTERN.replace(result, "")
        val trimmed = result.trim()
        if (trimmed != text.trim()) {
            Timber.d("[POSTPROCESS] HallucinationFilter | artifact removed original=\"%s\" filtered=\"%s\"", text.trim(), trimmed)
        }
        return trimmed
    }
}
