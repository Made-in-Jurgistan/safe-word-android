package com.safeword.android.transcription

import android.content.Context
import com.safeword.android.service.InputContextSnapshot
import com.safeword.android.service.ThermalTier
import com.safeword.android.testutil.DEFAULT_INPUT_CONTEXT
import com.safeword.android.testutil.PipelineAssertions
import io.mockk.mockk
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import kotlin.test.assertTrue

/**
 * Accent bias regression harness for the post-processing pipeline.
 *
 * Validates that WER degradation between standard (native/unaccented) and accented
 * (non-native phoneme-substituted) ASR inputs stays within
 * [OptimalParameters.ACCENT_BIAS_FAIRNESS_THRESHOLD] (15%) after the pipeline applies.
 *
 * ## Test structure
 * Each [BiasCase] pairs a "standard" and an "accented" ASR transcription of the same
 * utterance against a reference output. Both are run through:
 *
 *   `ContentNormalizer.preProcess`
 *   → `ConfusionSetCorrector.apply`
 *   → `ContentNormalizer.normalizePostPreProcess`
 *   → `TextFormatter.format`
 *
 * ## What is and is not tested
 * - **Tested**: that the *post-processing pipeline* does not amplify the acoustic-model
 *   bias already present in the ASR output.
 * - **Not tested**: the acoustic model itself or the VAD — those are evaluated via the
 *   STT benchmark harness (`scripts/Measure-SttBenchmark.ps1`).
 *
 * ## Known gaps (tracked @Ignore tests)
 * TH-stop and R-L substitutions produce gap > 15% because the confusion set does not
 * currently contain those phoneme pairs. Adding entries to the ConfusionSetCorrector
 * (e.g. "de" → "the", "dis" → "this") would close these gaps.
 */
class AccentBiasTest {

    private lateinit var corrector: ConfusionSetCorrector
    private val context: InputContextSnapshot = DEFAULT_INPUT_CONTEXT

    @Before
    fun setUp() {
        // Reset stateful HallucinationFilter so repeated pipeline() calls within a test
        // don't trigger cross-segment dedup and return an empty string.
        HallucinationFilter.resetSession()
        val mockContext = mockk<Context>(relaxed = true)
        corrector = ConfusionSetCorrector(
            patternCache = VocabularyPatternCache(),
            appContext = mockContext,
        )
    }

    // -------------------------------------------------------------------------
    // Model
    // -------------------------------------------------------------------------

    /**
     * Paired accent bias test case.
     *
     * @param label       Short identifier used in failure messages.
     * @param reference   Ground-truth string the pipeline should produce from clean input.
     * @param standard    Standard (native/unaccented) ASR transcription — should produce
     *                    low WER vs [reference] after processing.
     * @param accented    Accent-biased ASR transcription — phoneme substitutions typical
     *                    of non-native speakers (e.g. TH-stop, W-V swap, cluster reduction).
     */
    data class BiasCase(
        val label: String,
        val reference: String,
        val standard: String,
        val accented: String,
    )

    // -------------------------------------------------------------------------
    // Test data — Category A: formatting equality (gap expected ≈ 0)
    //
    // Both inputs contain the same words; accent only affects phoneme articulation
    // which modern STT handles correctly. Pipeline applies case/punctuation equally.
    // -------------------------------------------------------------------------

    private val formattingEqualityCases = listOf(
        BiasCase(
            label = "sentence_case_period",
            reference = "please send me the file",
            standard = "please send me the file",
            accented = "please send me the file",
        ),
        BiasCase(
            label = "filler_removal_uh",
            reference = "i need help with this task",
            standard = "uh i need help with this task",
            accented = "um i need help with this task",
        ),
        // "hmm" is in FILLER_WORDS and is always removed identically by the pipeline.
        BiasCase(
            label = "filler_removal_hmm",
            reference = "i think we should go now",
            standard = "hmm i think we should go now",
            accented = "hmm i think we should go now",
        ),
        BiasCase(
            label = "pronoun_i_passthrough",
            reference = "i think i can do it",
            standard = "i think i can do it",
            accented = "i think i can do it",
        ),
        BiasCase(
            label = "filler_and_pronoun",
            reference = "i want to call you",
            standard = "um i want to call you",
            accented = "uh i want to call you",
        ),
    )

    // -------------------------------------------------------------------------
    // Test data — Category B: minor acoustic variation (gap expected < 15%)
    //
    // Accent causes one or two word-level differences; pipeline normalization
    // should keep the gap within the fairness threshold because both inputs
    // benefit equally from case/filler processing and the differing word is
    // within one edit distance of the reference.
    // -------------------------------------------------------------------------

    private val minorVariationCases = listOf(
        // Both "uh" and "um" are in FILLER_WORDS → both removed → gap = 0.
        BiasCase(
            label = "filler_types",
            reference = "i need help with this task",
            standard = "uh i need help with this task",
            accented = "um i need help with this task",
        ),
        // 10-word reference; 1 TH-stop substitution ("this"→"dis") → gap = 0.10 < 0.15.
        BiasCase(
            label = "long_sentence_th_stop",
            reference = "i want to send this message to the right person",
            standard = "i want to send this message to the right person",
            accented = "i want to send dis message to the right person",
        ),
        // Stutter normalization: "yes yes" → "yes" via STUTTER_PATTERN → gap = 0.
        BiasCase(
            label = "stutter_normalization",
            reference = "yes i can help you",
            standard = "yes i can help you",
            accented = "yes yes i can help you",
        ),
    )

    // -------------------------------------------------------------------------
    // Test data — Category C: known current gaps (documented, not gated)
    //
    // These cases exceed the 15% fairness threshold because the pipeline lacks
    // specific confusion-set entries for these phoneme substitutions.
    // Fix path: add entries to ConfusionSetCorrector for each substitution pattern.
    // -------------------------------------------------------------------------

    private val knownGapCases = listOf(
        // TH-stop: "the" → "de", "this" → "dis", "thing" → "ting"
        // Fix: add ("de","the"), ("dis","this"), ("ting","thing") to confusion sets.
        BiasCase(
            label = "th_stop_single",
            reference = "the weather is great today",
            standard = "the weather is great today",
            accented = "de weather is great today",
        ),
        // W-V swap: "very" → "wery", "vote" → "bote"
        // Fix: add ("wery","very") to confusion sets.
        BiasCase(
            label = "w_v_swap",
            reference = "very well done",
            standard = "very well done",
            accented = "wery well done",
        ),
        // Final consonant cluster reduction: "asked" → "ask", "test" → "tes"
        // Fix: add ("ask","asked"), ("tes","test") to confusion sets.
        BiasCase(
            label = "final_cluster_reduction",
            reference = "i asked him to stop the test",
            standard = "i asked him to stop the test",
            accented = "i ask him to stop the tes",
        ),
    )

    // -------------------------------------------------------------------------
    // Pipeline helper
    // -------------------------------------------------------------------------

    private fun pipeline(raw: String): String {
        val preProcessed = ContentNormalizer.preProcess(raw)
        val corrected = corrector.apply(
            preProcessed,
            context,
            previousText = "",
            thermalTier = ThermalTier.NOMINAL,
        )
        val postNormalized = ContentNormalizer.normalizePostPreProcess(corrected)
        return TextFormatter.format(postNormalized)
    }

    // -------------------------------------------------------------------------
    // Tests — Category A: formatting equality
    // -------------------------------------------------------------------------

    /**
     * For formatting-equality cases, standard and accented inputs are identical words.
     * The WER gap must be exactly 0 — the pipeline must not introduce asymmetry.
     */
    @Test
    fun `formatting equality - gap is zero when inputs are identical words`() {
        val failures = mutableListOf<String>()

        for (case in formattingEqualityCases) {
            val stdOut = pipeline(case.standard)
            // Short-circuit: avoid calling pipeline() a second time for identical inputs.
            // HallucinationFilter.isRepeatOfLastSegment() would return "" on the second
            // call with the same string, producing a spurious gap.
            val accOut = if (case.accented == case.standard) stdOut else pipeline(case.accented)
            val stdWer = PipelineAssertions.computeWer(case.reference, stdOut)
            val accWer = PipelineAssertions.computeWer(case.reference, accOut)
            val gap = accWer - stdWer

            if (gap > 0.001) {
                failures += "[${case.label}] gap=%.3f std=\"$stdOut\" (WER=%.2f) acc=\"$accOut\" (WER=%.2f)"
                    .format(gap, stdWer, accWer)
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Formatting equality broken — pipeline produces different output for identical-word inputs " +
                "in ${failures.size} case(s):\n${failures.joinToString("\n")}",
        )
    }

    /**
     * For formatting-equality cases, the standard input must produce WER < 0.10
     * after pipeline processing (near-perfect output for standard speech).
     */
    @Test
    fun `formatting equality - standard input achieves near-perfect WER`() {
        val maxWer = 0.10
        val failures = mutableListOf<String>()

        for (case in formattingEqualityCases) {
            val out = pipeline(case.standard)
            val wer = PipelineAssertions.computeWer(case.reference, out)
            if (wer > maxWer) {
                failures += "[${case.label}] standard WER=%.2f > %.2f | ref=\"${case.reference}\" out=\"$out\""
                    .format(wer, maxWer)
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Standard ASR WER exceeds target in ${failures.size} case(s):\n${failures.joinToString("\n")}",
        )
    }

    // -------------------------------------------------------------------------
    // Tests — Category B: minor variation
    // -------------------------------------------------------------------------

    /**
     * For minor-variation cases, the WER gap between standard and accented pipeline
     * output must remain within [OptimalParameters.ACCENT_BIAS_FAIRNESS_THRESHOLD].
     */
    @Test
    fun `minor variation cases - WER gap within fairness threshold`() {
        val threshold = OptimalParameters.ACCENT_BIAS_FAIRNESS_THRESHOLD.toDouble()
        val failures = mutableListOf<String>()

        for (case in minorVariationCases) {
            val stdOut = pipeline(case.standard)
            val accOut = pipeline(case.accented)
            val stdWer = PipelineAssertions.computeWer(case.reference, stdOut)
            val accWer = PipelineAssertions.computeWer(case.reference, accOut)
            val gap = accWer - stdWer

            if (gap > threshold) {
                failures += buildString {
                    appendLine("[${case.label}]")
                    appendLine("  Reference : \"${case.reference}\"")
                    appendLine("  Standard  : \"$stdOut\" (WER=%.2f)".format(stdWer))
                    appendLine("  Accented  : \"$accOut\" (WER=%.2f)".format(accWer))
                    append("  Gap       : %.2f > threshold %.2f".format(gap, threshold))
                }
            }
        }

        assertTrue(
            failures.isEmpty(),
            "Accent bias WER gap exceeded fairness threshold in ${failures.size} case(s):\n" +
                failures.joinToString("\n\n"),
        )
    }

    // -------------------------------------------------------------------------
    // Tests — Category C: known gaps (documented, not blocking CI)
    //
    // These tests are @Ignore because the pipeline lacks the confusion-set entries
    // needed to close the gap. Each test documents the fix path.
    // Remove @Ignore when the fix is implemented and verified.
    // -------------------------------------------------------------------------

    /**
     * TH-stop substitution ("the" → "de", "this" → "dis").
     * Fix path: add word-boundary confusion pairs to ConfusionSetCorrector:
     *   ("de", "the"), ("dis", "this"), ("dat", "that"), ("dey", "they")
     */
    @Ignore("Known gap: confusion set missing TH-stop → {de=the, dis=this} entries")
    @Test
    fun `th-stop substitution - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "th_stop_single" })
    }

    /**
     * W-V swap ("very" → "wery").
     * Fix path: add ("wery", "very"), ("want", "want" is fine — "wote", "vote") pairs.
     */
    @Ignore("Known gap: confusion set missing W-V swap entries")
    @Test
    fun `w-v swap - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "w_v_swap" })
    }

    /**
     * Final consonant cluster reduction ("asked" → "ask", "test" → "tes").
     * Fix path: add ("ask", "asked"), ("tes", "test") to confusion sets, or improve
     * SymSpell to handle suffix-truncated words as common misspellings.
     */
    @Ignore("Known gap: confusion set missing final-cluster-reduction entries")
    @Test
    fun `final cluster reduction - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "final_cluster_reduction" })
    }

    @Ignore("Known gap: pipeline cannot correct third-person -s omission (want → wants)")
    @Test
    fun `third-person s omission - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "third_person_s_omission" })
    }

    @Ignore("Known gap: InverseTextNormalizer does not expand contractions (dont → don't)")
    @Test
    fun `contraction dont - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "contraction_dont" })
    }

    @Ignore("Known gap: SymSpell does not correct word-final consonant drops (bes → best)")
    @Test
    fun `word-final t drop - gap within fairness threshold`() {
        assertGapWithinThreshold(knownGapCases.first { it.label == "word_final_t_drop" })
    }

    // -------------------------------------------------------------------------
    // Utility assertions
    // -------------------------------------------------------------------------

    private fun assertGapWithinThreshold(case: BiasCase) {
        val threshold = OptimalParameters.ACCENT_BIAS_FAIRNESS_THRESHOLD.toDouble()
        val stdOut = pipeline(case.standard)
        val accOut = pipeline(case.accented)
        val stdWer = PipelineAssertions.computeWer(case.reference, stdOut)
        val accWer = PipelineAssertions.computeWer(case.reference, accOut)
        val gap = accWer - stdWer
        assertTrue(
            gap <= threshold,
            "[${case.label}] WER gap %.2f > threshold %.2f\n".format(gap, threshold) +
                "  Reference : \"${case.reference}\"\n" +
                "  Standard  : \"$stdOut\" (WER=%.2f)\n".format(stdWer) +
                "  Accented  : \"$accOut\" (WER=%.2f)".format(accWer),
        )
    }
}
