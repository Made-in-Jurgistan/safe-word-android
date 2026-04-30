# Safe Word Android — Architecture Guide

> **Last updated**: June 2025 — post-audit cleanup  
> **Package root**: `com.safeword.android`

---

## High-Level Overview

Safe Word is an on-device voice dictation app for Android. It captures audio,
streams it through the Moonshine SDK for real-time speech-to-text, applies a
multi-phase text processing pipeline, and inserts the final text into any
focused text field via the Accessibility Service.

```text
┌─────────────────────────────────────────────────────┐
│                    FloatingOverlayService            │
│   (foreground service, overlay window, ComposeView)  │
│                                                     │
│  OverlayViewModel  ◄── AccessibilityBridge          │
│        │                    │                       │
│        ▼                    ▼                       │
│  TranscriptionCoordinator                           │
│    ├── AudioRecorder        (16 kHz PCM capture)    │
│    ├── MoonshineStreamingEngine (on-device STT)     │
│    ├── DefaultTextProcessor (post-processing)       │
│    ├── VoiceCommandDetector (command recognition)   │
│    └── SettingsRepository   (DataStore prefs)       │
│                                                     │
│  Room DB  ◄── TranscriptionDao (history storage)    │
└─────────────────────────────────────────────────────┘
```

---

## State Machine

`TranscriptionCoordinator` drives a sealed-interface state machine observed
by the UI via `StateFlow<TranscriptionState>`:

```text
Idle ──► Recording ──► Streaming ──► Transcribing ──► Done ──► Idle
              │              │              │
              └──────────────┴──────────────┴──► Error ──► Idle
                                   │
                                   └──► CommandDetected ──► Idle
```

| State | Description |
|---|---|
| **Idle** | Ready. No active operation. |
| **Recording** | AudioRecorder capturing PCM samples. |
| **Streaming** | Moonshine SDK emitting live partial lines. |
| **Transcribing** | Finalizing: post-processing, voice-command check, output. |
| **Done** | Result available (text, audio duration, etc.). |
| **CommandDetected** | Voice command recognized — dispatched via AccessibilityBridge. |
| **Error** | Failure at any stage; auto-resets to Idle. |

---

## Text Processing Pipeline

`DefaultTextProcessor.process()` orchestrates these phases in order:

1. **Confusion-set correction** — `ConfusionSetCorrector` fixes common ASR
   homophones (e.g., "their" ↔ "there") using context and confidence gating.
2. **Content normalization** — `ContentNormalizer` handles filler removal,
   self-repair, spoken punctuation/emoji, number/date expansion.
3. **Punctuation prediction** — `PunctuationPredictor` inserts commas and
   periods based on pause heuristics and clause boundaries.
4. **Formatting** — `TextFormatter` applies capitalization, spacing, and
   sentence structure rules.
5. **Personalized dictionary** — `PersonalizedDictionaryCorrector` applies
   user-defined word substitutions from the local Room database.

---

## Voice Command Detection

`VoiceCommandDetector.detect()` runs on each completed streaming line and
(if no command was triggered) on the final combined text. Detection strategies
in priority order:

1. **Exact match** against the canonical command map
2. **Custom commands** from user-defined JSON
3. **Semantic intent** via `IntentRecognizer` keyword scoring
4. **Fuzzy match** using Levenshtein distance

Commands are gated by `FieldType` (password fields suppress most commands).

---

## Dependency Injection (Hilt)

| Module | Provides |
|---|---|
| `AppModule` | Room database, DAOs, repositories |
| `TextProcessingModule` | `DefaultTextProcessor` binding |
| `AudioModule` | `AudioRecorder` singleton |
| `AccessibilityModule` | `AccessibilityBridge` → `DefaultAccessibilityBridge` |
| `CoroutineScopesModule` | `@ApplicationScope` CoroutineScope |

---

## Services

| Service | Purpose |
|---|---|
| `FloatingOverlayService` | Foreground service hosting the overlay mic button and draft text ComposeView. Manages `SYSTEM_ALERT_WINDOW` and microphone foreground type. |
| `SafeWordAccessibilityService` | Accessibility service for text insertion, voice action dispatch, keyboard/field state observation. Wrapped by `AccessibilityBridge`. |

---

## Data Layer

- **Room** (`SafeWordDatabase`) — stores transcription history (`TranscriptionEntity`)
  and personalized dictionary entries (`PersonalizedEntry`).
- **DataStore** (`SettingsRepository`) — persists user preferences via
  Jetpack DataStore Preferences (auto-insert, auto-copy, dark mode, etc.).
- **ModelRepository** — manages Moonshine model download, caching, and
  version resolution.

---

## Key Design Decisions

- **Single glass UI theme** — the app uses one dark-glass `ColorScheme`
  (`AppGlassColorScheme`); palette switching was removed as dead code.
- **AccessibilityBridge** — introduced to decouple the coordinator and overlay
  from static `SafeWordAccessibilityService` calls, enabling unit testing.
- **Streaming-first architecture** — text is processed incrementally per
  Moonshine completed line, then finalized as a batch for output actions.
- **Confidence gating** — `ConfusionSetCorrector` uses `avgLogprob` below a
  threshold to activate corrections. `WordConfidenceEstimator` is wired to
  provide a synthetic below-threshold value until word-level timestamps are
  available from the SDK.
