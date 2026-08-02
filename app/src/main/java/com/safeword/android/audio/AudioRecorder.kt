// =============================================================================
// File: AudioRecorder.kt
// Purpose: Microphone capture pipeline producing 16 kHz mono float PCM in the
//          [-1, 1] range, suitable for Moonshine streaming.
// Responsibilities:
//   - Acquire AudioRecord with VOICE_RECOGNITION source and PCM_16BIT.
//   - Accumulate samples into FloatRingBuffer for offline retrieval.
//   - Publish throttled amplitude (dBFS) for UI VU meters.
//   - Attach hardware NoiseSuppressor / AGC / AEC.
// Dependencies: AndroidX core, kotlinx-coroutines, FloatRingBuffer, timber.log.Timber
// License: matches Safe Word Android repository.
// =============================================================================
package com.safeword.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * AudioRecorder — captures PCM at 16 kHz mono, normalized to [-1, 1].
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 512

        private const val SILENCE_FLOOR_DB = -60f
        private const val MAX_RECORDING_DURATION_SEC = 600

        /** Throttle UI amplitude updates to ~15 Hz (every 66 ms). */
        private const val AMPLITUDE_UPDATE_INTERVAL_MS = 66L
        /** Minimum dB delta below which amplitude updates are skipped. */
        private const val AMPLITUDE_UPDATE_HYSTERESIS_DB = 0.5f
    }

    @Volatile private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    private val _amplitudeDb = MutableStateFlow(SILENCE_FLOOR_DB)
    val amplitudeDb: StateFlow<Float> = _amplitudeDb.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private var sampleBuffer: FloatRingBuffer? = null

    /** Get a copy of the samples recorded in the last session. */
    fun getRecordedSamples(): FloatArray {
        val count = sampleBuffer?.size ?: 0
        val durationSec = count.toFloat() / SAMPLE_RATE
        Timber.d("[AUDIO] getRecordedSamples | sampleCount=%d durationSec=%.2f", count, durationSec)
        return sampleBuffer?.toFloatArray() ?: FloatArray(0)
    }

    /**
     * Records audio until the coroutine is cancelled.
     *
     * @param onChunkAvailable Optional callback invoked on the IO recording thread after each
     *   PCM chunk is captured. The slice handed to the callback is owned by the caller for
     *   the duration of the call only — copy out anything that must outlive the call.
     * @param accumulateSamples If true, samples are written into an internal ring buffer.
     */
    suspend fun record(
        onChunkAvailable: ((FloatArray, Int) -> Unit)? = null,
        accumulateSamples: Boolean = true,
    ): Unit = withContext(Dispatchers.IO) {
        Timber.i("[ENTER] AudioRecorder.record | thread=%s", Thread.currentThread().name)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("[PERMISSION] AudioRecorder.record | RECORD_AUDIO not granted")
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        val bufferSize = max(minBufferSize, CHUNK_SIZE * 2 * 2)

        sampleBuffer = if (accumulateSamples) FloatRingBuffer(SAMPLE_RATE * MAX_RECORDING_DURATION_SEC) else null

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            CHANNEL,
            FORMAT,
            bufferSize,
        )

        check(recorder.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialize — microphone may be in use or permission denied (bufferSize=$bufferSize)"
        }

        audioRecord = recorder

        attachAudioPreprocessing(recorder.audioSessionId, useHardwareNs = true)

        val chunk = ShortArray(CHUNK_SIZE)
        val floatChunk = FloatArray(CHUNK_SIZE)
        var totalChunksRead = 0L
        var totalReadErrors = 0
        var lastAmplitudeUpdateMs = 0L
        var lastAmplitudeDb = SILENCE_FLOOR_DB

        try {
            recorder.startRecording()
            _isRecording.value = true
            Timber.i(
                "[RECORDING] AudioRecorder.record | started sampleRate=%d chunkSize=%d hwNS=%b",
                SAMPLE_RATE, CHUNK_SIZE, noiseSuppressor != null,
            )

            while (isActive) {
                val read = recorder.read(chunk, 0, CHUNK_SIZE, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    totalChunksRead++
                    var sum = 0f
                    for (i in 0 until read) {
                        val v = chunk[i] / 32768f
                        floatChunk[i] = v
                        sum += v
                    }
                    val mean = sum / read
                    if (mean != 0f) {
                        for (i in 0 until read) floatChunk[i] -= mean
                    }

                    if (accumulateSamples) {
                        sampleBuffer?.write(floatChunk, read)
                    }
                    onChunkAvailable?.invoke(floatChunk, read)

                    val nowMs = SystemClock.elapsedRealtime()
                    if (nowMs - lastAmplitudeUpdateMs >= AMPLITUDE_UPDATE_INTERVAL_MS) {
                        val rms = calculateRms(floatChunk, read)
                        val db = if (rms > 0) (20 * log10(rms)).toFloat() else SILENCE_FLOOR_DB
                        if (kotlin.math.abs(db - lastAmplitudeDb) >= AMPLITUDE_UPDATE_HYSTERESIS_DB ||
                            db == SILENCE_FLOOR_DB
                        ) {
                            _amplitudeDb.value = db
                            lastAmplitudeDb = db
                        }
                        lastAmplitudeUpdateMs = nowMs
                    }
                } else if (read == 0) {
                    Timber.d("[AUDIO] AudioRecorder.record | read returned 0 — recorder likely stopped")
                } else {
                    totalReadErrors++
                    Timber.w(
                        "[WARN] AudioRecorder.record | read error code=%d " +
                            "(ERROR=%d ERROR_BAD_VALUE=%d ERROR_DEAD_OBJECT=%d)",
                        read, AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE,
                        AudioRecord.ERROR_DEAD_OBJECT,
                    )
                }
            }
        } finally {
            try { recorder.stop() } catch (_: IllegalStateException) { /* already stopped */ }
            releaseAudioPreprocessing()
            recorder.release()
            audioRecord = null
            _isRecording.value = false
            _amplitudeDb.value = SILENCE_FLOOR_DB
            val sampleCount = sampleBuffer?.size ?: 0
            val durationSec = sampleCount.toFloat() / SAMPLE_RATE
            Timber.i(
                "[EXIT] AudioRecorder.record | stopped sampleCount=%d durationSec=%.1f " +
                    "totalChunksRead=%d readErrors=%d",
                sampleCount, durationSec, totalChunksRead, totalReadErrors,
            )
        }
    }

    /** Stop recording (if recording from a different coroutine context). */
    fun stop() {
        val recorder = audioRecord
        Timber.i(
            "[ENTER] AudioRecorder.stop | hasRecorder=%b isRecording=%b thread=%s",
            recorder != null, _isRecording.value, Thread.currentThread().name,
        )
        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            Timber.w(e, "[WARN] AudioRecorder.stop | recorder already stopped")
        }
    }

    private fun calculateRms(samples: FloatArray, count: Int): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / count)
    }

    private fun attachAudioPreprocessing(audioSessionId: Int, useHardwareNs: Boolean) {
        if (useHardwareNs && NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also {
                    it.enabled = true
                    Timber.i("[AUDIO] NoiseSuppressor attached sessionId=%d", audioSessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "[AUDIO] NoiseSuppressor creation failed — continuing without")
            }
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId)?.also {
                    it.enabled = true
                    Timber.i("[AUDIO] AutomaticGainControl attached sessionId=%d", audioSessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "[AUDIO] AutomaticGainControl creation failed — continuing without")
            }
        }

        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also {
                    it.enabled = true
                    Timber.i("[AUDIO] AcousticEchoCanceler attached sessionId=%d", audioSessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "[AUDIO] AcousticEchoCanceler creation failed — continuing without")
            }
        }
    }

    private fun releaseAudioPreprocessing() {
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null
        echoCanceler?.release()
        echoCanceler = null
    }
}
