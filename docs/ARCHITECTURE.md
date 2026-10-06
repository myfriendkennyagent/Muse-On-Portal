# Architecture and protocol notes

## Modules

| Path | What |
|---|---|
| `protocol/` | Pure Kotlin/JVM port of Meta's Muse Gadget wire protocol. No Android dependencies; runs and tests on any JVM. |
| `app/` | The Android app for Portal (minSdk 28, targetSdk 29, arm64). |
| `tools/` | Deploy, device probe, laptop pairing, vector generation, upstream watch. |
| `.claude/skills/portal` | Meta's `portal` agent skill (vendored): Portal hardware and design constraints. |

### protocol/

Ported from `facebookincubator/muse-gadget-sdk` at the commit in
`tools/upstream.lock`:

| Kotlin | Upstream | Role |
|---|---|---|
| `noise/Proto.kt`, `Envelope.kt`, `Framing.kt` | `linux/.../noise/_proto.py`, `envelope.py`, `framing.py` | protobuf wire format, HTTP-over-Noise envelopes, 65,489-byte chunk framing |
| `noise/NoiseXX.kt` | `noise/noise_xx.py` | `Noise_XX_25519_AESGCM_SHA256` (X25519 via Bouncy Castle: Android 10 has none) |
| `noise/NoiseTransport.kt` | `noise/transport.py` | multiplexed request streams |
| `pairing/PairingSession.kt` | `pairing.py` | pairing v5: P-256 ECDH, HKDF-SHA256, AES-256-GCM records, `confirm_app` |
| `pairing/SetupController.kt`, `ble/BleFraming.kt` | `ble_setup.py`, `ble_framing.py` | the BLE setup commands and `0xFE` chunking |
| `api/MuseApi.kt` | `muse_api.py` | `GET /fetch_vms`, `POST /device_token/refresh` |
| `link/LinkSession.kt` | `link_client.py` | WebSocket `/v1/noise`, Noise handshake, `/link-control`, `/chat/stream` |
| `link/MuseConnection.kt` | `service.py` | reconnect loop, backoff, token rotation, plus the `/chat/subscribe` stream |
| `chat/ChatTracker.kt`, `ChatEvents.kt` | `esp32/.../muse_chat_session.cpp`, `muse_chat_link.c` | which replies belong to this device's turn; when a turn has settled |
| `chat/VoiceNote.kt` | `muse_chat_priv.h` | voice notes as `/chat/stream` file items |

Tests: `NoiseReferenceTest` checks handshake messages, transport frames and
envelope encodings byte-for-byte against vectors produced by running Meta's
Python code (`tools/gen_protocol_vectors.py`); `PairingTest` runs the SDK's
published pairing vectors; `LinkSessionTest` and `MuseConnectionTest` run a
whole session against an in-process fake VM.

### The session, in one connection

1. `GET https://api.muse.ai/fetch_vms` with the device token → VM id + per-VM bearer.
2. `wss://hatch.metaaivm.com/v1/noise?vm_id=…` with the bearer → Noise XX handshake.
3. `POST /link-control` (body left open): `link.register` with `commands_v2`;
   then `link.invoke` → `link.result`. Messages are little-endian u32-length JSON.
4. `POST /chat/subscribe` (body `{}`): NDJSON events for the chat:
   `delta.message_start/text_append/message_done`, `message.assistant`,
   `message.user`, `agent.status`.
5. Each spoken turn: `POST /chat/stream` with
   `{"message":"","output_modality":"text","items":[{"type":"file","mime_type":"audio/wav",…}],"device_id":…}`.
   The ack names the user message; replies are the assistant messages whose
   `reply_to_message_id` is that message.

## Known fragility (read this when something breaks)

- **The Portal registers as a Linux gadget** (`platform: linux`,
  `device_family: homehub`). There's no Android profile in the SDK. Meta could
  reject it at any time; the token terms allow that. The strings live only in
  `protocol/.../link/RegistrationProfile.kt`.
- **Upstream drift.** `.github/workflows/upstream-watch.yml` checks every
  Monday whether Meta changed the protocol files this port mirrors, and opens
  an issue if so. To catch up: port the change, regenerate vectors with
  `tools/gen_protocol_vectors.py`, and run `python3 tools/check_upstream.py --update`.
- **Muse doesn't speak gadget replies**, and `/api/voice/dictation` has no
  speech recognition behind it yet (per the ESP32 firmware), so turns are
  voice notes in, text out, speech synthesised on the Portal.
- **Proactive speech.** The subscription carries every chat event, including
  conversations you have in the phone app. The Portal only speaks a message
  outside a turn if it is a late reply to one of its own turns, or has no
  parent and nobody used Muse elsewhere in the last two minutes. A message
  counts whether it streams in as deltas or arrives whole as
  `message.assistant`, except in the first 5 s after the subscription
  (re)opens, when whole messages may be replayed history. Every message id is
  spoken at most once (the streamed message and its persisted copy share an
  id). The robust path for announcements is Muse calling `voice.say` on the
  device, which only happens when Muse means the Portal.
- **Side chat.** By default the Portal posts to its own side chat (a stable
  `session_id` per install), which keeps the main chat clean and makes the
  tracker drop events tagged with any other session. It's unverified that the
  subscription carries side-chat events. If a side-chat turn gets no reply and
  not even its own message back, the app switches to the main chat and says so.
- **Reply stream failures** are logged as either `REPLY STREAM REFUSED` (a
  4xx from `/chat/subscribe`: likely not offered to this registration
  profile; retried every 60 s) or `REPLY STREAM DROPPED` (network or VM;
  reopened after 3 s), and shown at the top of the screen.
- **Microphone.** Sideloaded apps get the single `handset-mic`; the far-field
  array behind "Hey Portal" needs a Meta-signed permission. Android 10 also
  silences background microphones, which is why the face stays in front. The
  built-in assistant can preempt the stream on some firmware
  (PortalDevKit `06-gotchas.md`).
- **One USB-C port.** You can't use adb over USB and a USB accessory (say a
  USB mic) at the same time. Switch to adb over Wi-Fi first:
  `adb tcpip 5555 && adb connect <portal-ip>:5555`.

## Secrets on the device

| What | Where | Protection |
|---|---|---|
| Device tokens (`pairing.json`) | app files | AES-256-GCM, key in Android Keystore |
| SDK token | app files | same |
| Identity (`identity.json`, a random MAC) | app files | plain: not secret, its suffix is the BLE name |
| Noise keys | memory only | fresh per connection, never stored |

Nothing secret is ever committed: `.gitignore` covers token and pairing files.
