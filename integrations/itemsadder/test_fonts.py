#!/usr/bin/env python3
import hashlib
import json
import subprocess
import sys
import unittest
from pathlib import Path
from PIL import Image

HERE = Path(__file__).parent
ASSETS = HERE / "selene/resourcepack/assets/selene"


def run(script):
    subprocess.run([sys.executable, str(HERE / script)], check=True)


class FontAssetsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        run("generate_captcha_font.py")
        run("generate_qr_font.py")

    def test_captcha_has_640_compact_deterministic_glyphs(self):
        font_path = ASSETS / "font/captcha.json"
        texture_path = ASSETS / "textures/font/captcha.png"
        before = hashlib.sha256(texture_path.read_bytes()).digest()
        run("generate_captcha_font.py")
        self.assertEqual(before, hashlib.sha256(texture_path.read_bytes()).digest())
        provider = json.loads(font_path.read_text())["providers"][0]
        self.assertEqual([64] * 10, list(map(len, provider["chars"])))
        self.assertEqual(0xE000, ord(provider["chars"][0][0]))
        self.assertEqual(0xE27F, ord(provider["chars"][-1][-1]))
        with Image.open(texture_path) as texture:
            self.assertEqual((12 * 64, 18 * 10), texture.size)

    def test_qr_has_all_exact_unique_tiles_without_clipping(self):
        font_path = ASSETS / "font/qr.json"
        texture = Image.open(ASSETS / "textures/font/qr.png").convert("RGBA")
        provider = json.loads(font_path.read_text())["providers"][0]
        chars = "".join(provider["chars"])
        self.assertEqual(list(range(0xE400, 0xE500)), list(map(ord, chars)))
        self.assertEqual((64, 128), texture.size)
        tiles = []
        for bits in range(256):
            x, y = (bits % 16) * 4, (bits // 16) * 8
            tile = texture.crop((x, y, x + 4, y + 8))
            tiles.append(tile.tobytes())
            opaque = sum(1 for py in range(8) for px in range(4) if tile.getpixel((px, py))[3])
            self.assertEqual(bits.bit_count() * 4, opaque)
        self.assertEqual(256, len(set(tiles)))


if __name__ == "__main__":
    unittest.main()
