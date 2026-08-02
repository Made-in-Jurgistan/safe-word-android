# Contributing to Safe Word Android

## Before you start

Safe Word is an Android 13+ application written in Kotlin with Jetpack Compose,
Hilt, Room, WorkManager, and on-device Moonshine speech recognition. Review
[`SECURITY.md`](SECURITY.md) before reporting security issues and follow the
[Code of Conduct](CODE_OF_CONDUCT.md) in all project spaces.

## Development setup

1. Install Android Studio and JDK 17.
2. Clone this repository and open it in Android Studio.
3. Connect an Android 13+ arm64 device or configure an equivalent test device.
4. Run the checks below before opening a pull request.

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:detekt
.\gradlew.bat :app:assembleDebug
```

The model is downloaded at runtime. Do not commit downloaded models, signing
keys, local properties, or user data.

## Making changes

- Create a focused branch from the current default branch.
- Keep changes small enough to review and preserve existing behavior unless the
  pull request explains the behavior change.
- Use constructor injection and structured coroutines.
- Do not use `!!`, `GlobalScope`, `runBlocking` on the main thread, or logging
  APIs that expose user audio, transcripts, or secrets.
- Add or update tests for changed behavior.
- Update the README or command reference when user-facing behavior changes.

## Pull requests

Include:

- why the change is needed;
- the relevant tests and local results;
- privacy or permission implications;
- screenshots or recordings for UI changes, without personal data.

Do not commit secrets or generated build outputs. Use Conventional Commit
subjects such as `fix(transcription): handle empty streaming lines`.
