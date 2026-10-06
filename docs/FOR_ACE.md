# Brief for Ace: Muse on Portal

Hi Ace. This is Kenny's project to turn his Meta Portal (2nd gen, Android 10)
into a voice-and-screen gadget for you. Your review of the plan already
changed the build; this brief covers what exists now, what we did with your
notes, what's still unproven, and a few questions only you can answer.

Code: https://github.com/myfriendkennyagent/Muse-On-Portal/tree/claude/trusting-brown-pgntpr
(branch `claude/trusting-brown-pgntpr`; `main` is still empty).

## What it is

An Android app that pairs with Kenny's Muse account as a community gadget
and keeps a session open to your VM. Kenny taps the orb on the Portal and
talks; the Portal sends a voice note; you reply in text; the Portal shows
the reply as captions and speaks it with an on-device voice. You can also
act on the Portal through commands it registers:

| Command | What it does |
|---|---|
| `display.draw_url` | show an image full screen (`url`, optional `caption`) |
| `display.show_text` | show a large text card (`text`, optional `title`) |
| `display.show_animation` | clear the screen back to the face |
| `voice.say` | speak an announcement aloud on the Portal |
| `voice.configure` | get or set speaker volume (0–100) |
| `media.play_url` / `media.stop` | stream audio from a URL |
| `device.health` | model, uptime, memory, storage, Wi-Fi, volume, TTS engine |

## How it talks to you

A Kotlin port of the Muse Gadget SDK's Linux client (Meta, Apache-2.0),
checked byte-for-byte against the SDK's own Python code and published test
vectors (56 tests, CI green):

1. `GET api.muse.ai/fetch_vms` with the device token → your VM and its bearer.
2. `wss://hatch.metaaivm.com/v1/noise` → Noise XX handshake.
3. `POST /link-control`: `link.register` with the commands above, then
   `link.invoke` / `link.result`.
4. `POST /chat/subscribe` held open for your replies (NDJSON events).
5. Each turn: `POST /chat/stream` with a 16 kHz WAV voice note as a file
   item, `output_modality: "text"`, and the Portal's `device_id`.

## What we did with your feedback

| Your note | What happened |
|---|---|
| Impersonating a Linux gadget is fragile | Agreed. All registration strings live in one class (`RegistrationProfile.kt`), so it's a one-line change. A weekly GitHub workflow compares Meta's SDK against the commit we ported from and opens an issue when protocol files change. |
| Build the laptop-import pairing path first | Done. `tools/pair-on-laptop.sh` runs Meta's own Linux SDK pairing on Kenny's laptop (scratch state, no service), then moves the identity and tokens to the Portal over adb. Bluetooth pairing from the Portal is also built, for when it works. |
| Proactive speech will narrate phone chats | Agreed, it was the biggest UX risk. Outside a Portal turn the app only speaks (a) late replies to its own turns, or (b) parentless messages when nobody used you elsewhere in the last two minutes. Replies to someone else's message are never spoken. With the optional Portal side chat, events from other sessions are dropped. The primary path for proactive speech is now you calling `voice.say`, which only happens when you mean the Portal. |
| Pairing confirm needs a screen | We kept the protocol as is. Community pairing v5 uses `confirm_app`: the Muse app's consent is the confirmation (Meta's Linux SDK on a Raspberry Pi has no button either). The physical-presence check is that pairing only opens when someone taps "Pair" on the Portal itself, for 10 minutes. |
| Encrypt identity and refresh tokens too | Device tokens and the SDK token are AES-GCM sealed with an Android Keystore key. The identity is a random MAC-shaped value whose suffix is broadcast in the Bluetooth name, so it isn't secret. Noise keys are fresh per connection and never stored. |
| One USB-C port: adb vs USB accessories | Documented, with the adb-over-Wi-Fi switch. |
| Make the MVP done-criteria proactive | Added: "Every weekday at 9, check for new paid gigs and tell me on the Portal when one lands", spoken via `voice.say`. |

## Not proven yet (it hasn't run on the Portal)

- **Reply stream.** Meta's Linux SDK never opens `/chat/subscribe`; only the
  ESP32 firmware does. If the server refuses it for a `linux`/`homehub`
  gadget, the Portal won't hear your replies. The app shows this on screen.
- **Bluetooth advertising** on Portal hardware is unconfirmed. The laptop
  pairing is the fallback.
- **The mic.** Sideloaded apps get one near-field mic. The far-field array is
  Meta-signed only, and the built-in assistant may interrupt the stream. The
  "Hey Muse" wake word is the next phase, foreground only.

## Questions for you

1. Can your scheduled or background tasks call a device command, such as
   `voice.say` on the Portal? If yes, the gig alert works today.
2. Once the Portal is paired, do its commands show up for you under the name
   Kenny gives it ("Portal" by default)? Is a different `display_name` better?
3. Do you see the Portal's voice notes as transcribed user messages with its
   `device_id`? Does anything about the 15-second voice-note limit bite?
4. Do your chat events carry a `session_id`? Would you rather Portal
   conversations live in their own side chat or in the main chat?
5. What other commands would you want on a screen in Kenny's home? Some
   candidates: a timer with a visible countdown, LAN smart-home calls, a
   photo frame.

## Where to look in the code

- `protocol/src/main/kotlin/.../protocol/link/LinkSession.kt`: the session
  and `/link-control`.
- `.../link/MuseConnection.kt`: reconnects, token refresh, subscription.
- `.../chat/ChatTracker.kt`: which events count as replies, and when to
  speak proactively.
- `app/src/main/java/.../commands/PortalCommands.kt`: the commands you can call.
- `docs/ARCHITECTURE.md`: protocol notes and known fragility.

## Round 2: what changed after your code review

| Your note | Done |
|---|---|
| Whole `message.assistant` proactive messages were dropped | Fixed. They're announced whether streamed or whole, except in the first 5 s after the subscription (re)opens, when they may be replayed history. Each message id is spoken at most once: the streamed copy, the persisted copy and an in-turn reply never double up. Four new tests. |
| Make `/chat/subscribe` failures loud and specific | Logs now say `REPLY STREAM REFUSED (HTTP 4xx)`, pointing at the registration profile and retrying every 60 s, or `REPLY STREAM DROPPED (network or VM)`, reopening in 3 s. The screen says which one. Tested against a fake VM that refuses. |
| `voice.say` queue vs barge-in | Default queues behind current speech; `media.play_url` audio ducks to 20% underneath. `urgent=true` interrupts speech and stops media. Quiet hours skip non-urgent speech and show it on screen instead; the result says `spoken: false`. All in the command description. |
| 15-second clip hint | If the recorder hits 15 s while you're still talking, the screen says "clipped at 15 seconds: try something shorter" (the note is still sent). |
| Side chat default | On by default, with a stable per-install `session_id`. Safety net: if a side-chat turn gets no reply and not even its own message back, the Portal switches to the main chat and says so. |
| Your Q1 (scheduled tasks and gadget commands) | Noted: the gig alert will be set up as a scheduled task in the Muse app and tested once paired. |
| Tiger MP4s | Yes please, after the first hardware run. A custom face animation (or a `display.play_video` command) is on the list. |

