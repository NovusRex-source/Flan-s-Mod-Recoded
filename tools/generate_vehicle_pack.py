#!/usr/bin/env python3
"""
Generates the built-in "Vehicles" content pack (src/main/resources/resourcepacks/vehicles): driveable vehicles in the
spirit of the original Flan's Mod vehicle packs - a jeep, a Humvee with a .50 cal turret and an M1 Abrams tank - plus
their hit-box parts, upgrades, mounted guns, tank shells, vehicle parts and Weapons Bench recipes, GeckoLib models,
textures and item icons.

It is a normal content pack; the mod code contains no vehicles. Edit the tables below and re-run:
    python3 tools/generate_vehicle_pack.py
Requires Pillow. The Humvee/tank machine guns fire .50 BMG from the Basic pack (flansbasic:50bmg).

Vehicle space is [right, up, forward] in blocks, origin at the centre of the footprint on the ground, matching the
`position`/`pivot`/`muzzle` fields of vehicle definitions. Models are built facing north (-Z) like guns.
"""
import json
import random
import shutil
from pathlib import Path

from PIL import Image

NS = "flansvehicles"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/vehicles"
DATA = ROOT / "data" / NS
ASSETS = ROOT / "assets" / NS


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


# ------------------------------------------------------------------------------------------- materials
# Vehicle texture: 128x64, 16x16 noisy tiles. Every cube face maps a whole tile (per-face UV), so big vehicle
# cubes need no huge box-UV layout.
TILE = 16
MATERIALS = {"olive": (0, 0), "olive_dark": (1, 0), "tan": (2, 0), "sand": (3, 0), "tyre": (4, 0), "steel": (5, 0),
             "metal": (6, 0), "glass": (7, 0), "canvas": (0, 1), "black": (1, 1), "light": (2, 1), "red": (3, 1),
             "track": (4, 1), "flash": (5, 1), "seat": (6, 1), "white": (7, 1)}
COLOURS = {"olive": (86, 96, 60), "olive_dark": (62, 70, 44), "tan": (176, 152, 110), "sand": (196, 176, 130),
           "tyre": (28, 28, 30), "steel": (120, 124, 130), "metal": (60, 63, 68), "glass": (120, 160, 175),
           "canvas": (120, 112, 80), "black": (16, 16, 18), "light": (250, 240, 200), "red": (170, 30, 30),
           "track": (48, 46, 44), "flash": (255, 226, 130), "seat": (70, 52, 38), "white": (220, 220, 220)}


def vehicle_texture(path: Path):
    rng = random.Random(7)
    img = Image.new("RGBA", (8 * TILE, 2 * TILE))
    for mat, (tx, ty) in MATERIALS.items():
        for x in range(TILE):
            for y in range(TILE):
                n = rng.randint(-7, 7)
                if mat == "tyre" and y % 4 == 0 or mat == "track" and x % 4 == 0:
                    n -= 12  # treads
                if x in (0, TILE - 1) or y in (0, TILE - 1):
                    n -= 10  # dark panel edges
                img.putpixel((tx * TILE + x, ty * TILE + y), tuple(max(0, min(255, c + n)) for c in COLOURS[mat]) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


# ------------------------------------------------------------------------------------------- geometry
def box(r0, u0, f0, r1, u1, f1, mat="olive"):
    return (min(r0, r1), min(u0, u1), min(f0, f1), max(r0, r1), max(u0, u1), max(f0, f1), mat)


def cube(b):
    """Vehicle-space box -> GeckoLib cube. Bedrock geometry is mirrored in X by GeckoLib, so +X is the vehicle's left."""
    r0, u0, f0, r1, u1, f1, mat = b
    tx, ty = MATERIALS[mat]
    face = {"uv": [tx * TILE, ty * TILE], "uv_size": [TILE, TILE]}
    return {"origin": [round(-r1 * 16, 3), round(u0 * 16, 3), round(-f1 * 16, 3)],
            "size": [round((r1 - r0) * 16, 3), round((u1 - u0) * 16, 3), round((f1 - f0) * 16, 3)],
            "uv": {f: dict(face) for f in ("north", "south", "east", "west", "up", "down")}}


def pivot(r, u, f):
    return [round(-r * 16, 3), round(u * 16, 3), round(-f * 16, 3)]


class Model:
    def __init__(self):
        self.bones = []

    def bone(self, name, boxes, parent="body", at=(0, 0, 0)):
        b = {"name": name, "pivot": pivot(*at), "cubes": [cube(x) for x in boxes]}
        if parent:
            b["parent"] = parent
        self.bones.append(b)
        return self

    def wheel(self, name, r, f, radius, width, steer=False, mat="tyre", parent="body"):
        """A wheel spinning around its axle; front wheels sit in a `steer_*` bone that turns with the steering."""
        hub = box(r - width / 2 - 0.02, radius - radius * 0.45, f - radius * 0.45, r + width / 2 + 0.02, radius + radius * 0.45,
                  f + radius * 0.45, "steel")
        tyre = box(r - width / 2, 0, f - radius, r + width / 2, 2 * radius, f + radius, mat)
        if steer:
            self.bone(f"steer_{name}", [], parent=parent, at=(r, radius, f))
            parent = f"steer_{name}"
        return self.bone(f"wheel_{name}", [tyre, hub], parent=parent, at=(r, radius, f))

    def geo(self, ident):
        return {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": f"geometry.{ident}", "texture_width": 8 * TILE, "texture_height": 2 * TILE},
            "bones": [{"name": "body", "pivot": [0, 0, 0], "cubes": []}] + self.bones}]}

    def boxes(self):
        """All boxes back in vehicle space (for the item icon), without upgrades and the muzzle flash."""
        out = []
        for b in self.bones:
            if b["name"].startswith("upgrade_") or b["name"] == "muzzle_flash":
                continue
            for c in b["cubes"]:
                x0, y0, z0 = c["origin"]
                sx, sy, sz = c["size"]
                mat = next(k for k, v in MATERIALS.items() if [v[0] * TILE, v[1] * TILE] == c["uv"]["north"]["uv"])
                out.append(box(-(x0 + sx) / 16, y0 / 16, -(z0 + sz) / 16, -x0 / 16, (y0 + sy) / 16, -z0 / 16, mat))
        return out


def jeep_model(paint="olive"):
    m = Model()
    m.bone("hull", [
        box(-0.85, 0.45, -1.6, 0.85, 0.95, 1.55, paint),          # tub
        box(-0.8, 0.95, 0.55, 0.8, 1.15, 1.55, paint),            # bonnet
        box(-0.55, 0.6, 1.55, 0.55, 1.05, 1.62, "black"),         # grille
        box(-0.5, 0.8, 1.62, -0.3, 0.95, 1.66, "light"), box(0.3, 0.8, 1.62, 0.5, 0.95, 1.66, "light"),
        box(-1.0, 0.75, 0.6, -0.85, 0.82, 1.6, paint), box(0.85, 0.75, 0.6, 1.0, 0.82, 1.6, paint),     # front fenders
        box(-1.0, 0.75, -1.5, -0.85, 0.82, -0.6, paint), box(0.85, 0.75, -1.5, 1.0, 0.82, -0.6, paint),  # rear fenders
        box(-0.8, 1.15, 0.45, 0.8, 1.75, 0.52, "glass"),          # windscreen
        box(-0.8, 1.15, 0.45, -0.72, 1.75, 0.52, paint), box(0.72, 1.15, 0.45, 0.8, 1.75, 0.52, paint),
        box(-0.7, 0.95, -0.35, -0.1, 1.05, 0.25, "seat"), box(0.1, 0.95, -0.35, 0.7, 1.05, 0.25, "seat"),
        box(-0.7, 1.05, -0.4, -0.1, 1.6, -0.3, "seat"), box(0.1, 1.05, -0.4, 0.7, 1.6, -0.3, "seat"),
        box(-0.75, 0.95, -1.4, 0.75, 1.05, -0.8, "seat"),
        box(-0.5, 1.0, 0.3, -0.45, 1.4, 0.35, "black"), box(-0.65, 1.35, 0.28, -0.3, 1.4, 0.38, "black"),  # steering wheel
        box(0.95, 0.6, -1.5, 1.05, 1.3, -0.9, paint),             # spare wheel mount
        box(-0.3, 0.9, -1.75, 0.3, 1.5, -1.6, "tyre"),           # spare wheel
    ])
    m.bone("upgrade_armor_kit", [
        box(-0.9, 0.5, -1.5, -0.85, 1.25, 0.5, "olive_dark"), box(0.85, 0.5, -1.5, 0.9, 1.25, 0.5, "olive_dark"),   # door plates
        box(-0.7, 0.55, 1.62, 0.7, 0.62, 1.75, "metal"), box(-0.7, 0.55, 1.62, -0.62, 1.1, 1.75, "metal"),
        box(0.62, 0.55, 1.62, 0.7, 1.1, 1.75, "metal"),                                                              # bull bar
    ])
    m.bone("upgrade_jerry_cans", [box(-0.75, 0.95, -1.78, -0.4, 1.45, -1.6, "olive_dark"), box(0.4, 0.95, -1.78, 0.75, 1.45, -1.6, "olive_dark")])
    m.bone("upgrade_engine_tuning", [box(-0.25, 1.15, 0.8, 0.25, 1.28, 1.3, "black")])                            # hood scoop
    for name, r, f in (("fl", -0.75, 1.05), ("fr", 0.75, 1.05), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)):
        m.wheel(name, r, f, 0.38, 0.3, steer=f > 0)
    return m


def humvee_model(paint="sand"):
    m = Model()
    m.bone("hull", [
        box(-1.1, 0.5, -1.9, 1.1, 1.2, 1.9, paint),               # lower body
        box(-1.05, 1.2, -1.6, 1.05, 1.95, 0.6, paint),             # cabin
        box(-1.0, 1.2, 0.6, 1.0, 1.35, 1.95, paint),               # bonnet
        box(-0.95, 1.3, 0.55, 0.95, 1.85, 0.62, "glass"),          # windscreen
        box(-1.07, 1.35, -1.2, -1.04, 1.8, 0.4, "glass"), box(1.04, 1.35, -1.2, 1.07, 1.8, 0.4, "glass"),
        box(-0.85, 0.65, 1.9, 0.85, 1.15, 2.0, "black"),           # grille
        box(-0.8, 0.95, 2.0, -0.55, 1.1, 2.03, "light"), box(0.55, 0.95, 2.0, 0.8, 1.1, 2.03, "light"),
        box(-1.2, 1.0, -1.9, -1.1, 1.08, 1.85, paint), box(1.1, 1.0, -1.9, 1.2, 1.08, 1.85, paint),
        box(-0.75, 1.95, -0.85, 0.75, 2.0, 0.05, "olive_dark"),    # roof ring base
    ])
    m.bone("upgrade_armor_kit", [
        box(-1.13, 0.6, -1.5, -1.07, 1.3, 0.5, "olive_dark"), box(1.07, 0.6, -1.5, 1.13, 1.3, 0.5, "olive_dark"),
        box(-0.9, 0.55, 2.0, 0.9, 0.65, 2.15, "metal"), box(-0.9, 0.55, 2.0, -0.8, 1.2, 2.15, "metal"), box(0.8, 0.55, 2.0, 0.9, 1.2, 2.15, "metal"),
    ])
    m.bone("upgrade_jerry_cans", [box(-0.9, 1.2, -2.0, -0.5, 1.7, -1.9, "olive_dark"), box(0.5, 1.2, -2.0, 0.9, 1.7, -1.9, "olive_dark")])
    m.bone("upgrade_engine_tuning", [box(-0.3, 1.35, 1.0, 0.3, 1.5, 1.6, "black")])
    for name, r, f in (("fl", -0.92, 1.25), ("fr", 0.92, 1.25), ("rl", -0.92, -1.25), ("rr", 0.92, -1.25)):
        m.wheel(name, r, f, 0.45, 0.38, steer=f > 0)
    # Roof turret: shield ring turns with the gunner, the M2 tilts with their aim.
    m.bone("turret", [
        box(-0.55, 2.0, -0.7, 0.55, 2.12, -0.1, "olive_dark"),
        box(-0.5, 2.12, 0.0, 0.5, 2.6, 0.08, paint),               # gun shield
        box(-0.6, 2.12, -0.2, -0.5, 2.5, 0.05, paint), box(0.5, 2.12, -0.2, 0.6, 2.5, 0.05, paint),
        box(-0.05, 2.12, -0.35, 0.05, 2.35, -0.25, "metal"),       # pintle
    ], at=(0, 2.0, -0.4))
    m.bone("mg", [
        box(-0.09, 2.3, -0.65, 0.09, 2.48, -0.05, "metal"),         # receiver
        box(-0.04, 2.35, -0.05, 0.04, 2.43, 0.85, "metal"),          # barrel
        box(-0.06, 2.33, 0.4, 0.06, 2.45, 0.55, "steel"),            # barrel jacket
        box(0.09, 2.25, -0.5, 0.25, 2.4, -0.3, "olive"),             # ammo can
        box(-0.12, 2.35, -0.75, 0.12, 2.4, -0.65, "metal"),          # spade grips
    ], parent="turret", at=(0, 2.4, -0.3))
    m.bone("muzzle_flash", [box(-0.08, 2.32, 0.86, 0.08, 2.46, 1.05, "flash")], parent="mg", at=(0, 2.4, -0.3))
    return m


def tank_model(paint="olive"):
    m = Model()
    m.bone("hull", [
        box(-1.25, 0.45, -2.6, 1.25, 1.25, 2.4, paint),            # hull
        box(-1.25, 1.25, -2.6, 1.25, 1.4, 1.2, paint),              # deck
        box(-1.2, 0.7, 2.4, 1.2, 1.2, 2.75, paint),                  # glacis
        box(-1.3, 1.0, -2.75, 1.3, 1.3, -2.6, "olive_dark"),        # rear plate
        box(-1.85, 1.0, -2.7, -1.25, 1.1, 2.6, paint), box(1.25, 1.0, -2.7, 1.85, 1.1, 2.6, paint),  # fenders
        box(-1.85, 0.55, -2.4, -1.75, 1.0, 2.2, "olive_dark"), box(1.75, 0.55, -2.4, 1.85, 1.0, 2.2, "olive_dark"),  # side skirts
        box(-0.3, 1.25, 1.6, 0.3, 1.38, 2.05, "metal"),             # driver hatch
    ])
    era = [box(-1.95, 0.6 + 0.3 * (i % 2), -2.2 + 0.5 * i, -1.85, 0.9 + 0.3 * (i % 2), -1.8 + 0.5 * i, "olive_dark") for i in range(9)]
    m.bone("upgrade_era_blocks", era + [box(-b[3], b[1], b[2], -b[0], b[4], b[5], b[6]) for b in era])
    m.bone("upgrade_jerry_cans", [box(-0.9, 1.0, -2.95, -0.2, 1.35, -2.75, "olive_dark"), box(0.2, 1.0, -2.95, 0.9, 1.35, -2.75, "olive_dark")])
    for side, r in (("l", -1.5), ("r", 1.5)):
        m.bone(f"track_{side}", [
            box(r - 0.25, 0.0, -2.5, r + 0.25, 0.12, 2.5, "track"),
            box(r - 0.25, 0.88, -2.3, r + 0.25, 1.0, 2.3, "track"),
            box(r - 0.25, 0.1, 2.35, r + 0.25, 0.9, 2.6, "track"), box(r - 0.25, 0.1, -2.6, r + 0.25, 0.9, -2.35, "track"),
        ])
        for i, f in enumerate((-1.9, -1.15, -0.4, 0.35, 1.1, 1.85)):
            m.wheel(f"{side}{i}", r, f, 0.38, 0.4, mat="metal")
    m.bone("turret", [
        box(-1.05, 1.4, -1.6, 1.05, 2.2, 0.9, paint),
        box(-0.85, 1.4, 0.9, 0.85, 2.05, 1.45, paint),              # sloped front
        box(-1.1, 1.55, -2.1, 1.1, 2.1, -1.6, "olive_dark"),        # bustle rack
        box(0.25, 2.2, -0.9, 0.75, 2.32, -0.4, "metal"),             # commander hatch
        box(-0.75, 2.2, -0.6, -0.4, 2.3, -0.3, "metal"),             # sight
    ], at=(0, 1.4, -0.2))
    m.bone("cannon", [
        box(-0.3, 1.6, 1.3, 0.3, 2.0, 1.75, "olive_dark"),          # mantlet
        box(-0.1, 1.7, 1.75, 0.1, 1.9, 4.6, "metal"),                # barrel
        box(-0.14, 1.66, 2.8, 0.14, 1.94, 3.2, paint),               # fume extractor
        box(-0.13, 1.67, 4.45, 0.13, 1.93, 4.62, "metal"),           # muzzle reference
    ], parent="turret", at=(0, 1.8, 1.4))
    m.bone("muzzle_flash", [box(-0.25, 1.55, 4.62, 0.25, 2.05, 5.2, "flash")], parent="cannon", at=(0, 1.8, 1.4))
    return m


# ------------------------------------------------------------------------------------------- content
MOUNTED_GUNS = {
    "m2_mounted": {"name": "M2 Browning (mounted)", "mounted": True, "damage": 11, "rpm": 550, "fire_mode": "auto",
                   "reload_ticks": 80, "velocity": 16, "spread": 1.2, "lifetime_ticks": 60, "gravity": 0.015,
                   "recoil": {"pitch": 0.4, "yaw": 0.3},
                   "sounds": {"shoot": f"{NS}:vehicle.hmg.shoot", "reload": f"{NS}:vehicle.reload", "empty": f"{NS}:vehicle.empty"}},
    "m256": {"name": "M256 120mm Cannon", "mounted": True, "damage": 45, "rpm": 8, "fire_mode": "semi", "reload_ticks": 100,
             "velocity": 6, "spread": 0.2, "lifetime_ticks": 80, "gravity": 0.01, "headshot_multiplier": 1.0,
             "recoil": {"pitch": 3.0, "yaw": 0.5},
             "tracer": {"color": "#FFE0A0", "width": 0.12, "length": 4},
             "sounds": {"shoot": f"{NS}:vehicle.cannon.shoot", "reload": f"{NS}:vehicle.cannon.reload", "empty": f"{NS}:vehicle.empty"}},
}
MAGAZINES = {
    "m2_box_100": {"name": ".50 BMG Ammo Box (100)", "caliber": "50bmg", "capacity": 100, "guns": [f"{NS}:m2_mounted"], "reload_multiplier": 1.0},
    "120mm_breech": {"name": "120mm Shell Rack (1)", "caliber": "120mm", "capacity": 1, "guns": [f"{NS}:m256"]},
}
AMMO = {
    "120mm_heat": {"name": "120mm HEAT Shell", "caliber": "120mm", "max_stack": 4, "projectile": f"{NS}:120mm_heat"},
    "120mm_apfsds": {"name": "120mm APFSDS Shell", "caliber": "120mm", "max_stack": 4, "armor_piercing": True,
                     "velocity_multiplier": 1.6, "damage_multiplier": 1.0, "tracer": {"color": "#FFFFFF", "width": 0.1, "length": 6}},
}
PROJECTILES = {
    "120mm_heat": {"name": "120mm HEAT", "contact": True, "throwable": False, "trail": True, "gravity": 0.01,
                   "explosion": {"power": 4.0, "break_blocks": True}, "icon": f"{NS}:120mm_heat"},
}

def wheel_part(r, f, radius=0.38, width=0.3, health=20, armor=0.0):
    return {"box": [r - width / 2, 0, f - radius, r + width / 2, 2 * radius, f + radius], "health": health, "armor": armor,
            "role": "propulsion", "core_damage": 0.1}


def car_parts(hull, engine, fuel_tank, wheels, radius, width, wheel_health, armor, extra=None):
    """Hull/engine/fuel tank boxes must not overlap (a shot hits the first box it enters); wheels hide their bone when shot off."""
    parts = {"hull": {"box": hull, "armor": armor, "role": "hull"},
             "engine": {"box": engine, "health": 30 + armor * 60, "armor": armor, "role": "engine", "core_damage": 0.4},
             "fuel_tank": {"box": fuel_tank, "health": 20 + armor * 30, "armor": armor, "role": "fuel_tank", "core_damage": 0.3}}
    for name, r, f in wheels:
        parts[f"wheel_{name}"] = {**wheel_part(r, f, radius, width, wheel_health, armor * 0.3), "bones": [f"wheel_{name}"]}
    parts.update(extra or {})
    return parts


# A seated player: vanilla puts their feet 0.6 below the seat point, legs forward at seat + 0.15, head top at seat + 1.2.
# So a seat's height is about the cushion top, and roofs must be 1.2 above it (or the head sticks out on purpose).
VEHICLES = {
    "jeep": dict(model=jeep_model, recipe=["S S E", "CCCC ", "W  W "], definition={
        "name": "Willys Jeep", "type": "car", "width": 2.0, "height": 1.4, "health": 60, "armor": 0.1,
        "max_speed": 0.95, "max_reverse_speed": 0.3, "acceleration": 0.028, "braking": 0.07, "drag": 0.015, "turn_speed": 5.0,
        "water_speed": 0.25, "collision_damage": 16, "death_explosion": 2.5, "camera_distance": 6,
        "fuel": {"capacity": 24000, "consumption": 1},
        "upgrade_slots": ["engine", "armor", "tyres", "tank"],
        "parts": car_parts(hull=[-0.85, 0.45, -1.3, 0.85, 1.15, 0.6], engine=[-0.8, 0.45, 0.6, 0.8, 1.15, 1.6],
                           fuel_tank=[-0.85, 0.45, -1.6, 0.85, 1.0, -1.3],
                           wheels=[("fl", -0.75, 1.05), ("fr", 0.75, 1.05), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)],
                           radius=0.38, width=0.3, wheel_health=20, armor=0.1),
        "seats": [{"position": [-0.4, 0.95, -0.05]}, {"position": [0.4, 0.95, -0.05]},
                  {"position": [-0.4, 0.95, -1.1]}, {"position": [0.4, 0.95, -1.1]}]}),
    "humvee": dict(model=humvee_model, recipe=[" G  ", "SSSE", "AACC", "W  W"], definition={
        "name": "M1114 Humvee", "type": "car", "width": 2.4, "height": 2.0, "health": 140, "armor": 0.5,
        "max_speed": 0.85, "max_reverse_speed": 0.25, "acceleration": 0.02, "braking": 0.06, "drag": 0.015, "turn_speed": 4.0,
        "water_speed": 0.2, "collision_damage": 22, "death_explosion": 3.0, "camera_distance": 8,
        "fuel": {"capacity": 36000, "consumption": 1},
        "upgrade_slots": ["engine", "armor", "tyres", "tank"],
        "parts": car_parts(hull=[-1.1, 0.5, -1.6, 1.1, 1.95, 0.6], engine=[-1.0, 0.5, 0.6, 1.0, 1.35, 2.0],
                           fuel_tank=[-1.1, 0.5, -1.9, 1.1, 1.2, -1.6],
                           wheels=[("fl", -0.92, 1.25), ("fr", 0.92, 1.25), ("rl", -0.92, -1.25), ("rr", 0.92, -1.25)],
                           radius=0.45, width=0.38, wheel_health=35, armor=0.5,
                           extra={"mg_mount": {"box": [-0.6, 2.0, -0.75, 0.6, 2.6, 0.1], "health": 30, "armor": 0.4, "role": "weapon",
                                               "seat": 2, "core_damage": 0.1, "bones": ["mg"]}}),
        # Cabin seats low enough for heads under the 1.95 roof; the gunner stands up through the roof ring.
        "seats": [{"position": [-0.45, 0.7, 0.0]}, {"position": [0.45, 0.7, 0.0]},
                  {"position": [0.0, 1.35, -0.75], "gun": f"{NS}:m2_mounted", "turret": True,
                   "pivot": [0.0, 2.4, -0.3], "muzzle": [0.0, 0.0, 1.15], "min_pitch": -20, "max_pitch": 50,
                   "yaw_bone": "turret", "pitch_bone": "mg"},
                  {"position": [0.45, 0.7, -1.1]}]}),
    "m1_abrams": dict(model=tank_model, recipe=["  TBBB", " AAAE ", "AHHHA ", "KKKKK "], definition={
        "name": "M1 Abrams", "type": "tank", "width": 3.6, "height": 2.3, "health": 400, "armor": 0.9,
        "max_speed": 0.6, "max_reverse_speed": 0.2, "acceleration": 0.012, "braking": 0.05, "drag": 0.03, "turn_speed": 2.5,
        "water_speed": 0.3, "step_height": 1.1, "collision_damage": 40, "death_explosion": 5.0, "camera_distance": 11,
        "fuel": {"capacity": 60000, "consumption": 2},
        "upgrade_slots": ["engine", "armor", "tank"],
        "repair": {"item": "minecraft:iron_ingot", "amount": 40},
        "parts": {
            "hull": {"box": [-1.25, 0.45, -1.6, 1.25, 1.4, 2.75], "armor": 0.9, "role": "hull"},
            "engine": {"box": [-1.25, 0.45, -2.75, 1.25, 1.4, -1.6], "health": 150, "armor": 0.8, "role": "engine", "core_damage": 0.4},
            "turret": {"box": [-1.05, 1.4, -2.1, 1.05, 2.2, 1.45], "health": 250, "armor": 0.92, "role": "weapon", "seat": 0, "core_damage": 0.5},
            "track_left": {"box": [-1.85, 0.0, -2.6, -1.25, 1.0, 2.6], "health": 150, "armor": 0.6, "role": "propulsion", "core_damage": 0.2,
                           "bones": ["track_l"]},
            "track_right": {"box": [1.25, 0.0, -2.6, 1.85, 1.0, 2.6], "health": 150, "armor": 0.6, "role": "propulsion", "core_damage": 0.2,
                            "bones": ["track_r"]},
        },
        # The driver also commands the turret (like the original Flan's tanks); the commander mans the .50 cal.
        # Crew inside the hull/turret, heads out of the hatches.
        "seats": [{"position": [0.0, 0.65, 1.8], "gun": f"{NS}:m256", "turret": True,
                   "pivot": [0.0, 1.8, -0.2], "muzzle": [0.0, 0.0, 4.9], "min_pitch": -8, "max_pitch": 20,
                   "yaw_bone": "turret", "pitch_bone": "cannon"},
                  {"position": [0.5, 1.6, -0.65], "gun": f"{NS}:m2_mounted", "turret": True,
                   "pivot": [0.5, 2.7, -0.65], "muzzle": [0.0, 0.0, 0.8], "min_pitch": -15, "max_pitch": 60}]}),
}

PARTS = {
    "wheel": dict(name="Wheel", pattern=["NKN", "KIK", "NKN"], count=2, icon=[box(-0.15, 0, -0.4, 0.15, 0.8, 0.4, "tyre"), box(-0.17, 0.25, -0.15, 0.17, 0.55, 0.15, "steel")]),
    "track": dict(name="Track Segment", pattern=["IIIIII", "N N N "], count=2, icon=[box(-0.3, 0, -0.9, 0.3, 0.15, 0.9, "track"), box(-0.3, 0.15, -0.9, 0.3, 0.3, -0.75, "track"), box(-0.3, 0.15, 0.75, 0.3, 0.3, 0.9, "track")]),
    "engine": dict(name="Petrol Engine", pattern=["IPI", "IRI"], icon=[box(-0.4, 0, -0.5, 0.4, 0.6, 0.5, "metal"), box(-0.3, 0.6, -0.4, 0.3, 0.75, 0.4, "steel"), box(0.4, 0.2, -0.3, 0.5, 0.4, 0.3, "black")]),
    "diesel_engine": dict(name="Gas Turbine Engine", pattern=["BPB", "IRI", "BCB"], icon=[box(-0.5, 0, -0.6, 0.5, 0.7, 0.6, "olive_dark"), box(-0.35, 0.7, -0.5, 0.35, 0.85, 0.5, "steel"), box(-0.2, 0.2, 0.6, 0.2, 0.5, 0.8, "black")]),
    "chassis": dict(name="Chassis", pattern=["IIIII", " I I "], icon=[box(-0.5, 0.1, -0.9, -0.35, 0.25, 0.9, "metal"), box(0.35, 0.1, -0.9, 0.5, 0.25, 0.9, "metal"), box(-0.5, 0.1, -0.1, 0.5, 0.25, 0.1, "metal")]),
    "heavy_chassis": dict(name="Heavy Hull", pattern=["BBBB", "I  I"], icon=[box(-0.6, 0, -0.9, 0.6, 0.5, 0.9, "olive")]),
    "seat": dict(name="Seat", pattern=["J  ", "JJJ"], icon=[box(-0.3, 0, -0.3, 0.3, 0.15, 0.3, "seat"), box(-0.3, 0.15, -0.3, 0.3, 0.75, -0.18, "seat")]),
    "armor_plate": dict(name="Armour Plate", pattern=["BI", "IB"], count=2, icon=[box(-0.5, 0, -0.6, 0.5, 0.2, 0.6, "olive_dark")]),
    "turret_ring": dict(name="Turret Ring", pattern=["NIN", "I I", "NIN"], icon=[box(-0.5, 0, -0.5, 0.5, 0.15, -0.35, "steel"), box(-0.5, 0, 0.35, 0.5, 0.15, 0.5, "steel"), box(-0.5, 0, -0.35, -0.35, 0.15, 0.35, "steel"), box(0.35, 0, -0.35, 0.5, 0.15, 0.35, "steel")]),
    "cannon_barrel": dict(name="120mm Gun Barrel", pattern=["BBBBBB"], icon=[box(-0.1, 0.3, -1.0, 0.1, 0.5, 1.0, "metal"), box(-0.18, 0.22, -1.0, 0.18, 0.58, -0.6, "olive_dark")]),
    "mg_mount": dict(name="Machine Gun Mount", pattern=["NIIN", " R  ", " I  "], icon=[box(-0.06, 0.3, -0.6, 0.06, 0.42, 0.7, "metal"), box(-0.1, 0.25, -0.6, 0.1, 0.45, -0.1, "metal"), box(-0.04, 0, -0.3, 0.04, 0.3, -0.2, "steel")]),
}
# Vehicle upgrades: slot, stats, which vehicle types they fit, bench recipe (letters → RAW / parts) and an icon.
UPGRADES = {
    "engine_tuning": dict(name="Engine Tuning Kit", slot="engine", types=["car"], recipe=["RER", "NPN"],
                          stats={"speed_multiplier": 1.2, "acceleration_multiplier": 1.35, "fuel_consumption_multiplier": 1.4},
                          icon=[box(-0.4, 0, -0.5, 0.4, 0.5, 0.5, "metal"), box(-0.2, 0.5, -0.3, 0.2, 0.7, 0.3, "red")]),
    "turbo_diesel": dict(name="Turbo Diesel", slot="engine", types=["tank"], recipe=["RDR", "BPB"],
                         stats={"speed_multiplier": 1.15, "acceleration_multiplier": 1.5, "fuel_consumption_multiplier": 1.25},
                         icon=[box(-0.5, 0, -0.6, 0.5, 0.6, 0.6, "olive_dark"), box(-0.25, 0.6, -0.25, 0.25, 0.85, 0.25, "steel")]),
    "armor_kit": dict(name="Armour Kit", slot="armor", types=["car"], recipe=["AAA", "IAI"],
                      stats={"armor_bonus": 0.2, "health_multiplier": 1.3, "speed_multiplier": 0.9, "acceleration_multiplier": 0.9},
                      icon=[box(-0.5, 0, -0.6, 0.5, 0.15, 0.6, "olive_dark"), box(-0.5, 0.15, -0.6, -0.4, 0.6, 0.6, "olive_dark")]),
    "era_blocks": dict(name="Reactive Armour (ERA)", slot="armor", types=["tank"], recipe=["AUA", "AUA", "AUA"],
                       stats={"armor_bonus": 0.04, "health_multiplier": 1.4, "speed_multiplier": 0.95},
                       icon=[box(-0.5, 0, -0.5, 0.5, 0.3, 0.5, "olive_dark"), box(-0.4, 0.3, -0.4, 0.4, 0.4, 0.4, "black")]),
    "offroad_tyres": dict(name="Off-road Tyres", slot="tyres", types=["car"], recipe=["W W", "K K"],
                          stats={"turn_multiplier": 1.2, "step_height_bonus": 0.25, "water_speed": 0.35, "speed_multiplier": 0.95},
                          icon=[box(-0.2, 0, -0.45, 0.2, 0.9, 0.45, "tyre"), box(-0.22, 0.3, -0.15, 0.22, 0.6, 0.15, "steel")]),
    "jerry_cans": dict(name="Jerry Cans", slot="tank", types=[], recipe=["I I", "INI", "III"],
                       stats={"fuel_capacity_multiplier": 1.5},
                       icon=[box(-0.3, 0, -0.15, 0.3, 0.8, 0.15, "olive_dark"), box(-0.1, 0.8, -0.05, 0.1, 0.95, 0.05, "black")]),
}

# Vehicle recipes: letters → parts. Magazines and shells use raw materials.
RECIPE_PARTS = dict(W="wheel", K="track", E="engine", S="seat", C="chassis", H="heavy_chassis", A="armor_plate",
                    T="turret_ring", B="cannon_barrel", G="mg_mount", D="diesel_engine")
VEHICLE_ENGINE = {"m1_abrams": "diesel_engine"}
RAW = {"I": "minecraft:iron_ingot", "N": "minecraft:iron_nugget", "B": "minecraft:iron_block", "K": "minecraft:black_dye",
       "P": "minecraft:piston", "R": "minecraft:redstone", "C": "minecraft:copper_ingot", "J": "minecraft:leather",
       "U": "minecraft:gunpowder", "T": "minecraft:tnt", "G": "minecraft:gold_ingot"}
OTHER_RECIPES = {
    "magazine_m2_box_100": (["III", "IKI"], {"id": "flansmod:magazine", "components": {"flansmod:magazine": {"magazine": f"{NS}:m2_box_100"}}}),
    "magazine_120mm_breech": (["N N", "NIN"], {"id": "flansmod:magazine", "components": {"flansmod:magazine": {"magazine": f"{NS}:120mm_breech"}}}),
    "ammo_120mm_heat": (["TCCC", " UU "], {"id": "flansmod:ammo", "count": 2, "components": {"flansmod:ammo_type": f"{NS}:120mm_heat", "minecraft:max_stack_size": 4}}),
    "ammo_120mm_apfsds": (["IICC", " UU "], {"id": "flansmod:ammo", "count": 2, "components": {"flansmod:ammo_type": f"{NS}:120mm_apfsds", "minecraft:max_stack_size": 4}}),
}
ICONS = {  # item icons for magazines/ammo (vehicle-space boxes, normalised into the item model)
    "m2_box_100": [box(-0.2, 0, -0.35, 0.2, 0.45, 0.35, "olive"), box(-0.15, 0.45, -0.3, 0.15, 0.5, 0.3, "metal")],
    "120mm_breech": [box(-0.35, 0, -0.35, 0.35, 0.1, 0.35, "steel"), box(-0.1, 0.1, -0.1, 0.1, 0.9, 0.1, "tan")],
    "120mm_heat": [box(-0.12, 0, -0.12, 0.12, 0.8, 0.12, "tan"), box(-0.08, 0.8, -0.08, 0.08, 1.15, 0.08, "olive_dark")],
    "120mm_apfsds": [box(-0.12, 0, -0.12, 0.12, 0.6, 0.12, "tan"), box(-0.04, 0.6, -0.04, 0.04, 1.2, 0.04, "black")],
}


# ------------------------------------------------------------------------------------------- item models
def item_model(name, boxes, gui_rotation=(25, 135, 0)):
    """3D vanilla element model built from vehicle-space boxes, scaled into the 0..16 item space."""
    lo = [min(b[i] for b in boxes) for i in range(3)]
    hi = [max(b[i + 3] for b in boxes) for i in range(3)]
    scale = 15.0 / max(hi[i] - lo[i] for i in range(3))
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    elements = []
    for b in boxes:
        tx, ty = MATERIALS[b[6]]
        # Vehicle [right, up, forward] → item [x, y, z] with the front facing west (-X) in the GUI view.
        frm = [8 + (b[2] - centre[2]) * -scale, 8 + (b[1] - centre[1]) * scale, 8 + (b[0] - centre[0]) * scale]
        to = [8 + (b[5] - centre[2]) * -scale, 8 + (b[4] - centre[1]) * scale, 8 + (b[3] - centre[0]) * scale]
        frm, to = [min(a, c) for a, c in zip(frm, to)], [max(a, c) for a, c in zip(frm, to)]
        u, v = tx * 2 + 0.25, ty * 8 + 0.25  # one tile of the 128x32 texture = 2x8 units of the 16x16 UV space
        face = {"uv": [u, v, u + 1.5, v + 7.5], "texture": "#m"}
        elements.append({"from": [round(max(-16, min(32, x)), 3) for x in frm], "to": [round(max(-16, min(32, x)), 3) for x in to],
                         "faces": {f: dict(face) for f in ("north", "south", "east", "west", "up", "down")}})
    write(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write(ASSETS / "models" / "item" / f"{name}.json", {
        "parent": "minecraft:block/block", "textures": {"m": f"{NS}:item/vehicles", "particle": f"{NS}:item/vehicles"},
        "elements": elements,
        "display": {"gui": {"rotation": list(gui_rotation), "scale": [0.6, 0.6, 0.6]},
                    "ground": {"translation": [0, 3, 0], "scale": [0.3, 0.3, 0.3]},
                    "fixed": {"rotation": [0, 90, 0], "scale": [0.6, 0.6, 0.6]},
                    "thirdperson_righthand": {"rotation": [75, 45, 0], "translation": [0, 2.5, 0], "scale": [0.35, 0.35, 0.35]},
                    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]}}})


# ------------------------------------------------------------------------------------------- recipes
def part_ingredient(part_id):
    return {"fabric:type": "fabric:components", "base": "flansmod:part", "components": {"flansmod:part": f"{NS}:{part_id}"}}


def bench(name, pattern, key, result):
    width = max(len(r) for r in pattern)
    rows = [r.ljust(width) for r in pattern]
    assert len(rows) <= 4 and width <= 6, name
    used = sorted({ch for row in rows for ch in row if ch != " "})
    write(DATA / "recipe" / f"{name}.json", {"type": "flansmod:weapon_assembly", "pattern": rows,
                                              "key": {k: key[k] for k in used}, "result": result})


def raw_key(pattern):
    return {ch: RAW[ch] for row in pattern for ch in row if ch != " "}


# ------------------------------------------------------------------------------------------- sounds
def sounds():
    def s(name, volume=1.0, pitch=1.0):
        return {"name": f"minecraft:{name}", "volume": volume, "pitch": pitch}
    events = {
        "vehicle.engine": [s("minecart/base", 0.6, 0.6)],
        "vehicle.tank.engine": [s("minecart/base", 0.9, 0.35)],
        "vehicle.hmg.shoot": [s("random/explode3", 0.8, 1.6)],
        "vehicle.cannon.shoot": [s("random/explode1", 2.5, 0.5), s("random/explode2", 2.5, 0.5)],
        "vehicle.cannon.reload": [s("block/iron_door/close1", 1.0, 0.6)],
        "vehicle.reload": [s("item/crossbow/loading_middle1", 0.9, 0.9)],
        "vehicle.empty": [s("random/click", 0.6, 1.4)],
    }
    write(ASSETS / "sounds.json", {k: {"sounds": v, "subtitle": f"subtitles.{NS}.{k}"} for k, v in events.items()})
    return events


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    write(ROOT / "pack.mcmeta", {
        "pack": {"description": "Flan's Mod: Recoded - jeeps, Humvees and tanks", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Flan's Vehicles", "icon": f"{NS}:m1_abrams"}})
    vehicle_texture(ASSETS / "textures" / "vehicle" / "vehicles.png")
    vehicle_texture(ASSETS / "textures" / "item" / "vehicles.png")  # item models can only use atlas (item/) textures
    texture = f"{NS}:textures/vehicle/vehicles.png"

    for vid, v in VEHICLES.items():
        model = v["model"]()
        write(ASSETS / "geckolib" / "models" / "vehicle" / f"{vid}.geo.json", model.geo(vid))
        definition = {**v["definition"], "model": {"texture": texture}, "icon": f"{NS}:{vid}"}
        definition["sounds"] = {"engine": f"{NS}:vehicle.tank.engine" if definition["type"] == "tank" else f"{NS}:vehicle.engine"}
        write(DATA / "flansmod" / "vehicles" / f"{vid}.json", definition)
        item_model(vid, model.boxes())
        parts = {**RECIPE_PARTS, "E": VEHICLE_ENGINE.get(vid, "engine")}
        key = {ch: part_ingredient(parts[ch]) for row in v["recipe"] for ch in row if ch != " "}
        bench(vid, v["recipe"], key, {"id": "flansmod:vehicle", "components": {"flansmod:vehicle": f"{NS}:{vid}"}})

    for pid, p in PARTS.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": p["name"], "icon": f"{NS}:{pid}"})
        item_model(pid, p["icon"])
        bench(f"part_{pid}", p["pattern"], raw_key(p["pattern"]),
              {"id": "flansmod:part", "count": p.get("count", 1), "components": {"flansmod:part": f"{NS}:{pid}"}})

    for uid, u in UPGRADES.items():
        definition = {"name": u["name"], "slot": u["slot"], "icon": f"{NS}:{uid}", **u["stats"]}
        if u["types"]:
            definition["types"] = u["types"]
        write(DATA / "flansmod" / "vehicle_upgrades" / f"{uid}.json", definition)
        item_model(uid, u["icon"])
        # W wheel, A armour plate, E engine, D diesel engine are vehicle parts; other letters are raw materials.
        key = {ch: part_ingredient(RECIPE_PARTS[ch]) if ch in "WAED" else RAW[ch] for row in u["recipe"] for ch in row if ch != " "}
        bench(f"upgrade_{uid}", u["recipe"], key, {"id": "flansmod:vehicle_upgrade", "components": {"flansmod:vehicle_upgrade": f"{NS}:{uid}"}})

    for gid, g in MOUNTED_GUNS.items():
        write(DATA / "flansmod" / "guns" / f"{gid}.json", g)
    for mid, m in MAGAZINES.items():
        write(DATA / "flansmod" / "magazines" / f"{mid}.json", {**m, "icon": f"{NS}:{mid}"})
        item_model(mid, ICONS[mid])
    for aid, a in AMMO.items():
        write(DATA / "flansmod" / "ammo" / f"{aid}.json", {**a, "icon": f"{NS}:{aid}"})
        item_model(aid, ICONS[aid], gui_rotation=(0, 0, -45))
    for gid, g in PROJECTILES.items():
        write(DATA / "flansmod" / "grenades" / f"{gid}.json", g)
    for name, (pattern, result) in OTHER_RECIPES.items():
        bench(name, pattern, raw_key(pattern), result)

    lang = {f"subtitles.{NS}.{e}": "Engine runs" if "engine" in e else "Gun fires" if "shoot" in e else "Gun reloads"
            if "reload" in e else "Gun clicks" for e in sounds()}
    lang.update({f"vehicle_upgrade.{NS}.{uid}": u["name"] for uid, u in UPGRADES.items()})
    write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(VEHICLES)} vehicles, {len(UPGRADES)} upgrades, {len(MOUNTED_GUNS)} mounted guns, {len(PARTS)} parts in {ROOT}")


if __name__ == "__main__":
    main()
