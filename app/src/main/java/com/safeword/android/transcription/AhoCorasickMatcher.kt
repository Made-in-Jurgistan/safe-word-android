package com.safeword.android.transcription

/**
 * Aho-Corasick multi-pattern string matching automaton.
 *
 * Builds the goto / failure / output table for a set of phrase patterns at
 * construction time, then finds all occurrences in O(text + matches) during
 * search — independent of pattern count.
 *
 * Patterns are matched **case-insensitively** (lowercased at build time).
 * The [findAll] result preserves the original casing of each matched [Match.pattern]
 * and enforces whole-word boundaries.
 *
 * Intended for use by [VocabularyPatternCache] when the vocabulary exceeds
 * [VocabularyPatternCache.AHO_CORASICK_THRESHOLD] entries.
 */
class AhoCorasickMatcher(patterns: List<String>) {

    data class Match(
        /** Inclusive start index in the searched text. */
        val start: Int,
        /** Exclusive end index in the searched text. */
        val end: Int,
        /** The original-case pattern that was matched. */
        val pattern: String,
    )

    // ── Automaton tables ────────────────────────────────────────────────────

    /** goto[state] = map of char → next state. */
    private val goto = mutableListOf<HashMap<Char, Int>>()

    /** failure[state] = fallback state (BFS-computed suffix links). */
    private val failure = mutableListOf<Int>()

    /** output[state] = original-case patterns that end at this state. */
    private val output = mutableListOf<MutableList<String>>()

    init {
        buildAutomaton(patterns)
    }

    // ── Construction ────────────────────────────────────────────────────────

    private fun buildAutomaton(patterns: List<String>) {
        // Root node (state 0)
        goto.add(HashMap())
        failure.add(0)
        output.add(mutableListOf())

        // Phase 1: build the goto function (trie).
        // Sort longest-first so that suffix-link merges in Phase 2 append only shorter
        // patterns — keeping each output[] list in descending-length order without an
        // explicit per-state sort. findAll() can then rely on this ordering.
        val sorted = patterns.sortedByDescending { it.length }
        for (original in sorted) {
            if (original.isBlank()) continue
            val lower = original.lowercase()
            var cur = 0
            for (ch in lower) {
                cur = goto[cur].getOrPut(ch) {
                    goto.add(HashMap())
                    failure.add(0)
                    output.add(mutableListOf())
                    goto.size - 1
                }
            }
            output[cur].add(original)
        }

        // Phase 2: BFS to compute failure links.
        val queue = ArrayDeque<Int>()

        // All depth-1 nodes fail back to root.
        for ((_, s) in goto[0]) {
            failure[s] = 0
            queue.add(s)
        }

        while (queue.isNotEmpty()) {
            val r = queue.removeFirst()
            for ((ch, s) in goto[r]) {
                queue.add(s)
                // Find the longest proper suffix of (r, ch) that is a prefix of any pattern.
                var state = failure[r]
                while (state != 0 && !goto[state].containsKey(ch)) {
                    state = failure[state]
                }
                val fs = goto[state][ch]?.takeIf { it != s } ?: 0
                failure[s] = fs
                // Dictionary suffix links: merge outputs from failure state.
                output[s].addAll(output[fs])
            }
        }
    }

    // ── Search ──────────────────────────────────────────────────────────────

    /**
     * Return all whole-word-boundary matches in [text], sorted by start position
     * then by descending match length (longest first at each position).
     *
     * Multiple patterns may overlap; the caller applies longest-first replacement.
     */
    fun findAll(text: String): List<Match> {
        if (text.isBlank()) return emptyList()
        val lower = text.lowercase()
        val results = mutableListOf<Match>()
        var state = 0

        for (i in lower.indices) {
            val ch = lower[i]
            while (state != 0 && !goto[state].containsKey(ch)) {
                state = failure[state]
            }
            state = goto[state][ch] ?: 0
            for (pattern in output[state]) {
                val start = i - pattern.length + 1
                if (isWordBoundary(lower, start, i + 1)) {
                    results.add(Match(start = start, end = i + 1, pattern = pattern))
                }
            }
        }

        // output[] lists are in descending-length order (guaranteed by buildAutomaton),
        // so within each start position matches are already longest-first.
        // Only sort by start position here — O(n log n) on match count, not pattern count.
        return results.sortedBy { it.start }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** True if [start]..[end] is delimited by word boundaries in [text]. */
    private fun isWordBoundary(text: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !text[start - 1].isLetterOrDigit()
        val afterOk = end == text.length || !text[end].isLetterOrDigit()
        return beforeOk && afterOk
    }
}
