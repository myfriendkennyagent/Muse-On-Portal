# Muse on Portal

Your Muse agent, living on a Meta Portal. Tap the orb (and soon, say
"Hey Muse"), ask for anything, and hear the answer from the Portal's
speaker. Muse can also act on the Portal by itself: put a picture or a
list on the screen, make an announcement, play audio, or tell you when
a long-running task finishes.

> Community project. Not made or endorsed by Meta. Built on Meta's
> open-source [Muse Gadget SDK](https://github.com/facebookincubator/muse-gadget-sdk)
> under its [SDK token terms](https://gadgets.muse.ai/sdk-terms): personal,
> non-commercial use. Meta can change or withdraw the gadget service at any time.

## How it works

```
 Portal (this app)                                Muse (Meta's cloud VM)
 ┌──────────────────────────────┐   wss + Noise XX   ┌──────────────────────────┐
 │ face · captions · tap-to-talk │ ─────────────────▶ │ /link-control            │
 │ voice note (16 kHz WAV) ──────┼─ /chat/stream ───▶ │   link.register          │
 │ replies ◀─────────────────────┼─ /chat/subscribe ─ │   link.invoke ─┐         │
 │ on-device TTS (sherpa-onnx)   │                    │ the agent, its tools,    │
 │ commands Muse can run: ◀──────┼────────────────────┼─ scheduled tasks         │
 │  display.* voice.* media.*    │                    └──────────────────────────┘
 └──────────────────────────────┘
```

- **The agent runs in your Muse VM.** The Portal is its voice, screen and a
  set of commands it can call.
- **Voice is turn-based:** a voice note goes up, Muse transcribes it and
  replies in text, and the Portal speaks the reply with an on-device voice.
  This isn't the phone app's live voice mode: gadgets don't get that.
- **The protocol** (`protocol/`) is a Kotlin port of Meta's Linux Device SDK,
  tested byte-for-byte against Meta's own Python code and published test
  vectors. Details: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Status

| Piece | State |
|---|---|
| Protocol: pairing v5, Noise XX, link session, chat streaming, token refresh | Done, 56 tests |
| App: face, tap-to-talk, captions, spoken replies, typed messages | Done, needs a run on hardware |
| Commands: `display.draw_url`, `display.show_text`, `voice.say`, `voice.configure`, `media.play_url`, `device.health` | Done |
| Pairing over the Portal's Bluetooth | Built; Portal BLE advertising not yet verified |
| Pairing on a Linux laptop, imported to the Portal | Built (the fallback, [docs/PAIRING.md](docs/PAIRING.md)) |
| "Hey Muse" wake word | Next phase |
| LAN smart-home commands, start on boot polish | Later |

## Get it running

Laptop setup (adb, JDK, Android SDK, Claude Code) is in
[docs/LAPTOP.md](docs/LAPTOP.md).

You need: a Portal with **Settings > Debug > ADB Enabled**, a data USB-C
cable, `adb`, an SDK token from
[gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens), and the Muse
app with **Settings > Devices > Developer mode** on.

```sh
tools/portal-probe.sh                 # what can this Portal do? (optional)
tools/deploy.sh --tts --token         # build, install the app + voice, send your SDK token
```

Then pair: in the app open **Settings > Pair over Bluetooth** and add the
`MuseGadgetXXXXXX` device from the Muse app. If the Portal can't advertise
over Bluetooth, use `tools/pair-on-laptop.sh` instead. Both are in
[docs/PAIRING.md](docs/PAIRING.md).

Done looks like:

1. The Portal shows **Connected**, and Muse lists it under Devices.
2. Tap the orb: "What's on my calendar today?". You hear the answer.
3. From your phone: "Show a picture of a heron on my Portal". It appears.
4. The proactive moment: create a scheduled task **in the Muse app**:
   "Every weekday at 9, check for new paid gigs and tell me on the Portal when
   one lands". Muse announces it aloud on the Portal by calling `voice.say` on
   this device. Whether scheduled runs can call gadget commands is the open
   experiment. If they can't, a parentless check-in message is spoken too.

## Develop

```sh
./gradlew :protocol:test                  # protocol tests, no device needed
./gradlew :app:assembleDebug              # APK in app/build/outputs/apk/debug/
adb logcat -s 'Muse.*' MuseService MuseTurn MuseBle MuseSpeaker
```

[CLAUDE.md](CLAUDE.md) has the build/deploy/debug loop for coding agents.
Meta's `portal` agent skill is vendored in `.claude/skills/portal`.

## License

Apache-2.0 ([LICENSE](LICENSE)). The protocol code is ported from Meta's
Apache-2.0 Muse Gadget SDK; see [NOTICE](NOTICE).
