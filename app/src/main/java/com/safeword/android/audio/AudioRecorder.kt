package com.safeword.android.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.ActivityCompat
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import android.os.Process
import java.util.ArrayDeque
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AudioRecorder — mirrors desktop Safe Word's AudioRecordingManager / cpal audio capture.
 *
 * Captures PCM audio at 16kHz mono (STT native sample rate — no resampling needed).
 * Streams amplitude levels for UI visualization.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val adaptiveVadSensitivity: AdaptiveVadSensitivity,
    private val vadDetector: SileroVadDetector,
) {
    companion object {
        /** Canonical audio sample rate — sourced from [SileroVadDetector]. */
        const val SAMPLE_RATE = SileroVadDetector.SAMPLE_RATE
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val FORMAT = AudioFormat.ENCODING_PCM_16BIT
        /** Chunk size in samples — derived from [SileroVadDetector.WINDOW_SIZE] (30 ms at 16 kHz). */
        private const val CHUNK_SIZE = SileroVadDetector.WINDOW_SIZE

        /** Floor dB value representing silence (used for amplitude state). */
        private const val SILENCE_FLOOR_DB = -60f

        /** Reciprocal of max Int16 value — precomputed for multiplication (3–5× faster than division on ARM). */
        private const val INT16_RECIPROCAL = 1f / 32768f

        /** Buffer pool capacity — 8 arrays ≈ 15 KB, absorbs SharedFlow consumer lag. */
        private const val BUFFER_POOL_CAPACITY = 8

        /**
         * Buffer size multiplier for AudioRecord allocation.
         * Double the minimum frame count to reduce underrun risk on high-load devices.
         * See AudioRecord documentation: bufferSizeInBytes >= minBufferSize.
         */
        private const val AUDIO_RECORD_BUFFER_MULTIPLIER = 2
    }

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recordingJob: Job? = null
    private val _amplitudeDb = MutableStateFlow(SILENCE_FLOOR_DB)
    val amplitudeDb: StateFlow<Float> = _amplitudeDb.asStateFlow()

    private val _speechProbability = MutableStateFlow(0f)
    /** Real-time speech probability from Silero VAD (0..1). Updated each chunk. */
    val speechProbability: StateFlow<Float> = _speechProbability.asStateFlow()

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    /** Set to false to disable VAD for the current session (e.g. when model is not loaded or user toggled off). */
    @Volatile var isVadEnabled: Boolean = true
        internal set

    /**
     * Stop flag set by [stop] before the recording coroutine may have started.
     * Checked at loop entry so a stop request that arrives before [recordingJob] is
     * assigned still terminates the session immediately.
     */
    @Volatile private var stopRequested: Boolean = false

    private val _audioChunks = MutableSharedFlow<FloatArray>(extraBufferCapacity = 64)

    /**
     * Hot stream of pre-processed audio chunks (DC removal → soft clip → pre-emphasis).
     * Collect this to feed audio to the STT engine.
     * Uses a 64-chunk buffer (~1.9 s at 30 ms/chunk) to absorb inference jitter without back-pressure.
     */
    val audioChunks: SharedFlow<FloatArray> = _audioChunks.asSharedFlow()

    /** Reusable window for VAD — avoids per-window allocation in recording loop. */
    private val vadWindow = FloatArray(SileroVadDetector.WINDOW_SIZE)

    /**
     * Pool of reusable FloatArray buffers for audio chunk emission.
     * Eliminates per-chunk .copyOf() allocation (was 66 allocs/sec, ~2.1 MB/min).
     * Consumer must return buffers via [recycleBuffer] after processing.
     */
    private val bufferPool = ArrayDeque<FloatArray>(BUFFER_POOL_CAPACITY)

    /** Acquire a FloatArray from the pool or allocate a new one. */
    private fun acquireBuffer(): FloatArray =
        synchronized(bufferPool) { bufferPool.pollFirst() } ?: FloatArray(CHUNK_SIZE)

    /**
     * Select the most reliable audio source for the current device.
     *
     * Samsung One UI builds can aggressively gate/denoise the VOICE_RECOGNITION path,
     * which drives Silero probabilities near zero and yields false NoSpeech sessions.
     * Prefer MIC on Samsung and keep VOICE_RECOGNITION elsewhere.
     */
    private fun selectAudioSource(): Int {
        val isSamsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        return if (isSamsung) MediaRecorder.AudioSource.MIC else MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun audioSourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> source.toString()
    }

    /** Return a buffer to the pool after the consumer has finished with it. */
    fun recycleBuffer(buffer: FloatArray) {
        if (buffer.size != CHUNK_SIZE) return
        synchronized(bufferPool) {
            if (bufferPool.size < BUFFER_POOL_CAPACITY) bufferPool.addLast(buffer)
        }
    }

    /** Records audio until the coroutine is cancelled. */
    suspend fun record(): Unit = withContext(Dispatchers.IO) {
        recordingJob = coroutineContext[Job]
        stopRequested = false
        Timber.i("[ENTER] AudioRecorder.record | thread=%s", Thread.currentThread().name)
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.e("[PERMISSION] AudioRecorder.record | RECORD_AUDIO not granted")
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        // Set audio-priority thread scheduling for reliable capture
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, FORMAT)
        val bufferSize = max(minBufferSize, CHUNK_SIZE * Short.SIZE_BYTES * AUDIO_RECORD_BUFFER_MULTIPLIER)
        val audioSource = selectAudioSource()
        val recorder = AudioRecord(
            audioSource,
            SAMPLE_RATE,
            CHANNEL,
            FORMAT,
            bufferSize,
        )

        // Guard the STATE_INITIALIZED check so a failing recorder is always released — `check(...)`
        // throws IllegalStateException and would otherwise leak the native AudioRecord resource,
        // holding the microphone slot system-wide.
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException(
                "AudioRecord failed to initialize — microphone may be in use or permission denied (bufferSize=$bufferSize)"
            )
        }

        audioRecord = recorder
        val chunk = ShortArray(CHUNK_SIZE)
        // Single working buffer: Int16 PCM is converted directly into the pooled emit buffer, then
        // DC-offset in place. Eliminates two previous System.arraycopy() calls per chunk
        // (~127 k float copies/sec of redundant work at 33 chunks/s).
        var totalChunksRead = 0L
        var totalReadErrors = 0
        var vadErrorCount = 0
        var lastVadResetFrame = 0

        try {
            if (isVadEnabled) vadDetector.resetStates()
            adaptiveVadSensitivity.reset()
            lastVadResetFrame = 0
            Timber.d("[STATE] AudioRecorder.record | vadDetector.resetStates called vadEnabled=%b vadLoaded=%b", isVadEnabled, vadDetector.isLoaded)
            _speechProbability.value = 0f
            recorder.startRecording()
            _isRecording.value = true
            Timber.i(
                "[RECORDING] AudioRecorder.record | started sampleRate=%d chunkSize=%d format=PCM_16BIT source=%s",
                SAMPLE_RATE,
                CHUNK_SIZE,
                audioSourceName(audioSource),
            )

            while (isActive && !stopRequested) {
                val read = recorder.read(chunk, 0, CHUNK_SIZE, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    totalChunksRead++
                    // Acquire the emit buffer up-front and write the converted PCM directly into it.
                    // Moonshine's learned conv frontend handles normalization internally,
                    // so no additional preprocessing is applied — no soft clip, no pre-emphasis,
                    // no DC-offset removal (redundant as Moonshine/Silero handle it).
                    // See: Moonshine v2 architecture paper §frontend_session ONNX node.
                    val emitBuffer = acquireBuffer()
                    for (i in 0 until read) {
                        emitBuffer[i] = chunk[i] * INT16_RECIPROCAL
                    }
                    // Short reads are rare (only at stream end). Zero-fill tail only when needed;
                    // the pooled buffer retains zeros from initialization / prior full reads.
                    if (read < CHUNK_SIZE && emitBuffer[read] != 0f) {
                        emitBuffer.fill(0f, read, CHUNK_SIZE)
                    }
                    val emitted = _audioChunks.tryEmit(emitBuffer)
                    if (!emitted) {
                        // Return the buffer to the pool — emission failure means no consumer will recycle.
                        recycleBuffer(emitBuffer)
                        Timber.w("[WARN] AudioRecorder.record | audio chunk dropped — channel full, chunkCount=%d", totalChunksRead)
                    }
                    // Calculate amplitude for UI visualization (matches what the engine sees).
                    val rms = calculateRms(emitBuffer, read)
                    _amplitudeDb.value = if (rms > 0) (20 * log10(rms)).toFloat() else SILENCE_FLOOR_DB

                    // Run VAD on 480-sample windows within this chunk
                    if (isVadEnabled && vadDetector.isLoaded) {
                        try {
                            var maxProb = 0f
                            var offset = 0
                            while (offset + SileroVadDetector.WINDOW_SIZE <= read) {
                                // Reuse vadWindow — System.arraycopy avoids per-window alloc
                                System.arraycopy(emitBuffer, offset, vadWindow, 0, SileroVadDetector.WINDOW_SIZE)
                                val prob = vadDetector.detect(vadWindow)
                                if (prob > maxProb) maxProb = prob
                                offset += SileroVadDetector.WINDOW_SIZE
                            }
                            _speechProbability.value = maxProb
                            adaptiveVadSensitivity.update(rms, maxProb)
                            vadErrorCount = 0  // clear error streak on success

                            // Reset VAD hidden state periodically during sustained silence.
                            // The Silero ONNX RNN accumulates hidden state across all frames;
                            // after thousands of noise-only frames the state drifts, causing
                            // biased probabilities when speech resumes.  Resetting every ~2 s
                            // of silence keeps the model fresh for the next utterance.
                            val silenceFrames = adaptiveVadSensitivity.consecutiveSilenceFrames
                            if (silenceFrames > 0 &&
                                silenceFrames - lastVadResetFrame >= AdaptiveVadSensitivity.VAD_RESET_SILENCE_FRAMES
                            ) {
                                lastVadResetFrame = silenceFrames
                                vadDetector.resetStates()
                                Timber.d("[STATE] AudioRecorder.record | periodic VAD reset after %d silence frames", silenceFrames)
                            }
                        } catch (e: Exception) {
                            vadErrorCount++
                            if (vadErrorCount >= 3) {
                                Timber.e(e, "[ERROR] AudioRecorder.record | VAD detect failed %d times consecutively, disabling for session", vadErrorCount)
                                isVadEnabled = false
                            } else {
                                Timber.w(e, "[WARN] AudioRecorder.record | VAD detect failed (attempt %d/3), will retry", vadErrorCount)
                            }
                        }
                    }
                } else if (read == 0) {
                    Timber.d("[AUDIO] AudioRecorder.record | read returned 0 — recorder likely stopped")
                } else {
                    totalReadErrors++
                    Timber.w("[WARN] AudioRecorder.record | read error code=%d (ERROR=%d ERROR_BAD_VALUE=%d ERROR_DEAD_OBJECT=%d)",
                        read, AudioRecord.ERROR, AudioRecord.ERROR_BAD_VALUE, AudioRecord.ERROR_DEAD_OBJECT)
                    if (read == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw IOException("AudioRecord server died (ERROR_DEAD_OBJECT)")
                    }
                }
            }
        } finally {
            // Only stop if we're still in recording state (avoid duplicate stop() call)
            if (_isRecording.value) {
                runCatching { recorder.stop() }
            }
            recorder.release()
            audioRecord = null
            recordingJob = null
            _isRecording.value = false
            _amplitudeDb.value = SILENCE_FLOOR_DB
            _speechProbability.value = 0f
            val sampleCount = totalChunksRead * CHUNK_SIZE
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
        // Signal stop immediately — this covers the race window where stop() is called
        // before withContext(Dispatchers.IO) has run and assigned recordingJob.
        stopRequested = true
        // Stop the AudioRecord to unblock the blocking AudioRecord.read() call in
        // the recording coroutine; without this the coroutine would hang until the next
        // chunk arrives (up to 30 ms) before seeing the cancellation.
        recorder?.stop()
        recordingJob?.cancel()
        recordingJob = null
        Timber.d("[STATE] AudioRecorder.stop | recorder stopped, coroutine cancelled")
    }

    private fun calculateRms(samples: FloatArray, count: Int): Float {
        var sum = 0f
        for (i in 0 until count) {
            sum += samples[i] * samples[i]
        }
        return sqrt(sum / count)
    }
}
