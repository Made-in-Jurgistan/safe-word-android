package com.safeword.android.transcription

/**
 * Inverse Text Normalization — converts spoken number words, ordinals, currencies,
 * measurements, temperatures, fractions, and time expressions to their compact
 * digit/symbol representations.
 *
 * Step 10 of the ContentNormalizer pipeline. Extracted from [ContentNormalizer]
 * to allow isolated testing and future extension.
 */
internal object InverseTextNormalizer {

    // -- Digit / tens / multiplier words --------------------------------------

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

    // -- Ordinals -------------------------------------------------------------

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

    // -- Currencies / percentages ---------------------------------------------

    private val PERCENT_PATTERN = Regex("(?<=\\d)\\s+(?:percent|per cent)\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_DOLLAR_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+(?:dollars?|bucks?)\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_EURO_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+euros?\\b", RegexOption.IGNORE_CASE)
    private val CURRENCY_POUND_REWRITE = Regex("(\\d+(?:\\.\\d+)?)\\s+pounds?\\b", RegexOption.IGNORE_CASE)

    // -- Measurements ---------------------------------------------------------

    private val MEASUREMENT_PATTERN = Regex(
        """(?i)(\d+(?:\.\d+)?)\s+(kilometres?|kilometers?|kilograms?|milligrams?|grams?|metres?|meters?|centimetres?|centimeters?|millimetres?|millimeters?|litres?|liters?|millilitres?|milliliters?)"""
    )

    private val MEASUREMENT_ABBR = mapOf(
        "kilometres" to "km", "kilometers" to "km", "kilometre" to "km", "kilometer" to "km",
        "kilograms" to "kg", "kilogram" to "kg",
        "milligrams" to "mg", "milligram" to "mg",
        "grams" to "g", "gram" to "g",
        "metres" to "m", "meters" to "m", "metre" to "m", "meter" to "m",
        "centimetres" to "cm", "centimeters" to "cm", "centimetre" to "cm", "centimeter" to "cm",
        "millimetres" to "mm", "millimeters" to "mm", "millimetre" to "mm", "millimeter" to "mm",
        "litres" to "L", "liters" to "L", "litre" to "L", "liter" to "L",
        "millilitres" to "mL", "milliliters" to "mL", "millilitre" to "mL", "milliliter" to "mL",
    )

    // -- Temperatures ---------------------------------------------------------

    private val TEMPERATURE_PATTERN = Regex(
        """(?i)(\d+(?:\.\d+)?)\s+degrees?\s+(celsius|centigrade|fahrenheit|kelvin)"""
    )

    private val TEMP_ABBR = mapOf(
        "celsius" to "\u00B0C", "centigrade" to "\u00B0C",
        "fahrenheit" to "\u00B0F", "kelvin" to "K",
    )

    // -- Fractions ------------------------------------------------------------

    private val FRACTION_MAP = mapOf(
        "one half" to "1/2", "one quarter" to "1/4", "three quarters" to "3/4",
        "one third" to "1/3", "two thirds" to "2/3",
        "one fifth" to "1/5", "two fifths" to "2/5", "three fifths" to "3/5", "four fifths" to "4/5",
        "one sixth" to "1/6", "five sixths" to "5/6",
        "one eighth" to "1/8", "three eighths" to "3/8", "five eighths" to "5/8",
        "seven eighths" to "7/8", "one tenth" to "1/10",
    )

    private val FRACTION_PATTERN: Regex = run {
        val alts = FRACTION_MAP.keys.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
        Regex("(?i)\\b(?:$alts)\\b")
    }

    // -- Time expressions -----------------------------------------------------

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
        Regex("(?i)\\b($hours)\\s+(?:($minutes)\\s+(AM|PM|a\\.m\\.|p\\.m\\.)|o'?clock(?:\\s+(AM|PM|a\\.m\\.|p\\.m\\.))?)\\b")
    }

    // -- Public API -----------------------------------------------------------

    /**
     * Apply all ITN transformations to [text].
     */
    fun normalize(text: String): String = normalizeNumbers(text)

    /**
     * Exposed as [internal] so [ContentNormalizer] can delegate the
     * `ContentNormalizer.normalizeNumbers` shim used by tests.
     */
    internal fun normalizeNumbers(text: String): String {
        var result = text

        result = normalizeTimeExpressions(result)

        result = ORDINAL_PATTERN.replace(result) { match ->
            val word = match.value.lowercase()
            val before = result.substring(0, match.range.first).trimEnd()
            val after = result.getOrNull(match.range.last + 1)
            val prevWord = before.split(WHITESPACE_SPLIT_REGEX).lastOrNull()
                ?.lowercase()?.trimEnd(',', '.', '!', '?') ?: ""
            when {
                after == ',' -> match.value
                prevWord == "at" -> match.value
                prevWord in setOf("but", "and", "or", "so", "now") -> match.value
                word == "second" && prevWord in setOf("i", "we", "they", "you", "he", "she") -> match.value
                else -> ORDINAL_WORDS[word] ?: match.value
            }
        }

        result = NUMBER_SPAN_PATTERN.replace(result) { match ->
            val matchText = match.value.trim()
            if (matchText.equals("one", ignoreCase = true)) {
                val before = result.substring(0, match.range.first).trimEnd()
                if (before.isNotEmpty() && before.last().isLetter()) {
                    val lastWord = before.split(WHITESPACE_SPLIT_REGEX).last().lowercase()
                    val isNumberWord = lastWord in DIGIT_WORDS || lastWord in TENS_WORDS ||
                        lastWord in MULTIPLIER_WORDS || lastWord == "number" || lastWord == "a"
                    if (!isNumberWord) return@replace matchText
                }
            }
            parseNumberSpan(matchText) ?: matchText
        }

        result = PERCENT_PATTERN.replace(result, "%")
        result = CURRENCY_DOLLAR_REWRITE.replace(result) { "\$${it.groupValues[1]}" }
        result = CURRENCY_EURO_REWRITE.replace(result) { "€${it.groupValues[1]}" }
        result = CURRENCY_POUND_REWRITE.replace(result) { "£${it.groupValues[1]}" }

        result = MEASUREMENT_PATTERN.replace(result) { m ->
            val num = m.groupValues[1]
            val unit = m.groupValues[2].lowercase()
            val abbr = MEASUREMENT_ABBR[unit]
            if (abbr != null) "$num $abbr" else m.value
        }

        result = TEMPERATURE_PATTERN.replace(result) { m ->
            val num = m.groupValues[1]
            val scale = m.groupValues[2].lowercase()
            val abbr = TEMP_ABBR[scale]
            if (abbr != null) "$num$abbr" else m.value
        }

        result = FRACTION_PATTERN.replace(result) { m ->
            FRACTION_MAP[m.value.lowercase()] ?: m.value
        }

        return result
    }

    // -- Private helpers ------------------------------------------------------

    private fun normalizeTimeExpressions(text: String): String =
        TIME_PATTERN.replace(text) { match ->
            val hourWord = match.groupValues[1].lowercase()
            val minuteWord = match.groupValues[2].lowercase().ifEmpty { null }
            val meridiem = match.groupValues[3].ifEmpty { match.groupValues[4] }.uppercase()
                .replace(".", "")

            val hour = HOUR_WORDS[hourWord] ?: return@replace match.value
            val minute = if (minuteWord != null) {
                MINUTE_WORDS[minuteWord] ?: return@replace match.value
            } else {
                0
            }

            val timeStr = "$hour:%02d".format(minute)
            if (meridiem.isNotEmpty()) "$timeStr $meridiem" else timeStr
        }

    private fun parseNumberSpan(span: String): String? {
        val tokens = span.lowercase()
            .replace("-", " ")
            .split(WHITESPACE_SPLIT_REGEX)
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
                    current += DIGIT_WORDS.getValue(token).toLong()
                }
                token in TENS_WORDS -> {
                    current += TENS_WORDS.getValue(token).toLong()
                }
                token in MULTIPLIER_WORDS -> {
                    val multiplier = MULTIPLIER_WORDS.getValue(token)
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
}
