#!/usr/bin/env python3
"""Generate the deterministic Selene CAPTCHA bitmap font atlas and provider."""
from pathlib import Path
import json
import random
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).parent / "selene" / "resourcepack" / "assets" / "selene"
TEXTURE = ROOT / "textures" / "font" / "captcha.png"
FONT = ROOT / "font" / "captcha.json"
# Keep the glyph advance small enough for five digits to fit in a native
# dialog at every supported GUI scale. Minecraft derives the advance from
# the atlas cell width, so transparent padding here also becomes visible
# spacing between digits.
CELL_W, CELL_H, VARIANTS, DIGITS = 12, 18, 64, 10


def main() -> None:
    atlas = Image.new("RGBA", (CELL_W * VARIANTS, CELL_H * DIGITS), (0, 0, 0, 0))
    font = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSansCondensed-Bold.ttf", 16)
    for digit in range(DIGITS):
        for variant in range(VARIANTS):
            rng = random.Random(0x5E1E000 + digit * VARIANTS + variant)
            cell = Image.new("RGBA", (CELL_W, CELL_H), (0, 0, 0, 0))
            draw = ImageDraw.Draw(cell)
            color = (rng.randint(205, 255), rng.randint(210, 255), 255, 255)
            draw.text((rng.randint(-1, 0), rng.randint(-3, -1)), str(digit), font=font, fill=color,
                      stroke_width=1, stroke_fill=(20, 25, 45, 255))
            for _ in range(8):
                x, y = rng.randrange(CELL_W), rng.randrange(CELL_H)
                draw.point((x, y), fill=(120, 160, 220, rng.randint(80, 190)))
            angle = rng.choice((-12, -8, -4, 0, 4, 8, 12))
            cell = cell.rotate(angle, Image.Resampling.BICUBIC, center=(CELL_W / 2, CELL_H / 2))
            if variant % 5 == 0:
                cell = cell.filter(ImageFilter.GaussianBlur(0.25))
            atlas.alpha_composite(cell, (variant * CELL_W, digit * CELL_H))
    TEXTURE.parent.mkdir(parents=True, exist_ok=True)
    FONT.parent.mkdir(parents=True, exist_ok=True)
    atlas.save(TEXTURE, optimize=True)
    rows = ["".join(chr(0xE000 + digit * VARIANTS + variant) for variant in range(VARIANTS))
            for digit in range(DIGITS)]
    FONT.write_text(json.dumps({"providers": [{"type": "bitmap", "file": "selene:font/captcha.png",
                                                "ascent": 16, "height": 18, "chars": rows}]},
                                    ensure_ascii=True, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
