package com.safeword.android.transcription

/**
 * Learns personalized dictionary substitutions from a user edit.
 *
 * This runs only when the user explicitly edits the draft text, treating the
 * pre-edit text as baseline and the post-edit text as the correction.
 */
object DictionaryEditLearner {

    private val WHITESPACE_REGEX = Regex("\\s+")
    private val PUNCT_STRIP_REGEX = Regex("[^\\p{L}\\p{N}\\s']+")

    data class LearnedPair(
        val fromPhrase: String,
        val toPhrase: String,
    )

    fun learnEdits(
        baselineText: String,
        correctedText: String,
    ): List<LearnedPair> {
        val baseTokens = tokenize(baselineText)
        val corrTokens = tokenize(correctedText)
        if (baseTokens.isEmpty() || corrTokens.isEmpty()) return emptyList()
        if (baseTokens == corrTokens) return emptyList()

        val lcs = lcsIndices(baseTokens, corrTokens)
        if (lcs.isEmpty()) {
            val pair = phrasePair(baseTokens, 0, baseTokens.size, corrTokens, 0, corrTokens.size)
            return listOfNotNull(pair)
        }

        val result = ArrayList<LearnedPair>(4)
        var bi = 0
        var ci = 0
        for ((bKeep, cKeep) in lcs) {
            if (bi < bKeep || ci < cKeep) {
                phrasePair(baseTokens, bi, bKeep, corrTokens, ci, cKeep)?.let(result::add)
            }
            bi = bKeep + 1
            ci = cKeep + 1
        }
        if (bi < baseTokens.size || ci < corrTokens.size) {
            phrasePair(baseTokens, bi, baseTokens.size, corrTokens, ci, corrTokens.size)?.let(result::add)
        }

        return mergeAdjacent(result)
            .filter { it.fromPhrase.isNotBlank() && it.toPhrase.isNotBlank() }
            .filterNot { it.fromPhrase.equals(it.toPhrase, ignoreCase = true) }
            .filter { it.fromPhrase.length in 1..80 && it.toPhrase.length in 1..80 }
    }

    private fun tokenize(text: String): List<String> {
        return text
            .trim()
            .split(WHITESPACE_REGEX)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Returns a list of matching token index pairs (baseIndex, correctedIndex) representing the LCS.
     */
    private fun lcsIndices(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].equals(b[j], ignoreCase = true)) {
                    1 + dp[i + 1][j + 1]
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }

        val out = ArrayList<Pair<Int, Int>>(minOf(n, m))
        var i = 0
        var j = 0
        while (i < n && j < m) {
            if (a[i].equals(b[j], ignoreCase = true)) {
                out.add(i to j)
                i++
                j++
            } else if (dp[i + 1][j] >= dp[i][j + 1]) {
                i++
            } else {
                j++
            }
        }
        return out
    }

    private fun phrasePair(
        base: List<String>,
        bStart: Int,
        bEnd: Int,
        corr: List<String>,
        cStart: Int,
        cEnd: Int,
    ): LearnedPair? {
        val from = base.subList(bStart, bEnd).joinToString(" ").trim()
        val to = corr.subList(cStart, cEnd).joinToString(" ").trim()
        if (from.isBlank() || to.isBlank()) return null
        if (looksLikeOnlyPunctuationChange(from, to)) return null
        return LearnedPair(fromPhrase = from, toPhrase = to)
    }

    private fun looksLikeOnlyPunctuationChange(from: String, to: String): Boolean {
        fun stripPunct(s: String): String =
            s.replace(PUNCT_STRIP_REGEX, "").replace(WHITESPACE_REGEX, " ").trim().lowercase()
        return stripPunct(from) == stripPunct(to)
    }

    private fun mergeAdjacent(pairs: List<LearnedPair>): List<LearnedPair> {
        if (pairs.size <= 1) return pairs
        val merged = ArrayList<LearnedPair>(pairs.size)
        var cur = pairs.first()
        for (i in 1 until pairs.size) {
            val next = pairs[i]
            // Merge small adjacent edits to reduce spam entries like ("a"->"the") ("b"->"c")
            val canMerge = (cur.fromPhrase.length + next.fromPhrase.length) <= 80 &&
                (cur.toPhrase.length + next.toPhrase.length) <= 80
            if (canMerge) {
                cur = LearnedPair(
                    fromPhrase = (cur.fromPhrase + " " + next.fromPhrase).trim(),
                    toPhrase = (cur.toPhrase + " " + next.toPhrase).trim(),
                )
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }
}
