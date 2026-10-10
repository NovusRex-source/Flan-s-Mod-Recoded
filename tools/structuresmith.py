"""
Battlefield structures for structure kits: bunkers, trenches, a watchtower, a gun nest, streets and a roadblock, and
large battle buildings (field headquarters, bunker complex, gun casemate, town house, ruined house, barracks,
warehouse, fortified outpost) for the Basic pack; a runway and an aircraft hangar (Vehicles pack).

Each structure is built block by block here and written as a vanilla structure template
(`data/<ns>/structure/<name>.nbt`, the format structure blocks save) plus its Flan's definition
(`data/<ns>/flansmod/structures/<name>.json`). Templates face north: z = 0 is the front (towards the enemy), the
entrance is at the back. Air in a template digs out what is there (trenches, interiors); unset cells keep the world.
Used by generate_basic_pack.py.
"""
import gzip
import json
import random
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


def stairs(block, facing, half="bottom"):
    """Stairs whose high side faces [facing] (a roof slope rising towards [facing])."""
    return f"minecraft:{block}_stairs[facing={facing},half={half},shape=straight,waterlogged=false]"


def wood_door(wood, half, facing, hinge="left"):
    return f"minecraft:{wood}_door[facing={facing},half={half},hinge={hinge},open=false,powered=false]"


def bed(facing, part, color="green"):
    """Bed half; the head lies towards [facing]."""
    return f"minecraft:{color}_bed[facing={facing},occupied=false,part={part}]"


PANE = "minecraft:glass_pane"  # connects to its neighbours when placed (shape updates)
BRICKS = "minecraft:bricks"
STONE_BRICKS = "minecraft:stone_bricks"
OAK = "minecraft:oak_planks"
BARREL = "minecraft:barrel[facing=up,open=false]"
LIGHTNING_ROD = "minecraft:lightning_rod[facing=up,powered=false,waterlogged=false]"


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

    def door(self, x, y, z, lower, upper):
        self.set(x, y, z, lower)
        self.set(x, y + 1, z, upper)

    def bed(self, x, y, z, dx, dz, color="green"):
        """Bed with its head at (x, z) and its foot one step along (dx, dz)."""
        facing = {(1, 0): "west", (-1, 0): "east", (0, 1): "north", (0, -1): "south"}[(dx, dz)]
        self.set(x, y, z, bed(facing, "head", color))
        self.set(x + dx, y, z + dz, bed(facing, "foot", color))

    def gable_roof(self, x0, x1, z0, z1, y, block, ridge_along_x=True, gable=None):
        """Pitched roof of [block] stairs over x0..x1 / z0..z1 from height [y]; the ridge runs along x (slopes to the
        front and back) or along z. [gable] fills the triangular end walls. Returns the ridge height."""
        a0, a1 = (z0, z1) if ridge_along_x else (x0, x1)
        lo, hi = ("south", "north") if ridge_along_x else ("east", "west")
        k = 0
        while a0 + k < a1 - k:
            for b in range(x0, x1 + 1) if ridge_along_x else range(z0, z1 + 1):
                for a, facing in ((a0 + k, lo), (a1 - k, hi)):
                    self.set(*((b, y + k, a) if ridge_along_x else (a, y + k, b)), stairs(block, facing))
            if gable:
                for a in range(a0 + k + 1, a1 - k):
                    for b in ((x0, x1) if ridge_along_x else (z0, z1)):
                        self.set(*((b, y + k, a) if ridge_along_x else (a, y + k, b)), gable)
            k += 1
        if a0 + k == a1 - k:  # odd width: a slab on the ridge
            for b in range(x0, x1 + 1) if ridge_along_x else range(z0, z1 + 1):
                self.set(*((b, y + k, a0 + k) if ridge_along_x else (a0 + k, y + k, b)), f"minecraft:{block}_slab[type=bottom,waterlogged=false]")
        return y + k


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


# ------------------------------------------------------------------------------------------- large battle buildings
def field_hq():
    """Two-storey command post: concrete ground floor (map room, quarters, firing slits), a roof terrace behind sandbags
    with a glazed observation room and a radio mast, a ladder through a hatch, sandbag cover in front."""
    b = Build(15, 11, 13)
    # Sandbag wall in front with gaps to fire through.
    for x in range(15):
        b.set(x, 1, 0, SANDBAGS if x not in (3, 11) else AIR)
        b.set(x, 2, 0, SANDBAG_SLAB if x % 4 else AIR)
    b.fill(1, 0, 2, 13, 0, 11, CONCRETE)
    b.walls(1, 1, 2, 13, 4, 11, CONCRETE)
    for x in (3, 5, 9, 11):
        b.set(x, 2, 2, EMBRASURE)
    for z in (5, 8):
        b.set(1, 2, z, EMBRASURE.replace("north", "west"))
        b.set(13, 2, z, EMBRASURE.replace("north", "east"))
    b.door(7, 1, 11, door("lower", "north"), door("upper", "north"))
    # Partition: map room on the left, quarters with the ladder on the right.
    b.fill(8, 1, 3, 8, 4, 10, CONCRETE)
    b.fill(8, 1, 6, 8, 2, 7, AIR)
    b.set(3, 1, 4, "minecraft:cartography_table"); b.set(4, 1, 4, "minecraft:lectern[facing=south,has_book=false,powered=false]")
    b.set(5, 1, 4, "minecraft:crafting_table")
    b.set(4, 1, 5, stairs("spruce", "south"))
    b.set(2, 1, 9, BARREL); b.set(2, 2, 9, BARREL); b.set(3, 1, 10, "minecraft:chest[facing=north,type=single,waterlogged=false]")
    b.bed(12, 1, 3, -1, 0); b.bed(12, 1, 5, -1, 0)
    b.fill(12, 1, 10, 12, 5, 10, ladder("north"))   # through a hatch onto the terrace
    b.fill(1, 5, 2, 13, 5, 11, CONCRETE)
    b.set(12, 5, 10, ladder("north"))
    for x in range(1, 14):
        for z in (2, 11):
            b.set(x, 6, z, SANDBAGS if x % 3 else SANDBAG_SLAB)
    for z in range(3, 11):
        b.set(1, 6, z, SANDBAGS if z % 3 else SANDBAG_SLAB)
        b.set(13, 6, z, SANDBAGS if z % 3 else SANDBAG_SLAB)
    # Observation room: concrete posts, windows all round, open at the back.
    b.walls(4, 6, 4, 10, 8, 8, CONCRETE)
    for x in range(5, 10):
        b.set(x, 7, 4, PANE); b.set(x, 7, 8, PANE)
    for z in range(5, 8):
        b.set(4, 7, z, PANE); b.set(10, 7, z, PANE)
    b.door(7, 6, 8, AIR, AIR)
    b.fill(4, 9, 4, 10, 9, 8, CONCRETE_SLAB)
    b.set(7, 8, 6, LANTERN); b.set(4, 4, 7, LANTERN); b.set(11, 4, 7, LANTERN)
    b.set(9, 10, 5, LIGHTNING_ROD)                  # radio mast
    return b


def bunker_complex():
    """Coastal-defence style bunker: three gun rooms with slits to the front, a corridor, quarters, command room and
    ammunition store at the back, an armoured observation cupola on the roof (ladder from the corridor)."""
    w, d = 19, 15
    b = Build(w, 8, d)
    b.fill(0, 0, 0, w - 1, 0, d - 1, CONCRETE)
    b.walls(0, 1, 0, w - 1, 3, d - 1, CONCRETE)
    for x0 in (1, 7, 13):                            # gun rooms
        for x in range(x0 + 1, x0 + 4):
            b.set(x, 2, 0, EMBRASURE)
    b.set(0, 2, 2, EMBRASURE.replace("north", "west")); b.set(w - 1, 2, 2, EMBRASURE.replace("north", "east"))
    for x in (6, 12):
        b.fill(x, 1, 1, x, 3, 4, CONCRETE)
        b.fill(x, 1, 9, x, 3, 13, CONCRETE)
    for z, doors in ((5, (3, 9, 15)), (8, (3, 10, 15))):  # corridor walls with doorways (none behind the ladder)
        b.fill(1, 1, z, w - 2, 3, z, CONCRETE)
        for x in doors:
            b.fill(x, 1, z, x, 2, z, AIR)
    for x in (3, 9, 15):
        b.door(x, 1, d - 1, door("lower", "north"), door("upper", "north"))
    for x0 in (1, 7, 13):                            # sandbags for the gunners, a lantern per room
        b.set(x0, 1, 1, SANDBAG_SLAB); b.set(x0 + 4, 1, 1, SANDBAG_SLAB)
        b.set(x0 + 2, 3, 3, LANTERN)
    for z in (9, 11):                                # quarters
        b.bed(1, 1, z, 1, 0, "gray"); b.bed(5, 1, z + 1, -1, 0, "gray")
    b.set(4, 3, 11, LANTERN)
    b.set(9, 1, 11, "minecraft:cartography_table"); b.set(10, 1, 11, "minecraft:crafting_table"); b.set(9, 3, 11, LANTERN)
    for x in range(13, 18):                          # ammunition store
        b.set(x, 1, 9, BARREL); b.set(x, 2, 9, BARREL if x % 2 else AIR)
    b.set(17, 1, 11, BARREL); b.set(17, 1, 12, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    b.set(15, 3, 11, LANTERN)
    b.set(9, 3, 6, LANTERN); b.set(3, 3, 7, LANTERN); b.set(15, 3, 7, LANTERN)
    b.fill(0, 4, 0, w - 1, 4, d - 1, CONCRETE)      # roof with a sandbag rim
    for x in range(w):
        for z in range(d):
            if x in (0, w - 1) or z in (0, d - 1):
                b.set(x, 5, z, SANDBAG_SLAB)
    b.walls(7, 5, 5, 11, 6, 9, CONCRETE)            # cupola
    for x in range(8, 11):
        b.set(x, 6, 5, EMBRASURE); b.set(x, 6, 9, EMBRASURE.replace("north", "south"))
    for z in range(6, 9):
        b.set(7, 6, z, EMBRASURE.replace("north", "west")); b.set(11, 6, z, EMBRASURE.replace("north", "east"))
    b.fill(7, 7, 5, 11, 7, 9, CONCRETE)
    b.fill(9, 1, 7, 9, 4, 7, ladder("north"))       # from the corridor up into the cupola
    return b


def casemate():
    """Artillery casemate: walls two blocks thick, a wide gun port to the front, the back open to wheel a gun in,
    ammunition niches in the side walls, a camouflaged roof."""
    w, d = 11, 12
    b = Build(w, 7, d)
    b.fill(0, 0, 0, w - 1, 0, d - 1, CONCRETE)
    b.fill(0, 1, 0, w - 1, 4, d - 1, CONCRETE)
    b.fill(2, 1, 2, w - 3, 4, d - 1, AIR)           # gun room, open at the back
    b.fill(0, 1, d - 1, 2, 4, d - 1, CONCRETE); b.fill(w - 3, 1, d - 1, w - 1, 4, d - 1, CONCRETE)
    b.fill(4, 2, 0, 6, 3, 1, AIR)                   # gun port
    b.fill(3, 2, 1, 7, 3, 1, AIR)
    for x in (1, w - 2):                             # niches with shells
        b.fill(x, 1, 5, x, 2, 7, AIR)
        b.fill(x, 1, 5, x, 1, 7, BARREL)
    b.fill(0, 5, 0, w - 1, 5, d - 1, CONCRETE)
    b.fill(0, 6, 0, w - 1, 6, d - 1, "minecraft:moss_block")
    for z in (4, 8):
        b.set(5, 4, z, LANTERN)
    return b


def town_house(ruined=False):
    """Two-storey brick house with a pitched roof: doors front and back, windows on every side, a staircase to the
    upper floor (rooms to hold, windows to shoot from). [ruined]: shelled - half the roof and upper floor gone, holes
    and rubble."""
    w, d = 9, 9
    b = Build(w, 14, d)
    b.fill(0, 0, 0, w - 1, 0, d - 1, STONE_BRICKS)
    b.fill(1, 0, 1, w - 2, 0, d - 2, OAK)
    b.walls(0, 1, 0, w - 1, 8, d - 1, BRICKS)
    b.fill(1, 5, 1, w - 2, 5, d - 2, OAK)            # upper floor
    for x in (2, 6):                                 # windows
        b.fill(x, 2, 0, x, 3, 0, PANE); b.fill(x, 6, 0, x, 7, 0, PANE); b.fill(x, 6, d - 1, x, 7, d - 1, PANE)
    b.fill(4, 6, 0, 4, 7, 0, PANE)
    b.fill(6, 2, d - 1, 6, 3, d - 1, PANE)
    for z in (2, 6):
        for x in (0, w - 1):
            b.fill(x, 2, z, x, 3, z, PANE); b.fill(x, 6, z, x, 7, z, PANE)
    b.door(4, 1, 0, wood_door("spruce", "lower", "south"), wood_door("spruce", "upper", "south"))
    b.door(2, 1, d - 1, wood_door("spruce", "lower", "north"), wood_door("spruce", "upper", "north"))
    for i, z in enumerate((6, 5, 4, 3)):             # staircase along the east wall, rising to the north
        b.set(7, 1 + i, z, stairs("spruce", "north"))
        b.set(7, 5, z, AIR)
    for z in range(3, 7):
        b.set(6, 6, z, FENCE)
    b.set(2, 1, 4, "minecraft:spruce_fence"); b.set(2, 2, 4, "minecraft:spruce_pressure_plate[powered=false]")
    b.set(1, 1, 4, stairs("spruce", "west")); b.set(3, 1, 4, stairs("spruce", "east"))
    b.set(1, 1, 1, BARREL); b.set(4, 4, 4, LANTERN)
    b.bed(1, 6, 1, 0, 1, "red"); b.set(1, 6, 7, "minecraft:chest[facing=east,type=single,waterlogged=false]")
    b.gable_roof(0, w - 1, 0, d - 1, 9, "dark_oak", gable=BRICKS)
    for x in range(1, w - 1):                        # attic stays open
        for y in range(9, 13):
            for z in range(1, d - 1):
                if (x, y, z) not in b.blocks:
                    b.set(x, y, z, AIR)
    if ruined:
        rng = random.Random(1944)
        for (x, y, z) in list(b.blocks):
            if y >= 9 and x >= 4:
                b.set(x, y, z, AIR)                  # roof blown off the east half
            elif y >= 6 and x >= 5 and (x, z) not in ((w - 1, 0), (w - 1, d - 1)) and rng.random() < 0.6:
                b.set(x, y, z, AIR)                  # upper storey shattered
            elif b.blocks[(x, y, z)] == BRICKS and rng.random() < 0.12:
                b.set(x, y, z, "minecraft:cracked_stone_bricks" if rng.random() < 0.5 else "minecraft:mossy_cobblestone")
        b.fill(6, 5, 2, 7, 5, 4, AIR)                # hole in the floor
        b.fill(0, 2, 3, 0, 4, 4, AIR)                # shell hole in the west wall
        for x, z in ((5, 2), (6, 2), (6, 3), (5, 6), (6, 6), (2, 6)):
            b.set(x, 1, z, "minecraft:cobblestone" if (x + z) % 2 else "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")
        b.set(6, 2, 2, "minecraft:cobblestone_slab[type=bottom,waterlogged=false]")
    return b


def barracks():
    """Long wooden hut: bunks along both walls, a stove, doors at both ends, a pitched roof."""
    w, d = 9, 17
    b = Build(w, 9, d)
    b.fill(0, 0, 0, w - 1, 0, d - 1, "minecraft:cobblestone")
    b.fill(1, 0, 1, w - 2, 0, d - 2, "minecraft:spruce_planks")
    b.walls(0, 1, 0, w - 1, 3, d - 1, "minecraft:spruce_planks")
    for z in range(0, d, 4):
        b.fill(0, 1, z, 0, 3, z, LOG); b.fill(w - 1, 1, z, w - 1, 3, z, LOG)
    for z in range(2, d, 4):
        b.set(0, 2, z, PANE); b.set(w - 1, 2, z, PANE)
    for z in (0, d - 1):
        b.door(4, 1, z, wood_door("spruce", "lower", "south" if z == 0 else "north"), wood_door("spruce", "upper", "south" if z == 0 else "north"))
        b.set(2, 2, z, PANE); b.set(6, 2, z, PANE)
    for z in (2, 4, 6, 10, 12, 14):
        b.bed(1, 1, z, 1, 0, "brown"); b.bed(w - 2, 1, z, -1, 0, "brown")
    b.set(1, 1, 8, "minecraft:furnace[facing=east,lit=false]"); b.set(w - 2, 1, 8, "minecraft:crafting_table")
    b.set(1, 1, 1, BARREL); b.set(w - 2, 1, 1, BARREL); b.set(1, 1, d - 2, BARREL); b.set(w - 2, 1, d - 2, BARREL)
    ridge = b.gable_roof(0, w - 1, 0, d - 1, 4, "spruce", ridge_along_x=False, gable="minecraft:spruce_planks")
    for x in range(1, w - 1):
        for y in range(4, ridge):
            for z in range(1, d - 1):
                if (x, y, z) not in b.blocks:
                    b.set(x, y, z, AIR)
    for z in (4, 8, 12):
        b.set(4, ridge - 1, z, LANTERN)
    return b


def warehouse():
    """Big storage hall: wide openings front and back, crates and barrels for cover, catwalks with ladders along the
    side walls under high windows, skylights."""
    w, d, h = 21, 15, 8
    b = Build(w, h, d)
    wall, pillar = "minecraft:light_gray_concrete", "minecraft:gray_concrete"
    b.fill(0, 0, 0, w - 1, 0, d - 1, "minecraft:smooth_stone")
    b.walls(0, 1, 0, w - 1, h - 2, d - 1, wall)
    for x in range(0, w, 5):
        b.fill(x, 1, 0, x, h - 2, 0, pillar); b.fill(x, 1, d - 1, x, h - 2, d - 1, pillar)
    for z in (0, 7, d - 1):
        b.fill(0, 1, z, 0, h - 2, z, pillar); b.fill(w - 1, 1, z, w - 1, h - 2, z, pillar)
    for x in range(1, w - 1):                        # high windows
        if x % 5 in (2, 3):
            b.set(x, 5, 0, PANE); b.set(x, 5, d - 1, PANE)
    for z in range(1, d - 1):
        if z % 7 in (3, 4):
            b.set(0, 5, z, PANE); b.set(w - 1, 5, z, PANE)
    for z in (0, d - 1):                             # loading openings
        b.fill(8, 1, z, 12, 4, z, AIR)
        for x in range(8, 13):
            b.set(x, 5, z, "minecraft:yellow_concrete" if x % 2 else "minecraft:black_concrete")
    for x, side in ((1, "east"), (w - 2, "west")):   # catwalks + ladders
        b.fill(x, 4, 1, x, 4, d - 3, "minecraft:spruce_slab[type=top,waterlogged=false]")
        b.fill(x, 1, d - 2, x, 4, d - 2, ladder(side))
        rail = x + 1 if x == 1 else x - 1
        b.fill(rail, 5, 1, rail, 5, d - 3, FENCE)
    crate = "minecraft:spruce_planks"
    for (x0, z0, high) in ((4, 3, 2), (15, 3, 3), (4, 9, 1), (15, 9, 2), (10, 6, 1)):
        for x in (x0, x0 + 1):
            for z in (z0, z0 + 1):
                for y in range(1, high + 1):
                    b.set(x, y, z, crate if (x + y + z) % 3 else BARREL)
    b.fill(9, 1, 11, 11, 1, 11, "minecraft:hay_block[axis=x]")
    b.fill(0, h - 1, 0, w - 1, h - 1, d - 1, pillar)  # flat roof with skylights
    for x in range(2, w - 2, 5):
        for z in range(3, d - 2, 4):
            b.set(x, h - 1, z, "minecraft:glass")
    for x in (5, 10, 15):
        for z in (4, 10):
            b.set(x, h - 2, z, LANTERN)
    return b


def outpost():
    """Fortified outpost, 25 x 25: corner towers with ladders and sandbagged platforms, chest-high sandbag walls with
    firing gaps, a gate at the back, a concrete command hut, a gun nest facing the front, lamps and a gravel road."""
    n = 25
    b = Build(n, 8, n)
    b.fill(1, 1, 1, n - 2, 3, n - 2, AIR)            # level the inside
    for i in range(3, n - 3):                         # walls between the towers
        for x, z in ((i, 0), (i, n - 1), (0, i), (n - 1, i)):
            b.set(x, 1, z, SANDBAGS)
            b.set(x, 2, z, SANDBAG_SLAB if i % 3 else AIR)
    b.fill(11, 1, n - 1, 13, 2, n - 1, AIR)          # gate
    b.set(10, 3, n - 1, LANTERN_STANDING); b.set(14, 3, n - 1, LANTERN_STANDING)
    b.set(10, 2, n - 1, SANDBAGS); b.set(14, 2, n - 1, SANDBAGS)
    for cx, cz in ((0, 0), (n - 3, 0), (0, n - 3), (n - 3, n - 3)):  # towers
        b.fill(cx, 0, cz, cx + 2, 5, cz + 2, CONCRETE)
        ix, iz = cx + 1, cz + 1
        out_z = -1 if cz == 0 else 1                  # ladder on the outer wall, entrance from the inside
        b.fill(ix, 1, iz, ix, 5, iz, ladder("south" if out_z == -1 else "north"))
        b.door(ix, 1, iz - out_z, AIR, AIR)
        for x in range(cx, cx + 3):
            for z in range(cz, cz + 3):
                if x in (cx, cx + 2) or z in (cz, cz + 2):
                    b.set(x, 6, z, SANDBAGS if (x + z) % 2 == 0 else SANDBAG_SLAB)
        b.set(cx + (2 if cx == 0 else 0), 7, cz + (2 if cz == 0 else 0), LANTERN_STANDING)
    # Command hut.
    b.fill(9, 0, 14, 15, 0, 18, CONCRETE)
    b.walls(9, 1, 14, 15, 3, 18, CONCRETE)
    for x in (10, 12, 14):
        b.set(x, 2, 14, EMBRASURE)
    b.door(12, 1, 18, door("lower", "north"), door("upper", "north"))
    b.fill(9, 4, 14, 15, 4, 18, CONCRETE_SLAB)
    b.set(10, 1, 15, "minecraft:cartography_table"); b.set(14, 1, 15, BARREL); b.set(14, 1, 17, "minecraft:chest[facing=west,type=single,waterlogged=false]")
    b.set(12, 3, 16, LANTERN); b.set(14, 5, 16, LIGHTNING_ROD)
    # Gun nest towards the front.
    for x in range(10, 15):
        for z in range(5, 9):
            edge = x in (10, 14) or z == 5
            if edge and z < 8:
                b.set(x, 1, z, SANDBAGS); b.set(x, 2, z, SANDBAG_SLAB if z == 5 else AIR)
    b.set(10, 1, 9, BARREL); b.set(14, 1, 9, BARREL)
    # Road from the gate, lamps.
    b.fill(11, 0, 19, 13, 0, n - 1, "minecraft:gravel")
    for x, z in ((6, 12), (18, 12), (6, 20), (18, 20)):
        b.fill(x, 1, z, x, 2, z, FENCE)
        b.set(x, 3, z, LANTERN_STANDING)
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
    # Large battle buildings (keys also: B bricks, T stone bricks, N glass panes, I iron).
    "field_hq": ("Field Headquarters", field_hq, "base", -1, 2500, ["SSSSSS", "CCNNCC", "CCDCCC", "CCCCCC"]),
    "bunker_complex": ("Bunker Complex", bunker_complex, "bunker", -1, 4000, ["CCCCCC", "CCCCCC", "CDCCDC", "CCIICC"]),
    "casemate": ("Gun Casemate", casemate, "bunker", -1, 2500, ["CCCCCC", "C    C", "CCIICC"]),
    "town_house": ("Town House", lambda: town_house(False), "building", -1, 1200, ["  BB  ", " BBBB ", "BNBBNB", "BBDPBB"]),
    "ruined_house": ("Ruined House", lambda: town_house(True), "building", -1, 800, ["  B   ", " BGBG ", "BNBBGB", "BBDPBB"]),
    "barracks": ("Barracks", barracks, "building", -1, 1200, [" PPPP ", "PPPPPP", "PNPPNP", "LPDPPL"]),
    "warehouse": ("Warehouse", warehouse, "building", -1, 2000, ["CCCCCC", "CNCCNC", "C    C", "CTTTTC"]),
    "outpost": ("Fortified Outpost", outpost, "base", -1, 5000, ["CSSSSC", "S    S", "S CC S", "CSDDSC"]),
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
