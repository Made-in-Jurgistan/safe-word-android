# STT Speed Optimization — Action Plan 1: Quick Wins

**Scope**: Changes that can be implemented in 1–3 days, require no architectural rework, and yield measurable latency reductions with low risk.

**Baseline reference**: Safe Word uses `whisper.cpp` via JNI with `ggml-small.en-q8_0.bin` (264 MB, Q8_0). CPU-only with ARM NEON. Batch mode (no streaming). ONNX Silero VAD pre-trims audio before Whisper inference.

---

## QW-1 ▸ Reduce thread count from 6 → 4 for 8+ core devices

**File**: `app/src/main/java/com/safeword/android/transcription/InferenceConfig.kt`
**Line**: 18 (`cores >= 8 -> 6`)

**Problem**: The STT Engineering Guide identifies 4 threads as the optimal sweet spot for big.LITTLE SoCs. 6 threads spill onto efficiency cores (A520), causing cache thrashing and memory-bandwidth contention that slows overall throughput.

**Change**:
```kotlin
// Before
cores >= 8 -> 6
// After
cores >= 8 -> 4
```

**Expected gain**: 5–15% faster inference on 8-core SoCs (Snapdragon 8 Gen 3, Exynos 2400). The 4 P-cores (1× X4 + 3× A720) have higher IPC and shared L2; the 2 extra threads on E-cores cause L3 contention that negates their contribution.

**Validation**: Run `sw_perf3.txt`-style benchmark before/after. Measure RTF for 5s, 10s, 30s clips. Expect RTF improvement of ≥0.05.

**Risk**: Low. Worst case is no change on devices with homogeneous cores; easily reverted.

---

## QW-2 ▸ Enable CPU prewarm pass

**File**: `app/src/main/java/com/safeword/android/transcription/TranscriptionCoordinator.kt`
**Line**: ~133 (`"no prewarm needed (CPU has no shader compilation)"`)

**Problem**: Prewarm is skipped when `useGpu = false` with comment "CPU has no shader compilation". However, the STT Guide documents that prewarm still eliminates:
- GGML graph planning and buffer allocation on first call
- OpenMP thread pool spinup latency (~50–100 ms)
- CPU I-cache and D-cache warming for the model weights (~100–200 ms for 264 MB Q8)
- JIT compilation of any GGML compute kernels that deferred-init on first use

**Change**:
```kotlin
// Replace:
if (loaded) {
    Timber.i("[INIT] TranscriptionCoordinator.preloadModels | model loaded — no prewarm needed (CPU has no shader compilation)")
}

// With:
if (loaded) {
    Timber.i("[INIT] TranscriptionCoordinator.preloadModels | model loaded — running prewarm for CPU cache/graph warmup")
    whisperEngine.prewarm()
}
```

The `prewarm()` method already exists and uses 5 seconds of silence with representative thread count.

**Expected gain**: 100–300 ms reduction on first transcription after model load. Subsequent transcriptions unaffected (already warm).

**Validation**: Log first-transcription inference time with and without prewarm. diff ≥ 100 ms = success.

**Risk**: Low. Prewarm adds ~200–500 ms to preload (runs on IO dispatcher in background). First mic-press latency is eliminated.

---

## QW-3 ▸ Add GGML_CPU_ALL_VARIANTS for runtime ISA dispatch

**File**: `app/src/main/cpp/CMakeLists.txt`

**Problem**: Current build compiles a single CPU backend targeting `armv8.2-a+dotprod`. Modern whisper.cpp supports `GGML_CPU_ALL_VARIANTS` which compiles multiple optimized ISA variants:

| Variant | ISA Features | Target SoCs |
|---------|-------------|-------------|
| `android_armv8.0_1` | NEON baseline | Cortex-A53/A55 (2017) |
| `android_armv8.2_1` | DOTPROD | Cortex-A76/A78 (2019) |
| `android_armv8.2_2` | DOTPROD + I8MM | Cortex-A710/X2 (2021) |
| `android_armv9.2_1` | DOTPROD + I8MM + SVE | Cortex-A715/X3 (2022) |
| `android_armv9.2_2` | DOTPROD + I8MM + SVE + SME | Cortex-X4 (2023) |

At runtime, `ggml_backend_score()` detects CPU features and loads the best variant. This means a Snapdragon 8 Gen 3 device gets I8MM-optimized Q8 matrix multiplication rather than the generic DOTPROD path.

**Change**: Add to `CMakeLists.txt` before `add_subdirectory`:
```cmake
# ── Dynamic multi-variant CPU dispatch (10-25% faster on modern SoCs) ──
# Builds multiple ARM ISA variants; runtime selects the best available.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
```

Also update `target_compile_options` for the JNI bridge to link shared libs, and ensure `System.loadLibrary` in Kotlin loads the backend variants.

**Expected gain**: 10–25% faster inference on 2021+ SoCs (I8MM). 5–10% on 2022+ SoCs (SVE). Marginal on 2019 baseline (already DOTPROD). KleidiAI's I8MM kernels are specifically optimized for quantized models like Q8_0.

**Validation**: Run inference benchmarks on:
- Pixel 8 (Tensor G3 — has I8MM)
- Samsung S24 (Snapdragon 8 Gen 3 — has I8MM + SVE)
- Pixel 6 (Tensor G1 — DOTPROD only, baseline control)

Compare RTF before/after. Expect ≥10% improvement on Pixel 8/S24.

**Risk**: Medium. Requires `BUILD_SHARED_LIBS=ON` which changes APK structure (multiple `.so` files instead of one). Need to verify ProGuard keeps all variant `.so` files. APK size increases ~2–4 MB (multiple small backend libs).

**Implementation note**: This also requires loading the dynamic backends at runtime. In Kotlin, verify that `System.loadLibrary("whisper-jni")` triggers the backend discovery mechanism, or explicitly call a GGML backend registration function from the JNI init.

---

## QW-4 ▸ Fix VAD min_silence_ms inconsistency

**File**: `app/src/main/java/com/safeword/android/audio/SileroVadDetector.kt`
**File**: `app/src/main/java/com/safeword/android/data/settings/AppSettings.kt`

**Problem**: ONNX Silero VAD uses `minSilenceDurationMs = 100` as default, while WhisperEngine's native VAD defaults to 500 ms. The STT Engineering Guide recommends 500 ms to avoid splitting mid-sentence pauses into separate segments, which causes:
1. Fragmented speech chunks sent to Whisper independently
2. More Whisper invocations (each has fixed overhead)
3. Loss of cross-utterance context

The 100 ms default is too aggressive — natural pauses between words/clauses are 150–400 ms.

**Change**: In `AppSettings.kt`, update the default:
```kotlin
// Before
val vadMinSilenceDurationMs: Int = 100

// After
val vadMinSilenceDurationMs: Int = 500
```

Also verify `SileroVadDetector.extractSpeechFromWindowProbs()` correctly handles 500 ms — it should already, since this parameter is passed through from settings.

**Expected gain**: Fewer but longer speech segments → fewer Whisper calls → less overhead. For a 10-second recording with 3 natural pauses: 100 ms splits into 4 chunks (4× inference overhead), 500 ms keeps it as 1–2 chunks. Estimated 200–500 ms total pipeline savings for typical recordings.

**Validation**: Record test clips with natural pauses. Verify speech segment count decreases. Overall pipeline time should decrease.

**Risk**: Low. Users who have already customized this setting retain their preference (DataStore migration preserves existing values). Only affects new installs.

---

## QW-5 ▸ Skip per-token logprob iteration unconditionally

**File**: `app/src/main/cpp/whisper-jni.cpp`
**Lines**: ~296–310

**Problem**: Current code iterates all tokens for segments ≤ 2 to compute `avg_logprob`. The `avg_logprob` is only used for:
1. `ConfusionSetCorrector` (checks `avgLogprob ≤ -0.08`)
2. General logging

The segment-level `no_speech_prob` (which is already always computed without token iteration) is the primary hallucination gate. Token-level logprob iteration adds latency proportional to output length.

**Change**: Replace the conditional with a comment explaining why we skip:
```cpp
// Skip per-token logprob iteration entirely — the segment-level no_speech_prob
// is sufficient for hallucination gating, and ConfusionSetCorrector only activates
// on extremely low confidence where segment-level metrics already indicate issues.
float doc_avg_logprob = 0.0f;  // Omit — not worth the token iteration cost
```

Alternatively, use `whisper_full_get_segment_avg_logprob()` if whisper.cpp exposes a pre-computed per-segment average (it does via `result_all[i].avg_logprob`), avoiding the token-level iteration entirely.

**Expected gain**: 1–5 ms savings per transcription (negligible for short clips, meaningful at scale for long audio). More importantly, removes an O(tokens) loop from the critical path.

**Validation**: Verify `ConfusionSetCorrector` still activates on known test inputs. Check that logprob-based logging still reports meaningful values (or switch to segment-level reporting).

**Risk**: Low. `avg_logprob` reverts to 0.0 which means `ConfusionSetCorrector` never triggers (its threshold is ≤ -0.08). If confusion correction is important, use the per-segment API instead.

---

## QW-6 ▸ Align ARM architecture flag with CPU_ALL_VARIANTS baseline

**File**: `app/src/main/cpp/CMakeLists.txt`

**Problem**: Current config sets `GGML_CPU_ARM_ARCH "armv8.2-a+dotprod"` explicitly. If QW-3 (CPU_ALL_VARIANTS) is adopted, this manual override may conflict with the multi-variant build system. If QW-3 is NOT adopted, the current flag is good but could be upgraded.

**Change (if QW-3 adopted)**: Remove the manual arch override and let ALL_VARIANTS handle it:
```cmake
# Remove or comment out:
# set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
```

**Change (if QW-3 NOT adopted)**: Keep current flag. Consider updating to `armv8.2-a+dotprod+i8mm` if minSdk 33 guarantees I8MM on target devices. NOTE: I8MM is NOT guaranteed on all ARMv8.2 devices — only Cortex-A710+ (2021). This would crash on Cortex-A76/A78 devices.

**Expected gain**: None directly — this is a correctness/compatibility fix for QW-3.

**Risk**: If incorrectly set to `+i8mm` without runtime detection, will crash on pre-2021 SoCs.

---

## QW-7 ▸ Post-processing off critical path (async normalization)

**File**: `app/src/main/java/com/safeword/android/transcription/TranscriptionCoordinator.kt`
**Lines**: ~630–640 (Phase 2+3 processing)

**Problem**: `TextPostProcessor.process()` runs synchronously on `Dispatchers.Default` between inference completion and state transition to `Done`. ContentNormalizer has 80+ emoji regex maps and multiple regex passes. For long transcriptions, this adds measurable latency to the end-to-end pipeline.

**Current flow**:
```
whisperEngine.transcribe() → VoiceCommandDetector.detect() → TextPostProcessor.process() → _state.value = Done
```

**Change**: Emit `Done` with raw text immediately, then update with normalized text:
```kotlin
// Emit raw result immediately so the user sees text ASAP
val rawResult = correctedRawResult
_state.value = TranscriptionState.Done(rawResult)

// Normalize async — update state when done
val cleanedText = TextPostProcessor.process(rawResult.text)
if (cleanedText != rawResult.text) {
    val normalizedResult = rawResult.copy(text = cleanedText)
    _state.value = TranscriptionState.Done(normalizedResult)
}
```

**Expected gain**: Perceived latency reduction of 5–20 ms (post-processing time). The user sees raw text faster. Note: VoiceCommandDetector still runs before Done emission (it must, to short-circuit commands).

**Validation**: Measure time between `whisperEngine.transcribe()` return and `_state.value = Done` before and after.

**Risk**: Low. The UI may briefly show un-normalized text (missing emoji substitution, etc.) before the update. For most transcriptions the normalization is fast enough that the user won't notice.

---

## Summary

| ID | Change | Expected Gain | Risk | Effort |
|----|--------|--------------|------|--------|
| **QW-1** | Thread count 6→4 | 5–15% inference speedup | Low | 1 line |
| **QW-2** | Enable CPU prewarm | 100–300 ms first-call saving | Low | 3 lines |
| **QW-3** | GGML_CPU_ALL_VARIANTS | 10–25% on 2021+ SoCs | Medium | ~20 lines cmake + Kotlin loading |
| **QW-4** | VAD min_silence 100→500 ms | 200–500 ms pipeline saving | Low | 1 line |
| **QW-5** | Skip token logprob loop | 1–5 ms per transcription | Low | 5 lines |
| **QW-6** | Align ARM arch flag | Correctness for QW-3 | Low | 1 line |
| **QW-7** | Async post-processing | 5–20 ms perceived latency | Low | 10 lines |

**Recommended implementation order**: QW-1 → QW-2 → QW-4 → QW-5 → QW-7 → QW-3 + QW-6

QW-1/2/4/5 are trivial, low-risk, independent changes. QW-3 requires cmake and Kotlin-side work plus multi-device testing.

**Combined expected gain**: 15–40% total pipeline latency reduction (device-dependent). QW-3 alone is worth ~10–25% on modern hardware.
