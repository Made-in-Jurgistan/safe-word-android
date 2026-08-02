package com.safeword.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.settings.CustomCommandRepository
import com.safeword.android.transcription.CustomVoiceCommand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class CustomCommandsViewModel @Inject constructor(
    private val repository: CustomCommandRepository,
) : ViewModel() {

    val commands: StateFlow<List<CustomVoiceCommand>> = repository.commands
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(triggerPhrases: String, insertText: String?, actionName: String?) {
        if (triggerPhrases.isBlank()) return
        if (insertText.isNullOrBlank() && actionName.isNullOrBlank()) return
        val command = CustomVoiceCommand(
            id = UUID.randomUUID().toString(),
            triggerPhrases = triggerPhrases.trim().lowercase(),
            insertText = insertText?.trim()?.takeIf { it.isNotBlank() },
            actionName = actionName?.trim()?.takeIf { it.isNotBlank() },
        )
        viewModelScope.launch { repository.addCommand(command) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repository.removeCommand(id) }
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch { repository.toggleCommand(id, enabled) }
    }
}
