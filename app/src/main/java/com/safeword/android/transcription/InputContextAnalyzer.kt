package com.safeword.android.transcription

import com.safeword.android.service.SafeWordAccessibilityService
import kotlin.math.ln

/**
 * Pure-function helpers for analysing the current accessibility input context.
 *
 * Extracted from [TranscriptionCoordinator] so the field-type derivation and
 * confidence-to-logprob mapping can be unit-tested in isolation and reused
 * by other callers without pulling in the full coordinator state machine.
 */
object InputContextAnalyzer {

    /**
     * Map a [0, 1] confidence to an avgLogprob compatible with
     * [ConfusionSetCorrector]'s LOW_CONF threshold (-0.08). At confidence ≈ 0.92
     * the logprob crosses the threshold. For empty/missing data we return a
     * neutral high value so corrections stay off.
     */
    fun confidenceToLogprob(avgConfidence: Float): Float {
        if (avgConfidence <= 0f) return 0f
        if (avgConfidence >= 1f) return 0f
        return ln(avgConfidence.toDouble()).toFloat()
    }

    /**
     * Build a [ConfusionSetCorrector.Context] from an accessibility snapshot
     * and a confidence sample. When [confidences] is non-empty, avgLogprob is
     * derived from the per-word confidences emitted by the Moonshine SDK
     * (word_timestamps=true). When empty, avgLogprob defaults to 0 so
     * confusion correction stays off.
     */
    fun buildCorrectorContext(
        ctx: SafeWordAccessibilityService.InputContextSnapshot,
        confidences: List<Float> = emptyList(),
    ): ConfusionSetCorrector.Context {
        val avgConfidence = if (confidences.isEmpty()) 1f
        else confidences.average().toFloat().coerceIn(0f, 1f)
        return ConfusionSetCorrector.Context(
            packageName = ctx.packageName,
            hintText = ctx.hintText,
            className = ctx.className,
            avgLogprob = confidenceToLogprob(avgConfidence),
        )
    }

    /**
     * Derive [FieldType] from the current input context using hint / class
     * heuristics and inputType flags. Used to gate voice commands that are
     * inappropriate in certain contexts.
     */
    fun deriveFieldType(ctx: SafeWordAccessibilityService.InputContextSnapshot): FieldType {
        val hint = ctx.hintText.lowercase()
        val cls = ctx.className.lowercase()
        val inputType = ctx.inputType

        // * NOTE: Check inputType flags first (more reliable than hint/class heuristics).
        val isPassword =
            (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) != 0 ||
                (inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) != 0
        if (isPassword || hint.contains("password") || hint.contains("pin") || cls.contains("password")) {
            return FieldType.PASSWORD
        }

        // TYPE_TEXT_VARIATION_URI = 0x00000010, TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS = 0x00000020,
        // TYPE_CLASS_TEXT | URI/WEB variants are reliable indicators that a field expects a URL
        // or search query rather than free-form text.
        val searchFlags = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI or
            android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val isSearch = (inputType and android.text.InputType.TYPE_TEXT_VARIATION_URI) != 0 ||
            inputType == searchFlags
        val hasSearchHint = hint.contains("search") || hint.contains("url") ||
            hint.contains("address")
        val hasSearchClass = cls.contains("url") || cls.contains("search")
        if (isSearch || hasSearchHint || hasSearchClass) {
            return FieldType.SEARCH
        }

        if (hint.contains("message") || hint.contains("chat") || hint.contains("sms") ||
            hint.contains("compose")
        ) {
            return FieldType.MESSAGING
        }

        return FieldType.UNKNOWN
    }
}
