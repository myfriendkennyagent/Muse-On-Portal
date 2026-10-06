#!/usr/bin/env bash
# Check what this Portal can do before relying on it: Android version,
# Bluetooth LE peripheral mode (for pairing), text-to-speech engines, the
# microphone, and the built-in assistant that competes for it.
#
#   tools/portal-probe.sh            prints a report; paste it back to Claude
set -uo pipefail

command -v adb >/dev/null || { echo "adb not found" >&2; exit 1; }
[ "$(adb get-state 2>/dev/null)" = device ] || { echo "no Portal connected over adb" >&2; exit 1; }

sh() { adb shell "$@" 2>/dev/null | tr -d '\r'; }
section() { printf '\n## %s\n' "$*"; }

section "Device"
echo "model:        $(sh getprop ro.product.model) ($(sh getprop ro.product.device))"
echo "android:      $(sh getprop ro.build.version.release) (API $(sh getprop ro.build.version.sdk))"
echo "build:        $(sh getprop ro.build.fingerprint)"
echo "abi:          $(sh getprop ro.product.cpu.abi)"
echo "screen:       $(sh wm size | sed 's/Physical size: //')"
echo "storage free: $(sh df -h /data | awk 'NR==2 {print $4}')"

section "Bluetooth"
bt=$(sh dumpsys bluetooth_manager)
echo "$bt" | grep -iE '^\s*(enabled|state|name|address):' | head -5
echo "$bt" | grep -iE 'MultiAdvertisement|LePeripheral|isLePeripheralMode|LeExtendedAdvertising|OffloadedFilter|Peripheral' | head -8
echo "(Pairing over Bluetooth needs LE advertising/peripheral support. If unsure, try 'Pair over Bluetooth' in the app; it reports if advertising fails.)"

section "Text-to-speech engines"
sh cmd package query-services -a android.intent.action.TTS_SERVICE | grep -E 'packageName|name=' | head -10 || true
echo "default engine: $(sh settings get secure tts_default_synth)"

section "Microphone"
sh dumpsys media.audio_policy | grep -iE 'in_snd_device|Input devices|AUDIO_DEVICE_IN' | sort -u | head -12
echo "privacy (mic mute) switch: $(sh getprop persist.vendor.audio.mic_mute 2>/dev/null)"

section "Assistant apps that may hold the mic"
sh pm list packages | grep -iE 'aloha|assistant|alexa|voice|hotword|wakeword' | sed 's/package:/  /'

section "Muse on Portal"
if sh pm list packages com.myfriendkennyagent.museportal | grep -q museportal; then
  sh dumpsys package com.myfriendkennyagent.museportal | grep -E 'versionName|RECORD_AUDIO: granted' | head -3
else
  echo "  not installed yet (run tools/deploy.sh)"
fi
