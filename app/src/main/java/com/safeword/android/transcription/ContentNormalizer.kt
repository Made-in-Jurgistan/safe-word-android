package com.safeword.android.transcription

import timber.log.Timber

/**
 * Phase 2 — Content Normalization.
 *
 * Transforms raw Whisper text into clean content by resolving disfluencies,
 * converting spoken punctuation and emoji names to symbols, normalizing
 * numbers, and stripping artefacts. All operations are content-level —
 * formatting (capitalization, trailing punctuation) is deferred to Phase 3.
 *
 * Pipeline order:
 *  1. Strip invisible Unicode characters
 *  2. Convert spoken emoji → emoji characters (NEW)
 *  3. Convert spoken punctuation → symbols
 *  4. Remove filler words
 *  5. Collapse stutters
 *  6. Resolve self-repair disfluencies
 *  7. Number word → digit conversion / ITN
 */
object ContentNormalizer {

    // -- Invisible characters -------------------------------------------------

    /** ZWSP, ZWNJ, ZWJ, BOM, soft-hyphen. */
    private val INVISIBLE_CHARS = Regex("[\u200B\u200C\u200D\uFEFF\u00AD]")

    // -- Spoken emoji → emoji characters (NEW) --------------------------------

    /**
     * Spoken emoji map: phrase → Unicode emoji.
     * Keys are lowercase. Phrases are matched as whole words.
     */
    private val SPOKEN_EMOJI_MAP: Map<String, String> = mapOf(
        // Smileys & emotion
        "smiley face" to "\uD83D\uDE0A",
        "happy face" to "\uD83D\uDE04",
        "sad face" to "\u2639\uFE0F",
        "crying face" to "\uD83D\uDE22",
        "laughing face" to "\uD83D\uDE02",
        "winking face" to "\uD83D\uDE09",
        "heart eyes" to "\uD83D\uDE0D",
        "thinking face" to "\uD83E\uDD14",
        "rolling eyes" to "\uD83D\uDE44",
        "mind blown" to "\uD83E\uDD2F",
        "face palm" to "\uD83E\uDD26",
        "shrug" to "\uD83E\uDD37",
        "clown face" to "\uD83E\uDD21",
        "skull" to "\uD83D\uDC80",
        "angry face" to "\uD83D\uDE20",
        "surprised face" to "\uD83D\uDE2E",
        "sunglasses face" to "\uD83D\uDE0E",
        "nerd face" to "\uD83E\uDD13",
        "kissing face" to "\uD83D\uDE18",

        // Gestures
        "thumbs up" to "\uD83D\uDC4D",
        "thumbs down" to "\uD83D\uDC4E",
        "clapping hands" to "\uD83D\uDC4F",
        "raised hands" to "\uD83D\uDE4C",
        "crossed fingers" to "\uD83E\uDD1E",
        "muscle" to "\uD83D\uDCAA",
        "wave" to "\uD83D\uDC4B",
        "pointing up" to "\u261D\uFE0F",
        "ok hand" to "\uD83D\uDC4C",
        "peace sign" to "\u270C\uFE0F",
        "fist bump" to "\uD83E\uDD1C",
        "high five" to "\uD83D\uDE4F",
        "middle finger" to "\uD83D\uDD95",

        // Hearts & love
        "red heart" to "\u2764\uFE0F",
        "heart" to "\u2764\uFE0F",
        "broken heart" to "\uD83D\uDC94",
        "fire heart" to "\u2764\uFE0F\u200D\uD83D\uDD25",
        "sparkling heart" to "\uD83D\uDC96",
        "blue heart" to "\uD83D\uDC99",
        "green heart" to "\uD83D\uDC9A",
        "purple heart" to "\uD83D\uDC9C",

        // Common objects & symbols
        "fire" to "\uD83D\uDD25",
        "star" to "\u2B50",
        "sparkles" to "\u2728",
        "lightning" to "\u26A1",
        "sun" to "\u2600\uFE0F",
        "moon" to "\uD83C\uDF19",
        "rainbow" to "\uD83C\uDF08",
        "cloud" to "\u2601\uFE0F",
        "snowflake" to "\u2744\uFE0F",
        "umbrella" to "\u2602\uFE0F",
        "rocket" to "\uD83D\uDE80",
        "airplane" to "\u2708\uFE0F",
        "check mark" to "\u2705",
        "cross mark" to "\u274C",
        "warning sign" to "\u26A0\uFE0F",
        "light bulb" to "\uD83D\uDCA1",
        "money bag" to "\uD83D\uDCB0",
        "crown" to "\uD83D\uDC51",
        "trophy" to "\uD83C\uDFC6",
        "gift" to "\uD83C\uDF81",
        "balloon" to "\uD83C\uDF88",
        "party popper" to "\uD83C\uDF89",
        "confetti" to "\uD83C\uDF8A",
        "bell" to "\uD83D\uDD14",
        "megaphone" to "\uD83D\uDCE3",

        // Food & drink
        "pizza" to "\uD83C\uDF55",
        "coffee" to "\u2615",
        "beer" to "\uD83C\uDF7A",
        "wine" to "\uD83C\uDF77",
        "taco" to "\uD83C\uDF2E",
        "cake" to "\uD83C\uDF82",
        "ice cream" to "\uD83C\uDF68",
        "cookie" to "\uD83C\uDF6A",
        "avocado" to "\uD83E\uDD51",
        "hot dog" to "\uD83C\uDF2D",

        // Animals
        "dog" to "\uD83D\uDC36",
        "cat" to "\uD83D\uDC31",
        "unicorn" to "\uD83E\uDD84",
        "butterfly" to "\uD83E\uDD8B",
        "snake" to "\uD83D\uDC0D",
        "panda" to "\uD83D\uDC3C",
        "monkey" to "\uD83D\uDC35",
        "penguin" to "\uD83D\uDC27",

        // Activities & reactions
        "poop" to "\uD83D\uDCA9",
        "hundred" to "\uD83D\uDCAF",
        "eye roll" to "\uD83D\uDE44",
        "pray" to "\uD83D\uDE4F",

        // Smileys & emotion (extended)
        "sleeping face" to "\uD83D\uDE34",
        "drooling face" to "\uD83E\uDD24",
        "zany face" to "\uD83E\uDD2A",
        "pleading face" to "\uD83E\uDD7A",
        "hugging face" to "\uD83E\uDD17",

        // Faces & creatures
        "angel face" to "\uD83D\uDE07",
        "devil face" to "\uD83D\uDE08",
        "ghost" to "\uD83D\uDC7B",
        "alien" to "\uD83D\uDC7D",
        "robot" to "\uD83E\uDD16",

        // Gestures (extended)
        "handshake" to "\uD83E\uDD1D",
        "writing hand" to "\u270D\uFE0F",
        "nail polish" to "\uD83D\uDC85",

        // Body parts
        "eyes" to "\uD83D\uDC40",
        "brain" to "\uD83E\uDDE0",

        // Nature
        "rose" to "\uD83C\uDF39",
        "cherry blossom" to "\uD83C\uDF38",
        "four leaf clover" to "\uD83C\uDF40",
        "seedling" to "\uD83C\uDF31",
        "globe" to "\uD83C\uDF0D",

        // Objects (extended)
        "gem" to "\uD83D\uDC8E",
        "musical note" to "\uD83C\uDFB5",
        "camera" to "\uD83D\uDCF7",
        "lock" to "\uD83D\uDD12",
        "magnifying glass" to "\uD83D\uDD0D",

        // Animals (extended)
        "frog" to "\uD83D\uDC38",
        "lion" to "\uD83E\uDD81",
        "bear" to "\uD83D\uDC3B",
        "fox" to "\uD83E\uDD8A",
        "shark" to "\uD83E\uDD88",

        // Food & drink (extended)
        "hamburger" to "\uD83C\uDF54",
        "popcorn" to "\uD83C\uDF7F",
        "banana" to "\uD83C\uDF4C",

        // Reactions (extended)
        "boom" to "\uD83D\uDCA5",
        "sweat drops" to "\uD83D\uDCA6",
        "dizzy" to "\uD83D\uDCAB",
    )

    /** Single alternation regex for spoken emoji (one pass). Requires "emoji" suffix. */
    private val SPOKEN_EMOJI_PATTERN: Regex = run {
        val alts = SPOKEN_EMOJI_MAP.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b(?:$alts)\\s+emoji\\b")
    }

    // -- Spoken punctuation ---------------------------------------------------

    private val SPOKEN_PUNCTUATION_MAP: Map<String, String> = mapOf(
        // Whitespace / structural
        "new paragraph" to "\n\n", "new line" to "\n",

        // Sentence-ending
        "period" to ".", "full stop" to ".",
        "question mark" to "?",
        "exclamation point" to "!", "exclamation mark" to "!",
        "ellipsis" to "…", "dot dot dot" to "…",

        // Mid-sentence
        "comma" to ",", "colon" to ":", "semicolon" to ";",
        "hyphen" to "-", "dash" to " — ", "em dash" to " — ",

        // Paired delimiters
        "open parenthesis" to "(", "close parenthesis" to ")",
        "open bracket" to "[", "close bracket" to "]",
        "open brace" to "{", "close brace" to "}",
        "open quote" to "\"", "close quote" to "\"",
        "open single quote" to "'", "close single quote" to "'",

        // Slash / backslash
        "forward slash" to "/", "slash" to "/", "backslash" to "\\",

        // Symbols — common
        "at sign" to "@", "ampersand" to "&", "hash" to "#", "hashtag" to "#", "pound sign" to "#",
        "asterisk" to "*", "apostrophe" to "'",
        "underscore" to "_", "tilde" to "~",
        "pipe" to "|", "caret" to "^", "degree sign" to "°", "copyright sign" to "©", "trademark" to "™",

        // Math / currency
        "plus sign" to "+", "plus" to "+",
        "minus sign" to "-",
        "equal sign" to "=", "equals" to "=",
        "greater than" to ">", "less than" to "<",
        "percent sign" to "%",
        "dollar sign" to "$",

        // STT guide gap — alias with trailing 's'
        "equals sign" to "=",

        // Common typographic symbols
        "bullet point" to "\u2022",
        "en dash" to "\u2013",

        // IP / trademark family (completing ©/™)
        "registered trademark" to "\u00AE",
        "registered sign" to "\u00AE",

        // Currency symbols
        "euro sign" to "\u20AC",
        "cent sign" to "\u00A2",
        "yen sign" to "\u00A5",

        // Arrows
        "right arrow" to "\u2192",
        "left arrow" to "\u2190",
    )

    private val SPOKEN_PUNCTUATION_PATTERN: Regex = run {
        val alts = SPOKEN_PUNCTUATION_MAP.keys
            .sortedByDescending { it.length }
            .joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b(?:$alts)\\b")
    }

    private val OPENING_SYMBOLS = setOf("(", "[", "{" , "\"", "'", "\u20AC", "\u00A5")

    private val PUNCT_SPACE_CLEANUP = Regex("\\s+([.,!?;:…)}\\]'%_\\\\\u00AE\u2122\u00A9\u00B0\u00A2])")
    private val OPEN_BRACKET_SPACE_CLEANUP = Regex("([\\[({'\\$\u20AC\u00A5_\\\\])\\s+")
    private val NEWLINE_SPACE_CLEANUP = Regex("[ \\t]*\\n[ \\t]*")
    private val HYPHEN_SPACE_CLEANUP = Regex(" +- +")
    private val HASHTAG_SPACE_CLEANUP = Regex("#\\s+(?=\\w)")
    private val AT_SIGN_SPACE_CLEANUP = Regex("@\\s+(?=\\w)")
    private val CURRENCY_SPACE_CLEANUP = Regex("[\\$\u20AC\u00A5]\\s+(?=\\d)")
    private val DOT_DOT_DOT_PATTERN = Regex("(?i)\\bdot\\s+dot\\s+dot\\b")

    // -- Fillers & stutters ---------------------------------------------------

    private val DEFAULT_FILLER_WORDS = linkedSetOf(
        "uh", "um", "uhm", "umm", "uhh", "uhhh",
        "ah", "hmm", "hm", "mmm", "mm", "mh",
        "eh", "ehh", "ha", "erm",
        "you know", "kind of", "sort of",
        "basically", "literally",
    )

    private val DEFAULT_FILLER_PATTERN: Regex = buildFillerPattern(DEFAULT_FILLER_WORDS)

    private val STUTTER_PATTERN = Regex("(?i)\\b([a-zA-Z]{1,6})\\b(?:\\s+\\1\\b)+")

    // -- Self-repair ----------------------------------------------------------

    private val EDITING_TERMS = listOf(
        "oh no actually", "well actually", "scratch that", "forget that",
        "never mind", "or rather", "no wait", "oh wait", "oh no wait",
        "oh no", "wait no", "i mean", "no no no", "no no",
    )

    private val SELF_REPAIR_PATTERN: Regex = run {
        val alternatives = EDITING_TERMS.joinToString("|") { Regex.escape(it) }
        Regex("(?i)[,;.!?…·\\s]*\\b(?<marker>$alternatives)\\b[,;.!?…·\\s]*")
    }

    // -- Hallucination filters ------------------------------------------------

    /** Known Whisper hallucination phrases (case-insensitive match). */
    private val HALLUCINATION_PHRASES = listOf(
        "thank you for watching",
        "thanks for watching",
        "please subscribe",
        "subscribe to",
        "like and subscribe",
        "subtitles by",
        "translated by",
        "translation by",
        "transcribed by",
        "all rights reserved",
        "music playing",
        "applause",
    )

    private val HALLUCINATION_PATTERN: Regex = run {
        val alts = HALLUCINATION_PHRASES.joinToString("|") { Regex.escape(it) }
        // "copyright" is a common hallucination but must not consume
        // the spoken punctuation command "copyright sign".
        // URL-like fragments (www.*, http(s)://*) are Whisper training-data
        // watermark artifacts — match them outside the \b word-boundary group
        // because URLs contain dots and slashes.
        Regex("(?i)(?:\\b(?:$alts|copyright(?!\\s+sign))\\b|(?:https?://|www\\.)\\S+)")
    }

    /** Triple-or-more word/phrase repetition (e.g. "the the the" or "I am I am I am"). */
    private val TRIPLE_REPEAT_PATTERN = Regex("(?i)\\b(\\w+(?:\\s+\\w+){0,3})(?:\\s+\\1){2,}\\b")

    // -- Backtrack triggers ---------------------------------------------------

    private val BACKTRACK_TRIGGERS = listOf(
        "actually no", "scratch that", "no wait", "I mean",
    )

    private val BACKTRACK_PATTERN: Regex = run {
        val alts = BACKTRACK_TRIGGERS.joinToString("|") { Regex.escape(it) }
        Regex("(?i)[,;.!?…\\s]*\\b(?:$alts)\\b[,;.!?…\\s]*")
    }

    // -- ITN (Inverse Text Normalization) -------------------------------------

    private val DIGIT_WORDS = mapOf(
        "zero" to "0", "one" to "1", "two" to "2", "three" to "3",
        "four" to "4", "five" to "5", "six" to "6", "seven" to "7",
        "eight" to "8", "nine" to "9", "ten" to "10", "eleven" to "11",
        "twelve" to "12", "thirteen" to "13", "fourteen" to "14",
        "fifteen" to "15", "sixteen" to "16", "seventeen" to "17",
        "eighteen" to "18", "nineteen" to "19",
    )

    private val TENS_WORDS = mapOf(
        "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "seventy" to 70, "eighty" to 80, "ninety" to 90,
    )

    private val MULTIPLIER_WORDS = mapOf(
        "hundred" to 100L, "thousand" to 1_000L, "million" to 1_000_000L,
        "billion" to 1_000_000_000L, "trillion" to 1_000_000_000_000L,
    )

    private val NUMBER_SPAN_PATTERN: Regex = run {
        val words = (DIGIT_WORDS.keys + TENS_WORDS.keys + MULTIPLIER_WORDS.keys)
            .sortedByDescending { it.length }
            .joinToString("|")
        Regex("(?i)\\b(?:$words|a)(?:[\\s-]+(?:and[\\s-]+)?(?:$words))*\\b")
    }

    private val ORDINAL_WORDS = mapOf(
        "first" to "1st", "second" to "2nd", "third" to "3rd",
        "fourth" to "4th", "fifth" to "5th", "sixth" to "6th",
        "seventh" to "7th", "eighth" to "8th", "ninth" to "9th",
        "tenth" to "10th", "eleventh" to "11th", "twelfth" to "12th",
        "thirteenth" to "13th", "fourteenth" to "14th", "fifteenth" to "15th",
        "sixteenth" to "16th", "seventeenth" to "17th", "eighteenth" to "18th",
        "nineteenth" to "19th", "twentieth" to "20th", "thirtieth" to "30th",
        "fortieth" to "40th", "fiftieth" to "50th", "sixtieth" to "60th",
        "seventieth" to "70th", "eightieth" to "80th", "ninetieth" to "90th",
        "hundredth" to "100th", "thousandth" to "1000th",
    )

    private val ORDINAL_PATTERN: Regex = run {
        val alts = ORDINAL_WORDS.keys.sortedByDescending { it.length }.joinToString("|")
        Regex("(?i)\\b($alts)\\b")
    }

    private val PERCENT_PATTERN = Regex("(?<=\\d)\\s+(?:percent|per cent)\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_DOLLAR_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+(?:dollars?|bucks?)\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_EURO_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+euros?\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_POUND_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+pounds?\\b", RegexOption.IGNORE_CASE)

    /**
     * Spoken time → formatted time.
     * Matches patterns like "three thirty PM", "twelve oh five AM", "ten fifteen".
     * Hour must be 1–12; minute 00–59 (word or digit form).
     */
    private val HOUR_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4,
        "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
        "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
    )

    private val MINUTE_WORDS = mapOf(
        "oh one" to 1, "oh two" to 2, "oh three" to 3, "oh four" to 4,
        "oh five" to 5, "oh six" to 6, "oh seven" to 7, "oh eight" to 8,
        "oh nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12,
        "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18,
        "nineteen" to 19, "twenty" to 20, "twenty one" to 21,
        "twenty two" to 22, "twenty three" to 23, "twenty four" to 24,
        "twenty five" to 25, "twenty six" to 26, "twenty seven" to 27,
        "twenty eight" to 28, "twenty nine" to 29, "thirty" to 30,
        "thirty one" to 31, "thirty two" to 32, "thirty three" to 33,
        "thirty four" to 34, "thirty five" to 35, "thirty six" to 36,
        "thirty seven" to 37, "thirty eight" to 38, "thirty nine" to 39,
        "forty" to 40, "forty one" to 41, "forty two" to 42,
        "forty three" to 43, "forty four" to 44, "forty five" to 45,
        "forty six" to 46, "forty seven" to 47, "forty eight" to 48,
        "forty nine" to 49, "fifty" to 50, "fifty one" to 51,
        "fifty two" to 52, "fifty three" to 53, "fifty four" to 54,
        "fifty five" to 55, "fifty six" to 56, "fifty seven" to 57,
        "fifty eight" to 58, "fifty nine" to 59,
    )

    private val TIME_PATTERN: Regex = run {
        val hours = HOUR_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        val minutes = MINUTE_WORDS.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        // "three thirty PM", "twelve oh five", "ten fifteen AM", "seven o'clock PM"
        Regex("(?i)\\b($hours)\\s+(?:($minutes)|o'?clock)(?:\\s+(AM|PM|a\\.m\\.|p\\.m\\.))?\\b")
    }

    private val WHITESPACE_SPLIT = Regex("\\s+")
    private val MULTI_SPACE_INLINE = Regex("[ \\t]{2,}")

    // -- Public API -----------------------------------------------------------

    /**
     * Apply all content normalization steps to raw Whisper text.
     *
     * @param text Raw transcription (after voice command detection).
     * @return Normalized content ready for Phase 3 formatting.
     */
    fun normalize(text: String): String {
        return normalize(text, fillerWords = DEFAULT_FILLER_WORDS)
    }

    /**
     * Variant with caller-provided filler vocabulary.
     *
     * This supports per-user filler customization while keeping the default
     * behavior backwards-compatible when callers use [normalize].
     */
    fun normalize(
        text: String,
        fillerWords: Set<String>,
    ): String {
        if (text.isBlank()) return ""

        var result = text

        // 1. Strip invisible Unicode characters
        result = INVISIBLE_CHARS.replace(result, "")

        // 2. Hallucination filter — remove known Whisper artifacts
        result = removeHallucinations(result)

        // 3. Filler word removal (guide: run early before command punctuation)
        result = removeFillers(result, fillerWords)

        // 4. Self-repair disfluency resolution
        result = resolveSelfRepairs(result)

        // 5. Backtrack resolution ("actually no", "scratch that", etc.)
        result = resolveBacktracks(result)

        // 6. Spoken emoji → emoji characters
        result = convertSpokenEmoji(result)

        // 7. Spoken punctuation → symbols
        result = convertSpokenPunctuation(result)

        // 8. Stutter collapse (after punctuation commands so phrases like
        // "dot dot dot" can be parsed first).
        result = STUTTER_PATTERN.replace(result) { match -> match.groupValues[1] }

        // 9. Triple-repeat deduplication (e.g. "the the the" → "the")
        result = TRIPLE_REPEAT_PATTERN.replace(result) { it.groupValues[1] }

        // 10. Number word → digit / ITN
        result = normalizeNumbers(result)

        // Deterministic, idempotent output for downstream phases.
        result = MULTI_SPACE_INLINE.replace(result, " ")
        result = NEWLINE_SPACE_CLEANUP.replace(result, "\n")

        return result.trim()
    }

    private fun buildFillerPattern(fillers: Set<String>): Regex {
        val nonBlank = fillers
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .sortedByDescending { it.length }
            .toList()

        if (nonBlank.isEmpty()) {
            // Never match when no fillers are configured.
            return Regex("(?!x)x")
        }

        val alternatives = nonBlank.joinToString("|") { Regex.escape(it) }
        return Regex("(?i)\\b(?:$alternatives)\\b(?:\\s*[,.;:!?…])?\\s*")
    }

    private fun removeFillers(
        text: String,
        fillerWords: Set<String>,
    ): String {
        val pattern = if (fillerWords == DEFAULT_FILLER_WORDS) {
            DEFAULT_FILLER_PATTERN
        } else {
            buildFillerPattern(fillerWords)
        }

        var result = pattern.replace(text, " ")
        result = MULTI_SPACE_INLINE.replace(result, " ")
        result = result.replace(Regex("(^|\\s)[,;:](?=\\s|$)"), "$1")
        result = result.replace(Regex("\\s+([,;:])"), "$1")
        return result.trim()
    }

    // -- Spoken emoji conversion ----------------------------------------------

    private fun convertSpokenEmoji(text: String): String {
        if (!text.contains("emoji", ignoreCase = true)) return text
        return SPOKEN_EMOJI_PATTERN.replace(text) { match ->
            val phrase = match.value.lowercase()
                .removeSuffix(" emoji")
                .trim()
            SPOKEN_EMOJI_MAP[phrase] ?: match.value
        }
    }

    // -- Spoken punctuation conversion ----------------------------------------

    private fun convertSpokenPunctuation(text: String): String {
        // Resolve this explicit command before stutter-like phrases can interfere.
        var result = DOT_DOT_DOT_PATTERN.replace(text, "…")
        val current = result
        result = SPOKEN_PUNCTUATION_PATTERN.replace(current) { match ->
            val symbol = SPOKEN_PUNCTUATION_MAP[match.value.lowercase()] ?: match.value
            val before = current.getOrNull(match.range.first - 1)
            if (symbol in OPENING_SYMBOLS && before != null && !before.isWhitespace()) " $symbol" else symbol
        }
        result = PUNCT_SPACE_CLEANUP.replace(result, "$1")
        result = OPEN_BRACKET_SPACE_CLEANUP.replace(result, "$1")
        result = NEWLINE_SPACE_CLEANUP.replace(result, "\n")
        result = HYPHEN_SPACE_CLEANUP.replace(result, "-")
        result = HASHTAG_SPACE_CLEANUP.replace(result, "#")
        result = AT_SIGN_SPACE_CLEANUP.replace(result, "@")
        result = CURRENCY_SPACE_CLEANUP.replace(result) { it.value.take(1) }
        return result
    }

    // -- Number normalization / ITN -------------------------------------------

    internal fun normalizeNumbers(text: String): String {
        var result = text

        // Time expressions first — before number-word conversion eats "three", "thirty", etc.
        result = normalizeTimeExpressions(result)

        result = ORDINAL_PATTERN.replace(result) { match ->
            ORDINAL_WORDS[match.value.lowercase()] ?: match.value
        }

        result = NUMBER_SPAN_PATTERN.replace(result) { match ->
            parseNumberSpan(match.value) ?: match.value
        }

        result = PERCENT_PATTERN.replace(result, "%")
        result = CURRENCY_DOLLAR_REWRITE.replace(result) { "\$${it.groupValues[1]}" }
        result = CURRENCY_EURO_REWRITE.replace(result) { "€${it.groupValues[1]}" }
        result = CURRENCY_POUND_REWRITE.replace(result) { "£${it.groupValues[1]}" }

        return result
    }

    private fun normalizeTimeExpressions(text: String): String {
        return TIME_PATTERN.replace(text) { match ->
            val hourWord = match.groupValues[1].lowercase()
            val minuteWord = match.groupValues[2].lowercase().ifEmpty { null }
            val meridiem = match.groupValues[3].uppercase()
                .replace(".", "")  // "a.m." → "AM"

            val hour = HOUR_WORDS[hourWord] ?: return@replace match.value
            val minute = if (minuteWord != null) {
                MINUTE_WORDS[minuteWord] ?: return@replace match.value
            } else {
                0 // o'clock
            }

            val timeStr = "$hour:%02d".format(minute)
            if (meridiem.isNotEmpty()) "$timeStr $meridiem" else timeStr
        }
    }

    private fun parseNumberSpan(span: String): String? {
        val tokens = span.lowercase()
            .replace("-", " ")
            .split(WHITESPACE_SPLIT)
            .filter { it != "and" }

        if (tokens.isEmpty()) return null

        val hasNumberWord = tokens.any { it in DIGIT_WORDS || it in TENS_WORDS || it in MULTIPLIER_WORDS }
        if (!hasNumberWord) return null

        if (tokens.size == 1) {
            DIGIT_WORDS[tokens[0]]?.let { return it }
            TENS_WORDS[tokens[0]]?.let { return it.toString() }
            MULTIPLIER_WORDS[tokens[0]]?.let { return it.toString() }
            return null
        }

        var total = 0L
        var current = 0L

        for (token in tokens) {
            when {
                token == "a" -> {
                    if (current == 0L) current = 1L
                }
                token in DIGIT_WORDS -> {
                    current += DIGIT_WORDS[token]!!.toLong()
                }
                token in TENS_WORDS -> {
                    current += TENS_WORDS[token]!!.toLong()
                }
                token in MULTIPLIER_WORDS -> {
                    val multiplier = MULTIPLIER_WORDS[token]!!
                    if (multiplier >= 1000) {
                        current = if (current == 0L) multiplier else current * multiplier
                        total += current
                        current = 0L
                    } else {
                        current = if (current == 0L) multiplier else current * multiplier
                    }
                }
                token == "oh" -> return null
                else -> return null
            }
        }

        total += current
        return if (total > 0) total.toString() else null
    }

    // -- Hallucination removal ------------------------------------------------

    private fun removeHallucinations(text: String): String {
        val result = HALLUCINATION_PATTERN.replace(text, "").trim()
        if (result != text.trim()) {
            Timber.d("[POSTPROCESS] removeHallucinations | removed hallucination phrases")
        }
        return result
    }

    // -- Backtrack resolution -------------------------------------------------

    /**
     * Resolve explicit backtrack triggers by removing the preceding clause.
     * "I went to the store actually no I went to the park" → "I went to the park"
     */
    internal fun resolveBacktracks(text: String): String {
        var result = text
        var safetyCounter = 0
        val maxIterations = 5

        while (safetyCounter < maxIterations) {
            safetyCounter++
            val match = BACKTRACK_PATTERN.find(result) ?: break

            val before = result.substring(0, match.range.first)
            val after = result.substring(match.range.last + 1)

            // Find the clause boundary before the backtrack trigger
            val clauseSep = before.lastIndexOfAny(charArrayOf('.', '!', '?', ';'))
            val prefix = if (clauseSep >= 0) before.substring(0, clauseSep + 1) else ""

            val stitched = if (prefix.isEmpty()) {
                after.trimStart()
            } else {
                "${prefix.trimEnd()} ${after.trimStart()}"
            }

            Timber.d("[POSTPROCESS] resolveBacktracks | trigger=\"%s\"", match.value.trim())
            result = stitched
        }

        return result
    }

    internal fun resolveSelfRepairs(text: String): String {
        var result = text
        var safetyCounter = 0
        val maxIterations = 10

        while (safetyCounter < maxIterations) {
            safetyCounter++
            val match = SELF_REPAIR_PATTERN.find(result) ?: break

            val markerStart = match.range.first
            val markerEnd = match.range.last + 1

            val before = result.substring(0, markerStart)
            val after = result.substring(markerEnd)

            val reparandumStart = findReparandumStart(before, after)

            if (reparandumStart < 0) {
                result = before.trimEnd() + " " + after.trimStart()
                continue
            }

            val prefix = result.substring(0, reparandumStart)
            val repairedAfter = after.trimStart()
            val stitched = if (prefix.isEmpty()) {
                repairedAfter
            } else {
                val lastPrefixChar = prefix.last()
                if (lastPrefixChar.isLetterOrDigit() && repairedAfter.firstOrNull()?.isLowerCase() == true) {
                    "$prefix $repairedAfter"
                } else {
                    "${prefix.trimEnd()} $repairedAfter"
                }
            }

            Timber.d(
                "[POSTPROCESS] resolveSelfRepairs | marker=\"%s\" reparandum=\"%s\"",
                match.value.trim(),
                before.substring(reparandumStart).trim(),
            )

            result = stitched
        }

        return result
    }

    private fun findReparandumStart(before: String, after: String): Int {
        if (before.isBlank()) return -1

        val trimmedBefore = before.trimEnd()
        val trimmedAfter = after.trimStart()

        val beforeWords = trimmedBefore.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }
        val afterWords = trimmedAfter.split(WHITESPACE_SPLIT).filter { it.isNotEmpty() }

        if (beforeWords.isEmpty() || afterWords.isEmpty()) return -1

        val repairFirstWord = afterWords.first().lowercase().trimEnd(',', '.', '!', '?', ';')

        if (repairFirstWord.isNotEmpty()) {
            val searchWindow = beforeWords.takeLast(8)
            val windowOffset = beforeWords.size - searchWindow.size

            for (i in searchWindow.indices) {
                val candidateWord = searchWindow[i].lowercase().trimEnd(',', '.', '!', '?', ';')
                if (candidateWord == repairFirstWord) {
                    val wordIndex = windowOffset + i
                    return findWordCharIndex(trimmedBefore, wordIndex)
                }
            }
        }

        val lastClauseSep = trimmedBefore.lastIndexOfAny(charArrayOf(',', ';'))
        if (lastClauseSep >= 0) {
            return lastClauseSep + 1
        }

        val wordsToRemove = minOf(afterWords.size, beforeWords.size, 5)
        if (wordsToRemove > 0) {
            val reparandumWordIndex = beforeWords.size - wordsToRemove
            return findWordCharIndex(trimmedBefore, reparandumWordIndex)
        }

        return -1
    }

    private fun findWordCharIndex(text: String, wordIndex: Int): Int {
        var count = 0
        var i = 0
        while (i < text.length && text[i].isWhitespace()) i++

        while (i < text.length && count < wordIndex) {
            while (i < text.length && !text[i].isWhitespace()) i++
            while (i < text.length && text[i].isWhitespace()) i++
            count++
        }
        return if (count == wordIndex) i else text.length
    }
}
