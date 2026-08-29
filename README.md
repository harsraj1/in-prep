# In Prep

In Prep is an Android interview-preparation app. Phase 0 provides a secure,
buildable Jetpack Compose baseline only; voice capture, speech recognition,
Gemini, and Voicebox are intentionally not integrated yet.

## Prerequisites

- Android Studio Meerkat (2024.3.1) or newer
- JDK 17
- Android SDK Platform 36 and Android SDK Build-Tools 36.x

## Local setup

1. Clone the repository.
2. Open the repository root in Android Studio.
3. Allow Android Studio to create `local.properties` with your local SDK path.
   This file is ignored and must not be committed.
4. Keep future credentials outside version control. Planned local-only
   configuration names are `GEMINI_API_KEY` and `VOICEBOX_BASE_URL`; neither is
   read by the Phase 0 application.
5. Sync Gradle, then run the `app` configuration on an API 26+ emulator or
   device.

Do not put real credentials in Gradle files, source files, resources, or the
example configuration. A client-side Gemini key may be used only as a
documented local-development compromise in a later phase. Production must use
a backend proxy or another design that does not ship secrets in the app.

## Build and verify

On Windows:

```powershell
.\gradlew.bat test lint assembleDebug
```

On macOS or Linux:

```bash
./gradlew test lint assembleDebug
```

There is no separate formatter task in the Phase 0 baseline.

## Privacy warning

Voice recordings and derived voice profiles are biometric and personal data.
Never commit real voice/audio data. Future implementations must obtain clear
user consent, minimize retention, use app-private storage, delete temporary
files promptly, secure all network transport, and document any server-side
retention or sharing.

See [the architecture notes](docs/architecture.md) for planned boundaries and
unresolved integration decisions.
