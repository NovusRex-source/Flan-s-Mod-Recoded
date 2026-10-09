# Making content packs

Everything in Flan's Mod (guns, ammunition, attachments, grenades, uniforms, gear, vehicles, structure kits and
factions) comes from content packs. This guide builds a pack step by step. Every field of every file is listed in
**[DEFINITIONS.md](DEFINITIONS.md)**.

The three built-in packs (`src/main/resources/resourcepacks/`) and the small `example` pack
(`tools/generate_example_pack.py`) are complete, working references. Copy from them freely.

---

## 1. What a content pack is

A content pack is a **normal Minecraft pack**, a folder or zip with `pack.mcmeta`, `data/` and `assets/`, placed in
`<game directory>/contentpacks/`. Flan's adds that folder to both the client's resource packs and every world's data
packs, so one pack provides both halves:

- `data/<namespace>/flansmod/...`: the definitions (JSON), read by the server and sent to every client.
- `data/<namespace>/recipe/...` and `data/<namespace>/structure/...`: vanilla recipes and structure templates.
- `assets/<namespace>/...`: models, textures, sounds, language files, read by each client.

Definitions reload with `/reload`. Assets reload with F3+T.

```
mypack/
  pack.mcmeta
  data/mypack/
    flansmod/
      guns/ magazines/ ammo/ attachments/ grenades/ parts/
      clothing/ gear/ vehicles/ vehicle_upgrades/ factions/ structures/
    recipe/                 Weapons Bench recipes
    structure/              structure templates (.nbt)
  assets/mypack/
    geckolib/models/gun/<name>.geo.json          gun models (Blockbench, GeckoLib format)
    geckolib/animations/gun/<name>.animation.json
    textures/gun/<name>.png
    geckolib/models/vehicle/ ... animations/vehicle/ ... textures/vehicle/
    items/ models/item/ textures/item/           icons for ammo, magazines, attachments, ...
    equipment/ textures/entity/equipment/        uniforms
    sounds.json sounds/                          sound events
    lang/en_us.json
```

`pack.mcmeta` (Minecraft 26.3: resource format 97, data format 121):

```json
{
  "pack": { "description": "My Flan's content", "min_format": 97, "max_format": 121 },
  "flansmod": { "name": "My Pack", "icon": "mypack:carbine" }
}
```

The `flansmod` section is optional. With the client option *pack tabs*, it names the pack's own creative tab.

**Ids:** a file `data/mypack/flansmod/guns/carbine.json` defines the gun `mypack:carbine`. Other definitions refer to
it by that id. Names come from the `name` field; the lang key `<type>.<namespace>.<name>` overrides it (for example
`gun.mypack.carbine`).

---

## 2. A first gun

### The definition

`data/mypack/flansmod/guns/carbine.json`. Only `name` is required, but a useful gun sets more:

```json
{
  "name": "Carbine",
  "category": "rifle",
  "damage": 6,
  "rpm": 600,
  "fire_mode": "auto",
  "fire_modes": ["safe", "semi", "auto"],
  "reload_ticks": 40,
  "velocity": 12,
  "gravity": 0.03,
  "spread": 3,
  "ads_spread": 0.5,
  "ads_zoom": 1.5,
  "recoil": { "pitch": 1.2, "yaw": 0.6 },
  "magazines": ["mypack:carbine_mag"],
  "attachment_slots": ["sight", "muzzle"],
  "sounds": { "shoot": "mypack:carbine.shoot", "reload": "mypack:carbine.reload", "empty": "flansbasic:gun.empty" }
}
```

`category` sorts the gun in the creative tab and prices it in the battle shop (`pistol`, `smg`, `rifle`, `dmr`,
`sniper`, `shotgun`, `lmg`, `launcher`). Bullets are server-side points: `velocity` × `lifetime_ticks` is the range,
`gravity` and `drag` shape the drop.

### The model

Guns render with **GeckoLib**. Model, texture and animations default to paths derived from the id:

| File | Default path |
|---|---|
| model | `assets/mypack/geckolib/models/gun/carbine.geo.json` |
| animations | `assets/mypack/geckolib/animations/gun/carbine.animation.json` |
| texture | `assets/mypack/textures/gun/carbine.png` |

Or reuse another pack's files with `"model": { "geo": "flansbasic:gun/m4a1", "texture": "flansbasic:textures/gun/basic.png",
"animations": "flansbasic:gun/m4a1" }`, which is what the `example` pack does.

Build the model in Blockbench (*GeckoLib Animated Model*) with the barrel pointing **north (-Z)**. Bones with special
names are driven by the mod:

| Bone | Behaviour |
|---|---|
| `muzzle_flash` | shown briefly after every shot |
| `magazine`, `magazine_<magazine id path>` | the inserted magazine (hidden when empty); a child per magazine type |
| `round` | a launcher's projectile, visible while loaded or reloading |
| `attachment_<attachment id path>` | shown while that attachment is installed |
| `default_<slot>` | shown while the slot is empty (for example iron sights under `default_sight`) |
| `right_hand` / `left_hand` with `arm_right` / `arm_left` | first-person arms, drawn with the player's skin (vanilla skin UV layout) |

Animations named **`shoot`** and **`reload`** are played by the server's triggers.

**Display:** the gun's base item model has no transforms; the definition's `display` supplies them per context
(`firstperson_righthand`, `thirdperson_righthand`, `gui`, `ground`, `fixed`, `head`), with the same meaning as
vanilla item models, plus **`ads`** for the aimed pose. Missing contexts use sensible defaults. `rail_ads` lets sight
attachments aim through their own optics (see `ads_height` on attachments).

### Magazine and ammunition

`data/mypack/flansmod/magazines/carbine_mag.json`:

```json
{ "name": "Carbine Magazine (30)", "caliber": "carbine", "capacity": 30, "icon": "mypack:carbine_mag" }
```

`data/mypack/flansmod/ammo/carbine_fmj.json`:

```json
{ "name": "Carbine Round", "caliber": "carbine", "icon": "mypack:carbine_round" }
```

Rounds fit every magazine of their **caliber**. Name the caliber in the language file:
`"caliber.flansmod.carbine": "7.62×39mm"`. Ammo can change the shot (`damage_multiplier`, `armor_piercing`,
`fire_seconds`, `pellets`, `tracer`) or replace the bullet with a projectile (`projectile`: a grenade id, for rockets
and 40 mm grenades). Use `"internal": true` for magazines built into the gun (tube, clip, breech): they're never an
item, and reloading loads loose rounds. Use an existing caliber (`556`, `762x39`, `9mm`, ...) to share rounds with the
built-in guns.

**Icons:** `icon` names an item model (`assets/mypack/items/carbine_mag.json`, a normal vanilla item model
definition). Ammo, magazines, attachments, grenades, parts and gear show it automatically.

### A recipe

Guns are assembled at the **Weapons Bench** with recipe type `flansmod:weapon_assembly`: a shaped pattern of up to
6×4, with vanilla ingredients. Flan's items are matched by component with Fabric's `fabric:components` ingredient:

```json
{
  "type": "flansmod:weapon_assembly",
  "pattern": ["SRB", " G "],
  "key": {
    "S": { "fabric:type": "fabric:components", "base": "flansmod:part", "components": { "flansmod:part": "flansbasic:wood_stock" } },
    "R": { "fabric:type": "fabric:components", "base": "flansmod:part", "components": { "flansmod:part": "flansbasic:rifle_receiver" } },
    "B": { "fabric:type": "fabric:components", "base": "flansmod:part", "components": { "flansmod:part": "flansbasic:barrel" } },
    "G": "minecraft:iron_ingot"
  },
  "result": { "id": "flansmod:gun", "components": { "flansmod:gun": "mypack:carbine" } }
}
```

The result works the same way for every type: `flansmod:magazine` + `flansmod:magazine`, `flansmod:ammo` +
`flansmod:ammo_type`, `flansmod:attachment` + `flansmod:attachment`, `flansmod:grenade` + `flansmod:grenade`,
`flansmod:clothing` + `flansmod:clothing`, `flansmod:gear` + `flansmod:gear`, `flansmod:vehicle` + `flansmod:vehicle`,
`flansmod:structure` + `flansmod:structure`.

### Sounds

Declare sound events in `assets/mypack/sounds.json` (vanilla format) and refer to them by id (`mypack:carbine.shoot`).
The built-in `flansbasic:gun.*` sounds can be reused.

---

## 3. Attachments

```json
{ "name": "Red Dot", "slot": "sight", "ads_zoom": 1.8, "spread_multiplier": 0.7, "ads_height": 1.5, "icon": "mypack:red_dot" }
```

An attachment fits every gun that lists its `slot` in `attachment_slots` (or only the guns in `guns`). It can multiply
damage, spread, recoil, velocity and reload time, bring a `scope` (overlay texture, night vision, thermal), act as a
bipod or tripod (`deploy`), project a `laser`, hide tracers or the muzzle flash. Its look comes from the gun model's
`attachment_<name>` bone.

---

## 4. Grenades, launchers and mines

```json
{
  "name": "Breaching Charge",
  "fuse_ticks": 80,
  "explosion": { "power": 3.0, "block_damage": { "radius": 2.5, "max_resistance": 3.0, "chance": 0.7 } }
}
```

Effects combine: `explosion` (with `fire`, `break_blocks` for TNT-like destruction, or limited `block_damage`),
`smoke`, `flash`. `contact: true` detonates on impact; otherwise the grenade bounces until its fuse runs out. With
`"throwable": false` it's a launcher projectile (named by ammo's `projectile`). A `mine` section turns it into a mine:

```json
"mine": { "trigger": "vehicle", "arm_ticks": 60, "vehicle_damage": 300 }
```

---

## 5. Uniforms and gear

**Clothing** is worn and drawn by vanilla's equipment system:

```json
{ "name": "Field Jacket", "slot": "chest", "asset": "mypack:field", "icon": "mypack:field_jacket", "armor": 6, "plate_slots": 1 }
```

`asset` points to `assets/mypack/equipment/field.json`:

```json
{ "layers": { "humanoid": [{ "texture": "mypack:field" }], "humanoid_leggings": [{ "texture": "mypack:field" }] } }
```

The textures are `assets/mypack/textures/entity/equipment/humanoid/field.png` and `.../humanoid_leggings/field.png`
(vanilla armour layout). `plate_slots` gives the piece armour-plate slots.

**Gear** (`gear/`): `type` is `backpack` or `pouch` (`slots`), `medical` (`effects`, `consume_seconds`),
`binoculars` (`zoom`, `overlay`), `plate` (`armor`, `toughness`, `durability`), `parachute` (`fall_speed`,
`canopy`), or one of the field utilities: `map` (`range` = radius in blocks), `flashlight` (`range` = beam reach,
`light_level`) and `compass`.

---

## 6. Vehicles

```json
{
  "name": "Scout Car",
  "type": "car",
  "health": 60,
  "max_speed": 0.95,
  "acceleration": 0.028,
  "turn_speed": 5.0,
  "fuel": { "capacity": 24000, "type": "petrol" },
  "upgrade_slots": ["engine", "armor", "tyres", "tank"],
  "seats": [
    { "position": [-0.4, 0.55, 0.0] },
    { "position": [0.4, 0.55, 0.0] },
    { "position": [0.0, 1.1, -0.8], "gun": "mypack:car_mg", "turret": true,
      "pivot": [0.0, 1.6, -0.8], "muzzle": [0.0, 0.0, 1.0], "yaw_bone": "mg_yaw", "pitch_bone": "mg_pitch" }
  ],
  "parts": {
    "hull":   { "box": [-0.85, 0.45, -1.3, 0.85, 1.15, 0.6], "role": "hull" },
    "engine": { "box": [-0.8, 0.45, 0.6, 0.8, 1.15, 1.6], "health": 36, "role": "engine", "core_damage": 0.4 },
    "wheel_fl": { "box": [-0.9, 0, 0.67, -0.6, 0.76, 1.43], "health": 20, "role": "propulsion", "bones": ["wheel_fl"] }
  }
}
```

- **Vehicle space** is `[right, up, forward]` in blocks, from the centre of the footprint at ground level. Seat 0 is
  the driver. A seat's height is the top of its cushion.
- **Types:** `car` (wheels, steering), `tank` (tracks, turns on the spot), `static` (emplacements such as mortars,
  aimed with the movement keys), `plane` and `helicopter` (see [Aircraft](#aircraft) below).
- **Parts** are the hit boxes: only where a part is can bullets hit. Roles: `hull`, `engine` (no throttle when
  broken), `propulsion` (slower), `weapon` (+ `seat`: that seat's gun stops), `fuel_tank` (leaks). The `bones` of a
  broken part are hidden.
- **Seat guns** are ordinary gun definitions with `"mounted": true`, using the same magazines and ammunition.
- **Model:** `assets/<ns>/geckolib/models/vehicle/<name>.geo.json`, built facing north. Note that GeckoLib mirrors X,
  so Bedrock +X is the vehicle's **left** side. Driven bones: `wheel*` spin, `steer*` steer, each seat's `yaw_bone` /
  `pitch_bone` follow the aim, `muzzle_flash`, `upgrade_<name>` / `default_<slot>` for upgrades.

### Stationary guns, sentry turrets and artillery

- **Stationary guns** are emplacements with `"lay_with_keys": false` and a turret seat; `"yaw_limit": 70` on the seat
  limits the traverse to 70° either side of the front.
- **Sentry turrets** add a `sentry` section (`range`, `targets`: `monsters`, `enemies`, `aircraft`, `turn_speed`,
  `scan_ticks`). Unmanned, the turret aims and fires seat 0's gun by itself and reloads from its `storage`.
  `enemies`/`aircraft` only ever means fighters of an enemy team in the battle its owner (who placed it) fights in.
- **Artillery** is an emplacement laid with the keys (the default) whose gun fires projectile ammunition; the
  artillery map works for it. Seats that can fire below 45° get the flatter of the two trajectories.

### Anti-aircraft guns

An emplacement (`static`) with `"lay_with_keys": false` is aimed with the gunner's view through a turret seat instead
of being laid with the movement keys. Give its rounds a `flak` section (`proximity`, `power`, `fuse_ticks`): they
burst near aircraft (and elytra fliers, phantoms and ghasts) or when the fuse runs out, without breaking blocks.

### Aircraft

Planes and helicopters are vehicles of type `plane` or `helicopter` with a `flight` section. Top speed, acceleration,
drag, braking and `turn_speed` work as for other vehicles.

```json
{
  "name": "Fighter",
  "type": "plane",
  "max_speed": 2.2, "acceleration": 0.03, "drag": 0.01, "turn_speed": 4.0,
  "flight": { "lift_speed": 0.9, "pitch_speed": 2.5, "max_pitch": 60, "crash_speed": 0.5, "crash_damage": 100 },
  "seats": [
    { "position": [0.0, 1.3, 0.1], "gun": "mypack:wing_guns", "pivot": [0.0, 2.3, 0.1], "muzzle": [0.0, -1.2, 1.6] }
  ],
  "parts": {
    "hull":       { "box": [-0.6, 0.9, -4.0, 0.6, 2.5, 1.6], "role": "hull" },
    "gear":       { "box": [-1.3, 0, 0.5, 1.3, 1.0, 1.3], "role": "hull" },
    "engine":     { "box": [-0.55, 1.0, 1.6, 0.55, 2.1, 3.4], "health": 40, "role": "engine" },
    "wing_left":  { "box": [-4.2, 1.05, -0.3, -0.6, 1.3, 1.3], "health": 36, "role": "propulsion", "bones": ["wing_l"] },
    "wing_right": { "box": [0.6, 1.05, -0.3, 4.2, 1.3, 1.3], "health": 36, "role": "propulsion", "bones": ["wing_r"] }
  }
}
```

- **Planes:** W/S set the throttle, which stays where it was left. In the air the nose follows the pilot's view. Below
  `lift_speed` the wings stop carrying: the plane sinks and the nose drops. On the ground the plane taxis with A/D and
  takes off by pulling the nose up once it is fast enough.
- **Helicopters:** W/S fly forward and back, A/D turn, Space climbs and the sprint key descends (`climb_speed`).
  Without input the helicopter hovers. Without power (no fuel, broken engine or rotor) it autorotates down.
- **Damage:** wings and rotors should be `propulsion` parts, so shooting one off costs its share of lift. Hitting
  something faster than `crash_speed` damages the hull by `crash_damage` per block per tick above it.
- **Bombs and other second weapons:** a seat can have a `secondary` gun (fired with H) at `secondary_muzzle`. Give
  a bomb gun `"drop": true`: its projectile ammo leaves with the aircraft's velocity instead of being fired. Bones
  `secondary_<seat>` show while it is loaded, `secondary_<seat>_<n>` while it holds more than n rounds.
- **Gunner seats:** ordinary turret seats (`turret`, `yaw_bone`, `pitch_bone`), e.g. a rear gunner.
- **Fixed guns:** a seat gun without `turret` fires along the nose. Put its `pivot` at the crewman's eye and keep
  `sight` just ahead of it, so the reflector sight view (right click) looks along the guns.
- **The hit box includes the landing gear** (a part reaching down to 0), otherwise the aircraft sinks into the ground
  up to its lowest part.
- **Bones:** `propeller*` spin around the forward axis, `rotor*` around the vertical axis and `tail_rotor*` around the
  sideways axis while the engine runs. A broken wing hides its bone.

**Upgrades** (`vehicle_upgrades/`) fit a `slot` (optionally only some `vehicles` or `types`) and multiply speed,
acceleration, turning, health, fuel capacity and consumption, or add armour or cargo rows (`storage_bonus`).

**Cargo:** `"storage": 18` gives a vehicle that many slots (whole rows of 9, at most 54). Players open it from the
vehicle menu or with sneak + right click holding an item.

The vehicles of the built-in packs are generated by `tools/vehiclesmith.py` (models with interiors, mounts whose gun
positions match the definitions) and the aircraft by `tools/aircraftsmith.py`. Both make good templates.

---

## 7. Factions

`data/mypack/flansmod/factions/blue_army.json`:

```json
{ "name": "Blue Army", "color": "#3060C0", "icon": "mypack:carbine", "order": 0 }
```

Give guns, vehicles, grenades, clothing and gear `"faction": "mypack:blue_army"`. The faction gets a creative tab and
a coloured tooltip line. In battles, a team with that faction gets a shop filtered to its gear, and its bots wear and
carry only its equipment.

---

## 8. Structure kits

1. Build the structure in a world and save it with a vanilla **structure block** (mode *Save*). Its **north side**
   (-Z) is the front, which will face away from whoever places it. Air in the template digs out what's there;
   structure voids keep it.
2. Copy the `.nbt` from `<world>/generated/<namespace>/structure/` to `data/mypack/structure/outpost.nbt`.
3. Add `data/mypack/flansmod/structures/outpost.json`:

```json
{ "name": "Outpost", "template": "mypack:outpost", "category": "bunker", "y_offset": -1, "price": 900 }
```

`y_offset` sinks it into the ground: `-1` puts the template's floor in place of the surface, and `-3` makes a trench
two blocks deep. `template` defaults to the definition's own id. Give it a bench recipe with result
`flansmod:structure` + `flansmod:structure`.

---

## 9. Testing your pack

1. Put the folder into `contentpacks/` (on a server, on both the server and the clients).
2. Start the game; edits to definitions apply with `/reload`, edits to assets with F3+T.
3. Errors in definitions are logged with the file name (`latest.log`). A definition that fails to parse is skipped;
   everything else still loads.
4. Look at your items in the creative tabs (*Mod Menu → Flan's Mod → pack tabs* gives your pack its own tab).

Developers can also load a pack in the GameTests: the `example` pack in `src/gametest/resources` is such a test pack.
