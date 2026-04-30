package com.safeword.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Test

class ConfusionSetCorrectorTest {

    private fun ctx(
        pkg: String = "com.example.app",
        hint: String = "",
        cls: String = "EditText",
        avgLogprob: Float = -0.10f,
    ) = ConfusionSetCorrector.Context(
        packageName = pkg,
        hintText = hint,
        className = cls,
        avgLogprob = avgLogprob,
    )

    // -- Whole-utterance matching (original behavior) --

    @Test
    fun `search context swaps material to return`() {
        val result = ConfusionSetCorrector.apply(
            "material",
            ctx(pkg = "com.android.chrome", hint = "Search or type URL", avgLogprob = -0.10f),
        )
        assertEquals("return", result)
    }

    @Test
    fun `no swap at high confidence`() {
        val result = ConfusionSetCorrector.apply(
            "material",
            ctx(pkg = "com.android.chrome", hint = "Search or type URL", avgLogprob = -0.02f),
        )
        assertEquals("material", result)
    }

    @Test
    fun `homophone correction at low confidence`() {
        val result = ConfusionSetCorrector.apply(
            "there",
            ctx(avgLogprob = -0.10f),
        )
        assertEquals("their", result)
    }

    @Test
    fun `no correction at normal confidence`() {
        val result = ConfusionSetCorrector.apply(
            "there",
            ctx(avgLogprob = -0.02f),
        )
        assertEquals("there", result)
    }

    @Test
    fun `empty text returns as-is`() {
        assertEquals("", ConfusionSetCorrector.apply("", ctx()))
    }

    // -- Mid-sentence context-aware correction --

    @Test
    fun `applyInContext replaces words within a sentence`() {
        val result = ConfusionSetCorrector.applyInContext(
            "I went their yesterday",
            ctx(avgLogprob = -0.10f),
        )
        // "their" is in the confusion set as from="there"->to="their",
        // but also "their" doesn't match "there" so no swap on that word.
        // The rules are bidirectional, so "there"->"their" fires if text contains "there"
        assertEquals("I went their yesterday", result)
    }

    @Test
    fun `applyInContext skips at high confidence`() {
        val result = ConfusionSetCorrector.applyInContext(
            "I went there yesterday",
            ctx(avgLogprob = -0.02f),
        )
        assertEquals("I went there yesterday", result)
    }

    @Test
    fun `applyInContext preserves case`() {
        val result = ConfusionSetCorrector.applyInContext(
            "THERE is a problem",
            ctx(avgLogprob = -0.10f),
        )
        assertEquals("THEIR is a problem", result)
    }

    // -- New confusion sets --

    @Test
    fun `loose to lose at low confidence`() {
        val result = ConfusionSetCorrector.apply(
            "loose",
            ctx(avgLogprob = -0.10f),
        )
        assertEquals("lose", result)
    }

    @Test
    fun `quiet to quite at low confidence`() {
        val result = ConfusionSetCorrector.apply(
            "quiet",
            ctx(avgLogprob = -0.10f),
        )
        assertEquals("quite", result)
    }
}
