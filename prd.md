# PRD: Safe Word Android

## 1. Product overview

### 1.1 Document title and version

- PRD: Safe Word Android
- Version: 0.3.0
- Date: March 28, 2026

### 1.2 Product summary

Safe Word is an on-device voice-to-text Android application that performs real-time speech transcription entirely on the user's device, with zero cloud dependency. It uses whisper.cpp (via JNI) for speech-to-text inference and Silero VAD (via ONNX Runtime plus optional native GGML Silero VAD inside whisper.cpp) for voice activity detection, delivering privacy-first dictation through a floating overlay microphone button that works over any app. It adds a voice-only extension to the user's existing keyboard — Safe Word is not a replacement keyboard. Transcribed text is inserted directly into the focused text field via an Accessibility Service, or copied to the clipboard as a fallback.

The app also provides a three-phase text post-processing pipeline (voice command detection, content normalization with spoken emoji/punctuation conversion, and text formatting), a confusion-set corrector for common homophones, and a comprehensive voice command system for hands-free text editing. All AI inference runs locally — no audio or text ever leaves the device.

Safe Word targets Android 13+ (API 33) devices with arm64-v8a architecture, optimized for modern big.LITTLE SoCs with ARM NEON and optional Vulkan GPU acceleration. The inference pipeline includes adaptive thread tuning, no-speech density short-circuit, backend switch hysteresis, thermal throttling awareness, and auto-stop on sustained silence. The app includes a guided 5-step onboarding flow, persistent transcription history via Room database, and a frosted-glass Material 3 UI with a three-phase splash screen (noise animation, glitch video, neon logo reveal).

## 2. Goals

### 2.1 Business goals

- Deliver a privacy-first voice dictation app that differentiates from cloud-dependent competitors (Google Voice Typing, Samsung Voice Input) by performing all inference on-device
- Establish the Safe Word brand as the go-to solution for secure, offline voice-to-text on Android
- Build a foundation for a premium product with advanced voice features (expanded language packs, personalization engine, adaptive formatting)
- Achieve reliable batch transcription with low perceived latency on mainstream Android devices (Snapdragon 8 Gen 1+, Tensor G2+, Dimensity 9000+)

### 2.2 User goals

- Dictate text hands-free into any app without switching contexts, using a floating overlay mic button
- Get accurate, properly formatted transcription without manually fixing capitalization, punctuation, or filler words
- Control text editing with voice commands (delete, undo, copy, paste, select, navigate)
- Dictate and insert emoji by speaking their names ("thumbs up", "heart", "smiley face")
- Trust that no audio or text data leaves the device — complete privacy
- Download a compact on-device model pack (~253 MB total: Whisper + native VAD) during onboarding and start transcribing immediately

### 2.3 Non-goals

- Cloud-based transcription or hybrid cloud/on-device modes
- Real-time translation between non-English languages (only translate-to-English is supported)
- Custom keyboard IME (the app is a voice-only extension to existing keyboards, not a replacement)
- Word-level confidence scores or alternative transcription suggestions
- Speaker diarization (multi-speaker identification)
- Audio file import and batch transcription from files
- Wearable or Wear OS support

## 3. User personas

### 3.1 Key user types

- Privacy-conscious professionals who dictate sensitive content (legal, medical, financial)
- Mobile power users who prefer voice input over typing
- Accessibility users with motor impairments who rely on voice for text input
- Journalists and writers who need hands-free note-taking
- Multilingual users who need on-device transcription without cloud language restrictions

### 3.2 Basic persona details

- **Alex (Privacy Professional)**: A 38-year-old attorney who dictates case notes and client communications on the go. Refuses to use cloud-based dictation due to attorney-client privilege requirements. Needs accurate punctuation and formatting without manual editing. Uses the floating overlay to dictate into email, messaging, and document apps.

- **Sam (Accessibility User)**: A 29-year-old software developer with repetitive strain injury who relies on voice input for most text entry. Needs reliable voice commands for editing (delete, undo, select) to minimize hand use. Uses the overlay mic button constantly throughout the workday across all apps.

- **Maya (Mobile Writer)**: A 45-year-old freelance journalist who captures interview notes and article drafts via voice. Needs fast, accurate transcription for capturing thoughts on the go. Values spoken emoji and punctuation for expressive writing without keyboard switching.

### 3.3 Role-based access

- **Standard user**: Full access to all features — recording, transcription, voice commands, settings, history. No account or authentication required. Single-user local app.

## 4. Functional requirements

- **On-device speech-to-text engine** (Priority: P0 — Critical)
  - Whisper.cpp inference via JNI with ARM NEON optimization and optional Vulkan GPU acceleration
  - Default model: small.en-q8_0 (252 MiB, English, 8-bit quantized)
  - Companion native VAD model: silero-v6.2.0 (~885 KB, GGML format)
  - 16 kHz mono PCM audio input (Whisper's native sample rate — no resampling)
  - Greedy decoding with optional initial prompt for vocabulary/style biasing
  - Adaptive thread count: 2–4 threads auto-tuned for big.LITTLE SoCs (cores-based heuristic: ≥6 cores → 4 threads, else cores−1 clamped 2–4), with audio-duration-aware reduction for short clips (≤2s → 2 threads) to minimize scheduling overhead
  - JNI single-pass JSON output with segment timings, no_speech_prob, and avg_logprob per segment
  - Fallback from Vulkan GPU to CPU-only if GPU initialization fails
  - Hallucination suppression via no_speech_prob threshold (default 0.6), logprob threshold (default −1.0), and entropy threshold (default 2.4)

- **Voice Activity Detection (VAD)** (Priority: P0 — Critical)
  - Silero VAD via ONNX Runtime with 480-sample windows (30 ms at 16 kHz) and 64-sample context prepended
  - Configurable speech threshold (default: 0.5), minimum speech duration (250 ms), and minimum silence duration (100 ms)
  - Real-time speech probability streaming to UI for visual feedback
  - Thread-safe inference with ReentrantLock
  - Pre-allocated ONNX tensors reused across inference calls for zero per-frame allocation
  - Primitive float arrays for audio buffers instead of boxed collections
  - Native VAD mode (whisper.cpp with GGML Silero model silero-v6.2.0) runs inside nativeTranscribe with independent tuning (threshold 0.5, min speech 250 ms, min silence 500 ms, speech pad 300 ms)

- **Batch transcription** (Priority: P0 — Critical)
  - Full audio buffer transcribed after recording stops
  - Silence trimming on recorded audio to reduce inference time
  - Minimum audio duration enforcement (1.25 seconds) for reliable Whisper output
  - Short audio padding with zero-filled buffer to meet minimum duration
  - No-speech density short-circuit: skips Whisper inference when VAD speech ratio falls below 5%, avoiding hallucinated output on near-silent recordings

- **Audio recording** (Priority: P0 — Critical)
  - 16 kHz mono PCM float recording via Android AudioRecord
  - Real-time amplitude (dB) streaming for UI visualization
  - Foreground service (RecordingService) for background microphone access on Android 10+
  - Configurable maximum recording duration (default: 600 seconds / 10 minutes)
  - Ring buffer architecture for memory-efficient long recordings

- **Three-phase text post-processing pipeline** (Priority: P0 — Critical)
  - Phase 1 — Voice Command Detection: full-utterance matching against command vocabulary; commands bypass text pipeline entirely
  - Phase 2 — Content Normalization: strip invisible Unicode, convert spoken emoji to characters (60+ mappings), convert spoken punctuation to symbols, remove filler words, collapse stutters, resolve self-repair disfluencies, number-word-to-digit conversion (ITN)
  - Phase 3 — Text Formatting: collapse multi-space runs, trim, sentence-case capitalization, pronoun "I" fix, trailing punctuation enforcement

- **Confusion-set corrector** (Priority: P1 — High)
  - Levenshtein-distance-based correction for common homophones and ASR confusion pairs
  - Applied as a post-decode refinement step between Whisper output and the three-phase pipeline
  - Context-aware: uses surrounding text and confusion pair frequency to select the best correction

- **Auto-stop on silence** (Priority: P1 — High)
  - Automatically stops recording after sustained silence detected by VAD
  - Configurable silence timeout (default: 2000 ms, range 0–10000 ms; 0 = disabled)
  - Integrates with TranscriptionCoordinator recording loop — monitors accumulated silence duration against threshold
  - Transcription pipeline runs normally on captured audio after auto-stop

- **Thermal monitoring** (Priority: P1 — High)
  - ThermalMonitor observes device thermal status via PowerManager
  - Exposes thermalStatus as StateFlow; isThrottled returns true at THERMAL_STATUS_MODERATE or above
  - STT pipeline disables GPU acceleration and may reduce thread count when device is thermally throttled
  - Started/stopped with FloatingOverlayService lifecycle

- **Backend switch hysteresis** (Priority: P1 — High)
  - Tracks real-time factor (RTF) across consecutive transcriptions
  - Only switches from GPU to CPU (or vice versa) after 3 consecutive slow RTFs exceed the threshold
  - Cooldown timer prevents rapid backend oscillation
  - Integrates with ThermalMonitor: respects thermal throttle regardless of RTF

- **Voice command system** (Priority: P1 — High)
  - Full-utterance command matching (rejects partial matches inside normal dictation)
  - Deletion: "delete that", "delete last word", "delete last sentence", "backspace"
  - Undo/Redo: "undo", "undo that", "redo", "redo that"
  - Selection: "select all", "select last word"
  - Clipboard: "copy", "copy that", "cut", "cut that", "paste", "paste that"
  - Navigation: "new line", "new paragraph"
  - Formatting: "capitalize that", "uppercase that", "lowercase that"
  - Punctuation shortcuts: "space", "tab", "period", "comma", "question mark", "exclamation point", "exclamation mark"
  - Session control: "send", "send that", "clear", "clear all", "go back", "stop listening", "stop"
  - Trailing Whisper-appended punctuation stripped before command matching

- **Floating overlay mic button** (Priority: P0 — Critical)
  - System-level overlay (SYSTEM_ALERT_WINDOW) rendered via ComposeView in FloatingOverlayService
  - Draggable positioning across screen
  - Visual state feedback: blue (idle), red pulsing (recording), grey spinner (transcribing)
  - Tap to toggle recording start/stop
  - Foreground service with persistent notification
  - Context-aware visibility: integrates with AccessibilityService to detect text field focus

- **Accessibility-based text insertion** (Priority: P0 — Critical)
  - SafeWordAccessibilityService finds focused editable text fields via AccessibilityNodeInfo
  - Primary insertion via ACTION_SET_TEXT on focused node
  - Fallback to clipboard paste if direct insertion fails
  - Notifies FloatingOverlayService of text field focus/unfocus events
  - Monitors TYPE_VIEW_FOCUSED, TYPE_VIEW_TEXT_CHANGED, TYPE_WINDOW_STATE_CHANGED events

- **Model management** (Priority: P0 — Critical)
  - Automatic download of default Whisper model and native GGML VAD model on first launch via WorkManager
  - Download from Hugging Face with TLS certificate pinning (intermediate + root CA)
  - Retry logic with exponential backoff (3 retries, 1-second initial backoff)
  - Download progress tracking via StateFlow
  - SHA-256 integrity verification after download
  - Model file size validation (within 10% of expected)
  - Support for Whisper and GGML VAD model files (.bin)

- **Transcription history** (Priority: P1 — High)
  - Room database (SQLite) with TranscriptionEntity: text, audio duration, inference time, language, timestamp
  - History ordered by creation date (newest first)
  - Recent transcriptions query (limit 50)
  - Individual and bulk delete operations
  - Automatic save after each transcription (configurable via settings)

- **Guided onboarding flow** (Priority: P1 — High)
  - Step 1: Grant RECORD_AUDIO permission
  - Step 2: Grant POST_NOTIFICATIONS permission (required on Android 13+)
  - Step 3: Grant SYSTEM_ALERT_WINDOW (overlay) permission
  - Step 4: Enable SafeWordAccessibilityService
  - Step 5: Download speech models (default Whisper + native VAD auto-download; wait for completion)
  - Sequential steps — each must complete before the next is shown
  - Onboarding completion persisted via DataStore; skipped on subsequent launches

- **Settings** (Priority: P1 — High)
  - Persisted via Jetpack DataStore Preferences with reactive Flow observation
  - Overlay section: enable/disable floating mic button
  - Appearance section: dark mode picker (system/light/dark)
  - Speech Models section: browse available Whisper models, download/delete/select
  - About section: version, whisper backend status, attribution
  - Additional settings stored but not yet surfaced in UI: overlay size/opacity, language, auto-detect, translate-to-English, auto-copy, auto-insert, save-to-history, VAD threshold/durations, thread count, initial prompt

- **Model preloading** (Priority: P1 — High)
  - VAD and Whisper models loaded in background before first mic press
  - Triggered from FloatingOverlayService.onCreate()
  - Idempotent — no-ops if already loaded or preload in progress
  - Eliminates 2-second model-loading delay on first transcription

- **App UI (Jetpack Compose + Material 3)** (Priority: P1 — High)
  - Three-phase splash screen: noise.gif (1.2s static), framed glitch video (safeword_start.mp4), neon flicker logo reveal (g.png, 2s)
  - Frosted-glass UI components (GlassCard, GlassSurface, GlassListItem, GlassDivider)
  - Navigation: Splash → Onboarding → Settings (NavHost with Compose Navigation)
  - Hilt dependency injection throughout (HiltAndroidApp, @HiltViewModel, @AndroidEntryPoint)

## 5. User experience

### 5.1 Entry points and first-time user flow

- User installs Safe Word from the Play Store and launches the app
- Three-phase splash screen plays: noise static → framed glitch video → neon logo reveal
- Onboarding flow guides through 5 sequential permission/setup steps
- After onboarding, the app navigates to the Settings screen (the main app UI)
- The floating overlay mic button appears over other apps when overlay is enabled
- User taps the mic button in any app to start/stop dictation

### 5.2 Core experience

- **Record**: User taps the floating mic button. The button turns red and pulses. Audio recording begins via the foreground service. Real-time amplitude and VAD speech probability are visualized.

- **Transcribe**: When the user taps the mic button again to stop, the full audio is transcribed in batch. The post-processing pipeline cleans and formats the text. The final text is inserted into the focused text field via AccessibilityService (or copied to clipboard as fallback). The transcription is saved to history.

- **Command**: If the spoken utterance matches a voice command (e.g., "delete last word", "undo", "paste"), the command is executed directly instead of inserting text. The user gets immediate hands-free editing capability.

### 5.3 Advanced features and edge cases

- Short recordings under 1.25 seconds are zero-padded to meet Whisper's minimum input requirement
- Silence is trimmed from the beginning and end of recordings to reduce inference time
- If no Whisper model is downloaded, recording starts but transitions to an error state with a clear message
- If the AccessibilityService is not enabled, text falls back to clipboard copy
- If Vulkan GPU initialization fails, the engine automatically retries with CPU-only inference
- Full audio buffer is transcribed in batch when recording stops, regardless of speech patterns
- Spoken emoji names are converted to Unicode emoji characters (60+ mappings including smileys, gestures, hearts, objects, animals, nature, symbols)
- Self-repair disfluencies are resolved (e.g., "I went to the, I mean, I drove to the store" → "I drove to the store")
- Filler words are removed (um, uh, like, you know, basically, literally, actually, honestly, right, so, well)

### 5.4 UI/UX highlights

- Floating mic button with state-driven visual feedback (color + pulse animation)
- Frosted-glass design language across all UI surfaces
- Settings organized into clear sections: Overlay, Appearance, Speech Models, About
- Model download progress shown with real-time progress bar
- Onboarding uses step badges and animated transitions between steps
- Dark mode support (system, light, dark) with dynamic color palette on Android 12+

## 6. Narrative

Alex, a privacy-conscious attorney, needs to dictate a confidential case brief while walking between meetings. She opens her email app and taps the small blue floating mic button in the corner of her screen. The button turns red and begins pulsing — she's recording. She speaks naturally: "Dear counsel comma this letter confirms our client's position regarding the settlement terms period new paragraph." The VAD tracks her speech probability in real time. When she taps the mic button to stop, the text appears instantly in her email draft — properly capitalized, with spoken punctuation converted to symbols, and "new paragraph" executed as an actual paragraph break. She realizes she said "settlement terms" but meant "mediation terms," so she says "delete last word" — and the word disappears. She corrects herself, taps the mic button to stop, and her complete, properly formatted text is ready to send. No audio ever left her phone. No cloud server processed her confidential client communications. Safe Word just works — privately, accurately, and hands-free.

## 7. Success metrics

### 7.1 User-centric metrics

- Transcription word error rate (WER) under 8% for English dictation (measured against manual ground truth)
- Voice command recognition accuracy above 95% for supported commands
- Time from recording stop to final transcribed text under 3 seconds for a 10-second utterance
- Post-processing pipeline latency under 50 ms for typical utterances
- Successful text insertion rate above 90% via AccessibilityService (remainder via clipboard fallback)

### 7.2 Business metrics

- Onboarding completion rate (all 5 steps) above 80%
- Daily active users who complete at least one transcription
- Average transcriptions per active user per day
- Model download success rate above 95% on first attempt
- App store rating above 4.5 stars

### 7.3 Technical metrics

- Whisper inference speed: real-time factor under 0.5x (inference time < 50% of audio duration) on reference device (Snapdragon 8 Gen 2)
- Memory usage under 500 MB during active transcription (model + audio + inference buffers)
- App cold start to overlay-ready under 3 seconds
- VAD processing latency under 5 ms per 32 ms window
- Model preload completes within 2 seconds of FloatingOverlayService creation
- Zero native crashes from whisper.cpp JNI bridge in production
- Battery drain under 5% per hour during active continuous recording and transcription

## 8. Technical considerations

### 8.1 Integration points

- whisper.cpp (C/C++) via JNI bridge (whisper-jni.cpp) — speech-to-text inference
- Silero VAD (ONNX Runtime) via ONNX Runtime Android — Kotlin-side voice activity detection
- GGML Silero VAD model (silero-v6.2.0) loaded by whisper.cpp for native VAD mode
- Hugging Face model repository — model downloads with TLS certificate pinning
- Android AccessibilityService API — text field detection and text insertion
- Android SYSTEM_ALERT_WINDOW — floating overlay rendering
- Android AudioRecord API — microphone capture at 16 kHz PCM float
- Android Foreground Service API — background recording and overlay lifecycle
- Jetpack WorkManager — background model download scheduling
- Jetpack Room — SQLite transcription history
- Jetpack DataStore Preferences — settings persistence
- Jetpack Compose with Material 3 — UI framework
- Hilt (Dagger) with KSP — dependency injection
- ExoPlayer (Media3) — splash screen video playback
- OkHttp — HTTP client for model downloads
- Timber — structured logging

### 8.2 Data storage and privacy

- All AI inference runs entirely on-device — no audio or text is transmitted to any server
- Model files stored in app-private internal storage (context.filesDir/models/)
- Transcription history stored in Room database (app-private SQLite)
- User settings stored in Jetpack DataStore (app-private shared preferences)
- No analytics, telemetry, or crash reporting SDKs — complete data isolation
- No user accounts, authentication, or cloud sync
- Model downloads authenticated only via TLS certificate pinning to Hugging Face (no API keys)
- android:allowBackup="false" prevents unintended data backup to Google Drive
- Audio samples are never written to persistent storage — held only in volatile memory during recording

### 8.3 Scalability and performance

- ARM NEON SIMD optimization for quantized matrix multiplication (15–25% speedup over scalar on Cortex-A55+)
- armv8.2-a+dotprod compilation flags for modern ARM CPUs
- Thin LTO enabled (-flto=thin) for 5–10% native library size reduction
- OpenMP parallelism for ARM (disabled for x86_64 due to NDK issues)
- Adaptive thread tuning: audio-duration-aware heuristic scales from 2 threads (short clips ≤2s) to 4 threads (devices with ≥6 cores), targeting big cores only
- Flash attention enabled for Vulkan GPU path (20–40% speedup)
- Vulkan GPU fallback to CPU ensures functionality on all devices
- Backend switch hysteresis: requires 3 consecutive slow RTFs before switching compute backend, with cooldown timer to prevent oscillation
- Thermal-aware inference: ThermalMonitor disables GPU and may reduce threads at THERMAL_STATUS_MODERATE or above
- No-speech density short-circuit: skips Whisper inference entirely when VAD detects <5% speech ratio, avoiding hallucinated output
- Silence trimming reduces audio sent to Whisper, directly reducing inference time
- Model preloading eliminates cold-start latency
- Silence trimming and batch transcription keep inference time proportional to speech duration
- Reusable padding buffer avoids ~78 KB allocation per transcription
- Pre-allocated ONNX tensors reused across VAD inference calls (zero per-frame allocation)
- Primitive float arrays for VAD audio buffers instead of boxed collections
- JNI single-pass JSON output: segment timings, no_speech_prob, avg_logprob emitted in one pass (no separate C-string allocations)
- Ring buffer for audio recording avoids large contiguous allocations
- Auto-stop on silence prevents unnecessarily long recordings from wasting inference time

### 8.4 Potential challenges

- **Accessibility Service approval**: Google Play requires justification for AccessibilityService usage; the app must demonstrate it is an assistive technology tool, not misusing the API for non-accessibility purposes
- **SYSTEM_ALERT_WINDOW restrictions**: Some OEMs (Xiaomi, OPPO, Vivo) add additional overlay permission gates beyond the standard Android permission; users may need to navigate manufacturer-specific settings
- **Model download size**: ~253 MB initial model pack (252 MiB Whisper + ~885 KB native VAD) may deter users on limited data plans or storage; model downloads automatically during onboarding
- **Battery consumption**: Continuous audio recording + VAD is power-intensive; need careful power profiling and optimization
- **Thread safety**: whisper_context is NOT thread-safe for concurrent calls; TranscriptionCoordinator serializes access via Kotlin-side Mutex, but this must be carefully maintained
- **JNI memory management**: whisper.cpp uses C-style malloc/free (not RAII); if a JNI exception is thrown mid-transcription, native heap may leak
- **Device fragmentation**: ARM NEON and Vulkan support varies across devices; the app requires arm64-v8a only (minSdk 33, API level guarantees)
- **Thermal false-positive throttling**: ThermalMonitor triggers at THERMAL_STATUS_MODERATE which may fire prematurely on some SoCs during sustained dictation in hot environments; currently no per-device thermal calibration
- **Auto-stop false triggers**: Auto-stop on silence (default 2 000 ms) may fire during natural pauses in slow dictation; threshold must balance responsiveness versus premature cutoff

## 9. Milestones and sequencing

### 9.1 Project estimate

- Medium: The core voice transcription pipeline is fully implemented and optimized. The app is at version 0.3.0.

### 9.2 Team size and composition

- Solo developer: Android/Kotlin engineer with native C/C++ JNI experience
- Roles covered: product, design, Android development, native development, testing

### 9.3 Suggested phases

- **Phase 1 (Complete)**: Core transcription pipeline
  - Audio recording with foreground service
  - Whisper.cpp JNI integration with CPU inference
  - Silero VAD integration
  - Batch transcription with silence trimming
  - Three-phase text post-processing (voice commands, content normalization, formatting)
  - Floating overlay mic button with state-driven UI
  - Accessibility-based text insertion
  - Guided onboarding flow
  - Settings with DataStore persistence
  - Room-backed transcription history
  - Model download with certificate pinning and integrity verification
  - Unit tests for post-processing pipeline, voice commands, and ViewModels

- **Phase 2 (Current — 0.x releases)**: Polish and hardening
  - Vulkan GPU acceleration (implemented with CPU fallback)
  - Voice command execution fully wired via Accessibility Service (21 action types)
  - Automatic default model pack download during onboarding (Whisper + native VAD)
  - Confusion-set corrector for common homophones (Levenshtein distance)
  - Thermal monitoring with GPU throttle at THERMAL_STATUS_MODERATE
  - Backend switch hysteresis (3 consecutive slow RTFs before switching)
  - Auto-stop recording on sustained silence (default 2000 ms, configurable)
  - Adaptive thread tuning: audio-duration-aware heuristic (2–4 threads)
  - No-speech density short-circuit (skip inference at <5% speech ratio)
  - Pre-allocated ONNX tensors and primitive VAD buffers (zero per-frame allocation)
  - JNI single-pass JSON output (segment timings + metrics in one pass)
  - Hallucination suppression (no_speech_prob, logprob, entropy thresholds)
  - Native VAD mode (whisper.cpp + GGML Silero model silero-v6.2.0)
  - Default Whisper model switched to small.en-q8_0 (8-bit quantized) for better latency/memory characteristics
  - Speech evaluation harness for WER measurement
  - Expanded test coverage (audio pipeline, coordinator, integration tests)
  - Play Store submission and Accessibility Service review

- **Phase 3 (Planned — 1.0)**: Advanced voice features
  - Expanded language packs for multilingual Whisper models
  - User style profiling and adaptive text formatting
  - Personalization engine for adaptive correction and vocabulary
  - Tablet and foldable optimization

## 10. User stories

### 10.1 First-launch onboarding

- **ID**: GH-001
- **Description**: As a new user, I want a guided setup flow that walks me through granting necessary permissions and downloading the speech model, so I can start using Safe Word quickly without confusion.
- **Acceptance criteria**:
  - Onboarding presents 5 sequential steps: microphone permission, notification permission, overlay permission, accessibility service, model download
  - Each step must be completed before the next step is revealed
  - Microphone permission step shows an "Allow" button that triggers the system permission dialog
  - Notification permission step shows a "Grant" button that triggers the POST_NOTIFICATIONS system dialog
  - Overlay permission step navigates to the system overlay settings page
  - Accessibility service step navigates to the system accessibility settings page
  - Model download step auto-starts the default model pack (Whisper + native VAD) and shows a progress bar with percentage
  - A "Continue" button appears only after all 5 steps are complete
  - Onboarding is skipped on subsequent app launches after completion
  - If the user force-closes mid-onboarding and returns, the flow resumes from the last incomplete step

### 10.2 Start and stop recording via overlay

- **ID**: GH-002
- **Description**: As a user, I want to tap a floating mic button to start and stop voice recording in any app, so I can dictate text without leaving my current context.
- **Acceptance criteria**:
  - A floating circular mic button is displayed over all apps when the overlay is enabled
  - Tapping the button while idle starts recording; the button changes from blue to red and pulses
  - Tapping the button while recording stops recording and begins transcription; the button shows a spinning indicator
  - After transcription completes, the button returns to blue idle state
  - The button is draggable to any position on the screen
  - The button remains visible across app switches and screen rotations
  - A persistent notification is shown while the overlay service is active

### 10.3 Batch transcription after recording

- **ID**: GH-003
- **Description**: As a user, I want my complete spoken audio transcribed quickly after I stop recording, so I get accurate, properly formatted text without waiting.
- **Acceptance criteria**:
  - Full audio is transcribed in a single batch pass after recording stops
  - Silence is trimmed from both ends to minimize inference time
  - Transcription latency is under 3 seconds for a 10-second utterance on supported devices
  - The transcribing state (spinner) is shown while inference runs
  - If the user stops recording mid-utterance, all captured audio is transcribed

### 10.4 Automatic text insertion into focused field

- **ID**: GH-004
- **Description**: As a user, I want my transcribed text to appear automatically in whatever text field I'm typing in, so I don't have to manually copy and paste.
- **Acceptance criteria**:
  - Transcribed text is inserted at the cursor position in the currently focused editable text field
  - Insertion works across all apps (email, messaging, notes, browsers, etc.)
  - If direct insertion via AccessibilityService fails, the text is copied to the clipboard and the user is notified
  - The text is properly formatted (sentence case, punctuation) before insertion
  - Consecutive dictations append to the existing text, not replace it

### 10.5 Voice command execution

- **ID**: GH-005
- **Description**: As a user, I want to speak editing commands like "delete last word" or "undo" and have them execute immediately, so I can edit text hands-free.
- **Acceptance criteria**:
  - Speaking "delete that" removes the current text selection
  - Speaking "delete last word" removes the most recent word
  - Speaking "undo" reverses the last text change
  - Speaking "redo" re-applies the last undone change
  - Speaking "select all" highlights all text in the focused field
  - Speaking "copy" / "cut" / "paste" performs the corresponding clipboard operation
  - Speaking "new line" inserts a line break; "new paragraph" inserts a paragraph break
  - Speaking "capitalize that" / "uppercase that" / "lowercase that" transforms selected text
  - Commands are only recognized as full utterances — partial matches within dictated sentences are ignored
  - A command utterance is not inserted as text (it is executed, not transcribed)
  - Trailing punctuation appended by Whisper (e.g., "delete that.") does not prevent command recognition

### 10.6 Spoken punctuation and emoji insertion

- **ID**: GH-006
- **Description**: As a user, I want to say "period", "comma", "question mark", or emoji names like "thumbs up" and "heart" and have them inserted as the corresponding symbols, so I can produce formatted and expressive text by voice alone.
- **Acceptance criteria**:
  - Spoken words "period", "comma", "question mark", "exclamation point", "colon", "semicolon", "dash", "hyphen" are converted to their corresponding punctuation symbols
  - Spoken emoji names ("thumbs up" → 👍, "heart" → ❤️, "smiley face" → 😊, "fire" → 🔥, etc.) are converted to Unicode emoji
  - At least 60 emoji mappings are supported across categories: smileys, gestures, hearts, objects, animals, nature, symbols
  - Conversion happens within the content normalization phase and works seamlessly with surrounding text
  - Unknown emoji names are left as plain text (not corrupted or partially converted)

### 10.7 Filler word and disfluency removal

- **ID**: GH-007
- **Description**: As a user, I want filler words and verbal disfluencies automatically removed from my transcription, so the output reads as clean written text.
- **Acceptance criteria**:
  - Common filler words are removed: "um", "uh", "like" (when used as filler), "you know", "basically", "literally", "actually", "honestly", "right", "so", "well" (when used as discourse markers)
  - Stutters are collapsed (e.g., "I I I went" → "I went")
  - Self-repair disfluencies are resolved (e.g., "I went to the, I mean, I drove to" → "I drove to")
  - Removal does not corrupt surrounding text or create double spaces
  - Filler words used meaningfully in context are not removed (e.g., "I actually like it" keeps "actually" if it's semantic)

### 10.8 Automatic text formatting

- **ID**: GH-008
- **Description**: As a user, I want my transcription to be automatically formatted with proper capitalization and punctuation, so I don't have to manually edit raw speech output.
- **Acceptance criteria**:
  - The first letter of each sentence is capitalized
  - The pronoun "I" is always capitalized
  - A trailing period is added if the transcription does not end with terminal punctuation (., !, ?, …)
  - Multiple consecutive spaces are collapsed to a single space
  - Leading and trailing whitespace is trimmed
  - Invisible Unicode characters (ZWSP, ZWNJ, ZWJ, BOM, soft-hyphen) are stripped

### 10.9 Auto-copy to clipboard

- **ID**: GH-009
- **Description**: As a user, I want my transcribed text to be automatically copied to the clipboard after transcription, so I can paste it anywhere even if direct insertion fails.
- **Acceptance criteria**:
  - When auto-copy is enabled in settings, the final formatted text is placed on the system clipboard after each transcription
  - The clipboard contains only the latest transcription (not appended to previous clipboard content)
  - Auto-copy is enabled by default and can be disabled in settings
  - A toast or visual confirmation indicates the text was copied

### 10.10 Transcription history

- **ID**: GH-010
- **Description**: As a user, I want my past transcriptions saved automatically so I can review or reuse them later.
- **Acceptance criteria**:
  - Each transcription is saved to the local database with: text, audio duration, inference time, language, and timestamp
  - History is displayed in reverse chronological order (newest first)
  - Up to 50 recent transcriptions are queryable
  - Individual transcriptions can be deleted
  - All history can be cleared at once
  - Saving to history is enabled by default and can be disabled in settings

### 10.11 Model download and management

- **ID**: GH-011
- **Description**: As a user, I want the speech model to download automatically during onboarding and be managed transparently, so I don't have to deal with manual file management.
- **Acceptance criteria**:
  - The default STT model (small.en-q8_0, 252 MiB) downloads automatically during onboarding step 5
  - The native GGML VAD model (silero-v6.2.0, ~885 KB) downloads automatically alongside the STT model
  - Download progress is shown as a percentage with a progress bar
  - Downloads retry up to 3 times with exponential backoff on failure
  - Download integrity is verified via SHA-256 checksum
  - Downloaded models are stored in app-private internal storage
  - Model download state is persisted — if the app is force-stopped mid-download, it resumes or retries on next launch
  - WorkManager ensures the download completes even if the app is backgrounded
  - The Speech Models settings section shows download status for both model artifacts

### 10.12 Settings configuration

- **ID**: GH-012
- **Description**: As a user, I want to customize transcription behavior, overlay appearance, and output preferences through a settings screen, so the app works the way I prefer.
- **Acceptance criteria**:
  - Settings UI is organized into 4 sections: Overlay, Appearance, Speech Models, About
  - Overlay section: enable/disable toggle for the floating overlay service
  - Appearance section: dark mode picker (system/light/dark)
  - Speech Models section: model cards showing downloaded STT models with size, status, and delete option
  - About section: app version, whisper.cpp backend version, "Made in Jurgistan" attribution
  - Additional settings (overlay size/opacity, VAD threshold/durations, language, auto-copy, thread count) are stored in DataStore but not yet surfaced in the settings UI
  - All settings persist across app restarts via DataStore
  - Settings changes take effect immediately (reactive Flow-based observation)

### 10.13 Background recording

- **ID**: GH-014
- **Description**: As a user, I want recording to continue even when I switch to a different app, so I can dictate while referencing other content.
- **Acceptance criteria**:
  - Recording continues uninterrupted when the user navigates away from Safe Word
  - A foreground service notification indicates that recording is active
  - The floating overlay mic button remains visible and responsive during background recording
  - Tapping the overlay button while in another app stops recording and inserts text into the current app's focused field
  - Background recording automatically stops at the configured maximum duration

### 10.14 Model preloading for instant start

- **ID**: GH-015
- **Description**: As a user, I want the speech engine to be ready instantly when I tap the mic button, with no loading delay.
- **Acceptance criteria**:
  - VAD and Whisper models are loaded in the background when the overlay service starts
  - The first mic button tap begins recording immediately without a model-loading delay
  - If preloading is still in progress when the user taps the mic, recording starts and the model finishes loading before transcription begins
  - Preloading is idempotent — multiple triggers do not cause redundant loads

### 10.15 Silence trimming for faster transcription

- **ID**: GH-016
- **Description**: As a user, I want silence at the beginning and end of my recording automatically removed before transcription, so inference is faster and results are not padded with empty output.
- **Acceptance criteria**:
  - Leading and trailing silence is detected using an RMS threshold analysis with 100 ms windows
  - A configurable padding of silence (default: 200 ms) is preserved on each side to avoid clipping speech edges
  - Trimming reduces the audio duration sent to Whisper, proportionally reducing inference time
  - Very short recordings (less than 2 windows) are not trimmed to avoid data loss
  - Trimming is logged when significant (more than 10% of audio removed)

### 10.16 Error state handling and recovery

- **ID**: GH-017
- **Description**: As a user, I want clear error messages and graceful recovery when something goes wrong, so I know what happened and can try again.
- **Acceptance criteria**:
  - If no model is downloaded, the error state displays "No model downloaded" with guidance to visit settings
  - If recording fails (permission revoked, hardware error), the error state displays a descriptive message
  - If transcription fails (model error, out of memory), the error state preserves the previous state context
  - The user can always tap the mic button to reset from an error state back to idle
  - GPU initialization failure is handled silently with automatic CPU fallback (no user-visible error)
  - Network errors during model download show retry option with descriptive message

### 10.17 Maximum recording duration enforcement

- **ID**: GH-018
- **Description**: As a user, I want recording to automatically stop after a configurable maximum duration, so the app doesn't record indefinitely and consume excessive resources.
- **Acceptance criteria**:
  - Recording automatically stops when the configured maximum duration is reached (default: 600 seconds)
  - The maximum duration is configurable in settings
  - When auto-stopped, the transcription pipeline runs normally on the captured audio
  - The recording duration is displayed in real time during recording via the state flow
  - Duration polling occurs every 200 ms for responsive UI updates

### 10.18 Auto-stop recording on sustained silence

- **ID**: GH-019
- **Description**: As a user, I want recording to automatically stop when I have been silent for a configurable period, so I don't have to manually tap the mic button after finishing my thought.
- **Acceptance criteria**:
  - Recording stops automatically when no speech is detected for the configured silence duration (default: 2 000 ms)
  - The silence threshold is configurable in settings (autoStopSilenceMs)
  - Auto-stop triggers the same transcription pipeline as a manual stop
  - VAD must be active for silence detection to function; if VAD is disabled, auto-stop is also disabled
  - Auto-stop does not fire during the first second of recording (grace period for slow starters)
  - The overlay button transitions from recording (red) to transcribing (spinner) when auto-stop fires

### 10.19 Thermal-aware inference

- **ID**: GH-020
- **Description**: As a user, I want inference to automatically back off from GPU to CPU when my device is running hot, so the app doesn't overheat my phone or get killed by the system.
- **Acceptance criteria**:
  - ThermalMonitor observes `PowerManager.OnThermalStatusChangedListener` for real-time thermal state
  - At THERMAL_STATUS_MODERATE or above, Vulkan GPU inference is disabled; CPU-only mode is used
  - When thermal status drops below MODERATE, GPU inference is re-enabled on the next transcription
  - Thermal throttling is logged with prefix `[THERMAL]`
  - The user does not need to take any action — throttling is transparent

### 10.20 Backend switch hysteresis

- **ID**: GH-021
- **Description**: As a user, I want the app to avoid flipping between GPU and CPU inference on borderline performance, so the transcription experience is stable.
- **Acceptance criteria**:
  - The backend only switches from GPU to CPU after 3 consecutive transcriptions exceed the RTF threshold
  - A cooldown timer prevents immediate switch-back once a backend change occurs
  - Individual slow transcriptions (network hiccup, one-off GC pause) do not trigger a switch
  - Backend switches are logged with prefix `[BRANCH]`

### 10.21 Confusion-set homophone correction

- **ID**: GH-022
- **Description**: As a user, I want common Whisper misrecognitions (homophones, phonetic near-misses) automatically fixed, so I get correct text without manual editing.
- **Acceptance criteria**:
  - A confusion-set table maps frequent Whisper misrecognitions to their intended forms (e.g., "their" ↔ "there" ↔ "they're")
  - Correction is applied after content normalization but before final text formatting
  - Corrections use Levenshtein distance for fuzzy matching within the set
  - Only high-confidence substitutions are applied; ambiguous cases are left as-is
  - The confusion set is extensible (simple map structure, no model dependency)

### 10.22 No-speech density short-circuit

- **ID**: GH-023
- **Description**: As a user, I want audio chunks that are almost entirely silence to be skipped without running full Whisper inference, so transcription is faster and I don't see hallucinated output from quiet audio.
- **Acceptance criteria**:
  - After VAD processing, the speech-to-total-audio ratio is computed
  - If the ratio is below the NO_SPEECH_DENSITY_THRESHOLD (5%), inference is skipped entirely for that chunk
  - Skipped chunks return an empty transcription result (not an error)
  - The short-circuit is logged with prefix `[BRANCH]` including the density ratio
  - This optimization only applies to batch transcription, not streaming mode
