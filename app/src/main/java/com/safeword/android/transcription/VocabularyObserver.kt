package com.safeword.android.transcription

import com.safeword.android.data.PersonalVocabularyRepository
import com.safeword.android.data.db.PersonalVocabularyEntity
import com.safeword.android.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes personal vocabulary changes and propagates invalidations to correction caches.
 *
 * Extracted from [TranscriptionCoordinator] to remove [PersonalVocabularyRepository],
 * [VocabularyPatternCache], and [PhoneticIndex] from the coordinator's constructor.
 *
 * The vocabulary [StateFlow] is shared Eagerly so all consumers always see a warm snapshot
 * without an additional cold-start collect delay.
 */
@Singleton
class VocabularyObserver @Inject constructor(
    private val vocabularyRepository: PersonalVocabularyRepository,
    private val patternCache: VocabularyPatternCache,
    private val phoneticIndex: PhoneticIndex,
    private val confusionSetCorrector: ConfusionSetCorrector,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Confirmed vocabulary entries.
     *
     * Shared with [SharingStarted.Eagerly] intentionally: all consumers read
     * [StateFlow.value] directly (no active collectors), so [SharingStarted.WhileSubscribed]
     * would prevent the upstream Room flow from ever starting — leaving [StateFlow.value]
     * permanently empty and skipping the [onEach] cache-invalidation side-effects.
     * The [personal_vocabulary] table is small and updated infrequently, so the constant
     * Room [InvalidationTracker] subscription is negligible overhead.
     */
    val confirmedVocabulary: StateFlow<List<PersonalVocabularyEntity>> =
        vocabularyRepository.getConfirmedEntries()
            // Skip cache rebuilds when only `lastUsedAt` changed — correction-relevant
            // fields (phrase, writtenForm, confirmationCount, dormancy, scoping) are
            // unchanged in that case, so rebuilding all caches is wasted work.
            .distinctUntilChangedBy { list ->
                list.map { e ->
                    "${e.phrase}|${e.writtenForm}|${e.confirmationCount}|${e.isDormant}|${e.appPackage}|${e.contextHint}"
                }
            }
            .onEach { vocab ->
                patternCache.invalidate(vocab.map { it.phrase }.toHashSet())
                phoneticIndex.rebuild(vocab)
                confusionSetCorrector.onVocabularyChanged(vocab)
            }
            .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /**
     * Prewarm the pattern cache with the most-used vocabulary entries.
     * Call during service startup alongside model preloading.
     */
    fun preloadVocabulary() {
        scope.launch {
            val hotVocab = withContext(Dispatchers.IO) {
                vocabularyRepository.getTopConfirmedEntries(50)
            }
            patternCache.prewarm(hotVocab.map { it.phrase })
            Timber.d("[INIT] VocabularyObserver.preloadVocabulary | phrases=%d", hotVocab.size)
        }
    }

    /**
     * Record that the given vocabulary [phrases] were used in a dictation session.
     * Dispatched asynchronously to IO to avoid blocking the transcription thread.
     */
    fun recordVocabUsed(phrases: List<String>) {
        if (phrases.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            phrases.forEach { vocabularyRepository.updateLastUsedAt(it, now) }
        }
    }
}
