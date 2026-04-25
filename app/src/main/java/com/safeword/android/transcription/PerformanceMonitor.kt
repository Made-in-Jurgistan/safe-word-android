package com.safeword.android.transcription

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe sliding-window latency tracker for Safe Word STT pipeline stages.
 *
 * Records the last [WINDOW_SIZE] samples per named stage and computes p50/p95/p99
 * percentiles on demand. Designed for diagnostic logging and regression detection
 * against the latency budgets defined in [OptimalParameters].
 *
 * Stages are free-form strings; use the [Stage] constants to avoid typos.
 *
 * Usage:
 * ```kotlin
 * val t0 = System.currentTimeMillis()
 * doWork()
 * performanceMonitor.recordLatency(PerformanceMonitor.Stage.CONFUSION_CORRECTOR,
 *     System.currentTimeMillis() - t0)
 * ```
 *
 * Call [logSummary] at session end to emit p50/p95/p99 for all recorded stages.
 */
@Singleton
class PerformanceMonitor @Inject constructor() {

    /** Named pipeline stages. Extend here if new stages are added. */
    object Stage {
        const val VAD_DETECT = "VAD_DETECT"
        const val MOONSHINE_INFERENCE = "MOONSHINE_INFERENCE"
        const val CONFUSION_CORRECTOR = "CONFUSION_CORRECTOR"
        const val SAFEWORD_TRANSFORMER = "SAFEWORD_TRANSFORMER"
        const val TOTAL_PIPELINE = "TOTAL_PIPELINE"
    }

    /**
     * Snapshot of latency statistics for a pipeline stage.
     *
     * @property stage Stage name (see [Stage]).
     * @property count Number of samples in the window.
     * @property p50Ms Median latency in milliseconds.
     * @property p95Ms 95th-percentile latency in milliseconds.
     * @property p99Ms 99th-percentile latency in milliseconds.
     * @property meanMs Mean latency in milliseconds.
     * @property sessionMaxMs Worst-case latency observed across the entire session.
     */
    data class StageStats(
        val stage: String,
        val count: Int,
        val p50Ms: Long,
        val p95Ms: Long,
        val p99Ms: Long,
        val meanMs: Long,
        val sessionMaxMs: Long,
    )

    companion object {
        private const val WINDOW_SIZE = 500
    }

    // stage name → circular deque of the last WINDOW_SIZE latency samples (ms)
    private val windows = HashMap<String, ArrayDeque<Long>>()
    // Session-wide max per stage — never evicted, captures tail latency spikes
    private val sessionMax = HashMap<String, Long>()
    private val lock = Any()

    /**
     * Record a latency sample for [stage]. Thread-safe.
     *
     * Evicts the oldest sample when the window is full so memory stays bounded.
     */
    fun recordLatency(stage: String, latencyMs: Long) {
        synchronized(lock) {
            val window = windows.getOrPut(stage) { ArrayDeque(WINDOW_SIZE) }
            if (window.size >= WINDOW_SIZE) window.removeFirst()
            window.addLast(latencyMs)
            val prev = sessionMax[stage] ?: Long.MIN_VALUE
            if (latencyMs > prev) sessionMax[stage] = latencyMs
        }
    }

    /**
     * Returns percentile [pct] (0–100) for [stage] using linear interpolation,
     * or 0 if no samples have been recorded.
     *
     * Uses standard linear interpolation between ranks for more accurate percentile
     * estimation than nearest-rank method.
     */
    fun getPercentile(stage: String, pct: Int): Long {
        require(pct in 0..100) { "pct must be 0..100, was $pct" }
        synchronized(lock) {
            val window = windows[stage] ?: return 0L
            if (window.isEmpty()) return 0L
            val sorted = window.toLongArray().also { it.sort() }
            return calculatePercentile(sorted, pct)
        }
    }

    /**
     * Calculate percentile using linear interpolation between ranks.
     * Formula: index = (pct / 100.0) * (n - 1)
     * If index is fractional: value = sorted[floor] + fraction * (sorted[ceil] - sorted[floor])
     */
    private fun calculatePercentile(sorted: LongArray, pct: Int): Long {
        if (sorted.isEmpty()) return 0L
        if (pct <= 0) return sorted.first()
        if (pct >= 100) return sorted.last()

        val n = sorted.size
        val exactIndex = (pct / 100.0) * (n - 1)
        val lowerIndex = exactIndex.toInt()
        val upperIndex = (lowerIndex + 1).coerceAtMost(n - 1)
        val fraction = exactIndex - lowerIndex

        val lowerValue = sorted[lowerIndex]
        val upperValue = sorted[upperIndex]

        return (lowerValue + fraction * (upperValue - lowerValue)).toLong()
    }

    /**
     * Returns p50/p95/p99/mean stats for [stage], or null if no samples recorded.
     * Uses linear interpolation for more accurate percentile calculation.
     */
    fun getStats(stage: String): StageStats? {
        synchronized(lock) {
            val window = windows[stage] ?: return null
            if (window.isEmpty()) return null
            val sorted = window.toLongArray().also { it.sort() }
            val count = sorted.size
            fun pct(p: Int) = calculatePercentile(sorted, p)
            return StageStats(
                stage = stage,
                count = count,
                p50Ms = pct(50),
                p95Ms = pct(95),
                p99Ms = pct(99),
                meanMs = sorted.sum() / count,
                sessionMaxMs = sessionMax[stage] ?: sorted.last(),
            )
        }
    }

    /**
     * Logs p50/p95/p99/mean for every stage that has at least one sample.
     * Call at session end alongside the [TranscriptionCoordinator] metrics log.
     */
    fun logSummary() {
        synchronized(lock) {
            val stageNames = windows.keys.sorted()
            if (stageNames.isEmpty()) {
                Timber.i("[PERF] PerformanceMonitor | no samples recorded this session")
                return
            }
            stageNames.forEach { stage ->
                val s = getStats(stage) ?: return@forEach
                Timber.i(
                    "[PERF] PerformanceMonitor | stage=%s n=%d p50=%dms p95=%dms p99=%dms mean=%dms max=%dms",
                    s.stage, s.count, s.p50Ms, s.p95Ms, s.p99Ms, s.meanMs, s.sessionMaxMs,
                )
            }
        }
    }

    /**
     * Clears samples for [stage] only. Useful between test runs.
     */
    fun reset(stage: String) {
        synchronized(lock) {
            windows[stage]?.clear()
            sessionMax.remove(stage)
        }
    }

    /**
     * Clears all samples across every stage. Call at session start for clean per-session metrics.
     */
    fun resetAll() {
        synchronized(lock) {
            windows.clear()
            sessionMax.clear()
        }
    }
}
