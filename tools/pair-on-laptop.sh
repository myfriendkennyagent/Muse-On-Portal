#!/usr/bin/env bash
# Pair from a Linux laptop, then move the pairing to the Portal.
#
# Use this if the Portal can't advertise over Bluetooth LE. It runs Meta's own
# Linux Device SDK pairing (`musegadget pair`) on the laptop, in a scratch
# state directory and WITHOUT installing its service, so the laptop never
# connects as this device. The resulting identity and device tokens are then
# sent to the Portal with tools/deploy.sh and deleted from the laptop.
#
#   sudo apt install bluez python3-dbus python3-gi python3-cryptography python3-websockets git
#   tools/pair-on-laptop.sh
#
# Needs a Linux laptop with Bluetooth LE, sudo, and the Portal plugged in.
set -euo pipefail

SDK="${XDG_CACHE_HOME:-$HOME/.cache}/museportal/muse-gadget-sdk"
STATE=$(mktemp -d)
trap 'sudo rm -rf "$STATE"' EXIT

say() { printf '\033[1m==> %s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

[ "$(uname)" = Linux ] || die "this needs Linux with BlueZ (the SDK's pairing uses BlueZ over D-Bus)"
python3 -c 'import dbus, gi, cryptography' 2>/dev/null ||
  die "missing Python packages: sudo apt install python3-dbus python3-gi python3-cryptography python3-websockets"
command -v adb >/dev/null || die "adb not found"
[ "$(adb get-state 2>/dev/null)" = device ] || die "connect the Portal over USB-C first (adb devices)"

if ! grep -qE '^\s*ExchangeMTU\s*=\s*256' /etc/bluetooth/main.conf 2>/dev/null; then
  cat <<'EOF'
BlueZ needs a larger ATT MTU for the Android Muse app ("Couldn't connect" after
Wi-Fi otherwise). Add this to /etc/bluetooth/main.conf and restart bluetooth:

    [GATT]
    ExchangeMTU = 256

    sudo systemctl restart bluetooth
EOF
  read -rp "Continue anyway? [y/N] " ok; [ "$ok" = y ] || exit 1
fi

if [ -d "$SDK/.git" ]; then
  git -C "$SDK" pull -q --ff-only
else
  say "Fetching Meta's Muse Gadget SDK"
  git clone -q --depth 1 https://github.com/facebookincubator/muse-gadget-sdk "$SDK"
fi

printf 'Paste your SDK token from gadgets.muse.ai (input hidden): '
read -rs token; echo
[[ "$token" =~ ^mgst_[A-Za-z0-9_-]{43}$ ]] || die "that doesn't look like an SDK token"
printf '%s' "$token" > "$STATE/sdk_token"; chmod 600 "$STATE/sdk_token"

say "Opening Bluetooth pairing on this laptop"
echo "In the Muse app: Settings > Devices > Developer mode on, then + and pick the MuseGadget device printed below."
sudo env MUSEGADGET_STATE_DIR="$STATE" PYTHONPATH="$SDK/linux/src" python3 -m musegadget pair

sudo test -f "$STATE/pairing.json" || die "pairing didn't complete"

say "Sending the identity, pairing and SDK token to the Portal"
IMPORT="/sdcard/Android/data/com.myfriendkennyagent.museportal/files/import"
adb shell mkdir -p "$IMPORT"
for f in identity.json pairing.json sdk_token; do
  sudo cat "$STATE/$f" | adb shell "cat > $IMPORT/$f"
done
adb shell am force-stop com.myfriendkennyagent.museportal
adb shell am start -n com.myfriendkennyagent.museportal/.ui.MainActivity >/dev/null
say "Done. The Portal should show Connected within a few seconds. The laptop copies are deleted on exit."
