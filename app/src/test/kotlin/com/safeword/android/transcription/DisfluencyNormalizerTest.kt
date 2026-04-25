package com.safeword.android.transcription

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DisfluencyNormalizer]: filler removal, self-repair retraction,
 * backtrack trigger handling, and stutter deduplication.
 *
 * Internal helpers [resolveSelfRepairs] and [resolveBacktracks] are tested directly
 * to isolate each behaviour from the rest of the pipeline.
 */
class DisfluencyNormalizerTest {

    // ════════════════════════════════════════════════════════════════════
    //  Filler removal (via normalize)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `filler uh is removed`() {
        assertEquals("the meeting is at three", DisfluencyNormalizer.normalize("uh the meeting is at three"))
    }

    @Test
    fun `filler um is removed`() {
        assertEquals("go ahead", DisfluencyNormalizer.normalize("um go ahead"))
    }

    @Test
    fun `filler hmm is removed`() {
        assertEquals("I think so", DisfluencyNormalizer.normalize("hmm I think so"))
    }

    @Test
    fun `multiple fillers are all removed`() {
        val result = DisfluencyNormalizer.normalize("uh um the uh problem is clear")
        assertFalse(result.contains("uh", ignoreCase = true), "uh not removed: $result")
        assertFalse(result.contains("um", ignoreCase = true), "um not removed: $result")
        assertTrue(result.contains("problem is clear", ignoreCase = true), "core text missing: $result")
    }

    @Test
    fun `filler-only input returns empty string`() {
        assertEquals("", DisfluencyNormalizer.normalize("uh um ah"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Self-repair — reparandum retraction (resolveSelfRepairs)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `scratch that retracts reparandum when first repair word matches before`() {
        // "i" appears in before → retract everything from "i went" to marker
        val result = DisfluencyNormalizer.resolveSelfRepairs("i went to the scratch that i drove")
        assertFalse(result.contains("went to the", ignoreCase = true), "reparandum not removed: $result")
        assertTrue(result.contains("i drove", ignoreCase = true), "repair text missing: $result")
    }

    @Test
    fun `i mean retracts reparandum when first repair word matches before`() {
        // "to" appears in before → retract from "to" onward
        val result = DisfluencyNormalizer.resolveSelfRepairs(
            "send the file to the dev i mean to the staging server",
        )
        assertFalse(result.contains("dev", ignoreCase = true), "reparandum not removed: $result")
        assertTrue(result.contains("staging server", ignoreCase = true), "repair text missing: $result")
    }

    @Test
    fun `no wait retracts reparandum when first repair word matches before`() {
        // "call" appears at index 0 in before → all of before is retracted
        val result = DisfluencyNormalizer.resolveSelfRepairs("call john no wait call james")
        assertFalse(result.contains("john", ignoreCase = true), "reparandum not removed: $result")
        assertTrue(result.contains("james", ignoreCase = true), "repair text missing: $result")
    }

    @Test
    fun `or rather retracts reparandum when first repair word matches before`() {
        // "send" appears in before → retract from "send the file" onward
        val result = DisfluencyNormalizer.resolveSelfRepairs(
            "please send the file or rather send the document",
        )
        assertFalse(result.contains("the file", ignoreCase = true), "reparandum not removed: $result")
        assertTrue(result.contains("the document", ignoreCase = true), "repair text missing: $result")
    }

    @Test
    fun `never mind removes marker when no overlap found — text before preserved`() {
        // "let's" does not appear in before → falls back to marker removal only
        val result = DisfluencyNormalizer.resolveSelfRepairs(
            "we should cancel the meeting never mind let's reschedule",
        )
        assertFalse(result.contains("never mind", ignoreCase = true), "marker not removed: $result")
    }

    @Test
    fun `scratch that with no overlap just removes marker and joins sides`() {
        // "let" does not appear in "please confirm" → no reparandum found, marker stripped
        val result = DisfluencyNormalizer.resolveSelfRepairs("please confirm scratch that let me know")
        assertFalse(result.contains("scratch that", ignoreCase = true), "marker not removed: $result")
        assertTrue(result.contains("let me know", ignoreCase = true), "after-text missing: $result")
    }

    @Test
    fun `multiple sequential self-repairs are all resolved`() {
        val result = DisfluencyNormalizer.resolveSelfRepairs(
            "i went to the store i mean i went to the mall i mean i went to the cinema",
        )
        assertFalse(result.contains("i mean", ignoreCase = true), "residual marker: $result")
        assertTrue(result.contains("cinema", ignoreCase = true), "final repair text missing: $result")
    }

    @Test
    fun `self-repair at start of input is stripped via normalize`() {
        // No before-text → marker removal falls back to just keeping the after-text
        val result = DisfluencyNormalizer.normalize("scratch that just go ahead")
        assertFalse(result.contains("scratch that", ignoreCase = true), "marker not removed: $result")
        assertTrue(result.contains("just go ahead", ignoreCase = true), "after-text missing: $result")
    }

    @Test
    fun `no self-repair marker leaves text unchanged`() {
        val input = "the quick brown fox"
        assertEquals(input, DisfluencyNormalizer.resolveSelfRepairs(input))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Backtrack triggers (resolveBacktracks)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `actually no discards mid-clause content before it`() {
        val result = DisfluencyNormalizer.resolveBacktracks(
            "i need more time actually no let's keep it short",
        )
        assertFalse(result.contains("need more time", ignoreCase = true), "pre-trigger not discarded: $result")
        assertTrue(result.contains("keep it short", ignoreCase = true), "post-trigger missing: $result")
    }

    @Test
    fun `actually no preserves completed prior sentence`() {
        // Clause separator '.' retained; only the fragment between '.' and trigger is discarded
        val result = DisfluencyNormalizer.resolveBacktracks(
            "we discussed the budget. i need more time actually no let's keep it short",
        )
        assertTrue(result.contains("we discussed the budget", ignoreCase = true), "prior sentence lost: $result")
        assertFalse(result.contains("need more time", ignoreCase = true), "inter-sentence fragment not discarded: $result")
        assertTrue(result.contains("keep it short", ignoreCase = true), "post-trigger missing: $result")
    }

    @Test
    fun `correction at start of input fires backtrack`() {
        val result = DisfluencyNormalizer.resolveBacktracks("correction let me start over")
        assertFalse(result.contains("correction", ignoreCase = true), "trigger not removed: $result")
        assertTrue(result.contains("let me start over", ignoreCase = true), "post-trigger missing: $result")
    }

    @Test
    fun `correction mid-sentence does not fire backtrack`() {
        // "correction" is mid-sentence (no terminal punct immediately before it in the match)
        val result = DisfluencyNormalizer.resolveBacktracks("i need a correction here")
        assertTrue(result.contains("correction here", ignoreCase = true), "mid-sentence correction wrongly triggered: $result")
    }

    @Test
    fun `mid-sentence correction skipped and later actually no fires — BUG-2 regression`() {
        // Regression: resolveBacktracks used break instead of continue+searchFrom advancement;
        // "correction" mid-sentence must be skipped so "actually no" can fire.
        val result = DisfluencyNormalizer.resolveBacktracks(
            "i need a correction here actually no let me start again",
        )
        assertFalse(result.contains("need a correction here", ignoreCase = true), "pre-trigger not discarded: $result")
        assertTrue(result.contains("let me start again", ignoreCase = true), "post-trigger missing: $result")
    }

    @Test
    fun `actually no with nothing following returns blank`() {
        val result = DisfluencyNormalizer.resolveBacktracks("this is wrong actually no")
        assertTrue(result.isBlank(), "expected blank, got: '$result'")
    }

    @Test
    fun `no backtrack trigger leaves text unchanged`() {
        val input = "the meeting is at three"
        assertEquals(input, DisfluencyNormalizer.resolveBacktracks(input))
    }

    // ════════════════════════════════════════════════════════════════════
    //  Stutter deduplication (via normalize)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `duplicate word stutter is collapsed to single`() {
        assertEquals("the problem is clear", DisfluencyNormalizer.normalize("the the problem is clear"))
    }

    @Test
    fun `triple word stutter is collapsed to single`() {
        assertEquals("the problem is clear", DisfluencyNormalizer.normalize("the the the problem is clear"))
    }

    @Test
    fun `verb stutter is collapsed`() {
        assertEquals("is clear", DisfluencyNormalizer.normalize("is is is clear"))
    }

    // ════════════════════════════════════════════════════════════════════
    //  normalize — integration (filler + repair + backtrack + stutter)
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `normalize chains filler removal then self-repair retraction`() {
        val result = DisfluencyNormalizer.normalize("uh i went to the store scratch that i drove")
        assertFalse(result.contains("uh", ignoreCase = true), "filler not removed: $result")
        assertFalse(result.contains("went to the store", ignoreCase = true), "reparandum not retracted: $result")
        assertTrue(result.contains("i drove", ignoreCase = true), "repair text missing: $result")
    }

    @Test
    fun `normalize handles empty string`() {
        assertEquals("", DisfluencyNormalizer.normalize(""))
    }

    @Test
    fun `normalize handles whitespace-only string`() {
        assertEquals("", DisfluencyNormalizer.normalize("   "))
    }

    @Test
    fun `normalize leaves clean text unchanged`() {
        val input = "the meeting is at three"
        assertEquals(input, DisfluencyNormalizer.normalize(input))
    }
}
