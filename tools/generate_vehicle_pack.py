#!/usr/bin/env python3
"""
Generates the built-in "Vehicles" content pack (src/main/resources/resourcepacks/vehicles): driveable vehicles in the
spirit of the original Flan's Mod vehicle packs - jeep, Humvee, M35 truck, BTR-80, M2 Bradley, M1 Abrams and T-72,
the UH-1H and AH-6 helicopters - plus their hit-box parts, upgrades, mounted guns with gunner's sights, tank shells and autocannon rounds, vehicle
parts, Weapons Bench recipes, GeckoLib models, textures and item icons.

It is a normal content pack; the mod code contains no vehicles. Edit the tables below and re-run:
    python3 tools/generate_vehicle_pack.py
Requires Pillow. Models come from tools/vehiclesmith.py and tools/aircraftsmith.py (shared with the WW2 pack, which also
uses this module's writers). The Humvee/Abrams machine guns fire .50 BMG from the Basic pack (flansbasic:50bmg).

Vehicle space is [right, up, forward] in blocks, origin at the centre of the footprint on the ground, matching the
`position`/`pivot`/`muzzle`/`sight` fields of vehicle definitions.
"""
import json
import shutil
from pathlib import Path

import aircraftsmith as asm
import generate_basic_pack as base
import structuresmith as ss
import vehiclesmith as vs
from vehiclesmith import box

NS = "flansvehicles"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/vehicles"
DATA = ROOT / "data" / NS
ASSETS = ROOT / "assets" / NS
VEHICLE_PARTS_NS = "flansvehicles"  # wheels, tracks, engines, ... (the WW2 pack builds its vehicles from these too)


def configure(ns, root):
    """Point this module's (and the Basic generator's) writers at a pack."""
    global NS, ROOT, DATA, ASSETS
    NS, ROOT, DATA, ASSETS = ns, root, root / "data" / ns, root / "assets" / ns
    base.NS, base.ROOT, base.DATA, base.ASSETS = NS, ROOT, DATA, ASSETS


def write(path: Path, data):
    base.write(path, data)


def seed_signatures():
    """Recipes of the other packs, so this pack's recipes are made unique against them."""
    for f in ROOT.parent.glob("*/data/*/recipe/*.json"):
        if ROOT in f.parents:
            continue
        d = json.loads(f.read_text())
        if d.get("type") != "flansmod:weapon_assembly":
            continue
        used = sorted({ch for row in d["pattern"] for ch in row if ch != " "})
        base._signatures[(tuple(d["pattern"]), tuple((k, json.dumps(d["key"][k], sort_keys=True)) for k in used))] = f.stem


# ------------------------------------------------------------------------------------------- definitions helpers
def wheel_part(r, f, radius, width, health, armor):
    return {"box": [r - width / 2, 0, f - radius, r + width / 2, 2 * radius, f + radius], "health": health, "armor": armor,
            "role": "propulsion", "core_damage": 0.1}


def wheeled_parts(hull, engine, wheels, radius, width, wheel_health, armor, fuel_tank=None, extra=None):
    """Hull/engine/fuel tank boxes must not overlap (a shot hits the first box it enters); wheels hide their bone when shot off."""
    parts = {"hull": {"box": hull, "armor": armor, "role": "hull"},
             "engine": {"box": engine, "health": 30 + armor * 60, "armor": armor, "role": "engine", "core_damage": 0.4}}
    if fuel_tank:
        parts["fuel_tank"] = {"box": fuel_tank, "health": 20 + armor * 30, "armor": armor, "role": "fuel_tank", "core_damage": 0.3}
    for name, r, f in wheels:
        parts[f"wheel_{name}"] = {**wheel_part(r, f, radius, width, wheel_health, armor * 0.3), "bones": [f"wheel_{name}"]}
    parts.update(extra or {})
    return parts


def tracked_parts(inner, outer, f0, f1, u0, top, track_h, engine_to, turret, armor, health, track_health, gunner=0):
    """Tank hit boxes: hull (front), engine deck (rear, up to [engine_to]), tracks below the fenders, turret."""
    parts = {"hull": {"box": [-inner, u0, engine_to, inner, top, f1], "armor": armor, "role": "hull"},
             "engine": {"box": [-inner, u0, f0, inner, top, engine_to], "health": health * 0.4, "armor": armor * 0.9, "role": "engine", "core_damage": 0.4},
             "track_left": {"box": [-outer, 0, f0, -inner, track_h, f1], "health": track_health, "armor": armor * 0.65, "role": "propulsion",
                            "core_damage": 0.2, "bones": ["track_l"]},
             "track_right": {"box": [inner, 0, f0, outer, track_h, f1], "health": track_health, "armor": armor * 0.65, "role": "propulsion",
                             "core_damage": 0.2, "bones": ["track_r"]}}
    if top > track_h:  # fenders/sponsons above the tracks belong to the hull
        parts["sponson_left"] = {"box": [-outer, track_h, f0, -inner, top, f1], "armor": armor, "role": "hull"}
        parts["sponson_right"] = {"box": [inner, track_h, f0, outer, top, f1], "armor": armor, "role": "hull"}
    if turret:
        parts["turret"] = {"box": turret, "health": health * 0.6, "armor": min(0.95, armor + 0.02), "role": "weapon", "seat": gunner, "core_damage": 0.5}
    return parts


def gun_seat(m, mount, position, gun, min_pitch, max_pitch, yaw_bone, pitch_bone):
    """A seat firing [gun] from the model's weapon [mount] (pivot/muzzle/sight from vehiclesmith; [position] None = the
    mount's own gunner position, for ring mounts)."""
    mt = m.mounts[mount]
    return {"position": position or mt["seat"], "gun": gun, "turret": True, "pivot": mt["pivot"], "muzzle": mt["muzzle"], "sight": mt["sight"],
            "min_pitch": min_pitch, "max_pitch": max_pitch, "yaw_bone": yaw_bone, "pitch_bone": pitch_bone}


def seat(*position):
    return {"position": list(position)}


# A seated player: vanilla puts the legs forward at about the seat point and the head top at seat + 1.2, so roofs must be
# 1.2 above a seat (or the head comes out of a hatch on purpose). Cushions in the models sit at the seat heights.
def car(name, **kw):
    return {"name": name, "type": "car", "upgrade_slots": ["engine", "armor", "tyres", "tank", "cargo"], **kw}


def static(name, health=60, **kw):
    """An emplacement (mortar): never drives, needs no fuel; one hit box around it."""
    return {"name": name, "type": "static", "health": health, "armor": 0.2, "max_speed": 0.0, "max_reverse_speed": 0.0,
            "collision_damage": 0, "death_explosion": 1.5, "camera_distance": 4, "fuel": {"capacity": 0},
            "parts": {"hull": {"box": [-0.5, 0, -0.5, 0.5, 0.9, 1.0], "role": "hull"}}, **kw}


def aa_emplacement(name, health=90, **kw):
    """A towed anti-aircraft gun: an emplacement the gunner aims with the view (not laid with the keys)."""
    return static(name, health=health, lay_with_keys=False, camera_distance=5, death_explosion=2.0,
                  parts={"hull": {"box": [-0.6, 0, -0.6, 0.6, 1.4, 1.0], "role": "hull"}}, **kw)


def mg_emplacement(name, health=50, **kw):
    """A manned machine gun (or grenade launcher) on a tripod: aimed with the view within its traverse arc."""
    return static(name, health=health, lay_with_keys=False, camera_distance=4,
                  parts={"hull": {"box": [-0.5, 0, -1.0, 0.5, 1.3, 0.9], "role": "hull"}}, **kw)


def mg_seat(m, gun, yaw_limit=70, min_pitch=-20, max_pitch=45):
    """The gunner behind a tripod gun (vehiclesmith.tripod_mg), traversing [yaw_limit] degrees to either side."""
    return {**gun_seat(m, "main", [0.0, 0.3, -0.85], gun, min_pitch, max_pitch, "mount", "gun"), "yaw_limit": yaw_limit}


def sentry(name, targets, reach, health=70):
    """An automatic sentry turret (the mod's SentryDefinition): fights by itself while unmanned and reloads from its
    cargo (magazines or rounds put into it)."""
    return aa_emplacement(name, health=health, storage=18, sentry={"range": reach, "targets": targets, "turn_speed": 8})


def sentry_seat(m, gun, min_pitch, max_pitch):
    return gun_seat(m, "main", [0.0, 0.3, -0.85], gun, min_pitch, max_pitch, "head", "gun")


def howitzer_emplacement(name, health=120, **kw):
    """A towed howitzer or rocket launcher: laid with the movement keys or the artillery map; shells in its cargo."""
    return static(name, health=health, camera_distance=6, storage=9, death_explosion=2.5,
                  parts={"hull": {"box": [-0.9, 0, -2.4, 0.9, 1.5, 1.0], "role": "hull"}}, **kw)


def artillery_seat(m, gun, min_pitch, max_pitch, bone="barrel"):
    """The layer beside the breech."""
    return gun_seat(m, "main", [-0.6, 0.3, -0.8], gun, min_pitch, max_pitch, "mount", bone)


def bomb_seat(seat_def, n):
    """[seat_def] (the pilot's seat) with [n] bombs as its secondary weapon (a rack for one, under the wings for more)."""
    return {**seat_def, "secondary": f"{VEHICLE_PARTS_NS}:{'bomb_rack' if n == 1 else 'bomb_bay'}", "secondary_muzzle": asm.BOMB_RELEASE}


def tank(name, **kw):
    return {"name": name, "type": "tank", "upgrade_slots": ["engine", "armor", "tank", "cargo"], "step_height": 1.1,
            "repair": {"item": "minecraft:iron_ingot", "amount": 40}, **kw}


def aircraft(name, kind, flight, **kw):
    """Planes and helicopters ([kind]): fly (see the mod's FlightModel); the engine burns fuel while someone flies."""
    return {"name": name, "type": kind, "upgrade_slots": ["engine", "armor", "tank"], "step_height": 0.6, "flight": flight,
            "repair": {"item": "minecraft:iron_ingot", "amount": 25}, **kw}


def fixed_seat(m, mount, position, gun):
    """A crew seat firing [gun] along the nose from the model's fixed [mount] (pivot = the crewman's eye)."""
    mt = m.mounts[mount]
    return {"position": list(position), "gun": gun, "turret": False, "pivot": mt["pivot"], "muzzle": mt["muzzle"], "sight": mt["sight"]}


def fighter(name, speed, turn, health, span, root, nose, faction=None, armor=0.05):
    """A piston fighter (aircraftsmith.fighter): fuselage, tail and landing gear count as hull; the engine; each wing is
    a propulsion part (shot off = its share of lift gone, the wing's bone disappears)."""
    a, uw = asm.AXIS, asm.AXIS - 0.45
    parts = {"hull": {"box": [-0.6, a - 0.62, -2.8, 0.6, a + 0.95, 1.6], "armor": armor, "role": "hull"},
             "tail": {"box": [-1.4, a - 0.3, -4.1, 1.4, a + 1.1, -2.8], "armor": armor, "role": "hull"},
             "engine": {"box": [-0.55, a - 0.55, 1.6, 0.55, a + 0.55, nose + 0.4], "health": 40, "armor": armor, "role": "engine", "core_damage": 0.4},
             "gear": {"box": [-1.3, 0, 0.55, 1.3, uw - 0.05, 1.25], "armor": armor, "role": "hull"}}
    for side, n in ((-1, "left"), (1, "right")):
        r0, r1 = sorted((side * 0.6, side * span))
        parts[f"wing_{n}"] = {"box": [r0, uw - 0.05, 1.25 - root, r1, uw + 0.2, 1.3], "health": round(health * 0.4), "armor": armor,
                              "role": "propulsion", "core_damage": 0.2, "bones": [f"wing_{n[0]}"]}
    return aircraft(name, "plane", {"lift_speed": 0.9, "pitch_speed": 2.5, "max_pitch": 60, "crash_speed": 0.5, "crash_damage": 100},
                    health=health, armor=armor, max_speed=speed, max_reverse_speed=0.0, acceleration=0.03, braking=0.04, drag=0.01,
                    turn_speed=turn, collision_damage=30, death_explosion=3.0, camera_distance=12,
                    fuel={"capacity": 36000, "consumption": 1}, parts=parts)


JEEP_WHEELS = [("fl", -0.75, 1.05), ("fr", 0.75, 1.05), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)]
HUMVEE_WHEELS = [("fl", -0.92, 1.25), ("fr", 0.92, 1.25), ("rl", -0.92, -1.25), ("rr", 0.92, -1.25)]
TRUCK_WHEELS = [("fl", -0.95, 2.1), ("fr", 0.95, 2.1), ("ml", -0.95, -1.15), ("mr", 0.95, -1.15), ("rl", -0.95, -2.35), ("rr", 0.95, -2.35)]
BTR_WHEELS = [(f"{s}{i}", r, f) for s, r in (("l", -1.3), ("r", 1.3)) for i, f in enumerate((2.3, 1.1, -1.1, -2.3))]
V = "flansvehicles"

VEHICLES = {
    "jeep": dict(model=vs.jeep, recipe=["S S E", "CCCC ", "W  W "], definition=car(
        "M151 MUTT", health=60, armor=0.1, max_speed=0.95, max_reverse_speed=0.3, acceleration=0.028, braking=0.07, drag=0.015,
        turn_speed=5.0, water_speed=0.25, collision_damage=16, death_explosion=2.5, camera_distance=6,
        fuel={"capacity": 24000, "consumption": 1},
        parts=wheeled_parts([-0.85, 0.45, -1.3, 0.85, 1.15, 0.6], [-0.8, 0.45, 0.6, 0.8, 1.15, 1.6], JEEP_WHEELS, 0.38, 0.3, 20, 0.1,
                            fuel_tank=[-0.85, 0.45, -1.6, 0.85, 1.0, -1.3])),
        seats=lambda m: [seat(-0.4, 0.8, -0.05), seat(0.4, 0.8, -0.05), seat(-0.4, 0.8, -1.1), seat(0.4, 0.8, -1.1)]),
    "humvee": dict(model=vs.humvee, recipe=[" G  ", "SSSE", "AACC", "W  W"], definition=car(
        "M1114 Humvee", health=140, armor=0.5, max_speed=0.85, max_reverse_speed=0.25, acceleration=0.02, braking=0.06, drag=0.015,
        turn_speed=4.0, water_speed=0.2, collision_damage=22, death_explosion=3.0, camera_distance=8,
        fuel={"capacity": 36000, "consumption": 1, "type": "diesel"},
        parts=wheeled_parts([-1.1, 0.5, -1.6, 1.1, 1.95, 0.6], [-1.0, 0.5, 0.6, 1.0, 1.35, 2.0], HUMVEE_WHEELS, 0.45, 0.38, 35, 0.5,
                            fuel_tank=[-1.1, 0.5, -1.9, 1.1, 1.2, -1.6],
                            extra={"mg_mount": {"box": [-0.6, 2.0, -1.0, 0.6, 2.75, 0.2], "health": 30, "armor": 0.4, "role": "weapon",
                                                "seat": 2, "core_damage": 0.1, "bones": ["mg"]}})),
        # Driver and passengers in the cabin; the gunner stands in the roof ring, which turns around them.
        seats=lambda m: [seat(-0.45, 0.75, -0.05), seat(0.45, 0.75, -0.05),
                         gun_seat(m, "mg", None, f"{V}:m2_mounted", -20, 50, "turret", "mg"),
                         seat(-0.5, 0.75, -1.15), seat(0.5, 0.75, -1.15)]),
    "m35": dict(model=vs.truck, recipe=["  SSE", "CCCCC", "WW WW"], definition=car(
        "M35 Cargo Truck", health=110, armor=0.15, max_speed=0.7, max_reverse_speed=0.2, acceleration=0.014, braking=0.05, drag=0.02,
        turn_speed=3.2, water_speed=0.15, collision_damage=26, death_explosion=3.0, camera_distance=10,
        fuel={"capacity": 48000, "consumption": 1, "type": "diesel"},
        parts=wheeled_parts([-1.2, 0.85, -3.3, 1.2, 2.5, 1.6], [-0.75, 0.8, 1.6, 0.75, 1.75, 2.85], TRUCK_WHEELS, 0.5, 0.36, 30, 0.15,
                            fuel_tank=[-1.25, 0.35, -0.6, -1.0, 0.85, 0.3])),
        seats=lambda m: [seat(-0.45, 1.2, 0.85), seat(0.45, 1.2, 0.85)] +
                        [seat(s * 0.85, 1.55, f) for f in (-0.4, -1.3, -2.2) for s in (-1, 1)]),
    "btr80": dict(model=vs.btr80, recipe=["  GSE", "AAAAA", "CCCCC", "WWWW "], definition=car(
        "BTR-80", health=200, armor=0.6, max_speed=0.75, max_reverse_speed=0.2, acceleration=0.016, braking=0.05, drag=0.02,
        turn_speed=3.0, water_speed=0.6, collision_damage=30, death_explosion=4.0, camera_distance=11,
        fuel={"capacity": 48000, "consumption": 1, "type": "diesel"}, upgrade_slots=["engine", "tyres", "tank", "cargo"],
        parts=wheeled_parts([-1.45, 0.55, -2.4, 1.45, 1.9, 3.5], [-1.45, 0.55, -3.6, 1.45, 1.9, -2.4], BTR_WHEELS, 0.55, 0.4, 45, 0.6,
                            extra={"turret": {"box": [-0.65, 1.9, -0.25, 0.65, 2.5, 1.05], "health": 80, "armor": 0.6, "role": "weapon",
                                              "seat": 2, "core_damage": 0.3, "bones": ["kpvt"]}})),
        seats=lambda m: [seat(-0.6, 1.15, 1.35), seat(0.6, 1.15, 1.35), gun_seat(m, "main", [0.0, 1.55, 0.4], f"{V}:kpvt", -5, 60, "turret", "kpvt")] +
                        [seat(s * 1.05, 0.62, f) for f in (-0.6, -1.5, -2.4) for s in (-1, 1)]),
    "m2_bradley": dict(model=vs.bradley, recipe=[" TBSS", "AAAAE", "HHHHH", "KKKKK"], definition=tank(
        "M2 Bradley", health=260, armor=0.75, max_speed=0.7, max_reverse_speed=0.22, acceleration=0.016, braking=0.05, drag=0.03,
        turn_speed=3.0, water_speed=0.3, collision_damage=34, death_explosion=4.5, camera_distance=11,
        fuel={"capacity": 54000, "consumption": 2, "type": "diesel"},
        parts=tracked_parts(1.0, 1.6, -3.2, 3.2, 0.5, 1.95, 0.95, -2.0, [-1.35, 1.95, -1.25, 0.95, 2.65, 1.0], 0.75, 260, 120, gunner=1)),
        seats=lambda m: [seat(-0.85, 1.2, 1.55), gun_seat(m, "main", [0.0, 1.95, -0.2], f"{V}:m242", -10, 55, "turret", "m242")] +
                        [seat(s * 1.15, 0.66, f) for f in (-1.0, -1.9, -2.8) for s in (-1, 1)]),
    "m1_abrams": dict(model=vs.abrams, recipe=["  TBBB", " AAAE ", "AHHHA ", "KKKKK "], definition=tank(
        "M1 Abrams", health=400, armor=0.9, max_speed=0.6, max_reverse_speed=0.2, acceleration=0.012, braking=0.05, drag=0.03,
        turn_speed=2.5, water_speed=0.3, collision_damage=40, death_explosion=5.0, camera_distance=11,
        fuel={"capacity": 60000, "consumption": 2, "type": "diesel"},
        parts=tracked_parts(1.27, 1.85, -2.75, 2.85, 0.45, 1.4, 1.0, -1.6, [-1.1, 1.4, -2.0, 1.1, 2.2, 1.35], 0.9, 400, 150)),
        # The driver also commands the turret (like the original Flan's tanks); the commander mans the .50 cal cupola.
        seats=lambda m: [gun_seat(m, "main", [0.0, 0.65, 1.65], f"{V}:m256", -8, 20, "turret", "cannon"),
                         gun_seat(m, "cupola", None, f"{V}:m2_mounted", -15, 60, "cupola", "cupola_mg")]),
    "m252": dict(model=lambda: vs.mortar(1.25, 0.065, "olive_drab", plate_size=0.4, round_plate=True), recipe=[" I ", " I ", "BNB"],
                 definition=static("M252 81mm Mortar"),
                 seats=lambda m: [gun_seat(m, "main", [0.0, 0.3, -1.1], f"{V}:m252_tube", 45, 85, "mount", "tube")]),
    # Stationary guns (aimed with the view), sentry turrets (fight by themselves) and artillery (laid, artillery map).
    "m2_tripod": dict(model=lambda: vs.tripod_mg(1.0), recipe=[" II  ", "IIIIN", " N N "], definition=mg_emplacement("M2HB on M3 Tripod"),
                      seats=lambda m: [mg_seat(m, f"{V}:m2_mounted")]),
    "mk19_tripod": dict(model=lambda: vs.tripod_mg(0.7, box_mag="olive_dark"), recipe=[" IIB ", "IIIIN", " N N "],
                        definition=mg_emplacement("Mk 19 Grenade Launcher"), seats=lambda m: [mg_seat(m, f"{V}:mk19", min_pitch=-15, max_pitch=50)]),
    "sentry_mg": dict(model=lambda: vs.sentry_turret(1.0), recipe=[" QII ", " RIR ", "  I  ", " BBB "],
                      definition=sentry("M240 Sentry Turret", ["monsters", "enemies"], 40),
                      seats=lambda m: [sentry_seat(m, f"{V}:sentry_m240", -25, 60)]),
    "sentry_aa": dict(model=lambda: vs.sentry_turret(1.3, twin=True), recipe=["QIIII", " RIR ", "  I  ", " BBB "],
                      definition=sentry("23mm Air-Defence Sentry", ["aircraft", "monsters"], 90, health=90),
                      seats=lambda m: [sentry_seat(m, f"{V}:sentry_23mm", -5, 85)]),
    "m777": dict(model=lambda: vs.howitzer(3.0, 0.09, "sand", shield=False, wheel_r=0.45, trail_len=2.6), recipe=["   BBB", "IIBII ", "I  I  ", "N  N  "],
                 definition=howitzer_emplacement("M777 155mm Howitzer"), seats=lambda m: [artillery_seat(m, f"{V}:m777_gun", -2, 70)]),
    "type63": dict(model=lambda: vs.rocket_launcher(3, 4), recipe=["IIII  ", "IIIIU ", " II   ", "N  N  "],
                   definition=howitzer_emplacement("Type 63 107mm Rocket Launcher", health=70),
                   seats=lambda m: [artillery_seat(m, f"{V}:type63_launcher", 0, 55, bone="tubes")]),
    "zu23": dict(model=lambda: vs.aa_gun(2, 2.1, 0.045, "soviet_green"), recipe=["BB  ", " IBB", "IIII", "N  N"],
                 definition=aa_emplacement("ZU-23-2"),
                 seats=lambda m: [gun_seat(m, "main", [0.0, 0.75, -0.05], f"{V}:zu23", -5, 85, "mount", "guns")]),
    "t72": dict(model=vs.t72, recipe=["  TBBB", "AAAAE ", "AHHHA ", "KKKKK "], definition=tank(
        "T-72B", health=380, armor=0.88, max_speed=0.62, max_reverse_speed=0.15, acceleration=0.013, braking=0.05, drag=0.03,
        turn_speed=2.6, water_speed=0.3, collision_damage=40, death_explosion=5.5, camera_distance=11,
        fuel={"capacity": 60000, "consumption": 2, "type": "diesel"},
        parts=tracked_parts(1.25, 1.8, -3.1, 3.0, 0.45, 1.25, 0.9, -1.7, [-1.15, 1.25, -1.15, 1.15, 2.1, 1.15], 0.88, 380, 140)),
        seats=lambda m: [gun_seat(m, "main", [0.0, 0.6, 1.55], f"{V}:2a46", -6, 14, "turret", "cannon"),
                         gun_seat(m, "cupola", None, f"{V}:nsvt", -5, 70, "cupola", "cupola_mg")]),
    # Helicopters: pilot (seat 0, right) and co-pilot up front; the Huey's door gunners sit at the open cargo doors.
    "uh1": dict(model=asm.huey, recipe=["OOOOOO", "SS E  ", "GFFFFF", "  G   "], definition=aircraft(
        "UH-1H Iroquois", "helicopter", {"climb_speed": 0.3, "tilt": 12, "bank": 6, "crash_speed": 0.5, "crash_damage": 80},
        health=140, armor=0.1, max_speed=1.1, max_reverse_speed=0.3, acceleration=0.025, braking=0.05, drag=0.02, turn_speed=3.0,
        collision_damage=20, death_explosion=3.5, camera_distance=13, fuel={"capacity": 48000, "consumption": 1},
        upgrade_slots=["engine", "armor", "tank", "cargo"],
        parts={"hull": {"box": [-1.25, 0.45, -1.6, 1.25, 2.45, 2.75], "armor": 0.1, "role": "hull"},
               "skids": {"box": [-1.1, 0, -1.5, 1.1, 0.45, 2.45], "armor": 0.1, "role": "hull"},
               "engine": {"box": [-0.6, 2.45, -1.6, 0.6, 2.95, 0.8], "health": 50, "armor": 0.1, "role": "engine", "core_damage": 0.4},
               "rotor": {"box": [-0.5, 2.95, -0.3, 0.5, 3.45, 0.75], "health": 50, "armor": 0.1, "role": "propulsion", "core_damage": 0.3,
                         "bones": ["rotor"]},
               "tail_boom": {"box": [-0.5, 1.45, -6.9, 0.5, 3.15, -1.6], "armor": 0.05, "role": "hull"}}),
        seats=lambda m: [seat(0.55, 1.0, 1.5), seat(-0.55, 1.0, 1.5),
                         gun_seat(m, "door_l", [-0.7, 1.0, -0.2], f"{V}:m60d", -60, 30, "door_l", "mg_l"),
                         gun_seat(m, "door_r", [0.7, 1.0, -0.2], f"{V}:m60d", -60, 30, "door_r", "mg_r"),
                         seat(-0.55, 1.0, -1.05), seat(0.0, 1.0, -1.05), seat(0.55, 1.0, -1.05)]),
    "ah6": dict(model=asm.little_bird, recipe=["OOOO", "SSE ", "GFFF", " B  "], definition=aircraft(
        "AH-6 Little Bird", "helicopter", {"climb_speed": 0.35, "tilt": 15, "bank": 8, "crash_speed": 0.45, "crash_damage": 80},
        health=80, armor=0.05, max_speed=1.3, max_reverse_speed=0.35, acceleration=0.035, braking=0.05, drag=0.02, turn_speed=4.5,
        collision_damage=15, death_explosion=3.0, camera_distance=10, fuel={"capacity": 30000, "consumption": 1},
        parts={"hull": {"box": [-0.8, 0.45, -0.6, 0.8, 2.1, 1.9], "armor": 0.05, "role": "hull"},
               "skids": {"box": [-0.85, 0, -1.0, 0.85, 0.45, 1.75], "armor": 0.05, "role": "hull"},
               "engine": {"box": [-0.55, 1.15, -1.5, 0.55, 2.1, -0.6], "health": 35, "armor": 0.05, "role": "engine", "core_damage": 0.4},
               "rotor": {"box": [-0.4, 2.1, -0.4, 0.4, 2.5, 0.4], "health": 35, "role": "propulsion", "core_damage": 0.3, "bones": ["rotor"]},
               "tail_boom": {"box": [-0.75, 1.4, -4.6, 0.75, 2.4, -1.5], "role": "hull"},
               "minigun": {"box": [-1.6, 0.55, -0.4, -0.8, 1.05, 1.4], "health": 25, "role": "weapon", "seat": 0, "core_damage": 0.05},
               "rocket_pod": {"box": [0.8, 0.55, -0.4, 1.6, 1.05, 1.0], "health": 25, "role": "weapon", "seat": 1, "core_damage": 0.05}}),
        seats=lambda m: [fixed_seat(m, "minigun", [0.3, 0.8, 0.5], f"{V}:m134"), fixed_seat(m, "rockets", [-0.3, 0.8, 0.5], f"{V}:hydra70")]),
}

# Cargo slots per vehicle (chest rows of 9; upgrades in the "cargo" slot add more).
STORAGE = {"jeep": 9, "humvee": 18, "m35": 54, "btr80": 18, "m2_bradley": 27, "m1_abrams": 9, "t72": 9, "uh1": 27}
for _vid, _slots in STORAGE.items():
    VEHICLES[_vid]["definition"]["storage"] = _slots

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
    "cannon_barrel": dict(name="Tank Gun Barrel", pattern=["BBBBBB"], icon=[box(-0.1, 0.3, -1.0, 0.1, 0.5, 1.0, "metal"), box(-0.18, 0.22, -1.0, 0.18, 0.58, -0.6, "olive_dark")]),
    # Aircraft parts.
    "propeller": dict(name="Propeller", pattern=[" W ", "WIW", " W "], icon=[box(-0.1, 0, -0.1, 0.1, 1.6, 0.1, "black"), box(-0.8, 0.7, -0.1, 0.8, 0.9, 0.1, "black"), box(-0.2, 0.6, -0.25, 0.2, 1.0, 0.25, "steel")]),
    "rotor_blade": dict(name="Rotor Blade", pattern=["IIIIII", "  NN  "], count=2, icon=[box(-0.12, 0, -1.0, 0.12, 0.08, 1.0, "black"), box(-0.2, 0, -0.2, 0.2, 0.15, 0.2, "metal")]),
    "wing": dict(name="Wing", pattern=["IIII", " NNN"], icon=[box(-0.9, 0, -0.5, 0.9, 0.1, 0.4, "olive"), box(0.4, 0, -0.3, 0.9, 0.1, 0.5, "olive")]),
    "airframe": dict(name="Airframe", pattern=["I I I", "IIIII"], icon=[box(-0.3, 0, -1.0, 0.3, 0.6, 1.0, "steel"), box(-0.05, 0.6, -1.0, 0.05, 1.0, -0.6, "steel")]),
    "aero_engine": dict(name="Aero Engine", pattern=["PIP", "RBR", "PIP"], icon=[*vs.face_disc(0, 0.5, -0.3, 0.3, 0.5, "metal"), box(-0.15, 0.35, 0.3, 0.15, 0.65, 0.6, "steel")]),
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
    # Cargo: more storage slots.
    "cargo_rack": dict(name="Cargo Rack", slot="cargo", types=["car"], recipe=["INI", "WWW"],
                       stats={"storage_bonus": 18, "speed_multiplier": 0.97},
                       icon=[box(-0.5, 0, -0.6, 0.5, 0.08, 0.6, "metal"), box(-0.5, 0.08, -0.6, -0.42, 0.35, 0.6, "metal"),
                             box(0.42, 0.08, -0.6, 0.5, 0.35, 0.6, "metal"), box(-0.3, 0.08, -0.4, 0.2, 0.4, 0.2, "canvas")]),
    "stowage_bin": dict(name="Turret Stowage Bin", slot="cargo", types=["tank"], recipe=["III", "N N", "III"],
                        stats={"storage_bonus": 9},
                        icon=[box(-0.5, 0, -0.3, 0.5, 0.45, 0.3, "olive_dark"), box(-0.5, 0.45, -0.3, 0.5, 0.5, 0.3, "olive")]),
    "cargo_pod": dict(name="Helicopter Cargo Pod", slot="cargo", types=["helicopter"], recipe=["NIN", "I I", "NIN"],
                      stats={"storage_bonus": 18, "speed_multiplier": 0.95},
                      icon=[*vs.face_disc(0, 0.3, -0.6, 0.6, 0.3, "olive_dark"), box(-0.05, 0.6, -0.1, 0.05, 0.75, 0.1, "metal")]),
    # Aircraft.
    "supercharger": dict(name="Two-Stage Supercharger", slot="engine", types=["plane"], recipe=["RPR", "NEN"],
                         stats={"speed_multiplier": 1.12, "acceleration_multiplier": 1.3, "fuel_consumption_multiplier": 1.3},
                         icon=[*vs.face_disc(0, 0.4, -0.3, 0.3, 0.4, "steel"), box(-0.1, 0.3, 0.3, 0.1, 0.5, 0.6, "black")]),
    "uprated_rotor": dict(name="Uprated Rotor Head", slot="engine", types=["helicopter"], recipe=["III", "RER"],
                          stats={"speed_multiplier": 1.1, "acceleration_multiplier": 1.25, "turn_multiplier": 1.15},
                          icon=[box(-0.15, 0, -0.15, 0.15, 0.4, 0.15, "metal"), box(-0.08, 0.4, -0.9, 0.08, 0.45, 0.9, "black"),
                                box(-0.9, 0.45, -0.08, 0.9, 0.5, 0.08, "black")]),
    "cockpit_armor": dict(name="Cockpit Armour", slot="armor", types=["plane", "helicopter"], recipe=["AAA", "A A"],
                          stats={"armor_bonus": 0.15, "health_multiplier": 1.25, "speed_multiplier": 0.95},
                          icon=[box(-0.5, 0, -0.5, 0.5, 0.12, 0.5, "olive_dark"), box(-0.5, 0.12, 0.38, 0.5, 0.7, 0.5, "olive_dark"),
                                box(-0.3, 0.3, 0.37, 0.3, 0.6, 0.38, "glass")]),
    "drop_tank": dict(name="Drop Tank", slot="tank", types=["plane"], recipe=["NIN", "I I", " I "],
                      stats={"fuel_capacity_multiplier": 1.6, "speed_multiplier": 0.97},
                      icon=[*vs.face_disc(0, 0.3, -0.7, 0.6, 0.25, "steel"), box(-0.02, 0.3, 0.6, 0.02, 0.6, 0.75, "metal")]),
    # Tanks.
    "side_skirts": dict(name="Side Skirts", slot="armor", types=["tank"], recipe=["AAAA", "N  N"],
                        stats={"armor_bonus": 0.03, "health_multiplier": 1.3, "speed_multiplier": 0.95},
                        icon=[box(-0.6, 0, -0.05, 0.6, 0.6, 0.05, "olive_dark"), box(-0.6, 0.6, -0.08, 0.6, 0.65, 0.08, "metal")]),
}

# Vehicle recipes: letters → parts.
RECIPE_PARTS = dict(W="wheel", K="track", E="engine", S="seat", C="chassis", H="heavy_chassis", A="armor_plate",
                    T="turret_ring", B="cannon_barrel", G="mg_mount", D="diesel_engine",
                    P="propeller", O="rotor_blade", L="wing", F="airframe")
VEHICLE_ENGINE = {"m1_abrams": "diesel_engine", "t72": "diesel_engine", "m2_bradley": "diesel_engine", "uh1": "aero_engine", "ah6": "aero_engine"}
RAW = {"I": "minecraft:iron_ingot", "N": "minecraft:iron_nugget", "B": "minecraft:iron_block", "K": "minecraft:black_dye",
       "P": "minecraft:piston", "R": "minecraft:redstone", "C": "minecraft:copper_ingot", "J": "minecraft:leather",
       "U": "minecraft:gunpowder", "T": "minecraft:tnt", "G": "minecraft:gold_ingot", "c": "minecraft:copper_nugget",
       "Q": "minecraft:quartz", "L": "minecraft:glass_pane", "H": "minecraft:paper", "S": "minecraft:smooth_stone", "W": "#minecraft:planks",
       "F": "minecraft:fire_charge", "Y": "minecraft:string", "Z": "minecraft:white_wool"}


# ------------------------------------------------------------------------------------------- weapons and ammunition
# Ids starting with "@:" are this pack's: the namespace is filled in when writing (tables are built before configure()).
def sounds_for(kind):
    return {"shoot": f"@:vehicle.{kind}.shoot", "reload": f"@:vehicle.{'cannon.reload' if kind == 'cannon' else 'reload'}",
            "empty": "@:vehicle.empty"}


def scope(kind, **extra):
    return {"overlay": f"@:textures/scope/{kind}.png", **extra}


def mounted_gun(name, damage, rpm, mode, reload, velocity, spread, zoom, sight, sound, **extra):
    """A vehicle weapon: aimed through its gunner's sight ([sight] overlay) with [zoom], fired only from its seat."""
    gun = {"name": name, "mounted": True, "category": "mounted", "damage": damage, "rpm": rpm, "fire_mode": mode, "reload_ticks": reload,
           "velocity": velocity, "spread": spread, "ads_spread": spread * 0.4, "ads_zoom": zoom, "scope": sight,
           "lifetime_ticks": 80, "sounds": sounds_for(sound), **extra}
    if sight is None:
        del gun["scope"]
    return gun


def mortar_gun(name, velocity, reload=30):
    """Muzzle-loaded mortar: lobs its bombs (projectile ammo); no optic, aim by elevation (the HUD shows the range)."""
    return mounted_gun(name, 0, 30, "semi", reload, velocity, 1.2, 1.0, None, "mortar", gravity=0.05, lifetime_ticks=400,
                       recoil={"pitch": 1.0, "yaw": 0.3}, tracer=None)


def cannon(name, damage, reload, velocity, zoom, sight, recoil=3.0):
    return mounted_gun(name, damage, 8, "semi", reload, velocity, 0.2, zoom, sight, "cannon", gravity=0.01, headshot_multiplier=1.0,
                       recoil={"pitch": recoil, "yaw": 0.5}, tracer={"color": "#FFE0A0", "width": 0.12, "length": 4})


def mg(name, damage, rpm, velocity, spread=1.2, zoom=1.6, sight=None, sound="hmg"):
    return mounted_gun(name, damage, rpm, "auto", 80, velocity, spread, zoom, sight or scope("mg_ring"), sound,
                       gravity=0.015, lifetime_ticks=60, recoil={"pitch": 0.4, "yaw": 0.3})


MOUNTED_GUNS = {
    "m2_mounted": mg("M2 Browning (mounted)", 11, 550, 16),
    "nsvt": mg("NSVT Utyos (mounted)", 11, 700, 16),
    "kpvt": mg("KPVT 14.5mm", 16, 600, 17, spread=0.9, zoom=2.5, sight=scope("tank_soviet")),
    "m242": {**mg("M242 Bushmaster 25mm", 18, 200, 15, spread=0.6, zoom=3.0, sight=scope("tank_modern", thermal=True), sound="autocannon"),
             "fire_modes": ["semi", "auto"], "fire_mode": "auto"},
    "m256": cannon("M256 120mm Cannon", 45, 100, 6, 3.0, scope("tank_modern", thermal=True)),
    "m252_tube": mortar_gun("M252 81mm Mortar", 2.4),
    "2a46": cannon("2A46M 125mm Cannon", 48, 120, 6, 2.8, scope("tank_soviet")),
    # Helicopter weapons: door guns, and the Little Bird's minigun and rocket pod (fixed, fired along the nose).
    "m60d": mg("M60D Door Gun", 8, 600, 17, spread=1.2, zoom=1.4),
    "m134": mg("M134 Minigun", 6, 1200, 17, spread=1.8, zoom=1.2),
    # Aircraft bombs (a seat's secondary weapon): dropped with the plane's velocity, loaded with loose bombs.
    "bomb_rack": mounted_gun("Bomb Rack", 0, 60, "semi", 40, 0.0, 0.0, 1.0, None, "bomb", drop=True, tracer=None),
    "bomb_bay": mounted_gun("Bomb Racks (4)", 0, 120, "semi", 80, 0.0, 0.0, 1.0, None, "bomb", drop=True, tracer=None),
    # Anti-aircraft: twin 23mm, both barrels fire per shot; HE-FRAG rounds burst next to aircraft.
    "mk19": mounted_gun("Mk 19 Grenade Launcher", 0, 350, "auto", 100, 2.4, 1.0, 1.4, scope("mg_ring"), "autocannon", gravity=0.02,
                        lifetime_ticks=200, recoil={"pitch": 0.6, "yaw": 0.3}, tracer=None),
    "sentry_m240": mg("M240 (sentry)", 8, 650, 17, spread=1.0, zoom=1.4),
    "sentry_23mm": mounted_gun("Twin 23mm (sentry)", 12, 900, "auto", 100, 18, 0.8, 2.0, scope("aa_ring"), "autocannon", pellets=2, gravity=0.012,
                               lifetime_ticks=30, recoil={"pitch": 0.25, "yaw": 0.25}, tracer={"color": "#FFB040", "width": 0.08, "length": 5}),
    # Artillery: shells and rockets fly as projectiles; laid with the keys or the artillery map, no optic.
    "m777_gun": mounted_gun("M776 155mm Howitzer", 0, 6, "semi", 120, 2.6, 0.3, 1.0, None, "cannon", lifetime_ticks=600,
                            recoil={"pitch": 1.5, "yaw": 0.4}, tracer=None),
    "type63_launcher": mounted_gun("Type 63 Launcher (12 tubes)", 0, 120, "auto", 160, 2.2, 1.2, 1.0, None, "rocket", lifetime_ticks=600,
                                   recoil={"pitch": 0.3, "yaw": 0.2}, tracer=None),
    "zu23": mounted_gun("ZU-23-2 Twin 23mm", 12, 1000, "auto", 100, 18, 0.8, 2.0, scope("aa_ring"), "autocannon", pellets=2, gravity=0.012,
                        lifetime_ticks=30, recoil={"pitch": 0.25, "yaw": 0.25}, tracer={"color": "#FFB040", "width": 0.08, "length": 5}),
    "hydra70": mounted_gun("Hydra 70 Rocket Pod", 0, 300, "auto", 120, 3.0, 0.8, 1.2, scope("mg_ring"), "rocket", gravity=0.005,
                           lifetime_ticks=200, recoil={"pitch": 0.3, "yaw": 0.2}, tracer=None),
}
# Magazines; internal = the gun's breech/autoloader, loaded with loose shells from the loader's inventory.
MAGAZINES = {
    "m2_box_100": dict(name=".50 BMG Ammo Box (100)", caliber="50bmg", capacity=100, guns=["m2_mounted"], recipe=["III", "IKI"]),
    "nsvt_box_50": dict(name="12.7×108mm Ammo Box (50)", caliber="127x108", capacity=50, guns=["nsvt"], recipe=["III", "KIK"]),
    "kpvt_belt_50": dict(name="14.5mm Belt Box (50)", caliber="145x114", capacity=50, guns=["kpvt"], recipe=["IIII", "IKKI"]),
    "m242_box_75": dict(name="25mm Ammo Box (75)", caliber="25x137", capacity=75, guns=["m242"], recipe=["IIII", "I  I", "IIII"]),
    "120mm_breech": dict(name="Breech (1)", caliber="120mm", capacity=1, guns=["m256"], internal=True),
    "125mm_autoloader": dict(name="Autoloader (1)", caliber="125mm", capacity=1, guns=["2a46"], internal=True),
    "81mm_tube": dict(name="Mortar Tube (1)", caliber="81mm", capacity=1, guns=["m252_tube"], internal=True),
    "m60_belt_200": dict(name="M60 Belt Box (200)", caliber="762x51", capacity=200, guns=["m60d", "sentry_m240"], recipe=["III", "IKI", "III"]),
    "mk19_box_32": dict(name="Mk 19 Ammo Can (32)", caliber="40x53", capacity=32, guns=["mk19"], recipe=["IIII", "IKKI"]),
    "155mm_breech": dict(name="Breech (1)", caliber="155mm", capacity=1, guns=["m777_gun"], internal=True),
    "107mm_rack_12": dict(name="Launch Tubes (12)", caliber="107mm", capacity=12, guns=["type63_launcher"], internal=True),
    "m134_can_1000": dict(name="M134 Ammo Can (1000)", caliber="762x51", capacity=1000, guns=["m134"], recipe=["IIII", "IKKI", "IIII"]),
    "hydra_pod_7": dict(name="Hydra 70 Rocket Pod (7)", caliber="70mm", capacity=7, guns=["hydra70"], recipe=["III", "I I", "III"]),
    "bomb_rack_1": dict(name="Bomb Rack (1)", caliber="bomb", capacity=1, guns=["bomb_rack"], internal=True),
    "bomb_bay_4": dict(name="Bomb Racks (4)", caliber="bomb", capacity=4, guns=["bomb_bay"], internal=True),
    "zu23_box_50": dict(name="ZU-23 Ammo Box (50)", caliber="23x152", capacity=50, guns=["zu23", "sentry_23mm"], recipe=["IIII", "IKKI", "IIII"]),
}
MAG_ICONS = {  # ammo boxes: body colour
    "m2_box_100": "olive", "nsvt_box_50": "soviet_green", "kpvt_belt_50": "soviet_green", "m242_box_75": "olive",
    "m60_belt_200": "olive", "m134_can_1000": "olive_dark", "hydra_pod_7": "olive_dark", "zu23_box_50": "soviet_green", "mk19_box_32": "olive_dark",
}
# Machine gun / autocannon cartridges: built like small-arms rounds (Basic pack's tips, casing class "heavy").
CARTRIDGES = {
    "127x108": dict(name="12.7×108mm", cls="heavy", band="steel", types=["fmj", "ap", "tracer", "api", "hei"]),
    "145x114": dict(name="14.5×114mm", cls="heavy", band="green", types=["fmj", "ap", "tracer", "api", "hei"]),
    "25x137": dict(name="25×137mm", cls="heavy", band="olive", types=["fmj", "ap", "tracer", "api", "hei"]),
    "23x152": dict(name="23×152mm", cls="heavy", band="red", types=["flak", "hei", "api", "ap", "tracer"]),
    # Extra rounds for calibers of other packs (their casings are reused): HEI for the .50 BMG of the M2s and the P-51.
    "50bmg": dict(name=".50 BMG", cls="heavy", band="brass", types=["hei"], casing_ns="flansbasic"),
}
CARTRIDGE_CASINGS = {"127x108": (["NNN", "N  "], 4), "145x114": (["CCC", "C  "], 3), "25x137": (["CCC", "CC "], 2), "23x152": (["CCC", "C C"], 2)}
# Anti-aircraft HE-FRAG: a proximity/time-fuzed shell (the mod's AmmoDefinition.flak) with this pack's fuze as its tip.
base.AMMO_TYPES["flak"] = dict(suffix="HE-FRAG", tip="fuze_flak", colour="red", damage_multiplier=0.6,
                               flak={"proximity": 3.0, "power": 1.6, "fuse_ticks": 14})
# Explosive rounds for vehicle and aircraft guns: a small blast on impact (the mod's AmmoDefinition.explosion), HEI also
# sets fire. Rifle-calibre "explosive" (observation rounds of WW2 aircraft guns), heavy-calibre "hei".
base.AMMO_TYPES["explosive"] = dict(suffix="Explosive", tip="bullet_explosive", colour="orange", damage_multiplier=0.8, explosion=0.6)
base.AMMO_TYPES["hei"] = dict(suffix="HEI", tip="bullet_hei", colour="green", damage_multiplier=0.9, explosion=1.0, fire_seconds=3)
OWN_TIPS = {"fuze_flak": dict(name="Proximity Fuze (HE-FRAG)", pattern=["T", "R", "C"], count=4, colour="red"),
            "bullet_explosive": dict(name="Explosive Bullet", pattern=["U", "N"], count=8, colour="orange"),
            "bullet_hei": dict(name="HEI Projectile", pattern=["U", "F", "N"], count=8, colour="green")}
# Tank shells: casing + 2 gunpowder + warhead/penetrator. Types: effects and the component (warhead) they use.
SHELL_TYPES = {
    "heat": dict(suffix="HEAT", warhead="warhead_heat", colour="olive_dark", projectile=True, explosion=4.0),
    "apfsds": dict(suffix="APFSDS", warhead="dart_apfsds", colour="black", armor_piercing=True, velocity_multiplier=1.6,
                   tracer={"color": "#FFFFFF", "width": 0.1, "length": 6}),
    "apcbc": dict(suffix="APCBC", warhead="shot_apcbc", colour="black", armor_piercing=True, velocity_multiplier=1.2, damage_multiplier=1.1,
                  tracer={"color": "#FF6030", "width": 0.1, "length": 5}),
    "he": dict(suffix="HE", warhead="warhead_he_shell", colour="olive_drab", projectile=True, explosion=3.2),
    # Mortar bombs: high arcs (gravity of the flying bomb), HE or a smoke screen.
    "mortar_he": dict(suffix="HE", warhead="warhead_mortar_he", colour="olive_drab", projectile=True, explosion=3.0, gravity=0.05),
    "mortar_smoke": dict(suffix="Smoke", warhead="warhead_mortar_smoke", colour="white", projectile=True, gravity=0.05,
                         smoke={"radius": 6.0, "duration_ticks": 400}),
    # Aircraft rockets: nearly flat flight.
    "rocket_he": dict(suffix="HE", noun="Rocket", warhead="warhead_rocket_he", colour="olive_drab", projectile=True, explosion=2.8, gravity=0.005),
    # Artillery: howitzer shells, rocket-artillery rockets, automatic grenades.
    "howitzer_he": dict(suffix="HE", warhead="warhead_he_shell", colour="olive_drab", projectile=True, explosion=4.5, gravity=0.05),
    "howitzer_smoke": dict(suffix="Smoke", warhead="warhead_mortar_smoke", colour="white", projectile=True, gravity=0.05,
                           smoke={"radius": 7.0, "duration_ticks": 500}),
    "rocket_art_he": dict(suffix="HE", noun="Rocket", warhead="warhead_rocket_he", colour="olive_drab", projectile=True, explosion=3.5, gravity=0.04),
    "gmg_he": dict(suffix="HEDP", noun="Grenade", warhead="warhead_rocket_he", colour="olive", projectile=True, explosion=1.6, gravity=0.02),
    # Aircraft bombs: dropped (no muzzle velocity), so they need real gravity.
    "bomb_he": dict(suffix="HE", noun="Bomb", warhead="bomb_filling_he", colour="olive_drab", projectile=True, explosion=5.0, gravity=0.04),
    "bomb_incendiary": dict(suffix="Incendiary", noun="Bomb", warhead="bomb_filling_incendiary", colour="red", projectile=True, explosion=2.5,
                            fire=True, gravity=0.04),
}
WARHEADS = {
    "warhead_heat": dict(name="HEAT Warhead", pattern=["T", "C", "T"], count=2,
                         icon=[box(-0.2, 0, -0.2, 0.2, 0.5, 0.2, "olive_dark"), box(-0.1, 0.5, -0.1, 0.1, 1.0, 0.1, "olive_dark")]),
    "dart_apfsds": dict(name="APFSDS Penetrator", pattern=["I", "B", "I"], count=2,
                        icon=[box(-0.05, 0, -0.05, 0.05, 1.2, 0.05, "black"), box(-0.2, 0, -0.02, 0.2, 0.3, 0.02, "black")]),
    "shot_apcbc": dict(name="APCBC Shot", pattern=["N", "I", "I"], count=2,
                       icon=[box(-0.2, 0, -0.2, 0.2, 0.6, 0.2, "black"), box(-0.12, 0.6, -0.12, 0.12, 0.85, 0.12, "red")]),
    "warhead_he_shell": dict(name="HE Shell Body", pattern=["I", "T", "C"], count=2,
                             icon=[box(-0.2, 0, -0.2, 0.2, 0.6, 0.2, "olive_drab"), box(-0.1, 0.6, -0.1, 0.1, 0.9, 0.1, "bronze")]),
    "warhead_mortar_he": dict(name="Mortar Bomb Body (HE)", pattern=["N", "T", "N"], count=4,
                              icon=[box(-0.15, 0, -0.15, 0.15, 0.4, 0.15, "olive_drab"), box(-0.08, 0.4, -0.08, 0.08, 0.55, 0.08, "bronze")]),
    "warhead_rocket_he": dict(name="Rocket Warhead (HE)", pattern=["T", "I"], count=2,
                              icon=[box(-0.1, 0, -0.1, 0.1, 0.35, 0.1, "olive_drab"), box(-0.05, 0.35, -0.05, 0.05, 0.5, 0.05, "olive_drab")]),
    "bomb_filling_he": dict(name="Bomb Filling (HE)", pattern=["TTT", "TUT"], count=1,
                            icon=[box(-0.25, 0, -0.25, 0.25, 0.5, 0.25, "olive_drab"), box(-0.15, 0.5, -0.15, 0.15, 0.6, 0.15, "bronze")]),
    "bomb_filling_incendiary": dict(name="Bomb Filling (Incendiary)", pattern=["TFT", "FUF"], count=1,
                                    icon=[box(-0.25, 0, -0.25, 0.25, 0.5, 0.25, "red"), box(-0.15, 0.5, -0.15, 0.15, 0.6, 0.15, "bronze")]),
    "warhead_mortar_smoke": dict(name="Mortar Bomb Body (Smoke)", pattern=["N", "H", "N"], count=4,
                                 icon=[box(-0.15, 0, -0.15, 0.15, 0.4, 0.15, "white"), box(-0.08, 0.4, -0.08, 0.08, 0.55, 0.08, "bronze")]),
}
# caliber: name, shell length/radius (icon), casing recipe, shell types
SHELLS = {
    "120mm": dict(name="120mm Tank Shell", length=1.5, radius=0.24, casing=["C C", "C C", "CCC"], types=["heat", "apfsds"]),
    "125mm": dict(name="125mm Tank Shell", length=1.55, radius=0.25, casing=["C C", "CCC", "CCC"], types=["heat", "apfsds"]),
    "81mm": dict(name="81mm Mortar Bomb", label="81mm Mortar", casing_name="81mm Mortar Tail (Fins + Charge)", length=0.55, radius=0.08,
                 casing=["N N", " I "], types=["mortar_he", "mortar_smoke"]),
    "155mm": dict(name="155mm Howitzer Shell", label="155mm", length=1.4, radius=0.2, casing=["C C", "CCC", "C C"], types=["howitzer_he", "howitzer_smoke"]),
    "107mm": dict(name="107mm Rocket", label="107mm", casing_name="107mm Rocket Motor", length=1.0, radius=0.1, casing=["NUN", "NUN", "N N"],
                  types=["rocket_art_he"]),
    "40x53": dict(name="40×53mm Grenade", label="40×53mm", casing_name="40×53mm Grenade Casing", length=0.35, radius=0.06, casing=["c c", "ccc"],
                  types=["gmg_he"]),
    "bomb": dict(name="Aircraft Bomb", label="250 kg", casing_name="Bomb Casing (with Tail Fins)", length=1.3, radius=0.2,
                 casing=["III", "N N"], types=["bomb_he", "bomb_incendiary"]),
    "70mm": dict(name="Hydra 70 Rocket", label="Hydra 70", casing_name="Hydra 70 Rocket Motor", length=1.1, radius=0.07,
                 casing=["NUN", "NUN"], types=["rocket_he"]),
}
SIGHTS = ["mg_ring", "tank_modern", "tank_soviet", "aa_ring"]


# ------------------------------------------------------------------------------------------- item models
def item_model(name, boxes, gui_rotation=(25, 135, 0), display=None):
    """3D vanilla element model built from vehicle-space boxes, scaled into the 0..16 item space ([display] replaces
    single display contexts)."""
    lo = [min(b[i] for b in boxes) for i in range(3)]
    hi = [max(b[i + 3] for b in boxes) for i in range(3)]
    scale = 15.0 / max(hi[i] - lo[i] for i in range(3))
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    elements = []
    for b in boxes:
        tx, ty = vs.MATERIALS[b[6]]
        # Vehicle [right, up, forward] → item [x, y, z] with the front facing west (-X) in the GUI view.
        frm = [8 + (b[2] - centre[2]) * -scale, 8 + (b[1] - centre[1]) * scale, 8 + (b[0] - centre[0]) * scale]
        to = [8 + (b[5] - centre[2]) * -scale, 8 + (b[4] - centre[1]) * scale, 8 + (b[3] - centre[0]) * scale]
        frm, to = [min(a, c) for a, c in zip(frm, to)], [max(a, c) for a, c in zip(frm, to)]
        u, v = tx * 2 + 0.25, ty * 4 + 0.25  # one tile of the 128x64 texture = 2x4 units of the 16x16 UV space
        face = {"uv": [u, v, u + 1.5, v + 3.5], "texture": "#m"}
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
                    "firstperson_righthand": {"rotation": [0, 45, 0], "scale": [0.4, 0.4, 0.4]}, **(display or {})}})


def shell_icon(length, radius, colour):
    """A shell standing up: brass casing (60%), then the projectile in its colour."""
    c = length * 0.55
    return [box(-radius, 0, -radius, radius, c, radius, "bronze"), box(-radius * 1.12, 0, -radius * 1.12, radius * 1.12, 0.06, radius * 1.12, "bronze"),
            box(-radius * 0.9, c, -radius * 0.9, radius * 0.9, length * 0.85, radius * 0.9, colour),
            box(-radius * 0.45, length * 0.85, -radius * 0.45, radius * 0.45, length, radius * 0.45, colour)]


def ammo_box_icon(colour):
    return [box(-0.2, 0, -0.35, 0.2, 0.45, 0.35, colour), box(-0.15, 0.45, -0.3, 0.15, 0.5, 0.3, "metal"), box(-0.05, 0.5, -0.1, 0.05, 0.55, 0.1, "black")]


# ------------------------------------------------------------------------------------------- recipes
def part_ingredient(part_id, ns=None):
    return {"fabric:type": "fabric:components", "base": "flansmod:part", "components": {"flansmod:part": f"{ns or NS}:{part_id}"}}


def bench(name, pattern, key, result):
    base.bench(name, pattern, key, result, extend=True)


def raw_key(pattern, extra=None):
    key = {ch: RAW[ch] for row in pattern for ch in row if ch != " " and ch in RAW}
    key.update(extra or {})
    return key


# ------------------------------------------------------------------------------------------- sounds
def sound_events():
    def s(name, volume=1.0, pitch=1.0):
        return {"name": f"minecraft:{name}", "volume": volume, "pitch": pitch}
    return {
        "vehicle.engine": [s("minecart/base", 0.6, 0.6)],
        "vehicle.tank.engine": [s("minecart/base", 0.9, 0.35)],
        "vehicle.hmg.shoot": [s("random/explode3", 0.8, 1.6)],
        "vehicle.autocannon.shoot": [s("random/explode3", 1.4, 1.1)],
        "vehicle.cannon.shoot": [s("random/explode1", 2.5, 0.5), s("random/explode2", 2.5, 0.5)],
        "vehicle.cannon.reload": [s("block/iron_door/close1", 1.0, 0.6)],
        "vehicle.mortar.shoot": [s("random/explode4", 1.2, 1.4), s("fireworks/launch1", 1.5, 0.5)],
        "vehicle.bomb.shoot": [s("random/click", 1.0, 0.5), s("block/iron_door/open1", 0.8, 1.3)],
        "vehicle.rocket.shoot": [s("fireworks/launch1", 1.6, 0.6), s("fireworks/largeblast_far1", 1.2, 1.4)],
        "vehicle.plane.engine": [s("minecart/base", 0.9, 1.4)],
        "vehicle.helicopter.engine": [s("minecart/base", 1.0, 0.45)],
        "vehicle.reload": [s("item/crossbow/loading_middle1", 0.9, 0.9)],
        "vehicle.empty": [s("random/click", 0.6, 1.4)],
    }


def subtitle(event):
    return "Engine runs" if "engine" in event else "Gun fires" if "shoot" in event else "Gun reloads" if "reload" in event else "Gun clicks"


# ------------------------------------------------------------------------------------------- writers
def write_textures():
    vs.vehicle_texture(ASSETS / "textures" / "vehicle" / "vehicles.png")
    vs.vehicle_texture(ASSETS / "textures" / "item" / "vehicles.png", opaque_glass=True)  # item models can't be translucent here


def write_vehicles(vehicles, faction=None):
    texture = f"{NS}:textures/vehicle/vehicles.png"
    for vid, v in vehicles.items():
        model = v["model"]()
        write(ASSETS / "geckolib" / "models" / "vehicle" / f"{vid}.geo.json", model.geo(vid))
        definition = {**v["definition"], "seats": v["seats"](model), "model": {"texture": texture}, "icon": f"{NS}:{vid}"}
        if definition["type"] != "static":
            engine = {"tank": "tank.engine", "plane": "plane.engine", "helicopter": "helicopter.engine"}.get(definition["type"], "engine")
            definition["sounds"] = {"engine": f"{NS}:vehicle.{engine}"}
        if v.get("faction") or faction:
            definition["faction"] = v.get("faction") or faction
        write(DATA / "flansmod" / "vehicles" / f"{vid}.json", definition)
        item_model(vid, model.boxes())
        parts = {**RECIPE_PARTS, "E": v.get("engine") or VEHICLE_ENGINE.get(vid, "engine")}
        # Emplacements are made from raw materials, vehicles from vehicle parts.
        key = raw_key(v["recipe"]) if definition["type"] == "static" else \
            {ch: part_ingredient(parts[ch], VEHICLE_PARTS_NS) for row in v["recipe"] for ch in row if ch != " "}
        bench(vid, v["recipe"], key, {"id": "flansmod:vehicle", "components": {"flansmod:vehicle": f"{NS}:{vid}"}})


def write_parts(parts, category):
    for pid, p in parts.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": p["name"], "category": category, "icon": f"{NS}:{pid}"})
        item_model(pid, p["icon"])
        bench(f"part_{pid}", p["pattern"], raw_key(p["pattern"]),
              {"id": "flansmod:part", "count": p.get("count", 1), "components": {"flansmod:part": f"{NS}:{pid}"}})


def write_weapons(guns, magazines, mag_icons, factions=None):
    """Mounted guns (prefixing gun ids of magazines with this namespace) and their ammo boxes / breeches."""
    for gid, g in guns.items():
        definition = json.loads(json.dumps(g).replace('"@:', f'"{NS}:'))
        if factions and gid in factions:
            definition["faction"] = factions[gid]
        write(DATA / "flansmod" / "guns" / f"{gid}.json", definition)
    for mid, m in magazines.items():
        definition = {k: v for k, v in m.items() if k != "recipe"}
        definition["guns"] = [f"{NS}:{g}" for g in m["guns"]]
        if not m.get("internal"):
            definition["icon"] = f"{NS}:{mid}"
            item_model(mid, ammo_box_icon(mag_icons[mid]))
            bench(f"magazine_{mid}", m["recipe"], raw_key(m["recipe"]),
                  {"id": "flansmod:magazine", "components": {"flansmod:magazine": {"magazine": f"{NS}:{mid}"}}})
        write(DATA / "flansmod" / "magazines" / f"{mid}.json", definition)


def write_cartridges(calibers, casings, basic="flansbasic"):
    """Machine gun / autocannon rounds like the Basic pack's: this pack's casing + gunpowder + the Basic pack's tips."""
    base.material_atlas(ASSETS / "textures" / "item" / "materials.png")
    for cal, (pattern, count) in casings.items():
        if calibers[cal].get("casing_ns"):
            continue  # another pack's caliber: its casing is used
        pid = f"casing_{cal}"
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": f"{calibers[cal]['name']} Casing", "category": "ammo", "icon": f"{NS}:{pid}"})
        base.model3d(pid, base.casing_model(calibers[cal]["cls"]), base.SMALL_ITEM_DISPLAY)
        bench(f"part_{pid}", pattern, raw_key(pattern), {"id": "flansmod:part", "count": count, "components": {"flansmod:part": f"{NS}:{pid}"}})
    ammo = base.build_ammo(calibers)
    for aid, a in ammo.items():
        # This pack's own tips (the flak fuze) come from the Vehicles pack, the rest are the Basic pack's.
        tip = part_ingredient(a["tip"], VEHICLE_PARTS_NS) if a["tip"] in OWN_TIPS else part_ingredient(a["tip"], basic)
        fields = {k: v for k, v in a.items() if k not in ("kind", "cls", "tip", "colour", "name", "caliber", "max_stack")}
        write(DATA / "flansmod" / "ammo" / f"{aid}.json", {"name": a["name"], "caliber": a["caliber"], "icon": f"{NS}:{aid}", **fields})
        base.model3d(aid, base.ammo_model(a), base.SMALL_ITEM_DISPLAY)
        cls = base.CASING_CLASSES[a["cls"]]
        bench(f"ammo_{aid}", ["A" + "U" * cls["powder"] + "D"],
              {"A": part_ingredient(f"casing_{a['caliber']}", calibers[a["caliber"]].get("casing_ns")), "U": RAW["U"], "D": tip},
              {"id": "flansmod:ammo", "count": cls["count"], "components": {"flansmod:ammo_type": f"{NS}:{aid}"}})
    return {cal: c["name"] for cal, c in calibers.items() if not c.get("casing_ns")}


def write_shells(shells, warheads):
    """Tank shells per caliber and type (casing + 2 gunpowder + warhead), stacking to 8; explosive ones fly as projectiles."""
    for pid, p in warheads.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": p["name"], "category": "ammo", "icon": f"{NS}:{pid}"})
        item_model(pid, p["icon"])
        bench(f"part_{pid}", p["pattern"], raw_key(p["pattern"]), {"id": "flansmod:part", "count": p["count"], "components": {"flansmod:part": f"{NS}:{pid}"}})
    names = {}
    for cal, s in shells.items():
        names[cal] = s["name"]
        label = s.get("label", cal)
        casing = f"casing_{cal}"
        write(DATA / "flansmod" / "parts" / f"{casing}.json", {"name": s.get("casing_name", f"{label} Shell Casing"), "category": "ammo", "icon": f"{NS}:{casing}"})
        item_model(casing, [box(-s["radius"], 0, -s["radius"], s["radius"], s["length"] * 0.55, s["radius"], "bronze"),
                            box(-s["radius"] * 1.12, 0, -s["radius"] * 1.12, s["radius"] * 1.12, 0.06, s["radius"] * 1.12, "bronze")])
        bench(f"part_{casing}", s["casing"], raw_key(s["casing"]), {"id": "flansmod:part", "count": 2, "components": {"flansmod:part": f"{NS}:{casing}"}})
        for kind in s["types"]:
            t = SHELL_TYPES[kind]
            aid = f"{cal}_{kind}"
            definition = {"name": f"{label} {t['suffix']} {t.get('noun', 'Shell')}", "caliber": cal, "max_stack": 8, "icon": f"{NS}:{aid}"}
            definition.update({k: v for k, v in t.items() if k in ("armor_piercing", "velocity_multiplier", "damage_multiplier", "tracer")})
            if t.get("projectile"):
                definition["projectile"] = f"{NS}:{aid}"
                projectile = {"name": f"{label} {t['suffix']}", "contact": True, "throwable": False, "trail": True,
                              "gravity": t.get("gravity", 0.01), "fuse_ticks": 600, "icon": f"{NS}:{aid}"}
                if "explosion" in t:
                    projectile["explosion"] = {"power": t["explosion"], "break_blocks": True, **({"fire": True} if t.get("fire") else {})}
                if "smoke" in t:
                    projectile["smoke"] = t["smoke"]
                write(DATA / "flansmod" / "grenades" / f"{aid}.json", projectile)
            write(DATA / "flansmod" / "ammo" / f"{aid}.json", definition)
            item_model(aid, shell_icon(s["length"], s["radius"], t["colour"]), gui_rotation=(0, 0, -45))
            bench(f"ammo_{aid}", ["AUUD"], {"A": part_ingredient(casing), "U": RAW["U"], "D": part_ingredient(t["warhead"])},
                  {"id": "flansmod:ammo", "count": 1, "components": {"flansmod:ammo_type": f"{NS}:{aid}", "minecraft:max_stack_size": 8}})
    return names


def write_mines(mines, factions=None):
    """Mines are grenade definitions with a `mine` section (laid instead of thrown); their item model lies flat on the
    ground at about [size] blocks across."""
    for mid, m in mines.items():
        definition = {"name": m["name"], "explosion": m["explosion"], "mine": m["mine"], "max_stack": 8, "icon": f"{NS}:{mid}"}
        if factions and mid in factions:
            definition["faction"] = factions[mid]
        write(DATA / "flansmod" / "grenades" / f"{mid}.json", definition)
        boxes = vs.mine_icon(m["icon"], m.get("paint", "olive_drab"))
        width = max(max(b[3] for b in boxes) - min(b[0] for b in boxes), max(b[5] for b in boxes) - min(b[2] for b in boxes))
        height = max(b[4] for b in boxes) - min(b[1] for b in boxes)
        scale = round(max(0.3, m.get("size", width * 1.4)) * 16 / 15, 3)
        lift = round(15 * height / width * scale / 2, 3)  # model centred on the origin: lift its lower half above the ground
        item_model(mid, boxes, gui_rotation=(30, 45, 0), display={"ground": {"translation": [0, lift, 0], "scale": [scale] * 3},
                                                                   "gui": {"rotation": [30, 45, 0], "scale": [0.8, 0.8, 0.8]}})
        bench(f"mine_{mid}", m["recipe"], raw_key(m["recipe"]),
              {"id": "flansmod:grenade", "count": m.get("count", 1), "components": {"flansmod:grenade": f"{NS}:{mid}", "minecraft:max_stack_size": 8}})


def at_mine(name, icon, paint="olive_drab", power=4.0, vehicle_damage=400, recipe=("NIN", "ITI", "NIN"), size=0.55):
    return dict(name=name, icon=icon, paint=paint, size=size, recipe=list(recipe), explosion={"power": power, "fire": False, "break_blocks": False},
                mine={"trigger": "vehicle", "arm_ticks": 60, "radius": 0.9, "vehicle_damage": vehicle_damage})


def ap_mine(name, icon, paint="olive_drab", power=1.8, recipe=("NUN", " I "), size=0.3):
    return dict(name=name, icon=icon, paint=paint, size=size, recipe=list(recipe), count=2,
                explosion={"power": power, "fire": False, "break_blocks": False}, mine={"trigger": "personnel", "arm_ticks": 40, "radius": 0.6})


def write_sights(kinds):
    for kind in kinds:
        vs.sight_overlay(ASSETS / "textures" / "scope" / f"{kind}.png", kind)


def write_sounds(events):
    write(ASSETS / "sounds.json", {k: {"sounds": v, "subtitle": f"subtitles.{NS}.{k}"} for k, v in events.items()})
    return {f"subtitles.{NS}.{e}": subtitle(e) for e in events}


def write_own_tips():
    """Ammunition tips only this pack makes (the flak fuze); other packs' rounds refer to them by this namespace."""
    for pid, p in OWN_TIPS.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": p["name"], "category": "ammo", "icon": f"{NS}:{pid}"})
        base.model3d(pid, [base.el((5, 0, 5), (11, 8, 11), "olive"), base.el((6, 8, 6), (10, 12, 10), p["colour"]), base.el((7, 12, 7), (9, 14, 9), "brass")],
                     base.SMALL_ITEM_DISPLAY)
        bench(f"part_{pid}", p["pattern"], raw_key(p["pattern"]), {"id": "flansmod:part", "count": p["count"], "components": {"flansmod:part": f"{NS}:{pid}"}})


def parachute_models(gid):
    """The packed parachute (worn on the back: harness straps on -Z) and the open canopy drawn above the wearer: a
    dome of olive panels (item space, 46 pixels across) with its lines running down to the harness."""
    if gid == "parachute_canopy":
        dome = [((-15, 22, -15), (31, 24, 31), "olive"), ((-12, 24, -12), (28, 27, 28), "green"), ((-7, 27, -7), (23, 30, 23), "olive"),
                ((-1, 30, -1), (17, 32, 17), "green")]
        lines = [((x, -14, z), (x + 0.3, 22, z + 0.3), "white") for x in (0, 15.7) for z in (0, 15.7)]
        return [base.el(a, b, m) for a, b, m in dome + lines]
    return [base.el((3, 1, 6), (13, 13, 11), "olive"), base.el((3.5, 13, 6.5), (12.5, 14, 10.5), "green"), base.el((5, 2, 5), (6, 14, 6), "black"),
            base.el((10, 2, 5), (11, 14, 6), "black"), base.el((3, 6, 4.8), (13, 7, 5.2), "black"), base.el((7, 4, 11), (9, 6, 11.5), "steel")]


GEAR = {
    "t10_parachute": dict(name="T-10 Parachute", type="parachute", fall_speed=0.2, recipe=["ZZZ", "Y Y", " J "]),
}

MINES = {
    "m15_mine": at_mine("M15 Anti-Tank Mine", "m15"),
    "m14_mine": ap_mine("M14 Anti-Personnel Mine", "m14"),
}


def main():
    configure(NS, ROOT)
    if ROOT.exists():
        shutil.rmtree(ROOT)
    seed_signatures()
    write(ROOT / "pack.mcmeta", {
        "pack": {"description": "Flan's Mod: Recoded - jeeps, trucks, APCs, tanks and helicopters", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Flan's Vehicles", "icon": f"{NS}:m1_abrams"}})
    write_textures()
    write_vehicles(VEHICLES)
    write_parts(PARTS, "vehicle")
    for uid, u in UPGRADES.items():
        definition = {"name": u["name"], "slot": u["slot"], "icon": f"{NS}:{uid}", **u["stats"]}
        if u["types"]:
            definition["types"] = u["types"]
        write(DATA / "flansmod" / "vehicle_upgrades" / f"{uid}.json", definition)
        item_model(uid, u["icon"])
        # W wheel, A armour plate, E engine, D diesel engine are vehicle parts; other letters are raw materials.
        key = {ch: part_ingredient(RECIPE_PARTS[ch]) if ch in "WAED" else RAW[ch] for row in u["recipe"] for ch in row if ch != " "}
        bench(f"upgrade_{uid}", u["recipe"], key, {"id": "flansmod:vehicle_upgrade", "components": {"flansmod:vehicle_upgrade": f"{NS}:{uid}"}})
    write_weapons(MOUNTED_GUNS, MAGAZINES, MAG_ICONS)
    write_own_tips()
    calibers = write_cartridges(CARTRIDGES, CARTRIDGE_CASINGS)
    calibers.update(write_shells(SHELLS, WARHEADS))
    write_sights(SIGHTS)
    write_mines(MINES)
    # Parachute: gear definition + item model, and the open canopy's item model (drawn above the wearer).
    base.write_gear({gid: {**g, "canopy": f"{NS}:parachute_canopy"} for gid, g in GEAR.items()}, parachute_models,
                    extra_key={k: RAW[k] for k in "YZJ"})
    base.model3d("parachute_canopy", parachute_models("parachute_canopy"), {"fixed": {"scale": [1, 1, 1]}})
    # Airfield structure kits: runway and hangar.
    structure_key = {"C": "flansmod:reinforced_concrete", "G": "minecraft:gravel", "I": "minecraft:iron_ingot", "L": "minecraft:sea_lantern"}
    for sid, pattern in ss.write_structures(DATA, NS, ss.AIRFIELD).items():
        bench(f"structure_{sid}", pattern, {ch: structure_key[ch] for row in pattern for ch in row if ch != " "},
              {"id": "flansmod:structure", "components": {"flansmod:structure": f"{NS}:{sid}"}})

    lang = write_sounds(sound_events())
    lang.update({f"vehicle_upgrade.{NS}.{uid}": u["name"] for uid, u in UPGRADES.items()})
    lang.update({f"caliber.flansmod.{cal}": name for cal, name in calibers.items()})
    write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(VEHICLES)} vehicles, {len(UPGRADES)} upgrades, {len(MOUNTED_GUNS)} mounted guns, {len(PARTS)} parts in {ROOT}")


if __name__ == "__main__":
    main()
