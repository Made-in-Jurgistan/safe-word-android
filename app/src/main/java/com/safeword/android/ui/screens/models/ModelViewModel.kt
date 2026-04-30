package com.safeword.android.ui.screens.models

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.model.ModelDownloadState
import com.safeword.android.data.model.ModelInfo
import com.safeword.android.data.model.ModelRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class ModelViewModel @Inject constructor(
    private val modelRepository: ModelRepository,
) : ViewModel() {

    val models: List<ModelInfo> = ModelInfo.AVAILABLE_MODELS

    val downloadStates: StateFlow<Map<String, ModelDownloadState>> =
        modelRepository.downloadStates

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        Timber.i("[INIT] ModelViewModel | availableModels=%d refreshing states", models.size)
        modelRepository.refreshStates()
    }

    fun downloadModel(modelId: String) {
        Timber.i("[DOWNLOAD] ModelViewModel.downloadModel | modelId=%s", modelId)
        viewModelScope.launch {
            modelRepository.downloadModel(modelId)
        }
    }

    fun deleteModel(modelId: String) {
        Timber.i("[MODEL] ModelViewModel.deleteModel | modelId=%s", modelId)
        modelRepository.deleteModel(modelId)
    }

    fun isModelDownloaded(modelId: String): Boolean =
        modelRepository.isModelDownloaded(modelId)
}
