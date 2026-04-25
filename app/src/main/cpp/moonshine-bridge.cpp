/*
 * moonshine-bridge.cpp
 *
 * JNI bridge between com.safeword.android.transcription.MoonshineNativeBridge
 * (Kotlin) and the open-source moonshine-ai/moonshine C API.
 *
 * Handle types: int32_t throughout — never cast to pointer.
 * Thread safety: all calls are serialized by MoonshineNativeEngine on a
 *   single-threaded feedDispatcher, matching the C API's guarantee that
 *   calculations on a single transcriber are serialised.
 * Memory: transcript_t data is transcriber-owned and valid until the next
 *   call to that transcriber. Do NOT free the transcript pointer.
 */

#include <jni.h>
#include <string>
#include <cstring>
#include <cstdio>
#include <sstream>

#include "moonshine-c-api.h"

// ── Helpers ──────────────────────────────────────────────────────────────────

static std::string json_escape(const char *str)
{
  std::string result;
  if (!str)
    return result;
  result.reserve(std::strlen(str) + 16);
  for (const char *p = str; *p != '\0'; ++p)
  {
    const unsigned char c = static_cast<unsigned char>(*p);
    switch (c)
    {
    case '"':
      result += "\\\"";
      break;
    case '\\':
      result += "\\\\";
      break;
    case '\n':
      result += "\\n";
      break;
    case '\r':
      result += "\\r";
      break;
    case '\t':
      result += "\\t";
      break;
    default:
      if (c < 0x20)
      {
        char buf[8];
        std::snprintf(buf, sizeof(buf), "\\u%04x", c);
        result += buf;
      }
      else
      {
        result += static_cast<char>(c);
      }
    }
  }
  return result;
}

// ── JNI functions ─────────────────────────────────────────────────────────────

extern "C"
{

  // nativeLoadTranscriber: loads models and returns transcriber handle (≥ 0) or
  // error code (< 0).
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeLoadTranscriber(
      JNIEnv *env, jclass /*clazz*/, jstring path, jint modelArch)
  {
    if (!path)
    {
      env->ThrowNew(env->FindClass("java/lang/NullPointerException"), "path is null");
      return -1;
    }
    const char *path_cstr = env->GetStringUTFChars(path, nullptr);
    if (!path_cstr)
      return -1;

    // Tuning options are intentionally explicit to keep behavior stable across
    // upstream library updates and to balance noisy-room robustness with soft
    // speech capture.
    const struct moonshine_option_t options[] = {
        {"identify_speakers", "false"},            // speaker embedding model data is empty — must disable or ORT fails at load
        {"vad_threshold", "0.25"},                 // lowered from 0.35 to 0.25 for better soft speech capture (matches Silero's sensitive range of 0.3-0.4, using 0.25 for extra sensitivity)
        {"vad_window_duration", "0.60"},           // default 0.5 — smoother speech probability in noisy environments
        {"vad_look_behind_sample_count", "12288"}, // default 8192 — preserves weak speech on segment boundaries
        {"vad_max_segment_duration", "20.0"},      // default 15.0 — fewer forced cuts during slow dictation
        {"max_tokens_per_second", "6.5"},          // default 6.5 — anti-hallucination guard on noisy input
        {"log_output_text", "false"},              // avoid logging transcribed user text to logcat
        {"log_ort_run", "false"},                  // keep native inference logs quiet outside targeted debugging
    };
    const uint64_t options_count = sizeof(options) / sizeof(options[0]);

    const int32_t handle = moonshine_load_transcriber_from_files(
        path_cstr,
        static_cast<uint32_t>(modelArch),
        options,
        options_count,
        MOONSHINE_HEADER_VERSION);

    env->ReleaseStringUTFChars(path, path_cstr);
    return static_cast<jint>(handle);
  }

  // nativeCreateStream: returns stream handle (≥ 0) or error code (< 0).
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeCreateStream(
      JNIEnv * /*env*/, jclass /*clazz*/, jint transcriberHandle)
  {
    return static_cast<jint>(
        moonshine_create_stream(static_cast<int32_t>(transcriberHandle), 0u));
  }

  // nativeStartStream: returns 0 on success, negative error code on failure.
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeStartStream(
      JNIEnv * /*env*/, jclass /*clazz*/, jint transcriberHandle, jint streamHandle)
  {
    return static_cast<jint>(
        moonshine_start_stream(
            static_cast<int32_t>(transcriberHandle),
            static_cast<int32_t>(streamHandle)));
  }

  // nativeAddAudio: feeds a PCM chunk to the stream buffer.
  // Returns 0 on success, negative error code on failure.
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeAddAudio(
      JNIEnv *env, jclass /*clazz*/,
      jint transcriberHandle, jint streamHandle,
      jfloatArray samples, jint sampleRate)
  {
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (!data)
      return MOONSHINE_ERROR_INVALID_ARGUMENT;
    const jsize len = env->GetArrayLength(samples);

    const int32_t result = moonshine_transcribe_add_audio_to_stream(
        static_cast<int32_t>(transcriberHandle),
        static_cast<int32_t>(streamHandle),
        data,
        static_cast<uint64_t>(len),
        static_cast<int32_t>(sampleRate),
        0u);

    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);
    return static_cast<jint>(result);
  }

  // nativeTranscribeStream: runs inference and returns JSON string:
  //   {"lines":[{"id":N,"text":"...","new":bool,"changed":bool,"complete":bool},...]}
  // transcript_t data is transcriber-owned — do NOT free it.
  JNIEXPORT jstring JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeTranscribeStream(
      JNIEnv *env, jclass /*clazz*/, jint transcriberHandle, jint streamHandle)
  {
    struct transcript_t *transcript = nullptr;
    const int32_t result = moonshine_transcribe_stream(
        static_cast<int32_t>(transcriberHandle),
        static_cast<int32_t>(streamHandle),
        0u,
        &transcript);

    if (result < 0 || !transcript)
    {
      return env->NewStringUTF("{\"lines\":[]}");
    }

    std::ostringstream json;
    json << "{\"lines\":[";
    for (uint64_t i = 0; i < transcript->line_count; ++i)
    {
      if (i > 0)
        json << ',';
      const struct transcript_line_t &line = transcript->lines[i];
      json << "{\"id\":" << line.id
           << ",\"text\":\"" << json_escape(line.text)
           << "\",\"new\":" << (line.is_new != 0 ? "true" : "false")
           << ",\"changed\":" << (line.has_text_changed != 0 ? "true" : "false")
           << ",\"complete\":" << (line.is_complete != 0 ? "true" : "false")
           << ",\"latency\":" << line.last_transcription_latency_ms
           << '}';
    }

    json << "]}";
    // NOTE: do NOT free transcript — owned by the transcriber until next call.
    return env->NewStringUTF(json.str().c_str());
  }

  // nativeStopStream: finalises stream processing.
  // Returns 0 on success, negative error code on failure.
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeStopStream(
      JNIEnv * /*env*/, jclass /*clazz*/, jint transcriberHandle, jint streamHandle)
  {
    return static_cast<jint>(
        moonshine_stop_stream(
            static_cast<int32_t>(transcriberHandle),
            static_cast<int32_t>(streamHandle)));
  }

  // nativeFreeStream: releases stream resources.
  // Returns 0 on success, negative error code on failure.
  JNIEXPORT jint JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeFreeStream(
      JNIEnv * /*env*/, jclass /*clazz*/, jint transcriberHandle, jint streamHandle)
  {
    return static_cast<jint>(
        moonshine_free_stream(
            static_cast<int32_t>(transcriberHandle),
            static_cast<int32_t>(streamHandle)));
  }

  // nativeFreeTranscriber: releases transcriber and all its resources.
  JNIEXPORT void JNICALL
  Java_com_safeword_android_transcription_MoonshineNativeBridge_nativeFreeTranscriber(
      JNIEnv * /*env*/, jclass /*clazz*/, jint transcriberHandle)
  {
    moonshine_free_transcriber(static_cast<int32_t>(transcriberHandle));
  }

} // extern "C"
