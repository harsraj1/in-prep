# Privacy overview

## Data collected and why

- Company and target role personalize practice answers. They may be saved locally
  when the user deliberately starts setup.
- Spoken interview questions are converted to text for review and answer generation.
- A voice sample is captured only after a visible user action and microphone grant.
- A non-sensitive Voicebox profile reference and base URL may be saved locally.

Generated answers and raw audio are not persisted as preferences. Voice recordings
and voice profiles are biometric/personal data and require careful handling.

## Transmission destinations

- The installed Android speech-recognition provider may receive question audio;
  whether processing is on-device is device/provider dependent.
- Gemini receives company, role, reviewed question, and answer-style instructions.
- The configured Voicebox server receives the voice sample during profile creation
  and generated answer text during synthesis. Its operator can access submitted data.

The development Voicebox topology is restricted to a trusted home LAN. Debug HTTP
is accepted only for private, loopback, or link-local addresses after application
validation. Release rejects all cleartext traffic. Production Gemini use requires a
backend proxy so a credential is never embedded in the APK.

## Retention and cleanup

- Samples and generated WAV files live only in app-private cache.
- Discard, completion, Close, reset, failure cleanup, and cache expiry remove files
  at their documented lifecycle points. Stop preserves prepared playback audio so
  the user can restart the visible answer; Close/reset deletes it.
- Settings contain only company, role, Voicebox URL, and profile reference. Reset
  clears them. Android backups are disabled.
- Server-side retention by Gemini, the recognition provider, or Voicebox is governed
  by those deployments/providers and must be verified before production use.

## User controls and known risks

Users explicitly start recording/listening, can edit a transcript before generation,
can stop/cancel operations, can Close a session while keeping saved choices, and can
Reset all local preferences and temporary media. The in-app Privacy dialog summarizes
the transmission boundaries.

Known risks include extractable debug API keys, an incorrectly trusted Voicebox
operator, compromised/untrusted Wi-Fi, server-side retention, speech-provider data
handling, voice-profile impersonation/misuse, and sensitive content visible on screen.
This MVP is not suitable for confidential interview material without production
backend, authentication, transport, consent, deletion, and retention controls.
