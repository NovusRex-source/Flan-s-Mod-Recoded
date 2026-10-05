#!/usr/bin/env python3
"""
Generates the mod's own assets (not content-pack art): the Weapons Bench block and its GUI, and the vehicle field
equipment - wrench, fuel cans, fuel synthesizer and petrol station (textures, item/block models, block states, GUIs,
loot tables, crafting recipes, mineable tag).
    python3 tools/generate_mod_assets.py
Requires Pillow.
"""
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw

ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/flansmod"
DATA = Path(__file__).resolve().parent.parent / "src/main/resources/data/flansmod"

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


def weapon_menu():
    """Panel + player inventory; attachment slots are drawn by the screen because their number varies."""
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 166)
    for row in range(3):
        for col in range(9):
            slot(d, 7 + col * 18, 83 + row * 18)
    for col in range(9):
        slot(d, 7 + col * 18, 141)
    out = ASSETS / "textures/gui/weapon_menu.png"
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


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def noisy(colour, size=16, var=6, seed=1):
    rng = random.Random(seed)
    img = Image.new("RGBA", (size, size))
    for x in range(size):
        for y in range(size):
            n = rng.randint(-var, var)
            img.putpixel((x, y), tuple(max(0, min(255, c + n)) for c in colour) + (255,))
    return img


def item_icons():
    folder = ASSETS / "textures/item"
    folder.mkdir(parents=True, exist_ok=True)
    # Wrench: diagonal handle, open jaw top right.
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    steel, dark = (170, 172, 178), (90, 92, 98)
    for i in range(10):
        d.rectangle((2 + i, 13 - i, 3 + i, 14 - i), fill=steel if i % 3 else dark)
    d.rectangle((10, 2, 14, 6), fill=steel); d.rectangle((12, 1, 13, 4), fill=(0, 0, 0, 0)); d.point((14, 2), fill=dark)
    d.line((2, 14, 3, 14), fill=dark)
    img.save(folder / "wrench.png")
    # Jerry cans: body, three-bar handle, spout; colour by fuel.
    for name, body in (("empty", (92, 100, 70)), ("petrol", (170, 42, 32)), ("diesel", (196, 160, 48))):
        img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
        d = ImageDraw.Draw(img)
        dark = tuple(int(c * 0.7) for c in body)
        d.rectangle((3, 4, 12, 15), fill=body)
        d.line((3, 9, 12, 9), fill=dark); d.line((7, 5, 7, 14), fill=dark)  # pressed X ribs
        d.rectangle((4, 1, 10, 3), fill=dark); d.rectangle((5, 2, 9, 2), fill=(0, 0, 0, 0))  # handle
        d.rectangle((11, 1, 13, 4), fill=(60, 60, 64))  # spout cap
        d.rectangle((3, 15, 12, 15), fill=dark)
        img.save(folder / f"fuel_can_{name}.png")
    write(ASSETS / "items/wrench.json", {"model": {"type": "minecraft:model", "model": "flansmod:item/wrench"}})
    write(ASSETS / "models/item/wrench.json", {"parent": "minecraft:item/handheld", "textures": {"layer0": "flansmod:item/wrench"}})
    for name in ("empty", "petrol", "diesel"):
        write(ASSETS / f"models/item/fuel_can_{name}.json", {"parent": "minecraft:item/generated", "textures": {"layer0": f"flansmod:item/fuel_can_{name}"}})
    # The can's look follows its fuel (custom model data string set by FuelCanItem).
    write(ASSETS / "items/fuel_can.json", {"model": {
        "type": "minecraft:select", "property": "minecraft:custom_model_data", "index": 0,
        "cases": [{"when": t, "model": {"type": "minecraft:model", "model": f"flansmod:item/fuel_can_{t}"}} for t in ("petrol", "diesel")],
        "fallback": {"type": "minecraft:model", "model": "flansmod:item/fuel_can_empty"}}})


def machine_blocks():
    folder = ASSETS / "textures/block"
    # Fuel synthesizer: riveted steel box, front with pressure gauge, sight glass and valve, piped sides, vented top.
    side = noisy((96, 102, 96), seed=11)
    d = ImageDraw.Draw(side)
    d.rectangle((0, 0, 15, 15), outline=(60, 64, 60))
    d.rectangle((3, 0, 5, 15), fill=(150, 110, 60)); d.line((3, 0, 3, 15), fill=(120, 86, 44))  # copper pipe
    for y in (2, 13):
        for x in (1, 14):
            d.point((x, y), fill=(140, 144, 140))
    side.save(folder / "fuel_synthesizer_side.png")
    front = noisy((96, 102, 96), seed=12)
    d = ImageDraw.Draw(front)
    d.rectangle((0, 0, 15, 15), outline=(60, 64, 60))
    d.ellipse((2, 2, 7, 7), fill=(230, 230, 220), outline=(40, 40, 40)); d.line((4, 5, 6, 3), fill=(200, 30, 30))  # gauge
    d.rectangle((10, 2, 13, 12), fill=(40, 40, 40)); d.rectangle((11, 6, 12, 11), fill=(200, 150, 40))  # sight glass
    d.rectangle((2, 10, 7, 13), fill=(160, 40, 30)); d.line((4, 9, 4, 13), fill=(110, 20, 20))  # valve wheel
    front.save(folder / "fuel_synthesizer_front.png")
    top = noisy((86, 90, 86), seed=13)
    d = ImageDraw.Draw(top)
    d.rectangle((0, 0, 15, 15), outline=(60, 64, 60))
    for y in range(3, 13, 3):
        d.line((3, y, 12, y), fill=(40, 42, 40))
    top.save(folder / "fuel_synthesizer_top.png")
    write(ASSETS / "models/block/fuel_synthesizer.json", {"parent": "minecraft:block/orientable", "textures": {
        "top": "flansmod:block/fuel_synthesizer_top", "front": "flansmod:block/fuel_synthesizer_front", "side": "flansmod:block/fuel_synthesizer_side"}})

    # Petrol station: concrete base, red pump with white band and display, hose with nozzle at the side.
    noisy((150, 150, 146), seed=21).save(folder / "petrol_station_base.png")
    body = noisy((178, 40, 32), seed=22)
    d = ImageDraw.Draw(body)
    d.rectangle((0, 6, 15, 8), fill=(235, 235, 230))
    body.save(folder / "petrol_station_body.png")
    face = noisy((178, 40, 32), seed=23)
    d = ImageDraw.Draw(face)
    d.rectangle((2, 2, 13, 7), fill=(30, 34, 30)); d.text((3, 1), "88", fill=(120, 230, 120))
    d.rectangle((0, 9, 15, 10), fill=(235, 235, 230)); d.rectangle((5, 12, 10, 14), fill=(40, 40, 40))
    face.save(folder / "petrol_station_front.png")
    noisy((28, 28, 30), seed=24, var=3).save(folder / "petrol_station_hose.png")
    t = {"base": "flansmod:block/petrol_station_base", "body": "flansmod:block/petrol_station_body",
         "front": "flansmod:block/petrol_station_front", "hose": "flansmod:block/petrol_station_hose", "particle": "flansmod:block/petrol_station_body"}

    def el(frm, to, tex, front=None):
        faces = {f: {"texture": f"#{tex}"} for f in ("north", "south", "east", "west", "up", "down")}
        if front:
            faces["north"] = {"texture": f"#{front}"}
        return {"from": frm, "to": to, "faces": faces}
    write(ASSETS / "models/block/petrol_station.json", {"parent": "minecraft:block/block", "textures": t, "elements": [
        el([0, 0, 0], [16, 2, 16], "base"), el([3, 2, 4], [13, 16, 12], "body", front="front"),
        el([13, 6, 6], [15, 14, 8], "hose"), el([13.5, 2, 6.5], [14.5, 6, 7.5], "hose"), el([2, 9, 6], [3, 13, 9], "hose")]})
    for name in ("fuel_synthesizer", "petrol_station"):
        write(ASSETS / f"blockstates/{name}.json", {"variants": {f"facing={f}": dict({"model": f"flansmod:block/{name}"}, **({"y": y} if y else {}))
                                                               for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
        write(ASSETS / f"items/{name}.json", {"model": {"type": "minecraft:model", "model": f"flansmod:block/{name}"}})
        write(DATA / f"loot_table/blocks/{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"flansmod:{name}", "functions": [{"function": "minecraft:copy_components", "source": "block_entity"}]}],
            "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write(Path(__file__).resolve().parent.parent / "src/main/resources/data/minecraft/tags/block/mineable/pickaxe.json",
          {"replace": False, "values": ["flansmod:weapons_bench", "flansmod:fuel_synthesizer", "flansmod:petrol_station"]})


def machine_guis():
    def inventory(d):
        for row in range(3):
            for col in range(9):
                slot(d, 7 + col * 18, 83 + row * 18)
        for col in range(9):
            slot(d, 7 + col * 18, 141)

    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 166)
    for x, y in ((43, 21), (43, 49), (133, 49)):
        slot(d, x, y)
    d.rectangle((21, 17, 34, 70), outline=DARK); d.rectangle((155, 17, 168, 70), outline=DARK)  # gauge frames
    inventory(d)
    img.save(ASSETS / "textures/gui/fuel_synthesizer.png")

    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 166)
    for x, y in ((25, 34), (133, 34)):
        slot(d, x, y)
    d.rectangle((73, 17, 102, 74), outline=DARK)
    d.polygon([(48, 40), (62, 40), (62, 37), (70, 43), (62, 49), (62, 46), (48, 46)], fill=SLOT)  # can → tank
    d.polygon([(106, 40), (120, 40), (120, 37), (128, 43), (120, 49), (120, 46), (106, 46)], fill=SLOT)  # tank → can
    inventory(d)
    img.save(ASSETS / "textures/gui/petrol_station.png")


def recipes():
    def shaped(name, pattern, key, result, count=1):
        write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "equipment" if name in ("wrench", "fuel_can") else "misc",
                                            "pattern": pattern, "key": key, "result": {"id": f"flansmod:{result}", "count": count}})
    shaped("wrench", [" I ", " II", "I  "], {"I": "minecraft:iron_ingot"}, "wrench")
    shaped("fuel_can", ["NI ", "I I", "III"], {"I": "minecraft:iron_ingot", "N": "minecraft:iron_nugget"}, "fuel_can")
    shaped("fuel_synthesizer", ["ICI", "BFB", "IPI"], {"I": "minecraft:iron_ingot", "C": "minecraft:copper_ingot", "B": "minecraft:bucket",
                                                       "F": "minecraft:blast_furnace", "P": "minecraft:piston"}, "fuel_synthesizer")
    shaped("petrol_station", ["IGI", "RHR", "SSS"], {"I": "minecraft:iron_ingot", "G": "minecraft:glass_pane", "R": "minecraft:red_dye",
                                                     "H": "minecraft:hopper", "S": "minecraft:smooth_stone"}, "petrol_station")


if __name__ == "__main__":
    gui()
    weapon_menu()
    block_textures()
    item_icons()
    machine_blocks()
    machine_guis()
    recipes()
    print("Generated Weapons Bench, vehicle tool and fuel machine assets")
