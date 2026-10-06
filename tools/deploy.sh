#!/usr/bin/env bash
# Build Muse on Portal and install it on a Portal connected over USB-C.
#
#   tools/deploy.sh                  build, install, launch
#   tools/deploy.sh --tts            also install the on-device voice (first time)
#   tools/deploy.sh --token          also send your SDK token to the Portal (asks for it)
#   tools/deploy.sh --pairing DIR    also send identity.json + pairing.json from DIR
#   tools/deploy.sh --no-build       install the APK that's already built
#   tools/deploy.sh --apk FILE       install a downloaded APK instead of building
#
# Needs adb (Android platform-tools) and, to build, JDK 17+ and the Android
# SDK (ANDROID_HOME). On the Portal: Settings > Debug > ADB Enabled, then
# accept the "Allow USB debugging" prompt.
set -euo pipefail

PKG=com.myfriendkennyagent.museportal
ROOT=$(cd "$(dirname "$0")/.." && pwd)
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
TTS_URL="https://huggingface.co/csukuangfj/sherpa-onnx-apk/resolve/main/tts-engine-new/1.12.22/sherpa-onnx-1.12.22-arm64-v8a-en-tts-engine-vits-piper-en_US-ljspeech-medium.apk"
TTS_SHA256=e0e7741531a60fcddaf7374a5477a175a88077334a761d133f82e5bbd7c80ae7
TTS_PKG=com.k2fsa.sherpa.onnx.tts.engine
CACHE="${XDG_CACHE_HOME:-$HOME/.cache}/museportal"
IMPORT_DIR="/sdcard/Android/data/$PKG/files/import"

build=1 tts=0 token=0 pairing_dir=""
while [ $# -gt 0 ]; do
  case "$1" in
    --tts) tts=1 ;;
    --token) token=1 ;;
    --pairing) pairing_dir="$2"; shift ;;
    --no-build) build=0 ;;
    --apk) APK="$2"; build=0; shift ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option: $1" >&2; exit 2 ;;
  esac
  shift
done

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

command -v adb >/dev/null || die "adb not found. Install Android platform-tools and put them on your PATH."

say "Looking for the Portal"
adb start-server >/dev/null
state=$(adb get-state 2>/dev/null || true)
[ "$state" = device ] || die "no device. Check the USB-C cable (data, not charge-only), Settings > Debug > ADB Enabled, and accept the prompt on the Portal. 'adb devices' shows what adb sees."
model=$(adb shell getprop ro.product.model | tr -d '\r')
sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "    $model, Android API $sdk"
[ "${sdk:-0}" -ge 28 ] || die "this app needs Android 9 (API 28) or newer"

if [ "$build" = 1 ]; then
  say "Building"
  (cd "$ROOT" && ./gradlew -q :app:assembleDebug)
fi
[ -f "$APK" ] || die "no APK at $APK"

say "Installing $(basename "$APK")"
# -g grants the runtime permissions (microphone, location for the Wi-Fi name) up front.
adb install -r -g "$APK" >/dev/null
# Lets the face come back by itself after a reboot.
adb shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow >/dev/null 2>&1 || true

if [ "$tts" = 1 ]; then
  if adb shell pm list packages "$TTS_PKG" | grep -q "$TTS_PKG"; then
    say "Voice engine already installed"
  else
    mkdir -p "$CACHE"
    engine="$CACHE/$(basename "$TTS_URL")"
    if [ ! -f "$engine" ]; then
      say "Downloading the on-device voice (sherpa-onnx, about 80 MB)"
      curl -fL --progress-bar -o "$engine.part" "$TTS_URL"
      mv "$engine.part" "$engine"
    fi
    echo "$TTS_SHA256  $engine" | sha256sum -c --quiet - || die "voice engine download is corrupt; delete $engine and retry"
    say "Installing the voice engine"
    adb install -r "$engine" >/dev/null
  fi
fi

push_secret() { # push_secret LOCAL_FILE NAME
  adb shell mkdir -p "$IMPORT_DIR"
  adb push "$1" "$IMPORT_DIR/$2" >/dev/null
}

if [ "$token" = 1 ]; then
  printf 'Paste your SDK token from gadgets.muse.ai (input hidden): '
  read -rs sdk_token; echo
  [[ "$sdk_token" =~ ^mgst_[A-Za-z0-9_-]{43}$ ]] || die "that doesn't look like an SDK token (mgst_ plus 43 characters)"
  tmp=$(mktemp); chmod 600 "$tmp"
  printf '%s' "$sdk_token" > "$tmp"
  push_secret "$tmp" sdk_token
  rm -f "$tmp"; unset sdk_token
  say "SDK token sent; the app encrypts it on import"
fi

if [ -n "$pairing_dir" ]; then
  for f in identity.json pairing.json; do [ -f "$pairing_dir/$f" ] || die "$pairing_dir/$f not found"; done
  push_secret "$pairing_dir/identity.json" identity.json
  push_secret "$pairing_dir/pairing.json" pairing.json
  say "Pairing sent. Delete the local copies once the Portal shows Connected."
fi

say "Launching"
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.ui.MainActivity" >/dev/null
echo "    Follow the log with: adb logcat -s 'Muse.*' MuseService MuseTurn MuseBle MuseSpeaker"
