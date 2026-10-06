# The Portal's voice

Muse replies to gadgets in text, and Portals ship with no text-to-speech
engine (no Google services). The app speaks through Android's standard
`TextToSpeech` API, so it needs an engine installed.

## Recommended: sherpa-onnx, on-device

`tools/deploy.sh --tts` downloads and installs the
[sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) TTS engine (Apache-2.0)
with the Piper **en_US-ljspeech-medium** voice (trained on the public-domain
LJ Speech dataset), checks its SHA-256, and installs it. Its package is
`com.k2fsa.sherpa.onnx.tts.engine`. The app uses it automatically when
present: you don't have to make it the system default. It runs fully on the
Portal, with no account, key or network. The community has confirmed it
speaks on Portal hardware (PortalDevKit, `knowledge/apps/portal-tts-engine.md`).

To try another voice, pick any `sherpa-onnx-*-arm64-v8a-*-tts-engine-*.apk`
from the [prebuilt engines](https://huggingface.co/csukuangfj/sherpa-onnx-apk/tree/main/tts-engine-new),
uninstall the current one (`adb uninstall com.k2fsa.sherpa.onnx.tts.engine`)
and `adb install` the new one. All of them share one package name, so only
one can be installed at a time. Check each voice's model card for its
dataset license.

## Without an engine

Replies still show as captions, and `voice.say` tells Muse that this Portal
can't speak. `device.health` reports the engine in use.
