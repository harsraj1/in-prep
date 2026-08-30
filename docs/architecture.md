# Architecture

## Phase 0 repository baseline

- One Android application module: `app`.
- Package, application ID, and namespace: `com.harsraj.inprep`.
- Kotlin with Gradle Kotlin DSL, Jetpack Compose, and Material 3.
- Minimum SDK 26; compile and target SDK 36.
- The manifest declares microphone and network permissions; cleartext remains
  disabled outside the debug-only LAN override.
- The application renders the complete Phase 2 setup/interview experience and
  uses real private-cache recording and the verified Voicebox adapter. Speech
  recognition, Gemini, and playback remain fake.

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
│   └── FakeApplicationContainer.kt       constructor-based application wiring
└── feature/session/
    ├── domain/
    │   ├── model/InterviewModels.kt      immutable vendor-neutral models
    │   └── SessionContracts.kt           replaceable repository interfaces
    ├── data/fake/
    │   └── FakeSessionServices.kt        deterministic in-memory adapters
    ├── ui/
    │   └── InterviewPreparationScreen.kt accessible state-driven Compose UI
    └── presentation/
        ├── InterviewSessionAction.kt     accepted user intents and rejections
        ├── InterviewSessionUiState.kt    exhaustive UI state hierarchy
        └── InterviewSessionViewModel.kt  lifecycle-aware orchestration
└── feature/voicebox/data/
    ├── VoiceboxVoiceServices.kt          isolated v0.5.0 HTTP adapter
    └── PrivateGeneratedAudioStore.kt     bounded private-cache WAV targets
```

Domain code contains no Android, transport, endpoint, authentication, vendor,
model-ID, or media-format assumptions. `TemporaryFileReference` is an opaque
app-owned identifier; a later Android data adapter will map it to a file in the
app-private cache. The Activity and Composables do not call repositories.

## Compose UI

`InterviewPreparationScreen` is a pure renderer: it receives one UI state and
dispatches typed actions. `MainActivity` owns no repository logic; it collects
the ViewModel `StateFlow` with lifecycle awareness and supplies the process-wide
fake application container.

- Setup fields start empty, preserve local editing state with `rememberSaveable`,
  support IME Next/Done actions, and show actionable validation errors.
- All action buttons are rendered only in states where the state machine accepts
  that action. Recording stop, sample discard, cloning, listening completion,
  playback, pause/resume, stop, retry, close, and reset remain distinct intents.
- The layout is vertically scrollable and switches from stacked cards to a
  two-column arrangement at 720 dp, while `imePadding` keeps keyboard content
  reachable on compact screens.
- Material components provide semantic button roles and minimum touch targets.
  Section titles are headings; changing session status uses a polite live
  region; recoverable errors use an assertive live region; progress indicators
  have a spoken description.
- Light and dark color schemes use paired Material container/on-container colors
  for readable contrast. Close and full reset each require confirmation.
- Preview-only values are visibly synthetic and never become production defaults.

Compose instrumentation tests cover setup validation, state-valid controls,
Pause/Resume labels, live error status and Retry, and confirmed Close/Reset.
State-machine JVM tests separately verify the full fake journey and resource
cleanup. No test includes a recording, generated audio binary, credential, or
production URL.

## Session state machine

`InterviewSessionViewModel` exposes one `StateFlow<InterviewSessionUiState>`.
Every action is synchronously accepted or rejected, so duplicate/invalid user
events cannot silently repeat side effects.

```text
Setup --StartRecording--> Recording --FinishRecording--> VoiceSampleReady
VoiceSampleReady --CloneVoice--> Cloning --> Ready
VoiceSampleReady --DiscardVoiceSample--> Setup
Setup --ReuseVoiceProfile--> Ready
Ready --StartListening--> Listening --FinishListening/final--> Transcribing/QuestionReady
QuestionReady --GenerateFromTranscript--> GeneratingAnswer --> SynthesizingSpeech --> ReadyToPlay
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
| Setup | StartRecording, ReuseVoiceProfile, Close, Reset |
| Recording | FinishRecording, Cancel, Stop, Close, Reset |
| VoiceSampleReady | CloneVoice, DiscardVoiceSample, Cancel, Stop, Close, Reset |
| Cloning | Cancel, Stop, Close, Reset |
| Ready | StartListening, Close, Reset |
| Listening | FinishListening, Cancel, Stop, Close, Reset |
| Transcribing / GeneratingAnswer / SynthesizingSpeech | Cancel, Stop, Close, Reset |
| QuestionReady | GenerateFromTranscript, StartListening, Cancel, Stop, Close, Reset |
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
  development topology. Android's debug network configuration cannot narrowly
  scope a runtime-selected IP, so strict private-address validation provides
  the host boundary; release builds reject cleartext traffic.
- Public HTTP addresses, public tunnels, router port forwarding, UPnP exposure,
  and firewall rules allowing Public-profile access are prohibited.
- The Voicebox host firewall may allow the chosen port only on the Private
  network profile and only as narrowly as practical for the trusted LAN.
- This topology does not make voice data non-sensitive. The app and service
  must still minimize retention and protect voice samples, profiles, prompts,
  and generated audio from other devices and users on the LAN.

## Phase 3 configuration and persistence

`DataStoreSettingsRepository` is the durable implementation of the domain
`SettingsRepository`. It stores only company, role, the validated Voicebox base
URL, and the non-sensitive voice-profile reference and timestamp. It never
stores voice samples, generated answers, generated audio, or credentials.
DataStore is application-scoped, so these values survive Activity recreation
and normal process restart. Reset clears the complete preference store.

The application container injects DataStore, the Android recorder, and the
Voicebox adapter while the remaining interview services stay fake. State-machine
unit tests continue to inject in-memory fakes. On startup, the session ViewModel
loads persisted metadata: a complete context/profile pair restores `Ready`; a
context without a profile pre-fills `Setup`.

`VoiceboxBaseUrlValidator` is the transport-policy boundary. HTTPS is accepted
in all variants. HTTP is accepted only when the caller identifies a debug build
and every DNS result is loopback, link-local, private IPv4, or IPv6 unique-local.
URLs containing credentials, query strings, fragments, or non-root paths are
rejected. The base URL is normalized with a trailing slash before persistence.

Android Network Security Configuration cannot narrowly enumerate a dynamic LAN
IP. The main manifest therefore denies cleartext, while the debug manifest has
a necessarily broad cleartext override. All future Voicebox adapters must call
the validator before creating or issuing a request. Release receives no LAN URL
default and cannot persist an HTTP URL.

The debug-only `BuildConfig.GEMINI_API_KEY` is loaded from ignored
`local.properties` or user-level Gradle properties. It is deliberately empty in
release. This does not make the debug key secret—APK contents are extractable—so
production Gemini access remains behind a backend proxy trust boundary.

## Phase 4 microphone and temporary recording boundary

`MainActivity` owns the Android runtime permission launcher but not recorder
logic. The visible Record action is the sole entry point. A pure permission
policy selects start, rationale, request, retry, or system-settings recovery;
denial never transitions the session into `Recording`.

`AndroidVoiceSampleRecorder` implements the domain recorder contract with
`MediaRecorder`. It publishes elapsed time, completion, and errors through a
`StateFlow`; `InterviewSessionViewModel` maps those events into the existing
explicit session state machine. Duplicate actions are rejected, recorder errors
are recoverable, and `ON_STOP` cancels active capture. ViewModel teardown also
cancels the recorder.

`PrivateVoiceSampleStore` is the only component translating opaque temporary
file IDs into paths. All paths are canonicalized beneath
`cacheDir/voice-samples`; cancellation, discard, reset, failure, and 24-hour
expiry delete them. Preferences never contain audio or generated answers.

The internal format is mono AAC in an MPEG-4 `.m4a` container at 44.1 kHz and
128 kbps, limited to 3–30 seconds. Voicebox v0.5.0 source confirms `.m4a` support,
so the adapter uploads it with its exact extension and `audio/mp4` media type.

## Phase 5 Voicebox boundary

`VoiceboxVoiceServices` is the sole owner of the verified HTTP contract and
implements both vendor-neutral voice-cloning and audio-synthesis interfaces. It
validates the configured base URL before every operation, creates a cloned
profile with `chatterbox_turbo`, uploads the Phase 4 sample and exact script,
starts asynchronous synthesis with the same engine, consumes the status SSE,
and streams the completed WAV into `cacheDir/generated-audio`.

Non-idempotent POST operations are never retried. Safe GET operations have at
most two bounded retries for transport failures or HTTP 502/503/504. Cancellation
closes the active call and best-effort cancels an identified generation. Audio
is limited to 25 MiB and partial files are removed. Logs omit URLs, text, IDs,
bodies, and media. `CompositeTemporaryFileCleaner` deletes both samples and
generated audio on session cleanup, while cold start expires files older than
24 hours. Exact evidence and schemas are in `docs/voicebox-api.md`.

## Phase 6 speech-recognition boundary

`AndroidSpeechRecognitionRepository` is the lifecycle owner of Android's
`SpeechRecognizer`. Every public operation asserts main-thread access, recognition
begins only after the visible Listen action passes the shared microphone permission
gate, and `destroy()` is called from ViewModel teardown. Stop, cancellation,
backgrounding, close, and reset release active recognition.

The repository publishes `Idle`, `Listening(partialTranscript)`, `Final`, and
`Failed` status through `StateFlow`. The session state machine mirrors partial text,
maps device/service errors to recoverable states, and moves valid final results into
`QuestionReady`. The editable review state is the only path to answer generation;
empty, punctuation-only, and obviously short text is never submitted. State validity
prevents voice-sample recording and question listening from running concurrently.

Pure policy tests cover transcript selection and error categories; ViewModel tests
cover partial/final delivery, review/edit submission, failure recovery, repeated
actions, cancellation, and host-stop cleanup. Device recognition engines differ, so
permission UI, service availability, network/offline behavior, and actual microphone
release also require the README physical-device checks.

## Decisions requiring verification

- The currently supported official Gemini Android/API integration, model ID,
  request schema, safety behavior, and structured-output support. These must be
  verified from official Google documentation immediately before integration.
- Whether speech recognition is on-device on each supported device remains a
  runtime/device property and requires appropriate disclosure.
- Voice profile and server-side audio retention/deletion policy.
- Production backend authentication and abuse controls.
- Detailed accessibility, localization, offline, and session-recovery behavior.

## Security and privacy decisions

- No secrets, signing keys, recordings, or generated audio are tracked.
- Microphone access starts only from an explicit visible user action; network
  access is constrained by the debug/release transport policy.
- Backups are disabled to reduce unintended replication of future sensitive
  local data; later phases must review Android backup/data-extraction rules in
  detail.
- Example configuration uses reserved placeholder values only.
