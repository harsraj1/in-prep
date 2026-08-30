# MVP threat model

## Assets and trust boundaries

Assets are the Gemini credential, voice samples/profile references, transcripts,
generated answers/audio, Voicebox address, and session settings. Boundaries exist
between the Android app, Android recognition provider, Gemini, the trusted LAN, and
the self-hosted Voicebox operator.

| Threat | Current mitigation | Release/production requirement |
|---|---|---|
| APK key extraction | Release embeds no Gemini key; debug key is ignored local configuration with warnings. | Route Gemini through an authenticated backend proxy; rotate any exposed development key. |
| Malicious Voicebox URL | URL validation rejects malformed and public cleartext targets; release rejects all HTTP. | Require authenticated HTTPS, certificate policy, and an allowlisted deployment. |
| Public firewall/router exposure | Documentation permits only TCP 17493 on Windows Private profile and prohibits Public profile, UPnP, forwarding, and tunnels. | Authenticated service, least-privilege firewall, monitoring, and no direct internet exposure. |
| Untrusted Wi-Fi interception | HTTP is debug-only and documented for a trusted home LAN. | TLS for every production connection; never use development HTTP on public/guest Wi-Fi. |
| Transcript/screen leakage | Answers/transcripts are session state only and are cleared on Close/reset. Logs are sanitized. | Review screenshots/recents protection, telemetry policy, and device-compromise assumptions. |
| Log leakage | Network logs contain stage/status metadata only; exception text and bodies are not shown to users. | Keep production logging minimal, redact identifiers, and test crash-report payloads. |
| Oversized/malformed responses | Both adapters bound response/audio sizes and map parsing/media failures. | Enforce equivalent limits at the proxy/server and monitor abuse. |
| Voice-profile misuse | Only an opaque profile reference is stored locally; audio stays in private cache. | Authenticate profile operations, authorize per user, support deletion, audit access, and document server retention. |
| Stale or orphaned media | Private stores expire files and session controls clean known files. | Validate cleanup across crashes and storage pressure; provide server-side deletion controls. |
| Prompt injection/company misinformation | Inputs are delimited as untrusted data and prompts prohibit insider/unverified claims. | Add policy monitoring and evaluation against adversarial prompts. |

## Residual risk

The Android device, recognition service, Gemini, and Voicebox operator all remain
trusted parties for the data they receive. A rooted device, malicious accessibility
service, compromised development computer, or hostile authenticated server is beyond
the current client-only controls. Release approval must explicitly accept or mitigate
these risks.
