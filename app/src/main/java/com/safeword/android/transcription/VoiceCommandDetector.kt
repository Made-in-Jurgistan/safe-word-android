package com.safeword.android.transcription

import timber.log.Timber

/**
 * Describes the type of input field that currently has focus.
 * Used to gate commands that are only appropriate in certain contexts.
 */
enum class FieldType {
    /** Generic text entry — permits all commands. */
    TEXT,
    /** Messaging composer (chat, SMS) — permits Send, formatting, nav. */
    MESSAGING,
    /** Browser URL/search bar — permits search and nav. */
    SEARCH,
    /** Password or sensitive field — command detection suppressed. */
    PASSWORD,
    /** No focused field known. */
    UNKNOWN,
}

/**
 * Phase 1 — Voice Command Detection (pre-text processing).
 *
 * Inspects raw ASR output for spoken voice commands. If the entire utterance
 * matches a known command, returns a [VoiceCommandResult.Command] with early exit —
 * the text never enters content normalization or formatting.
 *
 * Commands are matched as full-utterance phrases (after trim + lowercase) to avoid
 * false positives inside normal dictation. Optionally a [FieldType] hint can gate
 * commands that are only safe in certain contexts.
 *
 * Fuzzy matching (Levenshtein ≤ 2) is applied only when:
 *   - The normalized candidate has ≤ 6 tokens, AND
 *   - At least one token is a known command stem.
 */
object VoiceCommandDetector {

    // -- Custom user-defined commands -----------------------------------------
    // Updated at runtime via updateCustomCommands(). Keyed by trigger phrase (lowercase).
    @Volatile
    private var customCommandMap: Map<String, VoiceAction> = emptyMap()

    /**
     * Update the custom command map from user-defined [CustomVoiceCommand] list.
     * Called by [TranscriptionCoordinator] when settings change.
     */
    fun updateCustomCommands(commands: List<CustomVoiceCommand>) {
        val map = mutableMapOf<String, VoiceAction>()
        for (cmd in commands) {
            if (!cmd.enabled) continue
            val action = cmd.toVoiceAction() ?: continue
            for (trigger in cmd.triggers()) {
                map[trigger] = action
            }
        }
        customCommandMap = map
        Timber.d("[VOICE] VoiceCommandDetector.updateCustomCommands | %d triggers from %d commands",
            map.size, commands.size)
    }

    // -- Command vocabulary ---------------------------------------------------
    // Keyed by canonical spoken phrase (lowercase). Values are the action to execute.

    private val COMMAND_MAP: Map<String, VoiceAction> = buildMap {
        // Deletion
        put("delete that", VoiceAction.DeleteSelection)
        put("delete this", VoiceAction.DeleteSelection)
        put("erase that", VoiceAction.DeleteSelection)
        put("remove that", VoiceAction.DeleteSelection)
        put("delete last word", VoiceAction.DeleteLastWord)
        put("erase last word", VoiceAction.DeleteLastWord)
        put("delete last sentence", VoiceAction.DeleteLastSentence)
        put("delete sentence", VoiceAction.DeleteLastSentence)
        put("erase last sentence", VoiceAction.DeleteLastSentence)
        put("backspace", VoiceAction.Backspace)
        put("delete last paragraph", VoiceAction.DeleteLastParagraph)
        put("erase last paragraph", VoiceAction.DeleteLastParagraph)

        // Undo / redo
        put("undo", VoiceAction.Undo)
        put("undo that", VoiceAction.Undo)
        put("scratch that", VoiceAction.Undo)
        put("take that back", VoiceAction.Undo)
        put("redo", VoiceAction.Redo)
        put("redo that", VoiceAction.Redo)

        // Selection
        put("select all", VoiceAction.SelectAll)
        put("highlight all", VoiceAction.SelectAll)
        put("select last word", VoiceAction.SelectLastWord)
        put("select last sentence", VoiceAction.SelectLastSentence)
        put("select last paragraph", VoiceAction.SelectLastParagraph)
        put("select word", VoiceAction.SelectCurrentWord)
        put("select this word", VoiceAction.SelectCurrentWord)
        put("select current word", VoiceAction.SelectCurrentWord)
        put("select sentence", VoiceAction.SelectCurrentSentence)
        put("select this sentence", VoiceAction.SelectCurrentSentence)
        put("select current sentence", VoiceAction.SelectCurrentSentence)
        put("select line", VoiceAction.SelectCurrentLine)
        put("select this line", VoiceAction.SelectCurrentLine)
        put("select current line", VoiceAction.SelectCurrentLine)
        put("select to beginning", VoiceAction.SelectToStart)
        put("select to start", VoiceAction.SelectToStart)
        put("select to end", VoiceAction.SelectToEnd)

        // Clipboard
        put("copy that", VoiceAction.Copy)
        put("copy", VoiceAction.Copy)
        put("copy this", VoiceAction.Copy)
        put("cut that", VoiceAction.Cut)
        put("cut this", VoiceAction.Cut)
        put("cut", VoiceAction.Cut)
        put("paste", VoiceAction.Paste)
        put("paste that", VoiceAction.Paste)
        put("paste here", VoiceAction.Paste)

        // Navigation
        put("new line", VoiceAction.NewLine)
        put("newline", VoiceAction.NewLine)
        put("next line", VoiceAction.NewLine)
        put("go to next line", VoiceAction.NewLine)
        put("enter", VoiceAction.NewLine)
        put("press enter", VoiceAction.NewLine)
        put("hit enter", VoiceAction.NewLine)
        put("new paragraph", VoiceAction.NewParagraph)
        put("next paragraph", VoiceAction.NewParagraph)
        put("move to end", VoiceAction.MoveCursorToEnd)
        put("go to end", VoiceAction.MoveCursorToEnd)
        put("jump to end", VoiceAction.MoveCursorToEnd)
        put("move to beginning", VoiceAction.MoveCursorToStart)
        put("go to beginning", VoiceAction.MoveCursorToStart)
        put("go to start", VoiceAction.MoveCursorToStart)
        put("jump to start", VoiceAction.MoveCursorToStart)
        put("move to start", VoiceAction.MoveCursorToStart)
        put("go to start of line", VoiceAction.MoveToLineStart)
        put("go to end of line", VoiceAction.MoveToLineEnd)
        put("move to start of line", VoiceAction.MoveToLineStart)
        put("move to end of line", VoiceAction.MoveToLineEnd)
        put("beginning of line", VoiceAction.MoveToLineStart)
        put("end of line", VoiceAction.MoveToLineEnd)

        // Formatting
        put("capitalize that", VoiceAction.CapitalizeSelection)
        put("uppercase that", VoiceAction.UppercaseSelection)
        put("make that uppercase", VoiceAction.UppercaseSelection)
        put("make that upper case", VoiceAction.UppercaseSelection)
        put("lowercase that", VoiceAction.LowercaseSelection)
        put("make that lowercase", VoiceAction.LowercaseSelection)
        put("make that lower case", VoiceAction.LowercaseSelection)
        put("bold", VoiceAction.BoldSelection)
        put("bold that", VoiceAction.BoldSelection)
        put("make that bold", VoiceAction.BoldSelection)
        put("italic", VoiceAction.ItalicSelection)
        put("italic that", VoiceAction.ItalicSelection)
        put("italicize that", VoiceAction.ItalicSelection)
        put("make that italic", VoiceAction.ItalicSelection)
        put("underline", VoiceAction.UnderlineSelection)
        put("underline that", VoiceAction.UnderlineSelection)
        put("make that underline", VoiceAction.UnderlineSelection)
        put("make that underlined", VoiceAction.UnderlineSelection)

        // Spacing / punctuation shortcuts
        put("space", VoiceAction.InsertText(" "))
        put("add a space", VoiceAction.InsertText(" "))
        put("type a space", VoiceAction.InsertText(" "))
        put("tab", VoiceAction.InsertText("\t"))
        put("period", VoiceAction.InsertText(". "))
        put("full stop", VoiceAction.InsertText(". "))
        put("dot", VoiceAction.InsertText(". "))
        put("comma", VoiceAction.InsertText(","))
        put("add a comma", VoiceAction.InsertText(","))
        put("question mark", VoiceAction.InsertText("?"))
        put("exclamation point", VoiceAction.InsertText("!"))
        put("exclamation mark", VoiceAction.InsertText("!"))
        put("colon", VoiceAction.InsertText(":"))
        put("semicolon", VoiceAction.InsertText(";"))
        put("hyphen", VoiceAction.InsertText("-"))
        put("dash", VoiceAction.InsertText(" — "))
        put("ellipsis", VoiceAction.InsertText("…"))
        put("dot dot dot", VoiceAction.InsertText("…"))
        put("open bracket", VoiceAction.InsertText("["))
        put("close bracket", VoiceAction.InsertText("]"))
        put("open square bracket", VoiceAction.InsertText("["))
        put("close square bracket", VoiceAction.InsertText("]"))
        put("open parenthesis", VoiceAction.InsertText("("))
        put("close parenthesis", VoiceAction.InsertText(")"))
        put("open paren", VoiceAction.InsertText("("))
        put("close paren", VoiceAction.InsertText(")"))
        put("open curly", VoiceAction.InsertText("{"))
        put("close curly", VoiceAction.InsertText("}"))
        put("open brace", VoiceAction.InsertText("{"))
        put("close brace", VoiceAction.InsertText("}"))
        put("open quote", VoiceAction.InsertText("\""))
        put("close quote", VoiceAction.InsertText("\""))
        put("at sign", VoiceAction.InsertText("@"))
        put("at symbol", VoiceAction.InsertText("@"))
        put("hashtag", VoiceAction.InsertText("#"))
        put("hash", VoiceAction.InsertText("#"))
        put("number sign", VoiceAction.InsertText("#"))
        put("ampersand", VoiceAction.InsertText("&"))
        put("and sign", VoiceAction.InsertText("&"))
        put("asterisk", VoiceAction.InsertText("*"))
        put("star symbol", VoiceAction.InsertText("*"))

        // Session control
        put("send", VoiceAction.Send)
        put("send that", VoiceAction.Send)
        put("send message", VoiceAction.Send)
        put("send it", VoiceAction.Send)
        put("submit", VoiceAction.Submit)
        put("submit that", VoiceAction.Submit)
        put("confirm", VoiceAction.Submit)
        put("clear", VoiceAction.ClearAll)
        put("clear all", VoiceAction.ClearAll)
        put("clear everything", VoiceAction.ClearAll)
        put("erase all", VoiceAction.ClearAll)
        put("erase everything", VoiceAction.ClearAll)
        put("go back", VoiceAction.GoBack)
        put("navigate back", VoiceAction.GoBack)
        put("stop listening", VoiceAction.StopListening)
        put("stop recording", VoiceAction.StopListening)
        put("stop", VoiceAction.StopListening)
        put("stop dictation", VoiceAction.StopListening)
        put("done", VoiceAction.StopListening)
        put("open settings", VoiceAction.OpenSettings)
        put("dictation settings", VoiceAction.OpenSettings)
        put("safe word settings", VoiceAction.OpenSettings)
        put("scroll up", VoiceAction.ScrollUp)
        put("scroll down", VoiceAction.ScrollDown)
        put("page up", VoiceAction.ScrollUp)
        put("page down", VoiceAction.ScrollDown)
        put("dismiss", VoiceAction.Dismiss)
        put("dismiss keyboard", VoiceAction.Dismiss)
        put("hide keyboard", VoiceAction.Dismiss)
        put("close keyboard", VoiceAction.Dismiss)
    }

    private val WHITESPACE_PATTERN = Regex("\\s+")
    private val WAKE_WORD_PREFIX = Regex("^(?:ok\\s+)?safe\\s*word\\b[,:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val COMMAND_MODE_PREFIX = Regex("^(?:command(?:\\s+mode)?|dictation\\s+command)\\b[,:\\-]?\\s*", RegexOption.IGNORE_CASE)
    private val POLITE_PREFIX = Regex("^(?:please|can\\s+you|could\\s+you|would\\s+you|will\\s+you)\\b\\s*", RegexOption.IGNORE_CASE)
    private val POLITE_SUFFIX = Regex("\\b(?:please|thanks|thank\\s+you)\\s*$", RegexOption.IGNORE_CASE)

    /** Max raw length that could plausibly be a command (with wake-word + polite wrappers). */
    private const val MAX_COMMAND_RAW_LENGTH = 150

    private val COMMAND_STEMS: Set<String> = setOf(
        "delete", "undo", "redo", "select", "copy", "cut", "paste",
        "new", "capitalize", "uppercase", "lowercase", "send", "clear",
        "back", "stop", "period", "comma", "question", "exclamation", "enter",
        "erase", "remove", "scratch", "highlight", "done", "dash", "colon",
        "semicolon", "hyphen", "dot",
        "replace", "change", "search", "find", "look", "google", "swap", "switch",
        "bold", "italic", "underline", "move", "jump", "scroll", "submit", "confirm",
        "dismiss", "settings", "page", "bracket", "parenthesis", "quote",
        "spell", "letter",
    )

    /** Parameterized replace patterns: group 1 = old text, group 2 = new text. */
    private val REPLACE_PATTERNS: List<Regex> = listOf(
        Regex("^replace (.+) with (.+)$"),
        Regex("^change (.+) to (.+)$"),
        Regex("^swap (.+) with (.+)$"),
        Regex("^switch (.+) with (.+)$"),
        Regex("^switch (.+) to (.+)$"),
        Regex("^find (.+) and replace with (.+)$"),
        Regex("^find (.+) and replace by (.+)$"),
    )

    /** Parameterized search patterns: group 1 = query. */
    private val SEARCH_PATTERNS: List<Regex> = listOf(
        Regex("^search for (.+)$"),
        Regex("^look up (.+)$"),
        Regex("^google (.+)$"),
        Regex("^search (.+)$"),
    )

    /** Word-targeting select/highlight patterns: group 1 = target word(s). */
    private val SELECT_WORD_PATTERNS: List<Regex> = listOf(
        Regex("^select (?:the (?:word )?)?(.+)$"),
        Regex("^highlight (?:the (?:word )?)?(.+)$"),
    )

    /** Word-targeting copy patterns: group 1 = target word(s). */
    private val COPY_WORD_PATTERNS: List<Regex> = listOf(
        Regex("^copy (?:the (?:word )?)?(.+)$"),
    )

    /** Word-targeting delete patterns: group 1 = target word(s). */
    private val DELETE_WORD_PATTERNS: List<Regex> = listOf(
        Regex("^delete (?:the (?:word )?)?(.+)$"),
        Regex("^erase (?:the (?:word )?)?(.+)$"),
        Regex("^remove (?:the (?:word )?)?(.+)$"),
    )

    /** Spell-mode patterns: group 1 = letters. */
    private val SPELL_PATTERNS: List<Regex> = listOf(
        Regex("^spell (.+)$"),
        Regex("^type out (.+)$"),
    )

    /** Single-letter insertion: "letter A", "type letter B", "the letter C". */
    private val LETTER_PATTERN = Regex("^(?:type )?(?:the )?letter ([a-z])$")

    /** NATO phonetic alphabet → letter mapping for spell mode. */
    private val NATO_ALPHABET: Map<String, Char> = mapOf(
        "alpha" to 'a', "alfa" to 'a', "bravo" to 'b', "charlie" to 'c',
        "delta" to 'd', "echo" to 'e', "foxtrot" to 'f', "golf" to 'g',
        "hotel" to 'h', "india" to 'i', "juliet" to 'j', "juliett" to 'j',
        "kilo" to 'k', "lima" to 'l', "mike" to 'm', "november" to 'n',
        "oscar" to 'o', "papa" to 'p', "quebec" to 'q', "romeo" to 'r',
        "sierra" to 's', "tango" to 't', "uniform" to 'u', "victor" to 'v',
        "whiskey" to 'w', "x-ray" to 'x', "xray" to 'x', "yankee" to 'y',
        "zulu" to 'z',
    )

    /**
     * Attempt to detect a voice command from raw ASR output.
     *
     * Detection priority:
     *  1. **Exact match** — O(1) hash lookup against [COMMAND_MAP] (zero latency).
     *  1.5. **Custom commands** — user-defined trigger phrases from [customCommandMap].
     *  2. **IntentRecognizer** (primary) — keyword-weighted semantic scoring that
     *     handles natural-language variations and paraphrased commands.
     *  3. **Levenshtein fuzzy** (fallback) — catches minor ASR typos in short utterances.
     *
     * @param rawText   The unprocessed transcription text.
     * @param fieldType The type of the currently focused field; used to gate commands.
     * @return [VoiceCommandResult.Command] if the utterance is a command (early exit),
     *         or [VoiceCommandResult.Text] if it should proceed to content normalization.
     */
    fun detect(rawText: String, fieldType: FieldType = FieldType.UNKNOWN): VoiceCommandResult {
        val trimmed = rawText.trim()
        if (trimmed.isEmpty()) return VoiceCommandResult.Text("")

        // Password fields — never intercept as commands.
        if (fieldType == FieldType.PASSWORD) return VoiceCommandResult.Text(rawText)

        // Fast-path: utterances longer than any plausible command skip detection entirely.
        if (trimmed.length > MAX_COMMAND_RAW_LENGTH) return VoiceCommandResult.Text(rawText)

        val normalized = normalizeCandidate(trimmed)

        // 1. Exact match — O(1) hash lookup, zero-cost fast path for canonical phrasing.
        val exactAction = COMMAND_MAP[normalized] ?: matchParameterizedCommand(normalized)
        if (exactAction != null) {
            val gated = gateActionForField(exactAction, fieldType)
            if (gated != null) {
                Timber.i("[VOICE] VoiceCommandDetector.detect | exact-command=\"%s\" action=%s fieldType=%s", normalized, gated, fieldType)
                return VoiceCommandResult.Command(gated)
            }
        }

        // 1.5. Custom user-defined commands — checked before IntentRecognizer so
        //       user overrides take priority over semantic scoring.
        val customAction = customCommandMap[normalized]
        if (customAction != null) {
            val gated = gateActionForField(customAction, fieldType)
            if (gated != null) {
                Timber.i("[VOICE] VoiceCommandDetector.detect | custom-command=\"%s\" action=%s fieldType=%s", normalized, gated, fieldType)
                return VoiceCommandResult.Command(gated)
            }
        }

        // 2. Semantic intent recognition (PRIMARY) — keyword-weighted scoring that
        //    handles natural-language variations ("please remove everything" → ClearAll,
        //    "erase the last word" → DeleteLastWord) which exact matching cannot cover.
        val intentResult = IntentRecognizer.recognize(normalized)
        if (intentResult != null) {
            val gated = gateActionForField(intentResult.action, fieldType)
            if (gated != null) {
                Timber.i("[VOICE] VoiceCommandDetector.detect | intent-command=\"%s\" action=%s confidence=%.3f fieldType=%s",
                    normalized, gated, intentResult.confidence, fieldType)
                return VoiceCommandResult.Command(gated, confidence = intentResult.confidence)
            }
        }

        // 3. Levenshtein fuzzy fallback — catches minor transcription typos in short
        //    utterances that have a recognizable command stem.
        val fuzzyAction = tryFuzzyMatch(normalized)
        if (fuzzyAction != null) {
            val gated = gateActionForField(fuzzyAction, fieldType)
            if (gated != null) {
                Timber.i("[VOICE] VoiceCommandDetector.detect | fuzzy-command=\"%s\" action=%s fieldType=%s", normalized, gated, fieldType)
                return VoiceCommandResult.Command(gated)
            }
        }

        logPotentialCommandMiss(trimmed)
        return VoiceCommandResult.Text(rawText)
    }

    /**
     * Gate actions that are inappropriate for certain field types.
     * Returns null if the action should be suppressed.
     */
    private fun gateActionForField(action: VoiceAction, fieldType: FieldType): VoiceAction? {
        return when {
            // Search actions only make sense in a search field.
            action is VoiceAction.SearchText && fieldType == FieldType.TEXT -> null
            // Send is only appropriate in messaging contexts; in TEXT it falls through.
            action is VoiceAction.Send && fieldType == FieldType.SEARCH -> null
            // Bold/italic/underline are formatting-only; password never reaches here anyway.
            action is VoiceAction.BoldSelection && fieldType == FieldType.SEARCH -> null
            action is VoiceAction.ItalicSelection && fieldType == FieldType.SEARCH -> null
            action is VoiceAction.UnderlineSelection && fieldType == FieldType.SEARCH -> null
            // Word-targeting commands don't make sense in search bars
            action is VoiceAction.SelectWord && fieldType == FieldType.SEARCH -> null
            action is VoiceAction.CopyWord && fieldType == FieldType.SEARCH -> null
            action is VoiceAction.DeleteWord && fieldType == FieldType.SEARCH -> null
            else -> action
        }
    }

    /**
     * Attempt fuzzy Levenshtein matching (distance ≤ 2) against the COMMAND_MAP.
     * Only applied when the normalized candidate has ≤ 6 tokens AND contains a command stem.
     */
    private fun tryFuzzyMatch(normalized: String): VoiceAction? {
        val tokens = normalized.split(' ')
        if (tokens.size > 6) return null
        val hasCommandStem = COMMAND_STEMS.any { stem -> tokens.any { it == stem } }
        if (!hasCommandStem) return null

        var bestAction: VoiceAction? = null
        var bestDist = 3 // exclusive upper bound

        for ((key, action) in COMMAND_MAP) {
            val dist = levenshtein(normalized, key)
            if (dist < bestDist) {
                bestDist = dist
                bestAction = action
            }
        }
        return bestAction
    }

    /** Iterative Levenshtein distance, capped at 3 for performance. */
    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val la = a.length
        val lb = b.length
        if (la == 0) return lb.coerceAtMost(3)
        if (lb == 0) return la.coerceAtMost(3)

        val prev = IntArray(lb + 1) { it }
        val curr = IntArray(lb + 1)

        for (i in 1..la) {
            curr[0] = i
            var rowMin = i
            for (j in 1..lb) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                curr[j] = minOf(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                if (curr[j] < rowMin) rowMin = curr[j]
            }
            if (rowMin >= 3) return 3
            curr.copyInto(prev)
        }
        return prev[lb].coerceAtMost(3)
    }

    private fun normalizeCandidate(text: String): String {
        var candidate = text.trim()
            .trimEnd('.', ',', '!', '?', ';', ':')
            .trim()

        var changed: Boolean
        do {
            changed = false

            val afterWakeWord = WAKE_WORD_PREFIX.replaceFirst(candidate, "")
            if (afterWakeWord != candidate) {
                candidate = afterWakeWord
                changed = true
            }

            val afterCommandMode = COMMAND_MODE_PREFIX.replaceFirst(candidate, "")
            if (afterCommandMode != candidate) {
                candidate = afterCommandMode
                changed = true
            }

            val afterPolitePrefix = POLITE_PREFIX.replaceFirst(candidate, "")
            if (afterPolitePrefix != candidate) {
                candidate = afterPolitePrefix
                changed = true
            }

            val afterPoliteSuffix = POLITE_SUFFIX.replaceFirst(candidate, "")
            if (afterPoliteSuffix != candidate) {
                candidate = afterPoliteSuffix
                changed = true
            }

            candidate = candidate.trimStart(',', ':', '-', ' ').trimEnd(',', ':', '-', ' ')
        } while (changed && candidate.isNotEmpty())

        var normalized = candidate
            .lowercase()
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        return normalized
    }

    /**
     * Try to match a parameterised voice command (replace or search) from the
     * normalised utterance. Returns null when no pattern matches.
     */
    private fun matchParameterizedCommand(normalized: String): VoiceAction? {
        for (pattern in REPLACE_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val oldText = match.groupValues[1].trim()
            val newText = match.groupValues[2].trim()
            if (oldText.isNotEmpty() && newText.isNotEmpty()) {
                return VoiceAction.ReplaceText(oldText, newText)
            }
        }
        for (pattern in SEARCH_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val query = match.groupValues[1].trim()
            if (query.isNotEmpty()) {
                return VoiceAction.SearchText(query)
            }
        }

        // Word-targeting select/highlight: "select hello", "highlight the word hello"
        for (pattern in SELECT_WORD_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val word = match.groupValues[1].trim()
            if (word.isNotEmpty() && !isExistingNonParameterizedCommand(word) && isValidWordTarget(word)) {
                return VoiceAction.SelectWord(word)
            }
        }

        // Word-targeting copy: "copy hello"
        for (pattern in COPY_WORD_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val word = match.groupValues[1].trim()
            if (word.isNotEmpty() && !isExistingNonParameterizedCommand(word) && isValidWordTarget(word)) {
                return VoiceAction.CopyWord(word)
            }
        }

        // Word-targeting delete: "delete hello", "erase the word hello"
        for (pattern in DELETE_WORD_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val word = match.groupValues[1].trim()
            if (word.isNotEmpty() && !isExistingNonParameterizedCommand(word) && isValidWordTarget(word)) {
                return VoiceAction.DeleteWord(word)
            }
        }

        // Spell mode: "spell A B C" → "abc", supports single letters, NATO alphabet, or spoken letters
        for (pattern in SPELL_PATTERNS) {
            val match = pattern.matchEntire(normalized) ?: continue
            val rawLetters = match.groupValues[1].trim()
            val spelled = parseSpelledLetters(rawLetters)
            if (spelled.isNotEmpty()) {
                return VoiceAction.SpellOut(spelled)
            }
        }

        // Single-letter insertion: "letter A", "type letter B"
        LETTER_PATTERN.matchEntire(normalized)?.let { match ->
            val letter = match.groupValues[1]
            return VoiceAction.InsertText(letter)
        }

        return null
    }

    /**
     * Check if a word-target candidate collides with a non-parameterized COMMAND_MAP suffix.
     * Prevents "select all" → SelectWord("all") instead of SelectAll.
     */
    private fun isExistingNonParameterizedCommand(suffix: String): Boolean {
        val knownSuffixes = setOf(
            "all", "that", "this", "last word", "last sentence", "last paragraph",
            "word", "this word", "current word", "sentence", "this sentence",
            "current sentence", "line", "this line", "current line",
            "to beginning", "to start", "to end",
        )
        return suffix in knownSuffixes
    }

    /**
     * Validate that a captured word-target is a plausible specific word reference
     * and not a sentence fragment. Targets must be 1–3 tokens and must not start
     * with common pronouns/determiners that signal a full sentence.
     */
    private fun isValidWordTarget(target: String): Boolean {
        val tokens = target.split(' ')
        if (tokens.size > 3) return false
        val sentenceStarters = setOf("that", "this", "it", "a", "an", "my", "your", "his", "her", "our", "their")
        if (tokens.first() in sentenceStarters) return false
        return true
    }

    /**
     * Parse spelled-out letters from various formats:
     * - Space-separated single letters: "a b c" → "abc"
     * - NATO phonetic alphabet: "alpha bravo charlie" → "abc"
     * - Mixed: "a bravo c" → "abc"
     */
    private fun parseSpelledLetters(raw: String): String {
        val tokens = raw.split(WHITESPACE_PATTERN)
        val sb = StringBuilder(tokens.size)
        for (token in tokens) {
            when {
                token.length == 1 && token[0].isLetter() -> sb.append(token[0].lowercaseChar())
                NATO_ALPHABET.containsKey(token) -> sb.append(NATO_ALPHABET[token])
                else -> return "" // unknown token — not a valid spelling sequence
            }
        }
        return sb.toString()
    }

    private fun logPotentialCommandMiss(trimmedText: String) {
        // Keep diagnostics low-noise: only short utterances that look command-like.
        if (trimmedText.length > 40) return
        val normalized = trimmedText.lowercase()
            .replace(Regex("[^a-z\\s]"), " ")
            .replace(WHITESPACE_PATTERN, " ")
            .trim()
        val hasCommandStem = COMMAND_STEMS.any { stem -> normalized.contains(stem) }
        if (hasCommandStem) {
            Timber.w("[VOICE] VoiceCommandDetector.detect | near-miss raw=\"%s\" normalized=\"%s\"", trimmedText, normalized)
        }
    }
}

/**
 * Result of Phase 1 voice command detection.
 */
sealed interface VoiceCommandResult {
    /** The utterance is a command — execute on InputConnection, skip text processing.
     *  @param confidence 0.0–1.0 score from intent recognition. 1.0 for exact/fuzzy matches. */
    data class Command(val action: VoiceAction, val confidence: Float = 1.0f) : VoiceCommandResult

    /** The utterance is normal text — proceed to Phase 2 (content normalization). */
    data class Text(val rawText: String) : VoiceCommandResult
}

/**
 * Actions that voice commands can trigger on the editor via InputConnection.
 */
sealed interface VoiceAction {
    data object DeleteSelection : VoiceAction
    data object DeleteLastWord : VoiceAction
    data object DeleteLastSentence : VoiceAction
    data object DeleteLastParagraph : VoiceAction
    data object Backspace : VoiceAction
    data object Undo : VoiceAction
    data object Redo : VoiceAction
    data object SelectAll : VoiceAction
    data object SelectLastWord : VoiceAction
    data object SelectLastSentence : VoiceAction
    data object SelectLastParagraph : VoiceAction
    data object Copy : VoiceAction
    data object Cut : VoiceAction
    data object Paste : VoiceAction
    data object NewLine : VoiceAction
    data object NewParagraph : VoiceAction
    data object MoveCursorToEnd : VoiceAction
    data object MoveCursorToStart : VoiceAction
    data object CapitalizeSelection : VoiceAction
    data object UppercaseSelection : VoiceAction
    data object LowercaseSelection : VoiceAction
    data object BoldSelection : VoiceAction
    data object ItalicSelection : VoiceAction
    data object UnderlineSelection : VoiceAction
    data object MoveToLineStart : VoiceAction
    data object MoveToLineEnd : VoiceAction
    data object SelectToStart : VoiceAction
    data object SelectToEnd : VoiceAction
    data object SelectCurrentWord : VoiceAction
    data object SelectCurrentSentence : VoiceAction
    data object SelectCurrentLine : VoiceAction
    data class InsertText(val text: String) : VoiceAction
    data class ReplaceText(val oldText: String, val newText: String) : VoiceAction
    data class SearchText(val query: String) : VoiceAction
    data class SelectWord(val word: String) : VoiceAction
    data class CopyWord(val word: String) : VoiceAction
    data class DeleteWord(val word: String) : VoiceAction
    data class SpellOut(val letters: String) : VoiceAction
    data object Send : VoiceAction
    data object Submit : VoiceAction
    data object ClearAll : VoiceAction
    data object GoBack : VoiceAction
    data object StopListening : VoiceAction
    data object OpenSettings : VoiceAction
    data object ScrollUp : VoiceAction
    data object ScrollDown : VoiceAction
    data object Dismiss : VoiceAction
}
