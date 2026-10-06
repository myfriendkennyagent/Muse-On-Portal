#!/usr/bin/env python3
"""Watch Meta's Muse Gadget SDK for protocol changes this port depends on.

Muse on Portal re-implements the SDK's wire protocol in Kotlin. If Meta
changes it upstream (pairing, the Noise transport, link.register fields, chat
streaming), the server will follow and this app can break silently. This
script compares the newest upstream commits touching those files with the
commit recorded in tools/upstream.lock.

    python3 tools/check_upstream.py            # report; exit 1 if anything changed
    python3 tools/check_upstream.py --update   # record the current upstream commit

Runs weekly in .github/workflows/upstream-watch.yml, which opens an issue.
Set GITHUB_TOKEN to avoid API rate limits.
"""

import json
import os
import sys
import urllib.request
from pathlib import Path

REPO = "facebookincubator/muse-gadget-sdk"
LOCK = Path(__file__).with_name("upstream.lock")

# The upstream files the Kotlin protocol module is ported from or mirrors.
WATCHED = [
    "linux/src/musegadget/link_client.py",
    "linux/src/musegadget/muse_api.py",
    "linux/src/musegadget/pairing.py",
    "linux/src/musegadget/ble_setup.py",
    "linux/src/musegadget/ble_framing.py",
    "linux/src/musegadget/ble_server.py",
    "linux/src/musegadget/identity.py",
    "linux/src/musegadget/service.py",
    "linux/src/musegadget/noise",
    "linux/tests/vectors",
    "linux/AGENTS.md",
    "esp32/main/noise_control.cpp",
    "esp32/components/muse/muse_chat_session.cpp",
    "esp32/components/muse/muse_chat_link.c",
    "esp32/components/muse/muse_chat_priv.h",
]


def api(path):
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/{path}")
    req.add_header("Accept", "application/vnd.github+json")
    if os.environ.get("GITHUB_TOKEN"):
        req.add_header("Authorization", f"Bearer {os.environ['GITHUB_TOKEN']}")
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.load(resp)


def commits_since(path, since_date):
    return api(f"commits?path={path}&since={since_date}&per_page=20")


def main():
    lock = json.loads(LOCK.read_text())
    head = api("commits/main")
    if "--update" in sys.argv:
        LOCK.write_text(json.dumps({"commit": head["sha"], "date": head["commit"]["committer"]["date"]}, indent=2) + "\n")
        print(f"recorded {head['sha'][:12]}")
        return 0

    changed = {}
    for path in WATCHED:
        for c in commits_since(path, lock["date"]):
            if c["sha"] == lock["commit"]:
                continue
            changed.setdefault(c["sha"], {"message": c["commit"]["message"].splitlines()[0], "paths": []})
            changed[c["sha"]]["paths"].append(path)

    if not changed:
        print(f"No protocol-relevant upstream changes since {lock['commit'][:12]} ({lock['date']}).")
        return 0
    print(f"Upstream changed protocol files since {lock['commit'][:12]} ({lock['date']}):\n")
    for sha, info in changed.items():
        print(f"- [{sha[:12]}](https://github.com/{REPO}/commit/{sha}) {info['message']}")
        for p in sorted(set(info["paths"])):
            print(f"  - `{p}`")
    print("\nReview the diffs, port what matters to protocol/, re-run tools/gen_protocol_vectors.py, "
          "then `python3 tools/check_upstream.py --update`.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
