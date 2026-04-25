package com.safeword.android.domain.usecase

import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelRepository
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Use case for managing model download operations.
 * Encapsulates model download business logic and provides a clean interface for the UI layer.
 */
@Singleton
class ModelDownloadUseCase @Inject constructor(
    private val modelRepository: ModelRepository
) {
    
    val downloadStates: StateFlow<Map<String, ModelDownloadState>> = modelRepository.downloadStates
    
    suspend fun downloadModel(modelId: String): Boolean {
        return modelRepository.downloadModel(modelId)
    }
    
    fun getModelState(modelId: String): StateFlow<ModelDownloadState> {
        return modelRepository.getModelState(modelId)
    }
    
    fun getTotalModelSize(): Long {
        return modelRepository.getTotalModelSize()
    }
    
    fun clearCache() {
        modelRepository.clearCache()
    }
}
