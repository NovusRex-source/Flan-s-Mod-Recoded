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


# ------------------------------------------------------------------------------------------- fortifications
VANILLA_JAR = Path.home() / ".gradle/caches/fabric-loom/26.3/minecraft-client.jar"


def vanilla(path):
    """A vanilla asset JSON from the Minecraft jar Loom downloaded (door/trapdoor/stairs/slab block states are long
    and identical apart from names, so they are copied and renamed). Build the project once before running this."""
    import zipfile
    with zipfile.ZipFile(VANILLA_JAR) as jar:
        return json.loads(jar.read(f"assets/minecraft/{path}"))


def renamed(data, old, new):
    return json.loads(json.dumps(data).replace(f"minecraft:block/{old}", f"flansmod:block/{new}"))


def fortification_textures():
    folder = ASSETS / "textures/block"
    rng = random.Random(31)
    # Sandbags: stacked burlap bags in a running bond, stitched seams.
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        row = y // 4
        for x in range(16):
            bx = (x + (4 if row % 2 else 0)) % 8
            edge = y % 4 == 3 or bx == 7
            base = (172, 150, 104) if not edge else (120, 100, 66)
            n = rng.randint(-8, 8) + (-10 if y % 4 == 0 else 0)
            img.putpixel((x, y), tuple(max(0, min(255, c + n)) for c in base) + (255,))
    img.save(folder / "sandbags.png")
    # Reinforced concrete: grey, form-board lines, a few rebar stains.
    img = noisy((134, 136, 134), seed=32, var=7)
    d = ImageDraw.Draw(img)
    for y in (5, 11):
        d.line((0, y, 15, y), fill=(118, 120, 118))
    for x, y in ((3, 2), (12, 8), (7, 13)):
        d.point((x, y), fill=(120, 80, 50))
    img.save(folder / "reinforced_concrete.png")
    # Embrasure front: concrete with the dark slit (the model leaves the slit open).
    img.save(folder / "bunker_embrasure.png")
    # Steel door halves and hatch: plates, rivets, a handle and a vision slot.
    for name, slot in (("bunker_door_top", True), ("bunker_door_bottom", False), ("bunker_hatch", False)):
        img = noisy((88, 94, 88), seed=hash(name) & 0xFF, var=5)
        d = ImageDraw.Draw(img)
        d.rectangle((0, 0, 15, 15), outline=(54, 58, 54))
        d.rectangle((2, 2, 13, 13), outline=(70, 74, 70))
        for x, y in ((1, 1), (14, 1), (1, 14), (14, 14)):
            d.point((x, y), fill=(150, 154, 150))
        if slot:
            d.rectangle((4, 6, 11, 7), fill=(20, 20, 20))
        elif name == "bunker_door_bottom":
            d.rectangle((11, 2, 12, 5), fill=(40, 40, 40))
        else:
            d.rectangle((6, 7, 9, 8), fill=(40, 40, 40))
        img.save(folder / f"{name}.png")
    # Barbed wire: transparent background (cutout), loops of dark wire with barbs.
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for cx, cy in ((4, 4), (11, 5), (6, 11), (12, 12)):
        d.ellipse((cx - 3, cy - 3, cx + 3, cy + 3), outline=(92, 92, 96, 255))
    for x, y in ((1, 4), (7, 4), (8, 5), (14, 5), (3, 11), (9, 11), (12, 9), (12, 15)):
        d.point((x, y), fill=(170, 170, 176, 255))
    img.save(folder / "barbed_wire.png")
    noisy((70, 72, 74), seed=33, var=5).save(folder / "czech_hedgehog.png")


def fortification_models():
    m = ASSETS / "models/block"
    b = ASSETS / "blockstates"
    i = ASSETS / "items"

    def simple(name, model=None):
        write(b / f"{name}.json", {"variants": {"": {"model": f"flansmod:block/{model or name}"}}})
        write(i / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"flansmod:block/{model or name}"}})

    for full in ("sandbags", "reinforced_concrete"):
        write(m / f"{full}.json", {"parent": "minecraft:block/cube_all", "textures": {"all": f"flansmod:block/{full}"}})
        simple(full)
        tex = {"bottom": f"flansmod:block/{full}", "side": f"flansmod:block/{full}", "top": f"flansmod:block/{full}"}
        slab, stairs = full.replace("sandbags", "sandbag") + "_slab", full.replace("sandbags", "sandbag") + "_stairs"
        write(m / f"{slab}.json", {"parent": "minecraft:block/slab", "textures": tex})
        write(m / f"{slab}_top.json", {"parent": "minecraft:block/slab_top", "textures": tex})
        state = renamed(vanilla("blockstates/stone_slab.json"), "stone_slab", slab)
        state["variants"]["type=double"]["model"] = f"flansmod:block/{full}"
        write(b / f"{slab}.json", state)
        write(i / f"{slab}.json", {"model": {"type": "minecraft:model", "model": f"flansmod:block/{slab}"}})
        for suffix in ("", "_inner", "_outer"):
            write(m / f"{stairs}{suffix}.json", {"parent": f"minecraft:block/{'stairs' if not suffix else ('inner_stairs' if suffix == '_inner' else 'outer_stairs')}", "textures": tex})
        write(b / f"{stairs}.json", renamed(vanilla("blockstates/stone_stairs.json"), "stone_stairs", stairs))
        write(i / f"{stairs}.json", {"model": {"type": "minecraft:model", "model": f"flansmod:block/{stairs}"}})

    # Embrasure: concrete below 10 px and above 13 px, the slit open (faces towards the slit drawn dark).
    t = {"c": "flansmod:block/reinforced_concrete", "particle": "flansmod:block/reinforced_concrete"}
    faces = {f: {"texture": "#c"} for f in ("north", "south", "east", "west", "up", "down")}
    write(m / "bunker_embrasure.json", {"parent": "minecraft:block/block", "textures": t, "elements": [
        {"from": [0, 0, 0], "to": [16, 10, 16], "faces": faces}, {"from": [0, 13, 0], "to": [16, 16, 16], "faces": faces},
        {"from": [0, 10, 0], "to": [3, 13, 16], "faces": faces}, {"from": [13, 10, 0], "to": [16, 13, 16], "faces": faces}]})
    write(b / "bunker_embrasure.json", {"variants": {f"facing={f}": dict({"model": "flansmod:block/bunker_embrasure"}, **({"y": y} if y else {}))
                                                     for f, y in (("north", 0), ("east", 90), ("south", 180), ("west", 270))}})
    write(i / "bunker_embrasure.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/bunker_embrasure"}})

    # Steel door and hatch: vanilla iron door/trapdoor states and templates with our textures.
    for part in ("bottom_left", "bottom_left_open", "bottom_right", "bottom_right_open", "top_left", "top_left_open", "top_right", "top_right_open"):
        write(m / f"bunker_door_{part}.json", {"parent": f"minecraft:block/door_{part}",
                                               "textures": {"bottom": "flansmod:block/bunker_door_bottom", "top": "flansmod:block/bunker_door_top"}})
    write(b / "bunker_door.json", renamed(vanilla("blockstates/iron_door.json"), "iron_door", "bunker_door"))
    write(ASSETS / "models/item/bunker_door.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "flansmod:block/bunker_door_top"}})
    write(i / "bunker_door.json", {"model": {"type": "minecraft:model", "model": "flansmod:item/bunker_door"}})
    for part in ("bottom", "top", "open"):
        write(m / f"bunker_hatch_{part}.json", {"parent": f"minecraft:block/template_trapdoor_{part}", "textures": {"texture": "flansmod:block/bunker_hatch"}})
    write(b / "bunker_hatch.json", renamed(vanilla("blockstates/iron_trapdoor.json"), "iron_trapdoor", "bunker_hatch"))
    write(i / "bunker_hatch.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/bunker_hatch_bottom"}})

    write(m / "barbed_wire.json", {"parent": "minecraft:block/cross", "textures": {"cross": "flansmod:block/barbed_wire"}})
    write(b / "barbed_wire.json", {"variants": {"": {"model": "flansmod:block/barbed_wire"}}})
    write(ASSETS / "models/item/barbed_wire.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "flansmod:block/barbed_wire"}})
    write(i / "barbed_wire.json", {"model": {"type": "minecraft:model", "model": "flansmod:item/barbed_wire"}})

    # Czech hedgehog: three steel beams crossing in the middle (rotated elements).
    t = {"s": "flansmod:block/czech_hedgehog", "particle": "flansmod:block/czech_hedgehog"}
    beam = {f: {"texture": "#s"} for f in ("north", "south", "east", "west", "up", "down")}
    write(m / "czech_hedgehog.json", {"parent": "minecraft:block/block", "textures": t, "elements": [
        {"from": [6.5, -2, 6.5], "to": [9.5, 18, 9.5], "rotation": {"origin": [8, 8, 8], "axis": "x", "angle": 45}, "faces": beam},
        {"from": [6.5, -2, 6.5], "to": [9.5, 18, 9.5], "rotation": {"origin": [8, 8, 8], "axis": "z", "angle": 45}, "faces": beam},
        {"from": [-2, 6.5, 6.5], "to": [18, 9.5, 9.5], "rotation": {"origin": [8, 8, 8], "axis": "y", "angle": 45}, "faces": beam}]})
    simple("czech_hedgehog")

    for name in ("sandbags", "sandbag_slab", "sandbag_stairs", "reinforced_concrete", "reinforced_concrete_slab", "reinforced_concrete_stairs",
                 "bunker_embrasure", "bunker_hatch", "barbed_wire", "czech_hedgehog"):
        entry = {"type": "minecraft:item", "name": f"flansmod:{name}"}
        if name.endswith("_slab"):
            entry["functions"] = [{"function": "minecraft:set_count", "count": 2, "add": False,
                                   "conditions": [{"condition": "minecraft:block_state_property", "block": f"flansmod:{name}", "properties": {"type": "double"}}]}]
        write(DATA / f"loot_table/blocks/{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [entry],
                                                                                              "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write(DATA / "loot_table/blocks/bunker_door.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "flansmod:bunker_door"}],
        "conditions": [{"condition": "minecraft:block_state_property", "block": "flansmod:bunker_door", "properties": {"half": "lower"}}, {"condition": "minecraft:survives_explosion"}]}]})
    root = Path(__file__).resolve().parent.parent / "src/main/resources/data/minecraft/tags/block"
    write(root / "mineable/pickaxe.json", {"replace": False, "values": ["flansmod:weapons_bench", "flansmod:fuel_synthesizer", "flansmod:petrol_station",
        "flansmod:reinforced_concrete", "flansmod:reinforced_concrete_slab", "flansmod:reinforced_concrete_stairs", "flansmod:bunker_embrasure",
        "flansmod:bunker_door", "flansmod:bunker_hatch", "flansmod:czech_hedgehog", "flansmod:battle_master", "flansmod:battle_spawn"]})
    write(root / "mineable/shovel.json", {"replace": False, "values": ["flansmod:sandbags", "flansmod:sandbag_slab", "flansmod:sandbag_stairs"]})
    write(root / "doors.json", {"replace": False, "values": ["flansmod:bunker_door"]})
    write(root / "trapdoors.json", {"replace": False, "values": ["flansmod:bunker_hatch"]})


def fortification_recipes():
    def shaped(name, pattern, key, count=1, result=None):
        write(DATA / f"recipe/{name}.json", {"type": "minecraft:crafting_shaped", "category": "building", "pattern": pattern, "key": key,
                                            "result": {"id": f"flansmod:{result or name}", "count": count}})
    shaped("sandbags", ["WSW", "SWS", "WSW"], {"W": "#minecraft:wool", "S": "#minecraft:sand"}, 6)
    shaped("sandbag_slab", ["SSS"], {"S": "flansmod:sandbags"}, 6)
    shaped("sandbag_stairs", ["S  ", "SS ", "SSS"], {"S": "flansmod:sandbags"}, 4)
    shaped("reinforced_concrete", ["CCC", "CIC", "CCC"], {"C": "minecraft:gray_concrete", "I": "minecraft:iron_ingot"}, 8)
    shaped("reinforced_concrete_slab", ["CCC"], {"C": "flansmod:reinforced_concrete"}, 6)
    shaped("reinforced_concrete_stairs", ["C  ", "CC ", "CCC"], {"C": "flansmod:reinforced_concrete"}, 4)
    shaped("bunker_embrasure", ["CCC", "   ", "CCC"], {"C": "flansmod:reinforced_concrete"}, 3)
    shaped("bunker_door", ["IB", "IB", "IB"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_block"}, 2)
    shaped("bunker_hatch", ["IBI", "IBI"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_block"}, 2)
    shaped("barbed_wire", ["N N", " I ", "N N"], {"N": "minecraft:iron_nugget", "I": "minecraft:iron_ingot"}, 4)
    shaped("czech_hedgehog", ["I I", " B ", "I I"], {"I": "minecraft:iron_ingot", "B": "minecraft:iron_block"}, 2)


# ------------------------------------------------------------------------------------------- battles (gamemode)
TEAM_COLORS = {"black": (30, 30, 30), "dark_blue": (0, 0, 170), "dark_green": (0, 140, 0), "dark_aqua": (0, 150, 150), "dark_red": (170, 0, 0),
               "dark_purple": (150, 0, 150), "gold": (255, 170, 0), "gray": (170, 170, 170), "dark_gray": (85, 85, 85), "blue": (85, 85, 255),
               "green": (85, 230, 85), "aqua": (85, 230, 230), "red": (230, 70, 70), "light_purple": (240, 85, 240), "yellow": (240, 240, 85),
               "white": (235, 235, 235)}


def battle_assets():
    folder = ASSETS / "textures/block"
    # Battle Master: field desk with a map on top, radio panel sides.
    top = noisy((122, 96, 62), seed=41)
    d = ImageDraw.Draw(top)
    d.rectangle((2, 2, 13, 13), fill=(214, 200, 160)); d.line((2, 7, 13, 7), fill=(120, 150, 200)); d.line((5, 2, 9, 13), fill=(120, 110, 90))
    d.point((4, 4), fill=(200, 40, 40)); d.point((11, 10), fill=(40, 60, 200)); d.point((10, 4), fill=(200, 40, 40))
    top.save(folder / "battle_master_top.png")
    side = noisy((78, 86, 66), seed=42)
    d = ImageDraw.Draw(side)
    d.rectangle((2, 3, 13, 12), fill=(52, 56, 48)); d.rectangle((3, 4, 8, 7), fill=(30, 40, 30)); d.line((4, 6, 7, 5), fill=(120, 230, 120))
    for x in (10, 12):
        d.ellipse((x - 1, 9, x + 1, 11), fill=(150, 150, 150))
    d.rectangle((0, 0, 15, 1), fill=(122, 96, 62))
    side.save(folder / "battle_master_side.png")
    noisy((110, 86, 56), seed=43).save(folder / "battle_master_bottom.png")
    write(ASSETS / "models/block/battle_master.json", {"parent": "minecraft:block/cube_bottom_top", "textures": {
        "top": "flansmod:block/battle_master_top", "side": "flansmod:block/battle_master_side", "bottom": "flansmod:block/battle_master_bottom"}})
    write(ASSETS / "blockstates/battle_master.json", {"variants": {"": {"model": "flansmod:block/battle_master"}}})
    write(ASSETS / "items/battle_master.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/battle_master"}})

    # Team flag: a 2-block pole with a cloth in the team colour (one texture + model per vanilla team colour).
    noisy((96, 72, 44), seed=44, var=5).save(folder / "team_flag_pole.png")
    for name, rgb in TEAM_COLORS.items():
        img = noisy(rgb, seed=45, var=6)
        d = ImageDraw.Draw(img)
        dark = tuple(int(c * 0.75) for c in rgb)
        d.rectangle((0, 0, 15, 15), outline=dark)
        d.rectangle((6, 4, 9, 11), fill=tuple(min(255, int(c * 1.2) + 20) for c in rgb))  # emblem
        img.save(folder / f"team_flag_{name}.png")
        pole = {f: {"texture": "#pole"} for f in ("north", "south", "east", "west", "up", "down")}
        cloth = {f: {"texture": "#cloth", "uv": [0, 0, 16, 16]} for f in ("north", "south")}
        cloth.update({f: {"texture": "#cloth", "uv": [0, 0, 1, 16]} for f in ("east", "west", "up", "down")})
        write(ASSETS / f"models/block/team_flag_{name}.json", {"parent": "minecraft:block/block", "textures": {
            "pole": "flansmod:block/team_flag_pole", "cloth": f"flansmod:block/team_flag_{name}", "particle": f"flansmod:block/team_flag_{name}"},
            "elements": [{"from": [7, 0, 7], "to": [9, 32, 9], "faces": pole}, {"from": [6, 0, 6], "to": [10, 2, 10], "faces": pole},
                         {"from": [9, 19, 7.75], "to": [25, 31, 8.25], "faces": cloth}],
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -3, 0], "scale": [0.45, 0.45, 0.45]}}})
    # Stolen (capture the flag): the bare pole.
    pole = {f: {"texture": "#pole"} for f in ("north", "south", "east", "west", "up", "down")}
    write(ASSETS / "models/block/team_flag_bare.json", {"parent": "minecraft:block/block", "textures": {
        "pole": "flansmod:block/team_flag_pole", "particle": "flansmod:block/team_flag_pole"},
        "elements": [{"from": [7, 0, 7], "to": [9, 32, 9], "faces": pole}, {"from": [6, 0, 6], "to": [10, 2, 10], "faces": pole}]})
    variants = {}
    for n in TEAM_COLORS:
        variants[f"color={n},stolen=false"] = {"model": f"flansmod:block/team_flag_{n}"}
        variants[f"color={n},stolen=true"] = {"model": "flansmod:block/team_flag_bare"}
    write(ASSETS / "blockstates/team_flag.json", {"variants": variants})

    # Border marker: a red and white striped post with a small red pennant.
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), (205, 40, 40, 255) if (y // 4) % 2 == 0 else (235, 235, 235, 255))
    img.save(folder / "battle_border.png")
    noisy((200, 30, 30), seed=46, var=8).save(folder / "battle_border_pennant.png")
    post = {f: {"texture": "#post"} for f in ("north", "south", "east", "west", "up", "down")}
    pennant = {f: {"texture": "#pennant"} for f in ("north", "south", "east", "west", "up", "down")}
    write(ASSETS / "models/block/battle_border.json", {"parent": "minecraft:block/block", "textures": {
        "post": "flansmod:block/battle_border", "pennant": "flansmod:block/battle_border_pennant", "particle": "flansmod:block/battle_border"},
        "elements": [{"from": [7, 0, 7], "to": [9, 24, 9], "faces": post}, {"from": [9, 18, 7.5], "to": [15, 23, 8.5], "faces": pennant}],
        "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, -2, 0], "scale": [0.55, 0.55, 0.55]}}})
    write(ASSETS / "blockstates/battle_border.json", {"variants": {"": {"model": "flansmod:block/battle_border"}}})
    write(ASSETS / "items/battle_border.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/battle_border"}})
    write(DATA / "recipe/battle_border.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["R", "S", "S"],
        "key": {"R": "minecraft:red_wool", "S": "minecraft:stick"}, "result": {"id": "flansmod:battle_border", "count": 4}})

    # Default spawn point: a low pad, grey rim with a cross in the team colour (one texture + model per colour).
    noisy((96, 98, 100), seed=47, var=5).save(folder / "battle_spawn_side.png")
    for name, rgb in TEAM_COLORS.items():
        img = noisy((110, 112, 114), seed=48, var=5)
        d = ImageDraw.Draw(img)
        d.rectangle((2, 2, 13, 13), fill=rgb)
        light = tuple(min(255, int(c * 1.25) + 25) for c in rgb)
        d.rectangle((6, 3, 9, 12), fill=light); d.rectangle((3, 6, 12, 9), fill=light)
        img.save(folder / f"battle_spawn_{name}.png")
        side = {f: {"texture": "#side", "uv": [1, 13, 15, 16]} for f in ("north", "south", "east", "west")}
        write(ASSETS / f"models/block/battle_spawn_{name}.json", {"parent": "minecraft:block/block", "textures": {
            "top": f"flansmod:block/battle_spawn_{name}", "side": "flansmod:block/battle_spawn_side", "particle": "flansmod:block/battle_spawn_side"},
            "elements": [{"from": [1, 0, 1], "to": [15, 3, 15], "faces": dict(side, up={"texture": "#top", "uv": [1, 1, 15, 15]}, down={"texture": "#side"})}],
            "display": {"gui": {"rotation": [30, 225, 0], "translation": [0, 2, 0], "scale": [0.7, 0.7, 0.7]}}})
    write(ASSETS / "blockstates/battle_spawn.json", {"variants": {f"color={n}": {"model": f"flansmod:block/battle_spawn_{n}"} for n in TEAM_COLORS}})
    write(ASSETS / "items/battle_spawn.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/battle_spawn_white"}})
    write(DATA / "recipe/battle_spawn.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["WWW", "SSS"],
        "key": {"W": "#minecraft:wool", "S": "minecraft:smooth_stone_slab"}, "result": {"id": "flansmod:battle_spawn"}})

    # Border wall: translucent red and white hazard stripes (render type follows from the alpha).
    img = Image.new("RGBA", (16, 16))
    for y in range(16):
        for x in range(16):
            img.putpixel((x, y), (220, 40, 40, 110) if ((x + y) // 4) % 2 == 0 else (240, 240, 240, 80))
    img.save(folder / "battle_wall.png")
    write(ASSETS / "models/block/battle_wall.json", {"parent": "minecraft:block/cube_all", "textures": {"all": "flansmod:block/battle_wall"}})
    write(ASSETS / "blockstates/battle_wall.json", {"variants": {"": {"model": "flansmod:block/battle_wall"}}})

    # Structure kit: a rolled-out blueprint.
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rectangle((1, 3, 14, 13), fill=(40, 90, 170, 255), outline=(25, 55, 110, 255))
    d.rectangle((3, 5, 8, 10), outline=(220, 235, 255, 255)); d.line((8, 8, 12, 8), fill=(220, 235, 255, 255)); d.line((12, 5, 12, 11), fill=(220, 235, 255, 255))
    d.rectangle((0, 2, 1, 14), fill=(230, 225, 200, 255)); d.rectangle((14, 2, 15, 14), fill=(230, 225, 200, 255))
    (ASSETS / "textures/item").mkdir(parents=True, exist_ok=True)
    img.save(ASSETS / "textures/item/structure.png")
    write(ASSETS / "models/item/structure.json", {"parent": "minecraft:item/generated", "textures": {"layer0": "flansmod:item/structure"}})
    write(ASSETS / "items/structure.json", {"model": {"type": "minecraft:model", "model": "flansmod:item/structure"}})
    write(ASSETS / "items/team_flag.json", {"model": {"type": "minecraft:model", "model": "flansmod:block/team_flag_white"}})

    for name in ("battle_master", "team_flag", "battle_border", "battle_spawn"):
        write(DATA / f"loot_table/blocks/{name}.json", {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [
            {"type": "minecraft:item", "name": f"flansmod:{name}"}], "conditions": [{"condition": "minecraft:survives_explosion"}]}]})
    write(DATA / "recipe/battle_master.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["GBG", "IRI", "PPP"],
        "key": {"G": "minecraft:gold_ingot", "B": "minecraft:bell", "I": "minecraft:iron_ingot", "R": "minecraft:redstone_block", "P": "#minecraft:planks"},
        "result": {"id": "flansmod:battle_master"}})
    write(DATA / "recipe/team_flag.json", {"type": "minecraft:crafting_shaped", "category": "misc", "pattern": ["SWW", "SWW", "S  "],
        "key": {"S": "minecraft:stick", "W": "#minecraft:wool"}, "result": {"id": "flansmod:team_flag"}})
    root = Path(__file__).resolve().parent.parent / "src/main/resources/data/minecraft/tags/block"
    write(root / "mineable/axe.json", {"replace": False, "values": ["flansmod:team_flag", "flansmod:battle_border"]})

    # GUIs: Battle Master panel; flag post with a 3x9 shop grid above the inventory.
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 200, 186)
    d.line((8, 27, 191, 27), fill=SLOT)
    img.save(ASSETS / "textures/gui/battle_master.png")
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 186)
    for row in range(3):
        for col in range(9):
            slot(d, 7 + col * 18, 29 + row * 18)
    for row in range(3):
        for col in range(9):
            slot(d, 7 + col * 18, 103 + row * 18)
    for col in range(9):
        slot(d, 7 + col * 18, 161)
    img.save(ASSETS / "textures/gui/team_flag.png")
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    panel(d, 0, 0, 176, 166)
    d.line((8, 28, 167, 28), fill=SLOT)
    img.save(ASSETS / "textures/gui/battle_spawn.png")


# ------------------------------------------------------------------------------------------- gear slots
def gear_slot_assets():
    """Frame for Flan's extra inventory slots and the empty-slot icons (gui sprite atlas, like vanilla's armour icons)."""
    img = Image.new("RGBA", (18, 18), (0, 0, 0, 0))
    slot(ImageDraw.Draw(img), 0, 0)
    img.save(ASSETS / "textures/gui/gear_slot.png")
    folder = ASSETS / "textures/gui/sprites/container/slot"
    folder.mkdir(parents=True, exist_ok=True)
    ink = (198, 198, 198, 110)
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))  # backpack: body, flap, straps
    d = ImageDraw.Draw(img)
    d.rectangle((4, 3, 11, 14), outline=ink); d.rectangle((5, 3, 10, 6), fill=ink); d.rectangle((6, 9, 9, 12), outline=ink)
    d.line((6, 1, 9, 1), fill=ink); d.point((5, 2), fill=ink); d.point((10, 2), fill=ink)
    img.save(folder / "backpack.png")
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))  # armour plate: a cut-corner shooter's plate
    d = ImageDraw.Draw(img)
    d.polygon([(5, 2), (10, 2), (12, 4), (12, 13), (3, 13), (3, 4)], outline=ink)
    d.line((5, 7, 10, 7), fill=ink)
    img.save(folder / "plate.png")


if __name__ == "__main__":
    gear_slot_assets()
    battle_assets()
    fortification_textures()
    fortification_models()
    fortification_recipes()
    gui()
    weapon_menu()
    block_textures()
    item_icons()
    machine_blocks()
    machine_guis()
    recipes()
    print("Generated Weapons Bench, vehicle tool and fuel machine assets")
