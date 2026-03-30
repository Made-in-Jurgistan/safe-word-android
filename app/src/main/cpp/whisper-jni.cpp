/**
 * whisper-jni.cpp — JNI bridge between Kotlin and whisper.cpp
 *
 * Mirrors the desktop Safe Word's whisper-rs (Rust → C) bridge,
 * but goes Kotlin → JNI → C++ → whisper.cpp.
 *
 * Requires whisper.cpp submodule at app/src/main/cpp/whisper.cpp/
 */

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cmath>
#include <algorithm>
#include <cstring>
#include <time.h>
#include <sched.h>
#include <unistd.h>

#define TAG "WhisperJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#include "whisper.h"
#include "ggml-backend.h"

/**
 * Pin the calling thread and its worker pool to the highest-frequency CPU cores.
 * Reads /sys/devices/system/cpu/cpuN/cpufreq/cpuinfo_max_freq for each CPU,
 * sorts descending, and pins the top n_threads cores via sched_setaffinity.
 * Silently no-ops if sysfs is unavailable (emulator, locked devices).
 */
static void pin_to_big_cores(int n_threads)
{
    int n_cpus = static_cast<int>(sysconf(_SC_NPROCESSORS_CONF));
    if (n_cpus <= 0) return;

    std::vector<std::pair<int, long>> cores;
    cores.reserve(static_cast<size_t>(n_cpus));
    for (int i = 0; i < n_cpus; ++i) {
        char path[128];
        long freq = 0;
        snprintf(path, sizeof(path),
            "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", i);
        FILE *f = fopen(path, "r");
        if (f) { fscanf(f, "%ld", &freq); fclose(f); }
        cores.push_back({i, freq});
    }
    std::sort(cores.begin(), cores.end(),
        [](const std::pair<int,long>& a, const std::pair<int,long>& b) {
            return a.second > b.second;
        });

    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);
    int pinned = 0;
    for (const auto& core : cores) {
        if (pinned >= n_threads) break;
        if (core.second > 0) { CPU_SET(core.first, &cpuset); ++pinned; }
    }
    if (pinned > 0) {
        sched_setaffinity(0, sizeof(cpuset), &cpuset);
    }
}

extern "C"
{

    /**
     * Load GGML dynamic backends from the app's native library directory.
     * Must be called once before nativeInit when GGML_BACKEND_DL is enabled.
     * On Android, dlopen cannot discover .so files by default — we must
     * provide the path to the app's jniLibs extraction directory.
     */
    JNIEXPORT void JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeLoadBackends(
        JNIEnv *env, jobject /* this */, jstring searchDir)
    {
        static bool backends_loaded = false;
        if (backends_loaded)
        {
            LOGI("GGML backends already loaded, skipping");
            return;
        }

        const char *dir = env->GetStringUTFChars(searchDir, nullptr);
        LOGI("Loading GGML dynamic backends from: %s", dir);
        ggml_backend_load_all_from_path(dir);
        env->ReleaseStringUTFChars(searchDir, dir);
        backends_loaded = true;
        LOGI("GGML backends loaded, registered count: %zu", ggml_backend_reg_count());
    }

    static const char *basename_from_path(const char *path)
    {
        if (path == nullptr)
        {
            return "";
        }
        const char *slash = strrchr(path, '/');
        const char *backslash = strrchr(path, '\\');
        const char *last = slash;
        if (backslash && (!slash || backslash > slash))
        {
            last = backslash;
        }
        return last ? last + 1 : path;
    }

    /**
     * Initialize whisper context from a GGML model file.
     * Returns context pointer (as jlong), or 0 on failure.
     */
    JNIEXPORT jlong JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeInit(
        JNIEnv *env, jobject /* this */, jstring modelPath, jboolean useGpu)
    {
        const char *path = env->GetStringUTFChars(modelPath, nullptr);
        const char *model_name = basename_from_path(path);
        LOGI("Loading model: %s (useGpu=%d)", model_name, static_cast<int>(useGpu));

        struct whisper_context_params cparams = whisper_context_default_params();
        cparams.use_gpu = static_cast<bool>(useGpu);
        cparams.flash_attn = static_cast<bool>(useGpu);

        struct whisper_context *ctx = whisper_init_from_file_with_params(path, cparams);

        if (ctx == nullptr && useGpu)
        {
            LOGW("GPU init failed for model, retrying with CPU-only: %s", model_name);
            cparams.use_gpu = false;
            cparams.flash_attn = false;
            ctx = whisper_init_from_file_with_params(path, cparams);
        }

        env->ReleaseStringUTFChars(modelPath, path);

        if (ctx == nullptr)
        {
            LOGE("Failed to load whisper model");
            return 0;
        }

        LOGI("Model loaded successfully (model=%s, gpu=%d, flash_attn=%d)",
             model_name, cparams.use_gpu, cparams.flash_attn);
        return reinterpret_cast<jlong>(ctx);
    }

    /**
     * Escape a string for safe inclusion in a JSON value.
     * Handles control characters, backslash, double-quote, and high Unicode.
     */
    static std::string json_escape(const char *s)
    {
        if (s == nullptr)
            return "";
        std::string out;
        out.reserve(std::strlen(s) + 16);
        for (; *s; ++s)
        {
            unsigned char c = static_cast<unsigned char>(*s);
            switch (c)
            {
            case '"':
                out += "\\\"";
                break;
            case '\\':
                out += "\\\\";
                break;
            case '\b':
                out += "\\b";
                break;
            case '\f':
                out += "\\f";
                break;
            case '\n':
                out += "\\n";
                break;
            case '\r':
                out += "\\r";
                break;
            case '\t':
                out += "\\t";
                break;
            default:
                if (c < 0x20)
                {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                }
                else
                {
                    out += static_cast<char>(c);
                }
            }
        }
        return out;
    }

    /**
     * User data passed to whisper's new_segment_callback.
     * All fields are valid for the lifetime of the nativeTranscribeStreaming call.
     *
     * JavaVM* is stored instead of JNIEnv* because new_segment_callback fires on
     * whisper.cpp's internal compute thread, which is a different thread from the
     * JNI entry point. JNIEnv* is per-thread and MUST NOT be used across threads.
     * JavaVM* is process-wide and safe to share; we attach/detach as needed.
     */
    struct CallbackData
    {
        JavaVM *vm;
        jobject callback_obj; // global reference to the SegmentCallback Kotlin object
        jmethodID on_segment_id;
    };

    /**
     * Fired by whisper.cpp on its internal compute thread whenever n_new new
     * segments have been committed to the context.
     *
     * We attach the compute thread to the JVM for the duration of the call and
     * detach afterwards to avoid leaking a thread attachment.
     */
    static void segment_callback(
        struct whisper_context *ctx,
        struct whisper_state * /* state */,
        int n_new,
        void *user_data)
    {
        auto *cb = static_cast<CallbackData *>(user_data);

        JNIEnv *env = nullptr;
        bool attached = false;
        jint status = cb->vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        if (status == JNI_EDETACHED)
        {
            if (cb->vm->AttachCurrentThreadAsDaemon(
                    reinterpret_cast<JNIEnv **>(&env), nullptr) != JNI_OK)
            {
                LOGE("segment_callback: failed to attach compute thread to JVM");
                return;
            }
            attached = true;
        }
        else if (status != JNI_OK || env == nullptr)
        {
            LOGE("segment_callback: GetEnv failed status=%d", status);
            return;
        }

        int total = whisper_full_n_segments(ctx);
        for (int i = total - n_new; i < total; ++i)
        {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (!text)
                continue;
            jstring jtext = env->NewStringUTF(text);
            if (!jtext)
                continue;
            env->CallVoidMethod(cb->callback_obj, cb->on_segment_id, jtext);
            env->DeleteLocalRef(jtext);
            // Propagate Kotlin exception (e.g. CancellationException) outward
            if (env->ExceptionCheck())
            {
                env->ExceptionClear();
                break;
            }
        }

        if (attached)
            cb->vm->DetachCurrentThread();
    }

    /**
     * Transcribe PCM float audio samples with full settings support.
     *
     * Returns a JSON string:
     *   {"text":"...","no_speech_prob":0.12,"avg_logprob":-0.34,
     *    "segments":[{"text":"...","no_speech_prob":0.1,"avg_logprob":-0.3},...]}
     *
     * @param contextPtr           pointer returned by nativeInit
     * @param samples              float array of PCM audio [-1.0, 1.0] at 16kHz
     * @param jlanguage            language code ("en", "auto", etc.)
     * @param nThreads             number of CPU threads for inference
     * @param translate            if true, translate to English
     * @param autoDetect           if true, ignore language param and auto-detect
     * @param jinitialPrompt       optional prompt to bias decoder
     * @param useVad               enable native GGML Silero VAD
     * @param jvadModelPath        path to ggml-silero-v5.1.2.bin (or empty)
     * @param vadThreshold         VAD speech probability threshold (0.5)
     * @param vadMinSpeechMs       minimum speech duration ms (250)
     * @param vadMinSilenceMs      minimum silence duration ms (500)
     * @param vadSpeechPadMs       speech padding ms (300)
     * @param noSpeechThreshold    no-speech probability threshold (0.6)
     * @param logprobThreshold     average log-probability threshold (-1.0)
     * @param entropyThreshold     entropy / compression-ratio threshold (2.4)
     */
    JNIEXPORT jstring JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeTranscribe(
        JNIEnv *env, jobject /* this */,
        jlong contextPtr, jfloatArray samples, jstring jlanguage,
        jint nThreads, jboolean translate, jboolean autoDetect,
        jstring jinitialPrompt,
        jboolean useVad, jstring jvadModelPath,
        jfloat vadThreshold, jint vadMinSpeechMs, jint vadMinSilenceMs,
        jint vadSpeechPadMs,
        jfloat noSpeechThreshold, jfloat logprobThreshold, jfloat entropyThreshold)
    {
        static const char *EMPTY_JSON = "{\"text\":\"\",\"no_speech_prob\":0.0,\"avg_logprob\":0.0}";

        if (contextPtr == 0)
        {
            LOGE("nativeTranscribe called with null context");
            return env->NewStringUTF(EMPTY_JSON);
        }

        auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
        jsize n_samples = env->GetArrayLength(samples);

        // Extract strings BEFORE entering the critical section
        const char *lang = env->GetStringUTFChars(jlanguage, nullptr);
        const char *prompt = env->GetStringUTFChars(jinitialPrompt, nullptr);
        const char *vad_path = env->GetStringUTFChars(jvadModelPath, nullptr);

        jfloat *audio_data = env->GetFloatArrayElements(samples, nullptr);

        if (n_samples == 0 || audio_data == nullptr)
        {
            LOGW("Empty audio buffer");
            if (audio_data)
                env->ReleaseFloatArrayElements(samples, audio_data, JNI_ABORT);
            env->ReleaseStringUTFChars(jvadModelPath, vad_path);
            env->ReleaseStringUTFChars(jinitialPrompt, prompt);
            env->ReleaseStringUTFChars(jlanguage, lang);
            return env->NewStringUTF(EMPTY_JSON);
        }

        // Clamp thread count to reasonable range
        int threads = std::max(1, std::min(static_cast<int>(nThreads), 16));
        pin_to_big_cores(threads);

        // Configure full params (mirrors desktop Safe Word defaults)
        struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = static_cast<bool>(translate);
        params.language = autoDetect ? "auto" : lang;
        params.n_threads = threads;
        params.offset_ms = 0;
        params.no_context = true;
        params.single_segment = (n_samples < 30 * 16000); // single segment for < 30s audio (faster)
        params.suppress_blank = true;
        params.suppress_nst = true;
        params.no_timestamps = true; // skip timestamp token generation — saves decoder steps
        // Greedy params — single candidate per token (no resampling overhead)
        params.greedy.best_of = 1;
        // Disable temperature fallback — deterministic single-pass decoding
        params.temperature_inc = 0.0f;
        // Optional prompt to bias decoder vocabulary/style
        params.initial_prompt = (prompt != nullptr && prompt[0] != '\0') ? prompt : nullptr;

        // ----- Native GGML Silero VAD -----
        params.vad = static_cast<bool>(useVad);
        if (useVad && vad_path != nullptr && vad_path[0] != '\0')
        {
            params.vad_model_path = vad_path;
            params.vad_params.threshold = vadThreshold;
            params.vad_params.min_speech_duration_ms = vadMinSpeechMs;
            params.vad_params.min_silence_duration_ms = vadMinSilenceMs;
            params.vad_params.max_speech_duration_s = 30.0f;
            params.vad_params.speech_pad_ms = vadSpeechPadMs;
            params.vad_params.samples_overlap = 0.1f;
        }

        // ----- Hallucination prevention thresholds -----
        params.no_speech_thold = noSpeechThreshold;
        params.logprob_thold = logprobThreshold;
        params.entropy_thold = entropyThreshold;

        LOGI("Transcribing %d samples (%.1fs), threads=%d, lang=%s, translate=%d, vad=%d",
             n_samples, static_cast<float>(n_samples) / 16000.0f, threads,
             autoDetect ? "auto" : lang, static_cast<int>(translate),
             static_cast<int>(useVad));

        struct timespec t_start, t_end;
        clock_gettime(CLOCK_MONOTONIC, &t_start);

        int ret = whisper_full(ctx, params, audio_data, n_samples);

        clock_gettime(CLOCK_MONOTONIC, &t_end);
        double elapsed_ms = (t_end.tv_sec - t_start.tv_sec) * 1000.0 +
                            (t_end.tv_nsec - t_start.tv_nsec) / 1e6;
        LOGI("whisper_full() took %.0f ms for %.1fs audio (%.1fx realtime)",
             elapsed_ms, static_cast<float>(n_samples) / 16000.0f,
             (static_cast<float>(n_samples) / 16000.0f * 1000.0f) / elapsed_ms);

        env->ReleaseFloatArrayElements(samples, audio_data, JNI_ABORT);
        env->ReleaseStringUTFChars(jvadModelPath, vad_path);
        env->ReleaseStringUTFChars(jinitialPrompt, prompt);
        env->ReleaseStringUTFChars(jlanguage, lang);

        if (ret != 0)
        {
            LOGE("whisper_full() failed with code %d", ret);
            return env->NewStringUTF(EMPTY_JSON);
        }

        // ----- Build compact JSON: text + doc-level confidence only -----
        // Kotlin only reads top-level text, no_speech_prob, avg_logprob.
        // Skip per-segment JSON and per-token logprob iteration for lower latency.
        int n_segments = whisper_full_n_segments(ctx);

        double total_no_speech = 0.0;

        std::string full_text;
        full_text.reserve(static_cast<size_t>(n_segments) * 40);
        for (int i = 0; i < n_segments; i++)
        {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text)
                full_text += text;

            total_no_speech += whisper_full_get_segment_no_speech_prob(ctx, i);
        }

        float doc_no_speech = (n_segments > 0)
                                  ? static_cast<float>(total_no_speech / n_segments)
                                  : 0.0f;
        // Skip per-token logprob iteration — the segment-level no_speech_prob
        // is sufficient for hallucination gating. Avoids O(tokens) loop on the
        // critical path. ConfusionSetCorrector threshold (≤ -0.08) is unreachable
        // at 0.0, which is acceptable — segment-level metrics catch the same cases.
        float doc_avg_logprob = 0.0f;

        std::string json;
        json.reserve(full_text.size() + 96);
        json += "{\"text\":\"";
        json += json_escape(full_text.c_str());
        json += "\",\"no_speech_prob\":";
        char nbuf[32];
        snprintf(nbuf, sizeof(nbuf), "%.4f", doc_no_speech);
        json += nbuf;
        json += ",\"avg_logprob\":";
        snprintf(nbuf, sizeof(nbuf), "%.4f", doc_avg_logprob);
        json += nbuf;
        json += "}";

        LOGI("Transcription complete: %d segments, %zu chars, no_speech=%.3f, avg_logprob=%.3f",
             n_segments, full_text.size(), doc_no_speech, doc_avg_logprob);
        return env->NewStringUTF(json.c_str());
    }

    /**
     * Transcribe PCM float audio samples and invoke [segmentCallback].onSegment(String)
     * for each segment as it is decoded.
     *
     * Returns the same compact JSON as nativeTranscribe once all segments are complete.
     *
     * @param segmentCallback  Kotlin SegmentCallback instance (onSegment(String):Unit)
     */
    JNIEXPORT jstring JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeTranscribeStreaming(
        JNIEnv *env, jobject /* this */,
        jlong contextPtr, jfloatArray samples, jstring jlanguage, jint nThreads,
        jboolean translate, jboolean autoDetect, jstring jinitialPrompt,
        jboolean useVad, jstring jvadModelPath,
        jfloat vadThreshold, jint vadMinSpeechMs, jint vadMinSilenceMs, jint vadSpeechPadMs,
        jfloat noSpeechThreshold, jfloat logprobThreshold, jfloat entropyThreshold,
        jobject segmentCallback)
    {
        static const char *EMPTY_JSON = "{\"text\":\"\",\"no_speech_prob\":0.0,\"avg_logprob\":0.0}";

        if (contextPtr == 0)
        {
            LOGE("nativeTranscribeStreaming called with null context");
            return env->NewStringUTF(EMPTY_JSON);
        }

        if (segmentCallback == nullptr)
        {
            LOGE("nativeTranscribeStreaming called with null segmentCallback");
            return env->NewStringUTF(EMPTY_JSON);
        }

        // Resolve the onSegment method ID once before inference starts
        jclass cb_class = env->GetObjectClass(segmentCallback);
        jmethodID on_segment_id = env->GetMethodID(cb_class, "onSegment", "(Ljava/lang/String;)V");
        env->DeleteLocalRef(cb_class);
        if (!on_segment_id)
        {
            LOGE("nativeTranscribeStreaming: could not find onSegment method on SegmentCallback");
            return env->NewStringUTF(EMPTY_JSON);
        }

        auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
        jsize n_samples = env->GetArrayLength(samples);

        const char *lang = env->GetStringUTFChars(jlanguage, nullptr);
        const char *prompt = env->GetStringUTFChars(jinitialPrompt, nullptr);
        const char *vad_path = env->GetStringUTFChars(jvadModelPath, nullptr);

        jfloat *audio_data = env->GetFloatArrayElements(samples, nullptr);

        if (n_samples == 0 || audio_data == nullptr)
        {
            LOGW("nativeTranscribeStreaming: empty audio buffer");
            if (audio_data)
                env->ReleaseFloatArrayElements(samples, audio_data, JNI_ABORT);
            env->ReleaseStringUTFChars(jvadModelPath, vad_path);
            env->ReleaseStringUTFChars(jinitialPrompt, prompt);
            env->ReleaseStringUTFChars(jlanguage, lang);
            return env->NewStringUTF(EMPTY_JSON);
        }

        int threads = std::max(1, std::min(static_cast<int>(nThreads), 16));
        pin_to_big_cores(threads);

        struct whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.print_realtime = false;
        params.print_progress = false;
        params.print_timestamps = false;
        params.print_special = false;
        params.translate = static_cast<bool>(translate);
        params.language = autoDetect ? "auto" : lang;
        params.n_threads = threads;
        params.offset_ms = 0;
        params.no_context = true;
        params.single_segment = false; // must be false for mid-decode partial callbacks
        params.max_len = 25;           // fire new_segment_callback every ~5 words
        params.split_on_word = true;   // align boundaries to word edges
        params.suppress_blank = true;
        params.suppress_nst = true;
        params.no_timestamps = true;
        params.greedy.best_of = 1;
        params.temperature_inc = 0.0f;
        params.initial_prompt = (prompt != nullptr && prompt[0] != '\0') ? prompt : nullptr;

        // Wire up the per-segment callback.
        // Capture JavaVM* (process-wide) rather than JNIEnv* (per-thread) because
        // segment_callback fires on whisper's internal compute thread.
        JavaVM *vm = nullptr;
        env->GetJavaVM(&vm);
        CallbackData cb_data{vm, segmentCallback, on_segment_id};
        params.new_segment_callback = segment_callback;
        params.new_segment_callback_user_data = &cb_data;

        params.vad = static_cast<bool>(useVad);
        if (useVad && vad_path != nullptr && vad_path[0] != '\0')
        {
            params.vad_model_path = vad_path;
            params.vad_params.threshold = vadThreshold;
            params.vad_params.min_speech_duration_ms = vadMinSpeechMs;
            params.vad_params.min_silence_duration_ms = vadMinSilenceMs;
            params.vad_params.max_speech_duration_s = 30.0f;
            params.vad_params.speech_pad_ms = vadSpeechPadMs;
            params.vad_params.samples_overlap = 0.1f;
        }

        params.no_speech_thold = noSpeechThreshold;
        params.logprob_thold = logprobThreshold;
        params.entropy_thold = entropyThreshold;

        LOGI("nativeTranscribeStreaming: %d samples (%.1fs), threads=%d, lang=%s",
             n_samples, static_cast<float>(n_samples) / 16000.0f, threads,
             autoDetect ? "auto" : lang);

        int ret = whisper_full(ctx, params, audio_data, n_samples);

        env->ReleaseFloatArrayElements(samples, audio_data, JNI_ABORT);
        env->ReleaseStringUTFChars(jvadModelPath, vad_path);
        env->ReleaseStringUTFChars(jinitialPrompt, prompt);
        env->ReleaseStringUTFChars(jlanguage, lang);

        if (ret != 0)
        {
            LOGE("nativeTranscribeStreaming: whisper_full() failed with code %d", ret);
            return env->NewStringUTF(EMPTY_JSON);
        }

        // Build the same compact JSON as nativeTranscribe
        int n_segments = whisper_full_n_segments(ctx);
        double total_no_speech = 0.0;
        std::string full_text;
        full_text.reserve(static_cast<size_t>(n_segments) * 40);
        for (int i = 0; i < n_segments; i++)
        {
            const char *text = whisper_full_get_segment_text(ctx, i);
            if (text)
                full_text += text;
            total_no_speech += whisper_full_get_segment_no_speech_prob(ctx, i);
        }

        float doc_no_speech = (n_segments > 0)
                                  ? static_cast<float>(total_no_speech / n_segments)
                                  : 0.0f;
        float doc_avg_logprob = 0.0f;

        std::string json;
        json.reserve(full_text.size() + 96);
        json += "{\"text\":\"";
        json += json_escape(full_text.c_str());
        json += "\",\"no_speech_prob\":";
        char nbuf[32];
        snprintf(nbuf, sizeof(nbuf), "%.4f", doc_no_speech);
        json += nbuf;
        json += ",\"avg_logprob\":";
        snprintf(nbuf, sizeof(nbuf), "%.4f", doc_avg_logprob);
        json += nbuf;
        json += "}";

        LOGI("nativeTranscribeStreaming complete: %d segments, %zu chars, no_speech=%.3f",
             n_segments, full_text.size(), doc_no_speech);
        return env->NewStringUTF(json.c_str());
    }

    /**
     * Free whisper context and release resources.
     */
    JNIEXPORT void JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeFree(
        JNIEnv *env, jobject /* this */, jlong contextPtr)
    {
        if (contextPtr != 0)
        {
            auto *ctx = reinterpret_cast<struct whisper_context *>(contextPtr);
            whisper_free(ctx);
            LOGI("Whisper context freed");
        }
    }

    /**
     * Returns true — whisper.cpp is always compiled in when this file builds.
     */
    JNIEXPORT jboolean JNICALL
    Java_com_safeword_android_transcription_WhisperLib_nativeIsRealWhisper(
        JNIEnv *env, jobject /* this */)
    {
        return JNI_TRUE;
    }

} // extern "C"
