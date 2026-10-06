# Setting up your Linux laptop

Everything that touches the Portal runs from a laptop with the Portal on
USB-C. These steps are for Debian or Ubuntu; a Mac works the same way with
Homebrew (`brew install android-platform-tools openjdk@17`).

## 1. Tools

```sh
sudo apt install adb openjdk-17-jdk git curl shellcheck
# adb "no permissions" on Linux? This adds the udev rules:
sudo apt install android-sdk-platform-tools-common
```

To build the APK yourself you also need the Android SDK (about 1 GB):

```sh
mkdir -p ~/Android/Sdk/cmdline-tools && cd ~/Android/Sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip -q commandlinetools-linux-*.zip && mv cmdline-tools latest
export ANDROID_HOME=~/Android/Sdk    # add this to ~/.bashrc
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platforms;android-36" "build-tools;36.0.0"
```

Don't want the SDK? Every push builds the APK in GitHub Actions: download
`muse-on-portal-debug-apk` from the latest run on the Actions tab and use
`tools/deploy.sh --apk app-debug.apk`.

## 2. The repo

```sh
git clone https://github.com/myfriendkennyagent/Muse-On-Portal.git
cd Muse-On-Portal
git checkout claude/trusting-brown-pgntpr   # until it's merged
```

## 3. The Portal

1. On the Portal: **Settings > Debug > ADB Enabled** (enter the PIN if asked).
2. Plug it into the laptop with a data USB-C cable; tap **Allow** on the Portal.
3. `adb devices` should list it as `device`. If it doesn't, re-tap ADB Enabled
   (the toggle can race the cable).

```sh
tools/portal-probe.sh            # what this Portal can do
tools/deploy.sh --tts --token    # build, install app + voice, send your SDK token
```

Then pair (docs/PAIRING.md).

## 4. Claude Code on the laptop

To let Claude install, read logs and take screenshots on the Portal, run
Claude Code in the repo on the laptop:

```sh
cd Muse-On-Portal
claude remote-control     # shows up in the Claude Code app; or just `claude`
```

It reads CLAUDE.md and the vendored Portal skill automatically. For Meta's
device tools as well: `npx metavr mcp install claude-code`.

The Portal has one USB-C port: if you ever plug in a USB accessory, switch
adb to Wi-Fi first (`adb tcpip 5555`, unplug, `adb connect <portal-ip>:5555`).
