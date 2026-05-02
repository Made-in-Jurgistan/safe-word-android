package com.safeword.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
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
 * AudioRecorder — mirrors desktop Safe Word's AudioRecordingManager / cpal audio capture.
 *
 * Captures PCM audio at 16kHz mono (Moonshine's native sample rate — no resampling needed).
 * Streams amplitude levels for UI visualization.
 * Accumulates samples into a FloatArray buffer for transcription.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        const val SAMPLE_RATE = 16_000 // Moonshine native rate
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SIZE = 512 // 32ms at 16kHz (Silero VAD v5+ window)

        /** Default RMS threshold below which audio is considered silence. */
        private const val DEFAULT_SILENCE_THRESHOLD = 0.008f
        /** Milliseconds of silence to preserve on each side when trimming. */
        private const val DEFAULT_KEEP_PADDING_MS = 200
        /** Floor dB value representing silence (used for amplitude state). */
        private const val SILENCE_FLOOR_DB = -60f
        /** Maximum recording duration in seconds (ring-buffer capacity). */
        private const val MAX_RECORDING_DURATION_SEC = 600

    }

    @Volatile private var audioRecord: AudioRecord? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var gainControl: AutomaticGainControl? = null
    private val _amplitudeDb = MutableStateFlow(SILENCE_FLOOR_DB)
    val amplitudeDb: StateFlow<Float> = _amplitudeDb.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Sample buffer allocated fresh at the start of each recording session. */
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
     *   32 ms PCM chunk is captured. The [FloatArray] slice is a copy safe to forward to a
     *   streaming transcription engine (e.g. [MoonshineStreamingEngine]).
     *   Must be non-blocking; heavy work should be dispatched to another coroutine.
     * @param accumulateSamples If true, samples are written into an internal ring buffer
     *   (~38 MB) and retrievable via [getRecordedSamples]. Set to false for streaming-only
     *   use-cases to avoid the allocation.
     */
    suspend fun record(
        onChunkAvailable: ((FloatArray) -> Unit)? = null,
        accumulateSamples: Boolean = true,
    ): Unit = withContext(Dispatchers.IO) {
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

        // Allocate sample buffer on the IO thread only when the caller needs it.
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

        // Attach hardware audio preprocessing (free Android APIs)
        attachAudioPreprocessing(recorder.audioSessionId)

        Timber.d("[DIAGNOSTICS] AudioRecorder.record | audioRecord.state=%d channelCount=%d sampleRate=%d",
            recorder.state, recorder.channelCount, recorder.sampleRate)
        val chunk = ShortArray(CHUNK_SIZE)
        val floatChunk = FloatArray(CHUNK_SIZE)
        var totalChunksRead = 0L
        var totalReadErrors = 0

        try {
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
                    // Accumulate samples — bulk copy, no boxing (skipped when accumulateSamples = false)
                    if (accumulateSamples) sampleBuffer?.write(floatChunk, read)
                    // Notify streaming consumers (e.g. MoonshineStreamingEngine)
                    if (onChunkAvailable != null) {
                        onChunkAvailable(floatChunk.copyOf(read))
                    }
                    // Calculate amplitude for UI visualization
                    val rms = calculateRms(floatChunk, read)
                    _amplitudeDb.value = if (rms > 0) (20 * log10(rms)).toFloat() else SILENCE_FLOOR_DB
                } else if (read == 0) {
                    Timber.d("[AUDIO] AudioRecorder.record | read returned 0 — recorder likely stopped")
                } else {
                    totalReadErrors++
                    Timber.w("[WARN] AudioRecorder.record | read error code=%d (ERROR=%d ERROR_BAD_VALUE=%d ERROR_DEAD_OBJECT=%d)",
                        read, AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE, AudioRecord.ERROR_DEAD_OBJECT)
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
            Timber.i("[EXIT] AudioRecorder.record | stopped sampleCount=%d durationSec=%.1f totalChunksRead=%d readErrors=%d",
                sampleCount, durationSec, totalChunksRead, totalReadErrors)
        }
    }

    /** Stop recording (if recording from a different coroutine context). */
    fun stop() {
        val recorder = audioRecord
        Timber.i("[ENTER] AudioRecorder.stop | hasRecorder=%b isRecording=%b thread=%s",
            recorder != null, _isRecording.value, Thread.currentThread().name)
        try {
            recorder?.stop()
        } catch (e: IllegalStateException) {
            Timber.w(e, "[WARN] AudioRecorder.stop | recorder already stopped")
        }
        Timber.d("[STATE] AudioRecorder.stop | recorder stopped")
    }

    private fun calculateRms(samples: FloatArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / count)
    }

    /**
     * Attach Android hardware audio preprocessing:
     * - [NoiseSuppressor]: reduces ambient noise before PCM reaches the app.
     * - [AutomaticGainControl]: normalizes volume levels for consistent VAD/STT input.
     * Both are zero-CPU-cost effects handled in the audio HAL.
     */
    private fun attachAudioPreprocessing(audioSessionId: Int) {
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also {
                    it.enabled = true
                    Timber.i("[AUDIO] NoiseSuppressor attached and enabled sessionId=%d", audioSessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "[AUDIO] NoiseSuppressor creation failed — continuing without")
            }
        } else {
            Timber.d("[AUDIO] NoiseSuppressor not available on this device")
        }

        if (AutomaticGainControl.isAvailable()) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId)?.also {
                    it.enabled = true
                    Timber.i("[AUDIO] AutomaticGainControl attached and enabled sessionId=%d", audioSessionId)
                }
            } catch (e: Exception) {
                Timber.w(e, "[AUDIO] AutomaticGainControl creation failed — continuing without")
            }
        } else {
            Timber.d("[AUDIO] AutomaticGainControl not available on this device")
        }
    }

    /** Release audio preprocessing effects. */
    private fun releaseAudioPreprocessing() {
        noiseSuppressor?.release()
        noiseSuppressor = null
        gainControl?.release()
        gainControl = null
        Timber.d("[AUDIO] Audio preprocessing effects released")
    }
}
