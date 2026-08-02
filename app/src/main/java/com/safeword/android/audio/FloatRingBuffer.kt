// =============================================================================
// File: FloatRingBuffer.kt
// Purpose: Append-only PCM sample accumulator used by AudioRecorder to keep an
//          entire recording in memory without the GC cost of MutableList<Float>.
// Responsibilities:
//   - Provide O(1) append for FloatArray slices.
//   - Expose snapshot() / size that are consistent under concurrent reads from
//     a coroutine on Dispatchers.IO and the recording loop.
//   - Drop excess samples once capacity is reached, with a logged warning.
// Dependencies: timber.log.Timber
// License: matches Safe Word Android repository.
// =============================================================================
package com.safeword.android.audio

import timber.log.Timber

/**
 * Linear append buffer for float PCM samples.
 *
 * All public methods are guarded by a monitor on `this` so a writer on the
 * audio capture thread and a reader fetching the final transcription buffer
 * see a consistent state.
 */
class FloatRingBuffer(private val capacity: Int) {

    private val data = FloatArray(capacity)

    @Volatile private var written: Int = 0

    /** Number of samples currently in the buffer. */
    val size: Int @Synchronized get() = written

    /**
     * Append [count] samples from [src] starting at index 0.
     * If the buffer is full, excess samples are silently dropped.
     */
    @Synchronized
    fun write(src: FloatArray, count: Int) {
        require(count >= 0) { "count must be >= 0, was $count" }
        require(count <= src.size) { "count $count > src.size ${src.size}" }
        val available = capacity - written
        if (available <= 0) {
            Timber.w("[WARN] FloatRingBuffer.write | dropped %d samples — buffer full (capacity=%d)", count, capacity)
            return
        }
        val toCopy = if (count <= available) count else available
        if (toCopy < count) {
            Timber.w(
                "[WARN] FloatRingBuffer.write | partial write: copied=%d dropped=%d capacity=%d",
                toCopy, count - toCopy, capacity,
            )
        }
        System.arraycopy(src, 0, data, written, toCopy)
        written += toCopy
    }

    /**
     * Return a trimmed copy of all written samples. Internal buffer is not cleared.
     */
    @Synchronized
    fun toFloatArray(): FloatArray {
        Timber.d(
            "[DIAGNOSTICS] FloatRingBuffer.toFloatArray | size=%d capacity=%d utilization=%.1f%%",
            written, capacity, if (capacity > 0) written.toFloat() / capacity * 100f else 0f,
        )
        return data.copyOf(written)
    }

    /** Reset the write position to zero. Does not zero the backing array. */
    @Synchronized
    fun clear() {
        Timber.d("[STATE] FloatRingBuffer.clear | cleared size=%d capacity=%d", written, capacity)
        written = 0
    }
}
