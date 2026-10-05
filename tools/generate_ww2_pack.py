#!/usr/bin/env python3
"""
Generates the built-in "WW2" content pack (src/main/resources/resourcepacks/ww2, namespace flansww2): the Second World
War by faction - Axis (Germany), Allies (USA, UK) and the Soviet Union - with each side's small arms, grenades, uniforms
and vehicles (jeeps, a Bren Carrier, Sherman, Cromwell, Panzer IV, Tiger I, T-34-85), their magazines, ammunition,
tank shells, crafting parts and Weapons Bench recipes, plus GeckoLib models and animations.

Models come from gunsmith (shared with the Basic pack); writing models, definitions, animations and recipes reuses the
Basic generator with this pack's namespace. Calibers both packs use (9×19mm, .45 ACP, 7.62×54mmR) are the Basic
pack's: the same rounds feed the guns of both packs. Crafting uses the Basic pack's tips (bullets) and gun parts, so
this pack needs the Basic pack; vehicles are assembled from the Vehicles pack's parts (wheels, tracks, engines, ...)
and their models come from vehiclesmith. Run:
    python3 tools/generate_ww2_pack.py
"""
import json
import shutil
from pathlib import Path

import generate_basic_pack as base
import generate_vehicle_pack as vp
import gunsmith as gs
import vehiclesmith as vs
from vehiclesmith import box

NS = "flansww2"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/ww2"
BASIC = "flansbasic"

# id: name, arch (model/animation template: pistol, smg, rifle, dmr, sniper, lmg, launcher), stats, sound
GUNS = {
    # Rifles
    "m1_garand": dict(name="M1 Garand", arch="dmr", dmg=10, rpm=200, mode="semi", reload=50, vel=18, spread=1.8, recoil=(2.6, 0.6),
                      zoom=1.4, slots=[], sound="battle"),
    "kar98k": dict(name="Karabiner 98k", arch="sniper", dmg=13, rpm=40, mode="semi", reload=65, vel=20, spread=2, ads_spread=0.1,
                   recoil=(3.5, 0.8), zoom=1.4, slots=[], sound="sniper", headshot=2.0),
    "kar98k_scoped": dict(name="Karabiner 98k (ZF39)", arch="sniper", dmg=13, rpm=40, mode="semi", reload=70, vel=22, spread=3,
                          ads_spread=0.04, recoil=(3.5, 0.8), zoom=4.0, slots=[], sound="sniper", headshot=2.2, move=0.5,
                          scope={"overlay": f"{BASIC}:textures/scope/sniper.png"}),
    "mosin_nagant": dict(name="Mosin-Nagant M91/30", arch="sniper", dmg=13, rpm=35, mode="semi", reload=70, vel=20, spread=2,
                         ads_spread=0.1, recoil=(3.6, 0.9), zoom=1.4, slots=[], sound="sniper", headshot=2.0),
    "m1903": dict(name="M1903 Springfield", arch="sniper", dmg=13, rpm=40, mode="semi", reload=65, vel=20, spread=2, ads_spread=0.1,
                  recoil=(3.5, 0.8), zoom=1.4, slots=[], sound="sniper", headshot=2.0),
    "lee_enfield": dict(name="Lee-Enfield No. 4", arch="sniper", dmg=12, rpm=55, mode="semi", reload=60, vel=19, spread=2,
                        ads_spread=0.12, recoil=(3.2, 0.8), zoom=1.4, slots=[], sound="sniper", headshot=2.0),
    "m1_carbine": dict(name="M1 Carbine", arch="rifle", dmg=6, rpm=300, mode="semi", reload=40, vel=14, spread=2, recoil=(1.4, 0.5),
                       zoom=1.3, slots=[], sound="rifle"),
    "g43": dict(name="Gewehr 43", arch="dmr", dmg=10, rpm=180, mode="semi", reload=55, vel=18, spread=1.9, recoil=(2.8, 0.7),
                zoom=1.4, slots=[], sound="battle"),
    # Submachine guns and assault rifles
    "mp40": dict(name="MP 40", arch="smg", dmg=4.5, rpm=500, mode="auto", reload=45, vel=10, spread=3, recoil=(0.9, 0.5), zoom=1.25,
                 slots=[], sound="smg"),
    "ppsh41": dict(name="PPSh-41", arch="smg", dmg=4, rpm=900, mode="auto", reload=55, vel=11, spread=3.6, recoil=(0.9, 0.7), zoom=1.2,
                   slots=[], sound="smg"),
    "sten": dict(name="Sten Mk II", arch="smg", dmg=4.5, rpm=500, mode="auto", reload=45, vel=10, spread=3.6, recoil=(1.0, 0.6),
                 zoom=1.2, slots=[], sound="smg"),
    "m3_grease_gun": dict(name="M3 Grease Gun", arch="smg", dmg=5, rpm=450, mode="auto", reload=50, vel=9, spread=3.2,
                          recoil=(1.1, 0.6), zoom=1.2, slots=[], sound="smg"),
    "stg44": dict(name="StG 44", arch="rifle", dmg=6.5, rpm=550, mode="auto", reload=55, vel=13, spread=2.8, recoil=(1.4, 0.7),
                  zoom=1.3, slots=[], sound="ak"),
    # Machine guns
    "bar": dict(name="M1918 BAR", arch="lmg", dmg=10, rpm=500, mode="auto", reload=65, vel=17, spread=3, recoil=(2.0, 0.9), zoom=1.3,
                slots=[], sound="battle", move=0.5),
    "mg42": dict(name="MG 42", arch="lmg", dmg=9, rpm=1200, mode="auto", reload=100, vel=17, spread=4, recoil=(1.4, 1.1), zoom=1.3,
                 slots=[], sound="heavy", move=0.45),
    "bren": dict(name="Bren Mk II", arch="lmg", dmg=9.5, rpm=500, mode="auto", reload=60, vel=17, spread=2.6, recoil=(1.6, 0.8),
                 zoom=1.3, slots=[], sound="battle", move=0.5),
    "dp28": dict(name="DP-28", arch="lmg", dmg=9.5, rpm=550, mode="auto", reload=80, vel=17, spread=3.2, recoil=(1.6, 0.9),
                 zoom=1.3, slots=[], sound="battle", move=0.5),
    # Pistols
    "luger_p08": dict(name="Luger P08", arch="pistol", dmg=5, rpm=350, mode="semi", reload=35, vel=10, spread=1.7, recoil=(2.0, 0.6),
                      zoom=1.2, slots=[], sound="pistol"),
    "walther_p38": dict(name="Walther P38", arch="pistol", dmg=5, rpm=350, mode="semi", reload=35, vel=10, spread=1.8,
                        recoil=(2.0, 0.6), zoom=1.2, slots=[], sound="pistol"),
    "tt33": dict(name="Tokarev TT-33", arch="pistol", dmg=5.5, rpm=350, mode="semi", reload=35, vel=11, spread=1.8, recoil=(2.4, 0.7),
                 zoom=1.2, slots=[], sound="pistol"),
    # Launchers
    "bazooka": dict(name="M1A1 Bazooka", arch="launcher", dmg=0, rpm=20, mode="semi", reload=80, vel=2.2, spread=0.6, recoil=(3, 1),
                    zoom=1.6, slots=[], sound="rocket", move=0.5),
}
FIRE_MODES = {gid: (["safe", "auto", "semi"] if g["mode"] == "auto" and gid not in ("mg42", "ppsh41") else
                    ["safe", "auto"] if g["mode"] == "auto" else ["safe", "semi"]) for gid, g in GUNS.items()}
FIRE_MODES["bazooka"] = ["semi"]

# New calibers of this pack (casing class decides icon size, gunpowder and ammo types; see the Basic generator).
CALIBERS = {
    "3006": dict(name=".30-06 Springfield", cls="full", band="brass"),
    "792x57": dict(name="7.92×57mm Mauser", cls="full", band="steel"),
    "303": dict(name=".303 British", cls="full", band="red"),
    "792x33": dict(name="7.92×33mm Kurz", cls="rifle", band="steel", types=["fmj", "ap"]),
    "762x25": dict(name="7.62×25mm Tokarev", cls="pistol", band="green"),
    "30carbine": dict(name=".30 Carbine", cls="pistol", band="brass", types=["fmj", "ap"]),
    "rocket236": dict(name="2.36in Rocket", cls="rocket", band="olive"),
}
# Casing recipes: c copper nugget, C copper ingot, N iron nugget, I iron ingot, U gunpowder (patterns are made unique
# against the other packs' recipes automatically).
CASINGS = {"3006": (["C", "c"], 10), "792x57": (["C", "N"], 10), "303": (["C", "cc"], 10), "792x33": (["c", "C"], 12),
           "762x25": (["cN"], 16), "30carbine": (["c", "N"], 16), "rocket236": (["I", "U", "U", "I"], 2)}

# Magazines; internal = loaded with loose rounds / clips (no item).
MAGAZINES = {
    "garand_clip": dict(name="En-bloc Clip (8)", caliber="3006", capacity=8, guns=["m1_garand"], internal=True),
    "kar98k_internal": dict(name="Internal Magazine (5)", caliber="792x57", capacity=5, guns=["kar98k", "kar98k_scoped"], internal=True),
    "mosin_internal": dict(name="Internal Magazine (5)", caliber="762x54", capacity=5, guns=["mosin_nagant"], internal=True),
    "m1903_internal": dict(name="Internal Magazine (5)", caliber="3006", capacity=5, guns=["m1903"], internal=True),
    "enfield_10": dict(name="Lee-Enfield Magazine (10)", caliber="303", capacity=10, guns=["lee_enfield"]),
    "carbine_15": dict(name="M1 Carbine Magazine (15)", caliber="30carbine", capacity=15, guns=["m1_carbine"]),
    "carbine_30": dict(name="M1 Carbine Magazine (30)", caliber="30carbine", capacity=30, guns=["m1_carbine"], reload=1.1),
    "g43_10": dict(name="G43 Magazine (10)", caliber="792x57", capacity=10, guns=["g43"]),
    "mp40_32": dict(name="MP 40 Magazine (32)", caliber="9mm", capacity=32, guns=["mp40"]),
    "ppsh_drum_71": dict(name="PPSh Drum (71)", caliber="762x25", capacity=71, guns=["ppsh41"], reload=1.5),
    "ppsh_35": dict(name="PPSh Box Magazine (35)", caliber="762x25", capacity=35, guns=["ppsh41"]),
    "sten_32": dict(name="Sten Magazine (32)", caliber="9mm", capacity=32, guns=["sten"]),
    "grease_30": dict(name="M3 Magazine (30)", caliber="45acp", capacity=30, guns=["m3_grease_gun"]),
    "stg44_30": dict(name="StG 44 Magazine (30)", caliber="792x33", capacity=30, guns=["stg44"]),
    "bar_20": dict(name="BAR Magazine (20)", caliber="3006", capacity=20, guns=["bar"]),
    "mg42_drum_50": dict(name="MG 42 Belt Drum (50)", caliber="792x57", capacity=50, guns=["mg42"], reload=1.2),
    "bren_30": dict(name="Bren Magazine (30)", caliber="303", capacity=30, guns=["bren"]),
    "dp28_pan_47": dict(name="DP-28 Pan Magazine (47)", caliber="762x54", capacity=47, guns=["dp28"]),
    "luger_8": dict(name="Luger Magazine (8)", caliber="9mm", capacity=8, guns=["luger_p08"]),
    "p38_8": dict(name="P38 Magazine (8)", caliber="9mm", capacity=8, guns=["walther_p38"]),
    "tt33_8": dict(name="TT-33 Magazine (8)", caliber="762x25", capacity=8, guns=["tt33"]),
    "bazooka_tube": dict(name="Launch Tube (1)", caliber="rocket236", capacity=1, guns=["bazooka"], internal=True),
}
MAG_SHAPES = {
    "enfield_10": ("rifle_box", 10, "blued"), "carbine_15": ("stick", 15, "parkerized"), "carbine_30": ("smg_curved", 30, "parkerized"),
    "g43_10": ("rifle_box", 10, "blued"), "mp40_32": ("stick", 32, "blued"), "ppsh_drum_71": ("drum", 71, "blued"),
    "ppsh_35": ("ak", 35, "blued"), "sten_32": ("side", 32, "parkerized"), "grease_30": ("stick", 30, "parkerized"),
    "stg44_30": ("ak", 30, "blued"), "bar_20": ("rifle_box", 20, "parkerized"), "mg42_drum_50": ("drum", 50, "blued"),
    "bren_30": ("ak", 30, "parkerized"), "dp28_pan_47": ("pan", 47, "blued"), "luger_8": ("pistol", 8, "blued"),
    "p38_8": ("pistol", 8, "blued"), "tt33_8": ("pistol", 8, "blued"),
}
MAGAZINE_RECIPES = {  # (pattern, uses spring part F)
    "pistol": ["I", "F"], "stick": ["I", "I", "F"], "smg_curved": ["I ", "IN", "F "], "ak": ["I ", "IN", "IF"], "drum": ["NIN", "IFI", "NIN"],
    "rifle_box": ["II", "IF"], "side": ["IIF"], "pan": ["NNN", "IFI", "NNN"],
}

# This pack's own gun parts (category gun); B=iron block, I=iron ingot, N=nugget, W=planks, R=redstone, L=logs.
PARTS = {
    "walnut_stock": dict(name="Walnut Rifle Stock", pattern=["WWWWW", "  WWW"], icon="wood_stock"),
    "bolt_action_receiver": dict(name="Bolt-Action Receiver", pattern=["IIIR", "N   "], icon="receiver"),
    "semi_auto_receiver": dict(name="Semi-Auto Receiver", pattern=["IIIR", "NN  "], icon="receiver"),
    "stamped_receiver": dict(name="Stamped Receiver", pattern=["NNNR", "N   "], icon="receiver"),
    "mg_receiver": dict(name="Machine Gun Receiver", pattern=["IIIIR", "IB   "], icon="receiver"),
    "barrel_jacket": dict(name="Perforated Barrel Jacket", pattern=["INININ"], icon="heavy_barrel"),
}
# Assembly: letters → (namespace, part). S stock, R receiver, B barrel, G grip, T trigger group, C bolt carrier, F spring,
# A gas system, J jacket, L lens, K folding stock, P pistol frame, U launcher tube.
P = {"S": (NS, "walnut_stock"), "R": (NS, "bolt_action_receiver"), "Q": (NS, "semi_auto_receiver"), "M": (NS, "stamped_receiver"),
     "H": (NS, "mg_receiver"), "J": (NS, "barrel_jacket"), "B": (BASIC, "long_barrel"), "b": (BASIC, "barrel"), "s": (BASIC, "short_barrel"),
     "h": (BASIC, "heavy_barrel"), "G": (BASIC, "wood_grip"), "T": (BASIC, "trigger_group"), "C": (BASIC, "bolt_carrier"),
     "F": (BASIC, "spring"), "A": (BASIC, "gas_system"), "L": (BASIC, "lens"), "K": (BASIC, "folding_stock"), "P": (BASIC, "pistol_frame"),
     "U": (BASIC, "launcher_tube"), "W": (BASIC, "wood_stock"), "X": (BASIC, "polymer_grip")}
ASSEMBLY = {
    "m1_garand": ["SQAB", " T  "], "kar98k": ["SRCB", " T  "], "kar98k_scoped": [" L  ", "SRCB", " T  "], "mosin_nagant": ["SRCBb", " T   "],
    "m1903": ["SRCB", " TF "], "lee_enfield": ["SRCB", " TC "], "m1_carbine": ["SQAb", " T  "], "g43": ["SQAB", " GT "],
    "mp40": ["KMs", " GT"], "ppsh41": ["SMJ", " T "], "sten": ["KMs", "  T"], "m3_grease_gun": ["KMs", "XT "], "stg44": ["SMAb", " GT "],
    "bar": ["SHAB", " T  "], "mg42": ["SHJh", " GT "], "bren": ["SHAh", " GT "], "dp28": ["SHJB", " T  "],
    "luger_p08": ["Ps", "GT"], "walther_p38": ["Pb", "GT"], "tt33": ["Ps", "XT"], "bazooka": ["UUU", "GT "],
}

# Grenades and the bazooka rocket.
GRENADES = {
    "stielhandgranate": dict(name="Stielhandgranate 24", fuse_ticks=90, bounciness=0.25, throw_velocity=1.5,
                             explosion={"power": 2.6, "fire": False, "break_blocks": False}),
    "mk2": dict(name="Mk 2 Grenade", fuse_ticks=80, bounciness=0.35, explosion={"power": 2.4, "fire": False, "break_blocks": False}),
    "bazooka_rocket": dict(name="2.36in Rocket", throwable=False, contact=True, gravity=0.006, fuse_ticks=120, trail=True,
                           explosion={"power": 3.0, "fire": False, "break_blocks": True}),
}
GRENADE_RECIPES = {"stielhandgranate": ["NUN", " W ", " W "], "mk2": ["NIN", "IUI", "IIN"], "mills_bomb": ["NIN", "IUI", "NIN"], "f1": ["NIN", "IUI", "N N"]}
GRENADES["mills_bomb"] = dict(name="No. 36M Mills Bomb", fuse_ticks=80, bounciness=0.3, explosion={"power": 2.5, "fire": False, "break_blocks": False})
GRENADES["f1"] = dict(name="F-1 Grenade", fuse_ticks=80, bounciness=0.3, explosion={"power": 2.6, "fire": False, "break_blocks": False})

# ------------------------------------------------------------------------------------------- factions
# Each faction: creative tab, tooltip colour. Everything below names its side.
FACTIONS = {
    "axis": dict(name="Axis (Germany)", color="#A8AEA0", icon="tiger1", order=0),
    "usa": dict(name="Allies (USA)", color="#9DB05A", icon="sherman", order=1),
    "uk": dict(name="Allies (UK)", color="#C8B07A", icon="cromwell", order=2),
    "ussr": dict(name="Soviet Union", color="#E05050", icon="t34_85", order=3),
}
FACTION_ITEMS = {
    "axis": ["kar98k", "kar98k_scoped", "g43", "mp40", "stg44", "mg42", "luger_p08", "walther_p38", "stielhandgranate",
             "kubelwagen", "panzer4", "tiger1"],
    "usa": ["m1_garand", "m1903", "m1_carbine", "m3_grease_gun", "bar", "bazooka", "mk2", "willys", "sherman"],
    "uk": ["lee_enfield", "sten", "bren", "mills_bomb", "universal_carrier", "cromwell"],
    "ussr": ["mosin_nagant", "ppsh41", "dp28", "tt33", "f1", "gaz67", "t34_85"],
}
FACTION_OF = {item: f"{NS}:{faction}" for faction, items in FACTION_ITEMS.items() for item in items}

# ------------------------------------------------------------------------------------------- uniforms
# Per faction: helmet (shape), tunic, trousers, boots in the vanilla humanoid armour layout. Colours: helmet, tunic,
# collar, belt, buckle/buttons, trousers, boots, webbing/leggings (None = none).
UNIFORMS = {
    "axis": dict(helmet=(78, 84, 80), shape="stahlhelm", tunic=(96, 104, 90), collar=(48, 62, 46), belt=(24, 24, 24), metal=(170, 170, 170),
                 trousers=(104, 106, 98), boots=(22, 22, 22), boot_height=10, webbing=None),
    "usa": dict(helmet=(82, 88, 56), shape="m1", tunic=(152, 138, 98), collar=(140, 126, 88), belt=(168, 154, 112), metal=(150, 140, 90),
                trousers=(112, 96, 64), boots=(92, 60, 34), boot_height=4, webbing=(172, 158, 116), leggings=(176, 162, 120)),
    "uk": dict(helmet=(92, 94, 64), shape="brodie", tunic=(122, 106, 72), collar=(110, 95, 64), belt=(184, 174, 134), metal=(180, 160, 90),
               trousers=(122, 106, 72), boots=(24, 24, 24), boot_height=3, webbing=(184, 174, 134), leggings=(184, 174, 134)),
    "ussr": dict(helmet=(70, 88, 52), shape="ssh40", tunic=(152, 150, 102), collar=(140, 138, 94), belt=(102, 62, 30), metal=(200, 170, 60),
                 trousers=(112, 112, 76), boots=(26, 26, 26), boot_height=9, webbing=None, star=True),
}
# id: name, faction, slot, armour (helmets protect more), recipe
CLOTHING = {
    "stahlhelm": ("Stahlhelm M35", "axis", "head", 2.5), "feldbluse": ("Feldbluse M36", "axis", "chest", 4),
    "feldhose": ("Feldhose", "axis", "legs", 3), "marschstiefel": ("Marschstiefel", "axis", "feet", 1.5),
    "m1_helmet": ("M1 Helmet", "usa", "head", 2.5), "m1941_jacket": ("M1941 Field Jacket", "usa", "chest", 4),
    "wool_trousers": ("Wool Field Trousers", "usa", "legs", 3), "service_boots": ("Service Shoes & Leggings", "usa", "feet", 1.5),
    "brodie_helmet": ("Brodie Helmet Mk II", "uk", "head", 2.5), "battledress_blouse": ("Battledress Blouse", "uk", "chest", 4),
    "battledress_trousers": ("Battledress Trousers", "uk", "legs", 3), "ammunition_boots": ("Ammunition Boots & Anklets", "uk", "feet", 1.5),
    "ssh40_helmet": ("SSh-40 Helmet", "ussr", "head", 2.5), "gymnastyorka": ("Gymnastyorka M43", "ussr", "chest", 4),
    "sharovary": ("Sharovary Trousers", "ussr", "legs", 3), "kirza_boots": ("Kirza Boots", "ussr", "feet", 1.5),
}
FACTION_DYE = {"axis": "minecraft:gray_dye", "usa": "minecraft:green_dye", "uk": "minecraft:brown_dye", "ussr": "minecraft:red_dye"}
CLOTHING_RECIPES = {"head": ["IDI", "I I"], "chest": ["MDM", "MMM", "MMM"], "legs": ["MDM", "M M", "M M"], "feet": ["JDJ", "J J"]}


def uniform_textures(faction, folder: Path):
    """Vanilla humanoid armour layout (64x32): 'humanoid' (helmet, tunic, boots) and 'humanoid_leggings' (trousers)."""
    import random
    u = UNIFORMS[faction]
    rng = random.Random(faction)

    def px(img, x, y, c):
        n = rng.randint(-6, 6)
        img.putpixel((x, y), tuple(max(0, min(255, v + n)) for v in c) + (255,))

    def fill(img, x0, y0, x1, y1, c):
        for x in range(x0, x1):
            for y in range(y0, y1):
                px(img, x, y, c)

    from PIL import Image
    main = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    # Helmet: crown on top, down the sides/back/front by shape (the face stays free).
    side, back, front = {"stahlhelm": (5, 6, 2), "m1": (4, 4, 2), "ssh40": (4, 4, 2), "brodie": (1, 1, 1)}[u["shape"]]
    fill(main, 8, 0, 16, 8, u["helmet"])
    fill(main, 0, 8, 8, 8 + side, u["helmet"]); fill(main, 16, 8, 24, 8 + side, u["helmet"])
    fill(main, 24, 8, 32, 8 + back, u["helmet"]); fill(main, 8, 8, 16, 8 + front, u["helmet"])
    if u["shape"] == "brodie":  # wide brim: a darker rim row all around
        for x in range(0, 32):
            px(main, x, 8, tuple(int(v * 0.75) for v in u["helmet"]))
    if u.get("star"):
        for x, y in ((11, 8), (12, 8), (11, 9), (12, 9)):
            main.putpixel((x, y), (200, 30, 30, 255))
    # Tunic: body (16,16) 8x12x4 and arms (40,16) 4x12x4.
    fill(main, 16, 16, 40, 32, u["tunic"]); fill(main, 40, 16, 56, 32, u["tunic"])
    fill(main, 20, 20, 28, 21, u["collar"]); fill(main, 20, 16, 28, 17, u["collar"])  # collar
    fill(main, 16, 28, 40, 30, u["belt"])  # belt all around
    fill(main, 23, 28, 25, 30, u["metal"])  # buckle
    for y in (21, 23, 25, 27):
        main.putpixel((24, y), u["metal"] + (255,))  # buttons
    for x0 in (21, 25):  # breast pockets
        fill(main, x0, 22, x0 + 2, 24, tuple(int(v * 0.85) for v in u["tunic"]))
    fill(main, 40, 30, 56, 32, tuple(int(v * 0.8) for v in u["tunic"]))  # cuffs
    if u["webbing"]:  # braces over the shoulders
        for x in (21, 26):
            fill(main, x, 20, x + 1, 28, u["webbing"])
            fill(main, 32 + (x - 20), 20, 32 + (x - 19), 28, u["webbing"])
    # Boots: lower legs (0,16) 4x12x4 + soles.
    h = u["boot_height"]
    fill(main, 0, 32 - h, 16, 32, u["boots"]); fill(main, 8, 16, 12, 20, u["boots"])
    if u.get("leggings"):
        fill(main, 0, 32 - h - 4, 16, 32 - h, u["leggings"])
    legs = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    fill(legs, 0, 16, 16, 32, u["trousers"])
    fill(legs, 16, 26, 40, 32, u["trousers"])  # waist
    fill(legs, 20, 16, 28, 20, u["trousers"])
    (folder / "humanoid").mkdir(parents=True, exist_ok=True)
    (folder / "humanoid_leggings").mkdir(parents=True, exist_ok=True)
    main.save(folder / "humanoid" / f"{faction}.png")
    legs.save(folder / "humanoid_leggings" / f"{faction}.png")


def uniform_icon(faction, slot):
    u = UNIFORMS[faction]
    p = {}
    rect, shade = base.rect, base.shade
    if slot == "head":
        c = u["helmet"]
        if u["shape"] == "brodie":
            rect(p, 4, 6, 11, 8, c); rect(p, 1, 9, 14, 10, shade(c, 0.75)); rect(p, 3, 8, 12, 8, c)
        elif u["shape"] == "stahlhelm":
            rect(p, 4, 3, 11, 9, c); rect(p, 3, 6, 4, 11, c); rect(p, 11, 6, 12, 11, c); rect(p, 2, 10, 3, 11, shade(c, 0.8)); rect(p, 12, 10, 13, 11, shade(c, 0.8))
        else:
            rect(p, 4, 4, 11, 9, c); rect(p, 3, 7, 12, 10, c); rect(p, 2, 10, 13, 10, shade(c, 0.75))
        if u.get("star"):
            p[(7, 6)] = (200, 30, 30); p[(8, 6)] = (200, 30, 30)
    elif slot == "chest":
        c = u["tunic"]
        rect(p, 2, 2, 13, 5, c); rect(p, 4, 5, 11, 14, c); rect(p, 1, 3, 3, 10, shade(c, 0.85)); rect(p, 12, 3, 14, 10, shade(c, 0.85))
        rect(p, 6, 2, 9, 3, u["collar"]); rect(p, 4, 11, 11, 11, u["belt"]); p[(7, 11)] = u["metal"]
        for y in (5, 7, 9):
            p[(7, y)] = u["metal"]
    elif slot == "legs":
        c = u["trousers"]
        rect(p, 3, 2, 12, 5, c); rect(p, 3, 5, 6, 14, c); rect(p, 9, 5, 12, 14, c); rect(p, 3, 2, 12, 2, shade(c, 0.7))
    else:
        c = u["boots"]
        h = 7 if u["boot_height"] >= 9 else 4
        rect(p, 3, 13 - h, 6, 13, c); rect(p, 9, 13 - h, 12, 13, c); rect(p, 3, 13, 7, 14, c); rect(p, 9, 13, 13, 14, c)
        if u.get("leggings"):
            rect(p, 3, 5, 6, 8, u["leggings"]); rect(p, 9, 5, 12, 8, u["leggings"])
    return p


# ------------------------------------------------------------------------------------------- vehicles
VP = "flansvehicles"
JEEP_WHEELS = [("fl", -0.75, 1.0), ("fr", 0.75, 1.0), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)]
KUBEL_WHEELS = [("fl", -0.72, 1.1), ("fr", 0.72, 1.1), ("rl", -0.72, -1.2), ("rr", 0.72, -1.2)]
GAZ_WHEELS = [("fl", -0.7, 1.15), ("fr", 0.7, 1.15), ("rl", -0.7, -1.0), ("rr", 0.7, -1.0)]
seat, gun_seat, car, tank = vp.seat, vp.gun_seat, vp.car, vp.tank


def light_car(name, health, speed, **kw):
    return car(name, health=health, armor=0.05, max_speed=speed, max_reverse_speed=0.28, acceleration=0.026, braking=0.07, drag=0.015,
               turn_speed=5.0, water_speed=0.2, collision_damage=15, death_explosion=2.5, camera_distance=6,
               fuel={"capacity": 20000, "consumption": 1}, upgrade_slots=["engine", "tyres", "tank"], **kw)


def ww2_tank(name, health, armor, speed, turn, parts, **kw):
    return tank(name, **{**dict(health=health, armor=armor, max_speed=speed, max_reverse_speed=0.15, acceleration=0.011, braking=0.05, drag=0.03,
                                turn_speed=turn, water_speed=0.2, collision_damage=36, death_explosion=4.5, camera_distance=11,
                                fuel={"capacity": 48000, "consumption": 2}, upgrade_slots=["engine", "tank"], parts=parts), **kw})


VEHICLES = {
    "willys": dict(model=vs.willys, recipe=["SGSE", "CCCC", "W  W"], definition=light_car(
        "Willys MB", 55, 0.9, parts=vp.wheeled_parts([-0.85, 0.45, -1.3, 0.85, 1.12, 0.55], [-0.8, 0.45, 0.55, 0.8, 1.12, 1.55], JEEP_WHEELS, 0.36, 0.26, 20, 0.05,
                                                      fuel_tank=[-0.85, 0.45, -1.6, 0.85, 1.0, -1.3])),
        seats=lambda m: [seat(-0.4, 0.8, -0.05), seat(0.4, 0.8, -0.05), gun_seat(m, "mg", [0.0, 0.8, -1.1], f"{NS}:m1919_mounted", -15, 45, "turret", "mg")]),
    "kubelwagen": dict(model=vs.kubelwagen, recipe=["S SE", "CCCC", "WG W"], definition=light_car(
        "Kübelwagen Typ 82", 50, 0.85, parts=vp.wheeled_parts([-0.8, 0.4, -1.7, 0.8, 1.0, 0.5], [-0.8, 0.4, -2.0, 0.8, 1.0, -1.7], KUBEL_WHEELS, 0.38, 0.24, 18, 0.05,
                                                              fuel_tank=[-0.8, 0.4, 0.5, 0.8, 1.0, 1.6])),
        seats=lambda m: [seat(-0.38, 0.78, -0.1), seat(0.38, 0.78, -0.1), gun_seat(m, "mg", [0.0, 0.78, -1.15], f"{NS}:mg34_mounted", -15, 45, "turret", "mg")]),
    "gaz67": dict(model=vs.gaz67, recipe=["SS E", "CCCC", "W GW"], definition=light_car(
        "GAZ-67B", 55, 0.8, parts=vp.wheeled_parts([-0.72, 0.45, -1.2, 0.72, 1.12, 0.5], [-0.7, 0.45, 0.5, 0.7, 1.15, 1.7], GAZ_WHEELS, 0.37, 0.24, 20, 0.05,
                                                   fuel_tank=[-0.72, 0.45, -1.5, 0.72, 1.0, -1.2])),
        seats=lambda m: [seat(-0.4, 0.8, -0.05), seat(0.4, 0.8, -0.05), gun_seat(m, "mg", [0.0, 0.8, -1.1], f"{NS}:dt_mounted", -15, 45, "turret", "mg")]),
    "universal_carrier": dict(model=vs.universal_carrier, recipe=["SSGE", "AAAA", "KKKK"], definition=ww2_tank(
        "Universal Carrier", 120, 0.4, 0.75, 4.0,
        vp.tracked_parts(0.97, 1.4, -1.6, 1.55, 0.35, 1.1, 0.7, -0.6, None, 0.4, 120, 60),
        step_height=1.0, death_explosion=3.0, camera_distance=7),
        seats=lambda m: [seat(0.45, 0.75, 0.25), gun_seat(m, "mg", [-0.45, 0.75, 0.25], f"{NS}:bren_mounted", -10, 40, "turret", "mg"),
                         seat(-0.6, 0.75, -0.95), seat(0.6, 0.75, -0.95)]),
    "sherman": dict(model=vs.sherman, recipe=["  TBB", " AAAE", "AHHHA", "KKKKK"], definition=ww2_tank(
        "M4A3 Sherman", 320, 0.8, 0.62, 2.6,
        vp.tracked_parts(1.2, 1.7, -2.7, 2.6, 0.55, 1.95, 1.0, -1.3, [-1.0, 1.95, -1.05, 1.0, 2.75, 1.25], 0.8, 320, 120)),
        # The driver also commands the gun (like the original Flan's tanks); the commander mans the .50 cal on the turret.
        seats=lambda m: [gun_seat(m, "main", [-0.5, 0.95, 1.2], f"{NS}:m3_75mm", -10, 25, "turret", "cannon"), seat(0.5, 0.95, 1.2),
                         gun_seat(m, "cupola", None, f"{NS}:m2hb_mounted", -10, 60, "cupola", "cupola_mg")]),
    "cromwell": dict(model=vs.cromwell, recipe=[" TBB ", "AAAAE", "AHHHA", "KKKKK"], definition=ww2_tank(
        "Cromwell Mk IV", 290, 0.76, 0.72, 2.8,
        vp.tracked_parts(1.17, 1.65, -2.7, 2.6, 0.5, 1.55, 1.0, -1.2, [-0.95, 1.55, -0.95, 0.95, 2.4, 1.25], 0.76, 290, 110)),
        seats=lambda m: [gun_seat(m, "main", [-0.55, 0.7, 1.75], f"{NS}:qf75", -12, 20, "turret", "cannon"), seat(0.45, 1.6, -0.4)]),
    "panzer4": dict(model=vs.panzer4, recipe=["  TBBB", "AAAAE ", "AHHHA ", "KKKKK "], definition=ww2_tank(
        "Panzer IV Ausf. H", 300, 0.78, 0.6, 2.5,
        vp.tracked_parts(1.22, 1.82, -2.75, 2.65, 0.5, 1.75, 0.85, -1.4, [-1.25, 1.75, -1.2, 1.25, 2.5, 0.95], 0.78, 300, 110)),
        seats=lambda m: [gun_seat(m, "main", [-0.6, 0.95, 1.8], f"{NS}:kwk40", -8, 20, "turret", "cannon"), seat(0.6, 0.95, 1.8), seat(0.0, 1.75, -0.95)]),
    "tiger1": dict(model=vs.tiger1, recipe=[" TBBBB", "AAAAAE", "AHHHHA", "KKKKKK"], definition=ww2_tank(
        "Tiger I", 480, 0.92, 0.5, 2.0,
        vp.tracked_parts(1.45, 2.05, -3.1, 2.9, 0.5, 1.9, 0.95, -1.6, [-1.1, 1.9, -1.35, 1.1, 2.75, 1.25], 0.92, 480, 160),
        death_explosion=5.5),
        seats=lambda m: [gun_seat(m, "main", [-0.65, 1.05, 2.0], f"{NS}:kwk36", -8, 15, "turret", "cannon"), seat(0.65, 1.05, 2.0), seat(-0.5, 1.95, -0.8)]),
    "t34_85": dict(model=vs.t34_85, recipe=["  TBBB", "AAAAAE", "AHHHA ", "KKKKK "], engine="diesel_engine", definition=ww2_tank(
        "T-34-85", 320, 0.82, 0.7, 2.8,
        vp.tracked_parts(1.2, 1.7, -3.0, 2.7, 0.45, 1.55, 1.0, -1.5, [-0.95, 1.55, -1.15, 0.95, 2.45, 1.1], 0.82, 320, 120)),
        seats=lambda m: [gun_seat(m, "main", [-0.55, 0.75, 1.15], f"{NS}:zis_s53", -5, 25, "turret", "cannon"), seat(0.35, 1.5, -0.6)]),
}
MOUNTED_GUNS = {
    "m1919_mounted": vp.mg("M1919A4 (mounted)", 9, 500, 17),
    "mg34_mounted": vp.mg("MG 34 (mounted)", 9, 900, 17),
    "dt_mounted": vp.mg("DT (mounted)", 9, 600, 17),
    "bren_mounted": {**vp.mg("Bren (carrier mount)", 9.5, 500, 17, spread=0.9), "magazines": [f"{NS}:bren_30"]},
    "m2hb_mounted": {**vp.mg("M2HB .50 cal (mounted)", 11, 500, 16), "magazines": [f"{VP}:m2_box_100"]},
    "m3_75mm": vp.cannon("75mm M3 Gun", 38, 90, 5, 2.5, vp.scope("telescope")),
    "qf75": vp.cannon("Ordnance QF 75mm", 38, 90, 5, 2.5, vp.scope("telescope")),
    "kwk40": vp.cannon("7.5 cm KwK 40 L/48", 40, 95, 6, 2.5, vp.scope("tzf")),
    "kwk36": vp.cannon("8.8 cm KwK 36 L/56", 46, 120, 6.5, 2.5, vp.scope("tzf"), recoil=3.5),
    "zis_s53": vp.cannon("85mm ZiS-S-53", 44, 110, 5.5, 2.5, vp.scope("tank_soviet")),
}
MOUNTED_MAGAZINES = {
    "m1919_belt_150": dict(name=".30-06 Belt Box (150)", caliber="3006", capacity=150, guns=["m1919_mounted"], recipe=["III", "NKN"]),
    "mg34_belt_150": dict(name="MG 34 Belt Box (150)", caliber="792x57", capacity=150, guns=["mg34_mounted"], recipe=["III", "KNK"]),
    "dt_pan_63": dict(name="DT Pan Magazine (63)", caliber="762x54", capacity=63, guns=["dt_mounted"], recipe=["NIN", "IKI", "NIN"]),
    "75mm_breech": dict(name="Breech (1)", caliber="75mm", capacity=1, guns=["m3_75mm", "qf75"], internal=True),
    "75mm_kwk_breech": dict(name="Breech (1)", caliber="75mm_kwk", capacity=1, guns=["kwk40"], internal=True),
    "88mm_breech": dict(name="Breech (1)", caliber="88mm", capacity=1, guns=["kwk36"], internal=True),
    "85mm_breech": dict(name="Breech (1)", caliber="85mm", capacity=1, guns=["zis_s53"], internal=True),
}
MAG_ICONS = {"m1919_belt_150": "olive_drab", "mg34_belt_150": "panzer_grey", "dt_pan_63": "metal"}
SHELLS = {
    "75mm": dict(name="75mm Tank Shell (US/UK)", label="75mm", length=1.05, radius=0.15, casing=["C C", "CCC"], types=["apcbc", "he"]),
    "75mm_kwk": dict(name="7.5 cm KwK 40 Shell", label="7.5cm KwK", length=1.2, radius=0.15, casing=["C C", "C C", "CC "], types=["apcbc", "he"]),
    "88mm": dict(name="8.8 cm KwK 36 Shell", label="8.8cm KwK", length=1.35, radius=0.17, casing=["C C", "C C", " CC"], types=["apcbc", "he"]),
    "85mm": dict(name="85mm Tank Shell", label="85mm", length=1.25, radius=0.16, casing=["C C", " C ", "CCC"], types=["apcbc", "he"]),
}
SIGHTS = ["mg_ring", "telescope", "tzf", "tank_soviet"]


def grenade_model(gid):
    el = base.el
    if gid == "stielhandgranate":
        return [el((7, 0, 7), (9, 10, 9), "wood"), el((5.5, 10, 5.5), (10.5, 15, 10.5), "olive"), el((6.5, 15, 6.5), (9.5, 15.5, 9.5), "steel")]
    if gid in ("mills_bomb", "f1"):  # segmented egg, filler plug, lever and ring
        body = "olive" if gid == "mills_bomb" else "green"
        return [el((5, 2, 5), (11, 11, 11), body), el((4.5, 4, 4.5), (11.5, 9, 11.5), body), el((6, 1, 6), (10, 2, 10), "steel"),
                el((6.5, 11, 6.5), (9.5, 13, 9.5), "steel"), el((10.5, 6, 7.5), (11.5, 12.5, 8.5), "steel"), el((5.5, 12.5, 7.5), (7, 14, 8.5), "steel")]
    return [el((5, 2, 5), (11, 10, 11), "olive"), el((4.75, 4, 4.75), (11.25, 4.6, 11.25), "olive"), el((4.75, 7, 4.75), (11.25, 7.6, 11.25), "olive"),
            el((6.5, 10, 6.5), (9.5, 12, 9.5), "steel"), el((9.5, 7, 7.5), (10.5, 12, 8.5), "steel")]


def ingredient(ns, pid):
    return {"fabric:type": "fabric:components", "base": "flansmod:part", "components": {"flansmod:part": f"{ns}:{pid}"}}


def seed_signatures():
    """Recipes of the other packs, so this pack's recipes are made unique against them."""
    for f in (ROOT.parent).glob("*/data/*/recipe/*.json"):
        if ROOT in f.parents:
            continue
        d = json.loads(f.read_text())
        if d.get("type") != "flansmod:weapon_assembly":
            continue
        used = sorted({ch for row in d["pattern"] for ch in row if ch != " "})
        base._signatures[(tuple(d["pattern"]), tuple((k, json.dumps(d["key"][k], sort_keys=True)) for k in used))] = f.stem


def main():
    # The Basic generator's writers with this pack's namespace and tables.
    base.NS, base.ROOT, base.DATA, base.ASSETS = NS, ROOT, ROOT / "data" / NS, ROOT / "assets" / NS
    base.MAGAZINES, base.MAG_SHAPES, base.FIRE_MODES, base.ATTACHMENTS = MAGAZINES, MAG_SHAPES, FIRE_MODES, {}
    base.GUN_BUILDERS = gs.WW2_GUNS
    DATA, ASSETS = base.DATA, base.ASSETS
    if ROOT.exists():
        shutil.rmtree(ROOT)
    seed_signatures()
    base.write(ROOT / "pack.mcmeta", {
        "pack": {"description": "Flan's Mod: Recoded - Second World War: Axis, Allies and Soviets", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Flan's WW2", "icon": f"{NS}:m1_garand"}})
    gs.gun_texture(ASSETS / "textures" / "gun" / "basic.png")
    base.material_atlas(ASSETS / "textures" / "item" / "materials.png")

    for pid, part in PARTS.items():
        base.write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": part["name"], "icon": f"{NS}:{pid}"})
        base.model3d(pid, base.part_model(part["icon"]))
        base.bench(f"part_{pid}", part["pattern"], base.raw_key(part["pattern"]),
                   {"id": "flansmod:part", "components": {"flansmod:part": f"{NS}:{pid}"}}, extend=True)

    for gid, g in GUNS.items():
        geo = base.gun_geometry(gid, g)
        base.write(DATA / "flansmod" / "guns" / f"{gid}.json", {**base.gun_definition(gid, g, geo), "faction": FACTION_OF[gid]})
        base.write(ASSETS / "geckolib" / "models" / "gun" / f"{gid}.geo.json", base.gun_model(gid, g, geo))
        base.write(ASSETS / "geckolib" / "animations" / "gun" / f"{gid}.animation.json", base.gun_animations(g, geo))
        layout = ASSEMBLY[gid]
        key = {ch: ingredient(*P[ch]) for row in layout for ch in row if ch != " "}
        base.bench(gid, layout, key, {"id": "flansmod:gun", "components": {"flansmod:gun": f"{NS}:{gid}"}}, extend=True)

    for mid, m in MAGAZINES.items():
        definition = {"name": m["name"], "caliber": m["caliber"], "capacity": m["capacity"]}
        if m.get("reload"):
            definition["reload_multiplier"] = m["reload"]
        if m.get("internal"):
            base.write(DATA / "flansmod" / "magazines" / f"{mid}.json", {**definition, "internal": True})
            continue
        base.write(DATA / "flansmod" / "magazines" / f"{mid}.json", {**definition, "icon": f"{NS}:{mid}"})
        base.model3d(mid, base.normalize(base.magazine_cubes(mid)), base.MAGAZINE_DISPLAY)
        # Body by magazine kind, plus one casing of its caliber (tells which rounds it takes).
        shape = list(MAGAZINE_RECIPES[MAG_SHAPES[mid][0]])
        shape[0] = shape[0] + "K"
        casing = ingredient(NS if m["caliber"] in CALIBERS else BASIC, f"casing_{m['caliber']}")
        base.bench(f"magazine_{mid}", shape, base.raw_key(shape, {"F": ingredient(BASIC, "spring"), "K": casing}),
                   {"id": "flansmod:magazine", "components": {"flansmod:magazine": {"magazine": f"{NS}:{mid}"}}}, extend=True)

    # Rounds of the new calibers: this pack's casing + gunpowder + the Basic pack's tips / a rocket warhead.
    ammo = base.build_ammo(CALIBERS)
    for cal, (pattern, count) in CASINGS.items():
        pid = f"casing_{cal}"
        name = "Rocket Motor (2.36in)" if cal == "rocket236" else f"{CALIBERS[cal]['name']} Casing"
        base.write(DATA / "flansmod" / "parts" / f"{pid}.json", {"name": name, "category": "ammo", "icon": f"{NS}:{pid}"})
        base.model3d(pid, base.casing_model(CALIBERS[cal]["cls"]), base.SMALL_ITEM_DISPLAY)
        base.bench(f"part_{pid}", pattern, base.raw_key(pattern), {"id": "flansmod:part", "count": count,
                   "components": {"flansmod:part": f"{NS}:{pid}"}}, extend=True)
    base.write(DATA / "flansmod" / "parts" / "warhead_bazooka.json", {"name": "M6A1 Warhead", "category": "ammo", "icon": f"{NS}:warhead_bazooka"})
    base.model3d("warhead_bazooka", base.tip_model("warhead_pg7"), base.SMALL_ITEM_DISPLAY)
    base.bench("part_warhead_bazooka", ["T", "I", "C"], base.raw_key(["T", "I", "C"]), {"id": "flansmod:part", "count": 2,
               "components": {"flansmod:part": f"{NS}:warhead_bazooka"}}, extend=True)
    for aid, a in ammo.items():
        fields = {k: v for k, v in a.items() if k not in ("kind", "cls", "tip", "colour", "name", "caliber")}
        if fields.get("max_stack") == 64:
            del fields["max_stack"]
        if "projectile" in fields:
            fields["projectile"] = f"{NS}:bazooka_rocket"
        base.write(DATA / "flansmod" / "ammo" / f"{aid}.json", {"name": a["name"], "caliber": a["caliber"], "icon": f"{NS}:{aid}", **fields})
        base.model3d(aid, base.ammo_model(a), base.SMALL_ITEM_DISPLAY)
        cls = base.CASING_CLASSES[a["cls"]]
        components = {"flansmod:ammo_type": f"{NS}:{aid}"}
        if a.get("max_stack", 64) != 64:
            components["minecraft:max_stack_size"] = a["max_stack"]
        tip = ingredient(NS, "warhead_bazooka") if a["cls"] == "rocket" else ingredient(BASIC, a["tip"])
        base.bench(f"ammo_{aid}", ["A" + "U" * cls["powder"] + "D"], {"A": ingredient(NS, f"casing_{a['caliber']}"), "U": base.RAW["U"], "D": tip},
                   {"id": "flansmod:ammo", "count": cls["count"], "components": components}, extend=True)

    for gid, gr in GRENADES.items():
        definition = dict(gr)
        if gid in FACTION_OF:
            definition["faction"] = FACTION_OF[gid]
        if gr.get("throwable", True):
            definition["icon"] = f"{NS}:{gid}"
            base.model3d(gid, grenade_model(gid))
            shape = GRENADE_RECIPES[gid]
            base.bench(f"grenade_{gid}", shape, base.raw_key(shape), {"id": "flansmod:grenade", "count": 2,
                       "components": {"flansmod:grenade": f"{NS}:{gid}"}}, extend=True)
        else:
            definition["icon"] = f"{NS}:rocket236"  # flies with the look of its round
        base.write(DATA / "flansmod" / "grenades" / f"{gid}.json", definition)

    for fid, f in FACTIONS.items():
        base.write(DATA / "flansmod" / "factions" / f"{fid}.json", {**f, "icon": f"{NS}:{f['icon']}"})

    for faction in UNIFORMS:
        uniform_textures(faction, ASSETS / "textures" / "entity" / "equipment")
        base.write(ASSETS / "equipment" / f"{faction}.json", {"layers": {
            "humanoid": [{"texture": f"{NS}:{faction}"}], "humanoid_leggings": [{"texture": f"{NS}:{faction}"}]}})
    for cid, (name, faction, slot, armor) in CLOTHING.items():
        base.write(DATA / "flansmod" / "clothing" / f"{cid}.json", {"name": name, "slot": slot, "asset": f"{NS}:{faction}", "icon": f"{NS}:{cid}",
                                                                   "armor": armor, "faction": f"{NS}:{faction}"})
        base.item_model(cid, "clothing")
        base.icon(ASSETS / "textures" / "item" / "clothing" / f"{cid}.png", uniform_icon(faction, slot))
        shape = CLOTHING_RECIPES[slot]
        base.bench(f"clothing_{cid}", shape, base.raw_key(shape, {"D": FACTION_DYE[faction]}),
                   {"id": "flansmod:clothing", "components": {"flansmod:clothing": f"{NS}:{cid}"}}, extend=True)

    # Vehicles with the Vehicles pack's writers (same namespace and pack root as this pack).
    vp.configure(NS, ROOT)
    vp.write_textures()
    vp.write_vehicles({vid: {**v, "faction": FACTION_OF[vid]} for vid, v in VEHICLES.items()})
    vp.write_weapons(MOUNTED_GUNS, MOUNTED_MAGAZINES, MAG_ICONS)
    shell_names = vp.write_shells(SHELLS, {k: vp.WARHEADS[k] for k in ("shot_apcbc", "warhead_he_shell")})
    vp.write_sights(SIGHTS)

    gun_events = base.sounds()
    lang = vp.write_sounds({**gun_events, **vp.sound_events()})
    lang.update({f"subtitles.{NS}.{e}": "Gunshot" if "shoot" in e or "suppressed" in e else "Gun reloads" if "reload" in e else "Gun clicks"
                 for e in gun_events})
    lang.update({f"caliber.flansmod.{cal}": c["name"] for cal, c in CALIBERS.items()})
    lang.update({f"caliber.flansmod.{cal}": name for cal, name in shell_names.items()})
    base.write(ASSETS / "lang" / "en_us.json", lang)
    print(f"Generated {len(GUNS)} guns, {len(MAGAZINES)} magazines, {len(ammo)} ammo types, {len(GRENADES)} grenades, "
          f"{len(VEHICLES)} vehicles, {len(CLOTHING)} uniform pieces, {len(FACTIONS)} factions in {ROOT}")


if __name__ == "__main__":
    main()
