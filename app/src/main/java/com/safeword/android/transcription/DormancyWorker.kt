package com.safeword.android.transcription

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.safeword.android.data.PersonalVocabularyRepository
import com.safeword.android.data.db.PersonalVocabularyEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import android.database.sqlite.SQLiteException
import timber.log.Timber

/**
 * Weekly WorkManager task that marks low-activity auto-learned vocabulary entries as dormant.
 *
 * An auto-learned entry is considered dormant when its
 * [com.safeword.android.data.db.PersonalVocabularyEntity.activityScore] falls below
 * [DORMANCY_THRESHOLD], indicating it has not been encountered recently and has low
 * confirmation count. Dormant entries are kept in the database but excluded from active
 * correction by [ConfusionSetCorrector].
 *
 * Entries that have recovered (score ≥ [DORMANCY_THRESHOLD] but were previously marked
 * dormant, e.g. due to a manual confirmation bump) are reactivated.
 *
 * Schedule via [com.safeword.android.SafeWordApp.scheduleDormancyCheck].
 */
@HiltWorker
class DormancyWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val vocabularyRepository: PersonalVocabularyRepository,
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        Timber.i("[ENTER] DormancyWorker.doWork | checking auto-learned entries for dormancy")
        return try {
            val candidates = vocabularyRepository.getAutoLearnedUnconfirmed()
            val (dormantCount, reactivatedCount) = evaluateDormancy(candidates, logPrefix = "")

            Timber.i(
                "[EXIT] DormancyWorker.doWork | checked=%d dormant=%d reactivated=%d",
                candidates.size, dormantCount, reactivatedCount,
            )

            // Second pass: confirmed entries (manual, contacts, or auto-learned ≥ threshold)
            // that have not been applied for 90+ days are also eligible for dormancy.
            // Manual and contacts entries are never auto-dormanted — they represent intentional
            // user additions. Only auto-learned confirmed entries are evaluated here.
            val ninetyDaysCutoff = System.currentTimeMillis() - 90L * 86_400_000L
            val staleConfirmed = vocabularyRepository.getStaleConfirmedEntries(ninetyDaysCutoff)
            val (staleDormantCount, staleReactivatedCount) = evaluateDormancy(staleConfirmed, logPrefix = "staleConfirmed ")

            Timber.i(
                "[EXIT] DormancyWorker.doWork | staleConfirmed checked=%d dormant=%d reactivated=%d",
                staleConfirmed.size, staleDormantCount, staleReactivatedCount,
            )
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteException) {
            Timber.e(e, "[ERROR] DormancyWorker.doWork | transient DB error — scheduling retry")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "[ERROR] DormancyWorker.doWork | failed")
            Result.failure()
        }
    }

    /**
     * Evaluate dormancy for a list of vocabulary entries, marking or reactivating as needed.
     * @return Pair(dormantCount, reactivatedCount)
     */
    private suspend fun evaluateDormancy(
        entries: List<PersonalVocabularyEntity>,
        logPrefix: String,
    ): Pair<Int, Int> {
        var dormantCount = 0
        var reactivatedCount = 0
        val now = System.currentTimeMillis()
        for (entry in entries) {
            val score = entry.activityScore(now)
            when {
                score < DORMANCY_THRESHOLD && !entry.isDormant -> {
                    vocabularyRepository.setDormant(entry.id)
                    vocabularyRepository.decrementConfirmationCount(entry.id)
                    dormantCount++
                    Timber.d("[BRANCH] DormancyWorker | %sdormant phrase='%s' score=%.2f", logPrefix, entry.phrase, score)
                }
                score >= DORMANCY_THRESHOLD && entry.isDormant -> {
                    vocabularyRepository.resetDormancy(entry.id)
                    reactivatedCount++
                    Timber.d("[BRANCH] DormancyWorker | %sreactivated phrase='%s' score=%.2f", logPrefix, entry.phrase, score)
                }
            }
        }
        return Pair(dormantCount, reactivatedCount)
    }

    companion object {
        const val WORK_NAME = "dormancy_check_weekly"
        private const val DORMANCY_THRESHOLD = 0.5
    }
}
