package com.safeword.android.transcription

/**
 * Small, conservative confusion-set corrections for known ASR swaps.
 *
 * Scope is intentionally narrow:
 * - only exact/short substitutions
 * - only in matching UI contexts (for example, browser/search fields)
 * - only when decode confidence is mediocre (avgLogprob below threshold)
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

    private val rules: List<Rule> = listOf(
        Rule(from = "materials", to = "returns") { ctx, text ->
            isLikelySearchContext(ctx) && isShortSingleWord(text) && ctx.avgLogprob <= -0.08f
        },
        Rule(from = "material", to = "return") { ctx, text ->
            isLikelySearchContext(ctx) && isShortSingleWord(text) && ctx.avgLogprob <= -0.08f
        },
    )

    fun apply(rawText: String, context: Context): String {
        val normalized = rawText.trim()
        if (normalized.isEmpty()) return rawText

        for (rule in rules) {
            if (normalized.equals(rule.from, ignoreCase = true) && rule.applies(context, normalized)) {
                return rule.to
            }
        }
        return rawText
    }

    private fun isShortSingleWord(text: String): Boolean =
        text.length in 3..18 && !text.contains(' ')

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
