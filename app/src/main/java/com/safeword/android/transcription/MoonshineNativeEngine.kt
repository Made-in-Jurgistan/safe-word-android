package com.safeword.android.transcription

import com.safeword.android.audio.SileroVadDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import timber.log.Timber

/**
 * MoonshineNativeEngine wraps the open-source moonshine-ai/moonshine C++ core
 * via [MoonshineNativeBridge] (libmoonshine-bridge.so).
 *
 * Implements [StreamingTranscriptionEngine]. Mirrors the streaming behaviour of the
 * removed commercial SDK engine: buffered audio channel, single-threaded JNI dispatcher,
 * periodic stream restart to flush KV-cache state, and per-line event dispatch.
 *
 * API flow per stream:
 *   loadModel → startStream → [feedAudio]* → stopStream → (repeat) → release
 *
 * Restart cycle (transparent to caller):
 *   nativeStopStream + nativeFreeStream → nativeCreateStream + nativeStartStream
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class MoonshineNativeEngine @Inject constructor() : StreamingTranscriptionEngine {

    // ── Handles ───────────────────────────────────────────────────────────

    @Volatile private var transcriberHandle: Int = -1
    @Volatile private var streamHandle: Int = -1
    @Volatile private var eventListener: StreamingEventListener? = null

    // ── Concurrency ───────────────────────────────────────────────────────

    /**
     * Single-thread dispatcher for all JNI calls. The Moonshine C API serialises
     * calculations per transcriber, so concurrent calls would block each other;
     * owning the serialisation here makes latency predictable.
     */
    private val feedDispatcher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "MoonshineFeed").apply { isDaemon = true }
    }.asCoroutineDispatcher()

    // feedScope is created/accessed only from feedDispatcher (single-threaded executor).
    // @Volatile is not needed and would mislead readers. Access is serialized by the executor.
    // scopeResetLock serializes release() thread with feedDispatcher when recreating feedScope.
    private var feedScope = CoroutineScope(SupervisorJob() + feedDispatcher)
    private val scopeResetLock = Any()

    /**
     * Buffered channel between AudioRecord capture (caller thread) and inference
     * (feedDispatcher). Capacity of 64 chunks ≈ 1.9 s at 30 ms/chunk, absorbs
     * inference jitter without back-pressuring AudioRecord.
     */
    @Volatile private var audioChannel: Channel<FloatArray>? = null

    /**
     * Optional callback invoked after each [nativeAddAudio] call completes so the
     * caller can return the buffer to a pool.  Set via [setBufferRecycler].
     * Stored in an [AtomicReference] so it can be swapped without locking.
     */
    private val bufferRecycler = java.util.concurrent.atomic.AtomicReference<((FloatArray) -> Unit)?>(null)

    override fun setBufferRecycler(recycler: ((FloatArray) -> Unit)?) {
        bufferRecycler.set(recycler)
    }

    @Volatile private var feedJob: Job? = null

    // ── Transcript accumulation ───────────────────────────────────────────

    private val completedTranscript = StringBuilder()
    private var completedLineCount = 0
    private val completedLinesLock = Any()

    // ── Timing and restart state ──────────────────────────────────────────

    @Volatile private var firstLineStartedTimeMs: Long = 0L
    @Volatile private var streamStartTimeMs: Long = 0L
    private val segmentsSinceRestart = AtomicInteger(0)

    private val audioFedSinceRestartMs = AtomicLong(0L)

    /**
     * Cumulative audio fed to the engine across the *entire* session (never reset by
     * [doStreamRestart]). Used by [stopStream] to report the true audio duration.
     */
    private val totalAudioFedMs = AtomicLong(0L)

    private val restartRequested = AtomicBoolean(false)

    /**
     * Per-line state from the previous [nativeTranscribeStream] call.
     * Used for change detection without relying solely on the C API's
     * `is_new` / `has_text_changed` flags (which reset between restarts).
     * Accessed only from [feedDispatcher].
     */
    private val prevLines = HashMap<Long, PrevLineState>()

    /**
     * Line IDs for which [StreamingEventListener.onLineCompleted] has already fired.
     * Guards against the native engine cycling the same line between complete=false and
     * complete=true on every silent audio chunk, which causes 20+ duplicate completions
     * for a single utterance. Cleared on every stream start / restart.
     * Accessed only from [feedDispatcher].
     */
    private val firedCompletionIds = HashSet<Long>()

    // ── StreamingTranscriptionEngine ──────────────────────────────────────

    override val isLoaded: Boolean get() = transcriberHandle >= 0

    override suspend fun loadModel(path: String, modelArch: Int): Boolean =
        withContext(feedDispatcher) {
            if (transcriberHandle >= 0) {
                Timber.d("[INIT] MoonshineNativeEngine.loadModel | already_loaded=true")
                return@withContext true
            }
            val handle = MoonshineNativeBridge.nativeLoadTranscriber(path, modelArch)
            if (handle >= 0) {
                transcriberHandle = handle
                Timber.i(
                    "[INIT] MoonshineNativeEngine.loadModel | ok handle=%d arch=%d path=%s",
                    handle, modelArch, path,
                )
                true
            } else {
                Timber.e(
                    "[ERROR] MoonshineNativeEngine.loadModel | failed error=%d arch=%d path=%s",
                    handle, modelArch, path,
                )
                false
            }
        }

    override fun startStream() {
        val transH = transcriberHandle
        check(transH >= 0) { "Transcriber not loaded — call loadModel() first" }

        streamStartTimeMs = System.currentTimeMillis()
        firstLineStartedTimeMs = 0L

        synchronized(completedLinesLock) {
            completedTranscript.clear()
            completedLineCount = 0
        }
        prevLines.clear()
        firedCompletionIds.clear()
        segmentsSinceRestart.set(0)
        audioFedSinceRestartMs.set(0L)
        totalAudioFedMs.set(0L)
        restartRequested.set(false)

        val channel = Channel<FloatArray>(AUDIO_CHANNEL_CAPACITY)
        audioChannel = channel
        // Synchronize with release() to ensure feedScope is not reassigned mid-launch.
        synchronized(scopeResetLock) {
            feedJob = feedScope.launch {
                val streamH = MoonshineNativeBridge.nativeCreateStream(transH)
                if (streamH < 0) {
                    Timber.e("[ERROR] MoonshineNativeEngine.startStream | createStream failed error=%d", streamH)
                    audioChannel = null
                    channel.close()
                    eventListener?.onError(IllegalStateException("moonshine_create_stream failed: $streamH"))
                    return@launch
                }
                val startErr = MoonshineNativeBridge.nativeStartStream(transH, streamH)
                if (startErr < 0) {
                    Timber.e("[ERROR] MoonshineNativeEngine.startStream | startStream failed error=%d", startErr)
                    MoonshineNativeBridge.nativeFreeStream(transH, streamH)
                    audioChannel = null
                    channel.close()
                    eventListener?.onError(IllegalStateException("moonshine_start_stream failed: $startErr"))
                    return@launch
                }
                streamHandle = streamH
                Timber.i("[STATE] MoonshineNativeEngine.startStream | streamHandle=%d", streamH)
                runFeedConsumer(channel)
            }
        }
    }

    override fun feedAudio(samples: FloatArray, sampleRate: Int) {
        val channel = audioChannel
        if (channel == null) {
            // Engine not streaming — recycle so the pool stays warm across sessions.
            bufferRecycler.get()?.invoke(samples)
            return
        }
        if (channel.trySend(samples).isFailure) {
            // Back-pressure: we cannot enqueue, and the consumer will never see this buffer —
            // recycle it ourselves so the AudioRecorder pool doesn't drain to zero.
            bufferRecycler.get()?.invoke(samples)
            restartRequested.set(true)
            Timber.w("[WARN] MoonshineNativeEngine.feedAudio | channel full, restart requested")
            return
        }
        audioFedSinceRestartMs.addAndGet(CHUNK_DURATION_MS.toLong())
        totalAudioFedMs.addAndGet(CHUNK_DURATION_MS.toLong())
        if (audioFedSinceRestartMs.get() >= maxAudioBeforeRestartMs) {
            restartRequested.set(true)
        }
    }

    override suspend fun stopStream(): TranscriptionResult {
        val channel = audioChannel
        audioChannel = null
        channel?.close()

        val job = feedJob
        feedJob = null
        withTimeoutOrNull(DRAIN_TIMEOUT_MS) { job?.join() }
        // Note: withTimeoutOrNull already cancels the coroutine on timeout — no explicit cancel needed.

        val transH = transcriberHandle
        val streamH = streamHandle
        streamHandle = -1

        if (transH >= 0 && streamH >= 0) {
            withContext(feedDispatcher) {
                // Stop stream to finalise any pending VAD/decoder state, then poll
                // one final time to capture results that surface only after stop.
                MoonshineNativeBridge.nativeStopStream(transH, streamH)
                val json = MoonshineNativeBridge.nativeTranscribeStream(transH, streamH)
                dispatchTranscriptEvents(json)
                MoonshineNativeBridge.nativeFreeStream(transH, streamH)
            }
        }

        val fullText = buildFullTranscript()
        val ttftMs = if (firstLineStartedTimeMs > 0L && streamStartTimeMs > 0L) {
            firstLineStartedTimeMs - streamStartTimeMs
        } else {
            0L
        }

        Timber.i(
            "[STATE] MoonshineNativeEngine.stopStream | fullLen=%d ttft_ms=%d completedLineCount=%d",
            fullText.length, ttftMs, completedLineCount,
        )
        return if (fullText.isBlank()) {
            TranscriptionResult.NoSpeech
        } else {
            TranscriptionResult.Success(
                text = fullText,
                // Report total audio fed across the entire session — the since-restart counter
                // resets at each KV-cache flush and would under-report long sessions by ~5–10×.
                audioDurationMs = totalAudioFedMs.get(),
                inferenceDurationMs = ttftMs,
            )
        }
    }

    override fun setEventListener(listener: StreamingEventListener?) {
        eventListener = listener
    }

    override suspend fun release() {
        withContext(Dispatchers.IO) {
            restartRequested.set(false)
            segmentsSinceRestart.set(0)
            audioFedSinceRestartMs.set(0L)
            totalAudioFedMs.set(0L)
            audioChannel?.close()
            audioChannel = null
            val job = feedJob
            feedJob = null
            job?.cancel()
            job?.join()
            // Cancel the current scope's SupervisorJob then recreate a fresh scope
            // backed by the same dispatcher. Allows the engine to be reloaded within
            // the same process lifetime without closing the backing ExecutorService
            // (closing it would cause future feedScope.launch() to throw
            // RejectedExecutionException).
            // Use scopeResetLock to serialize with any feedDispatcher threads that may be
            // checking feedScope state (e.g. in startStream() creating new jobs).
            synchronized(scopeResetLock) {
                feedScope.cancel()
                feedScope = CoroutineScope(SupervisorJob() + feedDispatcher)
            }

            val transH = transcriberHandle
            val streamH = streamHandle
            streamHandle = -1
            transcriberHandle = -1

            if (transH >= 0) {
                if (streamH >= 0) {
                    MoonshineNativeBridge.nativeStopStream(transH, streamH)
                    MoonshineNativeBridge.nativeFreeStream(transH, streamH)
                }
                MoonshineNativeBridge.nativeFreeTranscriber(transH)
            }

            synchronized(completedLinesLock) {
                completedTranscript.clear()
                completedLineCount = 0
            }
            prevLines.clear()
            Timber.d("[STATE] MoonshineNativeEngine.release | done")
        }
    }

    // ── Internal: feed consumer ───────────────────────────────────────────────

    private suspend fun runFeedConsumer(channel: Channel<FloatArray>) {
        val transH = transcriberHandle
        var streamH = streamHandle

        for (samples in channel) {
            if (streamH < 0) {
                // No active stream — return buffer to pool and wait for next chunk.
                bufferRecycler.get()?.invoke(samples)
                continue
            }

            val addErr = MoonshineNativeBridge.nativeAddAudio(transH, streamH, samples, SAMPLE_RATE)
            // Recycle the buffer back to the AudioRecorder pool now that the native layer
            // has consumed its contents.  No-op when no recycler is registered.
            bufferRecycler.get()?.invoke(samples)
            if (addErr < 0) {
                Timber.e("[ERROR] feedConsumer.addAudio | streamHandle=%d error=%d", streamH, addErr)
                continue
            }

            val json = MoonshineNativeBridge.nativeTranscribeStream(transH, streamH)
            dispatchTranscriptEvents(json)

            if ((restartRequested.get() || segmentsSinceRestart.get() >= maxSegmentsBeforeRestart)
                && channel.isEmpty
            ) {
                streamH = doStreamRestart(transH, streamH)
            }
        }
        // Channel closed — stopStream will call nativeStopStream + final nativeTranscribeStream.
    }

    private fun doStreamRestart(transH: Int, oldStreamH: Int): Int {
        val reason = if (restartRequested.get()) "channel_full_or_timeout" else "max_segments"
        MoonshineNativeBridge.nativeStopStream(transH, oldStreamH)
        MoonshineNativeBridge.nativeFreeStream(transH, oldStreamH)

        val newStreamH = MoonshineNativeBridge.nativeCreateStream(transH)
        if (newStreamH < 0) {
            Timber.e("[ERROR] feedConsumer.restart | createStream failed error=%d", newStreamH)
            streamHandle = -1
            return -1
        }
        val startErr = MoonshineNativeBridge.nativeStartStream(transH, newStreamH)
        if (startErr < 0) {
            Timber.e("[ERROR] feedConsumer.restart | startStream failed error=%d", startErr)
            MoonshineNativeBridge.nativeFreeStream(transH, newStreamH)
            streamHandle = -1
            return -1
        }

        streamHandle = newStreamH
        segmentsSinceRestart.set(0)
        audioFedSinceRestartMs.set(0L)
        restartRequested.set(false)
        prevLines.clear()
        firedCompletionIds.clear()
        Timber.i(
            "[STATE] feedConsumer.restart | reason=%s newStreamHandle=%d",
            reason, newStreamH,
        )
        return newStreamH
    }

    /**
     * Parses a [nativeTranscribeStream] JSON payload and fires [StreamingEventListener]
     * callbacks for any lines that are new or have changed since [prevLines].
     *
     * Must be called only from [feedDispatcher] (for single-threaded [prevLines] access).
     */
    private fun dispatchTranscriptEvents(json: String) {
        if (json.isBlank()) return

        val lineObjects = try {
            Json.parseToJsonElement(json).jsonObject["lines"]?.jsonArray ?: return
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] dispatchTranscriptEvents | parse_fail json_prefix=%s", json.take(80))
            return
        }
        for (lineEl in lineObjects) {
            try {
                val lineObj = lineEl.jsonObject
                val id = lineObj["id"]?.jsonPrimitive?.longOrNull ?: continue
                val text = lineObj["text"]?.jsonPrimitive?.contentOrNull ?: continue
                val isComplete = lineObj["complete"]?.jsonPrimitive?.booleanOrNull ?: continue
                val latencyMs = lineObj["latency"]?.jsonPrimitive?.int ?: 0
                val prev = prevLines[id]

                when {
                    prev == null -> {
                        // Brand-new line
                        if (firstLineStartedTimeMs == 0L) {
                            firstLineStartedTimeMs = System.currentTimeMillis()
                            Timber.i("[VOICE] nativeEngine.lineStarted | lineId=%d ttft_start_ms=0", id)
                        } else {
                            Timber.d("[VOICE] nativeEngine.lineStarted | lineId=%d", id)
                        }
                        eventListener?.onLineStarted(id, text)
                        if (text.isNotEmpty()) {
                            eventListener?.onLineTextChanged(id, buildPartialTranscript(text))
                        }
                    }
                    text != prev.text -> {
                        Timber.d(
                            "[VOICE] nativeEngine.lineTextChanged | lineId=%d partialLen=%d",
                            id, text.length,
                        )
                        eventListener?.onLineTextChanged(id, buildPartialTranscript(text))
                    }
                }

                if (isComplete && !firedCompletionIds.contains(id)) {
                    firedCompletionIds.add(id)
                    val trimmed = text.trim()
                    if (trimmed.isNotEmpty()) {
                        synchronized(completedLinesLock) {
                            if (completedTranscript.isNotEmpty()) completedTranscript.append(' ')
                            completedTranscript.append(trimmed)
                            completedLineCount++
                        }
                    }
                    val fullText = buildFullTranscript()
                    val segments = segmentsSinceRestart.incrementAndGet()
                    if (segments >= maxSegmentsBeforeRestart) restartRequested.set(true)

                    Timber.i(
                        "[VOICE] nativeEngine.lineCompleted | lineId=%d latency_ms=%d lineLen=%d totalLen=%d segmentsSinceRestart=%d",
                        id, latencyMs, trimmed.length, fullText.length, segments,
                    )
                    eventListener?.onLineCompleted(id, trimmed, fullText)
                }

                prevLines[id] = PrevLineState(text = text, isComplete = isComplete)
            } catch (e: Exception) {
                Timber.e(e, "[ERROR] dispatchTranscriptEvents | malformed_line json_prefix=%s", lineEl.toString().take(80))
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildFullTranscript(): String =
        synchronized(completedLinesLock) { completedTranscript.toString().trim() }

    private fun buildPartialTranscript(currentLineText: String): String {
        val completed = buildFullTranscript()
        val current = currentLineText.trim()
        return when {
            completed.isEmpty() -> current
            current.isEmpty() -> completed
            else -> "$completed $current"
        }
    }

    private data class PrevLineState(val text: String, val isComplete: Boolean)

    companion object {
        private const val SAMPLE_RATE = SileroVadDetector.SAMPLE_RATE

        /** ~1.9 s buffer at 30 ms/chunk (480 samples). Absorbs inference jitter. */
        private const val AUDIO_CHANNEL_CAPACITY = 64

        /** 480 samples @ 16 kHz = 30 ms. Matches Silero VAD chunk size. */
        private const val CHUNK_DURATION_MS = 30.0

        /** Max drain wait in [stopStream] before cancelling the feed consumer. */
        private const val DRAIN_TIMEOUT_MS = 2_000L

        /**
         * Completed lines before a stop/start restart cycle.
         * Flushes KV-cache state to prevent accuracy degradation in long sessions.
         *
         * Base value is 30; scaled by device tier (low-end devices restart more frequently
         * to manage memory pressure, high-end devices can sustain longer contexts).
         */
        private const val MAX_SEGMENTS_BEFORE_RESTART_BASE = 30

        /**
         * Max audio fed (ms) before requesting a restart.
         * Guards against perpetual KV-cache accumulation in single long utterances.
         * 60 s keeps the context window fresh; research shows streaming ASR accuracy
         * degrades noticeably beyond ~60–90 s of continuous KV-cache accumulation.
         *
         * Base value is 60_000 ms; scaled by device tier.
         */
        private const val MAX_AUDIO_BEFORE_RESTART_MS_BASE = 60_000L

        /** Device tier detection for scaling restart thresholds. */
        private val deviceTier: DeviceTier by lazy { detectDeviceTier() }

        /** Scaled max segments based on device tier. */
        private val maxSegmentsBeforeRestart: Int
            get() = when (deviceTier) {
                DeviceTier.LOW -> (MAX_SEGMENTS_BEFORE_RESTART_BASE * 0.7).toInt()  // 21
                DeviceTier.MID -> MAX_SEGMENTS_BEFORE_RESTART_BASE              // 30
                DeviceTier.HIGH -> (MAX_SEGMENTS_BEFORE_RESTART_BASE * 1.5).toInt() // 45
            }

        /** Scaled max audio duration based on device tier. */
        private val maxAudioBeforeRestartMs: Long
            get() = when (deviceTier) {
                DeviceTier.LOW -> (MAX_AUDIO_BEFORE_RESTART_MS_BASE * 0.75).toLong() // 45s
                DeviceTier.MID -> MAX_AUDIO_BEFORE_RESTART_MS_BASE                // 60s
                DeviceTier.HIGH -> (MAX_AUDIO_BEFORE_RESTART_MS_BASE * 1.5).toLong() // 90s
            }

        /** Device capability tier for adaptive thresholds. */
        private enum class DeviceTier { LOW, MID, HIGH }

        /** Detect device tier based on available processors and memory. */
        private fun detectDeviceTier(): DeviceTier {
            val processors = Runtime.getRuntime().availableProcessors()
            val maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024) // MB
            return when {
                processors <= 4 || maxMemory < 256 -> DeviceTier.LOW
                processors >= 8 && maxMemory >= 512 -> DeviceTier.HIGH
                else -> DeviceTier.MID
            }
        }
    }
}
