# Architecture

## Phase 0 repository baseline

- One Android application module: `app`.
- Package, application ID, and namespace: `com.harsraj.inprep`.
- Kotlin with Gradle Kotlin DSL, Jetpack Compose, and Material 3.
- Minimum SDK 26; compile and target SDK 36.
- The manifest currently declares no permissions.
- The application UI still renders a static placeholder. Phase 1 adds only
  contracts, an explicit session state machine, and in-memory fakes; it has no
  network, microphone, speech-recognition, AI, or audio-generation behavior.

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

## Package structure

```text
com.harsraj.inprep
├── di/
│   └── FakeApplicationContainer.kt       constructor wiring for Phase 1 fakes
└── feature/session/
    ├── domain/
    │   ├── model/InterviewModels.kt      immutable vendor-neutral models
    │   └── SessionContracts.kt           replaceable repository interfaces
    ├── data/fake/
    │   └── FakeSessionServices.kt        deterministic in-memory adapters
    └── presentation/
        ├── InterviewSessionAction.kt     accepted user intents and rejections
        ├── InterviewSessionUiState.kt    exhaustive UI state hierarchy
        └── InterviewSessionViewModel.kt  lifecycle-aware orchestration
```

Domain code contains no Android, transport, endpoint, authentication, vendor,
model-ID, or media-format assumptions. `TemporaryFileReference` is an opaque
app-owned identifier; a later Android data adapter will map it to a file in the
app-private cache. The Activity and Composables do not call repositories.

## Session state machine

`InterviewSessionViewModel` exposes one `StateFlow<InterviewSessionUiState>`.
Every action is synchronously accepted or rejected, so duplicate/invalid user
events cannot silently repeat side effects.

```text
Setup --StartRecording--> Recording --FinishRecording--> Cloning --> Ready
Ready --StartListening--> Listening --FinishListening--> Transcribing
Transcribing --> GeneratingAnswer --> SynthesizingSpeech --> ReadyToPlay
ReadyToPlay --Play--> Playing --Pause--> Paused --Resume--> Playing
Playing --PlaybackCompleted--> Ready

Any active processing state --Cancel/Stop--> nearest stable Setup or Ready
Any operational failure --> RecoverableError --Retry--> failed operation
RecoverableError --Cancel/Stop--> recorded recovery point
Any non-closed state --Close--> Closed --Reset--> Setup
Any non-closed state --Reset--> Setup
```

Valid actions by state:

| State | Valid user actions |
| --- | --- |
| Setup | StartRecording, Close, Reset |
| Recording | FinishRecording, Cancel, Stop, Close, Reset |
| Cloning | Cancel, Stop, Close, Reset |
| Ready | StartListening, Close, Reset |
| Listening | FinishListening, Cancel, Stop, Close, Reset |
| Transcribing / GeneratingAnswer / SynthesizingSpeech | Cancel, Stop, Close, Reset |
| ReadyToPlay | Play, Stop, Close, Reset |
| Playing | Pause, PlaybackCompleted, Stop, Close, Reset |
| Paused | Resume, Stop, Close, Reset |
| RecoverableError | Retry, Cancel, Stop, Close, Reset |
| Closed | Reset |

`Stop` cancels the current coroutine, releases active recorder/recognizer/player
resources, deletes the relevant temporary sample or generated audio, and
returns to the nearest stable state. `Close` additionally removes all temporary
files and prevents further actions until `Reset`. `Reset` also clears persisted
session preferences. Contract `cancel`/`stop` operations are expected to be
idempotent.

## Rotation and process recreation

- Rotation keeps the same `InterviewSessionViewModel`, `StateFlow`, and active
  coroutine through the Activity's ViewModel store. A future Compose screen
  will collect the state with lifecycle awareness and render it without owning
  service resources.
- Process death must not attempt to recreate an in-flight recording,
  recognizer, request, synthesis, or playback operation. Those resources and
  coroutine continuations are process-local.
- Only stable `InterviewContext` and `VoiceProfileReference` values may be
  persisted through `SettingsRepository`. On recreation they initialize
  `Ready`; without both values the app initializes `Setup`.
- Transient question, answer, temporary-file identifiers, errors, and active
  states are not persisted. The application container must clean orphaned
  app-private temporary files at cold start before presenting restored state.
- `SavedStateHandle` may later preserve non-sensitive setup text across
  configuration/process recreation, but it must not contain recordings, audio,
  credentials, or raw service responses.

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

## Selected Voicebox network topology

- The Android phone and the computer hosting Voicebox are connected to the same
  trusted home LAN.
- The Voicebox base URL is configurable and uses the host computer's private
  LAN address, for example `http://192.168.1.50:17493/`. The example is not a
  confirmed API contract or a hard-coded default.
- Cleartext HTTP is permitted only in debug builds for this trusted-LAN
  development topology. A later implementation must scope any Android
  cleartext-network exception to debug configuration and the intended private
  host; release builds must not broadly permit cleartext traffic.
- Public HTTP addresses, public tunnels, router port forwarding, UPnP exposure,
  and firewall rules allowing Public-profile access are prohibited.
- The Voicebox host firewall may allow the chosen port only on the Private
  network profile and only as narrowly as practical for the trusted LAN.
- This topology does not make voice data non-sensitive. The app and service
  must still minimize retention and protect voice samples, profiles, prompts,
  and generated audio from other devices and users on the LAN.

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
