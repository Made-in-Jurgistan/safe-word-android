package com.safeword.android.transcription

import com.safeword.android.audio.AudioRecorder
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extracted audio processing logic from MoonshineNativeEngine.
 * Handles audio buffer management and VAD processing.
 */
@Singleton
class AudioProcessor @Inject constructor(
    private val audioRecorder: AudioRecorder
) {

    /** Expose whether audio recorder is currently active */
    val isRecording: StateFlow<Boolean> = audioRecorder.isRecording

    /** Start audio recording - delegates to AudioRecorder's suspend record() function */
    suspend fun startRecording() {
        audioRecorder.record()
    }

    /** Stop audio recording */
    fun stopRecording() {
        audioRecorder.stop()
    }

    /** Access the audio chunks flow for consuming audio data */
    fun getAudioChunks() = audioRecorder.audioChunks
}
