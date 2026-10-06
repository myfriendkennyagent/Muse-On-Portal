# Pairing the Portal with your Muse

Pairing gives the Portal its own device tokens for your Muse account. It
uses Meta's community pairing (protocol v5, `confirm_app`): the Muse app
finds the device over Bluetooth LE, the two do an encrypted handshake, and
the app hands over device tokens. Your consent in the Muse app is the
confirmation, as with Meta's Linux SDK on a Raspberry Pi. Pairing only opens
when you start it on the device itself, which is what proves it's yours.

Community pairing has no manufacturer verification and can't stop an active
man-in-the-middle on the Bluetooth link. Pair at home.

Before either way:

- Get an SDK token at [gadgets.muse.ai](https://gadgets.muse.ai/settings/sdk-tokens)
  (Account > SDK tokens). Send it to the Portal with `tools/deploy.sh --token`,
  or type it into the app's Settings. It is stored encrypted with a Keystore key.
- In the Muse phone app, turn on **Settings > Devices > Developer mode**.

## Option A: Bluetooth from the Portal

1. On the Portal, open the app, tap ⚙ **Settings > Pair over Bluetooth**. The
   Portal advertises as `MuseGadgetXXXXXX` for 10 minutes.
2. In the Muse app: **Settings > Devices > +**, pick `MuseGadgetXXXXXX`.
   Muse warns that it's a community device; continue.
3. When asked for Wi-Fi, pick the network shown. The Portal is already
   online, so no password is needed.
4. Settings shows **Paired**, then the status chip turns **Connected**.

If the app says the Portal **can't advertise over Bluetooth LE**, or the
phone never sees the device, use option B. Nobody has confirmed BLE
peripheral mode on Portal hardware yet: `tools/portal-probe.sh` shows what
the Bluetooth stack reports.

Troubleshooting from the Linux SDK's notes: GATT error 133 on the phone is
usually stale Bluetooth state on the phone (toggle its Bluetooth). If pairing
gets past Wi-Fi and then says "Couldn't connect", the phone's MTU
negotiation failed; check `adb logcat -s MuseBle` for the `ATT MTU` line.

## Option B: pair on a Linux laptop, then move it to the Portal

`tools/pair-on-laptop.sh` runs Meta's own Linux SDK pairing on your laptop
in a scratch directory, **without** installing its service (so the laptop
never connects as this device). It then sends the identity, device tokens
and SDK token to the Portal over adb, and deletes the laptop copies.

```sh
sudo apt install bluez python3-dbus python3-gi python3-cryptography python3-websockets git
tools/pair-on-laptop.sh
```

The script asks for your SDK token, opens pairing, and prints the
`MuseGadgetXXXXXX` name to pick in the Muse app. The Portal then uses that
identity from now on.

The import format is the Linux SDK's own `identity.json` and `pairing.json`.
To move a pairing by hand:

```sh
adb shell mkdir -p /sdcard/Android/data/com.myfriendkennyagent.museportal/files/import
adb push identity.json pairing.json /sdcard/Android/data/com.myfriendkennyagent.museportal/files/import/
# then Settings > Import from laptop, or restart the app
```

## Unpairing

**Settings > Unpair** forgets the device tokens (the identity is kept, as
upstream). Removing the device in the Muse app does the same from the other
side: the Portal hears `link.unpaired` and drops its tokens.
