package com.safeword.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import android.os.Process
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioRecorder — mirrors desktop Safe Word's AudioRecordingManager / cpal audio capture.
 *
 * Captures PCM audio at 16kHz mono (Whisper's native sample rate — no resampling needed).
 * Streams amplitude levels for UI visualization.
 * Accumulates samples into a FloatArray buffer for transcription.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    companion object {
        const val SAMPLE_RATE = 16_000 // Whisper native rate
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 480 // 30ms at 16kHz (Silero VAD window)

        /** Default RMS threshold below which audio is considered silence. */
        private const val DEFAULT_SILENCE_THRESHOLD = 0.008f
        /** Milliseconds of silence to preserve on each side when trimming. */
        private const val DEFAULT_KEEP_PADDING_MS = 200
        /** Fraction of original duration below which trim is considered significant for logging. */
        private const val TRIM_LOG_THRESHOLD = 0.9f
        /** Floor dB value representing silence (used for amplitude state). */
        private const val SILENCE_FLOOR_DB = -60f
        /** Maximum recording duration in seconds (ring-buffer capacity). */
        private const val MAX_RECORDING_DURATION_SEC = 600

        /**
         * Trim leading and trailing silence from audio samples.
         * Reduces audio length sent to whisper, directly reducing inference time.
         *
         * @param samples PCM audio samples [-1.0, 1.0]
         * @param silenceThreshold RMS threshold below which audio is considered silence
         * @param windowSize number of samples per analysis window (30ms at 16kHz = 480)
         * @param keepPaddingMs milliseconds of silence to keep on each side
         * @return trimmed audio samples
         */
        fun trimSilence(
            samples: FloatArray,
            silenceThreshold: Float = DEFAULT_SILENCE_THRESHOLD,
            windowSize: Int = CHUNK_SIZE,
            keepPaddingMs: Int = DEFAULT_KEEP_PADDING_MS,
        ): FloatArray {
            if (samples.size < windowSize * 2) return samples

            val paddingSamples = (keepPaddingMs * SAMPLE_RATE) / 1000
            // Compare energy (sum-of-squares) directly — avoids sqrt per window.
            val energyThreshold = silenceThreshold * silenceThreshold * windowSize

            // Find first window with audio above threshold (non-overlapping stride)
            var startIdx = 0
            for (i in 0 until samples.size - windowSize step windowSize) {
                var energy = 0.0f
                for (j in i until i + windowSize) {
                    energy += samples[j] * samples[j]
                }
                if (energy > energyThreshold) {
                    startIdx = (i - paddingSamples).coerceAtLeast(0)
                    break
                }
            }

            // Find last window with audio above threshold (non-overlapping stride)
            var endIdx = samples.size
            for (i in samples.size - windowSize downTo 0 step windowSize) {
                var energy = 0.0f
                val end = (i + windowSize).coerceAtMost(samples.size)
                val len = end - i
                for (j in i until end) {
                    energy += samples[j] * samples[j]
                }
                if (energy > silenceThreshold * silenceThreshold * len) {
                    endIdx = (end + paddingSamples).coerceAtMost(samples.size)
                    break
                }
            }

            if (startIdx >= endIdx) return samples

            val trimmed = samples.copyOfRange(startIdx, endIdx)
            val originalDur = samples.size.toFloat() / SAMPLE_RATE
            val trimmedDur = trimmed.size.toFloat() / SAMPLE_RATE
            Timber.d("[ENTER] AudioRecorder.trimSilence | inputSamples=%d threshold=%.4f windowSize=%d paddingMs=%d",
                samples.size, silenceThreshold, windowSize, keepPaddingMs)
            if (trimmedDur < originalDur * TRIM_LOG_THRESHOLD) {
                Timber.i("[PERF] AudioRecorder.trimSilence | %.1fs → %.1fs (saved %.0f%%) startIdx=%d endIdx=%d".format(
                    originalDur, trimmedDur, (1 - trimmedDur / originalDur) * 100, startIdx, endIdx,
                ))
            } else {
                Timber.d("[EXIT] AudioRecorder.trimSilence | no significant silence trimmed inputSec=%.1f outputSec=%.1f", originalDur, trimmedDur)
            }
            return trimmed
        }
    }

    @Volatile private var audioRecord: AudioRecord? = null
    private val _amplitudeDb = MutableStateFlow(SILENCE_FLOOR_DB)
    val amplitudeDb: StateFlow<Float> = _amplitudeDb.asStateFlow()

    private val _speechProbability = MutableStateFlow(0f)
    /** Real-time speech probability from Silero VAD (0..1). Updated each chunk. */
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Optional VAD detector for real-time speech probability during recording. */
    var vadDetector: SileroVadDetector? = null

    /** Sample buffer allocated fresh at the start of each recording session. */
    private var sampleBuffer: FloatRingBuffer? = null

    /** Reusable window for VAD — avoids per-window allocation in recording loop. */
    private val vadWindow = FloatArray(SileroVadDetector.WINDOW_SIZE)

    /**
     * Snapshot of (absoluteSampleOffset, probability) pairs from the last completed recording.
     * Written on the IO recording thread (volatile publish in finally), read on Default thread.
     * @Volatile ensures the reference assignment is visible across threads without explicit locking.
     */
    @Volatile
    private var vadWindowProbsSnapshot: List<Pair<Int, Float>> = emptyList()

    /** Returns the per-window speech probabilities captured during the last recording session. */
    fun getWindowProbabilities(): List<Pair<Int, Float>> = vadWindowProbsSnapshot

    /** Get a copy of the samples recorded in the last session. */
    fun getRecordedSamples(): FloatArray {
        val count = sampleBuffer?.size ?: 0
        val durationSec = count.toFloat() / SAMPLE_RATE
        Timber.d("[AUDIO] getRecordedSamples | sampleCount=%d durationSec=%.2f", count, durationSec)
        return sampleBuffer?.toFloatArray() ?: FloatArray(0)
    }

    /**
     * Records audio until the coroutine is cancelled.
     * Samples are accumulated in the internal ring buffer — retrieve via [getRecordedSamples].
     */
    suspend fun record(): Unit = withContext(Dispatchers.IO) {
        Timber.i("[ENTER] AudioRecorder.record | thread=%s", Thread.currentThread().name)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("[PERMISSION] AudioRecorder.record | RECORD_AUDIO not granted")
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        // Set audio-priority thread scheduling for reliable capture
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        Timber.d("[DIAGNOSTICS] AudioRecorder.record | threadPriority=THREAD_PRIORITY_AUDIO tid=%d", Process.myTid())

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        val bufferSize = max(minBufferSize, CHUNK_SIZE * 2 * 2 /* 2 frames of Int16 */)
        Timber.d("[DIAGNOSTICS] AudioRecorder.record | minBufferSize=%d allocatedBufferSize=%d multiplier=%.1f source=VOICE_RECOGNITION format=PCM_16BIT", minBufferSize, bufferSize, bufferSize.toFloat() / minBufferSize)

        // Allocate sample buffer on the IO thread, not at DI construction time.
        sampleBuffer = FloatRingBuffer(SAMPLE_RATE * MAX_RECORDING_DURATION_SEC)

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
        Timber.d("[DIAGNOSTICS] AudioRecorder.record | audioRecord.state=%d channelCount=%d sampleRate=%d",
            recorder.state, recorder.channelCount, recorder.sampleRate)
        val chunk = ShortArray(CHUNK_SIZE)
        val floatChunk = FloatArray(CHUNK_SIZE)
        var totalChunksRead = 0L
        var totalReadErrors = 0

        val localProbOffsets = IntArray(SAMPLE_RATE * MAX_RECORDING_DURATION_SEC / CHUNK_SIZE + 1)
        val localProbValues = FloatArray(localProbOffsets.size)
        var localProbCount = 0
        try {
            vadWindowProbsSnapshot = emptyList()
            vadDetector?.resetStates()
            Timber.d("[STATE] AudioRecorder.record | vadDetector.resetStates called hasVad=%b", vadDetector != null)
            _speechProbability.value = 0f
            recorder.startRecording()
            _isRecording.value = true
            Timber.i("[RECORDING] AudioRecorder.record | started sampleRate=%d chunkSize=%d format=PCM_16BIT source=VOICE_RECOGNITION", SAMPLE_RATE, CHUNK_SIZE)

            while (isActive) {
                val read = recorder.read(chunk, 0, CHUNK_SIZE, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    totalChunksRead++
                    // Convert Int16 PCM -> Float32 normalized to [-1, 1]
                    for (i in 0 until read) {
                        floatChunk[i] = chunk[i] / 32768f
                    }
                    // Accumulate samples — bulk copy, no boxing
                    sampleBuffer?.write(floatChunk, read)
                    // Calculate amplitude for UI visualization
                    val rms = calculateRms(floatChunk, read)
                    _amplitudeDb.value = if (rms > 0) (20 * log10(rms)).toFloat() else SILENCE_FLOOR_DB

                    // Run VAD on 480-sample windows within this chunk
                    val vad = vadDetector
                    if (vad != null && vad.isLoaded) {
                        val bufferBase = (sampleBuffer?.size ?: 0) - read
                        var maxProb = 0f
                        var offset = 0
                        while (offset + SileroVadDetector.WINDOW_SIZE <= read) {
                            // Reuse vadWindow — System.arraycopy avoids per-window alloc
                            System.arraycopy(floatChunk, offset, vadWindow, 0, SileroVadDetector.WINDOW_SIZE)
                            val prob = vad.detect(vadWindow)
                            if (localProbCount < localProbOffsets.size) {
                                localProbOffsets[localProbCount] = bufferBase + offset
                                localProbValues[localProbCount] = prob
                                localProbCount++
                            }
                            if (prob > maxProb) maxProb = prob
                            offset += SileroVadDetector.WINDOW_SIZE
                        }
                        _speechProbability.value = maxProb
                    }
                } else if (read == 0) {
                    Timber.d("[AUDIO] AudioRecorder.record | read returned 0 — recorder likely stopped")
                } else {
                    totalReadErrors++
                    Timber.w("[WARN] AudioRecorder.record | read error code=%d (ERROR=%d ERROR_BAD_VALUE=%d ERROR_DEAD_OBJECT=%d)",
                        read, AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE, AudioRecord.ERROR_DEAD_OBJECT)
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            audioRecord = null
            _isRecording.value = false
            _amplitudeDb.value = SILENCE_FLOOR_DB
            _speechProbability.value = 0f
            vadWindowProbsSnapshot = List(localProbCount) { i ->
                localProbOffsets[i] to localProbValues[i]
            }
            val sampleCount = sampleBuffer?.size ?: 0
            val durationSec = sampleCount.toFloat() / SAMPLE_RATE
            Timber.i("[EXIT] AudioRecorder.record | stopped sampleCount=%d durationSec=%.1f totalChunksRead=%d readErrors=%d",
                sampleCount, durationSec, totalChunksRead, totalReadErrors)
        }
    }

    /** Stop recording (if recording from a different coroutine context). */
    fun stop() {
        val recorder = audioRecord
        Timber.i("[ENTER] AudioRecorder.stop | hasRecorder=%b isRecording=%b thread=%s",
            recorder != null, _isRecording.value, Thread.currentThread().name)
        recorder?.stop()
        Timber.d("[STATE] AudioRecorder.stop | recorder stopped")
    }

    private fun calculateRms(samples: FloatArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / count)
    }
}
