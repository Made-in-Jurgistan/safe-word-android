package com.safeword.android.transcription

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Pre-compiled vocabulary regex and Aho-Corasick cache.
 *
 * Eliminates per-call Regex recompilation in [ConfusionSetCorrector.applyPersonalVocabulary].
 * For vocabulary sets > [AHO_CORASICK_THRESHOLD] entries the cache also maintains a prebuilt
 * [AhoCorasickMatcher] for a single-pass O(M + K) multi-pattern search.
 *
 * Thread-safe: [ConcurrentHashMap] for regex entries; [AhoCorasickMatcher] rebuilt under
 * double-checked lock and stored in a @Volatile field.
 */
@Singleton
class VocabularyPatternCache @Inject constructor() {

    companion object {
        /** Vocab size above which Aho-Corasick is used instead of per-pattern regex. */
        const val AHO_CORASICK_THRESHOLD = 20

        /**
         * Failsafe upper bound on the regex cache size.
         * In normal operation the cache is bounded by the DAO LIMIT 500 cap, but this
         * guards against any future change that bypasses that limit.
         */
        private const val MAX_CACHE_SIZE = 1_000
    }

    private val cache = ConcurrentHashMap<String, Regex>()

    /** The Aho-Corasick automaton for the current vocabulary, rebuilt on invalidation. */
    @Volatile
    private var ahoCorasickMatcher: AhoCorasickMatcher? = null

    /** Version counter incremented on every invalidation — enables O(1) staleness check. */
    @Volatile
    private var cacheVersion: Long = 0

    /** Version of the vocabulary set the current automaton was built for. */
    @Volatile
    private var ahoCorasickVersion: Long = -1

    private val acLock = Any()

    // ── Regex cache ──────────────────────────────────────────────────

    /** Return a cached, compiled whole-word case-insensitive [Regex] for [phrase]. */
    fun patternFor(phrase: String): Regex {
        // Failsafe eviction: clear the cache if it exceeds MAX_CACHE_SIZE and the phrase is new.
        // ConcurrentHashMap.clear() is thread-safe; invalidate() will repopulate on next use.
        if (cache.size >= MAX_CACHE_SIZE && !cache.containsKey(phrase)) {
            cache.clear()
            Timber.w("[WARN] VocabularyPatternCache.patternFor | cache overflow cleared size=%d", MAX_CACHE_SIZE)
        }
        return cache.getOrPut(phrase) {
            Regex("\\b${Regex.escape(phrase)}\\b", RegexOption.IGNORE_CASE)
        }
    }

    /**
     * Remove cached patterns whose phrases are no longer in [currentPhrases].
     * Also invalidates the Aho-Corasick automaton so it is rebuilt on the next access.
     */
    fun invalidate(currentPhrases: Set<String>) {
        val stale = cache.keys - currentPhrases
        stale.forEach { cache.remove(it) }
        // Hold acLock so the null write and version bump are atomic with respect
        // to getAhoCorasick() readers — eliminates the DCL window.
        synchronized(acLock) {
            ahoCorasickMatcher = null
            cacheVersion++
        }
        if (stale.isNotEmpty()) {
            Timber.d("[STATE] VocabularyPatternCache.invalidate | removed=%d remaining=%d", stale.size, cache.size)
        }
    }

    /**
     * Pre-warm the cache with a set of phrases (hot-tier preload).
     *
     * Compiles all regexes eagerly so the first dictation session has zero
     * compilation overhead.
     */
    fun prewarm(phrases: List<String>) {
        for (phrase in phrases) {
            if (phrase.isNotBlank()) patternFor(phrase)
        }
        Timber.d("[INIT] VocabularyPatternCache.prewarm | warmed=%d", phrases.size)
    }

    // ── Aho-Corasick cache ───────────────────────────────────────────

    /**
     * Return the [AhoCorasickMatcher] for [phrases], rebuilding it if stale.
     *
     * Only call when `phrases.size > AHO_CORASICK_THRESHOLD`.
     */
    fun getAhoCorasick(phrases: List<String>): AhoCorasickMatcher {
        // Fast path: automaton already built for this version.
        if (ahoCorasickVersion == cacheVersion) {
            ahoCorasickMatcher?.let { return it }
        }
        // Slow path: rebuild under lock. Stamp with live cacheVersion (not a pre-lock
        // snapshot) so a concurrent invalidate() bumping the version is never missed.
        synchronized(acLock) {
            val current = ahoCorasickMatcher
            if (ahoCorasickVersion != cacheVersion || current == null) {
                val matcher = AhoCorasickMatcher(phrases)
                ahoCorasickMatcher = matcher
                ahoCorasickVersion = cacheVersion
                Timber.d("[INIT] VocabularyPatternCache.getAhoCorasick | rebuilt for %d patterns", phrases.size)
                return matcher
            }
            return current
        }
    }
}
