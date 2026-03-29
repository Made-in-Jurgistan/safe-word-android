# STT Speed Optimization — Action Plan 2: Deep Optimizations

**Scope**: Architectural changes requiring 1–4 weeks of engineering. Higher impact, higher risk. Each item should be evaluated as a standalone spike before committing.

**Prerequisites**: Action Plan 1 (Quick Wins) should be implemented first — the baseline measurements from QW changes inform which deep optimizations provide the highest marginal return.

---

## DO-1 ▸ Implement streaming transcription with `new_segment_callback`

**Impact**: ★★★★★ (highest perceived latency improvement)
**Effort**: 2–3 weeks
**Files**: `whisper-jni.cpp`, `WhisperEngine.kt`, `WhisperLib.kt`, `TranscriptionCoordinator.kt`

### Problem

Current architecture is entirely batch: the user must wait until recording finishes AND full inference completes before seeing any text. For a 10-second recording, this means ~2–3 seconds of staring at a spinner after the mic button is released.

The STT Engineering Guide marks streaming as the #1 perceived latency optimization. whisper.cpp natively supports `new_segment_callback` which fires for each decoded segment during `whisper_full()`.

### Architecture

```
Current (batch):
  Record → Stop → [VAD preprocess] → [Whisper full run] → Text
  Latency: recording_time + vad_time + inference_time

Proposed (streaming):
  Record → Stop → [VAD preprocess] → [Whisper inference]
                                          ├─ segment_1 → UI update
                                          ├─ segment_2 → UI update
                                          └─ segment_N → Final UI update
  Perceived latency: recording_time + vad_time + time_to_first_segment
```

### Implementation plan

**Phase 1 — JNI callback bridge** (`whisper-jni.cpp`):

```cpp
struct jni_callback_data {
    JNIEnv* env;
    jobject callback_obj;     // Kotlin lambda reference
    jmethodID on_segment_id;  // Method ID for onSegment(String)
};

void new_segment_callback(struct whisper_context* ctx, struct whisper_state* state,
                           int n_new, void* user_data) {
    auto* cb = static_cast<jni_callback_data*>(user_data);
    const int n_segments = whisper_full_n_segments(ctx);
    for (int i = n_segments - n_new; i < n_segments; i++) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        jstring jtext = cb->env->NewStringUTF(text);
        cb->env->CallVoidMethod(cb->callback_obj, cb->on_segment_id, jtext);
        cb->env->DeleteLocalRef(jtext);
    }
}
```

Add new JNI function:
```cpp
extern "C" JNIEXPORT jstring JNICALL
Java_com_safeword_android_transcription_WhisperLib_nativeTranscribeStreaming(
    JNIEnv* env, jobject, jlong contextPtr, jfloatArray samples,
    /* existing params... */,
    jobject segmentCallback  // com.safeword.android.transcription.SegmentCallback
);
```

**Phase 2 — Kotlin callback interface** (`WhisperLib.kt`, `WhisperEngine.kt`):

```kotlin
fun interface SegmentCallback {
    fun onSegment(text: String)
}

// In WhisperEngine:
suspend fun transcribeStreaming(
    samples: FloatArray,
    onSegment: SegmentCallback,
    /* existing params */
): TranscriptionResult
```

**Phase 3 — Coordinator integration** (`TranscriptionCoordinator.kt`):

```kotlin
// In transcribe():
val partialSegments = mutableListOf<String>()
val result = whisperEngine.transcribeStreaming(
    samples = processedSamples,
    onSegment = { text ->
        partialSegments.add(text)
        _state.value = TranscriptionState.Transcribing(
            partialText = partialSegments.joinToString(" ")
        )
    },
    /* ... */
)
```

**Phase 4 — State machine update** (`TranscriptionState.kt`):

```kotlin
data class Transcribing(
    val partialText: String = "",    // NEW: progressive text display
    val segmentCount: Int = 0,       // NEW: segment counter
) : TranscriptionState
```

### Risks

- **JNI callback threading**: `new_segment_callback` fires on the whisper compute thread. JNI callbacks to Kotlin must be careful with thread attachment (`JNIEnv` is per-thread). May need to post to a handler instead of calling directly.
- **State flow backpressure**: Rapid segment callbacks may overwhelm the MutableStateFlow. Use `conflate()` on the collector side.
- **Post-processing**: Currently runs after full inference. With streaming, each segment would need individual normalization, or raw segments are displayed and normalized at the end.
- **VoiceCommandDetector**: Currently runs on full text. Streaming would need to detect commands from partial text or defer to final result.

### Expected gain

- **Perceived latency**: Instead of waiting for full inference (1–3s for 10s audio), user sees first text within 200–500 ms of inference start.
- **Actual inference time**: No change — same whisper_full() call.
- **User experience**: Transformative. Text appears progressively like a real-time transcription service.

---

## DO-2 ▸ Evaluate Moonshine as Whisper replacement

**Impact**: ★★★★★ (potentially 5× faster inference)
**Effort**: 2–4 weeks (spike + integration)
**Files**: New module or replacement of WhisperEngine

### Problem

Per MCP research (DeepWiki Moonshine), Moonshine achieves **5× faster inference** than Whisper on mobile ARM64, with comparable WER for English. Key advantages:

| Metric | Whisper small.en Q8 | Moonshine base |
|--------|-------------------|----------------|
| RTF (ARM64) | ~0.3–0.5 | ~0.06 (27.82× RTF) |
| Model size | 264 MB | ~400 MB (FP32 ONNX) |
| WER (clean) | ~5.5% | ~6.2% |
| Architecture | Transformer | Transformer (causal) |
| Runtime | GGML (C) | ONNX Runtime |

Moonshine processes audio **proportionally to input length** rather than fixed 30-second windows, which means short clips (2–5s) are dramatically faster.

### Spike plan

1. **Download Moonshine ONNX models** (tiny and base variants)
2. **Benchmark on target devices** using standalone ONNX Runtime Android test
3. **Compare WER** against Whisper small.en on Safe Word's test corpus
4. **Measure latency** for 2s, 5s, 10s, 30s clips
5. **Evaluate streaming support** — Moonshine supports token-level streaming natively

### Integration path

Moonshine uses ONNX Runtime, which Safe Word already ships for Silero VAD. This simplifies integration:

```kotlin
class MoonshineEngine : TranscriptionEngine {
    private val session: OrtSession  // Reuse existing ONNX Runtime dependency

    override suspend fun transcribe(
        samples: FloatArray,
        config: TranscriptionConfig,
    ): TranscriptionResult {
        // Moonshine-specific tokenization and inference
    }
}
```

The `TranscriptionCoordinator` would accept a `TranscriptionEngine` interface rather than directly depending on `WhisperEngine`, allowing A/B selection.

### Risks

- **No GGML version**: Moonshine is ONNX-only. Cannot reuse whisper.cpp infrastructure.
- **Quantization**: FP32 ONNX model is 400 MB. Need INT8 quantization for mobile (may not be available yet).
- **English-only**: Moonshine is English-only as of last research. Safe Word may need multi-language support.
- **Community maturity**: Newer project, less battle-tested than whisper.cpp.
- **ONNX Runtime version**: May need to align versions between Silero VAD and Moonshine.

### Decision criteria

- If Moonshine INT8 achieves ≤ 1% WER degradation vs Whisper Q8 at ≥3× speed: **adopt as default**
- If Moonshine is faster but WER is noticeably worse: **offer as "fast mode"** in settings
- If Moonshine doesn't have INT8 or is ≥400 MB: **defer** until quantized models ship

---

## DO-3 ▸ NNAPI / NPU delegation

**Impact**: ★★★★ (10–50% faster on supported SoCs)
**Effort**: 1–2 weeks
**Files**: `whisper-jni.cpp`, `CMakeLists.txt`, `WhisperEngine.kt`

### Problem

The STT Engineering Guide ranks NPU delegation as optimization #2. Modern flagship SoCs have dedicated neural processing units:

| SoC | NPU | TOPS | API |
|-----|-----|------|-----|
| Snapdragon 8 Gen 3 | Hexagon | 45 | NNAPI / QNN |
| Exynos 2400 | NPU | 37 | NNAPI |
| Tensor G3 | TPU v2 | 11 | NNAPI |
| MediaTek 9300 | APU 7.0 | 46 | NNAPI / NeuroPilot |

Current code only considers Vulkan GPU vs CPU. The NPU path is **completely absent**.

### Implementation options

**Option A — GGML NNAPI backend** (if available in upstream whisper.cpp):
```cmake
set(GGML_NNAPI ON CACHE BOOL "" FORCE)
```
Check upstream whisper.cpp for GGML_NNAPI support. Status: experimental/partial as of early 2025.

**Option B — ONNX Runtime NNAPI EP** (for Moonshine, if DO-2 is adopted):
```kotlin
val sessionOptions = OrtSession.SessionOptions().apply {
    addNnapi()  // NNAPI execution provider
}
```
ONNX Runtime's NNAPI EP is mature and well-tested on Android.

**Option C — Qualcomm QNN SDK** (Snapdragon-specific):
Direct integration with Qualcomm's QNN runtime for maximum Hexagon NPU utilization. Requires Snapdragon-specific build variant.

### Recommendation

If DO-2 (Moonshine) is adopted → **Option B** is trivial (ONNX Runtime already supports NNAPI EP).
If staying with Whisper → **Option A** after verifying upstream GGML support.
**Option C** only if pursuing Snapdragon-specific optimization tier.

### Risks

- **Op coverage**: Not all Transformer ops are delegated to NPU. Partial delegation causes CPU↔NPU data transfers that negate the benefit.
- **Quantization requirements**: Most NPUs require INT8/FP16 models. Q8_0 GGML format may not map cleanly.
- **Device fragmentation**: NPU capabilities vary wildly. Must implement capability detection and fallback.

### Expected gain

- 10–30% faster on mid-range (Tensor G3, Exynos 2200)
- 30–50% faster on flagship NPUs (Snapdragon 8 Gen 3) if full delegation achieved
- No gain on older SoCs without NNAPI ≥ 1.2

---

## DO-4 ▸ CPU affinity: pin inference to big cores

**Impact**: ★★★ (5–15% more consistent latency)
**Effort**: 3–5 days
**Files**: `whisper-jni.cpp` or new `ThreadPinning.kt` utility

### Problem

Current code sets `THREAD_PRIORITY_URGENT_AUDIO` which gives scheduling priority but does NOT pin threads to specific cores. On big.LITTLE SoCs, the scheduler may migrate threads between performance (P) and efficiency (E) cores mid-inference, causing:
- Cache invalidation on core migration
- Variable latency (P-core inference is 2–3× faster than E-core)
- Suboptimal throughput when threads land on E-cores

The STT Engineering Guide recommends explicit CPU affinity for inference threads.

### Implementation

**Option A — JNI-level affinity** (in `whisper-jni.cpp`):
```cpp
#include <sched.h>

void pin_to_big_cores(int n_threads) {
    cpu_set_t cpuset;
    CPU_ZERO(&cpuset);

    // Heuristic: big cores are typically the last N cores
    // On Snapdragon 8 Gen 3: cores 4-7 are A720/X4
    int n_cpus = sysconf(_SC_NPROCESSORS_CONF);
    int big_start = n_cpus > 4 ? n_cpus - 4 : 0;

    for (int i = big_start; i < n_cpus && i < big_start + n_threads; i++) {
        CPU_SET(i, &cpuset);
    }
    sched_setaffinity(0, sizeof(cpuset), &cpuset);
}
```

**Option B — Read sysfs topology** for reliable detection:
```cpp
// Read /sys/devices/system/cpu/cpu{N}/cpufreq/cpuinfo_max_freq
// Sort cores by max frequency, select top N
```

### Risks

- **Core topology varies by SoC**: Cannot hardcode core indices. Must read sysfs or use `android.os.CpuInfo`.
- **Permission issues**: `sched_setaffinity` may be restricted on some Android versions/OEMs.
- **Scheduler conflict**: Pinning may conflict with Android's EAS (Energy Aware Scheduler) and cause thermal issues.
- **OpenMP interaction**: If OpenMP manages its own thread pool, affinity may need to be set via `OMP_PLACES`/`OMP_PROC_BIND` environment variables before library load.

### Expected gain

- 5–10% more consistent RTF (reduces variance from core migration)
- 10–15% faster in worst case (prevents E-core scheduling entirely)
- No gain on homogeneous-core SoCs (rare in 2024+)

---

## DO-5 ▸ Chunked/progressive decoding for long recordings

**Impact**: ★★★ (improves time-to-first-text for recordings > 30s)
**Effort**: 1–2 weeks
**Files**: `TranscriptionCoordinator.kt`, `whisper-jni.cpp`

### Problem

For recordings longer than 30 seconds, the entire audio buffer is passed to `whisper_full()` as a single batch. whisper.cpp internally processes 30-second windows, but the Kotlin side must wait for ALL windows to complete before receiving any text.

### Architecture

```
Current (single batch):
  [=========== 60s audio ===========] → whisper_full() → all text at once

Proposed (chunked):
  [=== 30s chunk 1 ===] → whisper_full() → partial text → UI
  [=== 30s chunk 2 ===] → whisper_full() → append text → UI
```

### Implementation

```kotlin
// In TranscriptionCoordinator.transcribe():
private suspend fun transcribeChunked(
    samples: FloatArray,
    chunkDurationSec: Int = 30,
): TranscriptionResult {
    val chunkSize = chunkDurationSec * 16000
    val chunks = samples.toList().chunked(chunkSize).map { it.toFloatArray() }
    val results = mutableListOf<String>()

    for ((index, chunk) in chunks.withIndex()) {
        val result = whisperEngine.transcribe(chunk, /* ... */)
        results.add(result.text)
        _state.value = TranscriptionState.Transcribing(
            partialText = results.joinToString(" "),
            progress = (index + 1).toFloat() / chunks.size,
        )
    }
    return TranscriptionResult(text = results.joinToString(" "), /* ... */)
}
```

### Interaction with DO-1 (streaming)

If DO-1 is implemented, chunked decoding becomes less critical for perceived latency (streaming already provides progressive text). However, chunking still provides:
- Memory savings (only one 30s chunk in GGML buffers at a time)
- Progress indication for very long recordings
- Cancellation granularity (can cancel between chunks)

### Risks

- **Sentence-boundary splitting**: Naively splitting at 30s boundaries may cut mid-word/sentence. Need overlap or VAD-aligned boundaries.
- **Context loss**: `no_context = true` means each chunk starts fresh. For long recordings, this may produce repetition or inconsistency at boundaries.
- **Increased total inference time**: N chunks × overhead > 1 large batch (GGML buffer allocation per chunk).

### Expected gain

- Time-to-first-text for 60s recording: from ~4s to ~2s
- Memory peak reduced ~50% for long recordings
- Not applicable for typical <30s recordings

---

## DO-6 ▸ Model distillation: ship `distil-whisper` small.en

**Impact**: ★★★★ (2× faster inference, similar WER)
**Effort**: 1 week (model conversion + testing)
**Files**: `ModelInfo.kt`, model download infrastructure

### Problem

`distil-whisper` models are knowledge-distilled versions of Whisper that match original WER within 1% while being **2× faster** due to:
- Reduced decoder layers (2 instead of 12 for small)
- Same encoder (preserves feature extraction quality)
- Trained on pseudo-labelled data from the teacher model

### Available models

| Model | Decoder layers | Size (Q8) | Speed vs original |
|-------|---------------|-----------|-------------------|
| distil-small.en | 2 | ~130 MB | ~2× faster |
| distil-medium.en | 2 | ~250 MB | ~3× faster |

### Implementation

1. Convert `distil-small.en` to GGML format using `whisper.cpp/models/convert-huggingface.py`
2. Quantize to Q8_0 (or Q4_0 for maximum speed)
3. Add as alternative model in `ModelInfo.kt`:
```kotlin
ModelInfo(
    id = "distil-small.en-q8_0",
    displayName = "Small English (Fast)",
    fileName = "ggml-distil-small.en-q8_0.bin",
    downloadUrl = "...",
    sizeBytes = 130_000_000L,
    isDefault = false,
)
```
4. Let users choose in settings

### Risks

- **Distilled models may have trouble with**: accented English, noisy environments, domain-specific vocabulary
- **GGML conversion**: distil-whisper may require specific whisper.cpp version for compatibility
- **Testing burden**: Need to validate WER on Safe Word's evaluation corpus

### Expected gain

- 2× faster decode (encoder speed unchanged)
- 50% smaller model download
- Very close WER for clean, conversational English

---

## DO-7 ▸ Mel spectrogram caching for retry/correction

**Impact**: ★★ (niche but eliminates redundant work)
**Effort**: 3–5 days
**Files**: `whisper-jni.cpp`, `WhisperEngine.kt`

### Problem

whisper.cpp computes mel spectrograms from raw PCM on every `whisper_full()` call. If the same audio is re-transcribed (e.g., user correction, parameter adjustment, voice command retry), the mel computation is redundant.

The STT Engineering Guide notes mel computation takes 5–15% of total pipeline time.

### Implementation

Expose `whisper_pcm_to_mel()` separately via JNI, cache the mel output:

```cpp
// New JNI function:
extern "C" JNIEXPORT jfloatArray JNICALL
Java_...WhisperLib_nativeComputeMel(
    JNIEnv* env, jobject, jlong contextPtr, jfloatArray samples);

// Modified transcribe — accept pre-computed mel:
extern "C" JNIEXPORT jstring JNICALL
Java_...WhisperLib_nativeTranscribeFromMel(
    JNIEnv* env, jobject, jlong contextPtr, jfloatArray mel, ...);
```

### Risks

- **Memory**: Mel spectrogram for 30s audio is ~2.4 MB (80 mel bins × 3000 frames × float). Manageable.
- **API fragility**: Tightly coupled to whisper.cpp internal mel format. May break on upstream updates.
- **Limited applicability**: Most transcriptions happen once. Benefit only on retries.

### Expected gain

- 5–15% savings on re-transcription of the same audio
- No gain on first transcription

---

## Priority Matrix

| ID | Optimization | Gain | Risk | Effort | Priority |
|----|-------------|------|------|--------|----------|
| **DO-1** | Streaming (`new_segment_callback`) | ★★★★★ | Medium | 2–3 weeks | **P0** |
| **DO-2** | Moonshine evaluation | ★★★★★ | High | 2–4 weeks | **P0** (spike) |
| **DO-6** | distil-whisper small.en | ★★★★ | Low | 1 week | **P1** |
| **DO-3** | NNAPI/NPU delegation | ★★★★ | High | 1–2 weeks | **P1** |
| **DO-4** | CPU affinity big cores | ★★★ | Medium | 3–5 days | **P2** |
| **DO-5** | Chunked decoding | ★★★ | Medium | 1–2 weeks | **P2** |
| **DO-7** | Mel cache | ★★ | Low | 3–5 days | **P3** |

### Recommended sequence

```
Sprint 1 (P0):  DO-1 (streaming) — highest UX impact
                 DO-2 spike only (Moonshine benchmark, no integration yet)

Sprint 2 (P1):  DO-6 (distil-whisper) — fastest to ship, 2× speedup
                 DO-3 (NNAPI) — IF Moonshine spike shows ONNX NNAPI EP works

Sprint 3 (P2):  DO-4 (CPU affinity) — polish pass
                 DO-5 (chunking) — only if streaming doesn't obviate it

Backlog (P3):   DO-7 (mel cache) — niche, defer
```

### Dependencies

```
DO-1 (streaming) ─────────────── standalone, no deps
DO-2 (Moonshine) ─── spike ───→ DO-3 (NNAPI via ONNX Runtime)
DO-3 (NNAPI) ────────────────── depends on DO-2 decision
DO-4 (CPU affinity) ──────────── standalone
DO-5 (chunking) ──────────────── partially superseded by DO-1
DO-6 (distil-whisper) ────────── standalone
DO-7 (mel cache) ─────────────── standalone
```

---

## Cumulative impact estimate

With Quick Wins (Plan 1) + Deep Optimizations (Plan 2):

| Scenario | Current RTF | After QW | After QW + DO | Perceived latency |
|----------|------------|----------|----------------|-------------------|
| 5s clip, Snapdragon 8 Gen 3 | ~0.35 | ~0.25 | ~0.12 (Moonshine) | <200 ms (streaming) |
| 10s clip, Pixel 8 | ~0.40 | ~0.30 | ~0.15 (distil + I8MM) | <400 ms (streaming) |
| 30s clip, mid-range | ~0.50 | ~0.40 | ~0.25 (distil + NNAPI) | <1s to first text |

These are rough estimates. Actual gains depend on device, audio content, and VAD behavior. **Benchmark before and after each change.**
