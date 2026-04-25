package com.safeword.android.transcription

/**
 * Phase 2 — Content Normalization.
 *
 * Orchestrates the full normalization pipeline by delegating to focused helpers:
 *  - [HallucinationFilter]  — steps 1–2  (invisible chars + ASR artefacts)
 *  - [DisfluencyNormalizer] — steps 3–5  (fillers, self-repairs, backtracks, stutters)
 *  - [SpokenSymbolConverter]— steps 6–7  (spoken emoji + punctuation → symbols)
 *  - [InverseTextNormalizer]— step 10    (number words → digits, ITN)
 *
 * [preProcess] runs steps 1–5 before [ConfusionSetCorrector].
 * [normalizePostPreProcess] runs steps 6–10 after it.
 */
object ContentNormalizer {

    private val INVISIBLE_CHARS = Regex("[\u200B\u200C\u200D\uFEFF\u00AD]")
    private val NEWLINE_SPACE_CLEANUP = Regex("[ \t]*\n[ \t]*")

    // -- Public API -----------------------------------------------------------

    /**
     * Apply all content normalization steps to raw ASR text.
     *
     * @param text Raw transcription (after voice command detection).
     * @return Normalized content ready for Phase 3 formatting.
     */
    internal fun normalize(text: String, isComplete: Boolean = false): String =
        normalizePostPreProcess(preProcess(text, isComplete))

    /**
     * Pre-pass executed *before* [ConfusionSetCorrector] — applies only steps 1–5.
     *
     * Strips retracted speech so the confusion corrector never sees hallucinated,
     * filler, or backtracked text that would otherwise taint its statistical model.
     *
     * @param text Raw transcription output.
     * @param isComplete Whether this is a completed (final) segment. Passed through
     *        to [HallucinationFilter.isRepeatOfLastSegment] to only update state on
     *        complete segments.
     * @return Text with invisible chars, hallucinations, fillers, self-repairs,
     *         and backtracks removed, ready for confusion-set correction.
     */
    internal fun preProcess(text: String, isComplete: Boolean = false): String {
        if (text.isBlank()) return ""
        if (HallucinationFilter.isRepeatOfLastSegment(text, isComplete)) return ""
        var result = INVISIBLE_CHARS.replace(text, "")
        result = HallucinationFilter.filter(result)
        // Two-phase "dot dot dot" handling: convert to "…" here (before DisfluencyNormalizer
        // so the three repeated words aren't collapsed as a stutter), then
        // SpokenSymbolConverter.convert() in normalizePostPreProcess re-runs the same
        // pattern — that second pass is intentionally a no-op since "…" is already present.
        result = SpokenSymbolConverter.protectDotDotDot(result)
        result = DisfluencyNormalizer.normalize(result)
        return result.trim()
    }

    /**
     * Post-pre-process pass — applies only steps 6–10.
     *
     * Call this on text that has already passed through [preProcess] (and
     * optionally [ConfusionSetCorrector]) to avoid re-running steps 1–5.
     *
     * @param text Text that has already been pre-processed (steps 1–5 applied).
     * @return Normalized content ready for Phase 3 formatting.
     */
    internal fun normalizePostPreProcess(text: String): String {
        if (text.isBlank()) return ""
        var result = SpokenSymbolConverter.convert(text)
        // 9. Triple-repeat deduplication (internal val from HallucinationFilter)
        result = HallucinationFilter.TRIPLE_REPEAT_PATTERN.replace(result) { it.groupValues[1] }
        // 10. Number word -> digit / ITN
        result = InverseTextNormalizer.normalize(result)
        result = MULTI_SPACE_REGEX.replace(result, " ") // defined in StringDistance.kt
        result = NEWLINE_SPACE_CLEANUP.replace(result, "\n")
        return result.trim()
    }

}
