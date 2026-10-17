#!/usr/bin/env python3
"""Generate all 256 deterministic 2x4 QR bitmap tiles used by QrGlyphEncoder."""
from pathlib import Path
import json
from PIL import Image, ImageDraw

ROOT = Path(__file__).parent / "selene" / "resourcepack" / "assets" / "selene"
TEXTURE = ROOT / "textures" / "font" / "qr.png"
FONT = ROOT / "font" / "qr.json"
FIRST = 0xE400
GRID = 16
MODULE = 2
CELL_W, CELL_H = 2 * MODULE, 4 * MODULE
SPACER = 0xE3FF


def main() -> None:
    atlas = Image.new("RGBA", (GRID * CELL_W, GRID * CELL_H), (255, 255, 255, 255))
    draw = ImageDraw.Draw(atlas)
    for bits in range(256):
        origin_x = (bits % GRID) * CELL_W
        origin_y = (bits // GRID) * CELL_H
        for y in range(4):
            for x in range(2):
                if bits & (1 << (y * 2 + x)):
                    draw.rectangle((origin_x + x * MODULE, origin_y + y * MODULE,
                                    origin_x + (x + 1) * MODULE - 1,
                                    origin_y + (y + 1) * MODULE - 1), fill=(0, 0, 0, 255))
    TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    FONT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(TEXTURE, optimize=True)
    rows = ["".join(chr(FIRST + row * GRID + column) for column in range(GRID))
            for row in range(GRID)]
    FONT.write_text(json.dumps({"providers": [{"type": "bitmap", "file": "selene:font/qr.png",
                                                "ascent": 9, "height": 9, "chars": rows},
                                               {"type": "space", "advances": {chr(SPACER): -1.5}}]},
                                    ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
