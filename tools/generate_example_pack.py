#!/usr/bin/env python3
"""
The small "example" content pack: a minimal, current-format example of a content pack (two guns with magazines,
rounds and attachments) used by the GameTests and handy as a template. It borrows the Basic pack's models and item
icons, so it needs the Basic pack. Writes:
- src/gametest/resources (data + assets, loaded by the test mod),
- run/contentpacks/example (the dev client's content pack folder) if run/ exists.
    python3 tools/generate_example_pack.py
"""
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
B = "flansbasic"


def basic_gun(gid):
    """Model, display and rail calibration of a Basic pack gun, so the example renders and aims like it."""
    d = json.loads((ROOT / f"src/main/resources/resourcepacks/basic/data/{B}/flansmod/guns/{gid}.json").read_text())
    out = {"model": {"geo": f"{B}:gun/{gid}", "texture": d["model"]["texture"], "animations": f"{B}:gun/{gid}"}, "display": d["display"]}
    if "rail_ads" in d:
        out["rail_ads"] = d["rail_ads"]
    return out


DATA = {
    "guns/rifle.json": {"name": "Example Rifle", "category": "rifle", "damage": 6, "rpm": 600, "fire_mode": "auto",
                        "fire_modes": ["safe", "semi", "auto"], "reload_ticks": 40, "velocity": 12, "gravity": 0.03, "spread": 3,
                        "ads_spread": 0.5, "ads_zoom": 1.5, "recoil": {"pitch": 1.2, "yaw": 0.6},
                        "sounds": {"shoot": f"{B}:gun.rifle.shoot", "reload": f"{B}:gun.reload", "empty": f"{B}:gun.empty"},
                        "attachment_slots": ["sight", "barrel"], "magazines": ["example:rifle_mag"], **basic_gun("m4a1")},
    "guns/shotgun.json": {"name": "Example Shotgun", "category": "shotgun", "damage": 3, "pellets": 8, "spread": 8, "rpm": 60,
                          "fire_mode": "semi", "fire_modes": ["safe", "semi"], "magazines": ["example:shotgun_mag"],
                          "sounds": {"shoot": f"{B}:gun.shotgun.shoot", "reload": f"{B}:gun.reload", "empty": f"{B}:gun.empty"},
                          **basic_gun("m870")},
    "attachments/red_dot.json": {"name": "Example Red Dot", "slot": "sight", "ads_zoom": 1.8, "spread_multiplier": 0.7, "ads_height": 1.5,
                                 "icon": f"{B}:red_dot"},
    "attachments/suppressor.json": {"name": "Example Suppressor", "slot": "barrel", "damage_multiplier": 0.9, "hide_tracer": True,
                                    "shoot_sound": f"{B}:gun.suppressed", "icon": f"{B}:suppressor"},
    "magazines/rifle_mag.json": {"name": "Example Rifle Magazine (30)", "caliber": "example_rifle", "capacity": 30, "icon": f"{B}:stanag_30"},
    # A tube magazine: loaded with loose shells, never an item.
    "magazines/shotgun_mag.json": {"name": "Tube Magazine (6)", "caliber": "example_shell", "capacity": 6, "internal": True},
    "ammo/rifle_round.json": {"name": "Example Rifle Round", "caliber": "example_rifle", "icon": f"{B}:556"},
    "ammo/shell.json": {"name": "Example Shell", "caliber": "example_shell", "pellets": 8, "icon": f"{B}:12g"},
}
LANG = {"caliber.flansmod.example_rifle": "Example Rifle Round", "caliber.flansmod.example_shell": "Example Shell"}
PACK = {"pack": {"description": "Example content pack (needs Flan's Basic pack)", "min_format": 97, "max_format": 121},
        "flansmod": {"name": "Example Pack", "icon": "example:rifle"}}


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2) + "\n")


def emit(root: Path):
    for folder in ("data/example", "assets/example"):
        if (root / folder).exists():
            shutil.rmtree(root / folder)
    for name, data in DATA.items():
        write(root / "data/example/flansmod" / name, data)
    write(root / "assets/example/lang/en_us.json", LANG)


def main():
    emit(ROOT / "src/gametest/resources")
    run = ROOT / "run"
    if run.exists():
        target = run / "contentpacks/example"
        if target.exists():
            shutil.rmtree(target)
        emit(target)
        write(target / "pack.mcmeta", PACK)
    print("Generated the example content pack")


if __name__ == "__main__":
    main()
