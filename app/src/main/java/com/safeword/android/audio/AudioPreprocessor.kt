package com.safeword.android.audio

/**
 * Stateless preprocessing applied to each audio chunk before STT inference.
 *
 * Moonshine's learned conv frontend handles normalization internally, so only
 * DC offset removal is applied — soft clipping and pre-emphasis are not needed.
 */
internal object AudioPreprocessor {

    /**
     * Remove DC offset by subtracting the mean of the window.
     * Operates in-place on [buf] for the first [count] samples.
     */
    fun removeDcOffset(buf: FloatArray, count: Int) {
        var mean = 0.0
        for (i in 0 until count) mean += buf[i]
        mean /= count
        val meanF = mean.toFloat()
        for (i in 0 until count) buf[i] -= meanF
    }
}
