"""
Battlefield structures for structure kits: bunkers, trenches, a watchtower, a gun nest, streets and a roadblock
(Basic pack); a runway and an aircraft hangar (Vehicles pack).

Each structure is built block by block here and written as a vanilla structure template
(`data/<ns>/structure/<name>.nbt`, the format structure blocks save) plus its Flan's definition
(`data/<ns>/flansmod/structures/<name>.json`). Templates face north: z = 0 is the front (towards the enemy), the
entrance is at the back. Air in a template digs out what is there (trenches, interiors); unset cells keep the world.
Used by generate_basic_pack.py.
"""
import gzip
import json
import struct
from pathlib import Path

DATA_VERSION = 5023  # Minecraft 26.3 (version.json "world_version")

CONCRETE = "flansmod:reinforced_concrete"
CONCRETE_SLAB = "flansmod:reinforced_concrete_slab[type=bottom,waterlogged=false]"
EMBRASURE = "flansmod:bunker_embrasure[facing=north]"
SANDBAGS = "flansmod:sandbags"
SANDBAG_SLAB = "flansmod:sandbag_slab[type=bottom,waterlogged=false]"
WIRE = "flansmod:barbed_wire"
HEDGEHOG = "flansmod:czech_hedgehog"
AIR = "minecraft:air"
PLANKS = "minecraft:spruce_planks"
PLANK_SLAB = "minecraft:spruce_slab[type=bottom,waterlogged=false]"
PLANK_SLAB_TOP = "minecraft:spruce_slab[type=top,waterlogged=false]"
LOG = "minecraft:spruce_log[axis=y]"
FENCE = "minecraft:spruce_fence"
LANTERN = "minecraft:lantern[hanging=true,waterlogged=false]"
LANTERN_STANDING = "minecraft:lantern[hanging=false,waterlogged=false]"


def door(half, facing="north"):
    return f"flansmod:bunker_door[facing={facing},half={half},hinge=left,open=false,powered=false]"


def ladder(facing):
    return f"minecraft:ladder[facing={facing},waterlogged=false]"


class Build:
    def __init__(self, w, h, d):
        self.size = (w, h, d)
        self.blocks = {}

    def set(self, x, y, z, state):
        w, h, d = self.size
        assert 0 <= x < w and 0 <= y < h and 0 <= z < d, (x, y, z, self.size)
        self.blocks[(x, y, z)] = state

    def fill(self, x0, y0, z0, x1, y1, z1, state):
        for x in range(min(x0, x1), max(x0, x1) + 1):
            for y in range(min(y0, y1), max(y0, y1) + 1):
                for z in range(min(z0, z1), max(z0, z1) + 1):
                    self.set(x, y, z, state)

    def walls(self, x0, y0, z0, x1, y1, z1, state, inside=AIR):
        """A box: [state] walls, [inside] (usually air) within."""
        self.fill(x0, y0, z0, x1, y1, z1, state)
        if inside is not None and x1 - x0 > 1 and z1 - z0 > 1:
            self.fill(x0 + 1, y0, z0 + 1, x1 - 1, y1, z1 - 1, inside)


# ------------------------------------------------------------------------------------------- designs
def bunker():
    """Concrete command bunker: firing slits to the front and sides, steel door at the back, lantern inside."""
    b = Build(9, 5, 7)
    b.fill(0, 0, 0, 8, 0, 6, CONCRETE)               # floor, flush with the ground (y_offset -1)
    b.walls(0, 1, 0, 8, 3, 6, CONCRETE)
    for x in range(2, 7):
        b.set(x, 2, 0, EMBRASURE)
    b.set(0, 2, 3, EMBRASURE.replace("north", "west"))
    b.set(8, 2, 3, EMBRASURE.replace("north", "east"))
    b.set(4, 1, 6, door("lower", "north"))
    b.set(4, 2, 6, door("upper", "north"))
    b.fill(0, 4, 0, 8, 4, 6, CONCRETE)               # roof
    b.set(4, 3, 3, LANTERN)
    b.set(1, 1, 1, SANDBAG_SLAB); b.set(7, 1, 1, SANDBAG_SLAB)
    return b


def pillbox():
    """Small concrete pillbox: slits on three sides, door at the back."""
    b = Build(5, 4, 5)
    b.fill(0, 0, 0, 4, 0, 4, CONCRETE)
    b.walls(0, 1, 0, 4, 2, 4, CONCRETE)
    for x in range(1, 4):
        b.set(x, 2, 0, EMBRASURE)
    b.set(0, 2, 2, EMBRASURE.replace("north", "west"))
    b.set(4, 2, 2, EMBRASURE.replace("north", "east"))
    b.set(2, 1, 4, door("lower", "north"))
    b.set(2, 2, 4, door("upper", "north"))
    b.fill(0, 3, 0, 4, 3, 4, CONCRETE)
    return b


def trench(cells, w, d, front_parapet=True, east_parapet=False):
    """
    A trench dug two blocks deep (y_offset -3: y 0 = floor under the surface, y 2 = surface, y 3 = above ground).
    [cells] are the (x, z) of the channel; plank revetment around it, a fire step along the front, sandbag parapet.
    """
    b = Build(w, 4, d)
    for x in range(w):
        for z in range(d):
            if (x, z) in cells:
                b.set(x, 0, z, PLANKS)               # duckboards
                b.set(x, 1, z, AIR)
                b.set(x, 2, z, AIR)
                b.set(x, 3, z, AIR)
                if (x, z - 1) not in cells:          # fire step along the front wall
                    b.set(x, 1, z, PLANK_SLAB)
            elif any((x + dx, z + dz) in cells for dx in (-1, 0, 1) for dz in (-1, 0, 1)):
                b.set(x, 1, z, PLANKS)               # revetment
                b.set(x, 2, z, PLANKS)
                if (x, z + 1) in cells and front_parapet:
                    b.set(x, 3, z, SANDBAGS if x % 3 != 1 else SANDBAG_SLAB)  # firing gaps
                elif (x - 1, z) in cells and east_parapet:
                    b.set(x, 3, z, SANDBAGS)
                else:
                    b.set(x, 3, z, SANDBAG_SLAB)
    return b


def trench_straight():
    return trench({(x, z) for x in range(7) for z in (1, 2)}, 7, 4)


def trench_corner():
    """Turns from the left side towards the back (a communication trench)."""
    cells = {(x, z) for x in range(0, 4) for z in (1, 2)} | {(x, z) for x in (2, 3) for z in range(1, 5)}
    return trench(cells, 5, 5, east_parapet=True)


def gun_nest():
    """Horseshoe of sandbags around a gun position, open at the back."""
    b = Build(5, 2, 5)
    for x in range(5):
        for z in range(5):
            edge = x in (0, 4) or z == 0
            if edge and not (z == 4 and x in (0, 4)):
                b.set(x, 0, z, SANDBAGS)
                b.set(x, 1, z, SANDBAG_SLAB if z == 0 else SANDBAGS if z < 3 else AIR)
            elif z < 4:
                b.set(x, 0, z, AIR)
                b.set(x, 1, z, AIR)
    b.set(2, 0, 4, AIR)
    return b


def watchtower():
    """Wooden watchtower: four log legs, ladder at the back, platform with sandbags to the front and a roof."""
    b = Build(5, 10, 5)
    for x, z in ((0, 0), (4, 0), (0, 4), (4, 4)):
        b.fill(x, 0, z, x, 8, z, LOG)
    b.fill(2, 0, 4, 2, 6, 4, LOG)                    # ladder post
    b.fill(2, 0, 3, 2, 7, 3, ladder("north"))
    b.fill(0, 6, 0, 4, 6, 4, PLANKS)                 # platform
    b.set(2, 6, 3, ladder("north"))                  # hatch for the ladder
    for x in range(1, 4):
        b.set(x, 7, 0, SANDBAGS)
    for z in range(1, 4):
        b.set(0, 7, z, FENCE)
        b.set(4, 7, z, FENCE)
    b.set(1, 7, 4, FENCE); b.set(3, 7, 4, FENCE)
    b.fill(0, 9, 0, 4, 9, 4, PLANK_SLAB)             # roof
    b.set(2, 8, 2, LANTERN)
    return b


def street(crossing=False):
    """Road segment (7 x 7) laid into the ground; chain them forwards. Clears two blocks above it."""
    b = Build(7, 5, 7)
    asphalt, line, zebra, kerb = "minecraft:gray_concrete", "minecraft:yellow_concrete", "minecraft:white_concrete", "minecraft:smooth_stone"
    for x in range(7):
        for z in range(7):
            side_x, side_z = x in (0, 6), z in (0, 6)
            if crossing:
                state = kerb if side_x and side_z else asphalt
                if (side_z and not side_x and x % 2 == 1) or (side_x and not side_z and z % 2 == 1):
                    state = zebra
            else:
                state = kerb if side_x else line if x == 3 and z % 3 != 0 else asphalt
            b.set(x, 0, z, state)
            b.set(x, 1, z, AIR)
            b.set(x, 2, z, AIR)
    lamps = [(0, 0), (6, 6)] if crossing else [(0, 3)]
    for x, z in lamps:
        b.fill(x, 1, z, x, 3, z, "minecraft:andesite_wall[east=none,north=none,south=none,up=true,waterlogged=false,west=none]")
        b.set(x, 4, z, LANTERN_STANDING)
    return b


def roadblock():
    """Czech hedgehogs, barbed wire and a sandbag wall across a road."""
    b = Build(7, 2, 3)
    for x in range(0, 7, 2):
        b.set(x, 0, 0, HEDGEHOG)
    b.fill(0, 0, 1, 6, 0, 1, WIRE)
    b.fill(0, 0, 2, 6, 0, 2, SANDBAGS)
    b.fill(0, 1, 2, 6, 1, 2, SANDBAG_SLAB)
    b.set(3, 1, 2, AIR)
    return b


def runway():
    """Runway segment, 13 wide and 48 long, laid into the ground (chain them for a longer strip): concrete with edge
    and centre lines, threshold stripes at both ends and flush edge lights. Clears three blocks above it."""
    w, d = 13, 48
    b = Build(w, 4, d)
    for x in range(w):
        for z in range(d):
            state = "minecraft:gray_concrete"
            if x in (0, w - 1):
                state = "minecraft:sea_lantern" if z % 6 == 3 else "minecraft:smooth_stone"
            elif x in (1, w - 2):
                state = "minecraft:white_concrete"  # edge lines
            elif (z < 5 or z >= d - 5) and z not in (0, d - 1) and x % 2 == 0:
                state = "minecraft:white_concrete"  # threshold stripes ("piano keys")
            elif x == w // 2 and z % 6 < 3:
                state = "minecraft:white_concrete"  # centre line dashes
            b.set(x, 0, z, state)
            for y in range(1, 4):
                b.set(x, y, z, AIR)
    return b


def hangar():
    """Aircraft hangar: concrete floor, walls with windows, an arched roof and the whole back side open (towards the
    builder), 17 blocks wide and 15 deep inside: room for a fighter or a helicopter with its rotor."""
    w, d = 19, 17
    wall, roof, floor = "minecraft:light_gray_concrete", "minecraft:gray_concrete", "minecraft:smooth_stone"

    def height(x):  # roof line: walls 6 high, arching up to 9 in the middle
        return 6 + round(3 * (1 - ((x - (w - 1) / 2) / ((w - 1) / 2)) ** 2) ** 0.5)
    b = Build(w, 11, d)
    for x in range(w):
        top = height(x)
        for z in range(d):
            b.set(x, 0, z, floor if z % 4 else "minecraft:light_gray_concrete")  # floor with expansion joints
            side = x in (0, w - 1)
            for y in range(1, top + 1):
                if side or z == 0:
                    window = y in (3, 4) and ((side and z % 4 == 2 and 0 < z < d - 1) or (z == 0 and x % 4 == 2 and 0 < x < w - 1))
                    b.set(x, y, z, "minecraft:glass" if window else wall)
                elif y < top:
                    b.set(x, y, z, AIR)
            b.set(x, top, z, roof)
            if top + 1 < 11:
                b.set(x, top + 1, z, roof if x in (0, w - 1) or z in (0, d - 1) else AIR)
    # Door frame along the open back, lights under the roof, a workshop corner.
    for x in range(w):
        b.set(x, height(x), d - 1, "minecraft:yellow_concrete" if x % 2 else "minecraft:black_concrete")
    for x in (4, 9, 14):
        for z in (4, 9, 13):
            b.set(x, height(x) - 1, z, LANTERN)
    b.set(1, 1, 1, "minecraft:crafting_table")
    b.set(2, 1, 1, "minecraft:anvil[facing=east]")
    b.set(1, 1, 2, "minecraft:barrel[facing=up,open=false]")
    b.set(w - 2, 1, 1, "minecraft:barrel[facing=up,open=false]")
    return b


# Structures of the Vehicles pack (written by generate_vehicle_pack.py).
AIRFIELD = {
    "runway": ("Runway", runway, "airfield", -1, 400, ["CCCCCC", "GGGGGG", "CLCCLC"]),
    "hangar": ("Aircraft Hangar", hangar, "airfield", -1, 2500, ["IIIIII", "C    C", "CCCCCC"]),
}


# name, builder, category, y_offset, price, bench pattern (keys: C concrete, S sandbags, P planks, L logs, I iron, W wire, H hedgehog, D door, G gravel)
STRUCTURES = {
    "bunker": ("Concrete Bunker", bunker, "bunker", -1, 1500, ["CCCCCC", "CCDCCC", "CCCCCC"]),
    "pillbox": ("Pillbox", pillbox, "bunker", -1, 800, ["CCCC", "CDCC", "CCCC"]),
    "trench_straight": ("Trench", trench_straight, "trench", -3, 150, ["SSSS", "PPPP"]),
    "trench_corner": ("Trench Corner", trench_corner, "trench", -3, 150, ["SSS ", "PPP ", "  P "]),
    "gun_nest": ("Sandbag Gun Nest", gun_nest, "nest", 0, 200, ["SSS", "S S"]),
    "watchtower": ("Watchtower", watchtower, "tower", 0, 600, ["PPPP", "L  L", "L  L", "L  L"]),
    "street": ("Street", lambda: street(False), "street", -1, 50, ["GGG", "CCC"]),
    "street_crossing": ("Street Crossing", lambda: street(True), "street", -1, 80, ["GCG", "CCC", "GCG"]),
    "roadblock": ("Roadblock", roadblock, "checkpoint", 0, 300, ["H H H", "WWWWW", "SSSSS"]),
}


# ------------------------------------------------------------------------------------------- NBT
def _string(s):
    data = s.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _payload(tag, value):
    if tag == 3:
        return struct.pack(">i", value)
    if tag == 8:
        return _string(value)
    if tag == 10:
        out = b""
        for name, (t, v) in value.items():
            out += bytes([t]) + _string(name) + _payload(t, v)
        return out + b"\x00"
    if tag == 9:
        element, items = value
        return bytes([element]) + struct.pack(">i", len(items)) + b"".join(_payload(element, i) for i in items)
    raise ValueError(tag)


def _state(state):
    name, _, props = state.partition("[")
    tag = {"id": (8, name)}  # 26.3 block state format: id + properties (was Name + Properties)
    if props:
        tag["properties"] = (10, {k: (8, v) for k, v in (p.split("=") for p in props.rstrip("]").split(","))})
    return tag


def template_nbt(build: Build) -> bytes:
    palette = sorted(set(build.blocks.values()))
    index = {s: i for i, s in enumerate(palette)}
    root = {
        "DataVersion": (3, DATA_VERSION),
        "size": (9, (3, list(build.size))),
        "palette": (9, (10, [_state(s) for s in palette])),
        "blocks": (9, (10, [{"pos": (9, (3, list(pos))), "state": (3, index[s])} for pos, s in sorted(build.blocks.items())])),
        "entities": (9, (0, [])),
    }
    return gzip.compress(bytes([10]) + _string("") + _payload(10, root), mtime=0)


def write_structures(data: Path, ns: str, table=None):
    """Writes every structure's template and definition ([table], default the Basic pack's [STRUCTURES]); returns
    {id: pattern} for bench recipes."""
    recipes = {}
    for sid, (name, build, category, y_offset, price, pattern) in (table or STRUCTURES).items():
        b = build()
        path = data / "structure" / f"{sid}.nbt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(template_nbt(b))
        definition = {"name": name, "category": category, "y_offset": y_offset, "price": price, "size": list(b.size)}
        out = data / "flansmod" / "structures" / f"{sid}.json"
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(definition, indent=2) + "\n")
        recipes[sid] = pattern
    return recipes
