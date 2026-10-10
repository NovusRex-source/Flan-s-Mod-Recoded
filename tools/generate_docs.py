#!/usr/bin/env python3
"""
Generates docs/DEFINITIONS.md: every content-pack definition type with its JSON fields, types, defaults and the
KDoc of each field, read straight from the Kotlin data classes (so the reference never drifts from the code).
    python3 tools/generate_docs.py
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/kotlin/com/flansmod/recoded"
OUT = ROOT / "docs/DEFINITIONS.md"

# Definition types: (title, folder under data/<ns>/flansmod/, root class, source file)
TYPES = [
    ("Guns", "guns", "GunDefinition", "gun/GunDefinition.kt"),
    ("Magazines", "magazines", "MagazineDefinition", "gun/AmmoDefinition.kt"),
    ("Ammunition", "ammo", "AmmoDefinition", "gun/AmmoDefinition.kt"),
    ("Attachments", "attachments", "AttachmentDefinition", "gun/AttachmentDefinition.kt"),
    ("Grenades, mines and launcher projectiles", "grenades", "GrenadeDefinition", "gun/GrenadeDefinition.kt"),
    ("Parts", "parts", "PartDefinition", "gun/PartDefinition.kt"),
    ("Clothing", "clothing", "ClothingDefinition", "gun/ClothingDefinition.kt"),
    ("Gear", "gear", "GearDefinition", "gear/Gear.kt"),
    ("Vehicles", "vehicles", "VehicleDefinition", "gun/VehicleDefinition.kt"),
    ("Vehicle upgrades", "vehicle_upgrades", "VehicleUpgradeDefinition", "gun/VehicleUpgradeDefinition.kt"),
    ("Factions", "factions", "FactionDefinition", "gun/FactionDefinition.kt"),
    ("Structure kits", "structures", "StructureDefinition", "gun/StructureDefinition.kt"),
    ("Trench units", "trench_units", "TrenchUnitDefinition", "trenches/TrenchDefinitions.kt"),
    ("Trench supports", "trench_supports", "TrenchSupportDefinition", "trenches/TrenchDefinitions.kt"),
]


# Descriptions for fields without KDoc: "Class.field" first, then "field".
EXTRA = {
    "name": "Display name (a lang key `<type>.<namespace>.<name>` overrides it).",
    "icon": "Item model id of the item icon.",
    "faction": "Side this belongs to (a faction id): faction creative tab and tooltip line.",
    "category": "Grouping in creative tabs, tooltips and the battle shop.",
    "max_stack": "Stack size of the item.",
    "color": "Colour `#RRGGBB`.",
    "width": "Width in blocks.", "length": "Length in blocks.",
    "radius": "Radius in blocks.", "duration_ticks": "How long it lasts.",
    "geo": "GeckoLib model id (`geckolib/models/...`); default derived from the definition id.",
    "texture": "Texture path; default derived from the definition id.",
    "animations": "GeckoLib animation file id; default derived from the definition id.",
    "rotation": "Degrees around x, y, z.", "translation": "Offset in 1/16 block.", "scale": "Scale factors.",
    "GunDefinition.model": "GeckoLib model, texture and animations (defaults follow the gun id).",
    "GunDefinition.damage": "Damage per bullet (per pellet for shotguns).",
    "GunDefinition.headshot_multiplier": "Damage multiplier for hits at head height.",
    "GunDefinition.burst_count": "Rounds per trigger pull in `burst` mode.",
    "GunDefinition.reload_ticks": "Time to swap a magazine (or load one round into an internal magazine).",
    "GunDefinition.lifetime_ticks": "How long a bullet flies before it disappears (with `velocity`: the range).",
    "GunDefinition.ads_spread": "Spread while aiming down sights.",
    "GunDefinition.recoil": "Camera kick per shot.",
    "GunDefinition.sounds": "Sound event ids.",
    "GunDefinition.display": "Item model display transforms per context (`firstperson_righthand`, `thirdperson_righthand`, `gui`, `ground`, `fixed`, `head`) plus `ads` for the aimed pose; vanilla item model semantics.",
    "GunDefinition.attachment_slots": "Attachment slots this gun offers (`sight`, `muzzle`, `grip`, `underbarrel`, `stock`, ...).",
    "GunDefinition.category": "Weapon class: `pistol`, `smg`, `rifle`, `dmr`, `sniper`, `shotgun`, `lmg`, `launcher` (creative order, battle shop price, bots).",
    "GunDefinition.mounted": "Vehicle gun only: never an item, fired from a vehicle seat.",
    "Recoil.pitch": "Upward kick per shot in degrees.",
    "Recoil.ads_multiplier": "Recoil multiplier while aiming.",
    "Scope.overlay": "Full-screen texture shown while fully aimed (hides the gun model).",
    "Scope.thermal_range": "Range of the thermal highlight.",
    "Sounds.shoot": "Played on every shot.", "Sounds.reload": "Played when reloading.", "Sounds.empty": "Dry click (empty or safe).",
    "MagazineDefinition.caliber": "Caliber id; rounds of this caliber fit (`caliber.flansmod.<caliber>` names it).",
    "MagazineDefinition.capacity": "Rounds it holds.",
    "MagazineDefinition.guns": "Guns that accept it (guns may also list magazines).",
    "MagazineDefinition.internal": "Built-in magazine (tube, clip, breech): never an item; reloading loads loose rounds from the inventory.",
    "AmmoDefinition.caliber": "Caliber id; fits magazines of the same caliber.",
    "AmmoDefinition.damage_multiplier": "Multiplies the gun's damage.",
    "AmmoDefinition.velocity_multiplier": "Multiplies the gun's muzzle velocity.",
    "AmmoDefinition.spread_multiplier": "Multiplies the gun's spread.",
    "AttachmentDefinition.slot": "Attachment slot it goes into.",
    "AttachmentDefinition.guns": "Only these guns (default: every gun with the slot).",
    "AttachmentDefinition.damage_multiplier": "Multiplies damage.", "AttachmentDefinition.spread_multiplier": "Multiplies spread.",
    "AttachmentDefinition.recoil_multiplier": "Multiplies recoil.", "AttachmentDefinition.velocity_multiplier": "Multiplies muzzle velocity.",
    "AttachmentDefinition.reload_multiplier": "Multiplies reload time.", "AttachmentDefinition.ads_move_speed_multiplier": "Multiplies movement speed while aiming.",
    "AttachmentDefinition.hide_tracer": "Suppressors: no tracers.",
    "AttachmentDefinition.ads_height": "Sights: height of the dot or lens centre above the rail (1/16 block), so aiming looks through it.",
    "AttachmentDefinition.laser": "Laser sight: a visible dot and tighter hip fire.",
    "Deploy.spread_multiplier": "Spread while set down.", "Deploy.recoil_multiplier": "Recoil while set down.",
    "Deploy.prone_only": "Only when lying down (tripods), not when crouching.",
    "Laser.hip_spread_multiplier": "Hip-fire spread with the laser on.", "Laser.range": "Range of the dot.",
    "GrenadeDefinition.throw_velocity": "Throw speed in blocks per tick.",
    "GrenadeDefinition.gravity": "Downward acceleration per tick.",
    "GrenadeDefinition.cooldown_ticks": "Time between throws.",
    "GrenadeDefinition.throwable": "False for launcher projectiles (rockets, 40mm): no item of their own.",
    "GrenadeDefinition.explosion": "Explosion on detonation.", "GrenadeDefinition.smoke": "Smoke cloud on detonation.",
    "GrenadeDefinition.flash": "Blinding flash on detonation.", "GrenadeDefinition.detonate_sound": "Sound event played on detonation.",
    "GrenadeDefinition.mine": "Makes it a mine: right click lays it on the ground.",
    "Explosion.power": "Explosion strength (TNT is 4).", "Explosion.fire": "Sets the area on fire.",
    "Explosion.break_blocks": "Full TNT-like block destruction (rockets, shells).", "Explosion.block_damage": "Limited damage to weak blocks around it.",
    "Mine.trigger": "`personnel` (anyone) or `vehicle` (vehicles only).", "Mine.arm_ticks": "Time after laying before it is live.",
    "Mine.radius": "Trigger radius.", "Mine.vehicle_damage": "Extra damage to the vehicle part above it.",
    "BlockDamage.max_resistance": "Strongest blast resistance it breaks.", "BlockDamage.chance": "Chance per block (falls off to the edge).",
    "PartDefinition.category": "`gun`, `ammo` or `vehicle` (creative order).",
    "ClothingDefinition.slot": "`head`, `chest`, `legs` or `feet`.",
    "ClothingDefinition.asset": "Vanilla equipment asset (`assets/<ns>/equipment/<name>.json`) that draws it.",
    "ClothingDefinition.armor": "Armour points.", "ClothingDefinition.toughness": "Armour toughness.",
    "ClothingDefinition.knockback_resistance": "Knockback resistance (0-1).", "ClothingDefinition.speed_modifier": "Movement speed change (-0.1 = 10% slower).",
    "GearDefinition.type": "What it is.", "GearDefinition.slots": "Backpacks and pouches: number of slots (multiple of 9).",
    "GearDefinition.consume_seconds": "Medical: time to use.", "GearDefinition.effects": "Medical: status effects applied.",
    "GearDefinition.zoom": "Binoculars: FOV divisor.", "GearDefinition.overlay": "Binoculars: overlay texture.",
    "GearDefinition.armor": "Plates: armour added to the carrier.", "GearDefinition.toughness": "Plates: toughness added.",
    "GearDefinition.durability": "Plates: hits they take.", "GearDefinition.speed_modifier": "Plates: movement speed change.",
    "GearEffect.effect": "Status effect id.", "GearEffect.duration": "Duration in ticks.", "GearEffect.amplifier": "Level - 1.",
    "VehicleDefinition.type": "`car`, `tank`, `static` (emplacements such as mortars), `plane` or `helicopter`.",
    "VehicleDefinition.model": "GeckoLib model, texture and animations (defaults follow the vehicle id).",
    "VehicleDefinition.height": "Hit box height (without `parts`).",
    "VehicleDefinition.upgrade_slots": "Upgrade slots it offers (`engine`, `armour`, `tyres`, ...).",
    "VehicleDefinition.step_height": "Highest step it drives up.", "VehicleDefinition.max_speed": "Top speed in blocks per tick.",
    "VehicleDefinition.max_reverse_speed": "Top speed backwards.", "VehicleDefinition.turn_speed": "Degrees per tick at full steering.",
    "VehicleDefinition.fuel": "Tank and fuel type.", "VehicleDefinition.collision_damage": "Damage to entities it runs over at full speed.",
    "VehicleDefinition.camera_distance": "Third-person camera distance.", "VehicleDefinition.sounds": "Sound event ids.",
    "VehiclePart.box": "Hit box `[right0, up0, forward0, right1, up1, forward1]` in vehicle space.", "VehiclePart.health": "Health of this part.",
    "VehiclePart.armor": "Damage reduction of this part.", "VehiclePart.role": "What breaking it does.",
    "VehiclePart.core_damage": "Share of every hit passed on to the hull.", "VehiclePart.bones": "Model bones hidden while it is broken.",
    "Repair.item": "Item that repairs the vehicle on right click.", "Repair.amount": "Health restored per item.",
    "Fuel.capacity": "Tank size in units (200 per litre).", "Fuel.type": "`petrol`, `diesel` or a pack's own fuel name.",
    "Fuel.items": "Items that refuel it directly on right click (default none).",
    "Seat.position": "`[right, up, forward]` of the seat cushion in blocks.", "Seat.gun": "Mounted gun definition fired from this seat.",
    "Seat.turret": "The gun follows the occupant's aim.", "Seat.muzzle": "Muzzle position relative to `pivot`.",
    "Seat.min_pitch": "Lowest gun elevation.", "Seat.max_pitch": "Highest gun elevation.",
    "Seat.yaw_bone": "Model bone that turns with the aim.", "Seat.pitch_bone": "Model bone that elevates with the aim.",
    "VehicleSounds.engine": "Looping engine sound.",
    "VehicleUpgradeDefinition.slot": "Upgrade slot it goes into.", "VehicleUpgradeDefinition.vehicles": "Only these vehicles.",
    "VehicleUpgradeDefinition.types": "Only these vehicle types.",
    "VehicleUpgradeDefinition.speed_multiplier": "Multiplies top speed.", "VehicleUpgradeDefinition.acceleration_multiplier": "Multiplies acceleration.",
    "VehicleUpgradeDefinition.turn_multiplier": "Multiplies turn speed.", "VehicleUpgradeDefinition.fuel_capacity_multiplier": "Multiplies tank size.",
    "VehicleUpgradeDefinition.fuel_consumption_multiplier": "Multiplies fuel use.", "VehicleUpgradeDefinition.step_height_bonus": "Added step height.",
    "StructureDefinition.size": "Width, height, depth in blocks (tooltip; filled in by the generator).",
    "StructureDefinition.category": "`bunker`, `trench`, `tower`, `street`, `nest`, `checkpoint`, ...",
}


def clean_doc(doc: str) -> str:
    text = " ".join(line.strip().lstrip("*").strip() for line in doc.strip().removeprefix("/**").removesuffix("*/").splitlines())
    text = re.sub(r"\[([\w.]+)]", r"`\1`", text)  # KDoc links → code
    return re.sub(r"\s+", " ", text).strip()


def classes(source: str):
    """All `data class Name(params)` (and enums) with their KDoc: name → (doc, params text | enum constants)."""
    found = {}
    for m in re.finditer(r"(/\*\*(?:(?!\*/).)*\*/\s*)?(?:@[\w.()\":=, ]+\s*)*(data class|enum class) (\w+)\s*[({]", source, re.S):
        doc, kind, name = m.group(1) or "", m.group(2), m.group(3)
        if kind == "enum class":
            body = source[source.index("{", m.end() - 1):]
            constants = re.findall(r'@SerialName\("([\w-]+)"\)', body[:body.find("}")])
            found[name] = (clean_doc(doc), None, constants)
            continue
        depth, i = 1, m.end()
        if source[m.end() - 1] != "(":
            continue
        while depth:
            depth += {"(": 1, ")": -1}.get(source[i], 0)
            i += 1
        found[name] = (clean_doc(doc), source[m.end():i - 1], None)
    return found


def params(text: str):
    """Constructor parameters: (json name, type, default, doc)."""
    out, doc = [], ""
    depth, current = 0, ""
    parts = []
    comment = False
    for i, ch in enumerate(text):  # split at top-level commas (not those inside KDoc comments)
        if text.startswith("/*", i):
            comment = True
        elif comment and text.startswith("*/", i - 1):
            comment = False
        if comment:
            current += ch
            continue
        if ch in "([<{":
            depth += 1
        elif ch in ")]>}":
            depth -= 1
        if ch == "," and depth == 0:
            parts.append(current)
            current = ""
        else:
            current += ch
    parts.append(current)
    for part in parts:
        docs = re.findall(r"/\*\*(?:(?!\*/).)*\*/", part, re.S)
        code = re.sub(r"/\*\*(?:(?!\*/).)*\*/", "", part, flags=re.S)
        code = re.sub(r"//[^\n]*", "", code).strip()
        m = re.search(r"va[lr] (\w+)\s*:\s*(.+?)(?:\s*=\s*(.+))?$", code, re.S)
        if not m:
            continue
        name, type_, default = m.group(1), m.group(2), m.group(3)
        serial = re.search(r'@SerialName\("([\w-]+)"\)', code)
        type_ = re.sub(r"@Serializable\([\w:]+\)\s*", "", type_).strip()
        type_ = re.sub(r"\s+", " ", type_)
        default = re.sub(r"\s+", " ", default.strip()) if default else "**required**"
        out.append((serial.group(1) if serial else name, type_, default, clean_doc(docs[-1]) if docs else ""))
    return out


JSON_TYPES = {"String": "text", "Int": "integer", "Float": "number", "Double": "number", "Boolean": "true/false", "Identifier": "id"}


def json_type(t: str) -> str:
    t = t.replace("?", "")
    for k, v in JSON_TYPES.items():
        t = re.sub(rf"\b{k}\b", v, t)
    t = re.sub(r"List<(.+)>", r"list of \1", t)
    t = re.sub(r"Map<(.+?), (.+)>", r"map \1 → \2", t)
    return t


def json_default(d: str) -> str:
    if d == "**required**":
        return d
    d = re.sub(r"\b(\d+(?:\.\d+)?)[fL]\b", r"\1", d)
    d = re.sub(r"listOf\((.*)\)", r"[\1]", d)
    d = re.sub(r'Identifier\.withDefaultNamespace\("(\w+)"\)', r'"minecraft:\1"', d)
    if d == "[Seat()]":
        return "one default seat"
    d = {"emptyList()": "`[]`", "emptyMap()": "`{}`", "null": "-", "listOf()": "`[]`"}.get(d, d)
    if re.fullmatch(r"\w+\(\)", d):
        return "`{}`"
    m = re.fullmatch(r"([A-Z]\w+)\.([A-Z_]+)", d)
    if m:
        return f'`"{m.group(2).lower()}"`'
    if re.fullmatch(r'"[^"]*"', d):
        return f"`{d}`"
    return d if d.startswith("`") or d in ("-",) else f"`{d}`"


def main():
    all_classes = {}
    for path in SRC.rglob("*.kt"):
        all_classes.update(classes(path.read_text()))
    lines = ["# Content-pack definitions", "",
             "Generated by `tools/generate_docs.py` from the Kotlin data classes - do not edit by hand.", "",
             "Every file lives at `data/<namespace>/flansmod/<folder>/<name>.json`; its id is `<namespace>:<name>`. Fields",
             "are optional unless marked **required**. Distances are in blocks, times in ticks (20 per second), angles in",
             "degrees. Unknown fields are ignored, comments and trailing commas are allowed.", ""]
    lines += ["| Type | Folder |", "|---|---|"] + [f"| [{t}](#{t.lower().replace(' ', '-').replace(',', '')}) | `{f}/` |" for t, f, _, _ in TYPES] + [""]
    for title, folder, root, _ in TYPES:
        doc, text, _ = all_classes[root]
        lines += [f"## {title}", "", f"`data/<ns>/flansmod/{folder}/<name>.json` - {doc}", ""]
        seen, queue = set(), [root]
        while queue:
            name = queue.pop(0)
            if name in seen or name not in all_classes:
                continue
            seen.add(name)
            cdoc, ctext, constants = all_classes[name]
            if constants is not None:
                lines += [f"**{name}** (text): " + ", ".join(f"`{c}`" for c in constants) + (f" - {cdoc}" if cdoc else ""), ""]
                continue
            if name != root:
                lines += [f"### {name}", "", cdoc, ""] if cdoc else [f"### {name}", ""]
            lines += ["| Field | Type | Default | Description |", "|---|---|---|---|"]
            for field, type_, default, fdoc in params(ctext):
                fdoc = fdoc or EXTRA.get(f"{name}.{field}") or EXTRA.get(field, "")
                lines.append(f"| `{field}` | {json_type(type_)} | {json_default(default).replace('|', '/')} | {fdoc.replace('|', '/')} |")
                for ref in re.findall(r"\b([A-Z]\w+)\b", type_):
                    if ref in all_classes and ref not in seen:
                        queue.append(ref)
            lines.append("")
    OUT.parent.mkdir(exist_ok=True)
    OUT.write_text("\n".join(lines) + "\n")
    print(f"Wrote {OUT.relative_to(ROOT)} ({len(lines)} lines)")


if __name__ == "__main__":
    main()
