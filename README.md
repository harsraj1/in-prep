# In Prep

In Prep is an Android interview-preparation app. The current build provides the
accessible Jetpack Compose experience, persistent non-secret preferences,
lifecycle-safe local voice-sample capture, Android speech recognition, tailored
Gemini answers, and a verified Voicebox v0.5.0 LAN adapter.

## Prerequisites

- Android Studio Meerkat (2024.3.1) or newer
- JDK 17
- Android SDK Platform 36 and Android SDK Build-Tools 36.x

## Local setup

1. Clone the repository.
2. Open the repository root in Android Studio.
3. Allow Android Studio to create `local.properties` with your local SDK path.
   This file is ignored and must not be committed.
4. For local Gemini development only, add `GEMINI_API_KEY=your-local-key` to the
   ignored root `local.properties`, or to your user-level Gradle properties file
   (`~/.gradle/gradle.properties`). Never add it to the repository's tracked
   `gradle.properties`.
5. For Voicebox development, add a private-LAN URL such as
   `VOICEBOX_BASE_URL=http://192.168.1.50:17493/` to ignored
   `local.properties`. Replace the example with the Voicebox computer's current
   private address; no URL is shipped in release.
6. Sync Gradle, then run the `app` configuration on an API 26+ emulator or
   device.

For a deterministic demonstration with no microphone, Gemini, Voicebox, or
network calls, add `INPREP_USE_FAKE_SERVICES=true` to ignored
`local.properties` and rebuild the debug app. Remove it or set it to `false` to
exercise the real development configuration. Release builds always disable
fake mode.

Do not put real credentials in tracked Gradle files, source files, resources,
tests, logs, or example configuration. **A Gemini key placed in `BuildConfig` is
embedded in the debug APK and can be extracted.** This is a local-development
compromise only. Production must use a backend proxy or another design that
does not ship secrets in the app. Release builds receive an empty Gemini value.

## Voicebox on a trusted LAN

The persisted Voicebox base URL contains no credential. A valid physical-device
example is `http://192.168.1.50:17493/`, where the address
is the Voicebox computer's private LAN address. It is a placeholder example,
not an application default.

- `127.0.0.1` or `localhost` on an Android phone refers to the phone, not the
  Voicebox computer. Use the computer's private IPv4/IPv6 LAN address.
- The standard Android emulator can reach its host loopback through
  `10.0.2.2`; a physical phone cannot. This address is emulator-specific.
- Prefer a DHCP reservation in the router so the Voicebox computer keeps the
  same private address. Do not hardcode that development address in a release.
- Use HTTP only in a debug build, only while both devices are on a trusted home
  network. The app rejects HTTP hosts unless every resolved address is
  loopback, link-local, an RFC1918 private IPv4 address, or an IPv6 unique-local
  address. HTTPS remains the production transport.
- Never expose this MVP through router port forwarding, UPnP, a public tunnel,
  a public HTTP address, or a Public-profile firewall rule. Limit the computer
  firewall rule to the trusted Private network and the Voicebox port.

Android Network Security Configuration cannot allow cleartext for an arbitrary
runtime-selected IP with a narrow host rule. Consequently, the debug manifest
has a broad OS-level cleartext exception and application-level URL validation
is the mandatory boundary. The main/release manifest rejects cleartext.

## Voice sample recording

Recording starts only after the user presses **Record voice sample** and grants
microphone permission. A rationale is shown when Android recommends one; denied
permission can be retried, and permanent denial provides a route to system app
settings. Leaving the app while recording cancels the capture.

Samples must be 3–30 seconds. They are captured as mono AAC audio in an MPEG-4
`.m4a` container (44.1 kHz, 128 kbps) under the app-private
`cacheDir/voice-samples` directory. Discard, reset, recording failure, and cache
expiry remove temporary files. Raw recordings and generated answers are never
stored in preferences or logs.

Voicebox v0.5.0 explicitly accepts `.m4a`, so this format can be uploaded without
conversion. The displayed sample script is sent as the required
`reference_text` and must be spoken verbatim. The Voicebox adapter selects
`chatterbox_turbo` for both the cloned profile and synthesis to minimize latency.

Physical-device manual checks:

1. Remove microphone permission, press Record, and verify denial leaves the app
   usable; deny permanently and verify **Open settings** works.
2. Grant permission, record for less than 3 seconds, and verify a recoverable
   error appears without retaining the sample.
3. Record for 3–30 seconds and verify elapsed time, Stop, and Discard.
4. Start recording and background, rotate, or interrupt the app; verify capture
   stops and setup is restored.
5. Rapidly tap controls and verify only one recorder starts.

## Spoken question capture

Press **Listen** to start Android's installed `SpeechRecognizer`; it never starts
in the background. Partial text is shown while listening. A final result opens a
review field where the transcript can be corrected before **Generate answer** is
enabled. Empty, punctuation-only, and obviously short transcripts are rejected.

The recognizer shares the `RECORD_AUDIO` permission flow with voice sampling.
Only session states that cannot be recording permit Listen, so the sample recorder
and recognizer cannot own the microphone simultaneously. Backgrounding, Stop,
Cancel, Close, reset, and ViewModel teardown stop or destroy recognizer resources.

Speech recognition availability, language support, offline behavior, accuracy,
and network use vary by device and installed recognition service. On a physical
device, manually verify:

1. Denied and permanently denied microphone permission leave the session usable.
2. Partial text updates and the best final transcript reaches the review field.
3. Silence/no-match, airplane mode, and service/network failure show Retry/Cancel.
4. Rapid Listen taps do not create multiple recognizers; Stop and app backgrounding
   release the microphone so voice-sample recording can subsequently start.
5. Editing the transcript changes the question passed to answer generation.

Voicebox is optional for text practice. **Continue with text answers** starts a
session without recording or cloning, and the same option is offered if profile
creation fails. Gemini still receives the reviewed question and the generated
answer remains visible; synthesis/playback is simply skipped. If synthesis fails
after Gemini succeeds, the answer remains on screen and can be reused without
another Gemini request.

## Generated-answer playback

Voicebox synthesis completes and streams a bounded WAV into
`cacheDir/generated-audio` before **Start** becomes available. Playback uses stable
Media3 ExoPlayer 1.11.0 rather than the platform `MediaPlayer`. Pause preserves the
position and Resume continues it. Stop resets playback while retaining the prepared
answer and audio so it can be started again; completion, Close, reset, and cache
expiry remove private temporary audio.

ExoPlayer manages audio focus with speech audio attributes. Speech is paused rather
than ducked when focus policy requires it, and headphone/Bluetooth route loss pauses
playback instead of unexpectedly switching to the speaker. Backgrounding playback,
Close, reset, ViewModel teardown, corruption, and unsupported audio all release or
reset player resources. The session state machine prevents playback from overlapping
voice recording, recognition, answer generation, or synthesis.

Physical-device checks:

1. Generate an answer, then verify Start is unavailable until synthesis finishes.
2. Start, pause, resume, and stop; confirm resume preserves position and a later Start
   begins prepared audio from the beginning.
3. Let playback complete and verify the session returns to Ready for another question.
4. During playback, unplug wired headphones or disconnect Bluetooth and verify audio
   pauses instead of moving unexpectedly to the speaker.
5. Trigger another app's audio focus and background In Prep; verify no audio or player
   resource remains active. Close/reset during synthesis or playback must remain safe.

## Build and verify

On Windows:

```powershell
.\gradlew.bat test lint assembleDebug
```

With an emulator or device connected, run Compose UI tests with:

```powershell
.\gradlew.bat connectedDebugAndroidTest
```

On macOS or Linux:

```bash
./gradlew test lint assembleDebug
```

Then run `./gradlew connectedDebugAndroidTest` with an emulator or device.

There is no separate formatter task in the Phase 0 baseline.

For a release-candidate check, run from a clean working tree:

```powershell
.\gradlew.bat clean test lint assembleDebug assembleRelease
```

Release builds use R8 code shrinking and resource shrinking. The adapters use
explicit `org.json` parsing rather than reflection-based model serialization;
OkHttp and Media3 supply their own consumer rules. Investigate any future R8
warning instead of adding a broad keep rule. Release builds contain no Gemini key,
Voicebox development URL, or fake-service switch.

## Troubleshooting

- **Phone cannot reach Voicebox:** verify Voicebox listens on `0.0.0.0:17493`,
  both devices use the same trusted non-guest Wi-Fi, AP/client isolation is off,
  the saved URL uses the computer's current private address, and the inbound TCP
  rule applies only to Windows Private profile.
- **`127.0.0.1` works only on the computer:** localhost on a phone is the phone.
  Use the computer's LAN address. Android Emulator uses `10.0.2.2` for host loopback.
- **Voicebox reports healthy but generation fails:** load the documented
  Chatterbox Turbo model, verify the v0.5.0 contract in `docs/voicebox-api.md`, and
  inspect sanitized server logs without recording request bodies.
- **Gemini is unavailable:** confirm the ignored debug key is configured and valid,
  then check connectivity, quota, and safety response. Release intentionally has no
  client key and requires a backend proxy.
- **Speech recognition unavailable:** install/enable a recognition provider, grant
  microphone access, and test outside airplane mode; offline support varies by device.
- **Gradle daemon or SDK failure:** use JDK 17, install SDK 36/build-tools 36.x, stop
  stale daemons if necessary, and rerun the documented verification command.

## Privacy warning

Voice recordings and derived voice profiles are biometric and personal data.
Never commit real voice/audio data. Future implementations must obtain clear
user consent, minimize retention, use app-private storage, delete temporary
files promptly, secure all network transport, and document any server-side
retention or sharing.

The in-app **Privacy** dialog summarizes the active trust boundaries: the voice
sample and generated answer go to the configured Voicebox operator; company,
role, and recognized question go to Gemini; and the installed Android speech
recognition provider may process spoken questions. Close cancels active work,
deletes temporary session media, and returns to setup while retaining deliberately
saved target/profile preferences. Reset additionally clears those preferences.

See [the architecture notes](docs/architecture.md) and the
[verified Voicebox contract](docs/voicebox-api.md). The current Gemini model,
wire contract, safety behavior, and credential boundary are documented in
[the Gemini integration notes](docs/gemini-integration.md). Release candidates must
also review the [privacy overview](docs/privacy.md),
[threat model](docs/threat-model.md), [release checklist](docs/release-checklist.md),
and [manual test matrix](docs/manual-test-matrix.md).
