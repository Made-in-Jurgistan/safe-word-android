package com.safeword.android.transcription

/**
 * Encapsulates all mutable state related to streaming text accumulation.
 *
 * Consolidates the previously scattered `@Volatile` fields and `synchronized`
 * blocks into a single class with a consistent locking strategy (all
 * mutations go through `synchronized(lock)`).
 *
 * Thread-safe: all public methods synchronize on the internal [lock].
 */
class StreamingTextState {

    private val lock = Any()

    private var _liveText: String = ""
    private val _completedLines: MutableList<String> = mutableListOf()
    private var _completedTextCache: String = ""
    private var _completedTextCacheDirty: Boolean = true
    private var _commandTriggered: Boolean = false
    private var _draftEditedByUser: Boolean = false
    private var _autoStopSuppressed: Boolean = false
    private val _wordTimestamps: MutableList<Pair<Long, Long>> = mutableListOf()
    private val _wordConfidences: MutableList<Float> = mutableListOf()

    /** The current live (incomplete) text from the streaming engine. */
    val liveText: String get() = synchronized(lock) { _liveText }

    /** Whether a voice command was detected during this streaming session. */
    val commandTriggered: Boolean get() = synchronized(lock) { _commandTriggered }

    /** Whether the user has manually edited the draft text. */
    val draftEditedByUser: Boolean get() = synchronized(lock) { _draftEditedByUser }

    /**
     * Whether silence-based auto-stop should be skipped because the user is
     * actively interacting with the draft field (focused or editing). Cleared
     * only by [reset]; once the user has reached for the draft they must tap
     * the mic to commit.
     */
    val autoStopSuppressed: Boolean get() = synchronized(lock) { _autoStopSuppressed }

    /** Word-level timestamps from the SDK. */
    val wordTimestamps: List<Pair<Long, Long>> get() = synchronized(lock) { _wordTimestamps.toList() }

    /** Per-word confidence scores from the SDK. */
    val wordConfidences: List<Float> get() = synchronized(lock) { _wordConfidences.toList() }

    /** Reset all state for a new recording session. */
    fun reset() {
        synchronized(lock) {
            _liveText = ""
            _completedLines.clear()
            _completedTextCache = ""
            _completedTextCacheDirty = true
            _commandTriggered = false
            _draftEditedByUser = false
            _autoStopSuppressed = false
            _wordTimestamps.clear()
            _wordConfidences.clear()
        }
    }

    fun setLiveText(text: String) {
        synchronized(lock) { _liveText = text }
    }

    fun addCompletedLine(line: String) {
        synchronized(lock) {
            _completedLines.add(line)
            _completedTextCacheDirty = true
        }
    }

    fun addWordData(timestamps: List<Pair<Long, Long>>, confidences: List<Float>) {
        synchronized(lock) {
            _wordTimestamps.addAll(timestamps)
            _wordConfidences.addAll(confidences)
        }
    }

    /**
     * Remove and return the most recently added completed line, or null if none.
     * Used when a multi-line command is recognised and the just-added text needs
     * to be retracted before the command is executed.
     */
    fun removeLastCompletedLine(): String? = synchronized(lock) {
        if (_completedLines.isEmpty()) null
        else {
            _completedTextCacheDirty = true
            _completedLines.removeAt(_completedLines.size - 1)
        }
    }

    fun markCommandTriggered() {
        synchronized(lock) { _commandTriggered = true }
    }

    fun markDraftEditedByUser() {
        synchronized(lock) {
            _draftEditedByUser = true
            _autoStopSuppressed = true
        }
    }

    fun clearDraftEditedByUser() {
        synchronized(lock) { _draftEditedByUser = false }
    }

    /**
     * Suppress silence-based auto-stop. Called when the user focuses the draft
     * field — they have signalled an intent to edit and shouldn't lose the
     * recording session to a silence timer.
     */
    fun suppressAutoStop() {
        synchronized(lock) { _autoStopSuppressed = true }
    }

    /** Join all completed lines into a single trimmed string. Cached until mutated. */
    fun completedText(): String = synchronized(lock) {
        if (_completedTextCacheDirty) {
            _completedTextCache = _completedLines.joinToString(" ").trim()
            _completedTextCacheDirty = false
        }
        _completedTextCache
    }

    /** Build the current draft text by combining completed lines and the live partial. */
    fun buildDraftText(): String = synchronized(lock) {
        val completed = completedText()
        val live = _liveText.trim()
        listOf(completed, live)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
