package com.safeword.android.transcription

import timber.log.Timber

/**
 * Backward-compatible facade that delegates to the three-phase post-processing
 * pipeline: [ContentNormalizer] (Phase 2) → [TextFormatter] (Phase 3).
 *
 * Phase 1 ([VoiceCommandDetector]) is invoked upstream by [TranscriptionCoordinator]
 * before this facade is called, so only text utterances reach here.
 *
 * Callers outside the keyboard pipeline (auto-copy, accessibility insert, DB save)
 * can continue using [process] without changes.
 */
object TextPostProcessor {

    /**
     * Apply all post-processing steps to raw Whisper output.
     *
     * Delegates to:
     *  - [ContentNormalizer.normalize] — emoji, punctuation, fillers, stutters, self-repair, ITN
     *  - [TextFormatter.format] — whitespace, sentence case, pronoun I, trailing punctuation
     */
    fun process(text: String): String {
        if (text.isBlank()) return ""
        Timber.d("[ENTER] TextPostProcessor.process | inputLen=%d", text.length)
        val processStart = System.nanoTime()

        var result = ContentNormalizer.normalize(text)
        result = TextFormatter.format(result)

        val processMs = (System.nanoTime() - processStart) / 1_000_000
        if (result != text.trim()) {
            Timber.i("[PERF] TextPostProcessor.process | lenBefore=%d lenAfter=%d processMs=%d",
                text.length, result.length, processMs)
        } else {
            Timber.d("[EXIT] TextPostProcessor.process | no changes inputLen=%d processMs=%d", text.length, processMs)
        }

        return result
    }
}
