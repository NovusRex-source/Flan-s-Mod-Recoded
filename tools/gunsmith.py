"""
Gun and magazine geometry shared by the content-pack generators (Basic, WW2).

Units are GeckoLib model pixels at roughly real proportions: 1 px ≈ 23 mm (an M4 is 840 mm ≈ 36.5 px long).
Guns face north: the muzzle points to -Z, the butt to +Z, x is centred, the bore axis sits at y ≈ 7.5 and the grip
bottom near y ≈ 0 (first-person poses are calibrated for that frame).

Cubes may be rotated (angled grips, curved magazines, stocks). Magazines are built once per magazine type in their own
frame (feed lips at the origin, body hanging down, front at -Z) and placed into every gun that takes them, so the
magazine on the gun and the magazine item look identical.
"""
import math
import random
from pathlib import Path

from PIL import Image

# ------------------------------------------------------------------------------------------- materials
# Gun texture: 256x256, sixteen 64x64 noisy material tiles. Faces map onto their tile 1 texel per model pixel (per-face
# UV), so long parts never run into a neighbouring tile.
MATERIALS = {"metal": (0, 0), "polymer": (64, 0), "wood": (128, 0), "red": (192, 0),
             "olive": (0, 64), "tan": (64, 64), "steel": (128, 64), "lens": (192, 64),
             "black": (0, 128), "brass": (64, 128), "glass": (128, 128), "flash": (192, 128),
             "walnut": (0, 192), "parkerized": (64, 192), "bakelite": (128, 192), "blued": (192, 192)}
COLOURS = {"metal": (58, 61, 66), "polymer": (34, 36, 40), "wood": (122, 82, 48), "red": (200, 30, 30),
           "olive": (82, 92, 58), "tan": (176, 150, 108), "steel": (128, 132, 138), "lens": (70, 120, 140),
           "black": (20, 21, 23), "brass": (205, 165, 60), "glass": (60, 70, 72), "flash": (255, 226, 130),
           "walnut": (88, 55, 32), "parkerized": (72, 76, 70), "bakelite": (120, 58, 30), "blued": (40, 44, 56)}
TEXTURE_SIZE = (256, 256)


def gun_texture(path: Path):
    rng = random.Random(1)
    img = Image.new("RGBA", TEXTURE_SIZE)
    for mat, (ox, oy) in MATERIALS.items():
        grain = mat in ("wood", "walnut")
        for x in range(64):
            for y in range(64):
                n = rng.randint(-6, 6) + (8 if grain and (y // 2) % 5 == 0 else 0) - (4 if grain and (x + y // 3) % 11 == 0 else 0)
                img.putpixel((ox + x, oy + y), tuple(max(0, min(255, c + n)) for c in COLOURS[mat]) + (255,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


# ------------------------------------------------------------------------------------------- cubes
def cube(origin, size, mat="metal", rotation=None, pivot=None):
    """A GeckoLib cube with per-face UV on its material tile; optional rotation (degrees) about [pivot]."""
    ox, oy = MATERIALS[mat]
    w, h, d = size

    def face(a, b):
        return {"uv": [ox + 2, oy + 2], "uv_size": [round(max(0.5, min(a, 60)), 3), round(max(0.5, min(b, 60)), 3)]}

    c = {"origin": [round(v, 3) for v in origin], "size": [round(v, 3) for v in size],
         "uv": {"north": face(w, h), "south": face(w, h), "east": face(d, h), "west": face(d, h), "up": face(w, d), "down": face(w, d)},
         "mat": mat}
    if rotation and any(rotation):
        c["rotation"] = [round(v, 3) for v in rotation]
        c["pivot"] = [round(v, 3) for v in (pivot or [origin[i] + size[i] / 2 for i in range(3)])]
    return c


def bx(x0, y0, z0, x1, y1, z1, mat="metal", rot=None, pivot=None):
    """Box from min to max corner; [rot] = (x, y, z) degrees about [pivot] (default: the box centre)."""
    return cube((min(x0, x1), min(y0, y1), min(z0, z1)), (abs(x1 - x0), abs(y1 - y0), abs(z1 - z0)), mat, rot, pivot)


def sym(w, y0, z0, y1, z1, mat="metal", rot=None, pivot=None, x=0.0):
    """Box centred on x with half-width [w]."""
    return bx(x - w, y0, z0, x + w, y1, z1, mat, rot, pivot)


def _circle(r):
    """A pixel-art circle of radius [r] as horizontal slabs: (half-width, y0, y1) per slab, symmetric about 0."""
    n = max(1, round(r / 0.45))  # slabs per half
    out = []
    for i in range(n):
        y0, y1 = r * i / n, r * (i + 1) / n
        half = r * math.sqrt(max(0.0, 1 - (((y0 + y1) / 2) / r) ** 2))
        out += [(half, y0, y1), (half, -y1, -y0)]
    return out


def tube(z0, z1, y, r, mat="metal", x=0.0):
    """Round tube along Z (barrels, gas tubes, scopes) with a pixel-circle section."""
    if r < 0.6:  # thin: two crossed boxes read as round
        return [bx(x - r, y - r * 0.6, z0, x + r, y + r * 0.6, z1, mat), bx(x - r * 0.6, y - r, z0, x + r * 0.6, y + r, z1, mat)]
    return [bx(x - h, y + a, z0, x + h, y + b, z1, mat) for h, a, b in _circle(r)]


def disc(x0, x1, y, z, r, mat="metal"):
    """Round disc along X (drum magazines, cylinders) with a pixel-circle face."""
    return [bx(x0, y + a, z - h, x1, y + b, z + h, mat) for h, a, b in _circle(r)]


def move(cubes, dx=0.0, dy=0.0, dz=0.0):
    """Translates cubes (and their rotation pivots)."""
    out = []
    for c in cubes:
        c = dict(c)
        c["origin"] = [round(c["origin"][0] + dx, 3), round(c["origin"][1] + dy, 3), round(c["origin"][2] + dz, 3)]
        if "pivot" in c:
            c["pivot"] = [round(c["pivot"][0] + dx, 3), round(c["pivot"][1] + dy, 3), round(c["pivot"][2] + dz, 3)]
        out.append(c)
    return out


def _turn(y, z, deg, pivot):
    """Where (y, z) ends up when GeckoLib rotates by bedrock x-angle [deg] about [pivot] (it rotates by -deg)."""
    a = math.radians(-deg)
    dy, dz = y - pivot[1], z - pivot[2]
    return pivot[1] + dy * math.cos(a) - dz * math.sin(a), pivot[2] + dy * math.sin(a) + dz * math.cos(a)


def tilt(cubes, deg, pivot):
    """Rotates a group of cubes about the X axis at [pivot]; positive [deg]: the lower end swings back (to +Z).
    Already rotated cubes (same axis) are composed: their pivot turns with the group, the box follows the pivot."""
    out = []
    for c in cubes:
        c = dict(c)
        own = c.get("rotation", [0, 0, 0])
        if own[0] and "pivot" in c:
            q = c["pivot"]
            ny, nz = _turn(q[1], q[2], deg, pivot)
            c["origin"] = [c["origin"][0], round(c["origin"][1] + ny - q[1], 3), round(c["origin"][2] + nz - q[2], 3)]
            c["pivot"] = [q[0], round(ny, 3), round(nz, 3)]
            c["rotation"] = [round(own[0] + deg, 3), own[1], own[2]]
        else:
            c["rotation"] = [round(deg, 3), own[1], own[2]]
            c["pivot"] = [round(v, 3) for v in pivot]
        out.append(c)
    return out


def strip(cubes):
    """Cubes without generator-only keys, ready for a .geo.json."""
    return [{k: v for k, v in c.items() if k != "mat"} for c in cubes]


# ------------------------------------------------------------------------------------------- common parts
def trigger_guard(z, y, length=2.4, mat="metal"):
    """Trigger and guard below the receiver at grip front [z], receiver bottom [y]."""
    return [sym(0.25, y - 1.3, z - length, y - 1.0, z, mat), sym(0.25, y - 1.3, z - length, y, z - length + 0.3, mat),
            sym(0.12, y - 1.0, z - length * 0.55, y, z - length * 0.4, "black", rot=(-15, 0, 0))]


def pistol_grip(z, y_top, length=4.2, depth=1.8, rake=18, mat="polymer", w=0.8):
    """Angled pistol grip hanging below the receiver; [z] is its front edge at the top."""
    grip = [sym(w, y_top - length, z, y_top, z + depth, mat), sym(w * 0.9, y_top - length - 0.2, z + 0.1, y_top - length + 0.4, z + depth + 0.2, mat)]
    return tilt(grip, rake, (0, y_top, z + depth / 2))


def iron_post(z, y, h=1.2, mat="black"):
    return [sym(0.12, y, z - 0.15, y + h, z + 0.15, mat)]


# ------------------------------------------------------------------------------------------- magazines
# Magazine frame: feed lips centred at the origin (y = 0), body hanging down (-Y), front of the magazine at -Z.
def _segments(lengths, angles, depth, width, mat, ribs=None):
    """A (curved) magazine body: segments hanging down, each bent forward a bit more (angles in degrees, bottom
    forward). Returns the cubes and where the bottom ends: (y, z, total angle)."""
    out, y, z, a = [], 0.0, 0.0, 0.0
    for length, extra in zip(lengths, angles):
        a += extra
        piece = [sym(width, y - length, z - depth / 2, y, z + depth / 2, mat)]
        if ribs:
            piece.append(sym(width + 0.05, y - length * 0.7, z - depth * 0.3, y - length * 0.3, z + depth * 0.1, ribs))
        out += tilt(piece, -a, (0, y, z)) if a else piece
        y, z = _turn(y - length, z, -a, (0, y, z))
    return out, (y, z, a)


def _plate(at, width, depth, mat):
    """Baseplate at the end of a (curved) magazine body."""
    y, z, a = at
    return tilt([sym(width, y - 0.35, z - depth / 2, y, z + depth / 2, mat)], -a, (0, y, z)) if a else [sym(width, y - 0.35, z - depth / 2, y, z + depth / 2, mat)]


def magazine(kind, rounds=30, mat="steel", plate="black"):
    """Magazine body by kind; [rounds] scales its length. See module docstring for the frame."""
    if kind == "stanag":  # AR-15 aluminium magazine: straight top, gentle curve at the bottom, ribbed
        length = 3.4 + rounds * 0.16
        body, end = _segments([length * 0.62, length * 0.38], [0, 12], 2.6, 0.55, mat, ribs="metal")
        return body + _plate(end, 0.62, 2.8, plate) + [sym(0.5, 0, -1.1, 0.25, 1.0, "brass")]
    if kind == "ak":  # strongly curved, ribbed steel
        length = 3.0 + rounds * 0.25
        body, end = _segments([length * 0.3, length * 0.35, length * 0.35], [5, 12, 13], 2.8, 0.6, mat, ribs="black")
        return body + _plate(end, 0.65, 2.9, plate) + [sym(0.5, 0, -1.2, 0.25, 1.1, "brass")]
    if kind == "smg_curved":  # MP5: slightly curved
        length = 3.0 + rounds * 0.18
        body, end = _segments([length * 0.5, length * 0.5], [4, 10], 1.9, 0.5, mat)
        return body + _plate(end, 0.55, 2.0, plate)
    if kind == "stick":  # straight stick (Uzi, Thompson, Sten, MP40, SCAR, M14)
        length = 3.0 + rounds * 0.2
        return [sym(0.55, -length, -1.1, 0, 1.1, mat), sym(0.6, -length - 0.3, -1.2, -length, 1.2, plate), sym(0.45, 0, -0.9, 0.25, 0.9, "brass")]
    if kind == "pistol":  # pistol magazine (goes into the grip): baseplate, brass at the top
        length = 2.6 + rounds * 0.12
        return [sym(0.5, -length, -0.8, 0, 0.8, mat), sym(0.6, -length - 0.35, -0.9, -length, 1.05, plate), sym(0.4, 0, -0.6, 0.25, 0.6, "brass")]
    if kind == "rifle_box":  # short box (bolt-action, DMR, Barrett)
        length = 1.6 + rounds * 0.3
        return [sym(0.6, -length, -1.6, 0, 1.6, mat), sym(0.65, -length - 0.3, -1.7, -length, 1.7, plate), sym(0.45, 0, -1.2, 0.25, 1.2, "brass")]
    if kind == "drum":  # round drum with a feed tower
        r = 2.2 + rounds * 0.012
        return [sym(0.5, -1.6, -1.0, 0, 1.0, mat)] + disc(-1.1, 1.1, -1.6 - r, 0.4, r, mat) + disc(-1.2, 1.2, -1.6 - r, 0.4, r * 0.35, plate)
    if kind == "twin_drum":  # Beta C-Mag: two drums side by side
        r = 2.0
        return [sym(0.5, -1.4, -1.0, 0, 1.0, mat)] + disc(-2.4, -0.4, -1.4 - r, 0, r, mat) + disc(0.4, 2.4, -1.4 - r, 0, r, mat)
    if kind == "box":  # LMG ammo box / soft pouch
        w = 1.2 + rounds * 0.004
        return [bx(-w * 2.2, -4.6, -2.6, -0.2, 0, 2.6, mat), bx(-w * 2.3, -4.9, -2.7, -0.1, -4.6, 2.7, plate),
                sym(0.3, -1.0, -0.8, 0.3, 0.8, "brass", x=-0.1)]
    if kind == "p90":  # translucent top magazine lying along the gun
        return [sym(1.0, 0, -6.0, 0.9, 6.0, "glass"), sym(0.8, 0.1, -5.8, 0.5, 5.8, "brass"), sym(1.05, 0, 5.4, 1.0, 6.2, "black")]
    if kind == "loader":  # revolver speedloader: a ring of rounds
        return [sym(1.3, -0.6, -1.3, 0, 1.3, "black")] + [bx(x - 0.3, 0, z - 0.3, x + 0.3, 1.2, z + 0.3, "brass") for x, z in
                                                          ((-0.8, -0.5), (0.8, -0.5), (0, -1.0), (-0.8, 0.5), (0.8, 0.5), (0, 1.0))]
    if kind == "side":  # Sten: a straight stick sticking out to the left side
        length = 3.0 + rounds * 0.2
        return [bx(-length, -0.55, -1.1, 0, 0.55, 1.1, mat), bx(-length - 0.3, -0.6, -1.2, -length, 0.6, 1.2, plate)]
    if kind == "pan":  # DP-28: flat pan lying on top of the gun
        r = 3.6
        return [bx(-h, 0.2, a, h, 1.1, b, mat) for h, a, b in _circle(r)] + [bx(-h, 1.1, a, h, 1.4, b, plate) for h, a, b in _circle(r * 0.35)]
    raise ValueError(kind)


def mirror_y(cubes):
    """Flips cubes upside down (a magazine standing up out of the gun, e.g. the Bren's top magazine)."""
    out = []
    for c in cubes:
        c = dict(c)
        c["origin"] = [c["origin"][0], round(-(c["origin"][1] + c["size"][1]), 3), c["origin"][2]]
        if "pivot" in c:
            c["pivot"] = [c["pivot"][0], round(-c["pivot"][1], 3), c["pivot"][2]]
        if "rotation" in c:
            c["rotation"] = [-c["rotation"][0], c["rotation"][1], c["rotation"][2]]
        out.append(c)
    return out


def placed(cubes, at, rake=0.0, upward=False):
    """A magazine put into a gun: its feed lips at [at] = (y, z), tilted by [rake] (+ = bottom back); [upward] for
    magazines standing up out of the top of the gun."""
    y, z = at
    out = move(mirror_y(cubes) if upward else cubes, 0, y, z)
    return tilt(out, rake, (0, y, z)) if rake else out


# ------------------------------------------------------------------------------------------- guns
def gun(parts, moving, well, sight, muzzle, rhand, lhand, rake=0.0, under=None, iron=(), round_bone=False, upward=False):
    """What a gun builder returns. [well] = (y, z) where a magazine's feed lips sit, [rake] its tilt (+ = bottom back);
    [sight] = (top y, z) of the sight rail, [muzzle] = (y, z), [under] = (y, z) of the underbarrel mount."""
    return dict(parts=parts, moving=moving, well=well, rake=rake, sight=sight, muzzle=muzzle, under=under, iron=list(iron),
                rhand=rhand, lhand=lhand, round=round_bone, upward=upward)


def _ar15(gid, f):
    """AR-15 family: M4A1 (carbine, collapsible stock), M16A4 (rifle length, A2 stock)."""
    carbine = gid == "m4a1"
    front = -24.0 if carbine else -28.8
    fsb = -15.4 if carbine else -19.8
    p = [*tube(front + 1.0, -6.8, 7.5, 0.4), *tube(front, front + 1.2, 7.5, 0.55, "black"),  # barrel, A2 flash hider
         sym(0.12, 7.25, front + 0.2, 7.75, front + 0.9, "polymer"),  # flash hider slot
         sym(0.55, 6.9, fsb - 0.6, 8.3, fsb + 0.5), sym(0.3, 6.2, fsb - 0.3, 6.9, fsb + 0.3),  # front sight base, bayonet lug
         *tube(fsb + 0.6, -7.2, 7.5, 1.25, "polymer"), sym(1.35, 6.1, -7.4, 8.9, -6.8),  # handguard, delta ring
         *[sym(1.3, 8.6, z, 8.75, z + 0.35, "black") for z in [fsb + 1.3 + i * 1.1 for i in range(int((-8 - fsb) / 1.1))]],  # vents
         sym(1.05, 6.9, -6.8, 9.0, 1.2), sym(0.7, 9.0, -6.6, 9.45, 1.0, "black"),  # upper, flat-top rail
         *[sym(0.75, 9.25, z, 9.5, z + 0.25, "metal") for z in [-6.3 + i * 0.7 for i in range(11)]],  # rail slots
         bx(1.05, 7.3, -3.6, 1.12, 8.4, -0.7, "black"),  # ejection port cover
         bx(0.9, 7.7, -0.6, 1.55, 8.4, 0.5, "metal"), bx(1.55, 7.85, -0.45, 1.75, 8.25, 0.35, "steel"),  # forward assist
         sym(0.45, 8.9, 1.0, 9.35, 1.9, "black"),  # charging handle
         sym(1.0, 5.0, -6.4, 6.9, 1.6), sym(1.15, 3.9, -5.6, 5.1, -2.6),  # lower, flared mag well
         bx(-1.08, 5.6, -0.6, -1.0, 6.2, 0.6, "black"),  # mag release / selector
         *trigger_guard(0.4, 5.0), *pistol_grip(0.5, 5.0, 4.1, 1.75, 20, "polymer")]
    if carbine:  # buffer tube + collapsible stock
        p += [*tube(1.6, 9.2, 7.7, 0.6, "black"), sym(0.95, 6.6, 6.6, 9.0, 10.6, "polymer"),  # buffer tube, stock body
              *tilt([sym(0.9, 4.4, 6.9, 6.8, 10.6, "polymer")], 14, (0, 6.8, 6.9)),  # sloped underside
              sym(0.98, 4.0, 10.4, 9.1, 11.2, "black")]
    else:  # fixed A2 stock, deeper at the butt
        p += [sym(0.95, 6.0, 1.6, 9.0, 12.0, "polymer"), *tilt([sym(0.93, 3.6, 3.0, 6.4, 12.4, "polymer")], -8, (0, 6.2, 3.0)),
              sym(1.0, 3.4, 12.2, 9.1, 13.0, "black")]
    iron = [sym(0.5, 9.45, 0.0, 10.5, 0.9, "black"), sym(0.3, 10.5, 0.1, 10.9, 0.8, "black"),  # rear sight (BUIS)
            sym(0.25, 8.3, fsb - 0.4, 10.0, fsb + 0.1, "metal"), sym(0.4, 9.7, fsb - 0.45, 10.0, fsb + 0.15, "metal"),  # front sight tower
            *iron_post(fsb - 0.15, 10.0, 0.7)]
    bolt = ("bolt", [bx(1.0, 7.4, -3.4, 1.2, 8.3, -0.9, "steel"), sym(0.45, 8.9, 1.0, 9.35, 1.9, "black")])
    return gun(p, bolt, (6.0, -4.1), (9.45, -2.5), (7.5, front), rhand=(0, 2.8, 2.2), lhand=(0, 6.2, -10.5),
               under=(6.25, -11.0 if carbine else -14.0), iron=iron)


def _m249():
    front = -27.5
    p = [*tube(front + 1, -7, 7.5, 0.5), *tube(front, front + 1.1, 7.5, 0.6, "black"), *tube(-16, -11, 7.5, 0.75, "metal"),  # barrel, gas block
         sym(0.3, 7.5, -14.6, 10.4, -13.2, "black"), sym(0.3, 10.2, -15.5, 10.7, -11.5, "black"),  # carry handle on the barrel
         sym(1.35, 5.2, -11.5, 8.7, -6.8, "polymer"), sym(1.35, 5.2, -7.0, 9.6, 3.0), sym(1.3, 9.6, -6.0, 10.4, 2.0),  # handguard, receiver, feed cover
         *[sym(1.36, 6.0 + i * 0.8, -6.4, 6.3 + i * 0.8, 2.6, "black") for i in range(3)],
         bx(1.35, 7.6, -5.4, 1.6, 8.3, -3.0, "steel"),  # charging handle
         *[bx(sx * 1.0, 4.4, -22.0, sx * 1.4, 5.0, -12.0, "black") for sx in (-1, 1)],  # folded bipod legs
         *trigger_guard(0.4, 5.2), *pistol_grip(0.5, 5.2, 4.0, 1.75, 18, "polymer"),
         *tube(3.0, 8.5, 7.6, 0.55, "black"), sym(0.9, 5.4, 7.4, 8.8, 11.6, "polymer"), sym(0.95, 5.0, 11.6, 9.0, 12.4, "black")]
    iron = [sym(0.5, 10.4, 1.0, 11.4, 1.8, "black"), *iron_post(-25.8, 8.0, 1.5)]
    return gun(p, ("bolt", [bx(1.35, 7.6, -5.4, 1.6, 8.3, -3.0, "steel")]), (6.4, -3.6), (10.4, -1.0), (7.5, front),
               rhand=(0, 2.8, 2.4), lhand=(0, 5.4, -9.5), under=(5.2, -9.0), iron=iron)


def _ak(f):
    front = -25.0
    p = [*tube(front + 1.0, -14.4, 7.4, 0.38), *tube(front, front + 1.2, 7.4, 0.55, "black"),  # barrel, slant brake
         sym(0.5, 6.8, -22.8, 8.1, -21.6), sym(0.45, 8.1, -22.5, 9.4, -22.2), sym(0.45, 8.1, -21.9, 9.4, -21.6),  # front sight block, ears
         sym(0.55, 7.6, -15.2, 9.1, -14.2), *tube(-14.4, -7.6, 8.75, 0.42),  # gas block, gas tube
         sym(0.78, 8.4, -14.2, 9.45, -8.6, f), sym(1.15, 5.9, -14.4, 8.3, -8.2, f),  # upper and lower handguard
         *[sym(1.17, 6.4, z, 7.9, z + 0.4, "black") for z in (-13.3, -11.8, -10.3)],  # handguard grooves
         sym(0.6, 8.2, -8.3, 9.1, -6.6), sym(0.55, 9.1, -8.0, 9.4, -6.8, "black"),  # rear sight block + leaf
         sym(1.05, 5.6, -6.6, 8.3, 4.2), *tube(-6.4, 4.3, 8.3, 0.85),  # receiver, rounded dust cover
         bx(1.05, 6.6, -4.8, 1.13, 7.2, 1.8, "metal"),  # selector lever
         *trigger_guard(0.2, 5.6), *pistol_grip(0.7, 5.6, 3.9, 1.6, 22, f),
         *tilt([sym(0.88, 3.4, 4.0, 7.9, 13.6, f)], -9, (0, 7.9, 4.0)),  # drooping stock
         *tilt([sym(0.95, 2.4, 13.4, 8.1, 14.0, "metal")], -9, (0, 7.9, 4.0))]
    iron = [*iron_post(-22.05, 8.1, 1.1)]
    return gun(p, ("bolt", [bx(1.05, 7.4, -1.6, 1.9, 7.95, -0.9, "steel")]), (5.8, -4.4), (9.15, -1.0), (7.4, front),
               rhand=(0, 2.9, 2.6), lhand=(0, 5.5, -11.0), under=(5.9, -11.0), iron=iron)


def _pkm():
    front = -32.0
    p = [*tube(front + 1, -8, 7.4, 0.45), *tube(front, front + 1.4, 7.4, 0.55, "black"),
         sym(0.5, 6.6, -14.5, 8.1, -13.5), *tube(-14, -7.5, 6.2, 0.45),  # gas block and piston tube under the barrel
         sym(0.3, 7.5, -10.6, 9.6, -9.6, "black"), sym(0.3, 9.4, -11.5, 9.9, -8.7, "wood"),  # carry handle
         sym(1.15, 5.4, -7.5, 8.6, 3.5), sym(1.2, 8.6, -6.5, 9.4, 2.5),  # receiver, feed cover
         *[bx(sx * 1.0, 4.6, -20.0, sx * 1.4, 5.1, -9.5, "black") for sx in (-1, 1)],
         *trigger_guard(0.6, 5.4), *pistol_grip(0.7, 5.4, 3.8, 1.6, 15, "wood"),
         sym(0.9, 5.6, 3.5, 8.4, 6.0, "wood"), sym(0.9, 6.6, 6.0, 8.4, 12.0, "wood"), sym(0.9, 3.4, 6.0, 4.6, 12.0, "wood"),  # skeleton stock
         sym(0.9, 3.4, 11.4, 8.6, 12.8, "wood"), sym(0.92, 4.4, 7.0, 5.8, 8.0, "wood")]
    for i in range(8):
        p += tube(-27 + i * 1.4, -26.6 + i * 1.4, 7.4, 0.62)
    return gun(p, ("bolt", [bx(1.15, 6.8, -1.5, 1.9, 7.4, -0.9, "steel")]), (5.6, -2.6), (9.4, -1.5), (7.4, front),
               rhand=(0, 2.8, 2.7), lhand=(0, 6.0, -10.0), iron=[sym(0.5, 9.4, -3.6, 10.0, -2.8, "black"), *iron_post(-30.6, 8.0, 1.4)])


def _scar_h():
    front = -25.0
    p = [*tube(front + 1, -12, 7.6, 0.45), *tube(front, front + 1.3, 7.6, 0.6, "black"),
         sym(1.2, 6.2, -14.5, 9.6, 2.5, "tan"), sym(0.7, 9.6, -14.5, 10.1, 2.4, "black"),  # monolithic upper with full rail
         *[sym(0.75, 9.85, z, 10.15, z + 0.25, "metal") for z in [-14.2 + i * 0.75 for i in range(22)]],
         bx(-1.2, 8.3, -6.0, -1.5, 8.9, -4.6, "black"),  # reciprocating charging handle (left)
         sym(1.2, 4.6, -6.5, 6.2, 2.5, "tan"), sym(1.25, 3.0, -5.2, 4.7, -2.4, "tan"),  # lower, mag well
         *trigger_guard(0.6, 4.6), *pistol_grip(0.7, 4.6, 3.9, 1.7, 18, "polymer"),
         sym(1.0, 5.6, 2.5, 9.6, 4.6, "tan"), sym(0.8, 6.8, 4.6, 8.4, 9.5, "polymer"),  # folding stock hinge, side rails
         sym(1.0, 4.4, 9.5, 9.4, 12.5, "tan"), sym(0.95, 7.2, 6.0, 9.4, 9.5, "tan"), sym(1.05, 4.2, 12.5, 9.6, 13.1, "black")]
    iron = [sym(0.5, 10.1, 1.0, 11.2, 1.8, "black"), sym(0.4, 10.1, -14.0, 11.3, -13.3, "black")]
    return gun(p, ("bolt", [bx(-1.2, 8.3, -6.0, -1.5, 8.9, -4.6, "black")]), (4.9, -3.8), (10.1, -2.0), (7.6, front),
               rhand=(0, 2.6, 2.8), lhand=(0, 5.9, -10.0), under=(6.2, -10.5), iron=iron)


def _m14():
    front = -29.0
    p = [*tube(front + 1, -12, 7.6, 0.45), *tube(front, front + 1.5, 7.6, 0.62, "black"),
         sym(1.2, 6.0, -15.5, 9.4, -6.0, "polymer"), sym(0.7, 9.4, -15.5, 9.9, -6.0, "black"),  # railed EBR chassis front
         sym(1.05, 6.2, -6.0, 8.9, 3.0, "metal"), sym(0.8, 8.9, -5.5, 9.4, 2.0, "black"),  # receiver, scope rail
         bx(1.05, 7.6, -5.4, 1.8, 8.2, -4.4, "steel"),  # op rod handle
         sym(1.2, 4.6, -6.0, 6.2, 3.0, "polymer"), *trigger_guard(1.0, 4.6), *pistol_grip(1.1, 4.6, 3.9, 1.7, 18, "polymer"),
         *tube(3.0, 9.0, 7.6, 0.6, "black"), sym(0.95, 5.0, 7.0, 9.4, 11.8, "polymer"), sym(1.0, 4.6, 11.8, 9.6, 12.6, "black"),
         *[bx(sx * 1.0, 4.6, -24.0, sx * 1.4, 5.1, -15.0, "black") for sx in (-1, 1)]]
    iron = [sym(0.45, 9.4, 1.0, 10.4, 1.8, "black"), *iron_post(-27.8, 8.2, 1.4)]
    return gun(p, ("bolt", [bx(1.05, 7.6, -5.4, 1.8, 8.2, -4.4, "steel")]), (6.0, -2.6), (9.4, -1.5), (7.6, front),
               rhand=(0, 2.6, 3.0), lhand=(0, 5.7, -11.0), under=(6.0, -12.0), iron=iron)


def _svd():
    front = -36.0
    p = [*tube(front + 1.5, -12, 7.4, 0.4), *tube(front, front + 1.6, 7.4, 0.55, "black"),
         sym(0.45, 6.9, -27.5, 8.2, -26.6), sym(0.4, 8.2, -27.3, 9.0, -26.9),  # front sight
         sym(1.05, 6.0, -14.0, 8.6, -5.0, "wood"), *[sym(1.07, 6.6, z, 8.0, z + 0.35, "black") for z in (-13, -11.5, -10, -8.5, -7)],  # vented handguard
         sym(1.0, 5.8, -5.0, 8.7, 4.0), *tube(-5, 4, 8.7, 0.8), bx(1.0, 7.3, -1.2, 1.7, 7.9, -0.4, "steel"),  # receiver, dust cover
         *trigger_guard(0.0, 5.8), sym(0.85, 5.0, 4.0, 8.4, 6.5, "wood"),
         sym(0.85, 7.2, 6.5, 8.4, 12.8, "wood"), sym(0.85, 2.0, 6.5, 3.6, 12.8, "wood"), sym(0.85, 2.0, 3.3, 5.0, 5.0, "wood"),  # thumbhole stock
         sym(0.85, 5.5, 8.5, 7.2, 11.5, "wood"), sym(0.9, 2.0, 12.5, 8.6, 13.4, "black")]
    pso = [sym(0.35, 8.7, -2.2, 9.6, 1.4, "black"), *tube(-5.5, 3.5, 10.7, 0.75, "polymer", x=-0.4),
           *tube(-6.6, -5.4, 10.7, 1.0, "polymer", x=-0.4), *tube(3.4, 4.8, 10.7, 0.9, "polymer", x=-0.4), sym(0.35, 11.3, -1.5, 11.9, -0.7, "polymer", x=-0.4)]
    return gun(p, ("bolt", [bx(1.0, 7.3, -1.2, 1.7, 7.9, -0.4, "steel")]), (5.8, -2.2), (9.6, -1.0), (7.4, front),
               rhand=(0, 2.9, 4.2), lhand=(0, 5.6, -9.5), iron=pso)


def _bolt_rifle(gid, f):
    """M24 (heavy synthetic stock) and AWM (thumbhole stock, muzzle brake)."""
    awm = gid == "awm"
    front = -38.0 if awm else -35.0
    p = [*tube(front + (2.2 if awm else 0), -10, 7.5, 0.5), *tube(-10, 3.5, 7.7, 0.95, "metal"),  # barrel, receiver
         bx(0.95, 7.2, 1.6, 2.4, 7.7, 2.2, "metal"), bx(2.2, 6.5, 1.5, 2.9, 7.3, 2.4, "black"),  # bolt handle + knob
         *trigger_guard(1.2, 5.3), sym(1.15, 5.0, -21.0, 6.9, -9.0, f), sym(1.2, 4.6, -9.0, 6.9, 3.0, f)]
    if awm:
        p += [*tube(front, front + 2.2, 7.5, 0.75, "black"), sym(0.3, 7.6, front + 0.6, 7.9, front + 1.8, "polymer"),  # brake
              sym(1.0, 2.4, 3.0, 5.0, 4.6, f), sym(1.0, 6.4, 3.0, 8.0, 13.0, f), sym(1.0, 2.4, 7.0, 4.0, 13.0, f),  # thumbhole stock
              sym(1.0, 4.0, 10.5, 6.4, 13.0, f), sym(0.6, 8.0, 7.0, 9.0, 10.5, "black"), sym(1.05, 2.4, 13.0, 8.2, 13.8, "black"),
              *[bx(sx * 1.0, 4.4, -20.0, sx * 1.4, 4.9, -10.0, "black") for sx in (-1, 1)]]
    else:
        p += [*tilt([sym(1.1, 2.0, 3.0, 5.0, 5.0, f)], 18, (0, 5.0, 3.0)), sym(1.15, 3.8, 3.0, 7.4, 12.5, f),
              sym(1.15, 2.6, 7.0, 4.0, 12.5, f), sym(1.2, 2.5, 12.5, 7.6, 13.3, "black")]
    bolt = ("bolt", [bx(0.95, 7.2, 1.6, 2.4, 7.7, 2.2, "metal"), bx(2.2, 6.5, 1.5, 2.9, 7.3, 2.4, "black")])
    return gun(p, bolt, (5.0, -2.5), (8.65, -3.0), (7.5, front), rhand=(0, 2.8, 3.6), lhand=(0, 4.8, -13.0), iron=[])


def _barrett():
    front = -45.0
    p = [*tube(front + 3.5, -14, 7.6, 0.65), *[c for c in tube(front, front + 3.5, 7.6, 1.5, "steel")],  # barrel, double-chamber brake
         bx(-1.55, 7.0, front + 0.6, 1.55, 8.2, front + 1.5, "black"), bx(-1.55, 7.0, front + 2.0, 1.55, 8.2, front + 2.9, "black"),
         sym(1.4, 5.6, -14.0, 9.2, 9.0, "metal"), sym(0.8, 9.2, -12.0, 9.8, 6.0, "black"),  # receiver, rail
         sym(0.3, 9.8, -7.0, 11.0, -6.2, "black"), sym(0.3, 10.6, -7.0, 11.0, -2.0, "black"),  # carry handle
         bx(1.4, 7.4, -4.0, 2.2, 8.3, -2.8, "steel"),  # charging handle
         *[bx(sx * 1.0, 4.6, -28.0, sx * 1.5, 5.2, -14.0, "black") for sx in (-1, 1)],
         *trigger_guard(1.0, 5.6), *pistol_grip(1.1, 5.6, 4.0, 1.8, 18, "polymer"),
         sym(1.1, 4.6, 9.0, 9.0, 15.0, "polymer"), sym(0.9, 2.6, 13.0, 4.6, 15.0, "black"), sym(1.2, 2.6, 15.0, 9.4, 15.8, "black")]
    return gun(p, ("bolt", [bx(1.4, 7.4, -4.0, 2.2, 8.3, -2.8, "steel")]), (5.6, -4.5), (9.8, -2.0), (7.6, front),
               rhand=(0, 3.0, 3.2), lhand=(0, 5.0, -16.0), iron=[])


def _pump_shotgun(gid):
    spas = gid == "spas12"
    front = -30.0 if spas else -32.0
    f = "polymer" if spas else "wood"
    p = [*tube(front, -3, 7.6, 0.6), *tube(front + 4.5, -10, 6.2, 0.5, "black" if spas else "metal"),  # barrel, magazine tube
         sym(1.05, 5.4, -3.0, 8.8, 5.0, "black" if spas else "metal"), *trigger_guard(1.4, 5.4)]
    if spas:
        p += [sym(1.0, 7.0, -18.0, 8.6, -4.0, "black"), *[sym(1.02, 7.2, z, 8.4, z + 0.5, "metal") for z in (-17, -15, -13, -11, -9, -7, -5)],  # heat shield
              *pistol_grip(1.6, 5.4, 4.0, 1.7, 18, "polymer"), sym(0.25, 8.2, 5.0, 8.6, 13.5), sym(0.25, 4.6, 5.0, 5.0, 13.5),  # folding stock
              sym(0.25, 4.6, 13.0, 8.6, 13.5), sym(0.8, 4.8, 13.4, 8.4, 14.0, "black"), *tube(-13.5, -9.5, 7.6, 0.7, "black")]
    else:
        p += [*tilt([sym(0.85, 2.0, 5.0, 5.4, 7.0, f)], 30, (0, 5.4, 5.0)), *tilt([sym(0.95, 3.0, 5.0, 7.8, 14.0, f)], -6, (0, 7.8, 5.0)),
              *tilt([sym(1.0, 1.8, 13.8, 8.0, 14.5, "black")], -6, (0, 7.8, 5.0))]
    pump = [*tube(-16.0, -10.0, 6.2, 0.95, f), *[sym(0.98, 5.4 + i * 0.4, -15.5, 5.5 + i * 0.4, -10.5, "black") for i in range(3)]]
    return gun(p, ("pump", pump), (6.2, front + 3.5), (8.8, 2.0), (7.6, front), rhand=(0, 2.6, 5.0 if not spas else 3.6),
               lhand=(0, 5.2, -13.0), iron=[sym(0.2, 8.2, front + 0.5, 8.7, front + 1.0, "steel")])


def _aa12():
    front = -25.0
    p = [*tube(front, -12, 7.4, 0.6), *tube(front, front + 1.2, 7.4, 0.75, "black"),
         sym(1.35, 4.8, -12.5, 9.2, 7.0, "polymer"), sym(0.7, 9.2, -10.0, 9.7, 4.5, "black"), sym(0.3, 9.7, -2.0, 10.8, -1.4, "black"),
         *[sym(1.37, 7.2, z, 8.6, z + 0.5, "black") for z in (-11, -9.5, -8)],  # vents
         *trigger_guard(1.0, 4.8), *pistol_grip(1.1, 4.8, 3.8, 1.8, 15, "polymer"),
         sym(1.2, 4.6, 7.0, 9.0, 12.0, "polymer"), sym(1.25, 4.2, 12.0, 9.2, 12.6, "black")]
    return gun(p, ("bolt", [bx(1.35, 7.8, -6.0, 1.8, 8.4, -5.0, "black")]), (4.9, -4.0), (9.7, 0.0), (7.4, front),
               rhand=(0, 2.4, 2.6), lhand=(0, 4.6, -9.0), under=(4.8, -9.5), iron=[sym(0.5, 9.7, 2.0, 10.6, 2.8, "black")])


def _pistol(gid):
    """Glock 17 (polymer), M1911 (steel, walnut grips, hammer), Desert Eagle (big, triangular barrel)."""
    glock, deagle = gid == "glock17", gid == "deagle"
    front = {"glock17": -6.0, "m1911": -6.4, "deagle": -8.6}[gid]
    back = {"glock17": 2.8, "m1911": 3.0, "deagle": 3.1}[gid]
    top = {"glock17": 6.5, "m1911": 6.4, "deagle": 7.0}[gid]
    sh = {"glock17": 1.35, "m1911": 1.2, "deagle": 1.6}[gid]
    w = 0.62 if not deagle else 0.75
    slide_mat, frame_mat = ("black", "polymer") if glock else ("steel", "steel")
    frame_top = top - sh
    p = [sym(w * 0.95, frame_top - 0.9, front + 0.3, frame_top, back - 0.4, frame_mat),  # frame / dust cover
         *trigger_guard(-0.6, frame_top - 0.9, 2.1, frame_mat),
         *pistol_grip(-0.8, frame_top - 0.6, 4.4 if not deagle else 4.6, 2.6, 18, "polymer" if glock else "wood", w * 0.95)]
    if not glock:
        p += [*tilt([sym(w * 0.95, frame_top - 4.5, -0.75, frame_top - 0.5, -0.45, "steel")], 18, (0, frame_top - 0.6, 0.5)),  # grip frame
              sym(0.25, frame_top - 0.4, back - 0.3, frame_top + 0.6, back + 0.3, "steel")]  # grip safety / tang
        p += [sym(0.25, top - 0.2, back - 0.5, top + 0.5, back, "black")]  # hammer
    slide = [sym(w, frame_top, front, top, back, slide_mat), *[sym(w + 0.02, frame_top + 0.25 + i * 0.32, back - 1.4, frame_top + 0.4 + i * 0.32, back - 0.3, "polymer")
                                                              for i in range(3)],  # serrations
             bx(w, frame_top + 0.4, front + 3.0, w + 0.02, top - 0.3, front + 5.0, "black")]  # ejection port
    if deagle:
        slide += [sym(0.5, top, front, top + 0.3, -1.0, "black")]  # rail on the barrel
    iron = [sym(0.45, top, back - 0.8, top + 0.5, back - 0.3, "black"), sym(0.12, top, front + 0.4, top + 0.45, front + 0.8, "black")]
    return gun(p, ("slide", slide), (frame_top - 1.0, 0.5), (top, 0.0), (frame_top + sh * 0.45, front), rake=18,
               rhand=(0, 2.6, 1.4), lhand=(-0.4, 2.1, 0.6), iron=iron)


def _revolver():
    p = [*tube(-10.0, -3.4, 6.9, 0.5, "steel"), sym(0.45, 5.9, -10.0, 6.6, -3.4, "steel"),  # barrel, full underlug
         sym(0.3, 7.3, -10.0, 7.6, -3.4, "steel"),  # rib
         sym(0.75, 5.0, -3.4, 7.7, 1.8, "steel"), *trigger_guard(-0.4, 5.0, 2.0, "steel"),
         *pistol_grip(-0.2, 5.4, 4.2, 2.3, 25, "wood", 0.72), sym(0.25, 7.1, 1.4, 7.9, 2.1, "black")]
    cylinder = [*tube(-3.2, -0.2, 6.6, 1.25, "steel"), *[sym(0.15, 5.4, -3.0, 7.8, -0.4, "black", x=x) for x in (-1.05, 1.05)]]
    return gun(p, ("hammer", [sym(0.25, 7.4, 1.4, 8.5, 2.0, "steel")]), (6.6, -1.7), (7.6, -1.0), (6.9, -10.0),
               rhand=(0, 2.6, 1.9), lhand=(-0.4, 2.0, 1.0), iron=[sym(0.15, 7.6, -9.6, 8.3, -9.1, "steel")], round_bone=False) | {"cylinder": cylinder}


def _mp5():
    front = -17.5
    p = [*tube(front + 0.9, -11, 7.6, 0.35), *tube(front, front + 0.9, 7.6, 0.5, "black"),  # barrel, tri-lug
         *tube(-16.4, -14.6, 9.0, 0.75, "black"), *iron_post(-15.5, 8.6, 1.0),  # front sight hood
         *tube(-16.4, -4.0, 8.8, 0.32, "black"), bx(-0.35, 8.6, -14.6, -1.3, 9.0, -13.8, "black"),  # cocking tube, handle
         sym(1.15, 5.8, -11.5, 7.5, -4.6, "polymer"), *tube(-11.0, 4.0, 7.7, 0.95, "black"),  # handguard, receiver
         sym(0.95, 4.6, -3.0, 6.7, 3.2, "polymer"), *trigger_guard(0.2, 4.6), *pistol_grip(0.4, 4.6, 3.8, 1.6, 15, "polymer"),
         sym(0.85, 4.6, 4.0, 8.6, 6.0, "polymer"), *tilt([sym(0.8, 4.0, 6.0, 8.3, 12.6, "polymer")], -4, (0, 8.3, 6.0)),
         sym(0.9, 3.6, 12.4, 8.6, 13.1, "black")]
    iron = [*tube(1.6, 2.6, 9.2, 0.55, "black")]
    return gun(p, ("bolt", [bx(-0.35, 8.6, -14.6, -1.3, 9.0, -13.8, "black")]), (6.7, -3.6), (9.0, 0.5), (7.6, front), rake=-12,
               rhand=(0, 2.5, 2.2), lhand=(0, 5.4, -8.0), under=(5.8, -8.0), iron=iron)


def _uzi():
    front = -12.0
    p = [*tube(front, -9.0, 7.0, 0.4), *tube(-9.5, -8.6, 7.0, 0.7, "black"),  # barrel, barrel nut
         sym(1.1, 5.6, -8.8, 8.8, 4.6), *[sym(1.12, 6.0 + i * 0.7, -8.4, 6.25 + i * 0.7, 4.2, "black") for i in range(4)],  # ribbed receiver
         sym(0.4, 8.8, -4.0, 9.3, -2.6, "black"),  # cocking knob
         sym(1.0, 4.3, -2.2, 5.6, 3.0, "polymer"), *trigger_guard(0.0, 4.3, 2.0),
         *tilt([sym(0.85, 0.2, 0.2, 4.3, 2.6, "polymer")], 5, (0, 4.3, 1.4)),
         sym(0.2, 6.0, 4.6, 6.4, 11.0, "metal", x=0.8), sym(0.2, 6.0, 4.6, 6.4, 11.0, "metal", x=-0.8), sym(1.0, 5.2, 11.0, 7.4, 11.6, "metal")]
    iron = [sym(0.4, 8.8, 3.2, 9.8, 3.8, "black"), sym(0.4, 8.8, -8.6, 9.8, -8.0, "black"), *iron_post(-8.3, 9.8, 0.3)]
    return gun(p, ("bolt", [sym(0.4, 8.8, -4.0, 9.3, -2.6, "black")]), (4.4, 1.4), (8.8, 0.5), (7.0, front), rake=5,
               rhand=(0, 2.2, 1.4), lhand=(-0.3, 1.6, 1.2), iron=iron)


def _p90():
    p = [sym(1.35, 3.8, -10.0, 7.8, 6.5, "polymer"), sym(1.25, 1.2, 1.5, 3.8, 6.5, "polymer"),  # body, stock
         *tilt([sym(1.2, 1.2, -6.4, 3.8, -5.0, "polymer")], 15, (0, 3.8, -5.7)),  # front of the thumbhole grip
         sym(1.2, 1.2, -5.2, 2.0, 1.5, "polymer"), *tube(-12.0, -10.0, 6.4, 0.4), *tube(-12.6, -11.6, 6.4, 0.5, "black"),
         sym(0.6, 7.8, -6.0, 10.2, 0.8, "black"), *trigger_guard(-3.5, 3.8, 1.6)]
    return gun(p, ("bolt", [bx(1.35, 6.0, -7.0, 1.7, 6.6, -6.0, "black")]), (7.9, -2.5), (10.2, -2.0), (6.4, -12.6),
               rhand=(0, 1.6, -4.2), lhand=(0, 2.0, -7.6), iron=[sym(0.6, 10.2, -3.0, 10.6, 0.5, "black")])


def _thompson():
    front = -20.5
    p = [*tube(front + 1.6, -8, 7.4, 0.5), *tube(front, front + 1.6, 7.4, 0.7, "metal"),  # barrel, Cutts compensator
         *[c for i in range(7) for c in tube(-17.6 + i * 0.95, -17.2 + i * 0.95, 7.4, 0.85, "metal")],  # cooling fins
         sym(1.05, 5.6, -8.0, 8.6, 4.2, "metal"), sym(0.3, 8.6, -4.0, 9.1, -2.8, "steel"),  # receiver, cocking knob
         *tilt([sym(0.55, 2.0, -14.4, 6.6, -12.6, "walnut")], 8, (0, 6.6, -13.5)),  # vertical foregrip
         sym(0.9, 4.6, -1.6, 5.6, 3.0), *trigger_guard(0.4, 4.6), *pistol_grip(0.6, 4.6, 3.8, 1.7, 20, "walnut"),
         *tilt([sym(0.9, 3.0, 4.2, 8.0, 13.5, "walnut")], -10, (0, 8.0, 4.2)), *tilt([sym(0.95, 1.6, 13.3, 8.2, 13.9, "metal")], -10, (0, 8.0, 4.2))]
    iron = [sym(0.4, 8.6, 2.4, 9.8, 3.2, "steel"), *iron_post(-19.4, 8.1, 0.7)]
    return gun(p, ("bolt", [sym(0.3, 8.6, -4.0, 9.1, -2.8, "steel")]), (5.6, -2.9), (8.6, 0.0), (7.4, front),
               rhand=(0, 2.6, 2.4), lhand=(0, 4.0, -13.5), iron=iron)


def _m79():
    p = [*tube(-15.0, -1.0, 7.0, 1.45, "parkerized"), sym(1.25, 5.2, -1.0, 8.6, 2.5), *trigger_guard(1.6, 5.2),
         sym(0.8, 1.8, 2.2, 5.2, 3.8, "wood"), *tilt([sym(1.0, 3.2, 3.8, 7.8, 11.5, "wood")], -5, (0, 7.8, 3.8)),
         sym(1.1, 2.4, 11.0, 8.0, 11.6, "black"), sym(1.25, 5.3, -10.0, 5.9, -2.0, "wood")]
    shell = [*tube(-1.0, 0.2, 7.0, 1.3, "brass")]
    return gun(p, ("breech", [sym(0.3, 8.6, 1.4, 9.3, 2.2, "black")]), (7.0, -0.4), (8.6, -2.0), (7.0, -15.0),
               rhand=(0, 2.8, 3.0), lhand=(0, 4.6, -6.0), iron=[sym(0.6, 8.6, -2.0, 10.0, -1.6, "black")], round_bone=True) | {"round_cubes": shell}


def _rpg7():
    p = [*tube(-13.0, 13.0, 7.0, 0.9, "parkerized"), *tube(12.0, 17.0, 7.0, 1.45, "parkerized"),  # tube, venturi flare
         *tube(-5.0, 4.0, 7.0, 1.15, "wood"),  # wooden heat guard
         *tilt([sym(0.5, 2.6, -3.2, 6.0, -1.8, "wood")], 10, (0, 6.0, -2.5)), *tilt([sym(0.5, 2.6, 1.8, 6.0, 3.2, "wood")], 10, (0, 6.0, 2.5)),
         *trigger_guard(-1.0, 6.0, 1.6), bx(-2.6, 8.0, -2.2, -1.2, 9.6, 1.2, "black"), bx(-2.0, 7.0, -1.8, -1.2, 8.0, 0.8, "metal")]  # PGO-7 optic
    warhead = [*tube(-15.6, -13.0, 7.0, 0.5, "olive"), *tube(-19.5, -15.6, 7.0, 1.7, "olive"), *tube(-21.5, -19.5, 7.0, 1.1, "olive"),
               *tube(-22.8, -21.5, 7.0, 0.5, "olive"), sym(1.9, 6.8, -15.0, 7.2, -13.6, "olive")]
    return gun(p, ("trigger", [sym(0.2, 4.6, -1.4, 5.4, -1.0, "black")]), (7.0, -17.0), (9.6, -1.0), (7.0, -22.8),
               rhand=(0, 3.4, -2.3), lhand=(0, 3.4, 2.7), iron=[sym(0.4, 7.9, -11.0, 9.0, -10.4, "black")], round_bone=True) | {"round_cubes": warhead}


GUNS = {
    "m4a1": lambda g: _ar15("m4a1", g["furniture"]), "m16a4": lambda g: _ar15("m16a4", g["furniture"]), "m249": lambda g: _m249(),
    "ak47": lambda g: _ak(g["furniture"]), "pkm": lambda g: _pkm(), "scar_h": lambda g: _scar_h(), "m14": lambda g: _m14(),
    "svd": lambda g: _svd(), "m24": lambda g: _bolt_rifle("m24", g["furniture"]), "awm": lambda g: _bolt_rifle("awm", g["furniture"]),
    "barrett": lambda g: _barrett(), "m870": lambda g: _pump_shotgun("m870"), "spas12": lambda g: _pump_shotgun("spas12"),
    "aa12": lambda g: _aa12(), "glock17": lambda g: _pistol("glock17"), "m1911": lambda g: _pistol("m1911"),
    "deagle": lambda g: _pistol("deagle"), "revolver": lambda g: _revolver(), "mp5": lambda g: _mp5(), "uzi": lambda g: _uzi(),
    "p90": lambda g: _p90(), "thompson": lambda g: _thompson(), "m79": lambda g: _m79(), "rpg7": lambda g: _rpg7(),
}


# ------------------------------------------------------------------------------------------- WW2 guns
def _wood_rifle(front, butt, wood, *, wrist=True, full=True, nose=None, bore=7.4, bayonet_lug=True):
    """Classic one-piece wooden rifle stock: forend under the barrel to [nose] (default near the muzzle), receiver
    section, straight (or semi-pistol) wrist and a butt that drops towards the back."""
    nose = nose if nose is not None else front + 6
    p = [sym(0.95, bore - 1.9, nose, bore + 0.2, -6.0, wood),  # forend
         sym(0.6, bore + 0.2, nose + 2, bore + 1.0, -6.5, wood) if full else sym(0.0, 0, 0, 0, 0),  # upper handguard
         sym(1.0, bore - 2.4, -6.0, bore + 0.3, 2.0, wood),  # receiver section
         sym(0.75, bore - 3.6, 1.6, bore - 0.4, 4.2, wood) if wrist else sym(0.8, bore - 2.6, 1.6, bore - 0.2, 4.0, wood),  # wrist
         *tilt([sym(0.95, bore - 5.6, 3.8, bore - 0.2, butt, wood)], -6, (0, bore - 0.2, 3.8)),  # butt
         *tilt([sym(1.0, bore - 6.0, butt - 0.4, bore, butt + 0.2, "metal")], -6, (0, bore - 0.2, 3.8)),  # butt plate
         sym(0.98, bore - 1.9, nose - 0.6, bore + 0.4, nose, "metal")]  # nose cap / barrel band
    if bayonet_lug:
        p.append(sym(0.25, bore - 2.4, nose - 0.4, bore - 1.9, nose + 0.6, "metal"))
    return [c for c in p if c["size"][0] > 0]


def _bolt_action(gid):
    """Kar98k (turned-down bolt, hooded front sight), Mosin-Nagant (straight bolt, hex receiver, long), M1903."""
    front, butt = {"kar98k": (-35.0, 13.0), "kar98k_scoped": (-35.0, 13.0), "mosin": (-40.0, 13.6), "m1903": (-35.0, 13.0)}[gid]
    wood = {"mosin": "wood"}.get(gid, "walnut")
    p = [*tube(front, -8.0, 7.4, 0.42, "blued"), *tube(-8.0, 2.4, 7.5, 0.85, "blued"),  # barrel, receiver
         *_wood_rifle(front, butt, wood, wrist=gid != "mosin"), *trigger_guard(1.6, 5.4, 2.2, "blued"),
         sym(0.35, 4.8, -3.6, 5.6, 0.0, "blued")]  # floor plate
    if gid == "mosin":
        p += [sym(0.85, 6.7, -8.0, 8.3, 0.0, "blued")]  # hex receiver
        bolt = [bx(0.85, 7.4, 1.4, 2.6, 7.8, 1.9, "steel"), *tube(2.6, 2.8, 7.6, 0.35, "steel", x=2.6)]
    else:
        bolt = [bx(0.85, 6.9, 1.4, 2.3, 7.4, 1.9, "steel", rot=(0, 0, 35), pivot=(0.85, 7.4, 1.65)), sym(0.35, 5.8, 1.2, 6.6, 2.0, "steel", x=2.5)]
    iron = [sym(0.55, 8.4, -6.0, 8.9, -4.0, "blued"), sym(0.2, 7.9, front + 0.6, 8.9, front + 1.0, "blued")]
    if gid in ("kar98k", "kar98k_scoped"):
        iron.append(sym(0.45, 7.9, front + 0.4, 9.2, front + 1.2, "blued"))  # front sight hood
    scope = []
    if gid == "kar98k_scoped":  # ZF39 on a turret mount
        scope = [sym(0.3, 8.3, -4.0, 9.4, -3.2, "blued"), sym(0.3, 8.3, 0.4, 9.4, 1.2, "blued"), *tube(-6.5, 3.0, 10.2, 0.6, "black"),
                 *tube(-8.0, -6.5, 10.2, 0.9, "black"), *tube(3.0, 4.2, 10.2, 0.75, "black")]
        p += scope
    return gun(p, ("bolt", bolt), (5.6, -1.8), (9.0 if not scope else 11.0, -1.0), (7.4, front),
               rhand=(0, 3.4, 3.4), lhand=(0, 5.2, -12.0), iron=iron if not scope else [])


def _lee_enfield():
    front = -34.0
    p = [*tube(front, -8.0, 7.4, 0.42, "blued"), *tube(-8.0, 2.4, 7.5, 0.85, "blued"),
         sym(0.95, 5.6, -24.0, 7.6, -6.0, "walnut"), sym(0.65, 7.6, -22.0, 8.5, -8.0, "walnut"),  # two-piece forend + top cover
         sym(1.0, 5.0, -6.0, 7.8, 2.0, "walnut"), sym(0.75, 3.4, 1.6, 7.0, 4.0, "walnut"),
         *tilt([sym(0.95, 1.8, 3.8, 7.2, 13.0, "walnut")], -6, (0, 7.2, 3.8)), *tilt([sym(1.0, 1.4, 12.6, 7.4, 13.2, "metal")], -6, (0, 7.2, 3.8)),
         sym(0.98, 5.5, -24.6, 7.8, -24.0, "metal"), *trigger_guard(1.6, 5.0, 2.2, "blued")]
    bolt = [bx(0.85, 7.0, 1.0, 2.3, 7.5, 1.5, "steel", rot=(0, 0, 30), pivot=(0.85, 7.5, 1.25)), sym(0.35, 6.0, 0.8, 6.8, 1.6, "steel", x=2.4)]
    iron = [sym(0.5, 8.4, 0.4, 9.6, 1.2, "blued"), sym(0.45, 7.9, front + 0.4, 9.2, front + 1.2, "blued")]
    return gun(p, ("bolt", bolt), (5.2, -2.2), (9.6, -1.0), (7.4, front), rhand=(0, 3.2, 3.3), lhand=(0, 5.0, -12.0), iron=iron)


def _garand():
    front = -34.5
    p = [*tube(front, -8.0, 7.4, 0.45, "parkerized"), *tube(-8.5, 2.4, 7.5, 0.9, "parkerized"),
         *_wood_rifle(front, 13.8, "walnut", nose=-20.5), sym(0.5, 7.3, front + 0.6, 8.4, front + 1.8, "parkerized"),  # gas cylinder
         bx(0.9, 6.6, -16.0, 1.3, 7.2, -2.0, "parkerized"), bx(0.9, 6.6, -2.5, 1.7, 7.4, -1.4, "parkerized"),  # operating rod + handle
         *trigger_guard(1.8, 5.2, 2.2, "parkerized")]
    iron = [sym(0.6, 8.4, 0.5, 9.8, 1.4, "parkerized"), sym(0.45, 8.4, front + 0.6, 9.4, front + 1.6, "parkerized"),
            *iron_post(front + 1.1, 8.4, 0.8)]
    return gun(p, ("bolt", [bx(0.9, 6.6, -2.5, 1.7, 7.4, -1.4, "parkerized")]), (5.4, -2.0), (9.6, -1.0), (7.4, front),
               rhand=(0, 3.4, 3.4), lhand=(0, 5.2, -12.0), iron=iron)


def _m1_carbine():
    front = -26.0
    p = [*tube(front, -7.0, 7.4, 0.4, "parkerized"), *tube(-7.5, 2.0, 7.5, 0.8, "parkerized"),
         *_wood_rifle(front, 11.0, "walnut", nose=-15.0), bx(0.8, 6.8, -8.0, 1.2, 7.3, -1.0, "parkerized"),
         *trigger_guard(1.4, 5.2, 2.0, "parkerized")]
    iron = [sym(0.5, 8.3, 0.3, 9.4, 1.1, "parkerized"), sym(0.4, 7.8, front + 0.4, 9.0, front + 1.2, "parkerized")]
    return gun(p, ("bolt", [bx(0.8, 7.0, -1.6, 1.6, 7.6, -0.8, "steel")]), (5.4, -2.4), (9.4, -1.0), (7.4, front),
               rhand=(0, 3.2, 3.0), lhand=(0, 5.2, -10.0), iron=iron)


def _g43():
    front = -36.0
    p = [*tube(front, -8.0, 7.4, 0.42, "blued"), *tube(-8.5, 2.4, 7.5, 0.9, "blued"), *tube(-20.0, -10.0, 8.6, 0.35, "blued"),  # gas tube on top
         *_wood_rifle(front, 13.0, "walnut", nose=-21.0), bx(-0.9, 7.2, -1.6, -1.8, 7.8, -0.8, "steel"),  # charging handle (left)
         *trigger_guard(1.6, 5.2, 2.2, "blued")]
    iron = [sym(0.5, 8.4, -7.0, 9.0, -5.0, "blued"), sym(0.45, 7.9, front + 0.4, 9.2, front + 1.2, "blued")]
    return gun(p, ("bolt", [bx(-0.9, 7.2, -1.6, -1.8, 7.8, -0.8, "steel")]), (5.4, -2.2), (9.0, -1.0), (7.4, front),
               rhand=(0, 3.3, 3.4), lhand=(0, 5.2, -12.0), iron=iron)


def _mp40():
    front = -16.5
    p = [*tube(front + 1, -7.0, 7.4, 0.4, "blued"), *tube(front, front + 1.0, 7.4, 0.5, "blued"),
         sym(0.25, 5.8, front + 0.8, 7.0, front + 1.6, "bakelite"),  # barrel rest
         *tube(-7.0, 5.0, 7.6, 0.85, "blued"), sym(0.3, 7.6, -3.0, 8.9, -1.6, "steel", x=-0.9),  # receiver tube, cocking handle (left)
         sym(0.6, 4.6, -7.0, 6.8, -3.4, "blued"),  # magazine housing
         sym(0.9, 4.8, -3.0, 6.8, 4.4, "bakelite"), *trigger_guard(0.8, 4.8, 2.0, "blued"),
         *pistol_grip(1.0, 4.8, 3.6, 1.6, 12, "bakelite"),
         sym(0.18, 5.8, 4.4, 6.2, 12.5, "blued", x=0.7), sym(0.18, 5.8, 4.4, 6.2, 12.5, "blued", x=-0.7), sym(0.9, 4.0, 12.5, 6.4, 13.0, "blued")]
    iron = [sym(0.4, 8.4, 3.0, 9.3, 3.6, "blued"), sym(0.4, 8.0, front + 0.6, 8.9, front + 1.3, "blued")]
    return gun(p, ("bolt", [sym(0.3, 7.6, -3.0, 8.9, -1.6, "steel", x=-0.9)]), (4.8, -5.2), (8.5, 0.0), (7.4, front),
               rhand=(0, 2.6, 2.4), lhand=(0, 3.0, -5.2), iron=iron)


def _ppsh():
    front = -21.0
    p = [*tube(front, -8.0, 7.4, 1.0, "blued"),  # barrel jacket
         *[sym(1.02, 7.0, z, 7.8, z + 0.6, "black") for z in (-19.5, -17.5, -15.5, -13.5, -11.5, -9.5)],  # cooling slots
         sym(1.0, 6.0, front, 8.6, front + 0.8, "blued"),  # slanted muzzle brake (front of the jacket)
         sym(1.0, 6.4, -8.0, 8.6, 3.0, "blued"), *trigger_guard(1.2, 6.2, 2.0, "blued"),
         sym(0.95, 4.8, -8.0, 6.4, 2.0, "walnut"), *tilt([sym(0.9, 2.4, 1.5, 6.8, 13.4, "walnut")], -10, (0, 6.8, 1.5)),
         *tilt([sym(0.95, 1.4, 13.0, 7.0, 13.6, "metal")], -10, (0, 6.8, 1.5))]
    iron = [sym(0.4, 8.6, -3.0, 9.4, -2.0, "blued"), sym(0.3, 8.4, front + 0.6, 9.2, front + 1.2, "blued")]
    return gun(p, ("bolt", [bx(1.0, 7.2, -1.0, 1.6, 7.8, 0.0, "steel")]), (6.0, -5.0), (8.6, -1.0), (7.4, front),
               rhand=(0, 3.0, 2.6), lhand=(0, 5.0, -10.0), iron=iron)


def _sten():
    front = -15.0
    p = [*tube(front, -5.0, 7.4, 0.45, "parkerized"), *tube(-6.0, 6.0, 7.4, 0.95, "parkerized"),  # barrel, receiver tube
         sym(0.4, 6.4, -6.0, 8.6, -4.6, "parkerized", x=-1.2),  # magazine housing (left)
         sym(0.3, 4.6, 0.0, 6.4, 0.8, "parkerized"), *trigger_guard(1.0, 6.4, 2.0, "parkerized"),
         sym(0.2, 4.0, 1.4, 6.4, 1.8, "parkerized"), sym(0.18, 3.8, 1.6, 4.2, 11.0, "parkerized"),  # skeleton stock
         sym(0.18, 7.2, 6.0, 7.6, 11.0, "parkerized"), sym(0.9, 3.6, 10.8, 7.8, 11.2, "parkerized")]
    iron = [sym(0.4, 8.35, 2.0, 9.2, 2.6, "parkerized"), sym(0.15, 7.8, front + 0.3, 8.8, front + 0.6, "parkerized")]
    return gun(p, ("bolt", [sym(0.3, 7.6, -2.0, 8.6, -1.0, "steel", x=1.0)]), (7.4, -5.3), (8.4, 0.0), (7.4, front),
               rhand=(0, 2.8, 1.4), lhand=(-2.4, 6.6, -5.3), iron=iron)


def _grease_gun():
    front = -14.0
    p = [*tube(front, -7.0, 7.2, 0.45, "parkerized"), *tube(-7.5, 4.0, 7.2, 1.2, "parkerized"),  # barrel, tubular receiver
         sym(0.7, 4.8, -7.0, 6.2, -3.6, "parkerized"),  # magazine housing
         sym(0.9, 4.6, -3.0, 6.2, 1.6, "parkerized"), *trigger_guard(-0.2, 4.6, 1.8, "parkerized"),
         *pistol_grip(0.0, 4.6, 3.6, 1.6, 16, "parkerized"), bx(1.1, 6.8, -3.0, 1.4, 7.6, 1.2, "parkerized"),  # cocking crank
         sym(0.18, 5.4, 4.0, 5.8, 11.5, "steel", x=0.6), sym(0.18, 5.4, 4.0, 5.8, 11.5, "steel", x=-0.6), sym(0.8, 4.4, 11.4, 6.6, 11.8, "steel")]
    iron = [sym(0.35, 8.4, 2.4, 9.2, 3.0, "parkerized"), sym(0.15, 8.0, front + 1.0, 8.8, front + 1.4, "parkerized")]
    return gun(p, ("bolt", [bx(1.1, 6.8, -3.0, 1.4, 7.6, 1.2, "parkerized")]), (4.8, -5.3), (8.4, 0.0), (7.2, front),
               rhand=(0, 2.6, 1.0), lhand=(0, 3.2, -5.3), iron=iron)


def _stg44():
    front = -24.0
    p = [*tube(front, -12.0, 7.4, 0.42, "blued"), *tube(-12.0, -6.0, 8.6, 0.55, "blued"),  # barrel, gas tube
         sym(0.55, 6.8, -12.5, 9.1, -11.5, "blued"), sym(0.95, 6.0, -12.0, 8.2, -6.5, "walnut"),  # gas block, lower handguard
         sym(1.05, 6.0, -6.5, 8.8, 3.6, "blued"), sym(1.1, 4.4, -5.6, 6.0, -2.4, "blued"),  # stamped receiver, magazine well
         sym(1.0, 4.8, -2.4, 6.0, 2.4, "blued"), *trigger_guard(1.2, 4.8, 2.0, "blued"),
         *pistol_grip(1.4, 4.8, 3.6, 1.6, 16, "bakelite"), sym(0.9, 4.6, 3.6, 8.4, 6.0, "walnut"),
         *tilt([sym(0.9, 3.2, 5.8, 8.2, 13.2, "walnut")], -5, (0, 8.2, 5.8)), *tilt([sym(0.95, 2.6, 12.9, 8.4, 13.5, "metal")], -5, (0, 8.2, 5.8))]
    iron = [sym(0.5, 8.8, -5.0, 9.4, -3.6, "blued"), sym(0.45, 7.9, front + 0.6, 9.2, front + 1.4, "blued")]
    return gun(p, ("bolt", [bx(-1.05, 7.6, -4.0, -1.8, 8.2, -3.2, "steel")]), (5.2, -4.0), (9.4, -1.0), (7.4, front),
               rhand=(0, 2.8, 3.0), lhand=(0, 5.2, -9.0), iron=iron)


def _bar():
    front = -38.0
    p = [*tube(front + 1.5, -10.0, 7.4, 0.48, "parkerized"), *tube(front, front + 1.5, 7.4, 0.6, "parkerized"),
         *tube(-22.0, -10.0, 6.2, 0.4, "parkerized"),  # gas cylinder
         sym(1.0, 5.4, -20.0, 7.4, -10.0, "walnut"), sym(1.1, 5.0, -10.0, 8.8, 3.0, "parkerized"),  # forend, receiver
         *trigger_guard(1.6, 5.0, 2.2, "parkerized"), sym(0.75, 3.0, 2.6, 6.6, 4.6, "walnut"),
         *tilt([sym(0.95, 1.8, 4.4, 7.4, 14.5, "walnut")], -6, (0, 7.4, 4.4)), *tilt([sym(1.0, 1.4, 14.2, 7.6, 14.8, "metal")], -6, (0, 7.4, 4.4)),
         *[bx(sx * 0.9, 4.0, -36.0, sx * 1.3, 7.0, -35.4, "parkerized") for sx in (-1, 1)],  # bipod (folded forward)
         *[bx(sx * 0.9, 2.0, -36.0, sx * 1.3, 4.0, -26.0, "parkerized") for sx in (-1, 1)]]
    iron = [sym(0.5, 8.8, 0.6, 10.0, 1.6, "parkerized"), sym(0.4, 7.8, front + 0.4, 9.2, front + 1.2, "parkerized")]
    return gun(p, ("bolt", [bx(-1.1, 6.8, -4.0, -1.8, 7.4, -3.0, "steel")]), (5.0, -3.2), (10.0, -1.0), (7.4, front),
               rhand=(0, 2.8, 3.8), lhand=(0, 5.2, -14.0), iron=iron)


def _mg42():
    front = -38.0
    p = [*tube(front, front + 2.0, 7.6, 0.75, "black"),  # muzzle booster
         sym(1.2, 6.2, front + 2.0, 9.0, -8.0, "blued"),  # square, perforated barrel jacket
         *[sym(1.22, 6.8, z, 8.4, z + 0.7, "black") for z in [front + 3 + i * 1.6 for i in range(int((-10 - front - 3) / 1.6))]],
         bx(1.2, 6.6, -16.0, 1.24, 8.6, -10.0, "black"),  # barrel change hatch
         sym(1.3, 5.8, -8.0, 9.4, 3.0, "blued"), sym(1.2, 9.4, -6.5, 10.0, 1.0, "blued"),  # receiver, feed cover
         bx(1.3, 7.4, -6.0, 2.1, 8.0, -5.2, "steel"),  # charging handle
         *trigger_guard(1.4, 5.8, 2.0, "blued"), *pistol_grip(1.6, 5.8, 3.6, 1.6, 20, "bakelite"),
         sym(0.95, 5.2, 3.0, 9.0, 6.0, "bakelite"), *tilt([sym(0.9, 4.0, 5.8, 8.8, 13.0, "bakelite")], -4, (0, 8.8, 5.8)),
         *[bx(sx * 1.0, 4.0, -26.0, sx * 1.4, 6.2, -25.4, "blued") for sx in (-1, 1)],
         *[bx(sx * 1.0, 2.0, -26.0, sx * 1.4, 4.0, -18.0, "blued") for sx in (-1, 1)]]
    iron = [sym(0.5, 10.0, -5.0, 11.0, -4.2, "blued"), sym(0.3, 9.0, front + 2.6, 10.4, front + 3.2, "blued")]
    return gun(p, ("bolt", [bx(1.3, 7.4, -6.0, 2.1, 8.0, -5.2, "steel")]), (5.8, -3.0), (10.0, -1.0), (7.6, front),
               rhand=(0, 3.0, 3.4), lhand=(0, 6.2, -12.0), iron=iron)


def _bren():
    front = -37.0
    p = [*tube(front + 2.5, -12.0, 7.4, 0.5, "parkerized"), *tube(front, front + 2.5, 7.4, 0.75, "parkerized"),  # barrel, flash hider
         *[c for i in range(6) for c in tube(-14.0 + i * 0.6, -13.7 + i * 0.6, 7.4, 0.62, "parkerized")],  # barrel fins
         *tube(-22.0, -12.0, 6.0, 0.45, "parkerized"),  # gas cylinder
         sym(0.3, 7.6, -10.0, 9.4, -9.2, "parkerized"), sym(0.3, 9.2, -11.4, 9.7, -7.8, "walnut"),  # carry handle
         sym(1.15, 5.2, -12.0, 8.6, 4.0, "parkerized"), *trigger_guard(1.8, 5.2, 2.2, "parkerized"),
         *pistol_grip(2.0, 5.2, 3.6, 1.7, 15, "walnut"),
         sym(0.9, 4.4, 4.0, 8.0, 13.0, "walnut"), sym(0.95, 3.8, 13.0, 8.4, 13.6, "metal"),
         *[bx(sx * 0.9, 2.0, -33.0, sx * 1.3, 6.2, -32.4, "parkerized") for sx in (-1, 1)]]
    iron = [bx(-1.15, 8.6, 0.0, -2.0, 9.8, 0.8, "parkerized"), bx(-1.15, 7.6, front + 3.0, -2.2, 9.0, front + 3.6, "parkerized")]  # offset sights
    return gun(p, ("bolt", [bx(1.15, 6.8, -2.0, 1.9, 7.4, -1.2, "steel")]), (8.6, -4.5), (9.8, 0.0), (7.4, front),
               rhand=(0, 2.8, 3.8), lhand=(0, 5.0, -13.0), iron=iron, upward=True)


def _dp28():
    front = -40.0
    p = [*tube(front + 2, -12.0, 7.4, 0.48, "blued"), *tube(front, front + 2.0, 7.4, 0.8, "blued"),  # barrel, conical flash hider
         *tube(-24.0, -12.0, 7.4, 1.05, "blued"), *[sym(1.07, 7.0, z, 7.8, z + 0.7, "black") for z in (-23, -21, -19, -17, -15, -13)],  # jacket
         sym(1.1, 5.6, -12.0, 8.6, 2.0, "blued"), *trigger_guard(1.0, 5.6, 2.2, "blued"),
         sym(0.8, 3.6, 1.6, 6.8, 4.4, "walnut"), *tilt([sym(0.95, 2.4, 4.0, 7.6, 14.0, "walnut")], -6, (0, 7.6, 4.0)),
         *[bx(sx * 0.9, 1.6, -26.0, sx * 1.3, 6.4, -25.4, "blued") for sx in (-1, 1)]]
    iron = [sym(0.5, 8.6, -6.0, 9.4, -5.0, "blued"), sym(0.45, 8.2, front + 2.4, 9.4, front + 3.2, "blued")]
    return gun(p, ("bolt", [bx(1.1, 6.6, -3.0, 1.9, 7.2, -2.2, "steel")]), (8.6, -4.0), (9.4, -1.0), (7.4, front),
               rhand=(0, 2.8, 3.0), lhand=(0, 5.2, -14.0), iron=iron)


def _ww2_pistol(gid):
    """Luger P08 (toggle lock, steep grip), Walther P38 (open-top slide), TT-33."""
    front = {"luger": -6.6, "p38": -6.2, "tt33": -5.6}[gid]
    top = 6.3
    rake = {"luger": 30, "p38": 18, "tt33": 14}[gid]
    frame_top = top - 1.2
    p = [sym(0.55, frame_top - 0.8, front + 2.5, frame_top, 2.6, "blued"), *trigger_guard(-0.6, frame_top - 0.8, 2.0, "blued"),
         *pistol_grip(-0.6, frame_top - 0.5, 4.2, 2.4, rake, "walnut" if gid != "p38" else "bakelite", 0.6)]
    if gid == "luger":
        p += [*tube(front, front + 2.6, frame_top + 0.5, 0.42, "blued")]
        slide = [sym(0.5, frame_top, front + 2.5, top - 0.2, 2.6, "blued"), *tube(0.2, 1.6, top - 0.2, 0.42, "blued"),  # toggle
                 sym(0.6, top - 0.6, 1.8, top + 0.1, 2.4, "steel")]
    else:
        slide = [sym(0.55, frame_top, front, top, 2.6, "blued"), *[sym(0.57, frame_top + 0.2 + i * 0.3, 1.2, frame_top + 0.32 + i * 0.3, 2.3, "black") for i in range(3)]]
        if gid == "p38":
            slide.append(sym(0.4, top - 0.05, front + 0.4, top + 0.02, -1.6, "black"))  # open top
    iron = [sym(0.4, top, 1.8, top + 0.45, 2.3, "blued"), sym(0.12, top, front + 0.3, top + 0.45, front + 0.7, "blued")]
    return gun(p, ("slide", slide), (frame_top - 1.0, 0.4), (top, 0.0), (frame_top + 0.5, front), rake=rake,
               rhand=(0, 2.6, 1.4), lhand=(-0.4, 2.1, 0.6), iron=iron)


def _bazooka():
    p = [*tube(-30.0, 28.0, 7.2, 1.4, "olive"), *tube(-30.5, -29.0, 7.2, 1.6, "olive"),  # tube, front ring
         sym(0.8, 1.6, -4.0, 5.8, -2.6, "wood"), sym(0.8, 1.6, 2.0, 5.8, 3.4, "wood"),  # front grip, trigger grip
         sym(0.95, 4.4, 4.0, 5.8, 14.0, "wood"), sym(0.9, 2.4, 10.0, 5.8, 14.0, "wood"),  # shoulder stock
         *trigger_guard(3.4, 5.8, 1.8, "olive"), bx(-1.4, 8.6, -26.0, -2.6, 10.6, -25.4, "olive"), bx(-1.4, 8.6, -8.0, -2.4, 10.0, -7.4, "olive")]
    rocket = [*tube(25.0, 28.4, 7.2, 1.0, "olive"), *tube(24.0, 25.0, 7.2, 0.5, "olive")]
    return gun(p, ("trigger", [sym(0.2, 4.4, 3.0, 5.2, 3.4, "black")]), (7.2, 26.0), (10.6, -16.0), (7.2, -30.5),
               rhand=(0, 3.4, 2.7), lhand=(0, 3.4, -3.3), iron=[], round_bone=True) | {"round_cubes": rocket}


WW2_GUNS = {
    "m1_garand": lambda g: _garand(), "kar98k": lambda g: _bolt_action("kar98k"), "kar98k_scoped": lambda g: _bolt_action("kar98k_scoped"),
    "mosin_nagant": lambda g: _bolt_action("mosin"), "m1903": lambda g: _bolt_action("m1903"), "lee_enfield": lambda g: _lee_enfield(),
    "m1_carbine": lambda g: _m1_carbine(), "g43": lambda g: _g43(), "mp40": lambda g: _mp40(), "ppsh41": lambda g: _ppsh(),
    "sten": lambda g: _sten(), "m3_grease_gun": lambda g: _grease_gun(), "stg44": lambda g: _stg44(), "bar": lambda g: _bar(),
    "mg42": lambda g: _mg42(), "bren": lambda g: _bren(), "dp28": lambda g: _dp28(), "luger_p08": lambda g: _ww2_pistol("luger"),
    "walther_p38": lambda g: _ww2_pistol("p38"), "tt33": lambda g: _ww2_pistol("tt33"), "bazooka": lambda g: _bazooka(),
}
