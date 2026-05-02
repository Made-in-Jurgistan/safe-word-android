package com.safeword.android.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.safeword.android.transcription.CustomVoiceCommand
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private val Context.customCommandsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "custom_voice_commands"
)

/**
 * Repository for user-defined custom voice commands.
 *
 * Commands are stored as a JSON array in Jetpack DataStore Preferences.
 * Each command maps one or more trigger phrases to either a text insertion
 * or a built-in [com.safeword.android.transcription.VoiceAction].
 */
@Singleton
class CustomCommandRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val CUSTOM_COMMANDS = stringPreferencesKey("custom_commands_json")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Observe the full list of custom commands as a reactive Flow. */
    val commands: Flow<List<CustomVoiceCommand>> = context.customCommandsDataStore.data.map { prefs ->
        val raw = prefs[Keys.CUSTOM_COMMANDS] ?: return@map emptyList()
        try {
            json.decodeFromString<List<CustomVoiceCommand>>(raw)
        } catch (e: Exception) {
            Timber.e(e, "[SETTINGS] CustomCommandRepository | failed to parse commands JSON")
            emptyList()
        }
    }

    /** Save the full list of custom commands (replaces all). */
    suspend fun saveCommands(commands: List<CustomVoiceCommand>) {
        val encoded = json.encodeToString(commands)
        Timber.i("[SETTINGS] CustomCommandRepository.saveCommands | count=%d", commands.size)
        context.customCommandsDataStore.edit { it[Keys.CUSTOM_COMMANDS] = encoded }
    }

    /** Add a single custom command. */
    suspend fun addCommand(command: CustomVoiceCommand) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            prefs[Keys.CUSTOM_COMMANDS] = json.encodeToString(current + command)
        }
    }

    /** Update a command by ID. */
    suspend fun updateCommand(command: CustomVoiceCommand) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            prefs[Keys.CUSTOM_COMMANDS] = json.encodeToString(
                current.map { if (it.id == command.id) command else it }
            )
        }
    }

    /** Remove a command by ID. */
    suspend fun removeCommand(id: String) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            prefs[Keys.CUSTOM_COMMANDS] = json.encodeToString(
                current.filter { it.id != id }
            )
        }
    }

    /** Toggle a command's enabled state. */
    suspend fun toggleCommand(id: String, enabled: Boolean) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            prefs[Keys.CUSTOM_COMMANDS] = json.encodeToString(
                current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            )
        }
    }

    private fun decodeCommands(prefs: Preferences): List<CustomVoiceCommand> {
        val raw = prefs[Keys.CUSTOM_COMMANDS] ?: return emptyList()
        return try {
            json.decodeFromString(raw)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
