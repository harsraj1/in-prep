# Architecture

## Phase 0 repository baseline

- One Android application module: `app`.
- Package, application ID, and namespace: `com.harsraj.inprep`.
- Kotlin with Gradle Kotlin DSL, Jetpack Compose, and Material 3.
- Minimum SDK 26; compile and target SDK 36.
- The manifest currently declares no permissions.
- The application renders a static placeholder and has no network, microphone,
  speech-recognition, AI, or audio-generation behavior.

## Intended layers

The product can remain a single Gradle module initially while enforcing source
boundaries:

1. **UI** — Compose screens, explicit immutable UI states, and lifecycle-aware
   collection from screen-level ViewModels.
2. **Presentation** — ViewModels coordinate session actions and expose
   `StateFlow`; session states should be modeled as a sealed hierarchy rather
   than combinations of Boolean flags.
3. **Domain** — company/role context, interview questions and answers, voice
   profile references, session state, and use cases. This layer must not depend
   on Android or vendor SDKs.
4. **Data** — repository implementations and replaceable adapters for Android
   speech recognition, recording, Gemini, Voicebox, and app-private files.
5. **Application container** — a small constructor-based dependency container
   owns production implementations and supplies fakes in tests.

Separate Gradle modules should be introduced only when build isolation or team
ownership justifies the added complexity.

## Intended data flow

```text
Compose UI -> ViewModel -> use cases -> repository interfaces
                                      -> Android speech/recording adapters
                                      -> backend/Gemini adapter
                                      -> self-hosted Voicebox adapter
                                      -> app-private cache
```

The user supplies company and target role. A consented voice sample creates or
reuses a Voicebox profile. Android speech recognition produces question text;
the answer provider produces a role-aware answer; Voicebox produces temporary
audio for playback. Start, pause/resume, stop, and close/reset events pass
through one session state machine. Resource owners release microphones,
recognizers, players, and temporary files on stop and lifecycle teardown.

## Trust boundaries

- **Device boundary:** microphone input, recordings, cached generated audio,
  and any local configuration are sensitive. Files must remain in app-private
  cache and be deleted as soon as they are no longer needed.
- **Android service boundary:** `SpeechRecognizer` is platform/vendor supplied;
  its availability, lifecycle, error behavior, and possible network processing
  must be surfaced rather than hidden.
- **Application backend boundary:** production AI requests must go through a
  secret-preserving backend or equivalent design. No production Gemini secret
  may ship in the APK.
- **Gemini boundary:** questions, company, role, and generated text may leave
  the device. Safety behavior and retention require explicit product policy.
- **Voicebox boundary:** voice samples/profile identifiers and answer text may
  be sent to the self-hosted deployment. TLS, authentication, authorization,
  retention, deletion, and tenant isolation must be verified.

## Decisions requiring verification

- The exact deployed Voicebox version and its documented endpoints, fields,
  authentication, media types, error schema, and response formats. No adapter
  may be written until this contract is confirmed and recorded in
  `docs/voicebox-api.md`.
- The currently supported official Gemini Android/API integration, model ID,
  request schema, safety behavior, and structured-output support. These must be
  verified from official Google documentation immediately before integration.
- Whether speech recognition is on-device on each supported device and what
  disclosure/consent experience is required.
- Voice profile and server-side audio retention/deletion policy.
- Production backend authentication and abuse controls.
- Detailed accessibility, localization, offline, and session-recovery behavior.

## Security and privacy decisions

- No secrets, signing keys, recordings, or generated audio are tracked.
- Phase 0 has no sensitive manifest permissions.
- Backups are disabled to reduce unintended replication of future sensitive
  local data; later phases must review Android backup/data-extraction rules in
  detail.
- Example configuration uses reserved placeholder values only.
