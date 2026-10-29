#!/usr/bin/env python3
"""Generate the Selene CAPTCHA pixel font: eight shaded cells and one blank.

The backend draws each CAPTCHA as rows of these glyphs. A glyph identifies only a shade,
so the resource pack contains no digit shapes that reveal the answer from codepoints.
"""
from pathlib import Path
import json
from PIL import Image

ROOT = Path(__file__).parent / "selene" / "resourcepack" / "assets" / "selene"
TEXTURE = ROOT / "textures" / "font" / "captcha.png"
FONT = ROOT / "font" / "captcha.json"
CELL = 8
PIXEL_BASE, BLANK = 0xE100, 0xE108
# Shades 0-3 appear only as noise; shades 4-7 also draw digits. Keep in sync with captcha.rs.
SHADES = [
    (70, 90, 110), (95, 80, 120), (80, 110, 90), (110, 100, 70),
    (235, 240, 255), (190, 225, 255), (255, 230, 170), (200, 255, 215),
]


def main() -> None:
    atlas = Image.new("RGBA", (CELL * len(SHADES), CELL), (0, 0, 0, 0))
    for index, rgb in enumerate(SHADES):
        atlas.paste(Image.new("RGBA", (CELL, CELL), rgb + (255,)), (index * CELL, 0))
    TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    FONT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(TEXTURE, optimize=True)
    pixels = "".join(chr(PIXEL_BASE + index) for index in range(len(SHADES)))
    FONT.write_text(json.dumps({"providers": [
        {"type": "bitmap", "file": "selene:font/captcha.png", "ascent": 7, "height": CELL,
         "chars": [pixels]},
        {"type": "space", "advances": {chr(BLANK): CELL + 1}},
    ]}, ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
