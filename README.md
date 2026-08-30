# In Prep

In Prep is an Android interview-preparation app. The current build provides the
accessible Jetpack Compose experience, persistent non-secret preferences,
lifecycle-safe local voice-sample capture, and in-memory network-service fakes.
Speech recognition, Gemini requests, and Voicebox requests are intentionally not
integrated yet.

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
5. Sync Gradle, then run the `app` configuration on an API 26+ emulator or
   device.

Do not put real credentials in tracked Gradle files, source files, resources,
tests, logs, or example configuration. **A Gemini key placed in `BuildConfig` is
embedded in the debug APK and can be extracted.** This is a local-development
compromise only. Production must use a backend proxy or another design that
does not ship secrets in the app. Release builds receive an empty Gemini value.

## Voicebox on a trusted LAN

The persisted Voicebox base URL contains no credential. Configure it through
the settings repository/UI added around the real adapter in a later phase. A
valid physical-device example is `http://192.168.1.50:17493/`, where the address
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

This is an internal recording format, **not a claim of Voicebox compatibility**.
The deployed Voicebox media contract is still unconfirmed, so any required
conversion remains isolated behind `VoiceSampleFormatConverter`.

Physical-device manual checks:

1. Remove microphone permission, press Record, and verify denial leaves the app
   usable; deny permanently and verify **Open settings** works.
2. Grant permission, record for less than 3 seconds, and verify a recoverable
   error appears without retaining the sample.
3. Record for 3–30 seconds and verify elapsed time, Stop, and Discard.
4. Start recording and background, rotate, or interrupt the app; verify capture
   stops and setup is restored.
5. Rapidly tap controls and verify only one recorder starts.

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

## Privacy warning

Voice recordings and derived voice profiles are biometric and personal data.
Never commit real voice/audio data. Future implementations must obtain clear
user consent, minimize retention, use app-private storage, delete temporary
files promptly, secure all network transport, and document any server-side
retention or sharing.

See [the architecture notes](docs/architecture.md) for planned boundaries and
unresolved integration decisions.
