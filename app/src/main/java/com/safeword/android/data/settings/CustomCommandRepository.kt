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

    /**
     * Last successfully decoded blob — used as a recovery snapshot when the
     * persisted JSON is corrupt. Without this, a parse failure followed by a
     * `saveCommands(...)` call would silently clobber recoverable user data.
     */
    @Volatile private var lastGoodRaw: String? = null

    /** Observe the full list of custom commands as a reactive Flow. */
    val commands: Flow<List<CustomVoiceCommand>> = context.customCommandsDataStore.data.map { prefs ->
        val raw = prefs[Keys.CUSTOM_COMMANDS] ?: return@map emptyList()
        try {
            val parsed = json.decodeFromString<List<CustomVoiceCommand>>(raw)
            lastGoodRaw = raw
            parsed
        } catch (e: RuntimeException) {
            Timber.e(
                e,
                "[SETTINGS] CustomCommandRepository | failed to parse commands JSON, " +
                    "retaining last-good snapshot",
            )
            // Surface the last-known-good state instead of an empty list so the UI
            // doesn't think the user lost everything.
            lastGoodRaw?.let {
                runCatching { json.decodeFromString<List<CustomVoiceCommand>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }

    /** Save the full list of custom commands (replaces all). */
    suspend fun saveCommands(commands: List<CustomVoiceCommand>) {
        val encoded = json.encodeToString(commands)
        Timber.i("[SETTINGS] CustomCommandRepository.saveCommands | count=%d", commands.size)
        context.customCommandsDataStore.edit { it[Keys.CUSTOM_COMMANDS] = encoded }
        lastGoodRaw = encoded
    }

    /** Add a single custom command. */
    suspend fun addCommand(command: CustomVoiceCommand) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            val encoded = json.encodeToString(current + command)
            prefs[Keys.CUSTOM_COMMANDS] = encoded
            lastGoodRaw = encoded
        }
    }

    /** Update a command by ID. */
    suspend fun updateCommand(command: CustomVoiceCommand) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            val encoded = json.encodeToString(
                current.map { if (it.id == command.id) command else it }
            )
            prefs[Keys.CUSTOM_COMMANDS] = encoded
            lastGoodRaw = encoded
        }
    }

    /** Remove a command by ID. */
    suspend fun removeCommand(id: String) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            val encoded = json.encodeToString(current.filter { it.id != id })
            prefs[Keys.CUSTOM_COMMANDS] = encoded
            lastGoodRaw = encoded
        }
    }

    /** Toggle a command's enabled state. */
    suspend fun toggleCommand(id: String, enabled: Boolean) {
        context.customCommandsDataStore.edit { prefs ->
            val current = decodeCommands(prefs)
            val encoded = json.encodeToString(
                current.map { if (it.id == id) it.copy(enabled = enabled) else it }
            )
            prefs[Keys.CUSTOM_COMMANDS] = encoded
            lastGoodRaw = encoded
        }
    }

    /**
     * Decode the persisted JSON. On failure, prefer the [lastGoodRaw] snapshot to
     * avoid clobbering recoverable user data with an empty list during a write.
     * Throws if neither current nor cached snapshot can be parsed.
     */
    private fun decodeCommands(prefs: Preferences): List<CustomVoiceCommand> {
        val raw = prefs[Keys.CUSTOM_COMMANDS]
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val parsed = json.decodeFromString<List<CustomVoiceCommand>>(raw)
            lastGoodRaw = raw
            parsed
        } catch (e: RuntimeException) {
            Timber.e(e, "[SETTINGS] CustomCommandRepository.decodeCommands | corrupt JSON, falling back to snapshot")
            lastGoodRaw?.let {
                runCatching { json.decodeFromString<List<CustomVoiceCommand>>(it) }.getOrDefault(emptyList())
            } ?: emptyList()
        }
    }
}
