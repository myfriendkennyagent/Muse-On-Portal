#!/usr/bin/env python3
"""Draw the launcher icon: a glowing orb on a dark tile.

Portal's launcher only reads a large PNG from mipmap-xxxhdpi, so this writes
a 512x512 PNG with nothing but the standard library.

    python3 tools/make_icon.py app/src/main/res/mipmap-xxxhdpi/ic_launcher.png
"""

import math
import struct
import sys
import zlib

SIZE = 512


def png(path, pixels):
    raw = b"".join(b"\x00" + bytes(row) for row in pixels)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", SIZE, SIZE, 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def lerp(a, b, t):
    return a + (b - a) * t


def main(path):
    c = SIZE / 2
    tile_r = SIZE * 0.47  # rounded tile, drawn as a circle-cornered square
    corner = SIZE * 0.22
    orb_r = SIZE * 0.27
    rows = []
    for y in range(SIZE):
        row = []
        for x in range(SIZE):
            dx, dy = x + 0.5 - c, y + 0.5 - c
            # Rounded-square tile mask with a soft edge.
            qx, qy = max(abs(dx) - (tile_r - corner), 0), max(abs(dy) - (tile_r - corner), 0)
            edge = math.hypot(qx, qy) - corner
            tile = min(max(0.5 - edge, 0), 1)
            # Background: near-black with a faint violet glow around the orb.
            d = math.hypot(dx, dy)
            glow = math.exp(-((d / (orb_r * 1.9)) ** 2))
            r, g, b = lerp(26, 70, glow), lerp(26, 52, glow), lerp(26, 140, glow)
            # The orb: violet to blue, lit from the upper left.
            if d < orb_r + 1:
                t = (dy / orb_r + 1) / 2
                orb = (lerp(150, 60, t), lerp(110, 130, t), lerp(255, 250, t))
                hl = math.exp(-(((dx + orb_r * 0.35) ** 2 + (dy + orb_r * 0.4) ** 2) / (orb_r * 0.45) ** 2))
                orb = tuple(min(255, ch + 120 * hl) for ch in orb)
                a = min(max(orb_r + 0.5 - d, 0), 1)
                r, g, b = lerp(r, orb[0], a), lerp(g, orb[1], a), lerp(b, orb[2], a)
            row += [int(r), int(g), int(b), int(255 * tile)]
        rows.append(row)
    png(path, rows)


if __name__ == "__main__":
    main(sys.argv[1])
