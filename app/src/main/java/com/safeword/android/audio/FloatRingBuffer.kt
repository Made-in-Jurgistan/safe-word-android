package com.safeword.android.audio

import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pre-allocated, non-blocking float buffer for accumulating PCM audio samples.
 *
 * Eliminates GC pressure from boxed `Float` objects in `MutableList<Float>`.
 * At 16 kHz, a 30 s recording produces 480 000 samples — each `add()` on a
 * `MutableList<Float>` boxes one `Float` (16 bytes) ≈ 7.3 MB of garbage.
 * This buffer stores the same data in a contiguous `FloatArray` (1.8 MB)
 * with zero allocations after construction.
 *
 * Write index is `@Volatile` so a single writer + single reader is safe
 * without synchronisation. Multiple writers require external locking.
 */
class FloatRingBuffer(private val capacity: Int) {

    private val data = FloatArray(capacity)

    private val _size = AtomicInteger(0)

    /** Number of samples written. Thread-safe via AtomicInteger. */
    val size: Int get() = _size.get()

    /**
     * Append [count] samples from [src] starting at index 0.
     * If the buffer is full, excess samples are silently dropped.
     */
    fun write(src: FloatArray, count: Int) {
        val currentSize = _size.get()
        val available = capacity - currentSize
        val toCopy = count.coerceAtMost(available)
        if (toCopy <= 0) {
            Timber.w("[WARN] FloatRingBuffer.write | dropped %d samples — buffer full (capacity=%d size=%d)", count, capacity, currentSize)
            return
        }
        if (toCopy < count) {
            Timber.w("[WARN] FloatRingBuffer.write | partial write: copied=%d dropped=%d capacity=%d size=%d", toCopy, count - toCopy, capacity, currentSize)
        }
        System.arraycopy(src, 0, data, currentSize, toCopy)
        _size.addAndGet(toCopy)
    }

    /**
     * Return a trimmed copy of all written samples.
     * The internal buffer is **not** cleared.
     */
    fun toFloatArray(): FloatArray {
        val snapshot = _size.get()
        Timber.d("[DIAGNOSTICS] FloatRingBuffer.toFloatArray | size=%d capacity=%d utilization=%.1f%%",
            snapshot, capacity, if (capacity > 0) snapshot.toFloat() / capacity * 100 else 0f)
        return data.copyOf(snapshot)
    }

    /** Reset the write position to zero. Does not zero the backing array. */
    fun clear() {
        Timber.d("[STATE] FloatRingBuffer.clear | cleared size=%d capacity=%d", _size.get(), capacity)
        _size.set(0)
    }
}
