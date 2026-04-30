package com.safeword.android.transcription

/**
 * Conservative confusion-set corrections for known ASR homophones and common swaps.
 *
 * Scope is intentionally narrow:
 * - only exact whole-utterance substitutions (not mid-sentence replacement)
 * - only in matching UI contexts
 * - only when decode confidence is mediocre (avgLogprob below threshold) OR the swap
 *   is unambiguous in context (contractions vs. possessives)
 *
 * Rules target three categories:
 *  1. Search-context swaps (URL-bar / search-field confusion)
 *  2. Homophone contractions (their/there/they're, your/you're, its/it's, etc.)
 *  3. Broad homophones corrected only at low confidence
 */
object ConfusionSetCorrector {

    data class Context(
        val packageName: String,
        val hintText: String,
        val className: String,
        val avgLogprob: Float,
    )

    private data class Rule(
        val from: String,
        val to: String,
        val applies: (Context, String) -> Boolean,
    )

    private val LOW_CONF = -0.08f   // threshold for "mediocre" decode confidence

    private val rules: List<Rule> = listOf(

        // ── 1. Search-context swaps ───────────────────────────────────────────────
        Rule("materials", "returns") { ctx, text ->
            isLikelySearchContext(ctx) && isShortSingleWord(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("material", "return") { ctx, text ->
            isLikelySearchContext(ctx) && isShortSingleWord(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("chrome", "home") { ctx, text ->
            isLikelySearchContext(ctx) && isShortSingleWord(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("cite", "site") { ctx, text ->
            isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("sight", "site") { ctx, text ->
            isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },

        // ── 2. Homophone contractions (context-free; only at low confidence) ─────
        //    "their" is the most frequent form — keep unless confidence is low
        Rule("there", "their") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("they're", "their") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("your", "you're") { ctx, text ->
            !isLikelySearchContext(ctx) && isContraction(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("you're", "your") { ctx, text ->
            !isLikelySearchContext(ctx) && !isContraction(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("its", "it's") { ctx, text ->
            !isLikelySearchContext(ctx) && isContraction(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("it's", "its") { ctx, text ->
            !isLikelySearchContext(ctx) && !isContraction(text) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("were", "we're") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("whos", "whose") { ctx, _ ->
            ctx.avgLogprob <= LOW_CONF
        },
        Rule("who's", "whose") { ctx, text ->
            !isContraction(text) && ctx.avgLogprob <= LOW_CONF
        },

        // ── 3. Broad homophones (low confidence only) ────────────────────────────
        Rule("to", "too") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF - 0.05f
        },
        Rule("two", "too") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF - 0.05f
        },
        Rule("buy", "by") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("bye", "by") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("hear", "here") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("weather", "whether") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("accept", "except") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("except", "accept") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("affect", "effect") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("effect", "affect") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("than", "then") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("then", "than") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("knew", "new") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("know", "no") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF - 0.05f
        },
        Rule("no", "know") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF - 0.05f
        },
        Rule("write", "right") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("right", "write") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("bare", "bear") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("bear", "bare") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("blew", "blue") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("threw", "through") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("through", "threw") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("peak", "peek") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("peek", "peak") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("brake", "break") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("break", "brake") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("meat", "meet") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("meet", "meat") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("sense", "since") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("since", "sense") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("passed", "past") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("past", "passed") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("waist", "waste") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("waste", "waist") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("weak", "week") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("week", "weak") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("led", "lead") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("lead", "led") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },

        // ── 4. Additional homophones (low confidence only) ────────────────────────
        Rule("wear", "where") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("where", "wear") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("piece", "peace") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("peace", "piece") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("principle", "principal") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("principal", "principle") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("stationary", "stationery") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("stationery", "stationary") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("compliment", "complement") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("complement", "compliment") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("desert", "dessert") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("dessert", "desert") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("loose", "lose") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("lose", "loose") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("quite", "quiet") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
        Rule("quiet", "quite") { ctx, _ ->
            !isLikelySearchContext(ctx) && ctx.avgLogprob <= LOW_CONF
        },
    )

    fun apply(rawText: String, context: Context): String {
        val normalized = rawText.trim()
        if (normalized.isEmpty()) return rawText

        // 1. Whole-utterance exact match (original behavior)
        for (rule in rules) {
            if (normalized.equals(rule.from, ignoreCase = true) && rule.applies(context, normalized)) {
                return rule.to
            }
        }
        return rawText
    }

    /**
     * Mid-sentence context-aware correction.
     *
     * Unlike [apply] which checks whole-utterance matches, this replaces
     * individual words within longer text. Only fires at low confidence to
     * minimise false positives.
     */
    fun applyInContext(text: String, context: Context): String {
        if (text.isBlank() || context.avgLogprob > LOW_CONF) return text

        var result = text
        for (rule in rules) {
            if (!rule.applies(context, text)) continue
            val pattern = Regex("(?i)\\b${Regex.escape(rule.from)}\\b")
            result = pattern.replace(result) { match ->
                // Preserve original case pattern
                preserveCase(match.value, rule.to)
            }
        }
        return result
    }

    /** Preserve the case pattern of the original word when applying correction. */
    private fun preserveCase(original: String, replacement: String): String {
        return when {
            original.all { it.isUpperCase() } -> replacement.uppercase()
            original.first().isUpperCase() -> replacement.replaceFirstChar { it.uppercaseChar() }
            else -> replacement
        }
    }

    private fun isShortSingleWord(text: String): Boolean =
        text.length in 3..18 && !text.contains(' ')

    /** Heuristic: text contains an apostrophe (common in contractions). */
    private fun isContraction(text: String): Boolean = text.contains("'")

    private fun isLikelySearchContext(ctx: Context): Boolean {
        val pkg = ctx.packageName.lowercase()
        val hint = ctx.hintText.lowercase()
        val cls = ctx.className.lowercase()
        val browserPkg = pkg.contains("chrome") ||
            pkg.contains("browser") ||
            pkg.contains("firefox") ||
            pkg.contains("brave") ||
            pkg.contains("edge") ||
            pkg.contains("opera") ||
            pkg.contains("duckduckgo")
        val searchHint = hint.contains("search") ||
            hint.contains("address") ||
            hint.contains("url") ||
            hint.contains("type to search")
        val urlBarClass = cls.contains("url") || cls.contains("search")
        return browserPkg || searchHint || urlBarClass
    }
}
