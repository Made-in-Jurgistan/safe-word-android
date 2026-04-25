package com.safeword.android.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

internal val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "safeword_settings")
internal val Context.onboardingDataStore: DataStore<Preferences> by preferencesDataStore(name = "safeword_onboarding")
