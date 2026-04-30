# Contributing to Safe Word Android

## Before You Start

### Required Knowledge
- **Kotlin 2.1.10** — language, coroutines, sealed classes
- **Android 13+** (Min SDK 33) — services, accessibility, permissions
- **Jetpack Compose** — state hoisting, side effects, modifiers
- **Hilt DI** — constructor injection, `@Singleton`, `@Module`

### Setup
1. Clone the repo:
   ```bash
   git clone https://github.com/safeword-ai/SafeWordAndroid.git
   cd SafeWordAndroid
   ```

2. Ensure JDK 17+ is installed:
   ```bash
   java -version
   # Should show Java 17+
   ```

3. Build and run:
   ```bash
   ./gradlew clean assembleDebug
   ```

---

## Code Standards

### Kotlin Conventions
See [Safe Word Copilot Instructions](.github/copilot-instructions.md) for full list. Highlights:

- **No `!!` operator** — Use `?.let { }`, `?:`, or `requireNotNull()` with message
- **No `GlobalScope` or `runBlocking`** on main thread
- **Named parameters for 3+ args**: `foo(a=1, b=2, c=3)` not `foo(1, 2, 3)`
- **Trailing commas** on multi-line lists/params
- **Timber only** for logging — never `println`, `Log.d`, or `e.printStackTrace()`

### Logging Standards
All Timber calls use a **prefix** followed by context:

```kotlin
Timber.i("[LIFECYCLE] MyService.onCreate | setup=complete")
Timber.d("[STATE] Transitioning from %s to %s", fromState, toState)
Timber.e("[ERROR] Failed to load model | modelId=%s", modelId)
```

**Prefixes:**
- `[INIT]` — initialization, component startup
- `[LIFECYCLE]` — service/activity lifecycle
- `[STATE]` — state transitions
- `[ENTER]` — method entry (debug only)
- `[ERROR]` — errors, exceptions
- `[WARN]` — recoverable issues
- `[PERF]` — performance metrics
- `[VOICE]` — voice command events
- `[A11Y]` — accessibility service events
- `[DOWNLOAD]` — model download progress
- `[AUDIO]` — audio capture events

### Testing
- **Unit tests** use JUnit 4 + Mockk + Turbine
- **Instrumentation tests** use Android Test Suite (androidx.test)
- Place tests in the matching package under `src/test/kotlin/` or `src/androidTest/kotlin/`

Run tests:
```bash
# JVM unit tests
./gradlew :app:testDebugUnitTest

# Instrumentation tests (requires device/emulator)
./gradlew :app:connectedAndroidTest
```

### Documentation
- **Public APIs** require kdoc:
  ```kotlin
  /**
   * Transcribe audio samples to text.
   * @param samples Float array of PCM samples [-1.0, 1.0]
   * @return Transcribed text, or null if inference failed
   */
  fun transcribe(samples: FloatArray): String?
  ```

- **Complex logic** needs inline comments explaining `why`, not `what`
- **Magic numbers** become named constants

---

## Making Changes

### 1. Create a Branch
```bash
git checkout -b fix/issue-name
# or
git checkout -b feature/feature-name
```

Branch naming:
- `fix/` — bug fix
- `feature/` — new feature
- `refactor/` — code cleanup
- `docs/` — documentation
- `test/` — tests only

### 2. Make Your Changes
- **One feature per PR** — keep reviews focused
- **Commit frequently** — small, logical commits
- **Format with Kotlin standard** — trailing commas, named args, etc.

### 3. Test Locally
```bash
./gradlew clean assembleDebug                   # Compiles everything
./gradlew :app:testDebugUnitTest                # Runs JVM unit tests
./gradlew :app:lintDebug                        # Lint check
```

### 4. Commit
Use **Conventional Commits** format:
```
<type>(<scope>): <subject>

<body>

<footer>
```

**Examples:**
```
feat(transcription): add confusion-set correction for homophones

- Implemented homophone mapping (to→2, too→2, etc.)
- Added Levenshtein distance matching (≤2 edits)
- Tested on 50+ common homophones

Closes #42
```

```
fix(audio): handle AudioRecord init failure gracefully

Previously would crash if AudioRecord failed to initialize.
Now logs warning and returns error state to caller.

Closes #39
```

**Types:**
- `feat:` — new feature
- `fix:` — bug fix
- `test:` — test additions
- `docs:` — documentation
- `refactor:` — code restructuring (no behavior change)
- `perf:` — performance improvement
- `ci:` — CI/CD configuration

### 5. Open a Pull Request
- **Title:** Reflect the commit message (will be used for changelog)
- **Description:** Explain _why_ this change is needed, not just what
- **Link issues:** "Closes #XX" or "Fixes #XX"

**PR Checklist:**
- [ ] Tests pass (`./gradlew testDebugUnitTest`)
- [ ] Lint passes (`./gradlew lintDebug`)
- [ ] No new compiler warnings
- [ ] Documentation updated (if API changed)
- [ ] Commit messages follow Conventional Commits

---

## Architecture Overview

### Layers
```
Compose UI (Screens, ViewModels)
    ↓
Data Layer (Repositories, Room, DataStore)
    ↓
Transcription Pipeline (VAD, Moonshine, Post-processing)
    ↓
Android Services (Accessibility, Recording, Overlay)
    ↓
Native/System APIs (AudioRecord, JNI, Moonshine SDK)
```

### Key Packages
- **`transcription/`** — STT pipeline: VAD, streaming, text processing
- **`service/`** — Android services: Accessibility, Recording, Overlay
- **`data/`** — Repositories, Room database, settings (DataStore)
- **`ui/`** — Compose screens, themes, navigation
- **`di/`** — Hilt modules for DI

---

## Common Tasks

### Adding a New Screen
1. Create `SomeScreen.kt` in `ui/screens/some/`
2. Create `SomeViewModel.kt` in the same directory
3. Use `@HiltViewModel` for DI
4. Expose state as `StateFlow<UiState>`
5. Add navigation in `SafeWordNavGraph.kt`

### Adding a Database Table
1. Create entity class in `data/db/` with `@Entity`
2. Create DAO in `data/db/` with `@Dao`
3. Add entity to `SafeWordDatabase.entities` list
4. Bump database version
5. Add migration in `SafeWordDatabase.Companion`

### Adding a Setting
1. Add field to `AppSettings.kt` data class
2. Update `SettingsRepository.settings` flow
3. Update UI toggle/input in `SettingsScreen.kt`
4. Test persistence via `runTest { settings.first() }`

---

## Troubleshooting

### Build fails with "Unknown hilt option"
→ **Cause**: Hilt + Room KSP multi-round issue
→ **Fix**: Run `./gradlew clean assembleDebug` (full clean)

### Lint error: "minSdk is 33 but this API is 34+"
→ **Cause**: Using Android 34 API without `@RequiresApi(34)`
→ **Fix**: Wrap in `if (Build.VERSION.SDK_INT >= 34) { ... }`

### Compose recomposition spam
→ **Check**: Are you calling `remember` with unstable types?
→ **Fix**: Wrap non-stable objects in `@Stable` or use `@Immutable` data class

---

## Need Help?

- **Architecture questions** → Read [ARCHITECTURE.md](ARCHITECTURE.md)
- **API questions** → Check kdoc comments in the code
- **Build issues** → Try `./gradlew clean` first
- **Still stuck?** → Open an issue with detailed error output

---

## Code Review Process

All PRs require review from maintainers. Reviewers check for:
- ✅ Correctness (logic, edge cases)
- ✅ Type safety (no `!!, no `Any`)
- ✅ Error handling (no bare `catch`)
- ✅ Testing (unit + integration coverage)
- ✅ Documentation (comments, kdoc)
- ✅ Performance (no N+1, blocking ops)
- ✅ Security (no hardcoded secrets, injection safety)

---

## Release Checklist

Before releasing a new version:
- [ ] All tests pass locally and in CI
- [ ] Version bumped in `build.gradle.kts` (versionCode + versionName)
- [ ] `CHANGELOG.md` updated
- [ ] Screenshots/GIFs attached if UI changed
- [ ] Git tag created: `git tag v1.2.3`
