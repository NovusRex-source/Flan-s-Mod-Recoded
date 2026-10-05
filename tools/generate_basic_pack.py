#!/usr/bin/env python3
"""
Generates the built-in "Basic" content pack (src/main/resources/resourcepacks/basic): famous real-world guns
with their magazines and ammunition, attachments (incl. scopes), grenades and launcher projectiles, plus
recipes, GeckoLib models + animations, textures, icons, scope overlays and sounds.

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

NS = "flansbasic"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/basic"
DATA = ROOT / "data" / NS
ASSETS = ROOT / "assets" / NS

# Gun texture: 256x128, eight 64x64 noisy material blocks (box UV).
MATERIALS = {"metal": (0, 0), "polymer": (64, 0), "wood": (128, 0), "red": (192, 0),
             "olive": (0, 64), "tan": (64, 64), "steel": (128, 64), "lens": (192, 64)}
COLOURS = {"metal": (58, 61, 66), "polymer": (34, 36, 40), "wood": (122, 82, 48), "red": (200, 30, 30),
           "olive": (82, 92, 58), "tan": (176, 150, 108), "steel": (128, 132, 138), "lens": (70, 120, 140)}


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def cube(origin, size, mat="metal"):
    return {"origin": [round(v, 3) for v in origin], "size": [round(v, 3) for v in size], "uv": list(MATERIALS[mat])}


def bone(name, cubes, parent="gun"):
    b = {"name": name, "pivot": [0, 0, 0], "cubes": cubes}
    if parent:
        b["parent"] = parent
    return b


# ------------------------------------------------------------------------------------------- ammunition
# id: name, caliber, icon shape, colour, effects
AMMO = {
    "9mm": dict(name="9mm FMJ", caliber="9mm", shape="pistol", colour=(212, 175, 55)),
    "9mm_hp": dict(name="9mm Hollow Point", caliber="9mm", shape="pistol", colour=(190, 120, 60), damage_multiplier=1.25, spread_multiplier=1.1),
    "45acp": dict(name=".45 ACP", caliber="45acp", shape="pistol", colour=(200, 160, 60)),
    "50ae": dict(name=".50 AE", caliber="50ae", shape="pistol", colour=(220, 190, 80)),
    "357": dict(name=".357 Magnum", caliber="357", shape="pistol", colour=(205, 170, 70)),
    "57": dict(name="5.7x28mm", caliber="57", shape="rifle", colour=(190, 170, 90)),
    "556": dict(name="5.56 NATO", caliber="556", shape="rifle", colour=(200, 160, 50)),
    "556_ap": dict(name="5.56 NATO AP", caliber="556", shape="rifle", colour=(60, 60, 60), armor_piercing=True, damage_multiplier=0.9),
    "556_tracer": dict(name="5.56 NATO Tracer", caliber="556", shape="rifle", colour=(200, 60, 40),
                       tracer={"color": "#FF4020", "width": 0.06, "length": 5}),
    "762x39": dict(name="7.62x39mm", caliber="762x39", shape="rifle", colour=(150, 120, 70)),
    "762x39_ap": dict(name="7.62x39mm AP", caliber="762x39", shape="rifle", colour=(70, 70, 70), armor_piercing=True, damage_multiplier=0.9),
    "762x51": dict(name="7.62 NATO", caliber="762x51", shape="rifle", colour=(190, 150, 60)),
    "762x54": dict(name="7.62x54mmR", caliber="762x54", shape="rifle", colour=(160, 130, 70)),
    "338": dict(name=".338 Lapua Magnum", caliber="338", shape="rifle", colour=(210, 180, 80)),
    "50bmg": dict(name=".50 BMG", caliber="50bmg", shape="big", colour=(200, 170, 60)),
    "50bmg_api": dict(name=".50 BMG API", caliber="50bmg", shape="big", colour=(180, 40, 40), armor_piercing=True, fire_seconds=4),
    "12g": dict(name="12 Gauge Buckshot", caliber="12g", shape="shell", colour=(190, 40, 40), pellets=8),
    "12g_slug": dict(name="12 Gauge Slug", caliber="12g", shape="shell", colour=(40, 90, 170), pellets=1, damage_multiplier=4.0, spread_multiplier=0.3),
    "12g_dragon": dict(name="12 Gauge Dragon's Breath", caliber="12g", shape="shell", colour=(230, 130, 30), pellets=8,
                       fire_seconds=5, damage_multiplier=0.6, tracer={"color": "#FF8A20", "width": 0.08, "length": 2}),
    "40mm_he": dict(name="40mm HE Grenade", caliber="40mm", shape="40mm", colour=(90, 100, 60), projectile="40mm_he", max_stack=16),
    "pg7": dict(name="PG-7V Rocket", caliber="rpg", shape="rocket", colour=(90, 100, 60), projectile="rocket", max_stack=8),
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
    "shell_holder_6": dict(name="Shell Holder (6)", caliber="12g", capacity=6, kind="shells", guns=["m870"]),
    "spas_8": dict(name="SPAS-12 Shell Holder (8)", caliber="12g", capacity=8, kind="shells", guns=["spas12"]),
    "aa12_8": dict(name="AA-12 Box (8)", caliber="12g", capacity=8, kind="box", guns=["aa12"]),
    "aa12_drum_20": dict(name="AA-12 Drum (20)", caliber="12g", capacity=20, kind="drum", guns=["aa12"], reload=1.5),
    "m79_shell": dict(name="40mm Round Holder (1)", caliber="40mm", capacity=1, kind="loader", guns=["m79"]),
    "pg7_loader": dict(name="RPG-7 Rocket Loader (1)", caliber="rpg", capacity=1, kind="rocket", guns=["rpg7"]),
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
                ads_spread=0.1, recoil=(3.2, 0.8), zoom=4.0, slots=["sight", "muzzle"], sound="battle",
                scope={"overlay": f"{NS}:textures/scope/pso.png"}, ads_height=1.6),
    "m14": dict(name="M14 EBR", arch="dmr", L=1.15, furniture="polymer", dmg=9, rpm=300, mode="semi", reload=55, vel=18, spread=2.5,
                ads_spread=0.2, recoil=(2.2, 0.7), zoom=1.5, slots=["sight", "muzzle", "underbarrel"], sound="battle"),
    # Snipers
    "m24": dict(name="M24", arch="sniper", L=1.2, furniture="olive", dmg=18, rpm=40, mode="semi", reload=70, vel=25, spread=4,
                ads_spread=0.05, recoil=(6, 0.8), zoom=1.5, slots=["sight", "muzzle"], sound="sniper", headshot=2.5, move=0.45),
    "awm": dict(name="AWM", arch="sniper", L=1.3, furniture="olive", dmg=22, rpm=35, mode="semi", reload=75, vel=28, spread=4,
                ads_spread=0.03, recoil=(7, 0.8), zoom=1.5, slots=["sight", "muzzle"], sound="sniper", headshot=2.5, move=0.45),
    "barrett": dict(name="Barrett M82", arch="sniper", L=1.5, furniture="polymer", dmg=28, rpm=90, mode="semi", reload=80, vel=30,
                    spread=5, ads_spread=0.08, recoil=(8, 1.5), zoom=1.5, slots=["sight", "muzzle"], sound="heavy", headshot=2.0, move=0.4),
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
                recoil=(1.4, 1.0), zoom=1.35, slots=["sight", "muzzle"], sound="battle", move=0.5),
    # Launchers (fire grenade-type projectiles from their ammo)
    "m79": dict(name="M79 Grenade Launcher", arch="launcher", L=0.9, furniture="wood", dmg=0, rpm=30, mode="semi", reload=45, vel=1.6,
                spread=1, recoil=(4, 1), zoom=1.2, slots=[], sound="launcher"),
    "rpg7": dict(name="RPG-7", arch="rpg", L=1.0, furniture="wood", dmg=0, rpm=30, mode="semi", reload=70, vel=2.5,
                 spread=0.5, recoil=(3, 1), zoom=1.5, slots=["sight"], sound="rocket", move=0.5),
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
# Ammo: bullet + gunpowder + casing by shape, plus variant materials.
AMMO_SHAPES = {"pistol": ["NUC"], "rifle": ["NUUC"], "big": ["NUUUC"], "shell": ["NUH"], "40mm": ["TC", "CC"], "rocket": ["TUII"]}
AMMO_VARIANT = {"9mm_hp": {"N": "S"}, "556_ap": {"N": "I"}, "762x39_ap": {"N": "I"}, "556_tracer": {"extra": "R"},
                "50bmg_api": {"N": "I", "extra": "Z"}, "12g_slug": {"N": "I"}, "12g_dragon": {"extra": "Z"}}
ATTACHMENT_RECIPES = {
    "red_dot": ["ILI", " X "], "holographic": ["ILLI", " XX "], "acog": ["ILLI", "I  I"], "sniper_scope": ["ILLLI", "I   I"],
    "nv_scope": ["ILLI", "OXXO"], "thermal_scope": ["ILLI", "ZXXZ"], "suppressor": ["IKKKK"], "compensator": ["NIN"],
    "muzzle_brake": ["INI", "N N"], "vertical_grip": ["XX", " X", " X"], "angled_grip": ["XXX", "  X"],
}
GRENADE_RECIPES = {"frag": ["NIN", "IUI", "NIN"], "smoke": ["NHN", "IUI", "NIN"], "flashbang": ["NYN", "IUI", "NIN"],
                   "molotov": [" E ", " Z ", "PUP"]}

# ------------------------------------------------------------------------------------------- clothing
CLOTHING = {
    "army_helmet": dict(name="Army Helmet", slot="head", set="army", armor=2, toughness=0.5, recipe=["IVI", "I I"]),
    "army_jacket": dict(name="Army Jacket", slot="chest", set="army", armor=5, recipe=["MVM", "MMM", "MMM"]),
    "army_pants": dict(name="Army Pants", slot="legs", set="army", armor=4, recipe=["MVM", "M M", "M M"]),
    "army_boots": dict(name="Army Boots", slot="feet", set="army", armor=2, recipe=["JVJ", "J J"]),
    "spec_ops_helmet": dict(name="Spec Ops Helmet (NVG)", slot="head", set="spec_ops", armor=3, toughness=1, night_vision=True,
                            recipe=["IKI", "LXL"]),
    "spec_ops_vest": dict(name="Spec Ops Plate Carrier", slot="chest", set="spec_ops", armor=7, toughness=2, speed_modifier=-0.05,
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
ATTACHMENTS = {
    "red_dot": dict(name="Red Dot Sight", slot="sight", stats={"ads_zoom": 1.5, "spread_multiplier": 0.9, "ads_height": 1.2}),
    "holographic": dict(name="Holographic Sight", slot="sight", stats={"ads_zoom": 1.4, "spread_multiplier": 0.85, "ads_height": 1.4}),
    "acog": dict(name="ACOG 4x Scope", slot="sight", stats={"ads_zoom": 4.0, "spread_multiplier": 0.8, "ads_move_speed_multiplier": 0.85,
                 "ads_height": 1.6, "scope": {"overlay": f"{NS}:textures/scope/acog.png"}}),
    "sniper_scope": dict(name="8x Sniper Scope", slot="sight", stats={"ads_zoom": 8.0, "spread_multiplier": 0.7, "ads_move_speed_multiplier": 0.7,
                         "ads_height": 2.0, "scope": {"overlay": f"{NS}:textures/scope/sniper.png"}}),
    "nv_scope": dict(name="Night Vision Scope", slot="sight", stats={"ads_zoom": 4.0, "ads_move_speed_multiplier": 0.85, "ads_height": 1.8,
                     "scope": {"overlay": f"{NS}:textures/scope/night_vision.png", "night_vision": True}}),
    "thermal_scope": dict(name="Thermal Scope", slot="sight", stats={"ads_zoom": 3.0, "ads_move_speed_multiplier": 0.85, "ads_height": 1.8,
                          "scope": {"overlay": f"{NS}:textures/scope/thermal.png", "thermal": True, "thermal_range": 96}}),
    "suppressor": dict(name="Suppressor", slot="muzzle", stats={"damage_multiplier": 0.9, "hide_tracer": True, "shoot_sound": f"{NS}:gun.suppressed"}),
    "compensator": dict(name="Compensator", slot="muzzle", stats={"recoil_multiplier": 0.7}),
    "muzzle_brake": dict(name="Muzzle Brake", slot="muzzle", stats={"recoil_multiplier": 0.6, "spread_multiplier": 1.1}),
    "vertical_grip": dict(name="Vertical Grip", slot="underbarrel", stats={"recoil_multiplier": 0.75, "spread_multiplier": 0.85}),
    "angled_grip": dict(name="Angled Grip", slot="underbarrel", stats={"recoil_multiplier": 0.85, "ads_move_speed_multiplier": 1.15}),
}

GRENADES = {
    "frag": dict(name="Frag Grenade", fuse_ticks=60, bounciness=0.35, explosion={"power": 2.5, "fire": False, "break_blocks": False}),
    "smoke": dict(name="Smoke Grenade", fuse_ticks=40, bounciness=0.3, smoke={"radius": 5.0, "duration_ticks": 300},
                  detonate_sound="minecraft:block.fire.extinguish"),
    "flashbang": dict(name="Flashbang", fuse_ticks=35, bounciness=0.4, flash={"radius": 12.0, "duration_ticks": 100}),
    "molotov": dict(name="Incendiary Grenade", fuse_ticks=40, bounciness=0.2, contact=True,
                    explosion={"power": 1.2, "fire": True, "break_blocks": False}),
    # Launcher projectiles (not throwable)
    "40mm_he": dict(name="40mm HE", throwable=False, contact=True, gravity=0.03, fuse_ticks=200,
                    explosion={"power": 2.2, "fire": False, "break_blocks": False}),
    "rocket": dict(name="Rocket", throwable=False, contact=True, gravity=0.004, fuse_ticks=120, trail=True,
                   explosion={"power": 3.5, "fire": False, "break_blocks": False}),
}


# ------------------------------------------------------------------------------------------- models
def gun_geometry(gid, g):
    """Returns dict with body cubes, moving part, magazine cubes per kind, sight/muzzle/under positions, iron sights."""
    a, L, f = g["arch"], g["L"], g["furniture"]
    body = g.get("body", "metal")
    s = lambda v: v * L  # noqa: E731  scale lengths (z)

    if a == "pistol":
        top, front = 7.2, -s(6)
        parts = [cube((-1, 5, front), (2, 2.2, s(9)), body), cube((-0.75, 0, 0.5), (1.5, 5, 2), f),
                 cube((-0.25, 3.5, -1.5), (0.5, 0.5, 2))]
        moving = ("slide", [cube((-1.05, 6.1, front - 0.2), (2.1, 1.1, s(9) + 0.4), body)])
        mag = [cube((-0.6, -0.8, 0.7), (1.2, 1, 1.6))]
        return dict(parts=parts, moving=moving, mag=mag, drum=None, sight=(top, 0), muzzle=(6.1, front), under=None,
                    iron=[cube((-0.4, top, 1.8), (0.8, 0.6, 0.6)), cube((-0.2, top, front + 0.4), (0.4, 0.5, 0.4))])
    if a == "revolver":
        front = -s(8)
        parts = [cube((-0.6, 6, front), (1.2, 1.2, s(5))), cube((-1.2, 5, -3), (2.4, 2.4, 3.5), body),
                 cube((-0.9, 5, 0.5), (1.8, 2.2, 1.5)), cube((-0.75, 0, 1), (1.5, 5, 2), f)]
        moving = ("hammer", [cube((-0.3, 7, 1.5), (0.6, 1, 0.6))])
        mag = [cube((-1.25, 5.2, -2.8), (2.5, 2, 3), "steel")]
        return dict(parts=parts, moving=moving, mag=mag, drum=None, sight=(7.4, -1), muzzle=(6.6, front), under=None,
                    iron=[cube((-0.2, 7.2, front + 0.3), (0.4, 0.6, 0.5))])
    if a in ("smg", "bullpup_smg"):
        front = -s(12)
        if a == "bullpup_smg":
            parts = [cube((-1.3, 4, -8), (2.6, 4, 14), f), cube((-0.5, 5.5, front), (1, 1, 4)), cube((-0.75, 0.5, -4), (1.5, 3.5, 2), f)]
            mag = [cube((-1, 8, -6), (2, 0.8, 11), "steel")]
            return dict(parts=parts, moving=("bolt", [cube((1.2, 6, -2), (0.4, 0.6, 1.5))]), mag=mag, drum=None,
                        sight=(8.8, -2), muzzle=(6, front), under=None,
                        iron=[cube((-0.5, 8.8, 0), (1, 1, 1))])
        parts = [cube((-1, 4, -8), (2, 3, s(12))), cube((-0.75, 0, 0), (1.5, 4, 2), f),
                 cube((-0.5, 5, front), (1, 1, s(12) - 8 + 0.01) if s(12) > 8 else (1, 1, 0.5)),
                 cube((-0.5, 4.5, 4), (1, 1.5, 5), f), cube((-1.1, 3.6, -7.5), (2.2, 1, 4), f)]
        mag_z = 0.2 if g.get("mag_in_grip") else -5
        mag = [cube((-0.6, -1, mag_z), (1.2, 5, 1.6), "steel")]
        drum = [cube((-1.2, -2.5, -7), (2.4, 5, 5), "steel")]
        return dict(parts=parts, moving=("bolt", [cube((0.9, 5.5, -3), (0.4, 0.6, 1.5))]), mag=mag, drum=drum,
                    sight=(7, -2), muzzle=(5.5, front), under=(3.6, -6.5),
                    iron=[cube((-0.5, 7, 0), (1, 1, 1)), cube((-0.25, 7, -7.5), (0.5, 0.8, 0.5))])
    if a in ("rifle", "dmr", "lmg"):
        front = -s(16 if a == "rifle" else 20)
        stock_len = 6 if a != "lmg" else 7
        parts = [cube((-0.5, 7, front), (1, 1, -front - 6)), cube((-1, 6, -8), (2, 3, 12), body),
                 cube((-1, 4, 4), (2, 4, stock_len), f), cube((-0.5, 3, -1), (1, 3, 2), f),
                 cube((-1.1, 5.5, -12), (2.2, 2.2, 5), f)]
        if a == "lmg":
            parts += [cube((-0.3, 4, front + 3), (0.6, 3, 0.6)), cube((-1.5, 9, -6), (3, 1, 6))]
        mag = [cube((-0.6, 1, -6), (1.2, 5, 2), "steel")] if not g.get("curved_mag") else \
            [cube((-0.6, 2, -6), (1.2, 4, 2), "steel"), cube((-0.6, 0, -7.2), (1.2, 2.2, 2), "steel")]
        drum = [cube((-1.4, -1, -8.5), (2.8, 6, 6), "steel")]
        if a == "lmg":
            mag = [cube((-2.6, 2, -7), (2, 4, 4), "olive")]
            drum = None
        return dict(parts=parts, moving=("bolt", [cube((0.9, 7, -3), (0.4, 0.6, 2))]), mag=mag, drum=drum,
                    sight=(9, -2), muzzle=(7.5, front), under=(5.5, -9.5),
                    iron=[cube((-0.5, 9, 0), (1, 1.2, 1.5)), cube((-0.3, 9, -10.5), (0.6, 1, 0.5))])
    if a == "sniper":
        front = -s(18)
        parts = [cube((-0.5, 6.5, front), (1, 1, -front - 8)), cube((-1, 5, -8), (2, 3, 10), body),
                 cube((-1, 3, 2), (2, 4, 8), f), cube((-0.5, 2, -1), (1, 3, 1.5), f), cube((-1.2, 4, -14), (2.4, 2.5, 6), f)]
        if gid == "barrett":
            parts += [cube((-1.5, 6.2, front - 2), (3, 1.6, 2)), cube((-1.2, 5, -12), (2.4, 4, 14), body)]
        mag = [cube((-0.6, 2.5, -5), (1.2, 2.5, 2), "steel")]
        return dict(parts=parts, moving=("bolt", [cube((1, 7, -2), (1.5, 0.6, 0.6))]), mag=mag, drum=None,
                    sight=(8, -3), muzzle=(7, front), under=None,
                    iron=[cube((-0.3, 8, front + 1), (0.6, 0.6, 0.6))])
    if a == "shotgun":
        front = -s(16)
        parts = [cube((-0.75, 6, front), (1.5, 1.5, -front - 2)), cube((-1, 4.5, -2), (2, 3, 6), body),
                 cube((-1, 2.5, 4), (2, 3.5, 7), f), cube((-0.5, 1.5, 1), (1, 3, 1.5), f)]
        moving = ("pump", [cube((-0.9, 4.6, -11), (1.8, 1.3, 5), f)])
        mag = [cube((-0.6, 4.8, front + 1.5), (1.2, 1.2, 3))] if not g.get("box_mag") else [cube((-0.8, 1.5, -1.5), (1.6, 3, 2.5), "steel")]
        drum = [cube((-1.3, -1, -3), (2.6, 5.5, 5.5), "steel")] if g.get("box_mag") else None
        return dict(parts=parts, moving=moving, mag=mag, drum=drum, sight=(7.5, -1), muzzle=(6.75, front), under=(4.5, -8),
                    iron=[cube((-0.25, 7.5, front + 0.5), (0.5, 0.5, 0.5))])
    if a == "launcher":
        front = -s(12)
        parts = [cube((-1.4, 4.5, front), (2.8, 2.8, -front)), cube((-1, 2.5, 0), (2, 3.5, 8), f), cube((-0.5, 1.5, -1), (1, 3, 1.5), f)]
        mag = [cube((-1.2, 4.7, -1.5), (2.4, 2.4, 1.2), "olive")]
        return dict(parts=parts, moving=("breech", [cube((-1.5, 4.4, -0.5), (3, 3, 0.6))]), mag=mag, drum=None,
                    sight=(7.3, -3), muzzle=(5.9, front), under=None,
                    iron=[cube((-0.5, 7.3, -4), (1, 1.5, 0.4))])
    if a == "rpg":
        front = -s(14)
        parts = [cube((-1, 5, front), (2, 2, 26)), cube((-1.3, 4.7, -2), (2.6, 2.6, 6), f),
                 cube((-0.5, 1.5, -1), (1, 3.5, 1.5), f), cube((-0.5, 1.5, 4), (1, 3.5, 1.5), f), cube((-1.5, 4.5, 10), (3, 3, 3))]
        mag = [cube((-1.6, 4.4, front - 5), (3.2, 3.2, 5), "olive"), cube((-0.8, 5.2, front - 7), (1.6, 1.6, 2), "olive")]
        return dict(parts=parts, moving=("trigger", [cube((-0.2, 3.5, 0), (0.4, 0.8, 0.4))]), mag=mag, drum=None,
                    sight=(7, -1), muzzle=(6, front - 7), under=None,
                    iron=[cube((-0.5, 7, -1), (1, 1.5, 1))])
    raise ValueError(a)


def attachment_cubes(name, geo):
    top, sz = geo["sight"]
    my, mz = geo["muzzle"]
    if name == "red_dot":
        return [cube((-1, top, sz - 1.5), (2, 0.5, 3)), cube((-1, top + 0.5, sz - 1), (2, 2, 0.4)),
                cube((-0.3, top + 1.2, sz - 0.9), (0.6, 0.6, 0.2), "red")]
    if name == "holographic":
        return [cube((-1.1, top, sz - 2), (2.2, 0.6, 4)), cube((-1.1, top + 0.6, sz - 1.8), (0.4, 2, 3.6)),
                cube((0.7, top + 0.6, sz - 1.8), (0.4, 2, 3.6)), cube((-0.7, top + 0.8, sz - 1.6), (1.4, 1.4, 0.2), "lens")]
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
    if geo["under"] and name in ("vertical_grip", "angled_grip"):
        uy, uz = geo["under"]
        if name == "vertical_grip":
            return [cube((-0.5, uy - 3, uz), (1, 3, 1.2), "polymer")]
        return [cube((-0.5, uy - 1.2, uz - 1), (1, 1.2, 3), "polymer")]
    return None


def gun_model(gid, g, geo):
    bones = [bone("gun", [], parent=None), bone("body", geo["parts"]), bone(geo["moving"][0], geo["moving"][1]),
             bone("magazine", geo["mag"]), bone("default_sight", geo["iron"])]
    for mid, m in MAGAZINES.items():
        if gid in m["guns"] and m["kind"] == "drum" and geo["drum"]:
            bones.append(bone(f"magazine_{mid}", geo["drum"]))
    for aid, a in ATTACHMENTS.items():
        if a["slot"] in g["slots"]:
            cubes = attachment_cubes(aid, geo)
            if cubes:
                bones.append(bone(f"attachment_{aid}", cubes))
    return {"format_version": "1.12.0", "minecraft:geometry": [{
        "description": {"identifier": f"geometry.{gid}", "texture_width": 256, "texture_height": 128}, "bones": bones}]}


def gun_animations(g, geo):
    moving = geo["moving"][0]
    kick = {"bolt": [0, 0, 1.5], "slide": [0, 0, 1.5], "pump": [0, 0, 2.5], "hammer": [0, 0, 0.5],
            "breech": [0, 0, 1], "trigger": [0, 0, 0.3]}[moving]
    reload_s = g["reload"] / 20
    shoot_len = max(0.12, min(60 / g["rpm"], 1.0))
    r = lambda v: str(round(v, 3))  # noqa: E731
    return {"format_version": "1.8.0", "animations": {
        "shoot": {"animation_length": shoot_len, "bones": {
            "gun": {"position": {"0.0": [0, 0, 0], "0.03": [0, 0.1, 0.6], r(shoot_len): [0, 0, 0]},
                    "rotation": {"0.0": [0, 0, 0], "0.03": [-3, 0, 0], r(shoot_len): [0, 0, 0]}},
            moving: {"position": {"0.0": [0, 0, 0], "0.04": kick, r(shoot_len): [0, 0, 0]}}}},
        "reload": {"animation_length": reload_s, "bones": {
            "gun": {"rotation": {"0.0": [0, 0, 0], r(reload_s * 0.2): [15, 0, -20], r(reload_s * 0.8): [15, 0, -20], r(reload_s): [0, 0, 0]}},
            "magazine": {"position": {"0.0": [0, 0, 0], r(reload_s * 0.3): [0, -8, 0], r(reload_s * 0.6): [0, -8, 0], r(reload_s * 0.75): [0, 0, 0]}}}},
    }}


def gun_definition(gid, g, geo):
    length = -geo["muzzle"][1] + 10
    gui_scale = round(min(0.8, 13 / length), 2)
    sight_top = geo["sight"][0] + 1  # line of sight ~1px above the rail
    ads_y = round(6.2 + (10 - sight_top) - g.get("ads_height", 0), 2)
    d = {
        "name": g["name"],
        "model": {"texture": f"{NS}:textures/gun/basic.png"},
        "damage": g["dmg"], "rpm": g["rpm"], "fire_mode": g["mode"], "reload_ticks": g["reload"],
        "velocity": g["vel"], "spread": g["spread"], "ads_spread": g.get("ads_spread", round(g["spread"] / 5, 2)),
        "ads_zoom": g["zoom"], "ads_move_speed": g.get("move", 0.6),
        "recoil": {"pitch": g["recoil"][0], "yaw": g["recoil"][1]},
        "magazines": [f"{NS}:{m}" for m, mag in MAGAZINES.items() if gid in mag["guns"]],
        "sounds": {"shoot": f"{NS}:gun.{g['sound']}.shoot", "reload": f"{NS}:gun.reload", "empty": f"{NS}:gun.empty"},
        "fire_modes": FIRE_MODES[gid],
        "attachment_slots": g["slots"],
        "display": {
            "gui": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "fixed": {"rotation": [0, -90, 0], "translation": [-1.5, -0.5, 0], "scale": [gui_scale] * 3},
            "ads": {"translation": [0, ads_y, -6]},
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


def gun_texture(path: Path):
    rng = random.Random(1)
    img = Image.new("RGBA", (256, 128))
    for mat, (ox, oy) in MATERIALS.items():
        noise_block(img, ox, oy, COLOURS[mat], rng, wood=mat == "wood")
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


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


def el(frm, to, mat="metal"):
    return (tuple(frm), tuple(to), mat)


def model3d(name, elements):
    """Writes items/<name>.json + models/item/<name>.json as a 3D element model (parent block/block for display)."""
    write(ASSETS / "items" / f"{name}.json", {"model": {"type": "minecraft:model", "model": f"{NS}:item/{name}"}})
    out = []
    for frm, to, mat in elements:
        cx, cy = ATLAS[mat]
        face = {"uv": [cx + 1, cy + 1, cx + 3, cy + 3], "texture": "#m"}
        out.append({"from": [round(max(0, min(16, v)), 3) for v in frm], "to": [round(max(0, min(16, v)), 3) for v in to],
                    "faces": {f: dict(face) for f in ("north", "south", "east", "west", "up", "down")}})
    write(ASSETS / "models" / "item" / f"{name}.json", {"parent": "minecraft:block/block",
          "textures": {"m": f"{NS}:item/materials", "particle": f"{NS}:item/materials"}, "elements": out})


def normalize(cubes, target=14.0):
    """Gun-model cubes (pixels, origin at the gun) → elements centred in the 0..16 item space."""
    lo = [min(c["origin"][i] for c in cubes) for i in range(3)]
    hi = [max(c["origin"][i] + c["size"][i] for c in cubes) for i in range(3)]
    scale = min(1.6, target / max(hi[i] - lo[i] for i in range(3)))
    centre = [(lo[i] + hi[i]) / 2 for i in range(3)]
    mats = {tuple(v): k for k, v in MATERIALS.items()}
    result = []
    for c in cubes:
        frm = [8 + (c["origin"][i] - centre[i]) * scale for i in range(3)]
        to = [8 + (c["origin"][i] + c["size"][i] - centre[i]) * scale for i in range(3)]
        result.append(el(frm, to, mats.get(tuple(c["uv"]), "metal")))
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


def ammo_model(a):
    shape = a["shape"]
    tip = "black" if a.get("armor_piercing") else "red" if "tracer" in a else "orange" if a.get("fire_seconds") else "copper"
    if shape == "shell":
        body = "lens" if a.get("pellets") == 1 else "orange" if a.get("fire_seconds") else "red"
        return [e for x in (4, 9) for e in (el((x, 2, 7), (x + 3, 10, 10), body), el((x, 2, 7), (x + 3, 4, 10), "brass"))]
    if shape == "40mm":
        return [el((5, 2, 5), (11, 7, 11), "brass"), el((5, 7, 5), (11, 12, 11), "olive"), el((6, 12, 6), (10, 13, 10), "olive")]
    if shape == "rocket":
        return [el((7, 0, 7), (9, 6, 9), "metal"), el((6, 6, 6), (10, 12, 10), "olive"), el((6.5, 12, 6.5), (9.5, 15, 9.5), "olive"),
                el((7.25, 15, 7.25), (8.75, 16, 8.75), "olive"), el((5.5, 0, 7.75), (10.5, 2, 8.25), "metal")]
    height = {"pistol": 6, "rifle": 9, "big": 12}[shape]
    width = 2.5 if shape == "big" else 2
    out = []
    for x, z in ((4, 6), (7.5, 8), (11, 6.5)):
        out.append(el((x, 2, z), (x + width, 2 + height, z + width), "brass"))
        out.append(el((x + 0.3, 2 + height, z + 0.3), (x + width - 0.3, 4.5 + height * 0.25, z + width - 0.3), tip))
    return out


def magazine_model(kind):
    m = {"pistol": [el((6.5, 3, 7), (9.5, 12, 9), "polymer"), el((6.5, 2, 6.75), (9.5, 3, 9.25), "black"), el((7, 12, 7.25), (9, 12.5, 8.75), "brass")],
         "stick": [el((6.5, 1, 6.5), (9.5, 14, 9.5), "steel"), el((6.25, 0, 6.25), (9.75, 1, 9.75), "black"), el((7, 14, 7), (9, 15, 9), "brass")],
         "curved": [el((6.5, 7, 6.5), (9.5, 14, 9.5), "steel"), el((5, 1, 6.5), (8, 7.5, 9.5), "steel"), el((7, 14, 7), (9, 15, 9), "brass")],
         "drum": [el((3, 2, 5.5), (13, 12, 10.5), "steel"), el((4, 1, 6), (12, 13, 10), "steel"), el((6.5, 12, 7), (9.5, 16, 9), "steel"),
                  el((7, 6, 10.5), (9, 8, 11), "black")],
         "box": [el((3, 2, 4), (13, 11, 12), "olive"), el((3, 11, 4), (13, 12, 12), "olive"), el((7, 12, 7), (9, 14, 9), "black")],
         "loader": [el((5, 5, 5), (11, 7, 11), "black")] + [el((6 + dx * 3, 7, 6 + dz * 3), (7 + dx * 3, 10, 7 + dz * 3), "brass")
                                                             for dx in (0, 1) for dz in (0, 1)],
         "shells": [el((2, 4, 6), (14, 6, 10), "black")] + [el((3 + i * 3, 6, 7), (5 + i * 3, 11, 9), "red") for i in range(4)],
         "rocket": ammo_model({"shape": "rocket"})}
    return m[kind]


def grenade_model(gid):
    m = {"frag": [el((5, 2, 5), (11, 10, 11), "olive"), el((6.5, 10, 6.5), (9.5, 12, 9.5), "steel"), el((9.5, 7, 7.5), (10.5, 12, 8.5), "steel"),
                  el((5, 5, 5), (11, 6, 11), "black")],
         "smoke": [el((5, 2, 5), (11, 12, 11), "gray"), el((5, 8, 5), (11, 9, 11), "white"), el((6.5, 12, 6.5), (9.5, 13, 9.5), "steel")],
         "flashbang": [el((5.5, 2, 5.5), (10.5, 12, 10.5), "black"), el((5.25, 4, 5.25), (10.75, 5, 10.75), "steel"),
                       el((5.25, 9, 5.25), (10.75, 10, 10.75), "steel"), el((7, 12, 7), (9, 14, 9), "steel")],
         "molotov": [el((5.5, 1, 5.5), (10.5, 9, 10.5), "glass"), el((7, 9, 7), (9, 13, 9), "glass"), el((7.25, 13, 7.25), (8.75, 15, 8.75), "red")]}
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
       "J": "minecraft:leather"}
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

    for i, (mid, m) in enumerate(MAGAZINES.items()):
        definition = {"name": m["name"], "caliber": m["caliber"], "capacity": m["capacity"], "icon": f"{NS}:{mid}"}
        if m.get("reload"):
            definition["reload_multiplier"] = m["reload"]
        write(DATA / "flansmod" / "magazines" / f"{mid}.json", definition)
        model3d(mid, magazine_model(m["kind"]))
        shape = list(MAGAZINE_SHAPES[m["kind"]])
        if m["kind"] in ("stick", "pistol") and m["capacity"] > 25:
            shape = ["I"] + shape
        bench(f"magazine_{mid}", shape, raw_key(shape, {"F": part_ingredient("spring")}), {"id": "flansmod:magazine", "components": {
            "flansmod:magazine": {"magazine": f"{NS}:{mid}"}}}, extend=True)

    for i, (aid, a) in enumerate(AMMO.items()):
        fields = {k: v for k, v in a.items() if k not in ("shape", "colour", "name", "caliber")}
        if "projectile" in fields:
            fields["projectile"] = f"{NS}:{fields['projectile']}"
        write(DATA / "flansmod" / "ammo" / f"{aid}.json", {"name": a["name"], "caliber": a["caliber"], "icon": f"{NS}:{aid}", **fields})
        model3d(aid, ammo_model(a))
        count = 4 if a["shape"] in ("40mm", "rocket") else 8 if a["shape"] in ("shell", "big") else 16
        components = {"flansmod:ammo_type": f"{NS}:{aid}"}
        if a.get("max_stack", 64) != 64:
            components["minecraft:max_stack_size"] = a["max_stack"]
        variant = AMMO_VARIANT.get(aid, {})
        shape = ["".join(variant.get(ch, ch) for ch in row) for row in AMMO_SHAPES[a["shape"]]]
        if "extra" in variant:
            shape = [shape[0] + variant["extra"]] + shape[1:]
        bench(f"ammo_{aid}", shape, raw_key(shape), {"id": "flansmod:ammo", "count": count, "components": components}, extend=True)

    for i, (aid, a) in enumerate(ATTACHMENTS.items()):
        write(DATA / "flansmod" / "attachments" / f"{aid}.json", {"name": a["name"], "slot": a["slot"], "icon": f"{NS}:{aid}", **a["stats"]})
        fake_gun = {"sight": (4, 0), "muzzle": (8, 6), "under": (12, -2)}
        model3d(aid, normalize(attachment_cubes(aid, fake_gun)))
        shape = ATTACHMENT_RECIPES[aid]
        bench(f"attachment_{aid}", shape, raw_key(shape, {"L": part_ingredient("lens"), "X": part_ingredient("circuit") if "scope" in aid or aid in ("red_dot", "holographic") else part_ingredient("polymer")}), {"id": "flansmod:attachment", "components": {
            "flansmod:attachment": f"{NS}:{aid}"}})

    for i, (gid, gr) in enumerate(GRENADES.items()):
        definition = dict(gr)
        if gr.get("throwable", True):
            definition["icon"] = f"{NS}:{gid}"
            model3d(gid, grenade_model(gid))
            shape = GRENADE_RECIPES[gid]
            bench(f"grenade_{gid}", shape, raw_key(shape), {"id": "flansmod:grenade", "count": 2, "components": {
                "flansmod:grenade": f"{NS}:{gid}"}})
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
        for key in ("toughness", "night_vision", "speed_modifier"):
            if key in c:
                definition[key] = c[key]
        write(DATA / "flansmod" / "clothing" / f"{cid}.json", definition)
        item_model(cid, "clothing")
        icon(ASSETS / "textures" / "item" / "clothing" / f"{cid}.png", clothing_icon(c))
        bench(f"clothing_{cid}", c["recipe"], raw_key(c["recipe"], {"L": part_ingredient("lens"), "X": part_ingredient("circuit")}),
              {"id": "flansmod:clothing", "components": {"flansmod:clothing": f"{NS}:{cid}"}}, extend=True)

    lang = {}
    for event in sounds():
        lang[f"subtitles.{NS}.{event}"] = "Gunshot" if "shoot" in event or "suppressed" in event else \
            "Gun reloads" if "reload" in event else "Gun clicks"
    write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(GUNS)} guns, {len(MAGAZINES)} magazines, {len(AMMO)} ammo types, {len(ATTACHMENTS)} attachments, "
          f"{len(GRENADES)} grenades/projectiles, {len(PARTS)} parts, {len(CLOTHING)} clothing, {len(_signatures)} bench recipes in {ROOT}")


if __name__ == "__main__":
    main()
