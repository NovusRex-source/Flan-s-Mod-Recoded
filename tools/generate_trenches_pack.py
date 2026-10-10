#!/usr/bin/env python3
"""
Generates the built-in "Trenches" expansion pack (src/main/resources/resourcepacks/trenches, namespace flanstrenches):
the squads and supports of the Trenches battle mode, after the mobile game *Trenches* (Thunder Game Works, 2009) -
riflemen, machine gun teams whose loader takes over the gun, snipers, mortar teams, engineers who build bunkers and
lay or cut barbed wire, grenade-throwing assault troops, officers who inspire the men around them (and join squads
at random), an artillery barrage, a heavy bombardment and poison gas.

Soldiers carry guns of their team's faction picked by category, so the pack works with every faction of every pack
(WW2: Kar98k riflemen and MG 42 gunners for the Axis, Lee-Enfields and Brens for the British, ...). Run:
    python3 tools/generate_trenches_pack.py
"""
import json
import shutil
from pathlib import Path

NS = "flanstrenches"
ROOT = Path(__file__).resolve().parent.parent / "src/main/resources/resourcepacks/trenches"
DATA = ROOT / "data" / NS / "flansmod"

RIFLE = ["sniper", "dmr", "rifle"]  # bolt-action rifles are "sniper" guns without a scope in the WW2 pack
SIDEARM = ["pistol", "smg"]


def rifleman(**extra):
    return {"role": "rifleman", "guns": RIFLE, "scoped": False, **extra}


UNITS = {
    "rifle_squad": dict(
        name="Riflemen", order=0, cost=100, cooldown_seconds=4, officer_chance=0.15, officer=f"{NS}:officer",
        description="Three riflemen: cheap, steady, the backbone of every attack and defence.",
        soldiers=[rifleman(), rifleman(), rifleman()]),
    "machine_gun_team": dict(
        name="MG Team", order=1, cost=220, cooldown_seconds=10, officer_chance=0.1, officer=f"{NS}:officer",
        description="A machine gunner and his loader: murderous fire at short range. The loader takes over the gun.",
        soldiers=[{"role": "machine_gunner", "guns": ["lmg"], "health": 22, "range": 32, "fire_delay": 0.9},
                  rifleman(loader=True)]),
    "sniper": dict(
        name="Sniper", order=2, cost=150, cooldown_seconds=12,
        description="A marksman: long range, slow and deadly.",
        soldiers=[{"role": "sniper", "guns": ["sniper", "dmr"], "scoped": True, "health": 16, "range": 64, "spread": 0.35,
                   "fire_delay": 1.8}]),
    "mortar_team": dict(
        name="Mortar", order=3, cost=280, cooldown_seconds=18,
        description="Shells the enemy trenches in reach from a trench behind the front. Long reload.",
        soldiers=[{"role": "mortar", "guns": SIDEARM, "range": 16, "health": 18,
                   "mortar": {"power": 2.6, "reload_seconds": 9, "range": 70, "min_range": 12, "scatter": 3}},
                  rifleman()]),
    "engineers": dict(
        name="Engineers", order=4, cost=160, cooldown_seconds=10,
        description="Two sappers: build bunkers (cover and a forward spawn point), lay and cut barbed wire.",
        soldiers=[{"role": "engineer", "guns": ["smg", "shotgun", "pistol"], "health": 18},
                  {"role": "engineer", "guns": ["smg", "shotgun", "pistol"], "health": 18}]),
    "assault_squad": dict(
        name="Assault", order=5, cost=240, cooldown_seconds=12, officer_chance=0.2, officer=f"{NS}:officer",
        description="Three storm troopers with submachine guns and grenades for clearing trenches. Fast.",
        soldiers=[{"role": "assault", "guns": ["smg", "shotgun", "rifle"], "health": 22, "speed": 1.15, "range": 24,
                   "grenades": {"range": 14, "cooldown_seconds": 10}} for _ in range(3)]),
    "veteran_squad": dict(
        name="Veterans", order=6, cost=280, cooldown_seconds=15, officer_chance=0.25, officer=f"{NS}:officer",
        description="Four veterans: tougher and more accurate than fresh riflemen.",
        soldiers=[rifleman(health=26, spread=0.7, fire_delay=0.85) for _ in range(4)]),
    "officer": dict(
        name="Officer", order=7, cost=180, cooldown_seconds=30,
        description="Inspires the men around him: they fire faster and straighter. Also joins squads at random.",
        soldiers=[{"role": "officer", "guns": SIDEARM, "health": 24, "aura": {"radius": 10, "fire_delay": 0.7, "spread": 0.7}}]),
}

SUPPORTS = {
    "barrage": dict(name="Barrage", order=0, type="barrage", cost=400, cooldown_seconds=45, shells=8, power=2.5, duration_seconds=4,
                    description="Eight high-explosive shells on the trench. They hit whoever is there - friend or foe."),
    "bombardment": dict(name="Bombard", order=1, type="barrage", cost=750, cooldown_seconds=90, shells=16, power=3.2,
                        duration_seconds=8, description="Sixteen heavy shells over eight seconds. Friend or foe."),
    "gas": dict(name="Gas", order=2, type="gas", cost=500, cooldown_seconds=75, radius=4.5, duration_seconds=15,
                description="Clouds of poison gas linger in the trench for 15 seconds and hurt everyone in them."),
}


def write(path: Path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False) + "\n")


def main():
    if ROOT.exists():
        shutil.rmtree(ROOT)
    write(ROOT / "pack.mcmeta", {"pack": {"description": "Flan's Mod: Recoded - Trenches: squads and supports for the Trenches battle mode",
                                          "min_format": 97, "max_format": 121}})
    for uid, u in UNITS.items():
        write(DATA / "trench_units" / f"{uid}.json", u)
    for sid, s in SUPPORTS.items():
        write(DATA / "trench_supports" / f"{sid}.json", s)
    print(f"Generated {len(UNITS)} trench units and {len(SUPPORTS)} supports in {ROOT}")


if __name__ == "__main__":
    main()
