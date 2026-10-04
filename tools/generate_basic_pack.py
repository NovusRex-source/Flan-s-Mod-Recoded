#!/usr/bin/env python3
"""
Generates the built-in "Basic" content pack (src/main/resources/resourcepacks/basic):
gun/ammo/attachment definitions, recipes, GeckoLib models + animations, textures, icons and sounds.

It is a normal content pack; the mod code contains no guns. Re-run after editing the tables below:
    python3 tools/generate_basic_pack.py
Requires Pillow.
"""
import json
import random
import shutil
from pathlib import Path

from PIL import Image

NS = "flansbasic"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/basic"
DATA = ROOT / "data" / NS
ASSETS = ROOT / "assets" / NS

# Texture quadrants (128x128, box UV): each material is a 64x64 noisy colour block.
MATERIALS = {"metal": (0, 0), "polymer": (64, 0), "wood": (0, 64), "red": (64, 64)}
COLOURS = {"metal": (58, 61, 66), "polymer": (34, 36, 40), "wood": (122, 82, 48), "red": (200, 30, 30)}


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def cube(origin, size, mat="metal"):
    return {"origin": list(origin), "size": list(size), "uv": list(MATERIALS[mat])}


def bone(name, cubes, parent="gun", pivot=(0, 0, 0)):
    b = {"name": name, "pivot": list(pivot), "cubes": cubes}
    if parent:
        b["parent"] = parent
    return b


# ---------------------------------------------------------------------------------------------- guns
# Geometry in model pixels: barrel points north (-Z), grip bottom at y=0.
# sight: (top_y, z) of the rail; muzzle: (y, z); under: (y, z) under-barrel grip mount.
GUNS = {
    "pistol": dict(
        name="M9 Pistol", ammo="9mm", mag=15, dmg=5, rpm=400, mode="semi", reload=30, vel=10, spread=1.5,
        recoil=(2.0, 0.6), zoom=1.2, slots=["sight", "barrel"], sound="pistol",
        body=[cube((-1, 5, -6), (2, 2, 9)), cube((-0.75, 0, 0.5), (1.5, 5, 2), "polymer"),
              cube((-0.25, 3.5, -1.5), (0.5, 0.5, 2))],
        moving=("slide", [cube((-1.05, 6, -6.2), (2.1, 1.2, 9.4))]),
        mag_cubes=[cube((-0.6, -0.8, 0.7), (1.2, 1, 1.6))],
        sight=(7.2, 0), muzzle=(6, -6), under=None,
        iron=[cube((-0.4, 7.2, 1.8), (0.8, 0.6, 0.6)), cube((-0.2, 7.2, -5.6), (0.4, 0.5, 0.4))],
        recipe=["II ", " IG", "   "],
    ),
    "smg": dict(
        name="MP5 Submachine Gun", ammo="9mm", mag=30, dmg=3.5, rpm=800, mode="auto", reload=45, vel=11, spread=3,
        recoil=(0.9, 0.5), zoom=1.3, slots=["sight", "barrel", "grip"], sound="smg",
        body=[cube((-1, 4, -8), (2, 3, 12)), cube((-0.75, 0, 0), (1.5, 4, 2), "polymer"),
              cube((-0.5, 5, -12), (1, 1, 4)), cube((-0.5, 4.5, 4), (1, 1.5, 5), "polymer")],
        moving=("bolt", [cube((0.9, 5.5, -3), (0.4, 0.6, 1.5))]),
        mag_cubes=[cube((-0.6, -1, -5), (1.2, 5, 1.6), "polymer")],
        sight=(7, -2), muzzle=(5.5, -12), under=(4, -7),
        iron=[cube((-0.5, 7, 0), (1, 1, 1)), cube((-0.25, 7, -7.5), (0.5, 0.8, 0.5))],
        recipe=["III", " GI", " I "],
    ),
    "rifle": dict(
        name="M4 Assault Rifle", ammo="556", mag=30, dmg=6, rpm=650, mode="auto", reload=50, vel=14, spread=2.5,
        recoil=(1.2, 0.6), zoom=1.4, slots=["sight", "barrel", "grip"], sound="rifle",
        body=[cube((-0.5, 7, -16), (1, 1, 10)), cube((-1, 6, -8), (2, 3, 12)), cube((-1, 4, 4), (2, 4, 6), "polymer"),
              cube((-0.5, 3, -1), (1, 3, 2), "polymer"), cube((-1.1, 5.5, -11), (2.2, 2.2, 5), "polymer")],
        moving=("bolt", [cube((0.9, 7, -3), (0.4, 0.6, 2))]),
        mag_cubes=[cube((-0.6, 1, -6), (1.2, 5, 2))],
        sight=(9, -2), muzzle=(7.5, -16), under=(5.5, -9),
        iron=[cube((-0.5, 9, 0), (1, 1.2, 1.5)), cube((-0.3, 9, -10.5), (0.6, 1, 0.5))],
        recipe=["III", "GIW", " I "],
    ),
    "shotgun": dict(
        name="M870 Shotgun", ammo="12gauge", mag=6, dmg=3, pellets=8, rpm=70, mode="semi", reload=60, vel=9,
        spread=7, ads_spread=5, recoil=(4.5, 1.2), zoom=1.15, slots=["sight", "barrel"], sound="shotgun",
        lifetime=15,
        body=[cube((-0.75, 6, -16), (1.5, 1.5, 14)), cube((-1, 4.5, -2), (2, 3, 6)),
              cube((-1, 2.5, 4), (2, 3.5, 7), "wood"), cube((-0.5, 1.5, 1), (1, 3, 1.5), "wood")],
        moving=("pump", [cube((-0.9, 4.6, -11), (1.8, 1.3, 5), "wood")]),
        mag_cubes=[cube((-0.6, 4.8, -14.5), (1.2, 1.2, 3))],
        sight=(7.5, -1), muzzle=(6.75, -16), under=None,
        iron=[cube((-0.25, 7.5, -15.5), (0.5, 0.5, 0.5))],
        recipe=["III", "WG ", "   "],
    ),
    "sniper": dict(
        name="M24 Sniper Rifle", ammo="308", mag=5, dmg=18, rpm=40, mode="semi", reload=70, vel=25, spread=4,
        ads_spread=0.05, recoil=(6, 0.8), zoom=1.5, slots=["sight", "barrel"], sound="sniper", gravity=0.01,
        lifetime=60, headshot=2.5, move=0.45,
        body=[cube((-0.5, 6.5, -22), (1, 1, 14)), cube((-1, 5, -8), (2, 3, 10)), cube((-1, 3, 2), (2, 4, 8), "wood"),
              cube((-0.5, 2, -1), (1, 3, 1.5), "wood"), cube((-1.2, 4, -14), (2.4, 2.5, 6), "wood")],
        moving=("bolt", [cube((1, 7, -2), (1.5, 0.6, 0.6))]),
        mag_cubes=[cube((-0.6, 3.5, -5), (1.2, 1.5, 2))],
        sight=(8, -3), muzzle=(7, -22), under=None,
        iron=[cube((-0.3, 8, -21), (0.6, 0.6, 0.6))],
        recipe=["  I", "IID", "WG "],
    ),
}

AMMO = {
    "9mm": dict(name="9mm Rounds", per_craft=16, colour=(212, 175, 55), shape="round", recipe=["N", "G", "N"]),
    "556": dict(name="5.56mm Rounds", per_craft=16, colour=(200, 160, 50), shape="long", recipe=["NIN", " G "]),
    "12gauge": dict(name="12 Gauge Shells", per_craft=8, colour=(190, 40, 40), shape="shell", recipe=["P", "G", "N"]),
    "308": dict(name=".308 Rounds", per_craft=8, colour=(180, 150, 60), shape="long", recipe=["NIN", "NGN"]),
}

# slot, stats, recipe and a geometry factory: (gun geometry) -> cubes
ATTACHMENTS = {
    "red_dot": dict(name="Red Dot Sight", slot="sight", stats={"ads_zoom": 1.6, "spread_multiplier": 0.85, "ads_height": 1.2},
                    recipe=["IGI", " R "]),
    "scope_4x": dict(name="4x Scope", slot="sight",
                     stats={"ads_zoom": 4.0, "spread_multiplier": 0.9, "ads_move_speed_multiplier": 0.8, "ads_height": 1.8},
                     recipe=["IGI", "I I"]),
    "suppressor": dict(name="Suppressor", slot="barrel",
                       stats={"damage_multiplier": 0.9, "hide_tracer": True, "shoot_sound": f"{NS}:gun.suppressed"},
                       recipe=["W", "I", "I"]),
    "compensator": dict(name="Compensator", slot="barrel", stats={"recoil_multiplier": 0.7}, recipe=["N N", " I "]),
    "vertical_grip": dict(name="Vertical Grip", slot="grip", stats={"recoil_multiplier": 0.8, "spread_multiplier": 0.85},
                          recipe=["W", "I"]),
}


def attachment_cubes(name, g):
    top, sz = g["sight"]
    my, mz = g["muzzle"]
    if name == "red_dot":
        return [cube((-1, top, sz - 1.5), (2, 0.5, 3)), cube((-1, top + 0.5, sz - 1), (2, 2, 0.4)),
                cube((-0.3, top + 1.2, sz - 0.9), (0.6, 0.6, 0.2), "red")]
    if name == "scope_4x":
        return [cube((-0.5, top, sz - 3), (1, 0.6, 1)), cube((-0.5, top, sz + 1), (1, 0.6, 1)),
                cube((-0.9, top + 0.6, sz - 5), (1.8, 1.8, 9), "polymer")]
    if name == "suppressor":
        return [cube((-0.9, my - 0.9, mz - 7), (1.8, 1.8, 7), "polymer")]
    if name == "compensator":
        return [cube((-0.7, my - 0.7, mz - 2), (1.4, 1.4, 2))]
    if name == "vertical_grip" and g["under"]:
        uy, uz = g["under"]
        return [cube((-0.5, uy - 3, uz), (1, 3, 1.2), "polymer")]
    return None


def gun_model(gid, g):
    bones = [bone("gun", [], parent=None), bone("body", g["body"]),
             bone(g["moving"][0], g["moving"][1]), bone("magazine", g["mag_cubes"]),
             bone("default_sight", g["iron"])]
    for aid, a in ATTACHMENTS.items():
        if a["slot"] in g["slots"]:
            cubes = attachment_cubes(aid, g)
            if cubes:
                bones.append(bone(f"attachment_{aid}", cubes))
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{gid}", "texture_width": 128, "texture_height": 128},
        "bones": bones}]}


def gun_animations(g):
    moving = g["moving"][0]
    kick = {"bolt": [0, 0, 1.5], "slide": [0, 0, 1.5], "pump": [0, 0, 2.5]}[moving]
    reload_s = g["reload"] / 20
    shoot_len = max(0.12, min(60 / g["rpm"], 0.6))
    return {"format_version": "1.8.0", "animations": {
        "shoot": {"animation_length": shoot_len, "bones": {
            "gun": {"position": {"0.0": [0, 0, 0], "0.03": [0, 0.1, 0.6], str(round(shoot_len, 3)): [0, 0, 0]},
                    "rotation": {"0.0": [0, 0, 0], "0.03": [-3, 0, 0], str(round(shoot_len, 3)): [0, 0, 0]}},
            moving: {"position": {"0.0": [0, 0, 0], "0.04": kick, str(round(shoot_len, 3)): [0, 0, 0]}}}},
        "reload": {"animation_length": reload_s, "bones": {
            "gun": {"rotation": {"0.0": [0, 0, 0], str(round(reload_s * 0.2, 3)): [15, 0, -20],
                                 str(round(reload_s * 0.8, 3)): [15, 0, -20], str(reload_s): [0, 0, 0]}},
            "magazine": {"position": {"0.0": [0, 0, 0], str(round(reload_s * 0.3, 3)): [0, -8, 0],
                                      str(round(reload_s * 0.6, 3)): [0, -8, 0], str(round(reload_s * 0.75, 3)): [0, 0, 0]}}}},
    }}


def gun_definition(gid, g):
    long_gun = g["muzzle"][1] <= -16
    gui_scale = 0.45 if g["muzzle"][1] <= -20 else 0.55 if long_gun else 0.75
    sight_top = g["sight"][0] + 1  # line of sight ~1px above the rail
    d = {
        "name": g["name"],
        "model": {"texture": f"{NS}:textures/gun/basic.png"},
        "damage": g["dmg"], "rpm": g["rpm"], "fire_mode": g["mode"], "magazine": g["mag"],
        "reload_ticks": g["reload"], "velocity": g["vel"], "spread": g["spread"],
        "ads_spread": g.get("ads_spread", round(g["spread"] / 5, 2)), "ads_zoom": g["zoom"],
        "ads_move_speed": g.get("move", 0.6),
        "recoil": {"pitch": g["recoil"][0], "yaw": g["recoil"][1]},
        "ammo": {"item": f"{NS}:{g['ammo']}", "rounds_per_item": 1},
        "sounds": {"shoot": f"{NS}:gun.{g['sound']}.shoot", "reload": f"{NS}:gun.reload", "empty": f"{NS}:gun.empty"},
        "attachment_slots": g["slots"],
        "display": {
            "gui": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "fixed": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "ads": {"translation": [0, round(6.2 + (10 - sight_top), 2), -6]},
        },
    }
    for key, field in (("pellets", "pellets"), ("gravity", "gravity"), ("lifetime", "lifetime_ticks"), ("headshot", "headshot_multiplier")):
        if key in g:
            d[field] = g[key]
    return d


# ------------------------------------------------------------------------------------------- textures
def gun_texture(path: Path):
    rng = random.Random(1)
    img = Image.new("RGBA", (128, 128))
    for mat, (ox, oy) in MATERIALS.items():
        base = COLOURS[mat]
        for x in range(64):
            for y in range(64):
                n = rng.randint(-6, 6) + (8 if mat == "wood" and (y // 2) % 5 == 0 else 0)
                img.putpixel((ox + x, oy + y), tuple(max(0, min(255, c + n)) for c in base) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def icon(path: Path, pixels):
    """pixels: dict (x, y) -> rgb on a 16x16 transparent canvas."""
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for (x, y), c in pixels.items():
        img.putpixel((x, y), tuple(c) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def ammo_icon(a):
    px = {}
    brass, tip = a["colour"], (150, 110, 70)
    rounds = [(4, 4), (8, 3), (11, 5)] if a["shape"] != "shell" else [(4, 3), (9, 4)]
    for rx, ry in rounds:
        length = {"round": 6, "long": 9, "shell": 8}[a["shape"]]
        width = 3 if a["shape"] == "shell" else 2
        for dy in range(length):
            for dx in range(width):
                if a["shape"] == "shell":
                    c = (200, 170, 60) if dy >= length - 2 else shade(brass, 1.0 if dx == 0 else 0.8)
                else:
                    c = shade(tip, 1.0 if dx == 0 else 0.8) if dy < 2 else shade(brass, 1.0 if dx == 0 else 0.8)
                px[(rx + dx, ry + dy)] = c
    return px


def attachment_icon(aid):
    px = {}
    dark, mid, red = (40, 42, 46), (70, 73, 80), (220, 40, 40)

    def rect(x0, y0, x1, y1, c):
        for x in range(x0, x1 + 1):
            for y in range(y0, y1 + 1):
                px[(x, y)] = c

    if aid == "red_dot":
        rect(3, 11, 12, 12, dark); rect(5, 5, 10, 10, mid); rect(6, 6, 9, 9, (120, 160, 170)); px[(7, 7)] = red; px[(8, 8)] = red
    elif aid == "scope_4x":
        rect(1, 6, 14, 9, dark); rect(0, 5, 2, 10, mid); rect(13, 5, 15, 10, mid); rect(5, 10, 6, 12, dark); rect(10, 10, 11, 12, dark)
    elif aid == "suppressor":
        rect(1, 6, 14, 9, dark); rect(1, 6, 14, 6, mid)
    elif aid == "compensator":
        rect(4, 5, 11, 10, mid); rect(5, 6, 6, 9, dark); rect(9, 6, 10, 9, dark)
    elif aid == "vertical_grip":
        rect(3, 2, 12, 4, mid); rect(6, 4, 9, 14, dark); rect(6, 4, 6, 14, mid)
    return px


def item_model(name, texture_kind):
    write(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write(ASSETS / "models" / "item" / f"{name}.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{texture_kind}/{name}"}})


# ------------------------------------------------------------------------------------------- recipes
KEYS = {"I": "minecraft:iron_ingot", "G": "minecraft:gunpowder", "W": "#minecraft:planks", "N": "minecraft:iron_nugget",
        "D": "minecraft:diamond", "R": "minecraft:redstone", "P": "minecraft:paper", "C": "minecraft:copper_ingot"}


def shaped(name, pattern, result):
    pattern = [row for row in pattern if row.strip()] or pattern
    width = max(len(r) for r in pattern)
    pattern = [r.ljust(width) for r in pattern]
    used = {ch for row in pattern for ch in row if ch != " "}
    write(DATA / "recipe" / f"{name}.json", {
        "type": "minecraft:crafting_shaped", "category": "equipment",
        "pattern": pattern, "key": {k: KEYS[k] for k in sorted(used)}, "result": result})


# ------------------------------------------------------------------------------------------- sounds
def sounds():
    def s(name, volume=1.0, pitch=1.0):
        return {"name": f"minecraft:{name}", "volume": volume, "pitch": pitch}
    events = {
        "gun.pistol.shoot": [s("fireworks/blast1", 0.9, 1.5)],
        "gun.smg.shoot": [s("fireworks/blast1", 0.7, 1.8)],
        "gun.rifle.shoot": [s("fireworks/largeblast1", 1.0, 1.4)],
        "gun.shotgun.shoot": [s("random/explode1", 0.6, 1.6), s("random/explode2", 0.6, 1.6)],
        "gun.sniper.shoot": [s("fireworks/largeblast1", 1.5, 0.8)],
        "gun.suppressed": [s("item/crossbow/shoot1", 0.6, 1.7), s("item/crossbow/shoot2", 0.6, 1.7)],
        "gun.reload": [s("item/crossbow/loading_middle1", 0.8, 1.2)],
        "gun.empty": [s("random/click", 0.6, 1.8)],
    }
    write(ASSETS / "sounds.json", {k: {"sounds": v, "subtitle": f"subtitles.{NS}.{k}"} for k, v in events.items()})
    return events


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    write(ROOT / "pack.mcmeta", {
        "pack": {"description": "Flan's Mod: Recoded - standard guns, ammo and attachments", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Flan's Basic Weapons", "icon": f"{NS}:rifle"}})

    gun_texture(ASSETS / "textures" / "gun" / "basic.png")
    lang = {}

    for gid, g in GUNS.items():
        write(DATA / "flansmod" / "guns" / f"{gid}.json", gun_definition(gid, g))
        write(ASSETS / "geckolib" / "models" / "gun" / f"{gid}.geo.json", gun_model(gid, g))
        write(ASSETS / "geckolib" / "animations" / "gun" / f"{gid}.animation.json", gun_animations(g))
        shaped(gid, g["recipe"], {"id": "flansmod:gun", "components": {"flansmod:gun": f"{NS}:{gid}", "flansmod:ammo": 0}})

    for aid, a in AMMO.items():
        write(DATA / "flansmod" / "ammo" / f"{aid}.json", {"name": a["name"], "icon": f"{NS}:{aid}"})
        item_model(aid, "ammo")
        icon(ASSETS / "textures" / "item" / "ammo" / f"{aid}.png", ammo_icon(a))
        shaped(f"ammo_{aid}", a["recipe"], {"id": "flansmod:ammo", "count": a["per_craft"], "components": {
            "flansmod:ammo_type": f"{NS}:{aid}", "minecraft:item_model": f"{NS}:{aid}"}})

    for aid, a in ATTACHMENTS.items():
        write(DATA / "flansmod" / "attachments" / f"{aid}.json", {"name": a["name"], "slot": a["slot"], "icon": f"{NS}:{aid}", **a["stats"]})
        item_model(aid, "attachment")
        icon(ASSETS / "textures" / "item" / "attachment" / f"{aid}.png", attachment_icon(aid))
        shaped(f"attachment_{aid}", a["recipe"], {"id": "flansmod:attachment", "components": {
            "flansmod:attachment": f"{NS}:{aid}", "minecraft:item_model": f"{NS}:{aid}"}})

    for event in sounds():
        lang[f"subtitles.{NS}.{event}"] = "Gunshot" if "shoot" in event or "suppressed" in event else \
            "Gun reloads" if "reload" in event else "Gun clicks"
    write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(GUNS)} guns, {len(AMMO)} ammo types, {len(ATTACHMENTS)} attachments in {ROOT}")


if __name__ == "__main__":
    main()
