#!/usr/bin/env python3
"""Regenerate `dhash_fixtures.json` — the golden hashes that pin the Kotlin reduction.

The hashes in that file are produced by **the real producer**: Zoetrope's own
`zoetrope/fingerprint.py`, running under the Pillow build that generates our sprite
fingerprints. That is the entire point. A fixture computed by a second Kotlin
implementation would only prove the Kotlin agrees with itself, and the failure mode this
guards against — a reduction that looks right and never matches — is exactly the one that
self-consistency cannot see.

Images are described by a formula rather than shipped as pixels, so the fixture file stays
a few hundred bytes instead of megabytes and there is no image decoder in the test path to
disagree about. `FixtureImages.kt` implements the same formulas; if the two ever drift, the
hashes stop matching and the test says so.

Usage (from the Zoetrope checkout, which owns fingerprint.py and its Pillow):

    cd ~/Personal/Zoetrope
    ./.venv/bin/python \\
        ~/Personal/seekr-android-sdk/seekr-core/src/test/resources/generate_dhash_fixtures.py \\
        > ~/Personal/seekr-android-sdk/seekr-core/src/test/resources/dhash_fixtures.json
"""
from __future__ import annotations

import json
import sys

from PIL import Image

from zoetrope import fingerprint

# (name, width, height, box_width, box_height)
CASES = [
    # Already the tile's exact geometry — normalize() must short-circuit, no resample at all.
    ("mosaic", 320, 180, 320, 180),
    # Plain 2x downscale: the common case, every coefficient window the same width.
    ("mosaic", 640, 360, 320, 180),
    # 2.35:1 scope framing into a 16:9 tile — the letterbox pads top and bottom.
    ("mosaic", 1280, 536, 320, 180),
    # Non-integer scale factors, so coefficient windows differ per output pixel.
    ("mosaic", 321, 181, 320, 180),
    # Portrait input: the letterbox pads left and right instead.
    ("gradient", 200, 300, 320, 180),
    # Below the tile box, i.e. upscaled into it — filterscale clamps to 1.0 here.
    ("gradient", 256, 144, 320, 180),
    ("bars", 64, 36, 320, 180),
    # Degenerate sizes: the max(1, ...) clamp and a 1-pixel-wide resample.
    ("checker", 7, 5, 320, 180),
    ("checker", 3, 2, 320, 180),
    # High-frequency content stresses the kernel far harder than film does.
    ("hifreq", 640, 360, 320, 180),
    ("hifreq", 1920, 1080, 320, 180),
    # Other tile geometries, in case the generator's grid is ever reconfigured.
    ("mosaic", 640, 360, 160, 90),
    ("gradient", 852, 480, 426, 240),
    # Flat fields: every neighbour comparison is a tie, so the whole hash must be zero.
    ("flat", 320, 180, 320, 180),
]


def pixel(name: str, x: int, y: int, w: int, h: int) -> tuple[int, int, int]:
    """Must stay byte-for-byte equivalent to `FixtureImages.kt`. Integer maths only."""
    if name == "mosaic":
        return ((x * 7 + y * 13) % 256, (x * x + y * 3) % 256, (x ^ y) % 256)
    if name == "gradient":
        return (
            (x * 255) // max(1, w - 1),
            (y * 255) // max(1, h - 1),
            (x + y) % 256,
        )
    if name == "bars":
        v = 255 if (x // 9) % 2 == 0 else 0
        return (v, v, v)
    if name == "checker":
        v = 255 if ((x // 5) + (y // 3)) % 2 == 0 else 32
        return (v, v, v)
    if name == "hifreq":
        return (
            (x * 131 + y * 197) % 256,
            (x * 37 + y * 241) % 256,
            (x * 89 + y * 53) % 256,
        )
    if name == "flat":
        return (128, 128, 128)
    raise SystemExit(f"unknown pattern {name!r}")


def build(name: str, w: int, h: int) -> Image.Image:
    img = Image.new("RGB", (w, h))
    img.putdata([pixel(name, x, y, w, h) for y in range(h) for x in range(w)])
    return img


def main() -> int:
    fixtures = []
    for name, w, h, box_w, box_h in CASES:
        img = build(name, w, h)
        value = fingerprint.hash_frame(img, box_w, box_h)
        fixtures.append(
            {
                "pattern": name,
                "width": w,
                "height": h,
                "boxWidth": box_w,
                "boxHeight": box_h,
                "hash": f"{value:016x}",
            }
        )
    json.dump(
        {
            "algo": fingerprint.ALGO,
            "producer": "zoetrope/fingerprint.py",
            "fixtures": fixtures,
        },
        sys.stdout,
        indent=2,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
