package com.safeword.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatRingBufferTest {

    @Test
    fun `write and read back samples`() {
        val buffer = FloatRingBuffer(100)
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)
        buffer.write(samples, samples.size)
        assertEquals(3, buffer.size)
        assertArrayEquals(samples, buffer.toFloatArray(), 0.0001f)
    }

    @Test
    fun `write fills up to capacity then drops`() {
        val buffer = FloatRingBuffer(5)
        val samples = floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f)
        buffer.write(samples, samples.size)
        assertEquals(5, buffer.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), buffer.toFloatArray(), 0.0001f)
    }

    @Test
    fun `clear resets size to zero`() {
        val buffer = FloatRingBuffer(10)
        buffer.write(floatArrayOf(1f, 2f, 3f), 3)
        assertEquals(3, buffer.size)
        buffer.clear()
        assertEquals(0, buffer.size)
        assertArrayEquals(floatArrayOf(), buffer.toFloatArray(), 0.0001f)
    }

    @Test
    fun `multiple writes accumulate correctly`() {
        val buffer = FloatRingBuffer(100)
        buffer.write(floatArrayOf(1f, 2f), 2)
        buffer.write(floatArrayOf(3f, 4f, 5f), 3)
        assertEquals(5, buffer.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), buffer.toFloatArray(), 0.0001f)
    }

    @Test
    fun `toFloatArray returns defensive copy`() {
        val buffer = FloatRingBuffer(10)
        buffer.write(floatArrayOf(1f, 2f), 2)
        val copy = buffer.toFloatArray()
        copy[0] = 99f
        assertEquals(1f, buffer.toFloatArray()[0], 0.0001f)
    }

    @Test
    fun `write with zero count is no-op`() {
        val buffer = FloatRingBuffer(10)
        buffer.write(floatArrayOf(1f, 2f), 0)
        assertEquals(0, buffer.size)
    }

    @Test
    fun `partial write when near capacity`() {
        val buffer = FloatRingBuffer(5)
        buffer.write(floatArrayOf(1f, 2f, 3f), 3)
        buffer.write(floatArrayOf(4f, 5f, 6f, 7f), 4)
        assertEquals(5, buffer.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f), buffer.toFloatArray(), 0.0001f)
    }

    @Test
    fun `empty buffer returns empty array`() {
        val buffer = FloatRingBuffer(100)
        assertEquals(0, buffer.size)
        assertArrayEquals(floatArrayOf(), buffer.toFloatArray(), 0.0001f)
    }
}
