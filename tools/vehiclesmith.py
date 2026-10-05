"""
Vehicle geometry shared by the vehicle pack generators (modern Vehicles pack, WW2 pack).

Vehicle space is [right, up, forward] in blocks, origin at the centre of the footprint on the ground, the same frame
as the `position`/`pivot`/`muzzle`/`sight` fields of vehicle definitions. GeckoLib models face north (-Z) and GeckoLib
mirrors X, so a box's "right" ends up on the vehicle's right.

Bodies are built like real vehicles from the inside out: floors, hollow cabins with glass windows (translucent - the
vehicle renderer draws translucent), dashboards, steering wheels and seats, so riders see an interior and out of the
windows.
"""
import math
import random
from pathlib import Path

from PIL import Image

# ------------------------------------------------------------------------------------------- materials
# Vehicle texture: 128x64, 16x16 noisy tiles (per-face UV maps a whole tile onto each face).
TILE = 16
MATERIALS = {"olive": (0, 0), "olive_dark": (1, 0), "tan": (2, 0), "sand": (3, 0), "tyre": (4, 0), "steel": (5, 0),
             "metal": (6, 0), "glass": (7, 0), "canvas": (0, 1), "black": (1, 1), "light": (2, 1), "red": (3, 1),
             "track": (4, 1), "flash": (5, 1), "seat": (6, 1), "white": (7, 1),
             # Second row: paints (WW2 and Russian camouflage) and interior colours.
             "panzer_grey": (0, 2), "dunkelgelb": (1, 2), "soviet_green": (2, 2), "olive_drab": (3, 2), "khaki_green": (4, 2),
             "interior": (5, 2), "dashboard": (6, 2), "rubber": (7, 2), "green_camo": (0, 3), "desert": (1, 3),
             "chrome": (2, 3), "gauge": (3, 3), "wood": (4, 3), "rust": (5, 3), "navy": (6, 3), "bronze": (7, 3)}
COLOURS = {"olive": (86, 96, 60), "olive_dark": (62, 70, 44), "tan": (176, 152, 110), "sand": (196, 176, 130),
           "tyre": (28, 28, 30), "steel": (120, 124, 130), "metal": (60, 63, 68), "glass": (150, 190, 205),
           "canvas": (120, 112, 80), "black": (16, 16, 18), "light": (250, 240, 200), "red": (170, 30, 30),
           "track": (48, 46, 44), "flash": (255, 226, 130), "seat": (70, 52, 38), "white": (220, 220, 220),
           "panzer_grey": (70, 74, 78), "dunkelgelb": (178, 156, 98), "soviet_green": (74, 88, 52), "olive_drab": (84, 82, 52),
           "khaki_green": (96, 98, 66), "interior": (168, 170, 150), "dashboard": (40, 42, 40), "rubber": (34, 34, 34),
           "green_camo": (70, 86, 54), "desert": (190, 166, 120), "chrome": (190, 194, 200), "gauge": (230, 230, 210),
           "wood": (120, 84, 50), "rust": (120, 70, 40), "navy": (60, 70, 90), "bronze": (150, 110, 60)}
# Glass is see-through: the texel alpha (the vehicle renderer uses a translucent render type).
ALPHA = {"glass": 80, "flash": 230}
CAMO = {"green_camo": [(70, 86, 54), (52, 60, 40), (96, 92, 62)], "desert": [(190, 166, 120), (160, 130, 90), (205, 186, 140)]}


def vehicle_texture(path: Path, opaque_glass=False):
    """[opaque_glass] for the item-icon copy (item models cannot be translucent there)."""
    rng = random.Random(7)
    img = Image.new("RGBA", (8 * TILE, 4 * TILE))
    for mat, (tx, ty) in MATERIALS.items():
        for x in range(TILE):
            for y in range(TILE):
                base = COLOURS[mat]
                if mat in CAMO:  # blotches
                    base = CAMO[mat][((x // 5) * 3 + (y // 4) * 2 + (x * y) % 3) % 3]
                n = rng.randint(-7, 7)
                if mat == "tyre" and y % 4 == 0 or mat == "track" and x % 4 == 0:
                    n -= 12  # treads
                if mat == "gauge" and (x - 7) ** 2 + (y - 7) ** 2 > 36:
                    base = COLOURS["dashboard"]
                if x in (0, TILE - 1) or y in (0, TILE - 1):
                    n -= 10  # dark panel edges
                alpha = 255 if opaque_glass else ALPHA.get(mat, 255)
                img.putpixel((tx * TILE + x, ty * TILE + y), tuple(max(0, min(255, c + n)) for c in base) + (alpha,))
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


# ------------------------------------------------------------------------------------------- boxes
def box(r0, u0, f0, r1, u1, f1, mat="olive", slope=0.0, at=None):
    """A box in vehicle space; [slope] tilts it about the right axis through [at] (default its centre), in degrees:
    positive leans its top towards the back (a sloped glacis or windshield)."""
    b = (min(r0, r1), min(u0, u1), min(f0, f1), max(r0, r1), max(u0, u1), max(f0, f1), mat)
    if slope:
        centre = at or ((r0 + r1) / 2, (u0 + u1) / 2, (f0 + f1) / 2)
        return b + ((slope, centre),)
    return b


def pivot(r, u, f):
    return [round(-r * 16, 3), round(u * 16, 3), round(-f * 16, 3)]


def cube(b):
    """Vehicle-space box -> GeckoLib cube (per-face UV on the material's tile)."""
    r0, u0, f0, r1, u1, f1, mat = b[:7]
    tx, ty = MATERIALS[mat]
    face = {"uv": [tx * TILE, ty * TILE], "uv_size": [TILE, TILE]}
    c = {"origin": [round(-r1 * 16, 3), round(u0 * 16, 3), round(-f1 * 16, 3)],
         "size": [round((r1 - r0) * 16, 3), round((u1 - u0) * 16, 3), round((f1 - f0) * 16, 3)],
         "uv": {f: dict(face) for f in ("north", "south", "east", "west", "up", "down")}}
    if len(b) > 7:
        angle, centre = b[7]
        # Top towards the back (+Z in the model): GeckoLib's positive bedrock x rotation swings the lower end back,
        # so leaning the top back is a negative angle.
        c["rotation"] = [-angle, 0, 0]
        c["pivot"] = pivot(*centre)
    return c


def circle(r, steps=None):
    """Pixel-art circle of radius [r] (blocks): (half-width, offset0, offset1) slabs, symmetric about 0."""
    n = steps or max(2, round(r / 0.09))
    out = []
    for i in range(n):
        a, b = r * i / n, r * (i + 1) / n
        half = r * math.sqrt(max(0.0, 1 - ((a + b) / 2 / r) ** 2))
        out += [(half, a, b), (half, -b, -a)]
    return out


def wheel_disc(r, f, u, radius, width, mat="tyre"):
    """Round wheel face (circle in the up/forward plane) of [width] centred on (r, u, f)."""
    return [box(r - width / 2, u + a, f - h, r + width / 2, u + b, f + h, mat) for h, a, b in circle(radius)]


def drum(r0, r1, u, f, radius, mat):
    """Round cylinder lying along the right axis (fuel drums, hubs)."""
    return [box(r0, u + a, f - h, r1, u + b, f + h, mat) for h, a, b in circle(radius)]


def face_disc(r, u, f0, f1, radius, mat="tyre"):
    """Disc facing forward/backward (a spare wheel on a tailgate): circle in the right/up plane from f0 to f1."""
    return [box(r - h, u + a, f0, r + h, u + b, f1, mat) for h, a, b in circle(radius)]


def round_turret(r, u0, u1, f, radius, mat):
    """Round (cast) turret/hatch: a circle in the right/forward plane, from height u0 to u1."""
    return [box(r - h, u0, f + a, r + h, u1, f + b, mat) for h, a, b in circle(radius)]


def barrel(r, u, f0, f1, radius, mat="metal"):
    """Gun barrel along forward: crossed boxes read as round."""
    return [box(r - radius, u - radius * 0.6, f0, r + radius, u + radius * 0.6, f1, mat),
            box(r - radius * 0.6, u - radius, f0, r + radius * 0.6, u + radius, f1, mat)]


# ------------------------------------------------------------------------------------------- interior pieces
def seat(r, u, f, mat="seat", width=0.5, back=0.55, facing=1):
    """A seat whose cushion top is at height [u] (the seat position of the definition); backrest behind it.
    [facing] -1 for seats facing backwards/sideways benches along the forward axis."""
    d = 0.45
    return [box(r - width / 2, u - 0.12, f - d / 2, r + width / 2, u, f + d / 2, mat),
            box(r - width / 2, u, f - facing * d / 2 - 0.08, r + width / 2, u + back, f - facing * d / 2, mat)]


def bench(r, u, f0, f1, side, mat="seat"):
    """Side bench along the forward axis (troop seats in trucks); [side] = -1 left wall, +1 right wall."""
    return [box(r - 0.22, u - 0.1, f0, r + 0.22, u, f1, mat), box(r + side * 0.22, u, f0, r + side * 0.3, u + 0.45, f1, mat)]


def steering_wheel(r, u, f, mat="dashboard"):
    """Steering column and wheel, leaning back towards the driver."""
    return [box(r - 0.03, u - 0.25, f - 0.03, r + 0.03, u, f + 0.25, mat, slope=-35),
            box(r - 0.2, u - 0.02, f - 0.2, r + 0.2, u + 0.02, f - 0.16, mat, slope=-35, at=(r, u, f)),
            box(r - 0.2, u - 0.02, f + 0.16, r + 0.2, u + 0.02, f + 0.2, mat, slope=-35, at=(r, u, f)),
            box(r - 0.2, u - 0.02, f - 0.2, r - 0.16, u + 0.02, f + 0.2, mat, slope=-35, at=(r, u, f)),
            box(r + 0.16, u - 0.02, f - 0.2, r + 0.2, u + 0.02, f + 0.2, mat, slope=-35, at=(r, u, f))]


def dashboard(r0, r1, u, f, gauges=2):
    """Dashboard across the cabin at the front with round gauges facing the driver."""
    out = [box(r0, u - 0.25, f - 0.05, r1, u, f + 0.25, "dashboard")]
    for i in range(gauges):
        gx = r0 + 0.2 + i * 0.22
        out.append(box(gx - 0.08, u - 0.18, f - 0.06, gx + 0.08, u - 0.04, f - 0.05, "gauge"))
    return out


def cabin(r0, r1, u0, u1, f0, f1, paint, belt=None, glass_front=True, glass_back=True, roof=True, pillar=0.08, wall=0.06,
          interior="interior", door_lines=()):
    """A hollow cabin: floor, body below the belt line, glass above it with pillars, roof. [belt] = height of the
    window sills (default 55% up). [door_lines] = forward positions of door seams on both sides."""
    belt = belt if belt is not None else u0 + (u1 - u0) * 0.55
    w = wall
    out = [box(r0, u0, f0, r1, u0 + w, f1, interior),  # floor
           box(r0, u0, f0, r0 + w, belt, f1, paint), box(r1 - w, u0, f0, r1, belt, f1, paint),  # lower side walls
           box(r0, u0, f1 - w, r1, belt, f1, paint), box(r0, u0, f0, r1, belt, f0 + w, paint)]  # lower front/back
    # pillars at the four corners and the side glass between them
    for rr in (r0, r1 - pillar):
        for ff in (f0, f1 - pillar):
            out.append(box(rr, belt, ff, rr + pillar, u1, ff + pillar, paint))
    out += [box(r0 + 0.01, belt, f0 + pillar, r0 + w * 0.6, u1, f1 - pillar, "glass"),
            box(r1 - w * 0.6, belt, f0 + pillar, r1 - 0.01, u1, f1 - pillar, "glass")]
    out.append(box(r0 + pillar, belt, f1 - w * 0.6, r1 - pillar, u1, f1 - 0.01, "glass" if glass_front else paint))
    out.append(box(r0 + pillar, belt, f0 + 0.01, r1 - pillar, u1, f0 + w * 0.6, "glass" if glass_back else paint))
    if roof:
        out.append(box(r0, u1, f0, r1, u1 + w, f1, paint))
    for line in door_lines:  # door seams (dark lines on the outside)
        out += [box(r0 - 0.01, u0 + 0.1, line, r0, belt, line + 0.03, "black"), box(r1, u0 + 0.1, line, r1 + 0.01, belt, line + 0.03, "black")]
    return out


def open_tub(r0, r1, u0, u1, f0, f1, paint, wall=0.06, interior="interior"):
    """An open body (jeeps, carriers): floor and four thin walls."""
    w = wall
    return [box(r0, u0, f0, r1, u0 + w, f1, interior),
            box(r0, u0, f0, r0 + w, u1, f1, paint), box(r1 - w, u0, f0, r1, u1, f1, paint),
            box(r0, u0, f1 - w, r1, u1, f1, paint), box(r0, u0, f0, r1, u1, f0 + w, paint)]


def windshield(r0, r1, u0, u1, f, paint, frame=0.06, slope=12):
    """Windshield frame with a glass pane, leaning back a little."""
    centre = ((r0 + r1) / 2, u0, f)
    return [box(r0, u0, f - frame / 2, r0 + frame, u1, f + frame / 2, paint, slope, centre),
            box(r1 - frame, u0, f - frame / 2, r1, u1, f + frame / 2, paint, slope, centre),
            box(r0, u1 - frame, f - frame / 2, r1, u1, f + frame / 2, paint, slope, centre),
            box(r0, u0, f - frame / 2, r1, u0 + frame, f + frame / 2, paint, slope, centre),
            box(r0 + frame, u0 + frame, f - 0.015, r1 - frame, u1 - frame, f + 0.015, "glass", slope, centre)]


def headlights(r, u, f, size=0.14, pairs=(-1, 1)):
    return [box(r * s - size, u - size, f, r * s + size, u + size, f + 0.04, "light") for s in pairs]


# ------------------------------------------------------------------------------------------- model
class Model:
    """Bones of a vehicle model; `body` is the root. Wheels spin (`wheel_*`), `steer_*` bones steer."""

    def __init__(self):
        self.bones = []
        # Weapon mounts by name: pivot (on the yaw axis), muzzle and sight (relative to the pivot) for the seat definitions.
        self.mounts = {}

    def bone(self, name, boxes, parent="body", at=(0, 0, 0)):
        self.bones.append({"name": name, "parent": parent, "pivot": pivot(*at), "cubes": [cube(x) for x in boxes]})
        return self

    def add(self, name, boxes):
        """Adds boxes to an existing bone (or creates it under the body)."""
        for b in self.bones:
            if b["name"] == name:
                b["cubes"] += [cube(x) for x in boxes]
                return self
        return self.bone(name, boxes)

    def wheel(self, name, r, f, radius, width, steer=False, mat="tyre", hub="steel", parent="body"):
        """A round wheel spinning around its axle; front wheels sit in a `steer_*` bone that turns with the steering."""
        parts = wheel_disc(r, f, radius, radius, width, mat) + wheel_disc(r, f, radius, radius * 0.5, width + 0.04, hub)
        if steer:
            self.bone(f"steer_{name}", [], parent=parent, at=(r, radius, f))
            parent = f"steer_{name}"
        return self.bone(f"wheel_{name}", parts, parent=parent, at=(r, radius, f))

    def tracks(self, side, r, f0, f1, height, width, wheels, wheel_radius, wheel_mat="metal", skirt=None):
        """A track run (top, bottom, sloped ends) with spinning road wheels; [skirt] adds a side plate of that paint."""
        name = "track_l" if side < 0 else "track_r"
        t = 0.1
        self.bone(name, [box(r - width / 2, 0, f0 + 0.3, r + width / 2, t, f1 - 0.3, "track"),
                         box(r - width / 2, height - t, f0 + 0.1, r + width / 2, height, f1 - 0.1, "track"),
                         box(r - width / 2, 0.1, f1 - 0.35, r + width / 2, height - 0.1, f1, "track"),
                         box(r - width / 2, 0.1, f0, r + width / 2, height - 0.1, f0 + 0.35, "track")])
        tag = "l" if side < 0 else "r"
        for i, f in enumerate(wheels):
            self.wheel(f"{tag}{i}", r, f, wheel_radius, width * 0.8, mat=wheel_mat, hub="steel")
        if skirt:
            self.add(name, [box(r + side * (width / 2), height * 0.35, f0 + 0.2, r + side * (width / 2 + 0.05), height + 0.05, f1 - 0.2, skirt)])
        return self

    def geo(self, ident):
        return {"format_version": "1.12.0", "minecraft:geometry": [{
            "description": {"identifier": f"geometry.{ident}", "texture_width": 8 * TILE, "texture_height": 4 * TILE},
            "bones": [{"name": "body", "pivot": [0, 0, 0], "cubes": []}] + self.bones}]}

    def boxes(self):
        """All boxes back in vehicle space (for the item icon), without upgrades, muzzle flash and interior details."""
        out = []
        for b in self.bones:
            if b["name"].startswith("upgrade_") or b["name"] in ("muzzle_flash", "interior"):
                continue
            for c in b["cubes"]:
                x0, y0, z0 = c["origin"]
                sx, sy, sz = c["size"]
                mat = next(k for k, v in MATERIALS.items() if [v[0] * TILE, v[1] * TILE] == c["uv"]["north"]["uv"])
                out.append(box(-(x0 + sx) / 16, y0 / 16, -(z0 + sz) / 16, -x0 / 16, (y0 + sy) / 16, -z0 / 16, mat))
        return out


# ------------------------------------------------------------------------------------------- shared builders
def plate(r0, r1, fa, ua, fb, ub, mat, t=0.1, below=True):
    """A flat plate whose top (or with below=False, bottom) face runs along the line (fa, ua)-(fb, ub) in the
    forward/up plane: a box rotated about the right axis."""
    length = math.hypot(fb - fa, ub - ua)
    fm, um = (fa + fb) / 2, (ua + ub) / 2
    lo, hi = (um - t, um) if below else (um, um + t)
    return box(r0, lo, fm - length / 2, r1, hi, fm + length / 2, mat, slope=math.degrees(math.atan2(ub - ua, fb - fa)), at=((r0 + r1) / 2, um, fm))


def ramp(r0, r1, f0, f1, bot0, bot1, top0, top1, mat, steps=6):
    """A wedge along forward (glacis, sloped nose): bottom/top heights go from bot0/top0 at f0 to bot1/top1 at f1.
    Stairs inside, smooth sloped plates over the sloped faces."""
    out = []
    for i in range(steps):
        a, b = f0 + (f1 - f0) * i / steps, f0 + (f1 - f0) * (i + 1) / steps
        ta, tb = top0 + (top1 - top0) * i / steps, top0 + (top1 - top0) * (i + 1) / steps
        ba, bb = bot0 + (bot1 - bot0) * i / steps, bot0 + (bot1 - bot0) * (i + 1) / steps
        out.append(box(r0, max(ba, bb), a, r1, min(ta, tb), b, mat))
    if top0 != top1:
        out.append(plate(r0, r1, f0, top0, f1, top1, mat))
    if bot0 != bot1:
        out.append(plate(r0, r1, f0, bot0, f1, bot1, mat, below=False))
    return out


def open_hatch(r, u, f, size=0.28, mat="metal"):
    """An open round hatch: rim on the roof and the lid standing up behind it (crew heads come out here)."""
    return [box(r - size, u, f - size, r + size, u + 0.05, f - size + 0.06, mat), box(r - size, u, f + size - 0.06, r + size, u + 0.05, f + size, mat),
            box(r - size, u, f - size, r - size + 0.06, u + 0.05, f + size, mat), box(r + size - 0.06, u, f - size, r + size, u + 0.05, f + size, mat),
            box(r - size, u, f - size - 0.07, r + size, u + size * 1.8, f - size, mat)]


def mount(m, name, pivot_at, muzzle, sight, seat=None):
    """[seat]: where the gunner sits for this mount (vehicle space), if the mount decides it (ring mounts)."""
    m.mounts[name] = {"pivot": [round(x, 3) for x in pivot_at], "muzzle": [round(x, 3) for x in muzzle], "sight": [round(x, 3) for x in sight]}
    if seat:
        m.mounts[name]["seat"] = [round(x, 3) for x in seat]


def _mg(m, r, gu, f, gun_len, gun_bone, parent, mat, ammo="olive", flash=True):
    """Machine gun along forward from f (grips) - receiver - barrel; pitch bone pivoting at the receiver."""
    m.bone(gun_bone, [box(r - 0.08, gu - 0.08, f + 0.1, r + 0.08, gu + 0.08, f + 0.6, mat),
                      *barrel(r, gu, f + 0.6, f + 0.6 + gun_len, 0.04, mat),
                      box(r - 0.06, gu - 0.06, f + 0.75, r + 0.06, gu + 0.06, f + 0.95, "steel"),  # barrel jacket
                      box(r - 0.12, gu - 0.05, f, r + 0.12, gu, f + 0.1, mat),  # spade grips
                      box(r + 0.08, gu - 0.16, f + 0.2, r + 0.24, gu, f + 0.45, ammo)], parent=parent, at=(r, gu, f + 0.35))
    if flash:
        m.bone("muzzle_flash", [box(r - 0.08, gu - 0.07, f + 0.6 + gun_len, r + 0.08, gu + 0.07, f + 0.8 + gun_len, "flash")], parent=gun_bone, at=(r, gu, f + 0.35))


def mg_turret(m, r, u, f, paint, gun_len=1.0, shield=True, radius=0.55, gun_bone="mg", turret_bone="turret", mat="metal", name="mg", flash=True):
    """Ring mount around a standing gunner (seat at the ring centre): the ring with an optional shield turns (yaw bone),
    the machine gun in front of the gunner tilts (pitch bone)."""
    t = 0.08
    ring = [box(r - radius, u, f - radius, r + radius, u + 0.12, f - radius + t, "olive_dark"),
            box(r - radius, u, f + radius - t, r + radius, u + 0.12, f + radius, "olive_dark"),
            box(r - radius, u, f - radius, r - radius + t, u + 0.12, f + radius, "olive_dark"),
            box(r + radius - t, u, f - radius, r + radius, u + 0.12, f + radius, "olive_dark"),
            box(r - 0.05, u + 0.12, f + radius - 0.18, r + 0.05, u + 0.4, f + radius - 0.08, mat)]  # pintle on the front of the ring
    gu = u + 0.32
    if shield:  # lower plate below the gun, two wings beside a window to aim through
        s0 = f + radius - 0.02
        ring += [box(r - 0.5, u + 0.12, s0, r + 0.5, gu - 0.07, s0 + 0.06, paint),
                 box(r - 0.5, gu - 0.07, s0, r - 0.2, u + 0.75, s0 + 0.06, paint), box(r + 0.2, gu - 0.07, s0, r + 0.5, u + 0.75, s0 + 0.06, paint),
                 box(r - 0.56, u + 0.12, s0 - 0.3, r - 0.5, u + 0.65, s0 + 0.06, paint), box(r + 0.5, u + 0.12, s0 - 0.3, r + 0.56, u + 0.65, s0 + 0.06, paint)]
    m.bone(turret_bone, ring, at=(r, u, f))
    g0 = f + radius - 0.5  # grips
    _mg(m, r, gu, g0, gun_len, gun_bone, turret_bone, mat, flash=flash)
    # The gunner stands at the ring centre with the gun at chest height (eye = seat + 1.0); the sight is above the
    # front of the receiver, so the gun itself does not block the view.
    mount(m, name, (r, gu, f), [0, 0, g0 - f + 0.65 + gun_len], [0, 0.17, g0 - f + 0.62], seat=(r, gu - 0.68, f))


def post_mg(m, r, u0, u1, f, gun_len=0.9, gun_bone="mg", turret_bone="turret", mat="metal", name="mg", parent="body"):
    """Pedestal mount (jeeps, carriers): a post fixed to the body, a swivel on top (yaw bone) and the gun (pitch bone)."""
    m.add("hull", [box(r - 0.05, u0, f - 0.05, r + 0.05, u1, f + 0.05, mat)])
    m.bone(turret_bone, [box(r - 0.07, u1, f - 0.07, r + 0.07, u1 + 0.1, f + 0.07, mat)], parent=parent, at=(r, u1, f))
    gu = u1 + 0.17
    _mg(m, r, gu, f - 0.35, gun_len, gun_bone, turret_bone, mat)
    mount(m, name, (r, gu, f), [0, 0, 0.3 + gun_len], [0, 0.17, 0.27])


def tank_turret(m, f, u0, u1, r_half, front, back, paint, gun_len, gun_r, gun_bone="cannon", mantlet=0.3, round_radius=None,
                muzzle_brake=False, hatches=(), open_hatches=(), extras=(), name="main", sight_side=0.55):
    """Tank turret (yaw bone `turret`, turning about (0, f)) with mantlet and gun (pitch bone [gun_bone]): boxy with a
    sloped front, or cast-round with [round_radius]."""
    if round_radius:
        shell = round_turret(0, u0, u1 - 0.12, f, round_radius, paint) + round_turret(0, u1 - 0.12, u1, f, round_radius * 0.82, paint)
        face = f + round_radius
    else:
        shell = [box(-r_half, u0, f + back, r_half, u1, f + front, paint),
                 *ramp(-r_half * 0.9, r_half * 0.9, f + front, f + front + 0.35, u0, u0, u1 - 0.05, u1 - 0.3, paint, steps=3)]
        face = f + front + 0.35
    shell += [box(r - 0.28, u1, ff - 0.28, r + 0.28, u1 + 0.08, ff + 0.28, "metal") for r, ff in ((h[0], f + h[1]) for h in hatches)]
    for h in open_hatches:
        shell += open_hatch(h[0], u1, f + h[1])
    shell += list(extras)
    m.bone("turret", shell, at=(0, u0, f))
    gu = round((u0 + u1) / 2, 3)
    gf = face + 0.02
    end = gf + 0.25 + gun_len
    gun = [box(-mantlet, gu - mantlet * 0.75, gf - 0.15, mantlet, gu + mantlet * 0.75, gf + 0.25, paint), *barrel(0, gu, gf + 0.25, end, gun_r, "metal")]
    if muzzle_brake:
        gun += [box(-gun_r * 1.9, gu - gun_r * 1.4, end, gun_r * 1.9, gu + gun_r * 1.4, end + 0.25, "metal")]
        end += 0.25
    m.bone(gun_bone, gun, parent="turret", at=(0, gu, gf))
    m.bone("muzzle_flash", [box(-gun_r * 3, gu - gun_r * 3, end, gun_r * 3, gu + gun_r * 3, end + 0.7, "flash")], parent=gun_bone, at=(0, gu, gf))
    mount(m, name, (0, gu, f), [0, 0, end - f + 0.1], [sight_side, mantlet * 0.75 + 0.15, gf - f - 0.2])
    return gu, gf


def cupola_mg(m, f, u, gun_len=0.9, mat="metal", name="cupola"):
    """Commander's machine gun on a ring around the turret's yaw axis (the commander sits on the axis, so the ring
    turns around them independently of the turret)."""
    mg_turret(m, 0, u, f, "metal", gun_len=gun_len, shield=False, radius=0.38, gun_bone="cupola_mg", turret_bone="cupola", mat=mat, name=name, flash=False)


def sight_overlay(path: Path, kind):
    """256x256 gunner's sight overlay: opaque black outside the field of view, reticle inside.
    tank_modern (thermal-green tint, ranging cross), tank_soviet (TPD chevrons + range scale), tzf (German sharks'
    teeth), telescope (WW2 Allied crosshair with range lines), mg_ring (open ring and post, mostly see-through)."""
    from PIL import ImageDraw
    path.parent.mkdir(parents=True, exist_ok=True)
    size, c = 256, 128
    img = Image.new("RGBA", (size, size), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    if kind == "mg_ring":
        d.rectangle((0, 0, size, size), fill=(0, 0, 0, 0))
        for rr, w in ((90, 3), (45, 2)):
            d.ellipse((c - rr, c - rr, c + rr, c + rr), outline=(25, 25, 25, 255), width=w)
        for a in range(0, 360, 90):
            dx, dy = round(math.cos(math.radians(a)) * 90), round(math.sin(math.radians(a)) * 90)
            d.line((c, c, c + dx, c + dy), fill=(25, 25, 25, 200), width=1)
        d.rectangle((c - 2, c, c + 2, c + 40), fill=(25, 25, 25, 255))  # front post
        img.save(path)
        return
    r = 112
    tint = {"tank_modern": (30, 70, 30, 60), "tank_soviet": (0, 0, 0, 0), "tzf": (0, 0, 0, 0), "telescope": (0, 0, 0, 0)}[kind]
    ink = {"tank_modern": (220, 40, 30, 255), "tank_soviet": (15, 15, 15, 255), "tzf": (15, 15, 15, 255), "telescope": (15, 15, 15, 255)}[kind]
    d.ellipse((c - r, c - r, c + r, c + r), fill=tint)
    d.ellipse((c - r, c - r, c + r, c + r), outline=(0, 0, 0, 255), width=5)
    if kind == "tank_modern":
        d.line((c - 70, c, c - 10, c), fill=ink, width=2); d.line((c + 10, c, c + 70, c), fill=ink, width=2)
        d.line((c, c - 70, c, c - 10), fill=ink, width=2); d.line((c, c + 10, c, c + 70), fill=ink, width=2)
        d.rectangle((c - 3, c - 3, c + 3, c + 3), outline=ink)
        for i in (25, 45):
            d.line((c - 4, c + i, c + 4, c + i), fill=ink)
    elif kind == "tank_soviet":
        for i in range(3):  # aiming chevrons
            y = c + i * 22
            d.line((c - 10, y + 8, c, y, c + 10, y + 8), fill=ink, width=2)
        for i in range(1, 6):
            x = c - 100 + i * 14
            d.line((x, c + 70, x, c + 64 - (i % 2) * 6), fill=ink)
        d.line((c - 100, c + 70, c - 16, c + 70), fill=ink)
        d.line((c - 100, c, c - 30, c), fill=ink, width=2); d.line((c + 30, c, c + 100, c), fill=ink, width=2)
    elif kind == "tzf":
        for dx in (-60, -30, 0, 30, 60):  # sharks' teeth
            big = dx == 0
            hgt = 22 if big else 12
            d.polygon([(c + dx - 8, c + hgt), (c + dx, c), (c + dx + 8, c + hgt)], fill=ink)
        d.line((c - r, c + 22, c + r, c + 22), fill=ink, width=1)
    else:  # telescope
        d.line((c - r, c, c + r, c), fill=ink, width=1); d.line((c, c - r, c, c + r), fill=ink, width=1)
        for i in range(1, 5):
            d.line((c - 10 + i, c + i * 16, c + 10 - i, c + i * 16), fill=ink)
            d.line((c + i * 16, c - 3, c + i * 16, c + 3), fill=ink); d.line((c - i * 16, c - 3, c - i * 16, c + 3), fill=ink)
    path.parent.mkdir(parents=True, exist_ok=True)
    img.save(path)


# ------------------------------------------------------------------------------------------- wheeled vehicles
def open_car(m, paint, length=1.6, half=0.85, u0=0.45, wall=1.0, bonnet=(0.55, 1.55, 1.12), seat_u=0.8):
    """Open car (jeep, Kübelwagen, GAZ): tub with front seats, rear bench, dashboard, steering wheel, windshield."""
    f0, f1, top = bonnet
    hull = [*open_tub(-half, half, u0, wall, -length, f0, paint),
            box(-half + 0.05, u0, f0, half - 0.05, top, f1, paint),  # bonnet
            box(-half - 0.15, 0.75, f0 + 0.05, -half, 0.82, f1 + 0.05, paint), box(half, 0.75, f0 + 0.05, half + 0.15, 0.82, f1 + 0.05, paint),
            box(-half - 0.15, 0.75, -length + 0.1, -half, 0.82, -0.6, paint), box(half, 0.75, -length + 0.1, half + 0.15, 0.82, -0.6, paint)]
    m.bone("hull", hull)
    m.bone("interior", [*dashboard(-half + 0.05, half - 0.05, top, f0 - 0.05), *steering_wheel(-0.4, seat_u + 0.35, f0 - 0.3),
                        *seat(-0.4, seat_u, -0.05), *seat(0.4, seat_u, -0.05),
                        seat(0, seat_u, -1.1, width=2 * half - 0.2)[0], box(-half + 0.08, seat_u, -length + 0.1, half - 0.08, seat_u + 0.45, -length + 0.2, "seat"),
                        box(-0.05, u0, -0.4, 0.05, u0 + 0.4, 0.4, "dashboard")])  # gear stick tunnel
    m.bone("windshield", windshield(-half + 0.05, half - 0.05, top, top + 0.6, f0 - 0.07, paint))
    return m


def jeep(paint="olive"):
    """M151 MUTT: open 4x4 with seats, dashboard, steering wheel, windshield, spare wheel; armour kit / jerry can upgrades."""
    m = open_car(Model(), paint)
    m.add("hull", [box(-0.55, 0.6, 1.55, 0.55, 1.05, 1.62, "black"), *headlights(0.4, 0.88, 1.62, 0.08),
                   *face_disc(0, 1.2, -1.82, -1.62, 0.32), *face_disc(0, 1.2, -1.84, -1.6, 0.14, "steel")])
    for name, r, f in (("fl", -0.75, 1.05), ("fr", 0.75, 1.05), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)):
        m.wheel(name, r, f, 0.38, 0.3, steer=f > 0)
    m.bone("upgrade_armor_kit", [box(-0.9, 0.5, -1.5, -0.85, 1.25, 0.5, "olive_dark"), box(0.85, 0.5, -1.5, 0.9, 1.25, 0.5, "olive_dark"),
                                 box(-0.7, 0.55, 1.62, 0.7, 0.62, 1.75, "metal"), box(-0.7, 0.55, 1.62, -0.62, 1.1, 1.75, "metal"),
                                 box(0.62, 0.55, 1.62, 0.7, 1.1, 1.75, "metal")])
    m.bone("upgrade_jerry_cans", [box(-0.75, 0.95, -1.78, -0.4, 1.45, -1.6, "olive_dark"), box(0.4, 0.95, -1.78, 0.75, 1.45, -1.6, "olive_dark")])
    m.bone("upgrade_engine_tuning", [box(-0.25, 1.12, 0.8, 0.25, 1.25, 1.3, "black")])
    return m


def humvee(paint="sand"):
    """M1114: hollow armoured cabin with glass and four doors, high bonnet, roof ring with a turreted M2."""
    m = Model()
    hull = [box(-1.0, 0.5, 0.6, 1.0, 1.35, 1.95, paint),  # engine bay / bonnet
            box(-0.85, 0.65, 1.95, 0.85, 1.15, 2.02, "black"), *headlights(0.68, 1.02, 2.02, 0.1),
            box(-1.1, 0.5, -1.9, 1.1, 1.2, -1.6, paint),  # rear cargo deck
            box(-1.2, 1.0, -1.9, -1.1, 1.08, 1.85, paint), box(1.1, 1.0, -1.9, 1.2, 1.08, 1.85, paint),  # running boards / fenders
            *cabin(-1.1, 1.1, 0.5, 1.95, -1.6, 0.6, paint, belt=1.38, roof=False, door_lines=(-0.55, 0.45)),
            # roof around the gunner's ring
            box(-1.1, 1.95, 0.15, 1.1, 2.0, 0.6, paint), box(-1.1, 1.95, -1.6, 1.1, 2.0, -0.95, paint),
            box(-1.1, 1.95, -0.95, -0.55, 2.0, 0.15, paint), box(0.55, 1.95, -0.95, 1.1, 2.0, 0.15, paint)]
    m.bone("hull", hull)
    m.bone("interior", [*dashboard(-1.0, 1.0, 1.4, 0.45, 3), *steering_wheel(-0.45, 1.2, 0.2),
                        *seat(-0.45, 0.75, -0.05), *seat(0.45, 0.75, -0.05), *seat(-0.5, 0.75, -1.15), *seat(0.5, 0.75, -1.15),
                        box(-0.12, 0.56, -0.6, 0.12, 0.9, 0.3, "dashboard")])  # centre console
    for name, r, f in (("fl", -0.92, 1.25), ("fr", 0.92, 1.25), ("rl", -0.92, -1.25), ("rr", 0.92, -1.25)):
        m.wheel(name, r, f, 0.45, 0.38, steer=f > 0)
    mg_turret(m, 0, 2.0, -0.4, paint, gun_len=0.85)
    m.bone("upgrade_armor_kit", [box(-1.16, 0.6, -1.5, -1.1, 1.3, 0.5, "olive_dark"), box(1.1, 0.6, -1.5, 1.16, 1.3, 0.5, "olive_dark"),
                                 box(-0.9, 0.55, 2.02, 0.9, 0.65, 2.15, "metal"), box(-0.9, 0.55, 2.02, -0.8, 1.2, 2.15, "metal"), box(0.8, 0.55, 2.02, 0.9, 1.2, 2.15, "metal")])
    m.bone("upgrade_jerry_cans", [box(-0.9, 1.2, -2.0, -0.5, 1.7, -1.9, "olive_dark"), box(0.5, 1.2, -2.0, 0.9, 1.7, -1.9, "olive_dark")])
    m.bone("upgrade_engine_tuning", [box(-0.3, 1.35, 1.0, 0.3, 1.5, 1.6, "black")])
    return m


def truck(paint="olive", cab_canvas=False):
    """2½-ton cargo truck (M35): long bonnet, glazed cab, cargo bed with troop seats under canvas bows, 6 wheels."""
    m = Model()
    hull = [box(-0.75, 0.8, 1.6, 0.75, 1.75, 2.75, paint), box(-0.65, 0.9, 2.75, 0.65, 1.6, 2.82, "black"),  # bonnet, grille
            *headlights(0.55, 1.5, 2.82, 0.1),
            box(-1.15, 1.0, 1.6, -0.8, 1.1, 2.8, paint), box(0.8, 1.0, 1.6, 1.15, 1.1, 2.8, paint),  # front fenders
            *cabin(-1.0, 1.0, 1.0, 2.5, 0.4, 1.6, paint, belt=1.75, door_lines=(1.0,)),
            box(-1.2, 0.85, -3.3, 1.2, 1.15, 0.3, "olive_dark"),  # cargo bed floor
            box(-1.2, 1.15, -3.3, -1.12, 1.8, 0.3, paint), box(1.12, 1.15, -3.3, 1.2, 1.8, 0.3, paint),  # bed sides
            box(-1.2, 1.15, 0.22, 1.2, 1.8, 0.3, paint), box(-1.2, 1.15, -3.3, 1.2, 1.6, -3.22, paint),  # front board, tailgate
            *[box(rr, 1.8, f, rr + 0.06, 2.75, f + 0.06, "metal") for rr in (-1.18, 1.12) for f in (-3.2, -1.6, 0.1)],  # cover bows
            *[box(-1.18, 2.75, f, 1.18, 2.81, f + 0.06, "metal") for f in (-3.2, -1.6, 0.1)],
            box(-1.0, 0.35, -3.3, 1.0, 0.85, 1.6, "metal")]  # frame
    m.bone("hull", hull)
    interior = [*dashboard(-0.95, 0.95, 1.75, 1.45, 3), *steering_wheel(-0.45, 1.45, 1.2), *seat(-0.45, 1.2, 0.85), *seat(0.45, 1.2, 0.85),
                *bench(-0.85, 1.55, -3.1, 0.0, -1), *bench(0.85, 1.55, -3.1, 0.0, 1)]
    m.bone("interior", interior)
    for name, r, f in (("fl", -0.95, 2.1), ("fr", 0.95, 2.1), ("ml", -0.95, -1.15), ("mr", 0.95, -1.15), ("rl", -0.95, -2.35), ("rr", 0.95, -2.35)):
        m.wheel(name, r, f, 0.5, 0.36, steer=f > 0)
    return m


def btr80(paint="soviet_green"):
    """BTR-80: boat-shaped 8x8 hull with wedge nose, small KPVT turret, open crew hatches and vision blocks."""
    m = Model()
    hull = [box(-1.45, 0.55, -3.3, 1.45, 1.9, 1.7, paint),
            *ramp(-1.45, 1.45, 1.7, 3.5, 0.55, 0.9, 1.9, 1.15, paint, steps=6),  # upper glacis down to the nose
            *ramp(-1.45, 1.45, -3.3, -3.6, 0.55, 0.85, 1.9, 1.7, paint, steps=2),  # rear slope
            *[box(s * 1.45, 1.55, f, s * 1.47, 1.7, f + 0.35, "glass") for s in (-1, 1) for f in (-2.2, -1.2, -0.2)],  # vision blocks
            box(-1.1, 1.7, 2.0, -0.2, 1.75, 2.25, "glass"), box(0.2, 1.7, 2.0, 1.1, 1.75, 2.25, "glass"),  # windscreens (armoured flaps open)
            *open_hatch(-0.6, 1.9, 1.35), *open_hatch(0.6, 1.9, 1.35),
            *headlights(1.1, 1.3, 3.0, 0.09), box(-1.4, 0.6, -3.62, 1.4, 1.3, -3.58, "olive_dark")]
    m.bone("hull", hull)
    m.bone("interior", [box(-1.35, 0.55, -3.2, 1.35, 0.6, 1.6, "interior"), *seat(-0.6, 1.15, 1.35), *seat(0.6, 1.15, 1.35),
                        *bench(-1.05, 0.62, -3.0, -0.3, -1), *bench(1.05, 0.62, -3.0, -0.3, 1)])
    for i, f in enumerate((2.3, 1.1, -1.1, -2.3)):
        m.wheel(f"l{i}", -1.3, f, 0.55, 0.4, steer=i < 2)
        m.wheel(f"r{i}", 1.3, f, 0.55, 0.4, steer=i < 2)
    tank_turret(m, 0.4, 1.9, 2.45, 0, 0, 0, paint, gun_len=1.6, gun_r=0.06, gun_bone="kpvt", mantlet=0.16, round_radius=0.6,
                open_hatches=((0, -0.2),), sight_side=0.3)
    return m


def bradley(paint="desert"):
    """M2 Bradley: tracked IFV with a 25mm turret, TOW launcher, rear ramp, spaced armour and troop benches."""
    m = Model()
    hull = [box(-1.55, 0.5, -3.1, 1.55, 1.95, 1.9, paint),
            *ramp(-1.55, 1.55, 1.9, 3.2, 0.5, 0.8, 1.95, 1.1, paint, steps=5),
            box(-1.5, 0.5, -3.2, 1.5, 1.9, -3.1, "olive_dark"),  # ramp
            *open_hatch(-0.85, 1.95, 1.55), *headlights(1.2, 1.2, 3.1, 0.08),
            *[box(s * 1.55, 0.95, f, s * 1.62, 1.85, f + 1.2, paint) for s in (-1, 1) for f in (-2.6, -1.3, 0.0)]]  # spaced armour
    m.bone("hull", hull)
    m.bone("interior", [box(-1.45, 0.5, -3.0, 1.45, 0.55, 1.8, "interior"), *seat(-0.85, 1.2, 1.55),
                        *bench(-1.15, 0.66, -2.9, -0.8, -1), *bench(1.15, 0.66, -2.9, -0.8, 1)])
    for side in (-1, 1):
        m.tracks(side, side * 1.3, -3.15, 3.15, 0.95, 0.55, (-2.4, -1.5, -0.6, 0.3, 1.2, 2.1), 0.35, skirt=paint)
    tank_turret(m, -0.2, 1.95, 2.6, 0.95, 0.9, -1.0, paint, gun_len=2.0, gun_r=0.07, gun_bone="m242", mantlet=0.22,
                hatches=((-0.45, -0.5),), open_hatches=((0.4, -0.45),),
                extras=[box(-1.35, 2.0, -0.8, -0.95, 2.55, 0.7, "olive_dark"), box(-1.35, 2.15, 0.7, -0.95, 2.45, 0.75, "black")])  # TOW box
    return m


def tank_hull(m, paint, half, f0, f1, u0, top, glacis, nose_top, fender=0.0, rear=None):
    """Tank hull: box from f0 to f1 (front of the flat deck), sloped glacis to the nose at f1 + glacis."""
    hull = [box(-half, u0, f0, half, top, f1, paint), *ramp(-half, half, f1, f1 + glacis, u0, u0 + 0.25, top, nose_top, paint, steps=5)]
    if fender:
        hull += [box(-half - fender, top - 0.35, f0 - 0.1, -half, top - 0.28, f1 + glacis * 0.8, paint),
                 box(half, top - 0.35, f0 - 0.1, half + fender, top - 0.28, f1 + glacis * 0.8, paint)]
    hull.append(box(-half + 0.05, u0 + 0.2, f0 - 0.08, half - 0.05, top - 0.1, f0, rear or "olive_dark"))
    m.bone("hull", hull)


def abrams(paint="desert"):
    """M1 Abrams: low hull, angular turret with bustle rack, 120mm gun, side skirts, commander's M2 cupola."""
    m = Model()
    tank_hull(m, paint, 1.25, -2.65, 2.0, 0.45, 1.4, 0.85, 0.75, fender=0.6)
    m.add("hull", open_hatch(0, 1.4, 1.75))
    m.bone("interior", [box(-1.15, 0.5, -2.5, 1.15, 0.55, 1.9, "interior"), *seat(0, 0.65, 1.65)])
    for side in (-1, 1):
        m.tracks(side, side * 1.55, -2.6, 2.6, 1.0, 0.55, (-1.9, -1.15, -0.4, 0.35, 1.1, 1.85), 0.38, skirt=paint)
    tank_turret(m, -0.3, 1.4, 2.2, 1.05, 1.0, -1.4, paint, gun_len=3.2, gun_r=0.1, mantlet=0.32, hatches=((-0.5, -0.4),),
                extras=[box(-1.1, 1.55, -2.0, 1.1, 2.1, -1.4, "olive_dark"),  # bustle rack
                        box(0.62, 2.2, 0.35, 0.95, 2.45, 0.75, "metal")], sight_side=0.78)  # gunner's primary sight
    cupola_mg(m, -0.3, 2.2)
    era = [box(-2.0, 0.6 + 0.3 * (i % 2), -2.2 + 0.5 * i, -1.88, 0.9 + 0.3 * (i % 2), -1.8 + 0.5 * i, "olive_dark") for i in range(9)]
    m.bone("upgrade_era_blocks", era + [box(-b[3], b[1], b[2], -b[0], b[4], b[5], b[6]) for b in era])
    m.bone("upgrade_jerry_cans", [box(-0.9, 1.0, -2.95, -0.2, 1.35, -2.73, "olive_dark"), box(0.2, 1.0, -2.95, 0.9, 1.35, -2.73, "olive_dark")])
    return m


def t72(paint="green_camo"):
    """T-72: low hull with V splash board, round cast turret, 125mm gun, rubber skirts, rear fuel drums, NSVT cupola."""
    m = Model()
    tank_hull(m, paint, 1.3, -2.9, 1.9, 0.45, 1.25, 1.1, 0.7, fender=0.45)
    m.add("hull", [box(-0.9, 1.22, 2.05, 0.9, 1.3, 2.35, paint), *open_hatch(0, 1.25, 1.6),
                   *drum(-1.0, -0.3, 1.45, -3.1, 0.3, "olive_dark"), *drum(0.3, 1.0, 1.45, -3.1, 0.3, "olive_dark")])
    m.bone("interior", [box(-1.2, 0.5, -2.8, 1.2, 0.55, 1.8, "interior"), *seat(0, 0.6, 1.55)])
    for side in (-1, 1):
        m.tracks(side, side * 1.5, -2.9, 2.75, 0.9, 0.5, (-2.1, -1.25, -0.4, 0.45, 1.3, 2.1), 0.38, skirt="rubber")
    tank_turret(m, 0.0, 1.25, 1.95, 0, 0, 0, paint, gun_len=3.6, gun_r=0.09, round_radius=1.15, mantlet=0.26,
                hatches=((-0.5, -0.3),), extras=[box(0.45, 1.95, 0.25, 0.75, 2.2, 0.6, "metal")], sight_side=0.6)
    cupola_mg(m, 0.0, 1.95)
    return m


# ------------------------------------------------------------------------------------------- WW2 vehicles
def willys(paint="olive_drab"):
    """Willys MB: the WW2 jeep, with an M1919 on a pedestal behind the front seats."""
    m = open_car(Model(), paint, bonnet=(0.55, 1.5, 1.08))
    m.add("hull", [*[box(x - 0.025, 0.6, 1.5, x + 0.025, 1.0, 1.55, "black") for x in (-0.4, -0.2, 0, 0.2, 0.4)],  # slat grille
                   *headlights(0.32, 0.92, 1.55, 0.07), *face_disc(0.45, 1.15, -1.82, -1.62, 0.3), *face_disc(0.45, 1.15, -1.84, -1.6, 0.12, "steel")])
    for name, r, f in (("fl", -0.75, 1.0), ("fr", 0.75, 1.0), ("rl", -0.75, -1.05), ("rr", 0.75, -1.05)):
        m.wheel(name, r, f, 0.36, 0.26, steer=f > 0)
    post_mg(m, 0, 0.5, 1.5, -0.6, gun_len=0.7)
    return m


def kubelwagen(paint="dunkelgelb"):
    """VW Kübelwagen: angular open body, sloped front hood with the spare wheel, MG 34 on a pedestal."""
    m = Model()
    hull = [*open_tub(-0.8, 0.8, 0.4, 1.0, -1.7, 0.5, paint), *ramp(-0.8, 0.8, 0.5, 1.6, 0.4, 0.5, 1.0, 0.75, paint, steps=4),
            *round_turret(0, 0.88, 1.0, 1.05, 0.27, "tyre"), *headlights(0.55, 0.95, 1.4, 0.07),
            *ramp(-0.8, 0.8, -1.7, -2.0, 0.4, 0.55, 1.0, 0.8, paint, steps=2)]  # sloped engine lid at the back
    m.bone("hull", hull)
    m.bone("interior", [*dashboard(-0.75, 0.75, 1.0, 0.4), *steering_wheel(-0.38, 1.12, 0.2), *seat(-0.38, 0.78, -0.1), *seat(0.38, 0.78, -0.1),
                        seat(0, 0.78, -1.15, width=1.4)[0], box(-0.72, 0.78, -1.55, 0.72, 1.23, -1.45, "seat")])
    m.bone("windshield", windshield(-0.75, 0.75, 1.0, 1.55, 0.45, paint))
    for name, r, f in (("fl", -0.72, 1.1), ("fr", 0.72, 1.1), ("rl", -0.72, -1.2), ("rr", 0.72, -1.2)):
        m.wheel(name, r, f, 0.38, 0.24, steer=f > 0)
    post_mg(m, 0, 0.45, 1.45, -0.65, gun_len=0.75)
    return m


def gaz67(paint="soviet_green"):
    """GAZ-67B: narrow open car with flat fenders and a long bonnet, DT machine gun on a pedestal."""
    m = open_car(Model(), paint, half=0.72, bonnet=(0.5, 1.7, 1.15), length=1.5)
    m.add("hull", [box(-0.5, 0.6, 1.7, 0.5, 1.1, 1.75, "black"), *headlights(0.62, 1.05, 1.5, 0.08)])
    for name, r, f in (("fl", -0.7, 1.15), ("fr", 0.7, 1.15), ("rl", -0.7, -1.0), ("rr", 0.7, -1.0)):
        m.wheel(name, r, f, 0.37, 0.24, steer=f > 0)
    post_mg(m, 0, 0.5, 1.5, -0.6, gun_len=0.7)
    return m


def universal_carrier(paint="khaki_green"):
    """Universal (Bren) Carrier: small open-topped tracked box; the Bren fires through the front plate."""
    m = Model()
    hull = [*open_tub(-0.95, 0.95, 0.35, 1.1, -1.6, 1.0, paint), *ramp(-0.95, 0.95, 1.0, 1.55, 0.35, 0.45, 1.1, 0.8, paint, steps=3),
            box(-0.95, 1.1, 0.75, 0.0, 1.3, 1.0, paint), box(0.0, 1.1, 0.75, 0.95, 1.25, 1.0, paint),  # gunner's / driver's front boxes
            box(-0.3, 0.4, -1.5, 0.3, 1.0, -0.5, "olive_dark")]  # engine cover in the middle
    m.bone("hull", hull)
    m.bone("interior", [*seat(0.45, 0.75, 0.25), *seat(-0.45, 0.75, 0.25), *seat(-0.6, 0.75, -0.95), *seat(0.6, 0.75, -0.95),
                        *steering_wheel(0.45, 1.0, 0.55)])
    for side in (-1, 1):
        m.tracks(side, side * 1.2, -1.6, 1.6, 0.7, 0.4, (-1.0, -0.2, 0.6), 0.3)
    post_mg(m, -0.45, 1.2, 1.3, 1.1, gun_len=0.6)
    return m


def sherman(paint="olive_drab"):
    """M4A3 Sherman: tall hull with sloped glacis and sponsons, round cast turret, 75mm M3 gun, VVSS bogies, .50 cal."""
    m = Model()
    tank_hull(m, paint, 1.35, -2.6, 1.6, 0.55, 1.95, 1.0, 1.0)
    m.add("hull", [*open_hatch(-0.5, 1.95, 1.2), *open_hatch(0.5, 1.95, 1.2), box(-0.9, 1.95, -2.4, 0.9, 2.0, -1.4, "metal")])
    m.bone("interior", [box(-1.25, 0.6, -2.5, 1.25, 0.65, 1.5, "interior"), *seat(-0.5, 0.95, 1.2), *seat(0.5, 0.95, 1.2)])
    for side in (-1, 1):
        m.tracks(side, side * 1.45, -2.5, 2.6, 1.0, 0.5, (-1.8, -1.3, -0.3, 0.2, 1.2, 1.7), 0.3)
        m.add(f"track_{'l' if side < 0 else 'r'}", [box(side * 1.45 - 0.2, 0.25, f - 0.4, side * 1.45 + 0.2, 0.75, f + 0.4, "olive_dark") for f in (-1.55, 0.0, 1.45)])  # bogies
    tank_turret(m, -0.1, 1.95, 2.65, 0, 0, 0, paint, gun_len=2.1, gun_r=0.07, round_radius=0.95, mantlet=0.32,
                open_hatches=((0.4, -0.35),), sight_side=0.45)
    cupola_mg(m, -0.1, 2.65, gun_len=0.85)
    return m


def cromwell(paint="khaki_green"):
    """Cromwell Mk IV: low boxy hull, slab-sided turret, 75mm gun, five large road wheels, track guards."""
    m = Model()
    tank_hull(m, paint, 1.15, -2.6, 2.2, 0.5, 1.55, 0.35, 1.2, fender=0.45)
    m.add("hull", [*open_hatch(-0.55, 1.55, 1.85)])
    m.bone("interior", [box(-1.05, 0.55, -2.5, 1.05, 0.6, 2.1, "interior"), *seat(-0.55, 0.7, 1.7)])
    for side in (-1, 1):
        m.tracks(side, side * 1.4, -2.6, 2.55, 1.0, 0.45, (-1.85, -0.95, -0.05, 0.85, 1.75), 0.42)
    tank_turret(m, 0.0, 1.55, 2.35, 0.95, 0.85, -0.95, paint, gun_len=2.0, gun_r=0.07, mantlet=0.3,
                hatches=((-0.45, -0.4),), open_hatches=((0.45, -0.4),), sight_side=0.5)
    return m


def panzer4(paint="dunkelgelb"):
    """Panzer IV Ausf. H: superstructure hull, box turret with side skirts, long 75mm KwK 40, Schürzen, eight road wheels."""
    m = Model()
    tank_hull(m, paint, 1.2, -2.7, 2.3, 0.5, 1.75, 0.35, 1.25, fender=0.4)
    m.add("hull", [box(-1.25, 1.2, 1.6, 1.25, 1.75, 2.3, paint), *open_hatch(-0.6, 1.75, 1.8), *open_hatch(0.6, 1.75, 1.8),
                   *[box(s * 1.75, 0.65, -2.4, s * 1.8, 1.4, 2.0, paint) for s in (-1, 1)]])  # Schürzen
    m.bone("interior", [box(-1.1, 0.55, -2.6, 1.1, 0.6, 2.2, "interior"), *seat(-0.6, 0.95, 1.8)])
    for side in (-1, 1):
        m.tracks(side, side * 1.45, -2.6, 2.45, 0.85, 0.45, (-1.95, -1.4, -0.85, -0.3, 0.25, 0.8, 1.35, 1.9), 0.25)
    tank_turret(m, -0.2, 1.75, 2.45, 0.85, 0.75, -1.0, paint, gun_len=2.8, gun_r=0.06, mantlet=0.25, muzzle_brake=True,
                open_hatches=((0, -0.75),), sight_side=0.35,
                extras=[box(s * 1.2, 1.7, -1.3, s * 1.25, 2.4, 0.4, paint) for s in (-1, 1)])  # turret skirts
    return m


def tiger1(paint="dunkelgelb"):
    """Tiger I: big slab hull, wide box turret, 88mm KwK 36 with muzzle brake, interleaved road wheels."""
    m = Model()
    tank_hull(m, paint, 1.55, -2.95, 2.5, 0.5, 1.9, 0.4, 1.4, fender=0.5)
    m.add("hull", [*open_hatch(-0.65, 1.9, 2.0), *open_hatch(0.65, 1.9, 2.0), box(-1.0, 1.9, -2.8, 1.0, 1.96, -1.7, "metal")])
    m.bone("interior", [box(-1.45, 0.55, -2.85, 1.45, 0.6, 2.4, "interior"), *seat(-0.65, 1.05, 2.0)])
    for side in (-1, 1):
        m.tracks(side, side * 1.75, -2.8, 2.8, 0.95, 0.6, (-2.1, -1.4, -0.7, 0.0, 0.7, 1.4, 2.1), 0.42)
    tank_turret(m, -0.2, 1.9, 2.7, 1.1, 1.0, -1.15, paint, gun_len=3.3, gun_r=0.08, mantlet=0.4, muzzle_brake=True,
                open_hatches=((-0.5, -0.6),), hatches=((0.5, -0.6),), sight_side=0.5)
    return m


def t34_85(paint="soviet_green"):
    """T-34-85: long sloped glacis, three-man cast turret, 85mm ZiS-S-53, five big road wheels, external fuel tanks."""
    m = Model()
    tank_hull(m, paint, 1.25, -2.7, 1.3, 0.45, 1.55, 1.4, 0.7, fender=0.4)
    m.add("hull", [*open_hatch(-0.55, 1.55, 1.15), *drum(-1.65, -1.25, 1.45, -1.8, 0.22, "olive_dark"), *drum(1.25, 1.65, 1.45, -1.8, 0.22, "olive_dark"),
                   *ramp(-1.2, 1.2, -2.7, -3.0, 0.5, 0.7, 1.55, 1.2, paint, steps=2)])
    m.bone("interior", [box(-1.15, 0.5, -2.6, 1.15, 0.55, 1.3, "interior"), *seat(-0.55, 0.75, 0.95)])
    for side in (-1, 1):
        m.tracks(side, side * 1.45, -2.9, 2.7, 1.0, 0.5, (-2.0, -1.05, -0.1, 0.85, 1.8), 0.43)
    tank_turret(m, -0.1, 1.55, 2.3, 0.95, 0.8, -1.05, paint, gun_len=3.0, gun_r=0.07, mantlet=0.3,
                open_hatches=((0.35, -0.5),), sight_side=0.45, extras=[box(-0.3, 2.3, -0.75, 0.3, 2.45, -0.25, "metal")])
    return m


# ------------------------------------------------------------------------------------------- emplacements and mines
def mortar(tube_len=1.2, radius=0.06, paint="olive_drab", plate="metal", plate_size=0.35, round_plate=False):
    """Mortar: baseplate on the ground, tube pivoting at its base (yaw bone `mount`, pitch bone `tube`), bipod and sight.
    The gunner kneels behind it (mount `main`)."""
    m = Model()
    base = round_turret(0, 0, 0.08, 0, plate_size, plate) if round_plate else [box(-plate_size, 0, -plate_size, plate_size, 0.08, plate_size, plate)]
    m.bone("hull", base + [box(-0.08, 0.08, -0.08, 0.08, 0.16, 0.08, "metal")])  # socket
    # Bipod and traversing gear stand on the ground ahead of the base and turn with the tube.
    m.bone("mount", [box(-0.32, 0, 0.5, -0.28, 0.62, 0.54, paint, slope=12, at=(-0.3, 0, 0.52)),
                     box(0.28, 0, 0.5, 0.32, 0.62, 0.54, paint, slope=12, at=(0.3, 0, 0.52)),
                     box(-0.3, 0.55, 0.47, 0.3, 0.6, 0.53, "metal"), box(-0.03, 0.55, 0.47, 0.03, 0.85, 0.53, "metal")], at=(0, 0.12, 0))
    m.bone("tube", [*barrel(0, 0.12, 0.0, tube_len, radius, paint), box(-radius * 1.4, 0.12 - radius * 1.4, -0.06, radius * 1.4, 0.12 + radius * 1.4, 0.06, "metal"),
                    box(-radius * 1.25, 0.12 - radius * 1.25, tube_len - 0.06, radius * 1.25, 0.12 + radius * 1.25, tube_len, "metal"),
                    box(radius, 0.12 + radius, 0.4, radius + 0.1, 0.12 + radius + 0.12, 0.5, "black")],  # sight
           parent="mount", at=(0, 0.12, 0))
    m.bone("muzzle_flash", [box(-radius * 2, 0.12 - radius * 2, tube_len, radius * 2, 0.12 + radius * 2, tube_len + 0.4, "flash")], parent="tube", at=(0, 0.12, 0))
    mount(m, "main", (0, 0.12, 0), [0, 0, tube_len + 0.1], [radius + 0.05, radius + 0.25, 0.45])
    return m


def mine_icon(kind, paint="olive_drab"):
    """Vehicle-space boxes for mine items (laid flat, `up` = up). kind: teller (big flat disc with a fuze), m15 (square-ish
    AT mine), m1a1, mk5 (round AT mines), m14 (small plastic AP), s_mine (canister with prongs), pmd6 (wooden box)."""
    if kind == "teller":
        return round_turret(0, 0, 0.1, 0, 0.32, paint) + round_turret(0, 0.1, 0.13, 0, 0.12, "metal") + [box(-0.34, 0.03, -0.03, -0.3, 0.07, 0.03, "metal")]
    if kind == "m15":
        return round_turret(0, 0, 0.12, 0, 0.33, paint) + round_turret(0, 0.12, 0.15, 0, 0.1, "black") + [box(0.3, 0.05, -0.04, 0.38, 0.09, 0.04, "metal")]
    if kind in ("m1a1", "mk5"):
        return round_turret(0, 0, 0.11, 0, 0.22, paint) + round_turret(0, 0.11, 0.15, 0, 0.07, "bronze")
    if kind == "m14":
        return round_turret(0, 0, 0.05, 0, 0.05, "olive") + round_turret(0, 0.05, 0.065, 0, 0.035, "black")
    if kind == "s_mine":
        return round_turret(0, 0, 0.16, 0, 0.06, paint) + [box(-0.005, 0.16, -0.005, 0.005, 0.2, 0.005, "metal"),
                                                          box(0.015, 0.16, -0.005, 0.025, 0.2, 0.005, "metal"), box(-0.025, 0.16, -0.005, -0.015, 0.2, 0.005, "metal")]
    if kind == "pmd6":
        return [box(-0.1, 0, -0.05, 0.1, 0.06, 0.05, "wood"), box(-0.1, 0.06, -0.05, 0.1, 0.065, 0.05, "wood"), box(0.04, 0.065, -0.01, 0.06, 0.08, 0.01, "metal")]
    raise ValueError(kind)
