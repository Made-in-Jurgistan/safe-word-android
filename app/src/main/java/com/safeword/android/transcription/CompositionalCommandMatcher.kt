package com.safeword.android.transcription

import timber.log.Timber
import com.safeword.android.transcription.VoiceCommandRegistry.CmdIntent
import com.safeword.android.transcription.VoiceCommandRegistry.CmdTarget
import com.safeword.android.transcription.VoiceCommandRegistry.COMPOSITION
import com.safeword.android.transcription.VoiceCommandRegistry.FILLER_WORDS
import com.safeword.android.transcription.VoiceCommandRegistry.INTENT_PHRASES
import com.safeword.android.transcription.VoiceCommandRegistry.MAX_COMPOSITIONAL_WORDS
import com.safeword.android.transcription.VoiceCommandRegistry.TARGET_PHRASES

/**
 * Decompose a normalized utterance into an intent verb + optional target noun and resolve
 * the result to a [VoiceAction] via [VoiceCommandRegistry.COMPOSITION].
 *
 * This covers novel verb×target combinations (e.g. "scrap the last sentence",
 * "wipe everything") that aren't explicitly listed in [VoiceCommandRegistry.COMMAND_MAP].
 *
 * @return The matched [VoiceAction], or `null` if the utterance doesn't fit the
 *         compositional grammar.
 */
internal fun compositionalMatch(normalized: String): VoiceAction? {
    val words = normalized.split(' ')
    if (words.size > MAX_COMPOSITIONAL_WORDS) return null

    val (intentPhrase, intent) = INTENT_PHRASES.firstOrNull { (phrase, _) ->
        normalized.startsWith(phrase)
    } ?: return null

    val remainder = normalized.removePrefix(intentPhrase).trim()
        .split(' ')
        .filter { it.isNotBlank() && it !in FILLER_WORDS }
        .joinToString(" ")

    val target = if (remainder.isEmpty()) {
        // Allow bare single-word verbs only when the registry defines an explicit
        // default action for (intent, NONE), e.g. "delete" -> DeleteLastWord.
        if (words.size < 2 && COMPOSITION[intent to CmdTarget.NONE] == null) return null
        CmdTarget.NONE
    } else {
        TARGET_PHRASES.firstOrNull { (phrase, _) -> remainder == phrase }?.second
            ?: return null // Unrecognized remainder — not a command.
    }

    val action = COMPOSITION[intent to target] ?: return null
    Timber.i(
        "[VOICE] compositionalMatch | intent=%s target=%s action=%s",
        intent, target, action,
    )
    return action
}
