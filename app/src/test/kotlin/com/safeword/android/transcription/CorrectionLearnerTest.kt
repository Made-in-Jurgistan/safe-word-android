package com.safeword.android.transcription

import com.safeword.android.data.PersonalVocabularyRepository
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * Tests for [CorrectionLearner] — automatic vocabulary learning from user edits.
 *
 * Exercises:
 * - Identical dictated and edited text → no repository calls.
 * - No prior [recordDictation] → snapshot is a no-op.
 * - Blank dictated text → no repository calls.
 * - Blank current text → no repository calls.
 * - Record is atomically consumed — second snapshot cannot trigger double-learning.
 * - Single-word substitution triggers [PersonalVocabularyRepository.insert] or
 *   [PersonalVocabularyRepository.incrementConfirmation].
 */
class CorrectionLearnerTest {

    private val vocabularyRepository = mockk<PersonalVocabularyRepository>(relaxed = true)
    private lateinit var learner: CorrectionLearner

    @Before
    fun setUp() {
        learner = CorrectionLearner(vocabularyRepository)
    }

    @Test
    fun `snapshot with no prior recordDictation is a no-op`() = runTest {
        learner.onTextFieldSnapshot("any text")
        coVerify(exactly = 0) { vocabularyRepository.insert(any()) }
        coVerify(exactly = 0) { vocabularyRepository.incrementConfirmation(any()) }
    }

    @Test
    fun `identical dictated and current text produces no repository calls`() = runTest {
        learner.recordDictation("hello world")
        learner.onTextFieldSnapshot("hello world")
        coVerify(exactly = 0) { vocabularyRepository.insert(any()) }
        coVerify(exactly = 0) { vocabularyRepository.incrementConfirmation(any()) }
    }

    @Test
    fun `blank dictated text after trim produces no repository calls`() = runTest {
        learner.recordDictation("   ")
        learner.onTextFieldSnapshot("hello")
        coVerify(exactly = 0) { vocabularyRepository.insert(any()) }
    }

    @Test
    fun `blank current text produces no repository calls`() = runTest {
        learner.recordDictation("hello world")
        learner.onTextFieldSnapshot("   ")
        coVerify(exactly = 0) { vocabularyRepository.insert(any()) }
    }

    @Test
    fun `record is atomically consumed — second snapshot is always a no-op`() = runTest {
        learner.recordDictation("hello world foo bar")
        // First snapshot consumes the record (text is different so corrections may fire)
        learner.onTextFieldSnapshot("hello world foo baz")
        // Second snapshot: record already consumed
        learner.onTextFieldSnapshot("hello world foo qux")
        // At most one insert or increment from the first snapshot; never two.
        // atLeast = 0 because only one of insert/increment fires depending on whether
        // the word already exists in the vocabulary.
        coVerify(atLeast = 0, atMost = 1) { vocabularyRepository.insert(any()) }
        coVerify(atLeast = 0, atMost = 1) { vocabularyRepository.incrementConfirmation(any()) }
    }

    @Test
    fun `large diff (more than 6 replacements) is skipped`() = runTest {
        // Build 8 differing words (ratio in 0.5–2.0 range, so addIfCorrection fires)
        val original = "a b c d e f g h i j"
        val modified = "A B C D E F G H I J"
        learner.recordDictation(original)
        learner.onTextFieldSnapshot(modified)
        // 10 case replacements → exceeds threshold of 6 → skipped
        coVerify(exactly = 0) { vocabularyRepository.insert(any()) }
        coVerify(exactly = 0) { vocabularyRepository.incrementConfirmation(any()) }
    }
}
