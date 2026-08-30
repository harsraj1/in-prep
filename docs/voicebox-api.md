# Voicebox API contract

## Verified deployment

- Product: Voicebox by `jamiepine/voicebox`
- Running API title/version: `voicebox API` / `0.5.0` (OpenAPI 3.1.0)
- Official immutable release commit:
  `2bcb98d1a8b6fe05e15fbc1559e3085669e4035d`
- Evidence checked: running `GET /openapi.json`, running `GET /health`, the
  official v0.5.0 Git tag/source, and the installed CUDA server command line.
- Deployment: CUDA backend on Windows, `--host 0.0.0.0 --port 17493`.
- Authentication: none. The OpenAPI document has no security scheme. This is
  acceptable only on the selected trusted private LAN.
- Verified phone reachability: `GET http://192.168.29.61:17493/health` returned
  `status: healthy`, CUDA available, from a phone on the same Wi-Fi.
- Host firewall: inbound TCP 17493 is allowed on the Private profile only.
  Previous Public-profile all-port TCP/UDP Voicebox rules are disabled.

The address is development state, not a release default. A router DHCP
reservation is recommended because the current address can change. Router port
forwarding, UPnP exposure, public firewall access, and public tunnels are
prohibited.

## Profile creation and sample upload

There is no `/clone` endpoint. Cloning is a two-request operation.

1. `POST /profiles`, `Content-Type: application/json`

```json
{
  "name": "In Prep voice <random suffix>",
  "language": "en",
  "voice_type": "cloned",
  "default_engine": "chatterbox_turbo"
}
```

The only required request field is `name`; the adapter sends the other shown
fields explicitly. Success is HTTP 200 with `VoiceProfileResponse`. The profile
identifier is the required string field `id`. Other required response fields
are `name`, nullable `description`, `language`, `created_at`, and `updated_at`.

2. `POST /profiles/{profile_id}/samples`,
   `Content-Type: multipart/form-data`

| Part | Type | Required | Contract |
| --- | --- | --- | --- |
| `file` | file | yes | Maximum 50 MiB; streamed by the server in 1 MiB chunks |
| `reference_text` | text | yes | 1–1000 characters; must match speech verbatim |

The v0.5.0 source recognizes `.wav`, `.mp3`, `.m4a`, `.ogg`, `.flac`, `.aac`,
`.webm`, and `.opus`. The Phase 4 mono AAC/M4A sample is therefore accepted.
Unknown extensions are treated as WAV, so the Android adapter must retain the
verified `.m4a` filename and `audio/mp4` part media type rather than depend on
that fallback.

Success is HTTP 200 with required string fields `id`, `profile_id`,
`audio_path`, and `reference_text`. The current domain treats the returned
profile `id` as the reusable voice reference. If sample upload fails after
profile creation, the adapter attempts `DELETE /profiles/{profile_id}` cleanup;
it does not retry either non-idempotent POST.

## Speech generation

The selected engine is `chatterbox_turbo`. Official v0.5.0 source includes it
in `CLONING_ENGINES`, so cloned profiles are supported.

`POST /generate`, `Content-Type: application/json`:

```json
{
  "profile_id": "profile-id-placeholder",
  "text": "Sanitized interview answer.",
  "language": "en",
  "engine": "chatterbox_turbo",
  "personality": false,
  "normalize": true
}
```

`profile_id` and `text` are required. Text is 1–50,000 characters. Success is
HTTP 200 `GenerationResponse`; required fields include `id`, `profile_id`,
`text`, `language`, and `created_at`. The initial status is normally
`generating`.

Progress is streamed by `GET /generate/{generation_id}/status` as
`text/event-stream`. Each event has one JSON `data:` line:

```text
data: {"id":"generation-id-placeholder","status":"generating","duration":0,"error":null,"source":"manual"}

data: {"id":"generation-id-placeholder","status":"completed","duration":1.2,"error":null,"source":"manual"}
```

Terminal statuses are `completed` and `failed`; a missing generation emits
`not_found`. The server polls internally once per second. Android cancellation
cancels the HTTP call and makes a best-effort
`POST /generate/{generation_id}/cancel` when an ID has been obtained.

Completed audio is downloaded with `GET /audio/{generation_id}`. v0.5.0 stores
TTS output as WAV and serves it through `FileResponse`; generated content is
therefore `audio/wav`. The adapter streams it to app-private cache, caps the
response at 25 MiB, and never buffers the complete file in memory.

`POST /generate/stream` also returns `audio/wav`, but constructs the complete
WAV in server memory and is documented for a narrower backend path. The app
uses the asynchronous `/generate` + SSE + `/audio/{id}` contract instead.

## Errors, retries, and diagnostics

- FastAPI validation: HTTP 422, `{"detail":[{"loc":[],"msg":"...","type":"..."}]}`.
- Explicit route errors: HTTP 400/404/413/500, normally
  `{"detail":"sanitized message"}`.
- No general 4xx/5xx schema is declared in OpenAPI; clients must tolerate a
  missing or malformed body and retain the HTTP status.
- Profile creation, sample upload, and generation creation are not retried
  because they are non-idempotent.
- Health, generation status, and audio download may retry at most twice for
  safe transient I/O or HTTP 502/503/504 failures.
- Invalid configuration, DNS/unreachable host, refused connection,
  timeout/firewall-or-client-isolation, HTTP server failure, malformed JSON,
  unexpected media type, cancellation, and oversized audio are mapped to
  distinct domain diagnostics.
- Debug logging contains only method, contract path, and status category. It
  never logs text, multipart content, profile IDs, response bodies, or audio.

## Sanitized curl shapes

```bash
curl -X POST http://PRIVATE_LAN_HOST:17493/profiles \
  -H "Content-Type: application/json" \
  -d '{"name":"In Prep voice example","language":"en","voice_type":"cloned","default_engine":"chatterbox_turbo"}'

curl -X POST http://PRIVATE_LAN_HOST:17493/profiles/PROFILE_ID/samples \
  -F "file=@sample.m4a;type=audio/mp4" \
  -F "reference_text=The exact words spoken in the sample."
```

No real profile identifier, recording, generated answer, or credential is
included in this document or its tests.
