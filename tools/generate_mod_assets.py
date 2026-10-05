#!/usr/bin/env python3
"""
Generates the mod's own textures (not content-pack art): the Weapons Bench block and its GUI.
    python3 tools/generate_mod_assets.py
Requires Pillow.
"""
import random
from pathlib import Path

from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/flansmod"

# Vanilla container palette
BG, LIGHT, DARK, SLOT, OUTLINE = (198, 198, 198), (255, 255, 255), (85, 85, 85), (139, 139, 139), (0, 0, 0)


def panel(d, x0, y0, x1, y1):
    d.rectangle((x0, y0, x1, y1), fill=BG)
    d.line((x0 + 1, y0, x1 - 2, y0), fill=OUTLINE); d.line((x0, y0 + 1, x0, y1 - 2), fill=OUTLINE)
    d.line((x1 - 1, y0 + 2, x1 - 1, y1 - 2), fill=OUTLINE); d.line((x0 + 2, y1 - 1, x1 - 2, y1 - 1), fill=OUTLINE)
    d.line((x0 + 1, y0 + 1, x1 - 3, y0 + 1), fill=LIGHT); d.line((x0 + 1, y0 + 1, x0 + 1, y1 - 3), fill=LIGHT)
    d.line((x1 - 2, y0 + 2, x1 - 2, y1 - 2), fill=DARK); d.line((x0 + 2, y1 - 2, x1 - 2, y1 - 2), fill=DARK)


def slot(d, x, y, size=18):
    """Vanilla slot frame; (x, y) is the top-left of the 18px frame (item renders at x+1, y+1)."""
    d.rectangle((x, y, x + size - 1, y + size - 1), fill=SLOT)
    d.line((x, y, x + size - 2, y), fill=(55, 55, 55)); d.line((x, y, x, y + size - 2), fill=(55, 55, 55))
    d.line((x + 1, y + size - 1, x + size - 1, y + size - 1), fill=LIGHT); d.line((x + size - 1, y + 1, x + size - 1, y + size - 1), fill=LIGHT)


def gui():
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 190)
    for row in range(4):
        for col in range(6):
            slot(d, 7 + col * 18, 17 + row * 18)
    # arrow
    d.polygon([(122, 50), (136, 50), (136, 46), (144, 54), (136, 62), (136, 58), (122, 58)], fill=SLOT)
    # big result slot
    slot(d, 145, 40, 26)
    for row in range(3):
        for col in range(9):
            slot(d, 7 + col * 18, 107 + row * 18)
    for col in range(9):
        slot(d, 7 + col * 18, 165)
    out = ASSETS / "textures/gui/weapons_bench.png"
    out.parent.mkdir(parents=True, exist_ok=True)
    img.save(out)


def block_textures():
    rng = random.Random(7)

    def base(colour, var=8):
        img = Image.new("RGBA", (16, 16))
        for x in range(16):
            for y in range(16):
                n = rng.randint(-var, var)
                img.putpixel((x, y), tuple(max(0, min(255, c + n)) for c in colour) + (255,))
        return img

    top = base((92, 96, 102))
    d = ImageDraw.Draw(top)
    d.rectangle((0, 0, 15, 15), outline=(60, 62, 66))
    d.rectangle((2, 6, 13, 8), fill=(40, 42, 46))      # a rifle silhouette on the work surface
    d.rectangle((9, 8, 10, 11), fill=(40, 42, 46))
    d.rectangle((11, 5, 15, 9), fill=(110, 76, 44))
    d.point((4, 5), fill=(200, 60, 40)); d.point((7, 11), fill=(190, 160, 60)); d.point((8, 12), fill=(190, 160, 60))

    side = base((110, 76, 44), 6)
    d = ImageDraw.Draw(side)
    d.rectangle((0, 0, 15, 3), fill=(80, 84, 90))
    for y in (7, 11):
        d.line((0, y, 15, y), fill=(90, 62, 36))
    d.rectangle((3, 5, 6, 9), fill=(70, 72, 78)); d.rectangle((9, 5, 12, 9), fill=(70, 72, 78))
    d.point((4, 6), fill=(150, 150, 155)); d.point((10, 6), fill=(150, 150, 155))

    bottom = base((110, 76, 44), 6)
    folder = ASSETS / "textures/block"
    folder.mkdir(parents=True, exist_ok=True)
    top.save(folder / "weapons_bench_top.png")
    side.save(folder / "weapons_bench_side.png")
    bottom.save(folder / "weapons_bench_bottom.png")


if __name__ == "__main__":
    gui()
    block_textures()
    print("Generated Weapons Bench GUI and block textures")
