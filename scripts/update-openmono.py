#!/usr/bin/env python3
"""Refreshes the bundled OpenMono fonts from an OpenMono app build.

Converts the build's Regular and Bold text-subset WOFF2 files to the TTFs Android needs
(`core/src/main/res/font/openmono_regular.ttf`, `openmono_bold.ttf`) and copies its licence to
`core/src/main/assets/licenses/OpenMono-OFL.txt`, which the APK carries. Needs this fontTools
version with WOFF2 support, so the same input gives the same bytes:

    uvx --from 'fonttools[woff]==4.68.0' python scripts/update-openmono.py <app build folder>

The folder holds `woff2/OpenMonoNerd-<Style>-text.woff2` and `LICENSE`. The app ships no italic
files: the system slants the upright faces. Both fonts are converted and checked, and the licence
is read, before any file in the checkout is replaced; a failure leaves the checkout as it was.
Each font, as read and again as it will be written, must:

- be static and upright, with the weight of the file it becomes (`OpenCodeFonts.monospace`
  declares it);
- have a 0.6 em cell, with every character one cell wide except combining marks, format and
  separator characters, private-use characters and the Armenian abbreviation mark, which may be
  zero-width;
- have ASCII, box drawing, the block elements and ✓ ✗ ⚠;
- have no programming ligatures (no `calt`, `liga` or other ligature feature);
- be the text subset: a few private-use characters at most, not the Nerd Fonts icon set.
"""

import io
import os
import sys
import tempfile
import unicodedata
from pathlib import Path

import fontTools
from fontTools.ttLib import TTFont

FONTTOOLS = "4.68.0"
ROOT = Path(__file__).resolve().parent.parent
FONTS = ROOT / "core/src/main/res/font"
LICENSE = ROOT / "core/src/main/assets/licenses/OpenMono-OFL.txt"

# style in the build's file names -> (resource name, OS/2 weight)
STYLES = {
    "Regular": ("openmono_regular", 400),
    "Bold": ("openmono_bold", 700),
}
LIGATURE_FEATURES = {"liga", "clig", "dlig", "hlig", "rlig", "calt"}
REQUIRED = [*range(0x20, 0x7F), *range(0x2500, 0x25A0), ord("✓"), ord("✗"), ord("⚠")]
ZERO_WIDTH_CATEGORIES = {"Mn", "Mc", "Me", "Cf", "Zl", "Zp", "Co"}
# U+055F is punctuation by category but sits above the letter before it, as a combining mark does.
ZERO_WIDTH_CHARACTERS = {0x055F}
# The text subset has a few Powerline separators; the icon set has thousands of private-use characters.
MAX_PRIVATE_USE = 16


def check(font: TTFont, name: str, weight: int) -> None:
    def fail(message: str) -> None:
        sys.exit(f"{name}: {message}; nothing was changed")

    if "fvar" in font:
        fail("is a variable font")
    os2, head, post = font["OS/2"], font["head"], font["post"]
    if os2.usWeightClass != weight:
        fail(f"weight is {os2.usWeightClass}, expected {weight}")
    if os2.fsSelection & 1 or head.macStyle & 2 or post.italicAngle != 0:
        fail("is italic")
    if bool(os2.fsSelection & 32) != (weight >= 700) or bool(head.macStyle & 1) != (weight >= 700):
        fail("bold flags don't match the weight")
    if not post.isFixedPitch:
        fail("not marked fixed pitch")

    cmap, metrics = font.getBestCmap(), font["hmtx"].metrics
    missing = [code for code in REQUIRED if code not in cmap]
    if missing:
        fail(f"missing {len(missing)} required characters, first U+{missing[0]:04X}")
    cell = metrics[cmap[ord("m")]][0]
    if cell * 10 != head.unitsPerEm * 6:
        fail(f"the cell is {cell} of {head.unitsPerEm} units, expected 0.6 em")
    off_cell = [
        code
        for code, glyph in cmap.items()
        if metrics[glyph][0] != cell
        and not (
            metrics[glyph][0] == 0
            and (unicodedata.category(chr(code)) in ZERO_WIDTH_CATEGORIES or code in ZERO_WIDTH_CHARACTERS)
        )
    ]
    if off_cell:
        fail(f"{len(off_cell)} characters are not one cell wide, first U+{min(off_cell):04X}")
    features = {record.FeatureTag for record in font["GSUB"].table.FeatureList.FeatureRecord} if "GSUB" in font else set()
    if features & LIGATURE_FEATURES:
        fail(f"has ligature features {sorted(features & LIGATURE_FEATURES)}")
    private_use = sum(1 for code in cmap if unicodedata.category(chr(code)) == "Co")
    if private_use > MAX_PRIVATE_USE:
        fail(f"{private_use} private-use characters: this is not the text subset")


def main() -> None:
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    if fontTools.version != FONTTOOLS:
        sys.exit(f"fontTools {fontTools.version} is not {FONTTOOLS}; run it as the docstring says")
    build = Path(sys.argv[1]).expanduser()
    sources = {style: build / "woff2" / f"OpenMonoNerd-{style}-text.woff2" for style in STYLES}
    absent = [path for path in (*sources.values(), build / "LICENSE") if not path.is_file()]
    if absent:
        sys.exit(f"missing {', '.join(str(path) for path in absent)}; nothing was changed")

    outputs = []
    for style, (resource, weight) in STYLES.items():
        font = TTFont(sources[style], recalcTimestamp=False)
        check(font, sources[style].name, weight)
        font.flavor = None
        buffer = io.BytesIO()
        font.save(buffer)
        written = TTFont(io.BytesIO(buffer.getvalue()))
        check(written, f"{resource}.ttf", weight)
        note = f"{written['maxp'].numGlyphs:,} glyphs, {written['name'].getDebugName(5)}"
        outputs.append((FONTS / f"{resource}.ttf", buffer.getvalue(), note))
    licence = (build / "LICENSE").read_bytes()
    if b"SIL OPEN FONT LICENSE Version 1.1" not in licence:
        sys.exit(f"{build / 'LICENSE'} is not the SIL Open Font License 1.1; nothing was changed")
    outputs.append((LICENSE, licence, "licence"))

    with tempfile.TemporaryDirectory(dir=ROOT, prefix=".update-openmono-") as stage:
        for target, data, _ in outputs:
            (Path(stage) / target.name).write_bytes(data)
        for target, _, _ in outputs:
            os.replace(Path(stage) / target.name, target)
    for target, data, note in outputs:
        print(f"{target.relative_to(ROOT)}: {len(data):,} bytes, {note}")


if __name__ == "__main__":
    main()
