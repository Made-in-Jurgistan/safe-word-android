package com.safeword.android.transcription

import timber.log.Timber

/**
 * Steps 3–5 of the ContentNormalizer pipeline — disfluency normalization.
 *
 * Removes filler words, resolves self-repair sequences ("scratch that"),
 * and clears explicit backtrack triggers ("actually no"). Extracted from
 * [ContentNormalizer] to allow isolated testing and updating of trigger lists.
 */
internal object DisfluencyNormalizer {

    private val STRAY_PUNCT_PREFIX = Regex("(^|\\s)[,;:](?=\\s|$)")
    private val STRAY_PUNCT_SUFFIX = Regex("\\s+([,;:])")

    // -- Fillers & stutters ---------------------------------------------------

    private val FILLER_WORDS = linkedSetOf(
        "uh", "um", "uhm", "umm", "uhh", "uhhh",
        "ah", "hmm", "hm", "mmm", "mm", "mh",
        "eh", "ehh", "erm",
    )

    private val FILLER_PATTERN: Regex = buildFillerPattern(FILLER_WORDS)

    // Only targets genuine micro-stutters on very short function words (≤4 chars: "the the",
    // "I I I", "a a"). Longer words like "really really" or "very very" are intentional
    // emphasis in casual speech and must not be collapsed.
    internal val STUTTER_PATTERN = Regex("(?i)\\b([a-zA-Z]{1,4})\\b(?:\\s+\\1\\b)+")

    // -- Self-repair ----------------------------------------------------------

    private val EDITING_TERMS = listOf(
        "oh no actually", "well actually", "scratch that", "forget that",
        "never mind", "or rather", "no wait", "oh wait", "oh no wait",
        "wait no", "i mean",
    )

    private val SELF_REPAIR_PATTERN: Regex = run {
        val alternatives = EDITING_TERMS.joinToString("|") { Regex.escape(it) }
        Regex("(?i)[,;.!?…·\\s]*\\b(?<marker>$alternatives)\\b[,;.!?…·\\s]*")
    }

    // -- Backtrack triggers ---------------------------------------------------

    private val BACKTRACK_TRIGGERS = listOf(
        "actually no", "correction",
    )

    private val BACKTRACK_PATTERN: Regex = run {
        val alts = BACKTRACK_TRIGGERS.joinToString("|") { Regex.escape(it) }
        Regex("(?i)[,;.!?…\\s]*\\b(?:$alts)\\b[,;.!?…\\s]*")
    }

    // -- Public API -----------------------------------------------------------

    /**
     * Apply all disfluency normalization: fillers → repairs → backtracks → stutters.
     */
    fun normalize(text: String): String {
        var result = removeFillers(text)
        result = resolveSelfRepairs(result)
        result = resolveBacktracks(result)
        result = STUTTER_PATTERN.replace(result) { match -> match.groupValues[1] }
        return result.trim()
    }

    // -- Private helpers ------------------------------------------------------

    private fun buildFillerPattern(fillers: Set<String>): Regex {
        val nonBlank = fillers
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .toList()

        if (nonBlank.isEmpty()) return Regex("(?!x)x")

        val alternatives = nonBlank.joinToString("|") { Regex.escape(it) }
        return Regex("(?i)\\b(?:$alternatives)\\b(?:\\s*[,.;:!?…])?\\s*")
    }

    private fun removeFillers(text: String): String {
        var result = FILLER_PATTERN.replace(text, " ")
        result = MULTI_SPACE_REGEX.replace(result, " ")
        result = STRAY_PUNCT_PREFIX.replace(result, "$1")
        result = STRAY_PUNCT_SUFFIX.replace(result, "$1")
        // STRAY_PUNCT_PREFIX consumes a leading whitespace character and re-emits it
        // via $1, which can create a double-space when the comma was surrounded by spaces.
        // Re-run MULTI_SPACE_REGEX to collapse any such artefact before returning.
        result = MULTI_SPACE_REGEX.replace(result, " ")
        return result.trim()
    }

    internal fun resolveSelfRepairs(text: String): String {
        var result = text
        var safetyCounter = 0
        val maxIterations = 10

        while (safetyCounter < maxIterations) {
            safetyCounter++
            val match = SELF_REPAIR_PATTERN.find(result) ?: break

            val markerStart = match.range.first
            val markerEnd = match.range.last + 1

            val before = result.substring(0, markerStart)
            val after = result.substring(markerEnd)

            val reparandumStart = findReparandumStart(before, after)

            if (reparandumStart < 0) {
                result = before.trimEnd() + " " + after.trimStart()
                continue
            }

            val prefix = result.substring(0, reparandumStart)
            val repairedAfter = after.trimStart()
            val stitched = if (prefix.isEmpty()) {
                repairedAfter
            } else {
                val lastPrefixChar = prefix.last()
                if (lastPrefixChar.isLetterOrDigit() && repairedAfter.firstOrNull()?.isLowerCase() == true) {
                    "$prefix $repairedAfter"
                } else {
                    "${prefix.trimEnd()} $repairedAfter"
                }
            }

            Timber.d(
                "[POSTPROCESS] DisfluencyNormalizer | marker=\"%s\" reparandum=\"%s\"",
                match.value.trim(),
                before.substring(reparandumStart).trim(),
            )

            result = stitched
        }

        return result
    }

    internal fun resolveBacktracks(text: String): String {
        var result = text
        var safetyCounter = 0
        val maxIterations = 5
        // searchFrom tracks how far into the string we have already scanned, so that
        // skipped "correction" matches (those mid-sentence without sentence-ending
        // punctuation before them) don't get re-matched on every iteration, which
        // would exhaust maxIterations without ever reaching later triggers like "actually no".
        var searchFrom = 0

        while (safetyCounter < maxIterations) {
            safetyCounter++
            val match = BACKTRACK_PATTERN.find(result, searchFrom) ?: break

            if (match.value.trim().equals("correction", ignoreCase = true)) {
                val before = result.substring(0, match.range.first).trimEnd()
                // "correction" only triggers a backtrack when it follows a sentence-ending
                // mark (i.e. it opens a new sentence, not a mid-sentence word).
                // Advance searchFrom past this match so subsequent iterations look for
                // later triggers (e.g. "actually no") rather than re-finding this one.
                if (before.isNotEmpty() && before.last() !in setOf('.', '!', '?')) {
                    searchFrom = match.range.last + 1
                    continue
                }
            }

            // Successful match — reset searchFrom for the fresh result string.
            searchFrom = 0

            val before = result.substring(0, match.range.first)
            val after = result.substring(match.range.last + 1)

            val clauseSep = before.lastIndexOfAny(charArrayOf('.', '!', '?', ';'))
            val prefix = if (clauseSep >= 0) before.substring(0, clauseSep + 1) else ""

            val stitched = if (prefix.isEmpty()) {
                after.trimStart()
            } else {
                "${prefix.trimEnd()} ${after.trimStart()}"
            }

            Timber.d("[POSTPROCESS] DisfluencyNormalizer | backtrack trigger=\"%s\"", match.value.trim())
            result = stitched
        }

        return result
    }

    private fun findReparandumStart(before: String, after: String): Int {
        if (before.isBlank()) return -1

        val trimmedBefore = before.trimEnd()
        val trimmedAfter = after.trimStart()

        val beforeWords = trimmedBefore.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }
        val afterWords = trimmedAfter.split(WHITESPACE_SPLIT_REGEX).filter { it.isNotEmpty() }

        if (beforeWords.isEmpty() || afterWords.isEmpty()) return -1

        val repairFirstWord = afterWords.first().lowercase().trimEnd(',', '.', '!', '?', ';', '\u2026')

        if (repairFirstWord.isNotEmpty()) {
            val searchWindow = beforeWords.takeLast(8)
            val windowOffset = beforeWords.size - searchWindow.size

            for (i in searchWindow.indices) {
                val candidateWord = searchWindow[i].lowercase().trimEnd(',', '.', '!', '?', ';', '\u2026')
                if (candidateWord == repairFirstWord) {
                    val wordIndex = windowOffset + i
                    return findWordCharIndex(trimmedBefore, wordIndex)
                }
            }
            // Repair word not found in context. Don't fall back to clause separator —
            // this is likely a new thought pattern ("I'm going... I mean pants") rather
            // than a true correction of prior text. Return -1 to skip the repair entirely.
            return -1
        }

        val lastClauseSep = trimmedBefore.lastIndexOfAny(charArrayOf(',', ';'))
        if (lastClauseSep >= 0) return lastClauseSep + 1

        val wordsToRemove = minOf(afterWords.size, beforeWords.size, 5)
        if (wordsToRemove > 0) {
            val reparandumWordIndex = beforeWords.size - wordsToRemove
            return findWordCharIndex(trimmedBefore, reparandumWordIndex)
        }

        return -1
    }

    private fun findWordCharIndex(text: String, wordIndex: Int): Int {
        var count = 0
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++

        while (i < text.length && count < wordIndex) {
            while (i < text.length && !text[i].isWhitespace()) i++
            while (i < text.length && text[i].isWhitespace()) i++
            count++
        }
        return if (count == wordIndex) i else text.length
    }
}
