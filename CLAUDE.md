# CLAUDE.md

Muse on Portal: an Android app that makes a Meta Portal a voice and screen
gadget for the owner's Muse agent, using a Kotlin port of Meta's Muse Gadget
SDK protocol. Read README.md for the picture and docs/ARCHITECTURE.md before
touching `protocol/`.

The Portal skill in `.claude/skills/portal` (Meta's, vendored) has the
hardware rules: no Google services, minSdk 28 / targetSdk 29, launcher icon
as a PNG in `mipmap-xxxhdpi`, dark UI, reserve the top 64dp, 64–96dp touch
targets, the mic limits.

## Build and test

```sh
./gradlew :protocol:test                 # fast; no Android needed
./gradlew :app:testDebugUnitTest         # app logic (speech text, VAD)
./gradlew :app:assembleDebug             # app/build/outputs/apk/debug/app-debug.apk
shellcheck tools/*.sh
```

Needs JDK 17+ and the Android SDK (platform 36, build-tools 36) at
`ANDROID_HOME` or in `local.properties` (`sdk.dir=...`).

## Deploy and debug on the Portal

The Portal must be plugged in over USB-C with Settings > Debug > ADB Enabled.

```sh
tools/deploy.sh                          # build, install (-r -g), launch
tools/deploy.sh --tts --token            # first time: voice engine + SDK token
tools/portal-probe.sh                    # device report: Android, BLE, TTS, mic
adb logcat -s 'Muse.*' MuseService MuseTurn MuseBle MuseSpeaker MuseRecorder
adb exec-out screencap -p > /tmp/portal.png    # see the screen
```

If the Meta VR CLI is set up (`npx metavr mcp install claude-code`), its
`metavr` commands work too: `metavr app install`, `metavr log`,
`metavr capture screenshot`.

A healthy start logs, in order: `Noise session established`,
`sent link.register as homelink-xxxxxx`, `registered with the Muse`,
`chat subscription open`. Each command logs `invoke <command>` and how it
ended; parameters and output are never logged.

## Rules

- **Never commit secrets**: SDK tokens (`mgst_…`), `pairing.json`,
  `identity.json` from a real device, device or VM tokens. Test fixtures use
  the SDK's synthetic vectors only.
- **Registration strings live only in `RegistrationProfile.kt`.** Never
  register as device family `link` or advertise `device.ota`: the server
  pushes ESP32 firmware to those.
- **Say Muse, never Hatch** in anything a person reads. `hatch` stays only
  where the server depends on it (`hatch.metaaivm.com`, `hatch_refresh:`,
  `hatch_link`, `hatch-link-pairing-v5`), as upstream.
- **Protocol changes start from upstream.** Port the Python change, then
  regenerate `noise_reference.json` with
  `python3 tools/gen_protocol_vectors.py <sdk-checkout>` and keep the
  byte-for-byte tests passing.
- The app must stay useful without speech (no TTS engine: captions only) and
  without Bluetooth peripheral mode (laptop pairing).
- Portal UI: dark (`#1A1A1A`/`#2B2B2B`), Portal blue `#1990FF`, never pure
  black or white, text ≥ 14sp (body 18sp), touch targets ≥ 64dp (primary 96dp).
