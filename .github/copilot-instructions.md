# Safe Word Android — Copilot Instructions

## Project overview

Safe Word is an on-device voice-to-text Android app. All speech inference (whisper.cpp)
and voice activity detection (Silero VAD via ONNX Runtime and optional native GGML Silero VAD in whisper.cpp) run locally — no audio or text
leaves the device. The app provides a floating overlay mic button that works over any app
and inserts transcribed text via an Accessibility Service.

Safe Word is **not** a keyboard IME — it is a voice-only extension to the user's existing keyboard.

**Gradle project name**: `SafeWordAndroid`
**Package**: `com.safeword.android`
**Min SDK**: 33 (Android 13) | **Target SDK**: 35 | **Compile SDK**: 35
**NDK**: 28.2.13676358 | **ABI**: arm64-v8a only
**Kotlin**: 2.1.10 | **Java target**: 17 | **KSP** (not kapt)

---

## Repository layout

```
app/
├── src/main/java/com/safeword/android/
│   ├── audio/                  # Audio capture and VAD
│   │   ├── AudioRecorder.kt         # 16 kHz mono PCM via AudioRecord
│   │   ├── FloatRingBuffer.kt       # Lock-free ring buffer for audio samples
│   │   └── SileroVadDetector.kt     # Silero VAD via ONNX Runtime
│   ├── transcription/          # Speech-to-text pipeline
│   │   ├── WhisperLib.kt            # JNI declarations (native methods)
│   │   ├── WhisperEngine.kt         # Model lifecycle (init/free/preload)
│   │   ├── TranscriptionCoordinator.kt  # Orchestrates record→VAD→infer→post-process
│   │   ├── TranscriptionState.kt    # Sealed interface state machine
│   │   ├── TranscriptionResult.kt   # Data class for inference output
│   │   ├── InferenceConfig.kt       # Adaptive thread tuning heuristic
│   │   ├── VoiceCommandDetector.kt  # Phase 1: voice command matching
│   │   ├── ContentNormalizer.kt     # Phase 2: emoji, punctuation, normalization
│   │   ├── TextFormatter.kt         # Phase 3: capitalization, whitespace cleanup
│   │   ├── TextPostProcessor.kt     # Chains phases 1→2→3
│   │   └── ConfusionSetCorrector.kt # Homophone Levenshtein correction
│   ├── service/                # Android services
│   │   ├── FloatingOverlayService.kt      # SYSTEM_ALERT_WINDOW overlay host
│   │   ├── OverlayMicButton.kt            # Draggable floating mic button
│   │   ├── RecordingService.kt            # Foreground service for mic capture
│   │   ├── SafeWordAccessibilityService.kt # Text insertion + voice commands
│   │   └── ThermalMonitor.kt              # PowerManager thermal throttle
│   ├── data/
│   │   ├── db/                 # Room database (transcription history)
│   │   ├── model/              # Model download (WorkManager + OkHttp)
│   │   └── settings/           # DataStore preferences
│   │       ├── SettingsRepository.kt  # Flow-based settings access
│   │       └── AppSettings.kt        # Data class with defaults
│   ├── ui/                     # Jetpack Compose UI
│   │   ├── screens/            # onboarding/, settings/, models/, splash/
│   │   ├── components/         # GlassCard, GlassSurface, etc.
│   │   ├── navigation/         # SafeWordNavGraph
│   │   └── theme/              # Material 3 theme, colors
│   ├── di/                     # Hilt modules
│   │   ├── AppModule.kt             # Room, DAO singletons
│   │   └── CoroutineScopesModule.kt # @ApplicationScope
│   ├── SafeWordApp.kt         # @HiltAndroidApp, Timber, WorkManager
│   └── MainActivity.kt        # Single-activity Compose host
├── src/main/cpp/
│   ├── whisper-jni.cpp         # Custom JNI bridge (nativeInit, Transcribe, Free)
│   ├── CMakeLists.txt          # Top-level native build config
│   └── whisper.cpp/            # Upstream whisper.cpp submodule (GGML, Vulkan)
├── src/test/kotlin/            # JVM unit tests (JUnit 4 + Mockk + Turbine)
└── src/androidTest/            # Instrumentation tests
docs/                           # HTML reference documentation (read-only)
scripts/                        # Build/eval scripts (currently empty)
prd.md                          # Product Requirements Document
```

---

## Architecture

### Transcription pipeline

```
AudioRecorder (16 kHz PCM)
  → SileroVadDetector (speech probability per 30 ms frame)
  → TranscriptionCoordinator (orchestrates everything)
    ├── No-speech density short-circuit (<5% speech → skip inference)
    ├── WhisperEngine.transcribe (JNI → whisper.cpp)
    │   ├── Vulkan GPU (default) with CPU fallback
    │   ├── Prewarm pass on load (1s silent inference)
    │   ├── Backend switch hysteresis (3 consecutive slow RTFs)
    │   └── ThermalMonitor gate (disable GPU at THERMAL_STATUS_MODERATE)
    ├── ConfusionSetCorrector.apply (homophone fix)
    └── TextPostProcessor.process
        ├── Phase 1: VoiceCommandDetector (→ CommandDetected state)
        ├── Phase 2: ContentNormalizer (emoji, punctuation, disfluency)
        └── Phase 3: TextFormatter (capitalization, whitespace, trimming)
```

### State machine

`TranscriptionState` is a sealed interface observed via `StateFlow`:

```
Idle → Recording → Transcribing → Done
                                 → CommandDetected
Any state → Error(message, previousState)
```

### Dependency injection

Hilt with KSP. Two modules:

- `AppModule` — Room database, DAO (singleton)
- `CoroutineScopesModule` — `@ApplicationScope` CoroutineScope (SupervisorJob + Default)

Constructor injection everywhere. No field injection except `@Inject lateinit var` in
Android framework classes (Application, Service, Activity).

### Threading model

- **Main/UI**: Compose rendering, state observation
- **IO dispatcher**: Audio recording, file I/O, model loading, DataStore
- **Default dispatcher**: Inference coordination, VAD processing, post-processing
- **Native threads**: whisper.cpp manages its own thread pool (adaptive 2–4 threads via InferenceConfig)

`TranscriptionCoordinator` serialises access to `whisper_context` via Kotlin-side `Mutex`.
The JNI context pointer (`jlong`) is NOT thread-safe for concurrent native calls.

---

## Coding standards

### Kotlin conventions

- **Kotlin 2.1.10**, target Java 17, KSP annotation processing (never kapt)
- Trailing commas on multi-line parameter lists and collections
- Named arguments when calling functions with 3+ parameters
- Never use `!!` — use `requireNotNull()`, `checkNotNull()`, or `?.let { }` / `?: return`
- Prefer `sealed interface` over `sealed class` for state hierarchies
- Use `data class` for value objects; `data object` for singletons (e.g. `Idle`)
- Destructure data classes where it improves clarity
- Extensions over utility classes
- Explicit return types on public functions
- No wildcard imports — import each symbol individually

### Coroutines

- Structured concurrency: always launch inside a `CoroutineScope` (viewModelScope, lifecycleScope, @ApplicationScope)
- Use `StateFlow` / `MutableStateFlow` for observable state, **never** `LiveData`
- Collect in Compose with `collectAsStateWithLifecycle()`
- Never call `runBlocking` on the main thread
- Use `withContext(Dispatchers.IO)` for blocking calls
- Cancellation-safe: check `isActive` or use `ensureActive()` in long loops

### Compose

- Functional components only — no class-based views
- State hoisting: stateless composables receive state as parameters and emit events
- `Modifier` is always the first optional parameter
- Use `remember` / `derivedStateOf` to avoid unnecessary recomposition
- ViewModels expose `StateFlow<UiState>`, not individual fields
- No side effects in composable scope — use `LaunchedEffect`, `DisposableEffect`, `SideEffect`

### Error handling

- Prefer `Result<T>` or sealed-interface result types for expected failures
- Use `try`/`catch` only at system boundaries (JNI, I/O, framework callbacks)
- Whisper JNI: returns empty string or fallback JSON on failure, never throws
- GPU init failure: silent CPU fallback, logged with `[BRANCH]` prefix
- Log errors with `Timber.e` and `[ERROR]` prefix

### Naming conventions

- Classes: PascalCase (`TranscriptionCoordinator`)
- Functions/properties: camelCase (`optimalWhisperThreads`)
- Constants: SCREAMING_SNAKE_CASE (`NO_SPEECH_DENSITY_THRESHOLD`)
- Package: lowercase dot-separated (`com.safeword.android.transcription`)
- Test classes: `{ClassName}Test` in matching package under `src/test/kotlin/`

---

## Timber logging

All log messages use structured prefixes. **Always** use these prefixes:

| Prefix          | Level             | When to use                                  |
| --------------- | ----------------- | -------------------------------------------- |
| `[INIT]`        | `Timber.i` / `.d` | Component initialization, model loading      |
| `[LIFECYCLE]`   | `Timber.i`        | IME/app lifecycle events, service start/stop |
| `[STATE]`       | `Timber.d` / `.i` | State transitions (Idle→Recording, etc.)     |
| `[ENTER]`       | `Timber.d`        | Method/pipeline entry points                 |
| `[EXIT]`        | `Timber.i`        | Task completion, worker exit                 |
| `[BRANCH]`      | `Timber.d`        | Conditional logic, fallback paths            |
| `[WARN]`        | `Timber.w`        | Recoverable warnings, edge cases             |
| `[ERROR]`       | `Timber.e`        | Errors, exceptions, permanent failures       |
| `[SETTINGS]`    | `Timber.i`        | Settings changes, preference updates         |
| `[VOICE]`       | `Timber.d` / `.i` | Voice commands, dictation events             |
| `[PERF]`        | `Timber.i` / `.d` | Performance metrics, inference timings       |
| `[KEY]`         | `Timber.v` / `.d` | Keystroke events (dev-only, verbose)         |
| `[GESTURE]`     | `Timber.d`        | Gesture recognition events                   |
| `[DIAGNOSTICS]` | `Timber.i` / `.w` | GPU capabilities, system checks              |
| `[THERMAL]`     | `Timber.w` / `.d` | Thermal throttling events                    |
| `[AUTOFILL]`    | `Timber.d`        | Autofill suggestion responses                |

**Format**: `Timber.d("[PREFIX] ClassName.methodName | key=value | ...")`

Timber is planted only in debug builds (`BuildConfig.DEBUG` → `DebugTree`).

---

## Native code (C/C++)

### JNI bridge — `whisper-jni.cpp`

Four exported functions:

1. `nativeInit(modelPath)` → Load GGML model, return context pointer (`jlong`)
2. `nativeTranscribe(context, samples, params)` → Batch transcription → plain text
3. `nativeTranscribeWithTimings(context, samples, params)` → JSON with segment timings
4. `nativeFree(context)` → Release whisper context

**Key configuration**:

- `use_gpu = true` (Vulkan) with CPU-only retry on init failure
- `flash_attn = true` (20–40% speedup on Vulkan)
- `greedy.best_of = 1` (single-pass, no beam search)
- `temperature_inc = 0.0f` (no fallback retries)
- `no_context = true` (stateless batch mode)
- Thread count clamped to `[1, 16]`, set by `InferenceConfig.optimalWhisperThreads()`

### Build configuration — `CMakeLists.txt`

- Release mode: `-O3 -DNDEBUG` with thin LTO (`-flto=thin`)
- ARM NEON: `armv8.2-a+dotprod` enabled for arm64-v8a
- Vulkan: ON (shaders compiled via NDK `glslc`)
- OpenMP: ON for ARM only
- Debug builds add `-fsanitize=address`

### Thread safety

`whisper_context*` is **NOT thread-safe** for concurrent calls. The Kotlin-side
`TranscriptionCoordinator` serialises access via `Mutex`. Never call JNI methods
concurrently on the same context.

---

## Testing

### Framework

- **JUnit 4** (4.13.2) with `kotlin-test` assertions
- **Mockk** (1.13.12) for mocking
- **Turbine** (1.1.0) for Flow testing
- **kotlinx-coroutines-test** (1.8.1) with `MainDispatcherRule`

### Test layout

```
app/src/test/kotlin/com/safeword/android/
├── transcription/
│   ├── ContentNormalizerTest.kt
│   ├── TextFormatterTest.kt
│   ├── TextPostProcessorTest.kt
│   └── VoiceCommandDetectorTest.kt
├── ui/screens/
│   ├── onboarding/OnboardingViewModelTest.kt
│   └── settings/SettingsViewModelTest.kt
└── util/
    └── MainDispatcherRule.kt     # TestDispatcher rule for coroutines
```

### Conventions

- Test class: `{ClassName}Test` in matching package
- Test method naming: `methodName descriptive scenario in plain English`() with backticks
- Arrange–Act–Assert pattern
- Use `runTest { }` for suspending tests
- Use Turbine's `test { }` block for Flow assertions
- Prefer `every { ... } returns ...` over `coEvery` when not suspending
- Test public API, not implementation details

### Running tests

```bash
# All JVM unit tests
.\gradlew.bat :app:testDebugUnitTest

# Specific test class
.\gradlew.bat :app:testDebugUnitTest --tests "com.safeword.android.transcription.ContentNormalizerTest"

# Compile check only (no execution)
.\gradlew.bat :app:compileDebugKotlin :app:compileDebugAndroidTestKotlin
```

---

## Build commands

```bash
# Full debug build (APK)
.\gradlew.bat :app:assembleDebug

# Release build (requires signing config)
.\gradlew.bat :app:assembleRelease

# Run all JVM unit tests
.\gradlew.bat :app:testDebugUnitTest

# Run Android lint
.\gradlew.bat :app:lintDebug

# Clean build
.\gradlew.bat clean
```

The Gradle wrapper (`gradlew.bat` on Windows) must always be used — never a system Gradle.

---

## Key constants and defaults

From `AppSettings.kt`:

| Setting                   | Default   | Description                            |
| ------------------------- | --------- | -------------------------------------- |
| `maxRecordingDurationSec` | 600       | Auto-stop recording ceiling (seconds)  |
| `autoStopSilenceMs`       | 2000      | Silence duration before auto-stop (ms) |
| `vadEnabled`              | true      | Silero VAD active                      |
| `vadThreshold`            | 0.5       | VAD speech probability threshold       |
| `vadMinSpeechDurationMs`  | 250       | Minimum speech to trigger detection    |
| `vadMinSilenceDurationMs` | 100       | Minimum silence to end speech segment  |
| `nativeVadEnabled`        | true      | whisper.cpp built-in Silero VAD (GGML) |
| `nativeVadThreshold`      | 0.5       | Native VAD speech threshold            |
| `nativeVadMinSpeechMs`    | 250       | Native VAD minimum speech              |
| `nativeVadMinSilenceMs`   | 500       | Native VAD minimum silence             |
| `nativeVadSpeechPadMs`    | 300       | Native VAD speech padding              |
| `noSpeechThreshold`       | 0.6       | Whisper no_speech_prob suppression     |
| `logprobThreshold`        | -1.0      | Whisper avg logprob suppression        |
| `entropyThreshold`        | 2.4       | Whisper entropy-based suppression      |
| `colorPalette`            | "dynamic" | Material You dynamic colour            |

From `TranscriptionCoordinator.kt`:

| Constant                      | Value | Description                                 |
| ----------------------------- | ----- | ------------------------------------------- |
| `NO_SPEECH_DENSITY_THRESHOLD` | 0.05  | Skip inference below 5% speech ratio        |
| `BACKEND_SWITCH_HYSTERESIS`   | 3     | Consecutive slow RTFs before GPU→CPU switch |

From `InferenceConfig.kt`:

| Cores | Base threads          | Short clip (≤2 s) threads |
| ----- | --------------------- | ------------------------- |
| ≥ 6   | 4                     | 2                         |
| < 6   | (cores−1) clamped 2–4 | 2                         |

From `ModelInfo.kt`:

| Model ID        | Default | Artifact                 | Size    |
| --------------- | ------- | ------------------------ | ------- |
| `small.en-q8_0` | Yes     | `ggml-small.en-q8_0.bin` | 252 MiB |
| `silero-v6.2.0` | Yes     | `ggml-silero-v6.2.0.bin` | ~885 KB |

---

## Dependencies (production)

| Library               | Version    | Purpose                            |
| --------------------- | ---------- | ---------------------------------- |
| Jetpack Compose BOM   | 2024.12.01 | UI framework                       |
| Material 3            | (BOM)      | Design system                      |
| Hilt                  | 2.53.1     | Dependency injection (KSP)         |
| Room                  | 2.6.1      | Local database (KSP)               |
| DataStore Preferences | 1.1.1      | Key-value settings                 |
| WorkManager           | 2.10.0     | Background model download          |
| OkHttp                | 4.12.0     | HTTPS model download (TLS pinning) |
| ONNX Runtime Android  | 1.17.3     | Silero VAD inference               |
| Timber                | 5.0.1      | Structured logging                 |
| Coroutines            | 1.8.1      | Async concurrency                  |
| Media3 ExoPlayer      | 1.5.1      | Splash screen video                |

---

## Common pitfalls

1. **kapt vs KSP** — This project uses KSP exclusively. Never add `kapt()` dependencies.
2. **`!!` operator** — Banned. Use `requireNotNull()` or safe-call chains.
3. **LiveData** — Not used. All observable state is `StateFlow`.
4. **Blocking main thread** — Never `runBlocking` on Main. Use `withContext(Dispatchers.IO)`.
5. **JNI thread safety** — Never call whisper JNI concurrently on the same context pointer.
6. **Model paths** — Models are stored in app-private internal storage, never external.
7. **Native VAD path resolution** — Do not hardcode `silero_vad.onnx`; resolve native GGML VAD via `ModelRepository` using `ModelInfo.VAD_MODEL_ID`.
8. **Overlay permissions** — `SYSTEM_ALERT_WINDOW` requires explicit user grant; OEM-specific gates exist.
9. **Accessibility API** — Voice commands require `SafeWordAccessibilityService` to be enabled.
10. **NDK ABI** — arm64-v8a only. Do not add x86_64 or armeabi-v7a.
11. **Proguard** — Release builds minify. Keep rules in `proguard-rules.pro` when adding reflection.
