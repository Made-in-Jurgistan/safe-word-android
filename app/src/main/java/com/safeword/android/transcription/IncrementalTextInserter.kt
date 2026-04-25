package com.safeword.android.transcription

import com.safeword.android.di.ApplicationScope
import com.safeword.android.di.IoDispatcher
import com.safeword.android.service.AccessibilityStateHolder
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalMonitor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns all text-insertion state and pipeline for a transcription session.
 *
 * Responsibilities:
 * - Thread-safe streaming and inserted text buffer management.
 * - Incremental diff + post-processing + AccessibilityService insertion per completed line.
 * - Final transcript post-processing (called when recording stops).
 * - SymSpell warm-up delegation.
 *
 * Extracted from [TranscriptionCoordinator] to remove [ConfusionSetCorrector],
 * [ThermalMonitor], and vocabulary dependencies from the coordinator.
 */
@Singleton
class IncrementalTextInserter @Inject constructor(
    private val confusionSetCorrector: ConfusionSetCorrector,
    private val vocabularyObserver: VocabularyObserver,
    private val thermalMonitor: ThermalMonitor,
    private val accessibilityStateHolder: AccessibilityStateHolder,
    private val performanceMonitor: PerformanceMonitor,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val streamingTextLock = Any()
    private val streamingTextBuffer = StringBuilder()

    private val insertedTextLock = Any()
    private val insertedTextBuffer = StringBuilder()

    /** Full transcript text that has already been incrementally inserted this session. */
    @Volatile private var lastInsertedFullText = ""

    /**
     * Length of the text field content at session start.
     * Used so [replaceSessionText] preserves text the user had before recording.
     */
    @Volatile private var sessionStartOffset: Int = 0

    /**
     * Raw dictation text accumulated across all non-command segments this session.
     * Rebuilt on every [incrementalInsert] call so the full pipeline can run on the
     * combined cross-segment text, enabling cross-segment self-repair.
     * Capped at [MAX_ACCUMULATED_CHARS] to prevent degenerate growth in very long sessions.
     */
    private var displayableRawText: String = ""

    private companion object {
        val MAX_ACCUMULATED_CHARS get() = OptimalParameters.MAX_ACCUMULATED_CHARS
    }

    /** Result returned by [postProcessFull]. */
    data class FullProcessResult(
        val cleanedText: String,
        val matchedVocab: List<String>,
    )

    /**
     * Eagerly warm the SymSpell dictionary while the user speaks.
     * Fire-and-forget; dispatches to IO.
     */
    fun warmSymSpell() {
        scope.launch(ioDispatcher) { confusionSetCorrector.initSymSpell() }
    }

    /**
     * Reset all buffers for a new recording session.
     * Call before [com.safeword.android.audio.AudioRecorder.record] starts.
     */
    fun resetForSession() {
        synchronized(streamingTextLock) { streamingTextBuffer.clear() }
        synchronized(insertedTextLock) { insertedTextBuffer.clear() }
        lastInsertedFullText = ""
        displayableRawText = ""
        // Snapshot the field length so replaceSessionText can preserve pre-session text.
        val currentFieldLength = accessibilityStateHolder.getCurrentFocusedFieldText()?.length
        if (currentFieldLength == null) {
            Timber.w("[WARN] IncrementalTextInserter.resetForSession | a11y service inactive — sessionStartOffset=0, inserted text may overlap pre-existing content")
        }
        sessionStartOffset = currentFieldLength ?: 0
    }

    /**
     * Update the streaming text buffer with the latest partial transcription.
     * Thread-safe; called from the Moonshine event listener thread.
     */
    fun updateStreamingText(text: String) {
        synchronized(streamingTextLock) {
            streamingTextBuffer.clear()
            streamingTextBuffer.append(text)
        }
    }

    /** Clear the streaming text buffer (e.g. on session stop). */
    fun clearStreamingText() {
        synchronized(streamingTextLock) { streamingTextBuffer.clear() }
    }

    /** Returns a snapshot of the already-inserted text for the current session. */
    fun getInsertedText(): String = synchronized(insertedTextLock) { insertedTextBuffer.toString() }

    /**
     * Advance the "already-inserted" watermark to [fullText] without actually
     * inserting anything. Call when a voice command consumes a completed line so
     * the next [incrementalInsert] delta does not re-insert the command text.
     */
    fun skipCommandText(fullText: String) {
        lastInsertedFullText = fullText
    }

    /**
     * Returns the already-inserted text and clears all insertion tracking state.
     * Called once at the end of a session before reading the final engine transcript.
     */
    fun consumeInsertedText(): String = synchronized(insertedTextLock) {
        val text = insertedTextBuffer.toString()
        insertedTextBuffer.clear()
        lastInsertedFullText = ""
        displayableRawText = ""
        text
    }

    /**
     * Compute the diff between [fullTranscriptSoFar] and already-inserted text,
     * post-process the new portion, and insert it via [AccessibilityStateHolder].
     *
     * Pipeline: preProcess → normalizePostPreProcess → confusion correction →
     * whitespace format → vocabulary layer → insert.
     *
     * Must be called on a single-threaded dispatcher to serialise buffer access
     * and guarantee ordered insertion.
     *
     * @param fullTranscriptSoFar Accumulated transcript text including all completed lines.
     * @param correctionContext Text-field context snapshot for vocabulary correction.
     */
    suspend fun incrementalInsert(fullTranscriptSoFar: String, correctionContext: InputContextSnapshot) {
        val alreadyInserted = lastInsertedFullText
        val prefixMatch = fullTranscriptSoFar.startsWith(alreadyInserted)
        val newRaw = if (prefixMatch) {
            fullTranscriptSoFar.removePrefix(alreadyInserted).trimStart()
        } else {
            // Prefix mismatch — fall back to full text (shouldn't occur in normal operation).
            fullTranscriptSoFar.trim()
        }
        if (newRaw.isBlank()) return

        // Accumulate raw dictation across segments so the full pipeline can process
        // the combined text. This enables cross-segment self-repair (e.g. "I'm gonna
        // go shopping. Oh no wait... to the beach" spanning two Moonshine line completions).
        // On prefix mismatch the transcript has completely changed — reset the accumulator
        // so stale prior content is not prepended to the new stream.
        displayableRawText = if (!prefixMatch || displayableRawText.isBlank()) newRaw
                             else "$displayableRawText $newRaw"

        // Safety cap: trim oldest words to bound rule-stage processing in long sessions.
        if (displayableRawText.length > MAX_ACCUMULATED_CHARS) {
            val trimFrom = displayableRawText.length - MAX_ACCUMULATED_CHARS
            val spaceIdx = displayableRawText.indexOf(' ', trimFrom)
            if (spaceIdx > 0) displayableRawText = displayableRawText.substring(spaceIdx + 1)
        }

        val rawToProcess = displayableRawText

        val pipelineStart = System.currentTimeMillis()
        val preProcessed = ContentNormalizer.preProcess(rawToProcess)
        val postNorm = ContentNormalizer.normalizePostPreProcess(preProcessed)
        val confusionStart = System.currentTimeMillis()
        val corrected = confusionSetCorrector.apply(
            postNorm,
            correctionContext,
            isIncremental = true,
            locale = Locale.getDefault(),
            previousText = "",
            thermalTier = thermalMonitor.thermalTier,
        )
        performanceMonitor.recordLatency(
            PerformanceMonitor.Stage.CONFUSION_CORRECTOR,
            System.currentTimeMillis() - confusionStart,
        )
        val normalized = TextFormatter.format(corrected)

        val (cleaned, incrementalMatched) = confusionSetCorrector.applyVocabularyLayer(
            normalized, vocabularyObserver.confirmedVocabulary.value, correctionContext,
            isIncremental = true,
            thermalTier = thermalMonitor.thermalTier,
        )
        if (cleaned.isBlank()) return

        vocabularyObserver.recordVocabUsed(incrementalMatched)

        // Format the full processed session text and replace the session portion of the field.
        // This overwrites any previously-inserted partial text so cross-segment self-repair
        // (e.g. "oh no wait" marker resolved once the repair target arrives) is reflected live.
        val sessionText = TextFormatter.format(cleaned).trimEnd()
        val success = accessibilityStateHolder.replaceSessionText(sessionStartOffset, sessionText)
        if (success) {
            lastInsertedFullText = fullTranscriptSoFar
            synchronized(insertedTextLock) {
                insertedTextBuffer.clear()
                insertedTextBuffer.append(sessionText)
            }
            Timber.i("[VOICE] IncrementalTextInserter.insert | sessionChars=%d offset=%d",
                sessionText.length, sessionStartOffset)
        } else {
            // Roll back: prevents stale accumulation if next call retries with broader text.
            displayableRawText = ""
            Timber.w("[VOICE] IncrementalTextInserter.insert | replacement failed len=%d",
                sessionText.length)
        }
        performanceMonitor.recordLatency(
            PerformanceMonitor.Stage.TOTAL_PIPELINE,
            System.currentTimeMillis() - pipelineStart,
        )
    }

    /**
     * Post-process a full raw transcript for final insertion (called at session end).
     *
     * Pipeline: preProcess → normalizePostPreProcess → confusion correction →
     * whitespace format → vocabulary layer.
     *
     * Returns null if the result is blank after processing — callers should treat this
     * as a "no speech detected" terminal condition.
     *
     * @param rawText  Raw transcript text from the STT engine.
     * @param correctionContext Text-field context snapshot for vocabulary correction.
     */
    suspend fun postProcessFull(rawText: String, correctionContext: InputContextSnapshot): FullProcessResult? {
        val preProcessed = ContentNormalizer.preProcess(rawText)
        val postNorm = ContentNormalizer.normalizePostPreProcess(preProcessed)
        val confusionStart = System.currentTimeMillis()
        val corrected = confusionSetCorrector.apply(
            postNorm,
            correctionContext,
            locale = Locale.getDefault(),
            previousText = "",
            thermalTier = thermalMonitor.thermalTier,
        )
        performanceMonitor.recordLatency(
            PerformanceMonitor.Stage.CONFUSION_CORRECTOR,
            System.currentTimeMillis() - confusionStart,
        )
        val postFormatted = TextFormatter.format(corrected)
        if (postFormatted.isBlank()) return null

        val (cleanedText, finalMatched) = confusionSetCorrector.applyVocabularyLayer(
            postFormatted, vocabularyObserver.confirmedVocabulary.value, correctionContext,
            thermalTier = thermalMonitor.thermalTier,
        )
        vocabularyObserver.recordVocabUsed(finalMatched)

        return FullProcessResult(cleanedText, finalMatched)
    }
}
