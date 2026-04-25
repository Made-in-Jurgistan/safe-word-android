package com.safeword.android.audio

import android.os.SystemClock
import kotlinx.coroutines.flow.Flow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects sustained silence after confirmed speech and invokes a callback to auto-stop recording.
 *
 * Algorithm:
 * 1. Wait for speech confirmation: [minSpeechMs] ms of consecutive above-threshold probability.
 * 2. Once speech is confirmed, start counting consecutive below-threshold frames.
 * 3. After [hysteresisFrames] consecutive silence frames, start the silence timer.
 * 4. If silence persists for [silenceTimeoutMs] ms, invoke [onAutoStop].
 *
 * Injected as a singleton so [TranscriptionCoordinator] avoids a direct dependency on
 * [AdaptiveVadSensitivity].
 */
@Singleton
class SilenceAutoStopDetector @Inject constructor(
    private val adaptiveVadSensitivity: AdaptiveVadSensitivity,
) {
    companion object {
        /** Minimum duration of confirmed speech before the silence timer can activate (ms). */
        private const val MIN_SPEECH_MS = 250L

        /**
         * Consecutive below-threshold frames required before starting the silence timer.
         * Prevents brief noise spikes from resetting a running silence countdown (~90 ms at 30 ms/frame).
         */
        private const val HYSTERESIS_FRAMES = 3

        /**
         * Consecutive below-threshold frames required to cancel an in-progress onset window.
         * A single noisy frame won't discard [MIN_SPEECH_MS] of accumulated onset time — at
         * least 2 × 30 ms = 60 ms of sustained below-threshold audio is needed (~2 frames).
         */
        private const val ONSET_RESET_FRAMES = 2
    }

    /**
     * Collect [speechProbability] until either [isRecordingActive] returns false or
     * [silenceTimeoutMs] of sustained post-speech silence elapses.
     *
     * Suspends until the flow completes (i.e. [speechProbability] is cancelled).
     *
     * @param speechProbability Flow of per-chunk speech probability values (0.0–1.0).
     * @param silenceTimeoutMs Duration of sustained silence required to trigger auto-stop.
     * @param isRecordingActive Returns true while recording is still active.
     * @param onAutoStop Called when silence auto-stop is triggered.
     */
    suspend fun collectUntilAutoStop(
        speechProbability: Flow<Float>,
        silenceTimeoutMs: Long,
        isRecordingActive: () -> Boolean,
        onAutoStop: suspend () -> Unit,
    ) {
        var speechConfirmed = false
        var speechStartElapsed = 0L
        var silenceStartElapsed = 0L
        var consecutiveSilenceFrames = 0
        var onsetSilenceFrames = 0

        speechProbability.collect { prob ->
            if (!isRecordingActive()) return@collect
            val effectiveVadThreshold = adaptiveVadSensitivity.speechThreshold.value
            if (prob >= effectiveVadThreshold) {
                consecutiveSilenceFrames = 0
                onsetSilenceFrames = 0
                silenceStartElapsed = 0L
                if (!speechConfirmed) {
                    val now = SystemClock.elapsedRealtime()
                    if (speechStartElapsed == 0L) {
                        speechStartElapsed = now
                    } else if (now - speechStartElapsed >= MIN_SPEECH_MS) {
                        speechConfirmed = true
                    }
                }
            } else {
                consecutiveSilenceFrames++
                if (!speechConfirmed) {
                    // During onset detection, require sustained silence before discarding the
                    // accumulated onset time.  A single noisy frame must not restart the 250 ms
                    // window.
                    onsetSilenceFrames++
                    if (onsetSilenceFrames >= ONSET_RESET_FRAMES) {
                        speechStartElapsed = 0L
                        onsetSilenceFrames = 0
                    }
                } else if (consecutiveSilenceFrames >= HYSTERESIS_FRAMES) {
                    val now = SystemClock.elapsedRealtime()
                    if (silenceStartElapsed == 0L) {
                        silenceStartElapsed = now
                    } else if (now - silenceStartElapsed >= silenceTimeoutMs) {
                        Timber.i(
                            "[RECORDING] SilenceAutoStopDetector | auto-stop on silence silenceMs=%d",
                            now - silenceStartElapsed,
                        )
                        onAutoStop()
                    }
                }
            }
        }
    }
}
