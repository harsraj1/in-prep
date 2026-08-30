# Manual release test matrix

Record device identifiers generically; never attach real voice samples, transcripts,
credentials, private IP addresses, or screenshots containing personal data to Git.

| Area | Emulator (API 26+ and current target API preferred) | Physical device | Expected result |
|---|---|---|---|
| Fresh install/setup | Required | Required | Placeholder screen loads; validation is readable; no credential required to launch. |
| Fake full journey | Required | Required | With ignored fake flag, setup → clone/reuse → question → answer → synthesis → playback is deterministic. |
| Microphone grant/deny/permanent deny | If virtual microphone supports it | Required | Clear rationale/recovery; no crash, trap, or recording before direct action. |
| Sample duration/discard/retry | Optional | Required | 3–30 second enforcement; private file removed on discard/reset. |
| Speech partial/final/edit | Provider permitting | Required | Partial status, editable final transcript, empty input not submitted. |
| Rotation | Required | Required | No duplicate clone/Gemini/Voicebox request; retained state remains coherent. |
| Background/foreground | Required | Required | Recording/listening/playback stops safely; in-flight generation is not duplicated. |
| Gemini network loss/rate/safety failure | Stub/fake required | Real service where safe | Sanitized recoverable copy; retry reuses transcript. |
| Voicebox unreachable/firewall isolation | Stub/fake required | Required on trusted LAN | Useful LAN/server error; retry synthesis does not regenerate answer. |
| Playback/audio focus | Basic playback | Required | Pause/resume/stop/completion correct; call/media focus and noisy-route loss do not leak audio. |
| Headphone/Bluetooth disconnect | Not reliable | Required | Playback pauses rather than unexpectedly switching output. |
| Close/reset during each busy state | Required | Required | Work canceled, media cleaned, question/answer cleared; Close retains saved choices, Reset clears them. |
| Dark mode/high contrast | Required | Required | Readable contrast and state/error visibility. |
| TalkBack/font scale/touch targets | TalkBack where available | Required | Logical reading order, status announcements, descriptions, no clipped controls at 200% font. |
| Small screen/landscape/keyboard | Required | Required | Content scrolls, IME does not hide controls, adaptive layout remains usable. |
| Minified release smoke test | Required | Required | App launches; settings parse; network/JSON/media paths have no R8 regression. |
