package com.safeword.android.transcription

/**
 * Central registry for all voice command vocabulary, enums, and compound action tables.
 *
 * Extracted from [VoiceCommandDetector] so the data layer is separate from matching
 * logic. [VoiceCommandDetector] and [CompositionalCommandMatcher] reference this object
 * directly — no duplication.
 */
internal object VoiceCommandRegistry {

    // ── Command vocabulary ────────────────────────────────────────────────────

    /**
     * Exact-match command vocabulary — the fast path (O(1) hash lookup).
     *
     * Only phrases that **must** live here are registered:
     * - The canonical/primary phrasing per action (fast path + trailing detection).
     * - Split-verb idioms whose parts are separated by an object ("throw **that** away",
     *   "take **it** back") — [CompositionalCommandMatcher] can't decompose these because
     *   the intent phrase is interrupted.
     * - Non-decomposable idioms ("oops", "never mind", "title case", "all caps").
     * - Override mappings where the compositional grammar would produce the wrong action
     *   ("undo last word" → [VoiceAction.DeleteLastWord], not [VoiceAction.Undo]).
     *
     * Everything else is handled by the compositional matcher (verb × target → action)
     * or, for creative paraphrases, the semantic embedding matcher.
     */
    val COMMAND_MAP: Map<String, VoiceAction> = buildMap {
        fun reg(action: VoiceAction, vararg phrases: String) = phrases.forEach { put(it, action) }

        // ── Deletion ──────────────────────────────────────────────────────────
        reg(
            VoiceAction.DeleteSelection,
            "delete that", "delete selection", "erase that",
            // Split-verb (not decomposable — "throw away" interrupted by object):
            "throw that away", "throw it away",
        )
        reg(
            VoiceAction.DeleteLastWord,
            "delete last word", "delete the last word",
            "remove last word", "remove the last word",
            "erase last word", "wipe last word",
            // Override: "undo last word" → Delete, not Undo:
            "undo last word", "undo the last word",
        )
        reg(
            VoiceAction.DeleteLastSentence,
            "delete last sentence", "delete the last sentence",
            "remove last sentence", "erase last sentence",
            // Override: same rationale as above.
            "undo last sentence", "undo the last sentence",
        )
        reg(
            VoiceAction.DeleteNextWord,
            "delete next word", "delete the next word",
            "delete word ahead", "delete forward",
            "remove next word",
        )
        reg(
            VoiceAction.ClearAll,
            "clear all", "clear everything", "delete all", "start over",
            "erase everything", "erase all", "delete everything", "remove everything",
            "wipe everything", "wipe it all", "nuke it all",
            "clean slate", "fresh start",
        )
        reg(
            VoiceAction.Backspace,
            "backspace", "back space",
            "delete character", "delete last character",
        )

        // ── Undo / Redo ───────────────────────────────────────────────────────
        reg(
            VoiceAction.Undo,
            "undo", "undo that", "scratch that",
            // Split-verb (not decomposable — "take back" interrupted by object):
            "take that back", "take it back",
            // Non-compositional idioms:
            "never mind", "oops", "my bad", "whoops",
            "forget that", "forget it", "no wait",
            "go back", "undo it", "revert that", "revert it",
        )
        reg(
            VoiceAction.Redo,
            "redo", "redo that", "redo it",
            // Split-verb (not decomposable):
            "put that back", "put it back",
            "bring that back", "bring it back",
            "restore that", "restore it",
            // Non-compositional ("no" prefix + split verb):
            "no put it back",
            // "actually" prefix + non-decomposable:
            "actually keep that", "keep that",
        )

        // ── Selection ─────────────────────────────────────────────────────────
        reg(
            VoiceAction.SelectAll,
            "select all", "select everything",
            "highlight all", "highlight everything",
            // "grab" is not in INTENT_PHRASES — no compositional path:
            "grab all", "grab everything",
        )
        reg(
            VoiceAction.SelectLastWord,
            "select last word", "select the last word",
            "highlight last word",
            // "grab" not in INTENT_PHRASES:
            "grab last word",
        )
        reg(
            VoiceAction.SelectLastSentence,
            "select last sentence", "select the last sentence",
            "select sentence", "select the sentence",
            "highlight last sentence", "highlight the last sentence",
            "highlight sentence",
        )
        reg(
            VoiceAction.SelectNextWord,
            "select next word", "select the next word",
            "highlight next word",
        )

        // ── Clipboard ─────────────────────────────────────────────────────────
        reg(
            VoiceAction.Copy,
            "copy", "copy that", "grab that",
            "copy all", "copy everything", "copy text", "copy selection",
            // "snag" not in INTENT_PHRASES:
            "snag that",
        )
        reg(
            VoiceAction.Cut,
            "cut", "cut that", "cut selection",
        )
        reg(
            VoiceAction.Paste,
            "paste", "paste that", "paste here", "paste it here",
            "paste text", "insert clipboard", "put clipboard here",
            // "put/drop" are not PASTE intents — no compositional path:
            "put it here", "drop it here",
        )

        // ── Navigation & structure ────────────────────────────────────────────
        reg(
            VoiceAction.NewLine,
            "new line", "newline", "next line", "enter", "line break",
            // No compositional intents — must be in COMMAND_MAP:
            "press enter", "go down", "break line",
            "go to next line", "add new line", "add a line", "carriage return",
        )
        reg(
            VoiceAction.NewParagraph,
            "new paragraph", "next paragraph", "paragraph break",
            "begin new paragraph", "skip a line",
            "add paragraph", "blank line", "double enter",
        )




        // ── Formatting ─────────────────────────────────────────────────────
        reg(
            VoiceAction.Bold,
            "bold", "bold that", "make bold", "make it bold",
        )
        reg(
            VoiceAction.Italic,
            "italic", "italicize", "italicize that", "make italic", "make it italic",
        )
        reg(
            VoiceAction.Underline,
            "underline", "underline that", "make underline",
        )

        // ── Case change ───────────────────────────────────────────────────────
        reg(
            VoiceAction.CapitalizeLastWord,
            "capitalize", "capitalize that", "capitalize last word",
            "cap that", "title case",
        )
        reg(
            VoiceAction.UppercaseLastWord,
            "uppercase", "uppercase that", "uppercase last word",
            "all caps", "make uppercase", "make it uppercase",
        )
        reg(
            VoiceAction.LowercaseLastWord,
            "lowercase", "lowercase that", "lowercase last word",
            "make lowercase", "make it lowercase", "uncapitalize",
        )

        // ── Punctuation / symbol shortcuts ────────────────────────────────────
        SpokenPunctuationTable.entries.forEach { (phrase, text) ->
            put(phrase, VoiceAction.InsertText(text))
        }

        // ── Emoji shortcuts ───────────────────────────────────────────────────
        SpokenEmojiTable.entries.forEach { (phrase, emoji) ->
            put(phrase, VoiceAction.InsertText(emoji))
        }



        // ── Session control ───────────────────────────────────────────────────
        reg(
            VoiceAction.Send,
            "send", "send that", "send message", "submit",
            "send it", "ship it", "fire it off",
            "post that", "post it", "go ahead",
            "submit that", "submit it", "fire away",
        )
        reg(
            VoiceAction.StopListening,
            "stop", "stop listening", "stop dictation", "i'm done",
            "done dictating", "mic off", "turn off mic",
            "finish", "i'm finished", "all done", "that's all",
            "finish dictating",
        )

    }

    // ── Compositional vocabulary ──────────────────────────────────────────────

    enum class CmdIntent {
        DELETE, UNDO, REDO, SELECT, COPY, CUT, PASTE, CLEAR,
        BOLD, ITALIC, UNDERLINE, CAPITALIZE, UPPERCASE, LOWERCASE,
    }

    enum class CmdTarget {
        THAT, SELECTION, LAST_WORD, LAST_SENTENCE, ALL, CHARACTER, NONE,
    }

    /** Max words for a compositional match — anything longer is likely dictation. */
    const val MAX_COMPOSITIONAL_WORDS = 8

    /** Filler words stripped between intent and target. */
    val FILLER_WORDS: Set<String> = setOf("the", "a", "my", "one")

    /**
     * Intent verb phrases, longest-first so multi-word verbs match first.
     * Every phrase here must also appear in [COMMAND_STEMS] for near-miss logging.
     */
    val INTENT_PHRASES: List<Pair<String, CmdIntent>> = listOf(
        "get rid of" to CmdIntent.DELETE,
        "throw away" to CmdIntent.DELETE,
        "start over" to CmdIntent.CLEAR,
        "start fresh" to CmdIntent.CLEAR,
        "take back" to CmdIntent.UNDO,
        "put back" to CmdIntent.REDO,
        "bring back" to CmdIntent.REDO,
        "discard" to CmdIntent.DELETE,
        "delete" to CmdIntent.DELETE,
        "erase" to CmdIntent.DELETE,
        "remove" to CmdIntent.DELETE,
        "scrap" to CmdIntent.DELETE,
        "trash" to CmdIntent.DELETE,
        "wipe" to CmdIntent.DELETE,
        "toss" to CmdIntent.DELETE,
        "drop" to CmdIntent.DELETE,
        "kill" to CmdIntent.DELETE,
        "zap" to CmdIntent.DELETE,
        "lose" to CmdIntent.DELETE,
        "scratch" to CmdIntent.UNDO,
        "undo" to CmdIntent.UNDO,
        "revert" to CmdIntent.UNDO,
        "redo" to CmdIntent.REDO,
        "restore" to CmdIntent.REDO,
        "select" to CmdIntent.SELECT,
        "highlight" to CmdIntent.SELECT,
        "copy" to CmdIntent.COPY,
        "cut" to CmdIntent.CUT,
        "paste" to CmdIntent.PASTE,
        "clear" to CmdIntent.CLEAR,
        "nuke" to CmdIntent.CLEAR,
        "bold" to CmdIntent.BOLD,
        "italic" to CmdIntent.ITALIC,
        "italicize" to CmdIntent.ITALIC,
        "underline" to CmdIntent.UNDERLINE,
        "capitalize" to CmdIntent.CAPITALIZE,
        "uppercase" to CmdIntent.UPPERCASE,
        "lowercase" to CmdIntent.LOWERCASE,
    ).sortedByDescending { it.first.length }

    /**
     * Target noun phrases, longest-first for greedy matching.
     */
    val TARGET_PHRASES: List<Pair<String, CmdTarget>> = listOf(
        "previous sentence" to CmdTarget.LAST_SENTENCE,
        "entire sentence" to CmdTarget.LAST_SENTENCE,
        "whole sentence" to CmdTarget.LAST_SENTENCE,
        "last sentence" to CmdTarget.LAST_SENTENCE,
        "entire word" to CmdTarget.LAST_WORD,
        "whole word" to CmdTarget.LAST_WORD,
        "previous word" to CmdTarget.LAST_WORD,
        "last word" to CmdTarget.LAST_WORD,
        "last character" to CmdTarget.CHARACTER,
        "last letter" to CmdTarget.CHARACTER,
        "character" to CmdTarget.CHARACTER,
        "selection" to CmdTarget.SELECTION,
        "sentence" to CmdTarget.LAST_SENTENCE,
        "selected" to CmdTarget.SELECTION,
        "everything" to CmdTarget.ALL,
        "letter" to CmdTarget.CHARACTER,
        "it all" to CmdTarget.ALL,
        "word" to CmdTarget.LAST_WORD,
        "that" to CmdTarget.THAT,
        "this" to CmdTarget.THAT,
        "all" to CmdTarget.ALL,
        "it" to CmdTarget.THAT,
    ).sortedByDescending { it.first.length }

    /** Maps (intent, target) pairs to concrete [VoiceAction]s. */
    val COMPOSITION: Map<Pair<CmdIntent, CmdTarget>, VoiceAction> = mapOf(
        (CmdIntent.DELETE to CmdTarget.THAT) to VoiceAction.DeleteSelection,
        (CmdIntent.DELETE to CmdTarget.SELECTION) to VoiceAction.DeleteSelection,
        (CmdIntent.DELETE to CmdTarget.LAST_WORD) to VoiceAction.DeleteLastWord,
        (CmdIntent.DELETE to CmdTarget.LAST_SENTENCE) to VoiceAction.DeleteLastSentence,
        (CmdIntent.DELETE to CmdTarget.ALL) to VoiceAction.ClearAll,
        (CmdIntent.DELETE to CmdTarget.CHARACTER) to VoiceAction.Backspace,
        (CmdIntent.DELETE to CmdTarget.NONE) to VoiceAction.DeleteLastWord,
        (CmdIntent.UNDO to CmdTarget.THAT) to VoiceAction.Undo,
        (CmdIntent.UNDO to CmdTarget.NONE) to VoiceAction.Undo,
        (CmdIntent.REDO to CmdTarget.THAT) to VoiceAction.Redo,
        (CmdIntent.REDO to CmdTarget.NONE) to VoiceAction.Redo,
        (CmdIntent.SELECT to CmdTarget.ALL) to VoiceAction.SelectAll,
        (CmdIntent.SELECT to CmdTarget.LAST_WORD) to VoiceAction.SelectLastWord,
        (CmdIntent.SELECT to CmdTarget.LAST_SENTENCE) to VoiceAction.SelectLastSentence,
        (CmdIntent.SELECT to CmdTarget.SELECTION) to VoiceAction.SelectAll,
        (CmdIntent.SELECT to CmdTarget.THAT) to VoiceAction.SelectAll,
        (CmdIntent.SELECT to CmdTarget.NONE) to VoiceAction.SelectLastWord,
        (CmdIntent.COPY to CmdTarget.THAT) to VoiceAction.Copy,
        (CmdIntent.COPY to CmdTarget.SELECTION) to VoiceAction.Copy,
        (CmdIntent.COPY to CmdTarget.ALL) to VoiceAction.Copy,
        (CmdIntent.COPY to CmdTarget.NONE) to VoiceAction.Copy,
        (CmdIntent.CUT to CmdTarget.THAT) to VoiceAction.Cut,
        (CmdIntent.CUT to CmdTarget.SELECTION) to VoiceAction.Cut,
        (CmdIntent.CUT to CmdTarget.ALL) to VoiceAction.Cut,
        (CmdIntent.CUT to CmdTarget.NONE) to VoiceAction.Cut,
        (CmdIntent.PASTE to CmdTarget.THAT) to VoiceAction.Paste,
        (CmdIntent.PASTE to CmdTarget.NONE) to VoiceAction.Paste,
        (CmdIntent.CLEAR to CmdTarget.ALL) to VoiceAction.ClearAll,
        (CmdIntent.CLEAR to CmdTarget.THAT) to VoiceAction.ClearAll,
        (CmdIntent.CLEAR to CmdTarget.NONE) to VoiceAction.ClearAll,
        (CmdIntent.BOLD to CmdTarget.THAT) to VoiceAction.Bold,
        (CmdIntent.BOLD to CmdTarget.NONE) to VoiceAction.Bold,
        (CmdIntent.ITALIC to CmdTarget.THAT) to VoiceAction.Italic,
        (CmdIntent.ITALIC to CmdTarget.NONE) to VoiceAction.Italic,
        (CmdIntent.UNDERLINE to CmdTarget.THAT) to VoiceAction.Underline,
        (CmdIntent.UNDERLINE to CmdTarget.NONE) to VoiceAction.Underline,
        (CmdIntent.CAPITALIZE to CmdTarget.THAT) to VoiceAction.CapitalizeLastWord,
        (CmdIntent.CAPITALIZE to CmdTarget.LAST_WORD) to VoiceAction.CapitalizeLastWord,
        (CmdIntent.CAPITALIZE to CmdTarget.NONE) to VoiceAction.CapitalizeLastWord,
        (CmdIntent.UPPERCASE to CmdTarget.THAT) to VoiceAction.UppercaseLastWord,
        (CmdIntent.UPPERCASE to CmdTarget.LAST_WORD) to VoiceAction.UppercaseLastWord,
        (CmdIntent.UPPERCASE to CmdTarget.NONE) to VoiceAction.UppercaseLastWord,
        (CmdIntent.LOWERCASE to CmdTarget.THAT) to VoiceAction.LowercaseLastWord,
        (CmdIntent.LOWERCASE to CmdTarget.LAST_WORD) to VoiceAction.LowercaseLastWord,
        (CmdIntent.LOWERCASE to CmdTarget.NONE) to VoiceAction.LowercaseLastWord,
    )

    /**
     * Structural commands eligible for trailing-command scanning: no [VoiceAction.InsertText]
     * entries (emojis/punctuation — too short, too many false positives mid-dictation),
     * and at least 2 words so single-word English words ("copy", "stop", "enter") don't
     * trigger on the last word of normal dictation. Sorted longest-phrase-first so the
     * greediest (most specific) match wins.
     */
    val STRUCTURAL_COMMANDS_BY_LENGTH_DESC: List<Pair<String, VoiceAction>> by lazy {
        COMMAND_MAP.entries
            .filter { (phrase, action) ->
                action !is VoiceAction.InsertText && phrase.count { it == ' ' } >= 1
            }
            .map { it.key to it.value }
            .sortedByDescending { (phrase, _) -> phrase.length }
    }
}
