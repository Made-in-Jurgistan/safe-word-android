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
    private var _insertedCount: Int = 0
    private var _commandTriggered: Boolean = false
    private var _draftEditedByUser: Boolean = false

    /** The current live (incomplete) text from the streaming engine. */
    val liveText: String get() = synchronized(lock) { _liveText }

    /** Number of completed lines already inserted into the target text field. */
    val insertedCount: Int get() = synchronized(lock) { _insertedCount }

    /** Whether a voice command was detected during this streaming session. */
    val commandTriggered: Boolean get() = synchronized(lock) { _commandTriggered }

    /** Whether the user has manually edited the draft text. */
    val draftEditedByUser: Boolean get() = synchronized(lock) { _draftEditedByUser }

    /** Reset all state for a new recording session. */
    fun reset() {
        synchronized(lock) {
            _liveText = ""
            _completedLines.clear()
            _insertedCount = 0
            _commandTriggered = false
            _draftEditedByUser = false
        }
    }

    fun setLiveText(text: String) {
        synchronized(lock) { _liveText = text }
    }

    fun addCompletedLine(line: String) {
        synchronized(lock) { _completedLines.add(line) }
    }

    fun incrementInsertedCount() {
        synchronized(lock) { _insertedCount++ }
    }

    fun markCommandTriggered() {
        synchronized(lock) { _commandTriggered = true }
    }

    fun markDraftEditedByUser() {
        synchronized(lock) { _draftEditedByUser = true }
    }

    fun clearDraftEditedByUser() {
        synchronized(lock) { _draftEditedByUser = false }
    }

    /** Join all completed lines into a single trimmed string. */
    fun completedText(): String = synchronized(lock) {
        _completedLines.joinToString(" ").trim()
    }

    /** Build the current draft text by combining completed lines and the live partial. */
    fun buildDraftText(): String = synchronized(lock) {
        val completed = _completedLines.joinToString(" ").trim()
        val live = _liveText.trim()
        listOf(completed, live)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
}
