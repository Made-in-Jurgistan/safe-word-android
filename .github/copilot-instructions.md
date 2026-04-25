# Safe Word — AI Coding Assistant Instructions

## Project Overview

Safe Word is an **Android voice dictation app** that transcribes speech into text using an on-device STT engine (Moonshine Voice) and inserts text into any app via `AccessibilityService`. It is **not** an IME/keyboard — text insertion uses Android's accessibility framework, not `InputMethodService`.

- **Single module**: `:app` — namespace `com.safeword.android`
- **Kotlin 2.1.10**, minSdk 33, targetSdk/compileSdk 36, ABI: arm64-v8a only
- **Language**: Kotlin exclusively; no Java source files
- **Native C++ layer**: `app/src/main/cpp/moonshine-bridge.cpp` wraps the open-source `moonshine-core` C++ library via JNI. CMake builds `libmoonshine-bridge.so`. Kotlin side: `MoonshineNativeBridge.kt` (JNI declarations) → `MoonshineNativeEngine.kt` (streaming engine). ONNX Runtime for Silero VAD comes from a pre-built AAR.

---

## Build & Test

```bash
# Debug build
.\gradlew.bat assembleDebug

# Clean + debug build
.\gradlew.bat clean assembleDebug

# Compile Kotlin only (fast check)
.\gradlew.bat :app:compileDebugKotlin

# All unit tests
.\gradlew.bat :app:testDebugUnitTest

# Focused unit tests (faster)
.\gradlew.bat :app:testDebugUnitTest --tests "com.safeword.android.transcription.*"

# Compile Android test sources
.\gradlew.bat :app:compileDebugAndroidTestKotlin
```

- `lint { abortOnError = true; warningsAsErrors = true }` — all lint warnings are build failures
- A pre-compile hook (`.github/hooks/pre-compile.json`) auto-runs `compileDebugKotlin` after every `.kt` file edit and surfaces errors/warnings
- Gradle 9.3.1 with configuration cache enabled (`org.gradle.configuration-cache=true`)
- KSP for annotation processing (Room, Hilt) — **not** kapt

---

## Architecture

### Package Structure

| Package          | Responsibility                                                                                                         |
| ---------------- | ---------------------------------------------------------------------------------------------------------------------- |
| `audio/`         | `AudioRecorder` (16 kHz PCM capture, streaming-only), `SileroVadDetector` (Silero v6 ONNX, 30 ms windows)              |
| `transcription/` | STT orchestration, Moonshine engine, post-processing pipeline, personal vocabulary, phonetic matching                  |
| `service/`       | Android services: `FloatingOverlayService`, `SafeWordAccessibilityService`, `ThermalMonitor`                           |
| `data/db/`       | Room database (v4) — `personal_vocabulary`, `mic_access_events` tables                                                 |
| `data/settings/` | DataStore preferences (`SettingsRepository`, `AppSettings`, `OnboardingRepository`)                                    |
| `data/model/`    | STT model lifecycle: `ModelRepository` (download/cache), `ModelInfo`, `ModelDownloadWorker` (WorkManager)              |
| `di/`            | Hilt modules: `AppModule` (Room, DAOs), `CoroutineScopesModule` (`@ApplicationScope`)                                  |
| `ui/`            | Jetpack Compose — Onboarding, Settings, Splash screens; `SafeWordNavGraph`; `OverlayMicButton`, `StreamingTextPreview` |

### Source Files — Complete Inventory

**`audio/`** (5 files):
`AdaptiveVadSensitivity.kt`, `AudioPreprocessor.kt`, `AudioRecorder.kt`, `SilenceAutoStopDetector.kt`, `SileroVadDetector.kt`

**`transcription/`** (33 files):
`AhoCorasickMatcher.kt`, `CompositionalCommandMatcher.kt`, `ConfusionSetCorrector.kt`, `ContentNormalizer.kt`, `ContextualGrammarCorrector.kt`, `CorrectionLearner.kt`, `DisfluencyNormalizer.kt`, `DormancyWorker.kt`, `HallucinationFilter.kt`, `IncrementalTextInserter.kt`, `InverseTextNormalizer.kt`, `ModelManager.kt`, `MoonshineNativeBridge.kt`, `MoonshineNativeEngine.kt`, `OptimalParameters.kt`, `PerformanceMonitor.kt`, `PhoneticIndex.kt`, `SemanticCommandMatcher.kt`, `SentenceEmbeddingModel.kt`, `SpokenSymbolConverter.kt`, `SpokenSymbolTables.kt`, `StreamingTranscriptionEngine.kt`, `StringDistance.kt`, `SymSpellCorrector.kt`, `TextFormatter.kt`, `TranscriptionCoordinator.kt`, `TranscriptionState.kt`, `VocabularyObserver.kt`, `VocabularyPatternCache.kt`, `VoiceAction.kt`, `VoiceCommandDetector.kt`, `VoiceCommandRegistry.kt`, `WordPieceTokenizer.kt`

**`service/`** (4 files):
`AccessibilityStateHolder.kt`, `FloatingOverlayService.kt`, `SafeWordAccessibilityService.kt`, `ThermalMonitor.kt`

**`data/`** (2 files at package root):
`MicAccessEventRepository.kt`, `PersonalVocabularyRepository.kt`

**`data/db/`** (5 files):
`MicAccessEventDao.kt`, `MicAccessEventEntity.kt`, `PersonalVocabularyDao.kt`, `PersonalVocabularyEntity.kt`, `SafeWordDatabase.kt`

**`data/model/`** (3 files): `ModelDownloadWorker.kt`, `ModelInfo.kt`, `ModelRepository.kt`

**`data/settings/`** (3 files): `AppSettings.kt`, `OnboardingRepository.kt`, `SettingsRepository.kt`

**`di/`** (2 files): `AppModule.kt`, `CoroutineScopesModule.kt`

**`util/`** (2 files): `AppDataStore.kt`, `InstallSourceDetector.kt`

**`ui/`**: `MainActivity.kt`, `SafeWordApp.kt`, plus `components/` (`GlassComponents.kt`, `OverlayMicButton.kt`, `StreamingTextPreview.kt`), `navigation/` (`NavigationRoutes.kt`, `SafeWordNavGraph.kt`), `screens/` (onboarding: `AccessibilityPage.kt`, `CompletePage.kt`, `DeviceStepsProvider.kt`, `ModelDownloadPage.kt`, `OnboardingScreen.kt`, `OnboardingStepConstants.kt`, `OnboardingStepPage.kt`, `OnboardingViewModel.kt`, `WelcomePage.kt`; settings: `SettingsScreen.kt`, `SettingsViewModel.kt`; splash: `SplashScreen.kt`), `theme/` (`Theme.kt`)

### Voice Pipeline

```
AudioRecorder (16 kHz PCM, 480-sample chunks = 30 ms)
  ├─► SileroVadDetector (per-chunk speech probability, ONNX Runtime)
  └─► onProcessedChunk callback
        └─► MoonshineNativeEngine.feedAudio() [via buffered Channel]
              ├─► TranscriptEvent.LineTextChanged → partial text to UI
              └─► TranscriptEvent.LineCompleted → TranscriptionCoordinator
                    ├─► ContentNormalizer.preProcess (steps 1–5: clean, dehallucinate, defill, derepair)
                    ├─► ConfusionSetCorrector (personal vocabulary, phonetic matching, SymSpell)
                    ├─► ContentNormalizer.normalizePostPreProcess (steps 6–10: emoji, punctuation, ITN)
                    ├─► TextFormatter (sentence-case, pronoun-I, trailing punctuation, spacing)
                    └─► AccessibilityStateHolder → SafeWordAccessibilityService → target app
```

### Android Services

| Service                        | Type                    | Purpose                                                                                   |
| ------------------------------ | ----------------------- | ----------------------------------------------------------------------------------------- |
| `FloatingOverlayService`       | Foreground (microphone) | Draggable ComposeView mic button over all apps; keeps mic capture alive when backgrounded |
| `SafeWordAccessibilityService` | Accessibility           | Text insertion via `ACTION_SET_TEXT` / clipboard fallback                                 |

### Android Permissions

`RECORD_AUDIO`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `INTERNET`, `ACCESS_NETWORK_STATE`, `SYSTEM_ALERT_WINDOW`

### Key Design Decisions

- **Streaming-only architecture**: Audio flows from `AudioRecorder` → `onProcessedChunk` callback → `MoonshineNativeEngine.feedAudio()` in real time. There is no batch/accumulation mode.
- **Single-threaded JNI dispatcher**: All Moonshine JNI calls run on a dedicated `newSingleThreadExecutor("MoonshineFeed")` dispatcher + `SupervisorJob()` to prevent concurrent native access.
- **Moonshine Native API**: `MoonshineNativeBridge` JNI object → `nativeLoadTranscriber(path, modelArch)` → `nativeCreateStream` → `nativeStartStream` → `nativeAddAudio`/`nativeTranscribeStream` loop → `nativeStopStream` → `nativeFreeStream`. Returns JSON line snapshots parsed by `MoonshineNativeEngine`. `nativeFreeTranscriber()` releases all resources.
- **Buffered audio channel**: A `Channel<FloatArray>(64)` (~1.9 s buffer) decouples AudioRecord from inference, absorbing jitter without back-pressure.
- **ONNX Runtime conflict**: `packaging.jniLibs.pickFirsts` resolves `libonnxruntime.so` conflict between `onnxruntime-android` and the native build. The correct `.so` is in `app/src/main/jniLibs/arm64-v8a/`. `useLegacyPackaging = true` extracts native libs for ONNX discovery.
- **AccessibilityService for text insertion** — not InputMethodService. Falls back to clipboard paste if `ACTION_SET_TEXT` fails.
- **Thermal degradation**: `ThermalMonitor` exposes a 3-tier status (NOMINAL/WARM/HOT). WARM → CPU-only inference. HOT → pause transcription.
- **Personal vocabulary**: `ConfusionSetCorrector` applies word-boundary replacements from Room `personal_vocabulary` table. For >20 entries, `AhoCorasickMatcher` provides O(text+matches) multi-pattern search. `PhoneticIndex` (Soundex + Levenshtein) catches phonetic near-misses.

### Room Database

- **Version**: 4
- **Tables**: `personal_vocabulary`, `mic_access_events`
- **Migration strategy**: Explicit `Migration` objects (e.g., `MIGRATION_3_4` adds composite index). Destructive fallback from v2.
- **Schema export**: `room.schemaLocation` → `app/schemas/`

---

## Coding Conventions

### Dependency Injection (Hilt)

- **Constructor injection only** — no `@Inject` on fields or setters
- `@Singleton` for long-lived services: `TranscriptionCoordinator`, `ModelManager`, `SileroVadDetector`, `AudioRecorder`, `SettingsRepository`, `AccessibilityStateHolder`, `ThermalMonitor`, `VocabularyPatternCache`, `PhoneticIndex`, Room DAOs
- `@ApplicationContext` qualifier for `Context`
- `@ApplicationScope` qualifier for the app-level `CoroutineScope` (provided by `CoroutineScopesModule`)
- `SafeWordApp` implements `Configuration.Provider` for `HiltWorkerFactory`
- `SafeWordAccessibilityService` uses `@EntryPoint` for DI (accessibility services can't use `@AndroidEntryPoint`)

### Coroutines

- **ViewModels**: `viewModelScope` for UI-initiated work; expose `StateFlow` via `stateIn(SharingStarted.WhileSubscribed(5_000), initialValue)`
- **Repositories**: `Flow` from Room/DataStore; never call `.first()` on the main thread — use `withContext(Dispatchers.IO)`
- **JNI callers**: dedicated single-thread `feedDispatcher` via `Executors.newSingleThreadExecutor()` — see `MoonshineNativeEngine.feedScope`
- **Never catch `CancellationException`** — let structured concurrency propagate cancellation
- Use `withTimeoutOrNull` for graceful timeout handling
- `@ApplicationScope` scope (`SupervisorJob() + Dispatchers.Default`) for coordinator-level work that outlives individual ViewModels

### State Management

- `TranscriptionState` is a sealed interface: `Idle` → `Recording` → `Done` → `Idle`, or `Error` from any state
- `Recording` state carries `durationMs`, `amplitudeDb`, `speechProbability`, `partialText`, `insertedText`
- All state transitions go through `TranscriptionCoordinator` which owns the `MutableStateFlow<TranscriptionState>`
- UI observes state via `collectAsStateWithLifecycle()` in Compose

### Timber Logging

All log messages follow the format: `[PREFIX] methodName | key=value | key2=value2`

| Prefix        | Level                   | Use for                                        |
| ------------- | ----------------------- | ---------------------------------------------- |
| `INIT`        | `Timber.i`              | Initialization, model loading, component setup |
| `LIFECYCLE`   | `Timber.i`              | Service/app/view lifecycle transitions         |
| `STATE`       | `Timber.d` / `Timber.i` | State machine transitions                      |
| `BRANCH`      | `Timber.d`              | Conditional logic, fallback paths              |
| `VOICE`       | `Timber.d` / `Timber.i` | Dictation, voice commands                      |
| `SETTINGS`    | `Timber.i`              | Settings reads/writes                          |
| `ENTER`       | `Timber.d`              | Method entry points (non-trivial paths)        |
| `EXIT`        | `Timber.i`              | Task completion, worker exit                   |
| `PERF`        | `Timber.i`              | Latency, inference times                       |
| `WARN`        | `Timber.w`              | Edge cases, data-loss prevention               |
| `ERROR`       | `Timber.e`              | Exceptions, permanent failures                 |
| `DIAGNOSTICS` | `Timber.i` / `Timber.w` | GPU capabilities, double-processing            |
| `THERMAL`     | `Timber.w`              | Thermal throttle events                        |

### Error Handling

- Log with `Timber.e(throwable, "[ERROR] method | msg")` — exception first, then message
- Return sealed `Result`/`StateFlow` states instead of throwing across layer boundaries
- Room errors: let transaction rollback; catch and log at the DAO call site
- Model download: exponential backoff with 3 retries; SHA256 verification post-download
- Microphone: throw `SecurityException` if `RECORD_AUDIO` not granted; check in `AudioRecorder.record()`

---

## Testing

**Framework stack**: JUnit 4 · MockK 1.13.12 · Turbine 1.1.0 · `kotlinx-coroutines-test 1.8.1`

- Test files: `app/src/test/kotlin/com/safeword/android/…` — mirrors source structure
- Naming: `{ClassName}Test.kt`
- Coroutine tests: `runTest { }` with `MainDispatcherRule` from `util/MainDispatcherRule.kt`
- Flow tests: `turbineScope { flow.test { … } }` or `Turbine()`
- Mocking: `mockk<T>()` + `coEvery`, `coVerify`; use `relaxed = true` sparingly — prefer explicit stubs
- `@OptIn(ExperimentalCoroutinesApi::class)` required for `advanceUntilIdle()` / `StandardTestDispatcher`

```kotlin
@get:Rule val mainDispatcherRule = MainDispatcherRule()

@Test fun `my test description`() = runTest {
    // arrange / act / assert
}
```

### Existing Test Coverage

| Package          | Test files                                                                                                                                                                       |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `audio/`         | `AudioPipelineTest`                                                                                                                                                              |
| `transcription/` | `IncrementalTextInserterTest`, `ModelLifecycleTest`, `StreamingEngineContractTest`, `TextPostProcessingPipelineTest`, `TranscriptionCoordinatorTest`, `VoiceCommandPipelineTest` |
| `testutil/`      | `FakeAccessibilityStateHolder`, `FakeStreamingEngine`, `PipelineAssertions` (test infrastructure)                                                                                |
| `util/`          | `MainDispatcherRule` (test infrastructure)                                                                                                                                       |

---

## Key Dependencies (versions in `app/build.gradle.kts`)

| Library                                                | Purpose                                   |
| ------------------------------------------------------ | ----------------------------------------- |
| `com.microsoft.onnxruntime:onnxruntime-android:1.23.0` | Silero VAD inference (ONNX)               |
| `com.google.dagger:hilt-android:2.54`                  | Dependency injection                      |
| `androidx.room:room-runtime:2.8.4`                     | Local database (KSP code gen)             |
| `androidx.datastore:datastore-preferences:1.2.1`       | Typed preferences                         |
| `com.jakewharton.timber:timber:5.0.1`                  | Structured logging                        |
| `com.squareup.okhttp3:okhttp:4.12.0`                   | Model downloads with resume support       |
| `androidx.work:work-runtime-ktx:2.11.2`                | Background model download scheduling      |
| `androidx.media3:*:1.10.0`                             | Video playback (splash screen)            |
| Compose BOM `2026.03.01`                               | Jetpack Compose                           |

### Native Libraries

- `app/src/main/cpp/moonshine-bridge.cpp` + `moonshine-core/` — C++ JNI bridge wrapping the open-source Moonshine C++ library; built by CMake into `libmoonshine-bridge.so`
- `app/src/main/jniLibs/arm64-v8a/libonnxruntime.so` — manually extracted from `onnxruntime-android-1.23.0.aar` to resolve ABI conflict
- `app/src/main/assets/silero_vad.onnx` — Silero VAD v6 model, loaded by `SileroVadDetector`
- Moonshine model files are downloaded at runtime into `files/models/` via `ModelRepository`

---

## Common Pitfalls

- **Do not use `@Inject` on fields** — constructor injection only (Hilt convention for this project)
- **Never call `.first()` on a Flow from the main thread** — use `withContext(Dispatchers.IO)`
- **ONNX Runtime `.so` conflict**: do not add new ONNX-dependent libraries without verifying `pickFirsts` in `app/build.gradle.kts` covers them — the correct `libonnxruntime.so` lives in `jniLibs/arm64-v8a/`
- **ABI filter**: only `arm64-v8a` is enabled — x86/emulator builds will fail; use a real device or arm64 emulator image
- **Lint is strict**: `warningsAsErrors = true` — fix all warnings before committing
- **No batch/accumulation mode**: `AudioRecorder` streams directly to `onProcessedChunk`. Do not add sample buffer accumulation.
- **Native C++ code is CMake-managed**: `app/src/main/cpp/` contains the JNI bridge and moonshine-core. The CMake build is configured in `app/build.gradle.kts`. Do not add new JNI source files without updating `CMakeLists.txt`.
- **WorkManager initializer disabled**: `SafeWordApp` provides its own via `Configuration.Provider` — do not re-enable the default `InitializationProvider`
- **Room schema version is 4**: Uses explicit `Migration` objects (e.g., `MIGRATION_3_4` adds composite index for vocabulary correction) and destructive fallback from v2. When adding tables or columns, increment the version and add a new `Migration`.
