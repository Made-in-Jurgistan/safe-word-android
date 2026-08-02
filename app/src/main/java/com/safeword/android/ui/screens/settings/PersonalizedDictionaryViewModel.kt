package com.safeword.android.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.safeword.android.data.db.PersonalizedEntryEntity
import com.safeword.android.data.settings.PersonalizedDictionaryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PersonalizedDictionaryViewModel @Inject constructor(
    private val repository: PersonalizedDictionaryRepository,
) : ViewModel() {

    val entries: StateFlow<List<PersonalizedEntryEntity>> = repository.allEntries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun add(fromPhrase: String, toPhrase: String) {
        if (fromPhrase.isBlank() || toPhrase.isBlank()) return
        viewModelScope.launch { repository.add(fromPhrase, toPhrase) }
    }

    fun delete(entry: PersonalizedEntryEntity) {
        viewModelScope.launch { repository.delete(entry) }
    }

    fun setEnabled(entry: PersonalizedEntryEntity, enabled: Boolean) {
        viewModelScope.launch { repository.setEnabled(entry, enabled) }
    }
}
