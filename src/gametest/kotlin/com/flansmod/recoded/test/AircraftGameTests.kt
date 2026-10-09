package com.flansmod.recoded.test

import com.flansmod.recoded.aircraft.FlightDefinition
import com.flansmod.recoded.combat.Ballistics
import com.flansmod.recoded.combat.VehicleWeapons
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.gear.GearDefinition
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.gear.GearSlots
import com.flansmod.recoded.gear.GearType
import com.flansmod.recoded.gear.Parachutes
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Flak
import com.flansmod.recoded.gun.Fuel
import com.flansmod.recoded.gun.Gear
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Seat
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3

/**
 * Planes and helicopters ([com.flansmod.recoded.aircraft.FlightModel]), flown by autopilot and slow enough to stay in
 * their test area; bombs, anti-aircraft guns with flak, parachutes and the airfield structure kits.
 */
class AircraftGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)

    private fun GameTestHelper.spawnAircraft(id: String, def: VehicleDefinition, at: Vec3, fuel: Int = 5000): DriveableEntity {
        val vehicleId = Identifier.fromNamespaceAndPath("test", id)
        Vehicles.replace(Vehicles.all + (vehicleId to def))
        return DriveableEntity(level, vehicleId, absoluteVec(at), 0f).also {
            it.fuel = fuel
            level.addFreshEntity(it)
        }
    }

    private val helicopter = VehicleDefinition("Helicopter", type = VehicleType.HELICOPTER, width = 1f, height = 1f, maxSpeed = 0.1,
        fuel = Fuel(capacity = 5000), flight = FlightDefinition(climbSpeed = 0.15), seats = listOf(Seat()))

    // Takes off after about a block; a tiny plane that would otherwise fly out of the test area.
    private val plane = VehicleDefinition("Plane", type = VehicleType.PLANE, width = 1f, height = 0.8f, maxSpeed = 0.3, acceleration = 0.15,
        drag = 0.01, fuel = Fuel(capacity = 5000), seats = listOf(Seat()),
        flight = FlightDefinition(liftSpeed = 0.2, pitchSpeed = 4f, maxPitch = 30f, throttleRate = 1f))

    @GameTest
    fun aircraftPackContentIsLoaded(helper: GameTestHelper) {
        for ((id, type) in listOf("flansvehicles:uh1" to VehicleType.HELICOPTER, "flansvehicles:ah6" to VehicleType.HELICOPTER,
                                  "flansww2:spitfire" to VehicleType.PLANE, "flansww2:bf109" to VehicleType.PLANE,
                                  "flansww2:ju87" to VehicleType.PLANE, "flansvehicles:zu23" to VehicleType.STATIC, "flansww2:flak38" to VehicleType.STATIC)) {
            helper.assertTrue(Vehicles[Identifier.parse(id)]?.type == type, "$id should be a loaded ${type.name.lowercase()}")
        }
        val stuka = Vehicles[Identifier.parse("flansww2:ju87")]!!
        helper.assertTrue(stuka.seats[0].secondary != null, "the Stuka's pilot drops bombs")
        helper.assertTrue(stuka.seats.getOrNull(1)?.turret == true, "the Stuka has a rear gunner")
        helper.assertTrue(Vehicles[Identifier.parse("flansvehicles:zu23")]?.layWithKeys == false, "AA guns aim with the view")
        helper.assertTrue(AmmoTypes[Identifier.parse("flansvehicles:23x152_flak")]?.flak != null, "the ZU-23 has HE-FRAG flak rounds")
        for (id in listOf("flansvehicles:runway", "flansvehicles:hangar")) {
            helper.assertTrue(Structures[Identifier.parse(id)] != null, "structure kit $id should be loaded")
        }
        helper.assertTrue(Gear[Identifier.parse("flansvehicles:t10_parachute")]?.type == GearType.PARACHUTE, "the parachute should be loaded")
        helper.succeed()
    }

    @GameTest(maxTicks = 200)
    fun helicopterClimbsHoversAndAutorotatesWithoutFuel(helper: GameTestHelper) {
        val heli = helper.spawnAircraft("heli", helicopter, Vec3(2.5, 1.0, 2.5))
        helper.assertTrue(heli.isFlyingVehicle, "aircraft are flying vehicles (no anti-fly kick for their pilots)")
        val ground = heli.y
        heli.autopilot = DriveableEntity.Controls(0f, 0f, false, lift = 1f)
        // The rotor needs time to spool up before it lifts.
        helper.runAfterDelay(55) {
            helper.assertTrue(heli.y > ground + 0.8, "the helicopter climbs once the rotor spun up: ${heli.y - ground}")
            heli.autopilot = DriveableEntity.Controls(0f, 0f, false, lift = 0f)
        }
        var hover = 0.0
        helper.runAfterDelay(70) { hover = heli.y }
        helper.runAfterDelay(85) {
            helper.assertTrue(kotlin.math.abs(heli.y - hover) < 0.15, "without input it hovers: moved ${heli.y - hover}")
            helper.assertTrue(heli.fuel < 5000, "the engine burns fuel while flying")
            heli.fuel = 0
        }
        helper.runAfterDelay(150) {
            helper.assertTrue(heli.y < hover - 0.5, "out of fuel it comes down: ${heli.y} vs hover $hover")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 60)
    fun planeTakesOffOnceFastEnough(helper: GameTestHelper) {
        // yaw 0 faces +Z, along the test area.
        val aircraft = helper.spawnAircraft("plane", plane, Vec3(2.5, 1.0, 0.5))
        val start = aircraft.position()
        aircraft.autopilot = DriveableEntity.Controls(1f, 0f, false, lift = 1f)
        helper.runAfterDelay(6) {
            helper.assertTrue(aircraft.onGround(), "too slow to fly at first")
        }
        helper.succeedWhen {
            helper.assertTrue(!aircraft.onGround() && aircraft.y > start.y + 0.5, "the plane should lift off, at ${aircraft.position().subtract(start)}")
            helper.assertTrue(aircraft.bodyPitch > 5f, "nose up while climbing: ${aircraft.bodyPitch}")
            aircraft.discard()
        }
    }

    @GameTest(maxTicks = 40)
    fun stalledPlaneSinksNoseDown(helper: GameTestHelper) {
        val aircraft = helper.spawnAircraft("stalled", plane, Vec3(2.5, 4.0, 2.5), fuel = 0)
        val start = aircraft.y
        helper.runAfterDelay(10) {
            helper.assertTrue(aircraft.y < start - 0.3, "no airspeed, no lift: ${aircraft.y - start}")
            helper.assertTrue(aircraft.bodyPitch < -5f, "the nose drops: ${aircraft.bodyPitch}")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 60)
    fun flyingIntoAWallDamagesTheHull(helper: GameTestHelper) {
        for (x in 0..4) for (y in 1..3) helper.setBlock(BlockPos(x, y, 4), Blocks.STONE)
        // Never fast enough to fly; crashes into the wall on its wheels.
        val taxi = plane.copy(maxSpeed = 0.6, acceleration = 0.3, health = 100f,
            flight = plane.flight.copy(liftSpeed = 5.0, crashSpeed = 0.15, crashDamage = 100f))
        val aircraft = helper.spawnAircraft("crash", taxi, Vec3(2.5, 1.0, 0.5))
        aircraft.autopilot = DriveableEntity.Controls(1f, 0f, false)
        helper.succeedWhen {
            helper.assertTrue(aircraft.health < 100f, "hitting the wall should damage the hull, health ${aircraft.health}")
            aircraft.discard()
        }
    }

    @GameTest(maxTicks = 40)
    fun bombsFallWithTheAircraftsVelocity(helper: GameTestHelper) {
        val bomb = test("test_bomb")
        Grenades.replace(Grenades.all + (bomb to GrenadeDefinition("Test Bomb", contact = true, throwable = false, gravity = 0.04)))
        AmmoTypes.replace(AmmoTypes.all + (test("test_bomb_round") to AmmoDefinition("Test Bomb", caliber = "test_bomb", projectile = bomb)))
        Magazines.replace(Magazines.all + (test("test_bomb_rack") to MagazineDefinition("Rack", caliber = "test_bomb", capacity = 2, internal = true,
            guns = listOf(test("test_bomb_release")))))
        Guns.replace(Guns.all + (test("test_bomb_release") to GunDefinition("Release", mounted = true, drop = true, rpm = 1200)))
        val def = plane.copy(seats = listOf(Seat(secondary = test("test_bomb_release"), secondaryMuzzle = listOf(0.0, -0.5, 0.0))))
        val aircraft = helper.spawnAircraft("bomber", def, Vec3(2.5, 4.0, 2.5), fuel = 0)
        val pilot = helper.makeMockServerPlayerInLevel()
        pilot.startRiding(aircraft, true, false)
        aircraft.setMagazine(DriveableEntity.SECONDARY, MagazineContents.full(test("test_bomb_rack"), test("test_bomb_round")))
        aircraft.flight.velocity = Vec3(0.0, 0.0, 0.4)

        helper.assertTrue(VehicleWeapons.trigger(pilot, secondary = true), "the pilot's seat has a secondary weapon")
        helper.assertTrue(aircraft.seatMagazines[DriveableEntity.SECONDARY]?.rounds == 1, "one bomb left the rack")
        val dropped = helper.level.getEntitiesOfClass(GrenadeEntity::class.java, aircraft.boundingBox.inflate(3.0))
        helper.assertTrue(dropped.size == 1, "one bomb falls, found ${dropped.size}")
        val motion = dropped.first().deltaMovement
        helper.assertTrue(kotlin.math.abs(motion.z - 0.4) < 0.01 && motion.y <= 0.0, "the bomb keeps the plane's velocity: $motion")
        helper.assertTrue(dropped.first().y < aircraft.y, "it leaves from under the plane")
        dropped.forEach { it.discard() }
        aircraft.discard()
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun flakBurstsNextToAircraft(helper: GameTestHelper) {
        AmmoTypes.replace(AmmoTypes.all + (test("test_flak") to AmmoDefinition("Flak", caliber = "test_flak", flak = Flak(proximity = 3.0, power = 1.5f))))
        val gun = GunDefinition("AA", mounted = true, velocity = 2.0, spread = 0f, gravity = 0.0, lifetimeTicks = 6, damage = 1f)
        // The helicopter hangs in the air (falling: no pilot); the shell passes 1.5 blocks beside it, never touching it.
        val heli = helper.spawnAircraft("flak_target", helicopter, Vec3(3.5, 3.0, 3.5), fuel = 0)
        val gunner = helper.makeMockServerPlayerInLevel()
        val from = helper.absoluteVec(Vec3(0.5, 3.5, 5.0))
        // Along the area's x axis (test areas may be rotated).
        Ballistics.fire(gunner, gun, AmmoTypes[test("test_flak")], helper.absoluteVec(Vec3(1.5, 3.5, 5.0)).subtract(from), from)
        helper.succeedWhen {
            helper.assertTrue(heli.health < heli.definition!!.health, "the flak burst should damage the helicopter, health ${heli.health}")
            heli.discard()
        }
    }

    @GameTest
    fun antiAircraftGunsAimWithTheView(helper: GameTestHelper) {
        val aa = VehicleDefinition("AA", type = VehicleType.STATIC, layWithKeys = false, fuel = Fuel(capacity = 0),
            seats = listOf(Seat(gun = test("aa"), turret = true, minPitch = -5f, maxPitch = 85f)))
        val gun = helper.spawnAircraft("aa", aa, Vec3(2.5, 1.0, 2.5), fuel = 0)
        val gunner = helper.makeMockServerPlayerInLevel()
        gunner.startRiding(gun, true, false)
        gunner.yRot = 70f
        gunner.yHeadRot = 70f
        gunner.xRot = -60f
        val (yaw, elevation) = gun.aim(0, gunner)
        helper.assertTrue(yaw == 70f && elevation == 60f, "the AA gun follows the gunner's view, aimed at $yaw/$elevation")
        gunner.xRot = 30f
        helper.assertTrue(gun.aim(0, gunner).second == -5f, "but not below its lowest elevation")
        helper.succeed()
    }

    @GameTest(maxTicks = 20)
    fun parachuteOpensWhileFallingAndStopsFallDamage(helper: GameTestHelper) {
        Gear.replace(Gear.all + (test("chute") to GearDefinition("Chute", GearType.PARACHUTE, fallSpeed = 0.2)))
        val player = helper.makeMockServerPlayerInLevel()
        player.setOnGround(true)
        player.fallDistance = 0.0
        GearSlots.setBack(player, GearItem.stackFor(test("chute")))
        helper.assertFalse(Parachutes.open(player), "it does not open on the ground")
        player.setOnGround(false)
        player.fallDistance = 5.0
        helper.assertTrue(Parachutes.open(player), "it opens when falling")
        helper.assertTrue(Parachutes.openParachute(player)?.fallSpeed == 0.2, "the open parachute is synced as an attachment")
        player.fallDistance = 8.0
        helper.runAfterDelay(1) {
            helper.assertTrue(player.fallDistance == 0.0, "no fall damage builds up under the canopy: ${player.fallDistance}")
            player.setOnGround(true)
        }
        helper.runAfterDelay(3) {
            helper.assertTrue(Parachutes.openParachute(player) == null, "it closes on landing")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 40)
    fun explosiveRoundsBurstWhereTheyHitWithoutBreakingBlocks(helper: GameTestHelper) {
        // The round hits the stone wall next to the husk: only its blast can hurt it.
        for (y in 1..3) helper.setBlock(BlockPos(4, y, 2), Blocks.STONE)
        AmmoTypes.replace(AmmoTypes.all + (test("test_hei") to AmmoDefinition("HEI", caliber = "test_hei", explosion = 1.5f)))
        val husk = helper.spawnWithNoFreeWill(net.minecraft.world.entity.EntityTypes.HUSK, BlockPos(3, 1, 3))
        val gunner = helper.makeMockServerPlayerInLevel()
        val gun = GunDefinition("MG", mounted = true, velocity = 2.0, spread = 0f, gravity = 0.0, lifetimeTicks = 6, damage = 1f)
        val from = helper.absoluteVec(Vec3(0.5, 2.5, 2.5))
        Ballistics.fire(gunner, gun, AmmoTypes[test("test_hei")], helper.absoluteVec(Vec3(1.5, 2.5, 2.5)).subtract(from), from)
        helper.succeedWhen {
            helper.assertTrue(husk.health < husk.maxHealth, "the blast should hurt the husk next to the impact")
            helper.assertBlockPresent(Blocks.STONE, BlockPos(4, 2, 2))
        }
    }
}
