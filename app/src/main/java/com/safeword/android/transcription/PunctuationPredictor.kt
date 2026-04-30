package com.safeword.android.transcription

import timber.log.Timber

/**
 * Rule-based punctuation prediction for ASR output.
 *
 * **Gated by [DefaultTextProcessor.containsModelPunctuation]**: this predictor
 * is only invoked when Moonshine v2's output contains *no* internal punctuation,
 * meaning the model produced a raw, unpunctuated run. When the model already
 * emitted commas, periods, or question marks, this phase is skipped entirely to
 * avoid double-punctuation conflicts.
 *
 * Heuristics applied when active:
 *  - **Sentence boundaries**: Detects clause-ending patterns (conjunctions after
 *    independent clauses, discourse markers) and inserts periods or commas.
 *  - **Question detection**: Identifies interrogative patterns (WH-words at the
 *    start, tag questions, rising intonation markers) and converts trailing `.` → `?`.
 *  - **Comma insertion**: Adds commas after introductory phrases, before
 *    coordinating conjunctions in compound sentences, and around parenthetical
 *    expressions.
 *
 * This runs after [ContentNormalizer] (which handles spoken punctuation like
 * "period" → ".") and before [TextFormatter] (which handles capitalization).
 */
object PunctuationPredictor {

    // -- Question detection ----------------------------------------------------

    private val QUESTION_START = Regex(
        "^(who|what|where|when|why|how|which|whom|whose|is|are|was|were|do|does|did|can|could|would|should|will|shall|have|has|had|isn't|aren't|wasn't|weren't|don't|doesn't|didn't)\\b",
        RegexOption.IGNORE_CASE,
    )

    private val TAG_QUESTION = Regex(
        "\\b(right|correct|isn't it|aren't they|doesn't it|don't they|won't they|can't we|isn't that|don't you think|wouldn't you say|yeah|no|huh)\\s*[.!]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val OR_QUESTION = Regex(
        "\\b(or not|or what|or else|or is it|or should I)\\s*[.!]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    // -- Comma insertion patterns ---------------------------------------------

    /** Introductory words/phrases that should be followed by a comma. */
    private val INTRODUCTORY_PHRASES = Regex(
        "^(however|therefore|furthermore|moreover|meanwhile|nevertheless|consequently|additionally|unfortunately|fortunately|honestly|basically|actually|anyway|well|so|also|finally|first|second|third|next|then|otherwise|instead|similarly|likewise|regardless|apparently|obviously|clearly|certainly|indeed|besides|personally|naturally|generally|typically|usually|specifically|essentially|overall)\\s",
        RegexOption.IGNORE_CASE,
    )

    /** Coordinating conjunctions in compound sentences (before "but", "yet", "so" with independent clause). */
    private val COMPOUND_CONJUNCTION = Regex(
        "(?<=[a-z])\\s+(but|yet|so|however|although|though|whereas|while)\\s+(?=[a-z])",
        RegexOption.IGNORE_CASE,
    )

    /** Direct address / vocatives at sentence start or end. */
    private val VOCATIVE_END = Regex(
        ",?\\s+(please|thanks|thank you|sir|ma'am|guys|everyone|folks|dude|bro)\\s*[.!?]?\\s*$",
        RegexOption.IGNORE_CASE,
    )

    // -- Sentence boundary heuristics -----------------------------------------

    /** Discourse markers that likely start a new sentence if mid-text without punctuation. */
    private val NEW_SENTENCE_MARKERS = Regex(
        "\\s(anyway|besides|by the way|in fact|for example|for instance|in other words|on the other hand|at the same time|as a result|in addition|in conclusion|to be honest|to be fair|the thing is|the point is|what I mean is)\\s",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Apply punctuation prediction heuristics to normalized text.
     *
     * @param text Normalized text (after [ContentNormalizer], before [TextFormatter]).
     * @return Text with predicted punctuation inserted.
     */
    fun predict(text: String): String {
        if (text.isBlank()) return text

        var result = text.trim()

        // 1. Comma after introductory phrases.
        result = insertIntroductoryComma(result)

        // 2. Comma before coordinating conjunctions in compound sentences.
        result = insertConjunctionComma(result)

        // 3. Sentence boundary detection at discourse markers.
        result = insertSentenceBoundaries(result)

        // 4. Question mark detection — replace trailing period with ? for interrogatives.
        result = detectQuestions(result)

        if (result != text.trim()) {
            Timber.d("[POSTPROCESS] PunctuationPredictor.predict | modified punctuation")
        }

        return result
    }

    private fun insertIntroductoryComma(text: String): String {
        // Process each sentence separately (split on sentence-ending punctuation).
        return text.replace(INTRODUCTORY_PHRASES) { match ->
            val phrase = match.groupValues[1]
            "$phrase, "
        }
    }

    private fun insertConjunctionComma(text: String): String {
        return COMPOUND_CONJUNCTION.replace(text) { match ->
            val conjunction = match.groupValues[1]
            // Insert comma before the conjunction.
            ", $conjunction "
        }
    }

    private fun insertSentenceBoundaries(text: String): String {
        return NEW_SENTENCE_MARKERS.replace(text) { match ->
            val marker = match.value.trim()
            ". ${marker.replaceFirstChar { it.uppercaseChar() }} "
        }
    }

    private fun detectQuestions(text: String): String {
        // Split into sentences by existing punctuation.
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        if (sentences.size <= 1) {
            // Single sentence — check the whole thing.
            return detectQuestionSingle(text)
        }

        return sentences.joinToString(" ") { sentence ->
            detectQuestionSingle(sentence)
        }
    }

    private fun detectQuestionSingle(sentence: String): String {
        val trimmed = sentence.trim()
        if (trimmed.isEmpty()) return sentence

        val isQuestion = QUESTION_START.containsMatchIn(trimmed)
                || TAG_QUESTION.containsMatchIn(trimmed)
                || OR_QUESTION.containsMatchIn(trimmed)

        if (!isQuestion) return sentence

        // Replace trailing period with question mark (if present).
        return if (trimmed.endsWith(".")) {
            trimmed.dropLast(1) + "?"
        } else if (!trimmed.endsWith("?") && !trimmed.endsWith("!")) {
            "$trimmed?"
        } else {
            sentence
        }
    }
}
