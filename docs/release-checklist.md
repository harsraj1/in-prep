# Release candidate checklist

## Source and configuration

- [ ] Confirm branch and working tree are expected; review every staged file.
- [ ] Confirm `local.properties`, credentials, signing files, recordings, generated
  audio, APKs, AABs, and build output are ignored and unstaged.
- [ ] Confirm release `BuildConfig` has empty Gemini/Voicebox development values,
  fake services disabled, and cleartext disabled.
- [ ] Verify version code/name and release notes.
- [ ] Use a protected CI/release signing secret; never add a keystore to Git.

## Automated verification

- [ ] Run `clean test lint assembleDebug assembleRelease`.
- [ ] Confirm the GitHub **Android validation** workflow passes on the exact
  commit proposed for `v0.1.0`. It must run without Gemini or Voicebox secrets.
- [ ] Run `connectedDebugAndroidTest` on an available emulator/device and record the
  device/API; do not mark it complete without actual output.
- [ ] Inspect lint HTML/SARIF and both unit/instrumented reports.
- [ ] Install and smoke-test the minified release artifact without credentials.
- [ ] Confirm R8 has no missing-class warnings and the verified network/JSON paths work.

## Privacy and network

- [ ] Review `privacy.md` and `threat-model.md`; confirm in-app disclosure matches.
- [ ] Confirm Voicebox is limited to the trusted Private LAN firewall profile with no
  forwarding, UPnP, public tunnel, guest Wi-Fi, or AP isolation.
- [ ] Confirm production uses HTTPS/authentication and Gemini backend proxy; otherwise
  do not distribute beyond controlled local development.
- [ ] Exercise Close/reset and verify private temporary media cleanup.
- [ ] Verify server-side retention/deletion and voice-profile authorization policy.

## Manual sign-off

- [ ] Complete `manual-test-matrix.md` on at least one emulator and one physical device.
- [ ] Verify dark theme, font scaling, TalkBack/screen reader, keyboard navigation,
  rotation, backgrounding, audio focus, headphone disconnect, and network loss.
- [ ] Record known limitations and release approver/date outside the repository if it
  contains personal or deployment-sensitive information.

## MVP checkpoint

- [ ] Review the commits included since the baseline and confirm the intended
  checkpoint commit is on `main`.
- [ ] Summarize automated/device tests, privacy limitations, and known issues for
  the approver.
- [ ] Create the annotated local tag only after the summary is reviewed:
  `v0.1.0` with message `Android Interview Preparation App MVP`.
- [ ] Obtain explicit approval before pushing the tag. Tagging does not authorize
  creating a GitHub Release or uploading an APK/AAB.
