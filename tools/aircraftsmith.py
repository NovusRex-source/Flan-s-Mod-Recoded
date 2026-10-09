"""
Aircraft geometry for the vehicle pack generators: piston fighters (WW2 pack) and helicopters (Vehicles pack).

Same frame, materials and Model as vehiclesmith: vehicle space [right, up, forward] in blocks, origin on the ground
under the centre of the footprint, models facing north (-Z). Aircraft are modelled in level flight attitude; on the
ground they rest on their wheels or skids (tail wheels on long struts, so the fuselage stays level).

Bones the vehicle renderer animates:
- `propeller` spins around the forward axis, `rotor` around the vertical axis, `tail_rotor` around the sideways axis
  (each while the engine runs; children such as `blades` turn with them).
- `wing_l` / `wing_r` are hidden when the wing part is shot off (and the plane loses that wing's lift).
- `muzzle_flash*` show briefly after a crew member fired.

Every builder registers its weapon positions in `Model.mounts` (pivot, muzzle, sight), like vehiclesmith's mounts, so
the definitions take them over and model and ballistics agree. Fixed guns fire along the nose: their pivot is the
pilot's eye, the sight is the reflector gunsight just ahead of it.
"""
import math

import vehiclesmith as vs
from vehiclesmith import box


# ------------------------------------------------------------------------------------------- shapes
def fuselage(sections, u, paint):
    """Round body along forward from (f0, f1, radius) sections: pixel circles in the right/up plane."""
    return [b for f0, f1, radius in sections for b in vs.face_disc(0, u, f0, f1, radius, paint)]


def wing(side, u, lead, root, tip, half_span, paint, r0=0.4, segments=6, elliptical=False, t=0.1):
    """One wing ([side] -1 left, +1 right) from [r0] out to [half_span]: chord from [root] to [tip] (or an elliptical
    planform), leading edge at [lead] at the root, sweeping back with the chord (straight trailing edge feel)."""
    out = []
    for i in range(segments):
        a = r0 + (half_span - r0) * i / segments
        b = r0 + (half_span - r0) * (i + 1) / segments
        x = (i + 0.5) / segments
        chord = root * math.sqrt(max(0.08, 1 - x * x)) if elliptical else root + (tip - root) * x
        front = lead - (root - chord) * 0.3
        out.append(box(side * a, u, front - chord, side * b, u + t, front, paint))
    return out


def rotated(b, angle, centre):
    """A vehiclesmith box as a GeckoLib cube turned [angle] degrees around the forward axis through [centre]."""
    c = vs.cube(b)
    c["rotation"] = [0, 0, angle]
    c["pivot"] = vs.pivot(*centre)
    return c


def roundel(r, u, f, kind, size=0.35, vertical=False):
    """National marking: on top of a wing (circle in the right/forward plane), or [vertical] on a fuselage side
    (circle in the up/forward plane, [r] = the side's surface). uk: RAF roundel, us: star and bar, de: Balkenkreuz,
    su: red star (as a disc)."""
    def disc(radius, colour, layer):
        if vertical:
            return vs.wheel_disc(r + math.copysign(layer * 0.01, r), f, u, radius, 0.02, colour)
        return vs.round_turret(r, u + layer * 0.01, u + layer * 0.01 + 0.012, f, radius, colour)

    def bar(w, h, colour, layer):
        if vertical:
            rr = r + math.copysign(layer * 0.01, r)
            return [box(rr - 0.01, u - h, f - w, rr + 0.01, u + h, f + w, colour)]
        return [box(r - w, u + layer * 0.01, f - h, r + w, u + layer * 0.01 + 0.012, f + h, colour)]

    if kind == "uk":
        return disc(size, "navy", 0) + disc(size * 0.66, "white", 1) + disc(size * 0.33, "red", 2)
    if kind == "us":
        return bar(size * 1.6, size * 0.28, "white", 0) + disc(size, "navy", 1) + disc(size * 0.5, "white", 2)
    if kind == "de":
        return bar(size, size * 0.34, "white", 0) + bar(size * 0.34, size, "white", 0) + \
            bar(size * 0.85, size * 0.18, "black", 1) + bar(size * 0.18, size * 0.85, "black", 1)
    return disc(size, "white", 0) + disc(size * 0.85, "red", 1)  # su


# ------------------------------------------------------------------------------------------- fighters
# Fighter frame: fuselage axis height, the pilot's seat (cushion), the nose.
AXIS = 1.55
SEAT = (0.0, 1.3, 0.1)
EYE = (0.0, 2.3, 0.1)


REAR_SEAT = (0.0, 1.3, -1.5)


def fighter(paint, marking, *, underside=None, nose=3.0, spinner="black", blades=3, elliptical=False, span=4.2,
            root=1.5, tip=0.7, slim=1.0, scoop=False, cowl_guns=False, gun_r=1.7, rear_gunner=False, bombs=1, spats=False):
    """Single-engine piston fighter or attack plane, about 0.75 scale (the Vehicles pack's scale): round fuselage
    tapering to the tail, low wings, tail plane and fin, canopy, propeller with spinner, main gear under the wings
    (or fixed, spatted) and a tail wheel. [cowl_guns]: muzzle flash above the nose (guns firing through the
    propeller), else at the wing guns. [rear_gunner]: a long canopy with a rear gunner's machine gun on a ring (mount
    `rear`, bones `rear_ring`/`rear_mg`). [bombs]: 1 = one bomb under the belly (bone `secondary_0`), more = that many
    under the wing roots (bones `secondary_0_<n>`, shown while the pilot's rack holds more than n)."""
    m = vs.Model()
    under = underside or paint
    s = slim
    body = fuselage([(1.6, nose, 0.55 * s), (-0.4, 1.6, 0.6 * s), (-1.2, -0.4, 0.55 * s), (-2.0, -1.2, 0.47 * s),
                     (-2.8, -2.0, 0.38 * s), (-3.4, -2.8, 0.28 * s), (-3.9, -3.4, 0.2 * s)], AXIS, paint)
    body += [box(-0.5 * s, AXIS - 0.62 * s, -1.6, 0.5 * s, AXIS - 0.3, 1.6, under)]  # belly
    # Exhaust stubs on both sides of the cowling.
    body += [box(side * 0.5 * s, AXIS + 0.05, 2.0, side * 0.62 * s, AXIS + 0.15, 2.7, "black") for side in (-1, 1)]
    if scoop:  # belly radiator scoop (Mustang)
        body += [box(-0.3, AXIS - 0.95, -1.5, 0.3, AXIS - 0.55, 0.0, paint), box(-0.25, AXIS - 0.9, -0.05, 0.25, AXIS - 0.6, 0.0, "black")]
    # Tail plane and fin with rudder.
    body += wing(-1, AXIS - 0.02, -3.0, 0.75, 0.4, 1.35, paint, r0=0.15, segments=3, t=0.07)
    body += wing(1, AXIS - 0.02, -3.0, 0.75, 0.4, 1.35, paint, r0=0.15, segments=3, t=0.07)
    body += [box(-0.05, AXIS, -3.95, 0.05, AXIS + 0.6, -3.1, paint), box(-0.05, AXIS + 0.6, -3.9, 0.05, AXIS + 1.05, -3.35, paint),
             box(-0.055, AXIS + 0.1, -4.05, 0.055, AXIS + 0.95, -3.9, under)]
    # Wing centre section (under the fuselage, stays when a wing is shot off) and the wing roots' fairings.
    uw = AXIS - 0.45
    body += [box(-0.45, uw, 1.25 - root, 0.45, uw + 0.12, 1.25, under)]
    # Canopy: glass panes around the pilot's head, windscreen, frame, and the spine behind it.
    c0, c1, top = (-1.95 if rear_gunner else -0.35), 0.75, AXIS + 0.95
    # The rear gunner's end of a long canopy is open (slid forward), so the gun can turn.
    g0 = -0.9 if rear_gunner else c0
    body += [box(-0.36, AXIS + 0.45, c0, -0.32, top, c1, "glass"), box(0.32, AXIS + 0.45, c0, 0.36, top, c1, "glass"),
             box(-0.36, top - 0.04, g0, 0.36, top, c1, "glass"), box(-0.36, AXIS + 0.45, c0, 0.36, AXIS + 0.6, c0 + 0.04, "glass"),
             vs.plate(-0.33, 0.33, c1, top, c1 + 0.45, AXIS + 0.55, "glass", t=0.04),
             box(-0.37, top - 0.05, c1 - 0.05, 0.37, top + 0.01, c1, "black"), box(-0.02, top - 0.02, c0, 0.02, top + 0.01, c1, "black")]
    body += vs.ramp(-0.22, 0.22, -2.8, c0, AXIS + 0.3, AXIS + 0.3, AXIS + 0.45, (AXIS + 0.6) if rear_gunner else top - 0.1, paint, steps=4)
    # Markings: on top of both wings and on the fuselage sides.
    for side in (-1, 1):
        body += roundel(side * span * 0.62, uw + 0.1, 1.25 - root * 0.45, marking, size=0.36)
        body += roundel(side * 0.48 * s, AXIS, -2.0, marking, size=0.3, vertical=True)
    m.bone("hull", body)

    # Cockpit inside: seat, instrument panel, stick and the reflector gunsight.
    m.bone("interior", vs.seat(SEAT[0], SEAT[1], SEAT[2], width=0.45) + vs.dashboard(-0.35, 0.35, AXIS + 0.55, 0.85, gauges=3) +
           [box(-0.03, SEAT[1], 0.45, 0.03, SEAT[1] + 0.45, 0.5, "black"), box(-0.06, AXIS + 0.55, 0.85, 0.06, AXIS + 0.7, 0.9, "glass")] +
           (vs.seat(*REAR_SEAT, width=0.45) if rear_gunner else []))
    if rear_gunner:
        vs.post_mg(m, 0, AXIS + 0.3, AXIS + 0.6, -1.15, gun_len=0.6, gun_bone="rear_mg", turret_bone="rear_ring", name="rear",
                   flash_bone="muzzle_flash_rear")

    # Wings: own bones (hidden when shot off), with gun barrels in the leading edges.
    for side, bone in ((-1, "wing_l"), (1, "wing_r")):
        parts = wing(side, uw, 1.25, root, tip, span, paint, r0=0.45, elliptical=elliptical)
        parts += [box(side * (span - 0.6), uw - 0.01, 1.25 - root * 0.6, side * (span - 0.1), uw, 1.25 - root * 0.2, under)]
        parts += vs.barrel(side * gun_r, uw + 0.05, 1.2, 1.6, 0.03, "metal")
        m.bone(bone, parts, at=(side * 0.45, uw, 0))

    # Landing gear: main legs under the wings, tail wheel on a long strut.
    gear = []
    for side in (-1, 1):
        gear += [box(side * 1.1 - 0.05, 0.3, 0.85, side * 1.1 + 0.05, uw, 0.95, "steel")]
        gear += vs.wheel_disc(side * 1.1, 0.9, 0.3, 0.3, 0.14, "tyre")
        if spats:  # fixed gear in streamlined fairings (Stuka)
            gear += [box(side * 1.1 - 0.12, 0.15, 0.45, side * 1.1 + 0.12, 0.62, 1.3, paint), box(side * 1.1 - 0.1, 0.62, 0.6, side * 1.1 + 0.1, uw, 1.1, paint)]
    gear += [box(-0.04, 0.12, -3.45, 0.04, AXIS - 0.15, -3.37, "steel")] + vs.wheel_disc(0, -3.41, 0.12, 0.12, 0.08, "tyre")
    m.bone("gear", gear)

    # Propeller: spinner and blades turning around the forward axis.
    hub = (0, AXIS, nose + 0.1)
    prop = vs.face_disc(0, AXIS, nose, nose + 0.25, 0.25, spinner) + vs.face_disc(0, AXIS, nose + 0.25, nose + 0.4, 0.14, spinner)
    m.bone("propeller", prop, at=hub)
    blade = box(-0.09, AXIS, nose + 0.08, 0.09, AXIS + 1.25, nose + 0.13, "black")
    pb = next(b for b in m.bones if b["name"] == "propeller")
    if blades == 4:
        pb["cubes"] += [vs.cube(box(-0.09, AXIS - 1.25, nose + 0.08, 0.09, AXIS + 1.25, nose + 0.13, "black")),
                        vs.cube(box(-1.25, AXIS - 0.09, nose + 0.08, 1.25, AXIS + 0.09, nose + 0.13, "black"))]
    else:
        pb["cubes"] += [rotated(blade, a, hub) for a in (0, 120, -120)]

    # Muzzle flash: above the nose for cowling guns, else at both wing guns.
    if cowl_guns:
        flash = [box(-0.12, AXIS + 0.45, nose + 0.1, 0.12, AXIS + 0.6, nose + 0.45, "flash")]
        muzzle_at = (0, AXIS + 0.52, nose + 0.2)
    else:
        flash = [box(side * gun_r - 0.08, uw - 0.02, 1.6, side * gun_r + 0.08, uw + 0.12, 1.9, "flash") for side in (-1, 1)]
        muzzle_at = (0, uw + 0.05, 1.7)
    m.bone("muzzle_flash", flash)
    fixed_mount(m, "guns", EYE, muzzle_at)

    # Bombs (the pilot's secondary weapon), shown while loaded.
    def bomb(r, u, f, length, radius):
        return vs.face_disc(r, u, f - length / 2, f + length / 2, radius, "olive_drab") + \
            [box(r - radius * 1.3, u - 0.01, f - length / 2 - 0.2, r + radius * 1.3, u + 0.01, f - length / 2, "black"),
             box(r - 0.01, u - radius * 1.3, f - length / 2 - 0.2, r + 0.01, u + radius * 1.3, f - length / 2, "black"),
             box(r - 0.02, u + radius, f - 0.05, r + 0.02, uw if abs(r) > 0.5 else AXIS - 0.5, f + 0.05, "metal")]  # crutch
    if bombs == 1:
        m.bone("secondary_0", bomb(0, 0.62, 0.3, 1.0, 0.18))
    else:
        racks = [r * side for r in (1.55, 2.15, 2.75, 3.3) for side in (-1, 1)][:bombs]
        for n, r in enumerate(racks):
            m.bone(f"secondary_0_{n}", bomb(r, uw - 0.2, 0.55, 0.7, 0.12))
    return m


# Where the pilot's bombs leave the plane: under the belly, between the main wheels (vehicle space).
BOMB_RELEASE = [0.0, 0.45, 0.3]


def fixed_mount(m, name, eye, muzzle_at):
    """A gun firing along the nose: pivot at the pilot's eye, sight just ahead of it, muzzle relative to the pivot."""
    vs.mount(m, name, eye, [muzzle_at[i] - eye[i] for i in range(3)], [0, 0, 0.35])


def spitfire():
    return fighter("khaki_green", "uk", underside="white", elliptical=True, span=4.3, root=1.6, slim=0.95)


def bf109():
    return fighter("panzer_grey", "de", underside="white", nose=2.9, spinner="white", slim=0.9, span=3.9, root=1.4, tip=0.75, cowl_guns=True)


def p51():
    return fighter("chrome", "us", nose=3.1, blades=4, scoop=True, span=4.2, root=1.65, tip=0.65, gun_r=1.6)


def yak3():
    return fighter("soviet_green", "su", underside="white", nose=2.9, spinner="red", slim=0.9, span=3.7, root=1.45, cowl_guns=True)


# Two-seat attack planes with a rear gunner and four bombs under the wings.
def ju87():
    return fighter("panzer_grey", "de", underside="white", nose=2.9, slim=1.0, span=4.3, root=1.6, tip=0.8, rear_gunner=True, bombs=4, spats=True,
                   gun_r=2.2)


def sbd():
    return fighter("navy", "us", underside="white", nose=3.0, span=4.4, root=1.7, tip=0.8, rear_gunner=True, bombs=4, cowl_guns=True)


def il2():
    return fighter("soviet_green", "su", underside="white", nose=3.2, spinner="black", slim=1.05, span=4.4, root=1.7, tip=0.75, rear_gunner=True,
                   bombs=4, gun_r=1.9)


def fairey_battle():
    return fighter("khaki_green", "uk", underside="black", nose=3.1, span=4.6, root=1.8, tip=0.8, rear_gunner=True, bombs=4, gun_r=2.4)


# ------------------------------------------------------------------------------------------- helicopters
def rotor(m, u, f, radius, blades=2, mat="black", parent="body"):
    """Main rotor on its mast: hub (`rotor`, spinning) with the blades in a child bone `blades` (left out of item icons)."""
    m.bone("rotor", [box(-0.2, u - 0.08, f - 0.2, 0.2, u + 0.08, f + 0.2, "metal")], parent=parent, at=(0, u, f))
    cubes = [box(-0.2, u + 0.02, f - radius, 0.2, u + 0.06, f + radius, mat)]
    if blades == 4:
        cubes.append(box(-radius, u + 0.07, f - 0.2, radius, u + 0.11, f + 0.2, mat))
    m.bone("blades", cubes, parent="rotor", at=(0, u, f))


def tail_rotor(m, r, u, f, radius, mat="black"):
    """Tail rotor on the side of the fin, spinning around the sideways axis."""
    m.bone("tail_rotor", [box(r - 0.04, u - radius, f - 0.08, r + 0.04, u + radius, f + 0.08, mat),
                          box(r - 0.04, u - 0.08, f - radius, r + 0.04, u + 0.08, f + radius, mat),
                          box(r - 0.07, u - 0.07, f - 0.07, r + 0.07, u + 0.07, f + 0.07, "metal")], at=(r, u, f))


def skids(half, f0, f1, floor, mat="steel"):
    """Landing skids: two tubes on the ground with turned-up tips, cross tubes and struts up to the floor."""
    out = []
    for side in (-1, 1):
        r = side * half
        out += [box(r - 0.05, 0, f0, r + 0.05, 0.1, f1, mat), vs.plate(r - 0.05, r + 0.05, f1, 0.1, f1 + 0.35, 0.3, mat, t=0.1, below=False)]
    for f in (f0 + 0.4, f1 - 0.5):
        out += [box(-half, 0.08, f - 0.05, half, 0.16, f + 0.05, mat)]
        out += [box(side * half * 0.8 - 0.04, 0.12, f - 0.04, side * half * 0.8 + 0.04, floor, f + 0.04, mat) for side in (-1, 1)]
    return out


def tail_boom(f0, f1, u0, u1, h0, h1, w0, w1, paint, steps=5):
    """Tapering boom from the cabin at [f0] back to [f1]: centre height u0->u1, half height h0->h1, half width w0->w1."""
    out = []
    for i in range(steps):
        x = (i + 0.5) / steps
        a, b = f0 + (f1 - f0) * i / steps, f0 + (f1 - f0) * (i + 1) / steps
        u, h, w = u0 + (u1 - u0) * x, h0 + (h1 - h0) * x, w0 + (w1 - w0) * x
        out.append(box(-w, u - h, b, w, u + h, a, paint))
    return out


def huey(paint="olive_drab"):
    """UH-1H "Huey": cockpit with a big glazed nose, open cargo doors with an M60 on a post in each, troop bench,
    engine on the roof, two-blade main rotor, long tail boom with fin and tail rotor, skids."""
    m = vs.Model()
    floor, roof = 0.55, 2.35
    hull = skids(1.0, -1.5, 2.1, floor)
    hull += [box(-1.2, floor - 0.1, -1.5, 1.2, floor + 0.05, 2.0, paint),  # belly
             box(-1.2, floor, -1.5, 1.2, roof, -1.4, paint),  # rear wall
             box(-1.2, roof, -1.6, 1.2, roof + 0.1, 2.0, paint)]  # roof
    for side in (-1, 1):
        r = side * 1.2
        hull += [box(r - 0.08, floor, 0.85, r + 0.08, roof, 0.97, paint),  # pillar between cockpit and cargo door
                 box(r - 0.06, floor, 0.97, r + 0.06, 1.3, 2.0, paint),  # cockpit door below the window
                 box(r - 0.03, 1.3, 0.97, r + 0.03, roof, 2.0, "glass"),
                 box(r + side * 0.02, floor + 0.05, -1.55, r + side * 0.12, roof - 0.05, -0.45, paint),  # cargo door slid back
                 box(r - 0.06, floor, -1.5, r + 0.06, floor + 0.2, 0.85, paint)]  # door sill
    # Nose: lower nose in paint, big sloped windscreen and chin windows.
    hull += vs.ramp(-1.1, 1.1, 2.0, 2.7, floor - 0.1, floor + 0.15, 1.3, 1.15, paint, steps=3)
    hull += [vs.plate(-1.1, 1.1, 2.0, roof + 0.05, 2.7, 1.3, "glass", t=0.05),
             box(-0.03, 1.3, 2.0, 0.03, roof, 2.7, paint)]  # windscreen centre post
    # Engine and transmission on the roof, exhaust, mast.
    hull += [box(-0.6, roof + 0.1, -1.6, 0.6, 2.95, 0.8, paint), box(-0.25, 2.55, -2.05, 0.25, 2.85, -1.6, "black"),
             box(-0.08, 2.95, 0.15, 0.08, 3.25, 0.31, "metal")]
    # Tail boom, elevators, fin and markings.
    hull += tail_boom(-1.6, -6.6, 1.9, 2.15, 0.42, 0.15, 0.45, 0.16, paint)
    hull += [box(-0.9, 1.95, -4.75, 0.9, 2.0, -4.35, paint), box(-0.06, 2.0, -6.85, 0.06, 3.1, -6.35, paint, slope=25, at=(0, 2.0, -6.6))]
    hull += [box(-0.47, 1.7, -3.2, -0.45, 2.1, -2.2, "white"), box(0.45, 1.7, -3.2, 0.47, 2.1, -2.2, "white")]
    m.bone("hull", hull)
    m.bone("interior", vs.seat(0.55, 1.0, 1.5) + vs.seat(-0.55, 1.0, 1.5) + vs.dashboard(-1.0, 1.0, 1.55, 2.0, gauges=6) +
           [box(x - 0.03, 1.0, 1.85, x + 0.03, 1.45, 1.9, "black") for x in (-0.55, 0.55)] +
           vs.seat(0, 1.0, -1.05, width=1.8) + [box(-1.15, floor, -0.7, 1.15, floor + 0.04, 0.8, "rubber")])
    rotor(m, 3.3, 0.23, 5.4)
    tail_rotor(m, -0.2, 2.85, -6.55, 0.75)
    for side, n in ((-1, "l"), (1, "r")):
        vs.post_mg(m, side * 1.05, floor, 1.75, -0.2, gun_len=0.8, gun_bone=f"mg_{n}", turret_bone=f"door_{n}", name=f"door_{n}",
                   flash_bone=f"muzzle_flash_{n}")
    return m


def little_bird(paint="black"):
    """AH-6 Little Bird: small egg-shaped cabin with two crew, outriggers carrying a minigun (left) and a rocket pod
    (right), four-blade rotor, thin tail boom with T-tail and tail rotor, skids. Both weapons fire along the nose:
    the pilot's minigun, the co-pilot's rockets."""
    m = vs.Model()
    floor, roof = 0.5, 2.05
    hull = skids(0.75, -1.0, 1.4, floor)
    hull += vs.cabin(-0.75, 0.75, floor, roof, -0.6, 1.3, paint, belt=1.15, glass_back=False)
    hull += vs.ramp(-0.7, 0.7, 1.3, 1.85, floor, floor + 0.25, 1.2, 1.0, paint, steps=3)
    hull += [vs.plate(-0.7, 0.7, 1.3, roof, 1.85, 1.2, "glass", t=0.05)]
    hull += [box(-0.55, 1.15, -1.45, 0.55, 2.05, -0.6, paint), box(-0.15, 1.5, -1.75, 0.15, 1.8, -1.45, "black"),
             box(-0.08, roof, -0.08, 0.08, 2.3, 0.08, "metal")]
    hull += tail_boom(-1.45, -4.3, 1.55, 1.75, 0.18, 0.1, 0.18, 0.09, paint, steps=4)
    hull += [box(-0.05, 1.65, -4.45, 0.05, 2.35, -4.05, paint, slope=20, at=(0, 1.65, -4.25)), box(-0.7, 2.3, -4.55, 0.7, 2.35, -4.2, paint)]
    # Outriggers across the cabin floor, minigun left, rocket pod right.
    hull += [box(-1.55, 0.95, 0.05, 1.55, 1.03, 0.3, "metal")]
    hull += [box(-1.45, 0.75, -0.3, -1.3, 0.95, 0.5, "metal")] + vs.barrel(-1.38, 0.82, 0.5, 1.35, 0.06, "black")
    hull += vs.face_disc(1.38, 0.78, -0.35, 0.85, 0.2, "olive_dark") + vs.face_disc(1.38, 0.78, 0.85, 0.88, 0.14, "black")
    m.bone("hull", hull)
    m.bone("interior", vs.seat(0.3, 0.8, 0.5, width=0.45) + vs.seat(-0.3, 0.8, 0.5, width=0.45) + vs.dashboard(-0.6, 0.6, 1.3, 1.2, gauges=4))
    rotor(m, 2.35, 0.0, 4.0, blades=4)
    tail_rotor(m, -0.15, 2.0, -4.3, 0.5)
    m.bone("muzzle_flash", [box(-1.48, 0.72, 1.35, -1.28, 0.92, 1.6, "flash"), box(1.25, 0.65, 0.88, 1.51, 0.91, 1.1, "flash")])
    pilot_eye, copilot_eye = (0.3, 1.8, 0.5), (-0.3, 1.8, 0.5)
    fixed_mount(m, "minigun", pilot_eye, (-1.38, 0.82, 1.4))
    fixed_mount(m, "rockets", copilot_eye, (1.38, 0.78, 1.0))
    return m
