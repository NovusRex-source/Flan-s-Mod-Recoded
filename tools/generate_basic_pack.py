#!/usr/bin/env python3
"""
Generates the built-in "Basic" content pack (src/main/resources/resourcepacks/basic): famous real-world guns
with their magazines and ammunition, attachments (incl. scopes), grenades and launcher projectiles, plus
recipes, GeckoLib models + animations, textures, icons, scope overlays, sounds and structure kits (structuresmith).

It is a normal content pack; the mod code contains no guns. Edit the tables below and re-run:
    python3 tools/generate_basic_pack.py
Requires Pillow.
"""
import json
import math
import random
import shutil
from pathlib import Path

from PIL import Image, ImageDraw

import gunsmith as gs
import structuresmith as ss

NS = "flansbasic"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/basic"
DATA = ROOT / "data" / NS
ASSETS = ROOT / "assets" / NS

# Gun models, magazines and the gun texture come from gunsmith (shared with the WW2 pack generator).
MATERIALS, COLOURS = gs.MATERIALS, gs.COLOURS


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


cube, bx = gs.cube, gs.bx


def bone(name, cubes, parent="gun"):
    b = {"name": name, "pivot": [0, 0, 0], "cubes": gs.strip(cubes)}
    if parent:
        b["parent"] = parent
    return b


# ------------------------------------------------------------------------------------------- ammunition
# Calibers: display name, casing class (icon size, gunpowder per round, rounds per craft) and a colour for magazine bands.
CALIBERS = {
    "9mm": dict(name="9×19mm Parabellum", cls="pistol", band="copper"),
    "45acp": dict(name=".45 ACP", cls="pistol", band="orange"),
    "357": dict(name=".357 Magnum", cls="pistol", band="red"),
    "50ae": dict(name=".50 AE", cls="pistol", band="white"),
    "57": dict(name="5.7×28mm", cls="pistol", band="green"),
    "556": dict(name="5.56×45mm NATO", cls="rifle", band="lens"),
    "762x39": dict(name="7.62×39mm", cls="rifle", band="red"),
    "762x51": dict(name="7.62×51mm NATO", cls="full", band="orange"),
    "762x54": dict(name="7.62×54mmR", cls="full", band="green"),
    "338": dict(name=".338 Lapua Magnum", cls="heavy", band="white"),
    "50bmg": dict(name=".50 BMG", cls="heavy", band="brass"),
    "12g": dict(name="12 Gauge", cls="shotgun", band="red"),
    "40mm": dict(name="40×46mm Grenade", cls="40mm", band="olive"),
    "rpg": dict(name="PG-7 Rocket", cls="rocket", band="olive"),
}
# Per casing class: gunpowder per craft, rounds per craft, stack size, and which ammo types exist.
CASING_CLASSES = {
    "pistol": dict(powder=1, count=16, types=["fmj", "hp", "ap"]),
    "rifle": dict(powder=2, count=12, types=["fmj", "ap", "tracer"]),
    "full": dict(powder=2, count=10, types=["fmj", "ap", "tracer", "incendiary"]),
    "heavy": dict(powder=3, count=6, types=["fmj", "ap", "tracer", "api"]),
    "shotgun": dict(powder=1, count=8, types=["buckshot", "slug", "dragon", "flechette"]),
    "40mm": dict(powder=1, count=2, types=["he"], stack=16),
    "rocket": dict(powder=0, count=1, types=["pg7"], stack=8),
}
# Ammo types: name suffix, effects, and the tip/load/warhead component they are made with (icon tip colour).
AMMO_TYPES = {
    "fmj": dict(suffix="FMJ", tip="bullet_fmj", colour="copper"),
    "hp": dict(suffix="Hollow Point", tip="bullet_hp", colour="steel", damage_multiplier=1.25, spread_multiplier=1.1),
    "ap": dict(suffix="AP", tip="bullet_ap", colour="black", armor_piercing=True, damage_multiplier=0.9),
    "tracer": dict(suffix="Tracer", tip="bullet_tracer", colour="red", damage_multiplier=0.95, tracer={"color": "#FF4020", "width": 0.06, "length": 5}),
    "incendiary": dict(suffix="Incendiary", tip="bullet_incendiary", colour="lens", damage_multiplier=0.9, fire_seconds=4),
    "api": dict(suffix="API", tip="bullet_api", colour="white", armor_piercing=True, fire_seconds=4),
    "buckshot": dict(suffix="Buckshot", tip="load_buckshot", colour="red", pellets=8),
    "slug": dict(suffix="Slug", tip="load_slug", colour="lens", pellets=1, damage_multiplier=4.0, spread_multiplier=0.3),
    "dragon": dict(suffix="Dragon's Breath", tip="load_dragon", colour="orange", pellets=8, fire_seconds=5, damage_multiplier=0.6,
                   tracer={"color": "#FF8A20", "width": 0.08, "length": 2}),
    "flechette": dict(suffix="Flechette", tip="load_flechette", colour="green", pellets=12, armor_piercing=True, damage_multiplier=0.7),
    "he": dict(suffix="HE", tip="warhead_he", colour="olive", projectile="40mm_he"),
    "pg7": dict(suffix="PG-7V HEAT", tip="warhead_pg7", colour="olive", projectile="rocket"),
}
# Ids of the plain round of each caliber (kept from earlier versions); other types are <caliber>_<type>.
PLAIN = {"fmj", "buckshot", "he", "pg7"}
LEGACY_IDS = {("12g", "dragon"): "12g_dragon", ("40mm", "he"): "40mm_he", ("rpg", "pg7"): "pg7"}


def ammo_id(caliber, kind):
    return LEGACY_IDS.get((caliber, kind)) or (caliber if kind in PLAIN else f"{caliber}_{kind}")


def build_ammo(calibers):
    """Every ammo type of every caliber (by its casing class); shared with the WW2 pack generator."""
    ammo = {}
    for cal, c in calibers.items():
        cls = CASING_CLASSES[c["cls"]]
        for kind in c.get("types", cls["types"]):
            t = AMMO_TYPES[kind]
            effects = {k: v for k, v in t.items() if k not in ("suffix", "tip", "colour")}
            # The standard round is called just like its caliber ("5.56×45mm NATO"); special types add their name.
            name = c["name"] if c["cls"] == "rocket" or kind == "fmj" else f"{c['name']} {t['suffix']}"
            ammo[ammo_id(cal, kind)] = dict(name=name, caliber=cal, kind=kind, cls=c["cls"], tip=t["tip"], colour=t["colour"],
                                            max_stack=cls.get("stack", 64), **effects)
    return ammo


AMMO = build_ammo(CALIBERS)

# Ammunition components (parts of category "ammo"): one casing per caliber, and tips/loads/warheads by ammo type.
# Recipes: c copper nugget, C copper ingot, N iron nugget (steel cases), H paper, I iron ingot, U gunpowder.
CASINGS = {
    "9mm": (["cc"], 16), "45acp": (["ccc"], 16), "357": (["c", "c"], 16), "50ae": (["c", "c", "c"], 12), "57": (["cN"], 16),
    "556": (["C"], 12), "762x39": (["NN"], 12), "762x51": (["C", "C"], 10), "762x54": (["NNN"], 10), "338": (["CC"], 6),
    "50bmg": (["CCC"], 4), "12g": (["H", "c"], 8), "40mm": (["C C", "CCC"], 4), "rpg": (["I", "U", "I"], 2),
}
CASING_NAMES = {"12g": "12 Gauge Hull", "40mm": "40mm Grenade Casing", "rpg": "Rocket Motor"}
TIPS = {
    "bullet_fmj": ("Bullet (FMJ)", ["N", "c"], 16), "bullet_hp": ("Bullet (Hollow Point)", ["c", "N", "c"], 16),
    "bullet_ap": ("AP Core", ["I"], 8), "bullet_tracer": ("Tracer Bullet", ["R", "N"], 16),
    "bullet_incendiary": ("Incendiary Bullet", ["Z", "N"], 16), "bullet_api": ("API Bullet", ["Z", "I"], 8),
    "load_buckshot": ("Buckshot Load", ["NNN", "N N"], 8), "load_slug": ("Slug", ["I", "N"], 8),
    "load_dragon": ("Dragon's Breath Load", ["Z", "Z", "N"], 8), "load_flechette": ("Flechette Load", ["N N", " N "], 8),
    "warhead_he": ("40mm HE Warhead", ["T", "c"], 4), "warhead_pg7": ("PG-7V Warhead", ["T", "C"], 2),
}


# ------------------------------------------------------------------------------------------- magazines
# id: name, caliber, capacity, kind (icon/model), guns, reload multiplier
MAGAZINES = {
    "glock_17": dict(name="Glock Magazine (17)", caliber="9mm", capacity=17, kind="pistol", guns=["glock17"]),
    "glock_33": dict(name="Glock Extended Magazine (33)", caliber="9mm", capacity=33, kind="stick", guns=["glock17"], reload=1.1),
    "m1911": dict(name="M1911 Magazine (7)", caliber="45acp", capacity=7, kind="pistol", guns=["m1911"]),
    "deagle": dict(name="Desert Eagle Magazine (7)", caliber="50ae", capacity=7, kind="pistol", guns=["deagle"]),
    "speedloader_357": dict(name=".357 Speedloader (6)", caliber="357", capacity=6, kind="loader", guns=["revolver"]),
    "mp5_30": dict(name="MP5 Magazine (30)", caliber="9mm", capacity=30, kind="stick", guns=["mp5"]),
    "uzi_32": dict(name="UZI Magazine (32)", caliber="9mm", capacity=32, kind="stick", guns=["uzi"]),
    "p90_50": dict(name="P90 Magazine (50)", caliber="57", capacity=50, kind="box", guns=["p90"]),
    "thompson_30": dict(name="Thompson Stick Magazine (30)", caliber="45acp", capacity=30, kind="stick", guns=["thompson"]),
    "thompson_drum_50": dict(name="Thompson Drum (50)", caliber="45acp", capacity=50, kind="drum", guns=["thompson"], reload=1.4),
    "stanag_30": dict(name="STANAG Magazine (30)", caliber="556", capacity=30, kind="stick", guns=["m4a1", "m16a4", "m249"]),
    "stanag_drum_100": dict(name="5.56 Drum (100)", caliber="556", capacity=100, kind="drum", guns=["m4a1", "m16a4"], reload=1.6),
    "m249_box_200": dict(name="M249 Box (200)", caliber="556", capacity=200, kind="box", guns=["m249"], reload=1.8),
    "ak_30": dict(name="AK Magazine (30)", caliber="762x39", capacity=30, kind="curved", guns=["ak47"]),
    "ak_drum_75": dict(name="AK Drum (75)", caliber="762x39", capacity=75, kind="drum", guns=["ak47"], reload=1.5),
    "scar_20": dict(name="SCAR-H Magazine (20)", caliber="762x51", capacity=20, kind="stick", guns=["scar_h"]),
    "m14_20": dict(name="M14 Magazine (20)", caliber="762x51", capacity=20, kind="stick", guns=["m14"]),
    "svd_10": dict(name="SVD Magazine (10)", caliber="762x54", capacity=10, kind="stick", guns=["svd"]),
    "pkm_box_100": dict(name="PKM Box (100)", caliber="762x54", capacity=100, kind="box", guns=["pkm"], reload=1.8),
    "m24_5": dict(name="M24 Magazine (5)", caliber="762x51", capacity=5, kind="pistol", guns=["m24"]),
    "awm_5": dict(name="AWM Magazine (5)", caliber="338", capacity=5, kind="pistol", guns=["awm"]),
    "barrett_10": dict(name="M82 Magazine (10)", caliber="50bmg", capacity=10, kind="stick", guns=["barrett"]),
    # internal: built into the gun (tube, breech); reloading loads loose rounds, there is no magazine item.
    "shell_holder_6": dict(name="Tube Magazine (6)", caliber="12g", capacity=6, kind="shells", guns=["m870"], internal=True),
    "spas_8": dict(name="Tube Magazine (8)", caliber="12g", capacity=8, kind="shells", guns=["spas12"], internal=True),
    "aa12_8": dict(name="AA-12 Box (8)", caliber="12g", capacity=8, kind="box", guns=["aa12"]),
    "aa12_drum_20": dict(name="AA-12 Drum (20)", caliber="12g", capacity=20, kind="drum", guns=["aa12"], reload=1.5),
    "m79_shell": dict(name="Breech (1)", caliber="40mm", capacity=1, kind="loader", guns=["m79"], internal=True),
    "pg7_loader": dict(name="Launch Tube (1)", caliber="rpg", capacity=1, kind="rocket", guns=["rpg7"], internal=True),
}
# What each magazine looks like (gunsmith.magazine kind, rounds for its length, material), on the gun and as an item.
MAG_SHAPES = {
    "glock_17": ("pistol", 17, "polymer"), "glock_33": ("pistol", 33, "polymer"), "m1911": ("pistol", 7, "steel"),
    "deagle": ("pistol", 9, "steel"), "speedloader_357": ("loader", 6, "black"), "mp5_30": ("smg_curved", 30, "steel"),
    "uzi_32": ("stick", 32, "metal"), "p90_50": ("p90", 50, "glass"), "thompson_30": ("stick", 30, "metal"),
    "thompson_drum_50": ("drum", 50, "metal"), "stanag_30": ("stanag", 30, "steel"), "stanag_drum_100": ("twin_drum", 100, "polymer"),
    "m249_box_200": ("box", 200, "olive"), "ak_30": ("ak", 30, "metal"), "ak_drum_75": ("drum", 75, "metal"),
    "scar_20": ("stick", 20, "tan"), "m14_20": ("stick", 20, "metal"), "svd_10": ("ak", 10, "metal"),
    "pkm_box_100": ("box", 100, "olive"), "m24_5": ("rifle_box", 5, "metal"), "awm_5": ("rifle_box", 5, "metal"),
    "barrett_10": ("rifle_box", 10, "metal"), "aa12_8": ("rifle_box", 8, "polymer"), "aa12_drum_20": ("drum", 20, "polymer"),
}

# ------------------------------------------------------------------------------------------- guns
# arch selects the model template; L = overall length scale; furniture = material of stock/grip/handguard.
GUNS = {
    # Pistols
    "glock17": dict(name="Glock 17", arch="pistol", L=1.0, furniture="polymer", dmg=5, rpm=450, mode="semi", reload=30, vel=10,
                    spread=1.6, recoil=(2.0, 0.6), zoom=1.2, slots=["sight", "muzzle"], sound="pistol"),
    "m1911": dict(name="M1911", arch="pistol", L=1.05, furniture="wood", dmg=6.5, rpm=380, mode="semi", reload=30, vel=9,
                  spread=1.8, recoil=(2.6, 0.7), zoom=1.2, slots=["sight", "muzzle"], sound="pistol"),
    "deagle": dict(name="Desert Eagle", arch="pistol", L=1.3, furniture="polymer", dmg=11, rpm=200, mode="semi", reload=35, vel=11,
                   spread=2.2, recoil=(5.0, 1.4), zoom=1.25, slots=["sight", "muzzle"], sound="magnum", body="steel"),
    "revolver": dict(name=".357 Revolver", arch="revolver", L=1.1, furniture="wood", dmg=9, rpm=180, mode="semi", reload=45, vel=10,
                     spread=1.5, recoil=(4.0, 1.0), zoom=1.2, slots=["sight"], sound="magnum", body="steel"),
    # SMGs
    "mp5": dict(name="MP5", arch="smg", L=1.0, furniture="polymer", dmg=4, rpm=800, mode="auto", reload=45, vel=11, spread=3,
                recoil=(0.9, 0.5), zoom=1.3, slots=["sight", "muzzle", "underbarrel"], sound="smg"),
    "uzi": dict(name="UZI", arch="smg", L=0.8, furniture="polymer", dmg=3.5, rpm=950, mode="auto", reload=40, vel=10, spread=4,
                recoil=(1.0, 0.8), zoom=1.25, slots=["sight", "muzzle"], sound="smg", mag_in_grip=True),
    "p90": dict(name="P90", arch="bullpup_smg", L=1.0, furniture="polymer", dmg=4, rpm=900, mode="auto", reload=50, vel=12,
                spread=2.6, recoil=(0.8, 0.4), zoom=1.35, slots=["sight", "muzzle"], sound="smg"),
    "thompson": dict(name="Thompson M1928", arch="smg", L=1.2, furniture="wood", dmg=5, rpm=700, mode="auto", reload=55, vel=10,
                     spread=3.2, recoil=(1.2, 0.7), zoom=1.25, slots=["muzzle"], sound="smg"),
    # Assault rifles
    "m4a1": dict(name="M4A1", arch="rifle", L=1.0, furniture="polymer", dmg=6, rpm=800, mode="auto", reload=50, vel=14, spread=2.4,
                 recoil=(1.1, 0.6), zoom=1.4, slots=["sight", "muzzle", "underbarrel"], sound="rifle"),
    "ak47": dict(name="AK-47", arch="rifle", L=1.05, furniture="wood", dmg=7, rpm=600, mode="auto", reload=55, vel=13, spread=3,
                 recoil=(1.6, 0.9), zoom=1.35, slots=["sight", "muzzle", "underbarrel"], sound="ak", curved_mag=True),
    "m16a4": dict(name="M16A4", arch="rifle", L=1.15, furniture="polymer", dmg=6.5, rpm=800, mode="burst", reload=50, vel=15,
                  spread=2, recoil=(1.0, 0.5), zoom=1.45, slots=["sight", "muzzle", "underbarrel"], sound="rifle"),
    "scar_h": dict(name="SCAR-H", arch="rifle", L=1.1, furniture="tan", dmg=8, rpm=600, mode="auto", reload=55, vel=15, spread=2.6,
                   recoil=(1.7, 0.8), zoom=1.45, slots=["sight", "muzzle", "underbarrel"], sound="battle", body="tan"),
    # DMRs
    "svd": dict(name="SVD Dragunov", arch="dmr", L=1.25, furniture="wood", dmg=13, rpm=120, mode="semi", reload=60, vel=20, spread=3,
                ads_spread=0.1, recoil=(3.2, 0.8), zoom=4.0, slots=["sight", "muzzle", "underbarrel"], sound="battle",
                scope={"overlay": f"{NS}:textures/scope/pso.png"}, ads_height=1.6),
    "m14": dict(name="M14 EBR", arch="dmr", L=1.15, furniture="polymer", dmg=9, rpm=300, mode="semi", reload=55, vel=18, spread=2.5,
                ads_spread=0.2, recoil=(2.2, 0.7), zoom=1.5, slots=["sight", "muzzle", "underbarrel"], sound="battle"),
    # Snipers
    "m24": dict(name="M24", arch="sniper", L=1.2, furniture="olive", dmg=18, rpm=40, mode="semi", reload=70, vel=25, spread=4,
                ads_spread=0.05, recoil=(6, 0.8), zoom=1.5, slots=["sight", "muzzle", "underbarrel"], sound="sniper", headshot=2.5, move=0.45),
    "awm": dict(name="AWM", arch="sniper", L=1.3, furniture="olive", dmg=22, rpm=35, mode="semi", reload=75, vel=28, spread=4,
                ads_spread=0.03, recoil=(7, 0.8), zoom=1.5, slots=["sight", "muzzle", "underbarrel"], sound="sniper", headshot=2.5, move=0.45),
    "barrett": dict(name="Barrett M82", arch="sniper", L=1.5, furniture="polymer", dmg=28, rpm=90, mode="semi", reload=80, vel=30,
                    spread=5, ads_spread=0.08, recoil=(8, 1.5), zoom=1.5, slots=["sight", "muzzle", "underbarrel"], sound="heavy", headshot=2.0, move=0.4),
    # Shotguns
    "m870": dict(name="Remington 870", arch="shotgun", L=1.0, furniture="wood", dmg=3, rpm=70, mode="semi", reload=60, vel=9, spread=7,
                 ads_spread=5, recoil=(4.5, 1.2), zoom=1.15, slots=["sight"], sound="shotgun", lifetime=15),
    "spas12": dict(name="SPAS-12", arch="shotgun", L=1.05, furniture="polymer", dmg=3, rpm=140, mode="semi", reload=60, vel=9,
                   spread=7.5, ads_spread=5.5, recoil=(4.0, 1.2), zoom=1.15, slots=["sight"], sound="shotgun", lifetime=15),
    "aa12": dict(name="AA-12", arch="shotgun", L=1.0, furniture="polymer", dmg=2.6, rpm=300, mode="auto", reload=65, vel=9, spread=8,
                 ads_spread=6, recoil=(2.5, 1.0), zoom=1.15, slots=["sight", "underbarrel"], sound="shotgun", lifetime=15, box_mag=True),
    # LMGs
    "m249": dict(name="M249 SAW", arch="lmg", L=1.1, furniture="polymer", dmg=6, rpm=800, mode="auto", reload=90, vel=14, spread=3.5,
                 recoil=(1.0, 0.8), zoom=1.35, slots=["sight", "muzzle", "underbarrel"], sound="rifle", move=0.5),
    "pkm": dict(name="PKM", arch="lmg", L=1.2, furniture="wood", dmg=8, rpm=650, mode="auto", reload=95, vel=16, spread=3.5,
                recoil=(1.4, 1.0), zoom=1.35, slots=["sight", "muzzle", "underbarrel"], sound="battle", move=0.5),
    # Launchers (fire grenade-type projectiles from their ammo)
    "m79": dict(name="M79 Grenade Launcher", arch="launcher", L=0.9, furniture="wood", dmg=0, rpm=30, mode="semi", reload=45, vel=1.6,
                spread=1, recoil=(4, 1), zoom=1.2, slots=[], sound="launcher"),
    "rpg7": dict(name="RPG-7", arch="rpg", L=1.0, furniture="wood", dmg=0, rpm=30, mode="semi", reload=70, vel=2.5,
                 spread=0.5, recoil=(3, 1), zoom=2.7, slots=["sight"], sound="rocket", move=0.5,
                 scope={"overlay": f"{NS}:textures/scope/pso.png"}),  # aimed through its PGO-7 optic
}

# ------------------------------------------------------------------------------------------- parts
# id: name, icon kind, bench recipe (pattern over raw materials), result count
PARTS = {
    "polymer": dict(name="Polymer Sheet", icon="sheet", pattern=["KSK", "KSK"], count=4),
    "short_barrel": dict(name="Short Barrel", icon="barrel", pattern=["III"]),
    "barrel": dict(name="Barrel", icon="barrel", pattern=["IIII"]),
    "long_barrel": dict(name="Long Barrel", icon="long_barrel", pattern=["IIIII"]),
    "heavy_barrel": dict(name="Heavy Barrel", icon="heavy_barrel", pattern=["BIII"]),
    "shotgun_barrel": dict(name="Shotgun Barrel", icon="heavy_barrel", pattern=["IIII", "IIII"]),
    "launcher_tube": dict(name="Launcher Tube", icon="tube", pattern=["IIIII", "     ", "IIIII"]),
    "pistol_frame": dict(name="Pistol Frame", icon="frame", pattern=["IIR", "I  "]),
    "revolver_frame": dict(name="Revolver Frame", icon="cylinder", pattern=["NNN", "IIR", "I  "]),
    "smg_receiver": dict(name="SMG Receiver", icon="receiver", pattern=["IIIR", "I   "]),
    "rifle_receiver": dict(name="Rifle Receiver", icon="receiver", pattern=["IIIIR", "II   "]),
    "battle_receiver": dict(name="Battle Rifle Receiver", icon="receiver", pattern=["IIIIR", "IB   "]),
    "sniper_receiver": dict(name="Bolt-Action Receiver", icon="receiver", pattern=["IIIIR", "I  N "]),
    "heavy_receiver": dict(name="Heavy Receiver", icon="receiver", pattern=["BBIR"]),
    "shotgun_receiver": dict(name="Shotgun Receiver", icon="receiver", pattern=["IIIR", "W   "]),
    "trigger_group": dict(name="Trigger Group", icon="trigger", pattern=["NR", "N "]),
    "bolt_carrier": dict(name="Bolt Carrier", icon="bolt", pattern=["INN"]),
    "gas_system": dict(name="Gas System", icon="gas", pattern=["NIN", "N N"]),
    "spring": dict(name="Spring", icon="spring", pattern=["N  ", " N ", "  N"], count=2),
    "wood_stock": dict(name="Wooden Stock", icon="wood_stock", pattern=["WWWW", " WWW"]),
    "polymer_stock": dict(name="Polymer Stock", icon="polymer_stock", pattern=["XXXX", " XXX"], uses={"X": "polymer"}),
    "folding_stock": dict(name="Folding Stock", icon="folding_stock", pattern=["NNNN", "N  N"]),
    "wood_grip": dict(name="Wooden Grip", icon="wood_grip", pattern=["WW", " W"]),
    "polymer_grip": dict(name="Polymer Grip", icon="polymer_grip", pattern=["XX", " X"], uses={"X": "polymer"}),
    "lens": dict(name="Lens", icon="lens", pattern=["PQ"]),
    "circuit": dict(name="Circuit Board", icon="circuit", pattern=["RGR", "QCQ"]),
}

# Assembly layout per archetype; letters are filled from each gun's "parts" (S stock, R receiver, A gas system,
# B barrel, C bolt carrier, G grip, T trigger group, F spring, L lens).
ASSEMBLY = {
    "pistol": ["RB", "GT"], "revolver": ["RB", "GT"], "smg": ["SRB", " GT"], "bullpup_smg": ["RRB", "GT "],
    "rifle": ["SRAB", " GT "], "dmr": ["SRAB", " GT "], "dmr_scoped": [" L  ", "SRAB", " GT "],
    "sniper": ["SRCB", " GT "], "shotgun": ["SRB", " GT"], "shotgun_auto": ["SRAB", " GT "],
    "lmg": ["SRAB", "FGT "], "launcher": ["SB", "GT"], "rpg": ["BB", "GT"],
}
GUN_PARTS = {
    "glock17": ("pistol", dict(R="pistol_frame", B="short_barrel", G="polymer_grip")),
    "m1911": ("pistol", dict(R="pistol_frame", B="short_barrel", G="wood_grip")),
    "deagle": ("pistol", dict(R="pistol_frame", B="barrel", G="polymer_grip")),
    "revolver": ("revolver", dict(R="revolver_frame", B="barrel", G="wood_grip")),
    "mp5": ("smg", dict(S="polymer_stock", R="smg_receiver", B="short_barrel", G="polymer_grip")),
    "uzi": ("smg", dict(S="folding_stock", R="smg_receiver", B="short_barrel", G="polymer_grip")),
    "p90": ("bullpup_smg", dict(R="smg_receiver", B="short_barrel", G="polymer_grip")),
    "thompson": ("smg", dict(S="wood_stock", R="smg_receiver", B="barrel", G="wood_grip")),
    "m4a1": ("rifle", dict(S="polymer_stock", R="rifle_receiver", B="barrel", G="polymer_grip")),
    "ak47": ("rifle", dict(S="wood_stock", R="rifle_receiver", B="barrel", G="wood_grip")),
    "m16a4": ("rifle", dict(S="polymer_stock", R="rifle_receiver", B="long_barrel", G="polymer_grip")),
    "scar_h": ("rifle", dict(S="polymer_stock", R="battle_receiver", B="barrel", G="polymer_grip")),
    "svd": ("dmr_scoped", dict(S="wood_stock", R="battle_receiver", B="long_barrel", G="wood_grip")),
    "m14": ("dmr", dict(S="polymer_stock", R="battle_receiver", B="long_barrel", G="polymer_grip")),
    "m24": ("sniper", dict(S="wood_stock", R="sniper_receiver", B="long_barrel", G="wood_grip")),
    "awm": ("sniper", dict(S="polymer_stock", R="sniper_receiver", B="heavy_barrel", G="polymer_grip")),
    "barrett": ("sniper", dict(S="polymer_stock", R="heavy_receiver", B="heavy_barrel", G="polymer_grip")),
    "m870": ("shotgun", dict(S="wood_stock", R="shotgun_receiver", B="shotgun_barrel", G="wood_grip")),
    "spas12": ("shotgun", dict(S="polymer_stock", R="shotgun_receiver", B="shotgun_barrel", G="polymer_grip")),
    "aa12": ("shotgun_auto", dict(S="polymer_stock", R="shotgun_receiver", B="shotgun_barrel", G="polymer_grip")),
    "m249": ("lmg", dict(S="polymer_stock", R="rifle_receiver", B="heavy_barrel", G="polymer_grip")),
    "pkm": ("lmg", dict(S="wood_stock", R="battle_receiver", B="heavy_barrel", G="wood_grip")),
    "m79": ("launcher", dict(S="wood_stock", B="launcher_tube", G="wood_grip")),
    "rpg7": ("rpg", dict(B="launcher_tube", G="wood_grip")),
}
COMMON_PARTS = dict(T="trigger_group", A="gas_system", C="bolt_carrier", F="spring", L="lens")

# Magazines: body by kind (I iron, N nugget, F spring part); capacity adds iron rows.
MAGAZINE_SHAPES = {"pistol": ["I", "F"], "stick": ["I", "I", "F"], "curved": ["I ", "IN", "F "], "drum": ["NIN", "IFI", "NIN"],
                   "box": ["III", "IFI", "III"], "loader": ["NNN", "NFN"], "shells": ["HHH", "NFN"], "rocket": ["CF"]}
ATTACHMENT_RECIPES = {
    "red_dot": ["ILI", " X "], "holographic": ["ILLI", " XX "], "acog": ["ILLI", "I  I"], "sniper_scope": ["ILLLI", "I   I"],
    "nv_scope": ["ILLI", "OXXO"], "thermal_scope": ["ILLI", "ZXXZ"], "suppressor": ["IKKKK"], "compensator": ["NIN"],
    "muzzle_brake": ["INI", "N N"], "vertical_grip": ["XX", " X", " X"], "angled_grip": ["XXX", "  X"],
    "bipod": ["NXN", "N N"], "tripod": ["IXI", "N N", "N N"], "laser_sight": ["XLR"], "green_laser": ["XLV"], "flash_hider": ["NXN"],
}
GRENADE_RECIPES = {"frag": ["NIN", "IUI", "NIN"], "smoke": ["NHN", "IUI", "NIN"], "flashbang": ["NYN", "IUI", "NIN"],
                   "concussion": ["NUN", "IUI", "NUN"], "impact_grenade": ["NRN", "IUI", "NIN"], "satchel_charge": ["MEM", "UTU", "MMM"],
                   "thermite": ["NZN", "IZI", "NIN"],
                   "molotov": [" E ", " Z ", "PUP"]}

# ------------------------------------------------------------------------------------------- clothing
CLOTHING = {
    "army_helmet": dict(name="Army Helmet", slot="head", set="army", armor=2, toughness=0.5, recipe=["IVI", "I I"]),
    "army_jacket": dict(name="Army Jacket", slot="chest", set="army", armor=5, plate_slots=1, recipe=["MVM", "MMM", "MMM"]),
    "army_pants": dict(name="Army Pants", slot="legs", set="army", armor=4, recipe=["MVM", "M M", "M M"]),
    "army_boots": dict(name="Army Boots", slot="feet", set="army", armor=2, recipe=["JVJ", "J J"]),
    "spec_ops_helmet": dict(name="Spec Ops Helmet (NVG)", slot="head", set="spec_ops", armor=3, toughness=1, night_vision=True,
                            recipe=["IKI", "LXL"]),
    "spec_ops_vest": dict(name="Spec Ops Plate Carrier", slot="chest", set="spec_ops", armor=7, toughness=2, speed_modifier=-0.05, plate_slots=2,
                          recipe=["IKI", "III", "IKI"]),
    "spec_ops_pants": dict(name="Spec Ops Pants", slot="legs", set="spec_ops", armor=5, toughness=1, recipe=["MKM", "M M", "M M"]),
    "spec_ops_boots": dict(name="Spec Ops Boots", slot="feet", set="spec_ops", armor=2, toughness=1, recipe=["JKJ", "J J"]),
}

# Selector positions per gun (the first non-safe entry is not necessarily the default; "mode" is).
FIRE_MODES = {
    "glock17": ["safe", "semi"], "m1911": ["safe", "semi"], "deagle": ["safe", "semi"], "revolver": ["semi"],
    "mp5": ["safe", "semi", "burst", "auto"], "uzi": ["safe", "semi", "auto"], "p90": ["safe", "semi", "auto"],
    "thompson": ["safe", "semi", "auto"], "m4a1": ["safe", "semi", "auto"], "ak47": ["safe", "auto", "semi"],
    "m16a4": ["safe", "semi", "burst"], "scar_h": ["safe", "semi", "auto"], "svd": ["safe", "semi"],
    "m14": ["safe", "semi", "auto"], "m24": ["safe", "semi"], "awm": ["safe", "semi"], "barrett": ["safe", "semi"],
    "m870": ["safe", "semi"], "spas12": ["safe", "semi"], "aa12": ["safe", "semi", "auto"], "m249": ["safe", "auto"],
    "pkm": ["safe", "auto"], "m79": ["semi"], "rpg7": ["safe", "semi"],
}

# ------------------------------------------------------------------------------------------- attachments
# Sight attachments: ads_height = height of the optic's line of sight (dot / lens centre) above the rail, in model pixels.
ATTACHMENTS = {
    "red_dot": dict(name="Red Dot Sight", slot="sight", stats={"ads_zoom": 1.5, "spread_multiplier": 0.9, "ads_height": 1.5}),
    "holographic": dict(name="Holographic Sight", slot="sight", stats={"ads_zoom": 1.4, "spread_multiplier": 0.85, "ads_height": 1.5}),
    "acog": dict(name="ACOG 4x Scope", slot="sight", stats={"ads_zoom": 4.0, "spread_multiplier": 0.8, "ads_move_speed_multiplier": 0.85,
                 "ads_height": 1.7, "scope": {"overlay": f"{NS}:textures/scope/acog.png"}}),
    "sniper_scope": dict(name="8x Sniper Scope", slot="sight", stats={"ads_zoom": 8.0, "spread_multiplier": 0.7, "ads_move_speed_multiplier": 0.7,
                         "ads_height": 1.5, "scope": {"overlay": f"{NS}:textures/scope/sniper.png"}}),
    "nv_scope": dict(name="Night Vision Scope", slot="sight", stats={"ads_zoom": 4.0, "ads_move_speed_multiplier": 0.85, "ads_height": 1.7,
                     "scope": {"overlay": f"{NS}:textures/scope/night_vision.png", "night_vision": True}}),
    "thermal_scope": dict(name="Thermal Scope", slot="sight", stats={"ads_zoom": 3.0, "ads_move_speed_multiplier": 0.85, "ads_height": 1.7,
                          "scope": {"overlay": f"{NS}:textures/scope/thermal.png", "thermal": True, "thermal_range": 96}}),
    "suppressor": dict(name="Suppressor", slot="muzzle", stats={"damage_multiplier": 0.9, "hide_tracer": True, "shoot_sound": f"{NS}:gun.suppressed"}),
    "compensator": dict(name="Compensator", slot="muzzle", stats={"recoil_multiplier": 0.7}),
    "muzzle_brake": dict(name="Muzzle Brake", slot="muzzle", stats={"recoil_multiplier": 0.6, "spread_multiplier": 1.1}),
    "vertical_grip": dict(name="Vertical Grip", slot="underbarrel", stats={"recoil_multiplier": 0.75, "spread_multiplier": 0.85}),
    "angled_grip": dict(name="Angled Grip", slot="underbarrel", stats={"recoil_multiplier": 0.85, "ads_move_speed_multiplier": 1.15}),
    # Set-down supports (Stance.deployed: prone or crouching): steadier, but a bit heavier to carry and aim.
    "bipod": dict(name="Bipod", slot="underbarrel", stats={"ads_move_speed_multiplier": 0.9,
                  "deploy": {"spread_multiplier": 0.5, "recoil_multiplier": 0.4}}),
    "tripod": dict(name="Tripod", slot="underbarrel", stats={"spread_multiplier": 1.25, "ads_move_speed_multiplier": 0.6,
                   "deploy": {"spread_multiplier": 0.3, "recoil_multiplier": 0.2},
                   "guns": [f"{NS}:m249", f"{NS}:pkm", f"{NS}:barrett", "flansww2:mg42", "flansww2:bren", "flansww2:bar", "flansww2:dp28"]}),
    "laser_sight": dict(name="Laser Sight", slot="underbarrel", stats={"laser": {"color": "#FF2020", "hip_spread_multiplier": 0.6}}),
    "green_laser": dict(name="Green Laser Sight", slot="underbarrel", stats={"laser": {"color": "#30FF40", "hip_spread_multiplier": 0.55, "range": 96}}),
    "flash_hider": dict(name="Flash Hider", slot="muzzle", stats={"hide_flash": True, "spread_multiplier": 0.95}),
}

# ------------------------------------------------------------------------------------------- gear
# Clothing accessories (GearDefinition): backpacks/pouches (slots), medical supplies (vanilla status effects, used over
# consume_seconds or applied to someone else), binoculars (zoom + overlay).
def effect(name, seconds=0, amplifier=0):
    return {"effect": f"minecraft:{name}", "duration": max(1, int(seconds * 20)), "amplifier": amplifier}


GEAR = {
    "assault_pack": dict(name="Assault Pack", type="backpack", slots=9, recipe=["JEJ", "J J", "JJJ"]),
    "rucksack": dict(name="Rucksack", type="backpack", slots=18, recipe=["JEJ", "JMJ", "JJJ"]),
    "field_pack": dict(name="Large Field Pack", type="backpack", slots=27, recipe=["MEM", "JMJ", "JJJ"]),
    "ammo_pouch": dict(name="Ammo Pouch", type="pouch", slots=9, recipe=["J J", "JJJ"]),
    "bandage": dict(name="Bandage", type="medical", consume_seconds=1.5, max_stack=16, count=4, recipe=["MEM"],
                    effects=[effect("instant_health", 0.05)]),
    "first_aid_kit": dict(name="First Aid Kit", type="medical", consume_seconds=3.0, max_stack=4, recipe=["HRH", "MEM"],
                          effects=[effect("instant_health", 0.05, 1), effect("regeneration", 10)]),
    "morphine": dict(name="Morphine Syrette", type="medical", consume_seconds=1.0, max_stack=8, count=2, recipe=["P", "Z", "N"],
                     effects=[effect("absorption", 30, 1), effect("speed", 15), effect("resistance", 10)]),
    "binoculars": dict(name="Binoculars", type="binoculars", zoom=6.0, overlay=f"{NS}:textures/scope/binoculars.png", recipe=["PNP", "I I"]),
    # Armour plates for the plate slots of modern armour (plate carrier, army jacket). Durability = hits taken.
    "soft_armor_insert": dict(name="Soft Armour Insert (IIIA)", type="plate", armor=2.0, durability=60, recipe=["MMM", "MEM"]),
    "steel_plate": dict(name="Steel Plate (III)", type="plate", armor=4.0, toughness=1.0, durability=40, speed_modifier=-0.01, recipe=["III", "IBI"]),
    "ceramic_plate": dict(name="Ceramic Plate (IV)", type="plate", armor=5.0, toughness=2.5, durability=20, speed_modifier=-0.005, recipe=["QQQ", "QIQ"]),
    # Field utilities (the mod's utility package): terrain map with waypoints, flashlight, compass.
    "field_map": dict(name="Field Map", type="map", range=160, recipe=["HHH", "HKV", "HHH"]),
    "flashlight": dict(name="Flashlight", type="flashlight", range=24, light_level=15, recipe=["IYP", "IRI"]),
    "lensatic_compass": dict(name="Lensatic Compass", type="compass", recipe=[" N ", "NRN", " N "]),
}


def gear_model(gid):
    """Item models (also drawn on the wearer's back for backpacks: the front faces -Z, against the back)."""
    m = {"assault_pack": [el((4, 1, 6), (12, 12, 10), "olive"), el((4.5, 2, 10), (11.5, 8, 11.5), "olive"), el((4.5, 8, 10), (11.5, 9, 11.7), "black"),
                          el((5, 2, 5), (6, 13, 6), "black"), el((10, 2, 5), (11, 13, 6), "black")],
         "rucksack": [el((3, 0, 5.5), (13, 13, 11), "olive"), el((3, 13, 5.5), (13, 14.5, 11.5), "green"), el((1.5, 1, 6.5), (3, 8, 10), "olive"),
                      el((13, 1, 6.5), (14.5, 8, 10), "olive"), el((4.5, 1, 11), (11.5, 7, 12.5), "olive"), el((5, 2, 4.5), (6, 14, 5.5), "black"),
                      el((10, 2, 4.5), (11, 14, 5.5), "black")],
         "field_pack": [el((2.5, 0, 5), (13.5, 15, 11.5), "olive"), el((1.5, 15, 6), (14.5, 18, 10), "green"), el((1, 1, 6), (2.5, 9, 10.5), "olive"),
                        el((13.5, 1, 6), (15, 9, 10.5), "olive"), el((4, 1, 11.5), (12, 8, 13), "olive"), el((4.5, 2, 4), (5.5, 15, 5), "black"),
                        el((10.5, 2, 4), (11.5, 15, 5), "black"), el((2, 4, 4), (14, 5, 5), "black")],
         "ammo_pouch": [el((5, 4, 6), (11, 11, 10), "olive"), el((4.8, 9.5, 5.8), (11.2, 11.5, 10.2), "green"), el((7.5, 8, 10), (8.5, 10, 10.5), "brass")],
         "bandage": [el((5, 4, 6), (11, 11, 10), "white"), el((6, 4, 5), (10, 11, 11), "white"), el((7, 4, 4.8), (9, 11, 5), "gray")],
         "first_aid_kit": [el((3, 3, 5), (13, 11, 11), "olive"), el((5.5, 4.5, 4.9), (10.5, 9.5, 5), "white"), el((7.25, 5, 4.8), (8.75, 9, 4.9), "red"),
                           el((6, 6.25, 4.8), (10, 7.75, 4.9), "red"), el((6, 11, 7.5), (10, 12, 8.5), "black")],
         "morphine": [el((7, 3, 7), (9, 11, 9), "glass"), el((6.5, 2, 6.5), (9.5, 3, 9.5), "red"), el((7.6, 11, 7.6), (8.4, 15, 8.4), "steel")],
         "soft_armor_insert": [el((3, 1, 7), (13, 15, 9), "olive"), el((4, 2, 6.8), (12, 14, 7), "black")],
         "steel_plate": [el((3, 1, 7), (13, 13, 8.5), "gray"), el((4.5, 13, 7), (11.5, 15, 8.5), "gray"), el((4, 2, 6.8), (12, 12, 7), "steel")],
         "ceramic_plate": [el((3, 1, 7), (13, 13, 9), "tan"), el((4.5, 13, 7), (11.5, 15, 9), "tan"), el((4, 2, 6.8), (12, 12, 7), "black")],
         "field_map": [el((1, 7, 2), (15, 7.6, 14), "tan"), el((2, 7.6, 3), (8, 7.7, 9), "green"), el((8, 7.6, 7), (14, 7.7, 13), "olive"),
                       el((5, 7.6, 10), (11, 7.75, 10.6), "red"), el((7.5, 7.6, 3), (8.1, 7.75, 13), "white")],
         "flashlight": [el((7, 6, 1), (9, 8, 11), "black"), el((6.5, 5.5, 11), (9.5, 8.5, 14), "black"), el((7, 6, 13.8), (9, 8, 14.1), "glass"),
                        el((7.5, 8, 5), (8.5, 8.4, 6.5), "red")],
         "lensatic_compass": [el((4, 6, 4), (12, 8, 12), "olive"), el((5, 8, 5), (11, 8.1, 11), "white"), el((7.75, 8.1, 5.5), (8.25, 8.2, 10.5), "red"),
                              el((7, 8, 11.5), (9, 11, 12), "olive"), el((7.5, 6.5, 3.5), (8.5, 7.5, 4), "brass")],
         "binoculars": [el((3, 5, 4), (7, 10, 12), "black"), el((9, 5, 4), (13, 10, 12), "black"), el((7, 7, 6), (9, 9, 10), "metal"),
                        el((3.5, 5.5, 3.8), (6.5, 9.5, 4), "lens"), el((9.5, 5.5, 3.8), (12.5, 9.5, 4), "lens")]}
    return m[gid]


GEAR_DISPLAY = {"gui": {"rotation": [25, 210, 0], "scale": [0.9, 0.9, 0.9]}, "fixed": {"scale": [1, 1, 1]},
                "ground": {"translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]}}


def write_gear(table, models, factions=None, extra_key=None):
    """Gear definitions, item models and Weapons Bench recipes (shared with the WW2 generator)."""
    for gid, g in table.items():
        definition = {k: v for k, v in g.items() if k not in ("recipe", "count")}
        definition["icon"] = f"{NS}:{gid}"
        if factions and gid in factions:
            definition["faction"] = factions[gid]
        write(DATA / "flansmod" / "gear" / f"{gid}.json", definition)
        model3d(gid, models(gid), GEAR_DISPLAY)
        components = {"flansmod:gear": f"{NS}:{gid}"}
        if g.get("max_stack", 1) != 1:
            components["minecraft:max_stack_size"] = g["max_stack"]
        bench(f"gear_{gid}", g["recipe"], raw_key(g["recipe"], extra_key), {"id": "flansmod:gear", "count": g.get("count", 1), "components": components},
              extend=True)


def binocular_overlay(path: Path):
    """Two overlapping round fields of view, a range scale and a centre mark."""
    img = Image.new("RGBA", (256, 256), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    for cx in (92, 164):
        d.ellipse((cx - 84, 128 - 84, cx + 84, 128 + 84), fill=(0, 0, 0, 0))
    for i in range(-4, 5):
        d.line((128 + i * 12, 150, 128 + i * 12, 146 if i % 2 else 142), fill=(20, 20, 20, 220))
    d.line((80, 150, 176, 150), fill=(20, 20, 20, 220))
    d.line((124, 128, 132, 128), fill=(20, 20, 20, 220)); d.line((128, 124, 128, 132), fill=(20, 20, 20, 220))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


# Damage to the surroundings by grenade type (see GrenadeDefinition.BlockDamage): max blast resistance broken.
SHATTER = {"radius": 1.8, "max_resistance": 0.3, "chance": 0.8}          # glass, leaves, plants
FRAGMENTATION = {"radius": 2.0, "max_resistance": 0.5, "chance": 0.6}    # + soil, sand, snow
BLAST = {"radius": 2.5, "max_resistance": 1.5, "chance": 0.6}            # + wool, clay, netherrack, concrete powder
DEMOLITION = {"radius": 3.0, "max_resistance": 6.0, "chance": 0.75}      # + wood, stone, bricks (not sandbags/bunkers)
INCENDIARY = {"radius": 1.5, "max_resistance": 3.0, "chance": 0.4}       # burns through planks and wool


def blast(power, damage, fire=False):
    return {"power": power, "fire": fire, "break_blocks": False, "block_damage": damage}


GRENADES = {
    "frag": dict(name="Frag Grenade", fuse_ticks=60, bounciness=0.35, explosion=blast(2.5, FRAGMENTATION)),
    "smoke": dict(name="Smoke Grenade", fuse_ticks=40, bounciness=0.3, smoke={"radius": 5.0, "duration_ticks": 300},
                  detonate_sound="minecraft:block.fire.extinguish"),
    "flashbang": dict(name="Flashbang", fuse_ticks=35, bounciness=0.4, flash={"radius": 12.0, "duration_ticks": 100},
                      explosion=blast(0.6, SHATTER)),
    "molotov": dict(name="Incendiary Grenade", fuse_ticks=40, bounciness=0.2, contact=True, explosion=blast(1.2, INCENDIARY, fire=True)),
    "concussion": dict(name="Concussion Grenade", fuse_ticks=50, bounciness=0.3, explosion=blast(3.2, BLAST)),
    "impact_grenade": dict(name="Impact Grenade", fuse_ticks=100, contact=True, explosion=blast(2.3, FRAGMENTATION)),
    "satchel_charge": dict(name="Satchel Charge", fuse_ticks=100, bounciness=0.1, throw_velocity=0.6, max_stack=4, cooldown_ticks=40,
                           explosion=blast(4.0, DEMOLITION)),
    "thermite": dict(name="Thermite Grenade", fuse_ticks=45, bounciness=0.2, explosion=blast(0.8, INCENDIARY, fire=True)),
    # Launcher projectiles (not throwable)
    "40mm_he": dict(name="40mm HE", throwable=False, contact=True, gravity=0.03, fuse_ticks=200,
                    explosion={"power": 2.2, "fire": False, "break_blocks": True}),
    "rocket": dict(name="Rocket", throwable=False, contact=True, gravity=0.004, fuse_ticks=120, trail=True,
                   explosion={"power": 3.5, "fire": False, "break_blocks": True}),
}


# ------------------------------------------------------------------------------------------- models
def attachment_cubes(name, geo):
    top, sz = geo["sight"]
    my, mz = geo["muzzle"]
    # Reflex sights are open frames (opaque textures cannot show glass) with the reticle floating in the window,
    # centred ads_height = 1.5 px above the rail so aiming looks straight through it.
    if name == "red_dot":
        return [cube((-1, top, sz - 1.5), (2, 0.5, 3)), cube((-1, top + 0.5, sz - 1), (0.4, 2, 0.4)),
                cube((0.6, top + 0.5, sz - 1), (0.4, 2, 0.4)), cube((-1, top + 2.5, sz - 1), (2, 0.4, 0.4)),
                cube((-0.15, top + 1.35, sz - 0.9), (0.3, 0.3, 0.2), "red")]
    if name == "holographic":
        ring = [((-0.1, 1.4), (0.2, 0.2)), ((-0.35, 1.95), (0.7, 0.1)), ((-0.35, 0.95), (0.7, 0.1)),
                ((-0.45, 1.05), (0.1, 0.9)), ((0.35, 1.05), (0.1, 0.9))]
        return [cube((-1.1, top, sz - 2), (2.2, 0.6, 4)), cube((-1.1, top + 0.6, sz - 1.8), (0.4, 2.2, 3.6)),
                cube((0.7, top + 0.6, sz - 1.8), (0.4, 2.2, 3.6)), cube((-1.1, top + 2.8, sz - 1.8), (2.2, 0.3, 3.6))] + \
            [cube((x, top + y, sz - 1.0), (w, h, 0.1), "red") for (x, y), (w, h) in ring]
    if name in ("acog", "nv_scope", "thermal_scope"):
        mat = {"acog": "tan", "nv_scope": "olive", "thermal_scope": "polymer"}[name]
        return [cube((-0.5, top, sz - 2), (1, 0.6, 1)), cube((-0.5, top, sz + 1), (1, 0.6, 1)),
                cube((-1.1, top + 0.6, sz - 3), (2.2, 2.2, 6), mat), cube((-0.8, top + 0.9, sz - 3.1), (1.6, 1.6, 0.2), "lens")]
    if name == "sniper_scope":
        return [cube((-0.5, top, sz - 3), (1, 0.6, 1)), cube((-0.5, top, sz + 1), (1, 0.6, 1)),
                cube((-0.9, top + 0.6, sz - 6), (1.8, 1.8, 11), "polymer"), cube((-1.2, top + 0.3, sz - 7), (2.4, 2.4, 2), "polymer")]
    if name == "suppressor":
        return [cube((-0.9, my - 0.9, mz - 7), (1.8, 1.8, 7), "polymer")]
    if name == "compensator":
        return [cube((-0.7, my - 0.7, mz - 2), (1.4, 1.4, 2))]
    if name == "muzzle_brake":
        return [cube((-1, my - 0.6, mz - 2.5), (2, 1.2, 2.5), "steel")]
    if name in ("bipod", "tripod", "laser_sight", "green_laser"):
        # Guns without a rail mount take these under the barrel, a third of the way back from the muzzle.
        uy, uz = geo["under"] or (my - 1.0, mz + 7)
        if name == "bipod":  # clamp and two splayed legs
            return [cube((-0.6, uy - 0.8, uz - 1), (1.2, 0.8, 1.5), "steel"), cube((-1.6, uy - 6.5, uz - 0.8), (0.5, 6, 0.5), "steel"),
                    cube((1.1, uy - 6.5, uz - 0.8), (0.5, 6, 0.5), "steel"), cube((-1.8, uy - 6.8, uz - 1), (0.9, 0.3, 0.9), "polymer"),
                    cube((0.9, uy - 6.8, uz - 1), (0.9, 0.3, 0.9), "polymer")]
        if name == "tripod":  # cradle and three long legs
            return [cube((-0.8, uy - 1.2, uz - 2), (1.6, 1.2, 3), "steel"), cube((-0.3, uy - 3, uz - 0.3), (0.6, 1.8, 0.6), "steel"),
                    cube((-3.2, uy - 10, uz - 2.5), (0.5, 7.5, 0.5), "steel"), cube((2.7, uy - 10, uz - 2.5), (0.5, 7.5, 0.5), "steel"),
                    cube((-0.25, uy - 10, uz + 4), (0.5, 7.5, 0.5), "steel")]
        lens = "red" if name == "laser_sight" else "lens"
        return [cube((-0.7, uy - 1.8, uz - 2.5), (1.4, 1.8, 3), "polymer"), cube((-0.35, uy - 1.4, uz - 2.6), (0.7, 0.7, 0.1), lens)]
    if name == "flash_hider":
        return [cube((-0.6, my - 0.6, mz - 2.5), (1.2, 1.2, 2.5), "polymer"), cube((-0.75, my - 0.2, mz - 2.3), (1.5, 0.4, 1.6), "polymer")]
    if geo["under"] and name in ("vertical_grip", "angled_grip"):
        uy, uz = geo["under"]
        if name == "vertical_grip":
            return [cube((-0.5, uy - 3, uz), (1, 3, 1.2), "polymer")]
        return [cube((-0.5, uy - 1.2, uz - 1), (1, 1.2, 3), "polymer")]
    return None


# Gun models by id (the WW2 generator swaps in its own table).
GUN_BUILDERS = gs.GUNS


def gun_geometry(gid, g):
    """The gun's model (gunsmith) with its magazines: the first fitting magazine is the default `magazine` bone, the
    others become `magazine_<id>` bones in it (shown instead while that magazine is inserted)."""
    geo = GUN_BUILDERS[gid](g)
    mags = [mid for mid, m in MAGAZINES.items() if gid in m["guns"] and not m.get("internal")]
    placed = {mid: gs.placed(magazine_cubes(mid), geo["well"], geo["rake"], geo.get("upward", False)) for mid in mags}
    if geo["round"]:
        geo["mag"] = geo["round_cubes"]  # launchers: the visible round
    elif "cylinder" in geo:
        geo["mag"] = geo["cylinder"]  # revolver: the cylinder swings out when reloading
    else:
        geo["mag"] = placed[mags[0]] if mags else []
    geo["mags"] = {mid: cubes for mid, cubes in placed.items() if mags and mid != mags[0] and "cylinder" not in geo}
    wy, wz = geo["well"]
    geo["mag_at"] = (0, wy - 2.5, wz)
    return geo


def magazine_cubes(mid):
    """A magazine in its own frame (feed lips at the origin), shared by guns and the magazine item."""
    kind, rounds, mat = MAG_SHAPES[mid]
    return gs.magazine(kind, rounds, mat)


# First-person arms (shown only in first person, drawn with the player's skin by the mod's SkinArmLayer): an
# arm-shaped cube using the vanilla skin UV layout, hand end on the anchor bone. ARM_TILT turns the arm from "up"
# to "towards the camera"; ARM_ANGLES (bone rotation) then swings it down and out to its side.
ARM_ANGLES = {"right": [-25, -35, 0], "left": [-18, 52, 0]}
ARM_TILT = -90
SKIN_UV = {"right": ((40, 16), (40, 32)), "left": ((32, 48), (48, 48))}  # (arm, sleeve layer) box UV origins


def arm_bone(name, parent, hand, side):
    hx, hy, hz = hand
    pivot = [round(v, 3) for v in hand]
    arm_uv, sleeve_uv = SKIN_UV[side]

    def arm_cube(uv, inflate=0.0):
        c = {"origin": [round(hx - 2, 3), round(hy - 2, 3), round(hz - 2, 3)], "size": [4, 12, 4], "uv": list(uv),
             "pivot": pivot, "rotation": [ARM_TILT, 0, 0]}
        if inflate:
            c["inflate"] = inflate
        return c

    return {"name": name, "parent": parent, "pivot": pivot, "rotation": ARM_ANGLES[side],
            "cubes": [arm_cube(arm_uv), arm_cube(sleeve_uv, 0.25)]}


def gun_model(gid, g, geo):
    def anchor(name, pos):
        return {"name": name, "parent": "gun", "pivot": [round(v, 3) for v in pos], "cubes": []}

    my, mz = geo["muzzle"]
    flash = [bx(-1.6, my - 0.4, mz - 4.5, 1.6, my + 0.4, mz - 0.2, "flash"), bx(-0.4, my - 1.6, mz - 4.5, 0.4, my + 1.6, mz - 0.2, "flash"),
             bx(-0.9, my - 0.9, mz - 2.5, 0.9, my + 0.9, mz - 0.1, "flash")]
    mag_bone = "round" if geo["round"] else "magazine"
    bones = [bone("gun", [], parent=None), bone("body", geo["parts"]), bone(geo["moving"][0], geo["moving"][1]),
             bone(mag_bone, geo["mag"]), bone("default_sight", geo["iron"]), bone("muzzle_flash", flash),
             anchor("right_hand", geo["rhand"]), anchor("left_hand", geo["lhand"]),
             arm_bone("arm_right", "right_hand", geo["rhand"], "right"),
             arm_bone("arm_left", "left_hand", geo["lhand"], "left")]
    for mid, cubes in geo["mags"].items():
        bones.append(bone(f"magazine_{mid}", cubes, parent="magazine"))
    for aid, a in ATTACHMENTS.items():
        if a["slot"] in g["slots"]:
            cubes = attachment_cubes(aid, geo)
            if cubes:
                bones.append(bone(f"attachment_{aid}", cubes))
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{gid}", "texture_width": gs.TEXTURE_SIZE[0], "texture_height": gs.TEXTURE_SIZE[1]}, "bones": bones}]}


def gun_animations(g, geo):
    moving = geo["moving"][0]
    kick = {"bolt": [0, 0, 1.5], "slide": [0, 0, 1.6], "pump": [0, 0, 2.5], "hammer": [0, 0, 0.5],
            "breech": [0, 0, 1], "trigger": [0, 0, 0.3]}[moving]
    r = lambda v: str(round(v, 3))  # noqa: E731
    shoot_len = max(0.12, min(60 / g["rpm"], 1.0))
    shoot = {"gun": {"position": {"0.0": [0, 0, 0], "0.03": [0, 0.1, 0.6], r(shoot_len): [0, 0, 0]},
                     "rotation": {"0.0": [0, 0, 0], "0.03": [-3, 0, 0], r(shoot_len): [0, 0, 0]}},
             moving: {"position": {"0.0": [0, 0, 0], "0.04": kick, r(shoot_len): [0, 0, 0]}}}
    if g["arch"] in ("sniper",) and g["rpm"] <= 60:  # bolt action: cycle the bolt after the shot
        shoot[moving] = {"position": {"0.0": [0, 0, 0], r(shoot_len * 0.35): [0, 0, 0], r(shoot_len * 0.55): [0, 0, 3],
                                      r(shoot_len * 0.8): [0, 0, 0]}}

    # Reload: the left hand leaves the handguard, pulls the magazine (or round) out and down, comes back with a
    # new one and seats it, then returns. Bone offsets are relative to rest positions.
    t = g["reload"] / 20
    lx, ly, lz = geo["lhand"]
    mx, my, mz = geo["mag_at"]
    to_mag = [mx - lx, my - ly, mz - lz]
    # Kept short so the whole motion stays on screen (the gun is also raised towards the centre).
    out = [-4.0, -6.0, 3.0] if not geo["round"] else [0.0, -3.0, -7.0]
    below = [0.0, -2.5, 0.0] if not geo["round"] else [0.0, 0.0, -4.0]
    add = lambda a, b: [round(a[i] + b[i], 3) for i in range(3)]  # noqa: E731
    mag_bone = "round" if geo["round"] else "magazine"
    reload = {
        "gun": {"rotation": {"0.0": [0, 0, 0], r(t * 0.12): [0, 0, -30], r(t * 0.85): [0, 0, -30], r(t): [0, 0, 0]},
                "position": {"0.0": [0, 0, 0], r(t * 0.12): [-3, 3, -6], r(t * 0.85): [-3, 3, -6], r(t): [0, 0, 0]}},
        "left_hand": {"position": {"0.0": [0, 0, 0], r(t * 0.15): to_mag, r(t * 0.35): add(to_mag, out), r(t * 0.5): add(to_mag, out),
                                   r(t * 0.65): add(to_mag, below), r(t * 0.75): to_mag, r(t * 0.9): [0, 0, 0]}},
        mag_bone: {"position": {"0.0": [0, 0, 0], r(t * 0.15): [0, 0, 0], r(t * 0.35): out, r(t * 0.5): out,
                                r(t * 0.65): below, r(t * 0.75): [0, 0, 0]},
                   "scale": {"0.0": [1, 1, 1], r(t * 0.36): [1, 1, 1], r(t * 0.37): [0, 0, 0], r(t * 0.49): [0, 0, 0], r(t * 0.5): [1, 1, 1]}},
        moving: {"position": {"0.0": [0, 0, 0], r(t * 0.78): [0, 0, 0], r(t * 0.84): [0, 0, kick[2] * 1.5], r(t * 0.9): [0, 0, 0]}},
    }
    if geo["round"]:  # launchers start empty: the new round only appears when the hand brings it
        reload[mag_bone]["scale"] = {"0.0": [0, 0, 0], r(t * 0.49): [0, 0, 0], r(t * 0.5): [1, 1, 1]}
    return {"format_version": "1.8.0", "animations": {
        "shoot": {"animation_length": shoot_len, "bones": shoot},
        "reload": {"animation_length": t, "bones": reload},
    }}


GUN_CATEGORY = {"pistol": "pistol", "revolver": "pistol", "smg": "smg", "bullpup_smg": "smg", "rifle": "rifle", "dmr": "dmr",
                "dmr_scoped": "dmr", "sniper": "sniper", "shotgun": "shotgun", "shotgun_auto": "shotgun", "lmg": "lmg",
                "launcher": "launcher", "rpg": "launcher"}


def gun_definition(gid, g, geo):
    length = -geo["muzzle"][1] + 10
    gui_scale = round(min(0.8, 13 / length), 2)
    # Line of sight: just below the top of the iron sights, or ~1px above the rail for guns without irons.
    irons_top = max((c["origin"][1] + c["size"][1] for c in geo["iron"]), default=None)
    sight_line = irons_top - 0.4 if irons_top is not None and not g.get("scope") else geo["sight"][0] + 1
    # Calibrated in game (M4A1 irons): this pose puts the sight line on the screen centre.
    ads_y = round(6.0 + (10 - sight_line) - g.get("ads_height", 0), 2)
    d = {
        "name": g["name"],
        "model": {"texture": f"{NS}:textures/gun/basic.png"},
        "damage": g["dmg"], "rpm": g["rpm"], "fire_mode": g["mode"], "reload_ticks": g["reload"],
        "velocity": g["vel"], "spread": g["spread"], "ads_spread": g.get("ads_spread", round(g["spread"] / 5, 2)),
        "ads_zoom": g["zoom"], "ads_move_speed": g.get("move", 0.6),
        # Aiming pose height for a sight line exactly on the rail; optics add their own height above it.
        "rail_ads": round(6.0 + (10 - geo["sight"][0]), 2),
        "recoil": {"pitch": g["recoil"][0], "yaw": g["recoil"][1]},
        "magazines": [f"{NS}:{m}" for m, mag in MAGAZINES.items() if gid in mag["guns"]],
        "sounds": {"shoot": f"{NS}:gun.{g['sound']}.shoot", "reload": f"{NS}:gun.reload", "empty": f"{NS}:gun.empty"},
        "fire_modes": FIRE_MODES[gid],
        "attachment_slots": g["slots"],
        "category": GUN_CATEGORY[g["arch"]],
        "display": {
            # Pistols sit further forward and higher than long guns (two-handed pistol grip).
            **({"firstperson_righthand": {"rotation": [5, 12, 0], "translation": [5, 2.5, -12]}} if g["arch"] in ("pistol", "revolver") else {}),
            "gui": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "fixed": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "ads": {"translation": [-1.0, ads_y, -6 if g["arch"] not in ("pistol", "revolver") else -9]},
        },
    }
    for key, field in (("lifetime", "lifetime_ticks"), ("headshot", "headshot_multiplier"), ("scope", "scope")):
        if key in g:
            d[field] = g[key]
    if g["arch"] in ("sniper", "dmr"):
        d["gravity"] = 0.01
        d["lifetime_ticks"] = 60
    if g["arch"] in ("launcher", "rpg"):
        d["tracer"] = None
    return d


# ------------------------------------------------------------------------------------------- textures
def noise_block(img, ox, oy, base, rng, wood=False, w=64, h=64):
    for x in range(w):
        for y in range(h):
            n = rng.randint(-6, 6) + (8 if wood and (y // 2) % 5 == 0 else 0)
            img.putpixel((ox + x, oy + y), tuple(max(0, min(255, c + n)) for c in base) + (255,))


gun_texture = gs.gun_texture


def scope_overlay(path: Path, kind):
    """256x256 overlay: opaque black outside the lens, tinted glass and a reticle inside."""
    size, r = 256, 118
    tint = {"acog": (0, 0, 0, 0), "pso": (0, 0, 0, 0), "sniper": (0, 0, 0, 0),
            "night_vision": (40, 255, 60, 70), "thermal": (255, 120, 20, 45)}[kind]
    reticle = {"acog": (230, 40, 30, 255), "pso": (20, 20, 20, 255), "sniper": (10, 10, 10, 255),
               "night_vision": (120, 255, 120, 255), "thermal": (255, 255, 255, 255)}[kind]
    img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    d.ellipse((128 - r, 128 - r, 128 + r, 128 + r), fill=tint)
    d.ellipse((128 - r, 128 - r, 128 + r, 128 + r), outline=(0, 0, 0, 255), width=6)
    c = 128
    if kind in ("sniper", "night_vision", "thermal"):
        d.line((c - r, c, c - 8, c), fill=reticle, width=1)
        d.line((c + 8, c, c + r, c), fill=reticle, width=1)
        d.line((c, c - r, c, c - 8), fill=reticle, width=1)
        d.line((c, c + 8, c, c + r), fill=reticle, width=1)
        d.line((c - r, c, c - 60, c), fill=reticle, width=3)
        d.line((c + 60, c, c + r, c), fill=reticle, width=3)
        d.line((c, c + 60, c, c + r), fill=reticle, width=3)
        for i in range(1, 5):  # mil dots
            for sx, sy in ((i * 12, 0), (-i * 12, 0), (0, i * 12), (0, -i * 12)):
                d.ellipse((c + sx - 1, c + sy - 1, c + sx + 1, c + sy + 1), fill=reticle)
        d.point((c, c), fill=reticle)
    else:  # acog / pso: chevron + range lines
        d.polygon([(c, c - 2), (c - 7, c + 8), (c - 4, c + 8), (c, c + 3), (c + 4, c + 8), (c + 7, c + 8)], fill=reticle)
        d.line((c, c + 10, c, c + 60), fill=reticle, width=1)
        for i in range(1, 5):
            d.line((c - 10 + i, c + 10 + i * 10, c + 10 - i, c + 10 + i * 10), fill=reticle, width=1)
        d.line((c - r, c, c - 40, c), fill=(0, 0, 0, 255), width=2)
        d.line((c + 40, c, c + r, c), fill=(0, 0, 0, 255), width=2)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def icon(path: Path, pixels):
    img = Image.new("RGBA", (16, 16), (0, 0, 0, 0))
    for (x, y), c in pixels.items():
        if 0 <= x < 16 and 0 <= y < 16:
            img.putpixel((x, y), tuple(c) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def shade(c, f):
    return tuple(max(0, min(255, int(v * f))) for v in c)


def rect(px, x0, y0, x1, y1, c):
    for x in range(x0, x1 + 1):
        for y in range(y0, y1 + 1):
            px[(x, y)] = c


# 3D item models: vanilla block-style element models coloured from a 16x16 material atlas (4x4 cells).
ATLAS = {"metal": (0, 0), "polymer": (4, 0), "wood": (8, 0), "red": (12, 0), "olive": (0, 4), "tan": (4, 4), "steel": (8, 4),
         "lens": (12, 4), "brass": (0, 8), "copper": (4, 8), "green": (8, 8), "black": (12, 8), "white": (0, 12),
         "gray": (4, 12), "glass": (8, 12), "orange": (12, 12)}
ATLAS_COLOURS = {"metal": (58, 61, 66), "polymer": (34, 36, 40), "wood": (122, 82, 48), "red": (190, 35, 35), "olive": (82, 92, 58),
                 "tan": (176, 150, 108), "steel": (128, 132, 138), "lens": (70, 130, 160), "brass": (205, 165, 60),
                 "copper": (184, 110, 70), "green": (30, 110, 50), "black": (18, 18, 20), "white": (220, 220, 220),
                 "gray": (120, 120, 125), "glass": (150, 200, 190), "orange": (230, 130, 30)}


# Gun materials without an item-atlas colour of their own.
ATLAS_ALIAS = {"flash": "orange", "walnut": "wood", "parkerized": "gray", "bakelite": "copper", "blued": "metal"}


def material_atlas(path: Path):
    rng = random.Random(3)
    img = Image.new("RGBA", (16, 16))
    for mat, (ox, oy) in ATLAS.items():
        for x in range(4):
            for y in range(4):
                n = rng.randint(-5, 5)
                img.putpixel((ox + x, oy + y), tuple(max(0, min(255, c + n)) for c in ATLAS_COLOURS[mat]) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


def el(frm, to, mat="metal", rotation=None):
    return (tuple(frm), tuple(to), mat, rotation)


# Small items (rounds, casings, tips) are drawn larger in inventory slots than block-sized models.
# Magazines are shown from the side, upright, like the guns' side view.
MAGAZINE_DISPLAY = {"gui": {"rotation": [15, -60, 0], "scale": [0.85, 0.85, 0.85]}, "ground": {"translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
                    "fixed": {"rotation": [0, 90, 0], "scale": [0.8, 0.8, 0.8]}}
SMALL_ITEM_DISPLAY = {"gui": {"rotation": [30, 225, 0], "translation": [0, 0.5, 0], "scale": [0.95, 0.95, 0.95]},
                      "ground": {"translation": [0, 3, 0], "scale": [0.4, 0.4, 0.4]},
                      "fixed": {"scale": [0.8, 0.8, 0.8]}}


def model3d(name, elements, display=None):
    """Writes items/<name>.json + models/item/<name>.json as a 3D element model (parent block/block for display)."""
    write(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    out = []
    for frm, to, mat, *rotation in elements:
        cx, cy = ATLAS[ATLAS_ALIAS.get(mat, mat)]
        face = {"uv": [cx + 1, cy + 1, cx + 3, cy + 3], "texture": "#m"}
        element = {"from": [round(max(-16, min(32, v)), 3) for v in frm], "to": [round(max(-16, min(32, v)), 3) for v in to],
                   "faces": {f: dict(face) for f in ("north", "south", "east", "west", "up", "down")}}
        if rotation and rotation[0]:
            element["rotation"] = rotation[0]
        out.append(element)
    model = {"parent": "minecraft:block/block", "textures": {"m": f"{NS}:item/materials", "particle": f"{NS}:item/materials"}, "elements": out}
    if display:
        model["display"] = display
    write(ASSETS / "models" / "item" / f"{name}.json", model)


def normalize(cubes, target=14.0):
    """Gun-model cubes (pixels, origin at the gun) → elements centred in the 0..16 item space, keeping rotations."""
    lo = [min(c["origin"][i] for c in cubes) for i in range(3)]
    hi = [max(c["origin"][i] + c["size"][i] for c in cubes) for i in range(3)]
    scale = min(1.6, target / max(hi[i] - lo[i] for i in range(3)))
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    result = []
    for c in cubes:
        frm = [8 + (c["origin"][i] - centre[i]) * scale for i in range(3)]
        to = [8 + (c["origin"][i] + c["size"][i] - centre[i]) * scale for i in range(3)]
        rotation = None
        if "rotation" in c:
            pivot = [8 + (c["pivot"][i] - centre[i]) * scale for i in range(3)]
            rx, ry, rz = c["rotation"]
            rotation = {"origin": [round(v, 3) for v in pivot], "x": -rx, "y": -ry, "z": rz}  # GeckoLib negates x and y
        result.append(el(frm, to, c.get("mat", "metal"), rotation))
    return result


def part_model(kind):
    m = {"barrel": [el((1, 7, 7), (15, 9, 9)), el((14, 6.75, 6.75), (15.5, 9.25, 9.25), "polymer")],
         "long_barrel": [el((0, 7.25, 7.25), (16, 8.75, 8.75)), el((0, 7, 7), (1.5, 9, 9), "polymer")],
         "heavy_barrel": [el((1, 6.5, 6.5), (15, 9.5, 9.5)), el((0, 6, 6), (2, 10, 10), "steel")],
         "tube": [el((1, 5, 5), (15, 11, 11), "olive"), el((0, 4.5, 4.5), (1.5, 11.5, 11.5), "olive")],
         "frame": [el((3, 7, 7), (13, 10, 9)), el((9, 3, 7.25), (12, 7, 8.75), "polymer"), el((6, 6, 7.5), (8, 7, 8.5), "black")],
         "receiver": [el((2, 6, 6.5), (14, 10, 9.5)), el((6, 8.5, 9.5), (10, 9.5, 9.75), "black"), el((4, 10, 7.5), (12, 10.5, 8.5), "steel")],
         "cylinder": [el((4, 4, 4), (12, 12, 12), "steel"), el((5, 12, 5), (11, 12.5, 11), "metal")],
         "trigger": [el((4, 8, 6), (12, 10, 10)), el((7.5, 5, 7.5), (8.5, 8, 8.5), "black"), el((5, 4, 7.5), (11, 5, 8.5), "black")],
         "bolt": [el((2, 7, 7), (14, 9, 9), "steel"), el((10, 9, 7.5), (11, 12, 8.5), "steel"), el((9.5, 12, 7), (11.5, 13, 9), "black")],
         "gas": [el((1, 9, 7.5), (15, 10, 8.5), "steel"), el((4, 6.5, 6.5), (7, 10, 9.5))],
         "spring": [el((2 + i * 1.5, 7 + (i % 2), 7), (3.5 + i * 1.5, 8 + (i % 2), 9), "steel") for i in range(8)],
         "wood_stock": [el((1, 6, 6.5), (9, 10, 9.5), "wood"), el((9, 4, 6.5), (15, 10, 9.5), "wood"), el((14.5, 3.5, 6.25), (15.5, 10.5, 9.75), "black")],
         "polymer_stock": [el((1, 7, 6.5), (9, 10, 9.5), "polymer"), el((9, 5, 6.5), (15, 10, 9.5), "polymer"), el((14.5, 4.5, 6.25), (15.5, 10.5, 9.75), "black")],
         "folding_stock": [el((1, 9, 7.5), (15, 10, 8.5)), el((1, 5, 7.5), (15, 6, 8.5)), el((14, 5, 7.5), (15, 10, 8.5))],
         "wood_grip": [el((6, 3, 6.5), (9, 10, 9.5), "wood"), el((5, 10, 6.5), (11, 12, 9.5), "metal")],
         "polymer_grip": [el((6, 3, 6.5), (9, 10, 9.5), "polymer"), el((5, 10, 6.5), (11, 12, 9.5), "metal")],
         "lens": [el((4, 4, 7.5), (12, 12, 8.5), "lens"), el((3.5, 3.5, 7.25), (12.5, 4.5, 8.75), "black"), el((3.5, 11.5, 7.25), (12.5, 12.5, 8.75), "black")],
         "circuit": [el((2, 7.5, 3), (14, 8.5, 13), "green"), el((4, 8.5, 5), (7, 9.5, 8), "black"), el((9, 8.5, 9), (12, 9.5, 11), "brass")],
         "sheet": [el((3, 7.5, 3), (13, 8.5, 13), "polymer"), el((4, 8.5, 4), (12, 8.75, 12), "black")]}
    return m[kind]


# Round sizes per casing class: width, casing height, bullet height (item pixels).
ROUND_SIZE = {"pistol": (4.5, 7.0, 4.5), "rifle": (3.5, 9.0, 5.5), "full": (4.0, 9.5, 5.5), "heavy": (5.0, 9.0, 6.0)}
# Bullet colours by ammo type: the whole bullet is coloured so types are told apart at a glance.
BULLET = {"copper": ("copper", "copper"), "steel": ("steel", "black"), "black": ("black", "black"), "red": ("red", "red"),
          "lens": ("lens", "lens"), "white": ("white", "red")}


def cartridge(x, z, cls, colour):
    """One upright round at (x, z): brass casing with rim, then the bullet (body + narrower nose) in the type colour."""
    w, h, b = ROUND_SIZE[cls]
    body, nose = BULLET.get(colour, (colour, colour))
    i = w * 0.12
    return [el((x - 0.25, 0, z - 0.25), (x + w + 0.25, 1, z + w + 0.25), "brass"), el((x, 1, z), (x + w, h, z + w), "brass"),
            el((x + i, h, z + i), (x + w - i, h + b * 0.6, z + w - i), body),
            el((x + i * 2.5, h + b * 0.6, z + i * 2.5), (x + w - i * 2.5, h + b, z + w - i * 2.5), nose)]


def ammo_model(a):
    """Two big rounds side by side: size shows the caliber class, the bullet colour the ammo type."""
    cls, colour = a["cls"], a["colour"]
    if cls == "shotgun":
        return [e for x in (2.5, 9) for e in (el((x, 1, 5.5), (x + 4.5, 15, 10), colour), el((x - 0.25, 0, 5.25), (x + 4.75, 3.5, 10.25), "brass"))]
    if cls == "40mm":
        return [el((3, 0, 3), (13, 6, 13), "brass"), el((3.5, 6, 3.5), (12.5, 12, 12.5), colour), el((5, 12, 5), (11, 14.5, 11), colour)]
    if cls == "rocket":
        return [el((7, 0, 7), (9, 6, 9), "metal"), el((6, 6, 6), (10, 12, 10), "olive"), el((6.5, 12, 6.5), (9.5, 15, 9.5), "olive"),
                el((7.25, 15, 7.25), (8.75, 16, 8.75), "olive"), el((5.5, 0, 7.75), (10.5, 2, 8.25), "metal")]
    w = ROUND_SIZE[cls][0]
    gap = (16 - 2 * w) / 3
    return [e for x in (gap, 2 * gap + w) for e in cartridge(x, 8 - w / 2, cls, colour)]


def casing_model(cls):
    """One empty casing / hull / rocket motor, sized like the rounds of that class."""
    if cls == "shotgun":
        return [el((5.5, 1, 5.5), (10.5, 13, 10.5), "red"), el((5.25, 1, 5.25), (10.75, 4, 10.75), "brass")]
    if cls == "40mm":
        return [el((3, 1, 3), (13, 8, 13), "brass"), el((2.5, 1, 2.5), (13.5, 2, 13.5), "brass")]
    if cls == "rocket":
        return [el((6, 1, 6), (10, 14, 10), "metal")] + [el(f, t, "metal") for f, t in (((3, 1, 7.5), (13, 4, 8.5)), ((7.5, 1, 3), (8.5, 4, 13)))]
    w, h, _ = ROUND_SIZE[cls]
    w, h = w * 1.5, h * 1.45
    x = 8 - w / 2
    return [el((x, 1, x), (x + w, 1 + h, x + w), "brass"), el((x - 0.3, 1, x - 0.3), (x + w + 0.3, 2.2, x + w + 0.3), "brass"),
            el((x + w * 0.25, 1 + h, x + w * 0.25), (x + w * 0.75, 1 + h + 0.4, x + w * 0.75), "black")]


def tip_model(pid):
    """Bullets (three tips), shotgun loads (pellets, slug, darts) and warheads."""
    tips = {"bullet_fmj": "copper", "bullet_hp": "steel", "bullet_ap": "black", "bullet_tracer": "red", "bullet_incendiary": "lens", "bullet_api": "white"}
    if pid in tips:
        c = tips[pid]
        body = "copper" if c in ("black", "white") else c
        return [e for x in (2, 9) for e in (el((x, 0, 5.5), (x + 5, 7, 10.5), body), el((x + 0.75, 7, 6.25), (x + 4.25, 11, 9.75), c),
                                              el((x + 1.5, 11, 7), (x + 3.5, 13, 9), c))]
    if pid == "load_buckshot":
        return [el((x, 1 + y, z), (x + 2, 3 + y, z + 2), "steel") for x, y, z in ((3, 0, 4), (6, 0, 6), (9, 0, 4), (4.5, 2, 5), (7.5, 2, 5), (6, 4, 5.5), (11, 0, 8))]
    if pid == "load_slug":
        return [el((5, 1, 5), (11, 8, 11), "steel"), el((6, 8, 6), (10, 9.5, 10), "steel")]
    if pid == "load_dragon":
        return [el((x, 1 + y, z), (x + 2, 3 + y, z + 2), "orange") for x, y, z in ((3, 0, 4), (6, 0, 6), (9, 0, 4), (4.5, 2, 5), (7.5, 2, 5), (6, 4, 5.5))]
    if pid == "load_flechette":
        return [el((3 + i * 2.5, 1, 7.5), (3.6 + i * 2.5, 13, 8.1), "steel") for i in range(5)]
    if pid == "warhead_he":
        return [el((4, 1, 4), (12, 7, 12), "olive"), el((5, 7, 5), (11, 10, 11), "olive"), el((6.5, 10, 6.5), (9.5, 11, 9.5), "brass")]
    return [el((5, 1, 5), (11, 9, 11), "olive"), el((6, 9, 6), (10, 13, 10), "olive"), el((7.25, 13, 7.25), (8.75, 15, 8.75), "brass")]


def grenade_model(gid):
    m = {"frag": [el((5, 2, 5), (11, 10, 11), "olive"), el((6.5, 10, 6.5), (9.5, 12, 9.5), "steel"), el((9.5, 7, 7.5), (10.5, 12, 8.5), "steel"),
                  el((5, 5, 5), (11, 6, 11), "black")],
         "smoke": [el((5, 2, 5), (11, 12, 11), "gray"), el((5, 8, 5), (11, 9, 11), "white"), el((6.5, 12, 6.5), (9.5, 13, 9.5), "steel")],
         "flashbang": [el((5.5, 2, 5.5), (10.5, 12, 10.5), "black"), el((5.25, 4, 5.25), (10.75, 5, 10.75), "steel"),
                       el((5.25, 9, 5.25), (10.75, 10, 10.75), "steel"), el((7, 12, 7), (9, 14, 9), "steel")],
         "molotov": [el((5.5, 1, 5.5), (10.5, 9, 10.5), "glass"), el((7, 9, 7), (9, 13, 9), "glass"), el((7.25, 13, 7.25), (8.75, 15, 8.75), "red")],
         # Concussion: smooth black cylinder with tape; impact: egg with a nose fuze; satchel: canvas bag with a strap
         # and a fuze cord; thermite: grey can with a red band.
         "concussion": [el((5.5, 2, 5.5), (10.5, 12, 10.5), "black"), el((5.25, 6, 5.25), (10.75, 8, 10.75), "olive"), el((7, 12, 7), (9, 14, 9), "steel")],
         "impact_grenade": [el((5, 2, 5), (11, 9, 11), "olive"), el((5.5, 9, 5.5), (10.5, 11, 10.5), "olive"), el((7, 11, 7), (9, 14, 9), "steel"),
                            el((5.5, 1, 5.5), (10.5, 2, 10.5), "olive")],
         "satchel_charge": [el((2, 1, 4), (14, 9, 12), "tan"), el((2.5, 9, 7), (13.5, 10, 9), "tan"), el((3, 9, 7.5), (4, 15, 8.5), "olive"),
                            el((12, 9, 7.5), (13, 15, 8.5), "olive"), el((3, 14, 7.5), (13, 15, 8.5), "olive"), el((7, 5, 12), (9, 7, 14), "red")],
         "thermite": [el((5.5, 2, 5.5), (10.5, 12, 10.5), "gray"), el((5.25, 7, 5.25), (10.75, 9, 10.75), "red"), el((7, 12, 7), (9, 14, 9), "steel"),
                      el((9.5, 8, 7.5), (10.5, 13, 8.5), "steel")]}
    return m[gid]


def armor_textures(set_name, folder: Path):
    """Vanilla humanoid armour layout (64x32): 'humanoid' (helmet, torso, arms, boots) and 'humanoid_leggings'."""
    rng = random.Random(hash(set_name) & 0xFFFF)
    army = set_name == "army"
    palette = [(85, 95, 60), (55, 68, 38), (104, 84, 54), (33, 34, 28)] if army else [(32, 33, 36), (24, 25, 27), (42, 44, 48), (18, 18, 20)]

    def camo(img, box):
        x0, y0, x1, y1 = box
        for x in range(x0, x1):
            for y in range(y0, y1):
                img.putpixel((x, y), palette[0] + (255,))
        if army:
            for _ in range((x1 - x0) * (y1 - y0) // 10):
                cx, cy, c = rng.randrange(x0, x1), rng.randrange(y0, y1), palette[rng.randrange(1, 4)]
                for dx in range(rng.randint(1, 3)):
                    for dy in range(rng.randint(1, 2)):
                        if x0 <= cx + dx < x1 and y0 <= cy + dy < y1:
                            img.putpixel((cx + dx, cy + dy), c + (255,))
        else:
            for x in range(x0, x1):
                for y in range(y0, y1):
                    if rng.random() < 0.15:
                        img.putpixel((x, y), palette[rng.randrange(1, 4)] + (255,))

    main = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    camo(main, (0, 0, 32, 16))                 # helmet
    for x in range(9, 15):                     # face stays visible
        for y in range(11 if army else 13, 16):
            main.putpixel((x, y), (0, 0, 0, 0))
    if not army:
        for x in range(8, 16):                 # balaclava eye slit + NVG lenses on the hat layer
            main.putpixel((x, 11), (0, 0, 0, 0)); main.putpixel((x, 12), (0, 0, 0, 0))
        for x0 in (41, 45):
            for x in range(x0, x0 + 2):
                for y in range(10, 12):
                    main.putpixel((x, y), (60, 200, 80, 255))
        for x in range(40, 48):
            main.putpixel((x, 9), (30, 30, 32, 255))
    camo(main, (16, 16, 40, 32))               # torso
    camo(main, (40, 16, 56, 32))               # arms
    if not army:                               # plate carrier pouches
        for x0 in (21, 24):
            for x in range(x0, x0 + 2):
                for y in range(25, 28):
                    main.putpixel((x, y), (55, 58, 50, 255))
    for x in range(0, 16):                     # boots: lower leg + soles
        for y in range(16, 32):
            if y >= 27 or (y < 20 and 8 <= x < 12):
                main.putpixel((x, y), ((40, 30, 20) if army else (15, 15, 16)) + (255,))
    legs = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    camo(legs, (0, 16, 16, 32))
    camo(legs, (16, 16, 40, 32))
    for x in range(16, 40):                    # belt
        for y in range(26, 28):
            legs.putpixel((x, y), (30, 25, 20, 255))
    (folder / "humanoid").mkdir(parents=True, exist_ok=True)
    (folder / "humanoid_leggings").mkdir(parents=True, exist_ok=True)
    main.save(folder / "humanoid" / f"{set_name}.png")
    legs.save(folder / "humanoid_leggings" / f"{set_name}.png")


def clothing_icon(c):
    px = {}
    col = (85, 95, 60) if c["set"] == "army" else (32, 33, 36)
    dark = shade(col, 0.7)
    slot = c["slot"]
    if slot == "head":
        rect(px, 3, 4, 12, 9, col); rect(px, 2, 9, 13, 10, dark)
        if c.get("night_vision"):
            rect(px, 5, 2, 10, 4, (30, 30, 32)); px[(6, 3)] = (60, 200, 80); px[(9, 3)] = (60, 200, 80)
    elif slot == "chest":
        rect(px, 2, 2, 13, 5, col); rect(px, 4, 5, 11, 14, col); rect(px, 1, 3, 3, 9, dark); rect(px, 12, 3, 14, 9, dark)
        if c["set"] != "army":
            rect(px, 5, 8, 6, 10, (55, 58, 50)); rect(px, 9, 8, 10, 10, (55, 58, 50))
    elif slot == "legs":
        rect(px, 3, 2, 12, 5, col); rect(px, 3, 5, 6, 14, col); rect(px, 9, 5, 12, 14, col); rect(px, 3, 2, 12, 2, (30, 25, 20))
    else:
        rect(px, 2, 8, 6, 13, (40, 30, 20) if c["set"] == "army" else (15, 15, 16)); rect(px, 9, 8, 13, 13, (40, 30, 20) if c["set"] == "army" else (15, 15, 16))
        rect(px, 2, 13, 7, 14, (20, 20, 20)); rect(px, 9, 13, 14, 14, (20, 20, 20))
    return px


def item_model(name, kind):
    write(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    write(ASSETS / "models" / "item" / f"{name}.json",
          {"parent": "minecraft:item/generated", "textures": {"layer0": f"{NS}:item/{kind}/{name}"}})


# ------------------------------------------------------------------------------------------- recipes
# Everything gun-related is made at the Weapons Bench (recipe type flansmod:weapon_assembly, up to 6x4).
RAW = {"I": "minecraft:iron_ingot", "N": "minecraft:iron_nugget", "B": "minecraft:iron_block", "W": "#minecraft:planks",
       "K": "minecraft:black_dye", "S": "minecraft:slime_ball", "R": "minecraft:redstone", "Q": "minecraft:quartz",
       "P": "minecraft:glass_pane", "G": "minecraft:gold_ingot", "C": "minecraft:copper_ingot", "U": "minecraft:gunpowder",
       "T": "minecraft:tnt", "H": "minecraft:paper", "Z": "minecraft:blaze_powder", "Y": "minecraft:glowstone_dust",
       "E": "minecraft:string", "O": "minecraft:emerald", "V": "minecraft:green_dye", "M": "#minecraft:wool",
       "J": "minecraft:leather", "c": "minecraft:copper_nugget"}
_signatures = {}


def part_ingredient(part_id):
    return {"fabric:type": "fabric:components", "base": "flansmod:part", "components": {"flansmod:part": f"{NS}:{part_id}"}}


def bench(name, pattern, key, result, extend=False):
    """Writes a Weapons Bench recipe; with [extend], adds nuggets until the pattern is unique among all bench recipes."""
    pattern = [r for r in pattern]
    key = dict(key)
    while True:
        width = max(len(r) for r in pattern)
        rows = [r.ljust(width) for r in pattern]
        used = sorted({ch for row in rows for ch in row if ch != " "})
        signature = (tuple(rows), tuple((k, json.dumps(key[k], sort_keys=True)) for k in used))
        if signature not in _signatures:
            break
        if not extend:
            raise SystemExit(f"Bench recipe {name} duplicates {_signatures[signature]}")
        # Distinguish by an extra nugget in the first free cell to the right, then a new row.
        key.setdefault("N", RAW["N"])
        if width < 6:
            pattern = [r.ljust(width) + ("N" if i == 0 else " ") for i, r in enumerate(rows)]
        elif len(rows) < 4:
            pattern = rows + ["N".ljust(width)]
        else:
            raise SystemExit(f"Cannot make bench recipe {name} unique")
    assert len(rows) <= 4 and width <= 6, name
    _signatures[signature] = name
    write(DATA / "recipe" / f"{name}.json", {"type": "flansmod:weapon_assembly", "pattern": rows,
                                              "key": {k: key[k] for k in used}, "result": result})


def raw_key(pattern, extra=None):
    key = {ch: RAW[ch] for row in pattern for ch in row if ch != " " and ch in RAW}
    key.update(extra or {})
    return key


# ------------------------------------------------------------------------------------------- sounds
def sounds():
    def s(name, volume=1.0, pitch=1.0):
        return {"name": f"minecraft:{name}", "volume": volume, "pitch": pitch}
    events = {
        "gun.pistol.shoot": [s("fireworks/blast1", 0.9, 1.5)],
        "gun.magnum.shoot": [s("fireworks/largeblast1", 1.0, 1.6)],
        "gun.smg.shoot": [s("fireworks/blast1", 0.7, 1.8)],
        "gun.rifle.shoot": [s("fireworks/largeblast1", 1.0, 1.4)],
        "gun.ak.shoot": [s("fireworks/largeblast1", 1.0, 1.25)],
        "gun.battle.shoot": [s("fireworks/largeblast1", 1.2, 1.1)],
        "gun.shotgun.shoot": [s("random/explode1", 0.6, 1.6), s("random/explode2", 0.6, 1.6)],
        "gun.sniper.shoot": [s("fireworks/largeblast1", 1.5, 0.8)],
        "gun.heavy.shoot": [s("random/explode3", 1.2, 1.2)],
        "gun.launcher.shoot": [s("item/crossbow/shoot1", 1.0, 0.6)],
        "gun.rocket.shoot": [s("fireworks/launch1", 1.5, 0.6)],
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
        "pack": {"description": "Flan's Mod: Recoded - famous guns, magazines, ammo and attachments", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Flan's Basic Weapons", "icon": f"{NS}:ak47"}})

    gun_texture(ASSETS / "textures" / "gun" / "basic.png")
    material_atlas(ASSETS / "textures" / "item" / "materials.png")
    for kind in ("acog", "pso", "sniper", "night_vision", "thermal"):
        scope_overlay(ASSETS / "textures" / "scope" / f"{kind}.png", kind)
    binocular_overlay(ASSETS / "textures" / "scope" / "binoculars.png")

    for pid, part in PARTS.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": part["name"], "icon": f"{NS}:{pid}"})
        model3d(pid, part_model(part["icon"]))
        extra = {k: part_ingredient(v) for k, v in part.get("uses", {}).items()}
        bench(f"part_{pid}", part["pattern"], raw_key(part["pattern"], extra), {"id": "flansmod:part", "count": part.get("count", 1),
              "components": {"flansmod:part": f"{NS}:{pid}"}})

    for gid, g in GUNS.items():
        geo = gun_geometry(gid, g)
        write(DATA / "flansmod" / "guns" / f"{gid}.json", gun_definition(gid, g, geo))
        write(ASSETS / "geckolib" / "models" / "gun" / f"{gid}.geo.json", gun_model(gid, g, geo))
        write(ASSETS / "geckolib" / "animations" / "gun" / f"{gid}.animation.json", gun_animations(g, geo))
        template, parts = GUN_PARTS[gid]
        layout = ASSEMBLY[template]
        letters = {**COMMON_PARTS, **parts}
        key = {ch: part_ingredient(letters[ch]) for row in layout for ch in row if ch != " "}
        bench(gid, layout, key, {"id": "flansmod:gun", "components": {"flansmod:gun": f"{NS}:{gid}"}})

    for mid, m in MAGAZINES.items():
        definition = {"name": m["name"], "caliber": m["caliber"], "capacity": m["capacity"]}
        if m.get("reload"):
            definition["reload_multiplier"] = m["reload"]
        if m.get("internal"):
            definition["internal"] = True
            write(DATA / "flansmod" / "magazines" / f"{mid}.json", definition)
            continue
        definition["icon"] = f"{NS}:{mid}"
        write(DATA / "flansmod" / "magazines" / f"{mid}.json", definition)
        model3d(mid, normalize(magazine_cubes(mid)), MAGAZINE_DISPLAY)
        shape = list(MAGAZINE_SHAPES[m["kind"]])
        if m["kind"] in ("stick", "pistol") and m["capacity"] > 25:
            shape = ["I"] + shape
        bench(f"magazine_{mid}", shape, raw_key(shape, {"F": part_ingredient("spring")}), {"id": "flansmod:magazine", "components": {
            "flansmod:magazine": {"magazine": f"{NS}:{mid}"}}}, extend=True)

    # Ammunition components: a casing per caliber, tips/loads/warheads per ammo type.
    for cal, (pattern, count) in CASINGS.items():
        pid = f"casing_{cal}"
        name = CASING_NAMES.get(cal, f"{CALIBERS[cal]['name']} Casing")
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": name, "category": "ammo", "icon": f"{NS}:{pid}"})
        model3d(pid, casing_model(CALIBERS[cal]["cls"]), SMALL_ITEM_DISPLAY)
        bench(f"part_{pid}", pattern, raw_key(pattern), {"id": "flansmod:part", "count": count, "components": {"flansmod:part": f"{NS}:{pid}"}})
    for pid, (name, pattern, count) in TIPS.items():
        write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": name, "category": "ammo", "icon": f"{NS}:{pid}"})
        model3d(pid, tip_model(pid), SMALL_ITEM_DISPLAY)
        bench(f"part_{pid}", pattern, raw_key(pattern), {"id": "flansmod:part", "count": count, "components": {"flansmod:part": f"{NS}:{pid}"}})

    # Rounds: casing + gunpowder + tip in a row at the bench.
    for aid, a in AMMO.items():
        fields = {k: v for k, v in a.items() if k not in ("kind", "cls", "tip", "colour", "name", "caliber")}
        if fields.get("max_stack") == 64:
            del fields["max_stack"]
        if "projectile" in fields:
            fields["projectile"] = f"{NS}:{fields['projectile']}"
        write(DATA / "flansmod" / "ammo" / f"{aid}.json", {"name": a["name"], "caliber": a["caliber"], "icon": f"{NS}:{aid}", **fields})
        model3d(aid, ammo_model(a), SMALL_ITEM_DISPLAY)
        cls = CASING_CLASSES[a["cls"]]
        components = {"flansmod:ammo_type": f"{NS}:{aid}"}
        if a.get("max_stack", 64) != 64:
            components["minecraft:max_stack_size"] = a["max_stack"]
        pattern = ["A" + "U" * cls["powder"] + "D"]
        key = {"A": part_ingredient(f"casing_{a['caliber']}"), "U": RAW["U"], "D": part_ingredient(a["tip"])}
        bench(f"ammo_{aid}", pattern, key, {"id": "flansmod:ammo", "count": cls["count"], "components": components})

    for i, (aid, a) in enumerate(ATTACHMENTS.items()):
        write(DATA / "flansmod" / "attachments" / f"{aid}.json", {"name": a["name"], "slot": a["slot"], "icon": f"{NS}:{aid}", **a["stats"]})
        fake_gun = {"sight": (4, 0), "muzzle": (8, 6), "under": (12, -2)}
        model3d(aid, normalize(attachment_cubes(aid, fake_gun)))
        shape = ATTACHMENT_RECIPES[aid]
        bench(f"attachment_{aid}", shape, raw_key(shape, {"L": part_ingredient("lens"), "X": part_ingredient("circuit") if "scope" in aid or aid in ("red_dot", "holographic", "laser_sight", "green_laser") else part_ingredient("polymer")}), {"id": "flansmod:attachment", "components": {
            "flansmod:attachment": f"{NS}:{aid}"}})

    for i, (gid, gr) in enumerate(GRENADES.items()):
        definition = dict(gr)
        if gr.get("throwable", True):
            definition["icon"] = f"{NS}:{gid}"
            model3d(gid, grenade_model(gid))
            shape = GRENADE_RECIPES[gid]
            components = {"flansmod:grenade": f"{NS}:{gid}"}
            if gr.get("max_stack", 16) != 16:  # like GrenadeItem.stackFor, so crafted and creative stacks stack
                components["minecraft:max_stack_size"] = gr["max_stack"]
            bench(f"grenade_{gid}", shape, raw_key(shape), {"id": "flansmod:grenade", "count": 2 if gr.get("max_stack", 16) >= 8 else 1, "components": components})
        else:
            # Flying launcher projectiles render with their ammo item's look.
            ammo_id = next(a for a, ad in AMMO.items() if ad.get("projectile") == gid)
            definition["icon"] = f"{NS}:{ammo_id}"
        write(DATA / "flansmod" / "grenades" / f"{gid}.json", definition)

    for set_name in sorted({c["set"] for c in CLOTHING.values()}):
        armor_textures(set_name, ASSETS / "textures" / "entity" / "equipment")
        write(ASSETS / "equipment" / f"{set_name}.json", {"layers": {
            "humanoid": [{"texture": f"{NS}:{set_name}"}], "humanoid_leggings": [{"texture": f"{NS}:{set_name}"}]}})
    for cid, c in CLOTHING.items():
        definition = {"name": c["name"], "slot": c["slot"], "asset": f"{NS}:{c['set']}", "icon": f"{NS}:{cid}", "armor": c["armor"]}
        for key in ("toughness", "night_vision", "speed_modifier", "plate_slots"):
            if key in c:
                definition[key] = c[key]
        write(DATA / "flansmod" / "clothing" / f"{cid}.json", definition)
        item_model(cid, "clothing")
        icon(ASSETS / "textures" / "item" / "clothing" / f"{cid}.png", clothing_icon(c))
        bench(f"clothing_{cid}", c["recipe"], raw_key(c["recipe"], {"L": part_ingredient("lens"), "X": part_ingredient("circuit")}),
              {"id": "flansmod:clothing", "components": {"flansmod:clothing": f"{NS}:{cid}"}}, extend=True)

    write_gear(GEAR, gear_model)

    # Structure kits (bunkers, trenches, tower, streets, ...): vanilla structure templates + definitions + bench recipes.
    structure_key = {"C": "flansmod:reinforced_concrete", "S": "flansmod:sandbags", "P": "#minecraft:planks", "L": "#minecraft:logs",
                     "D": "flansmod:bunker_door", "G": "minecraft:gravel", "W": "flansmod:barbed_wire", "H": "flansmod:czech_hedgehog",
                     "B": "minecraft:bricks", "T": "minecraft:stone_bricks", "N": "minecraft:glass_pane", "I": "minecraft:iron_ingot"}
    for sid, pattern in ss.write_structures(DATA, NS).items():
        bench(f"structure_{sid}", pattern, {ch: structure_key[ch] for row in pattern for ch in row if ch != " "},
              {"id": "flansmod:structure", "components": {"flansmod:structure": f"{NS}:{sid}"}}, extend=True)

    lang = {}
    for event in sounds():
        lang[f"subtitles.{NS}.{event}"] = "Gunshot" if "shoot" in event or "suppressed" in event else \
            "Gun reloads" if "reload" in event else "Gun clicks"
    lang.update({f"caliber.flansmod.{cal}": c["name"] for cal, c in CALIBERS.items()})
    write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(GUNS)} guns, {len(MAGAZINES)} magazines, {len(AMMO)} ammo types, {len(ATTACHMENTS)} attachments, "
          f"{len(GRENADES)} grenades/projectiles, {len(PARTS)} parts, {len(CLOTHING)} clothing, {len(_signatures)} bench recipes in {ROOT}")


if __name__ == "__main__":
    main()
