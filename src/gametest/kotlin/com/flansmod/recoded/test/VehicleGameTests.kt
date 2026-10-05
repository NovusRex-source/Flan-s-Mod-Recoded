package com.flansmod.recoded.test

import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Fuel
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.Seat
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.gun.VehiclePart
import com.flansmod.recoded.gun.PartRole
import com.flansmod.recoded.gun.VehicleUpgradeDefinition
import com.flansmod.recoded.gun.VehicleUpgrades
import com.flansmod.recoded.item.VehicleItem
import com.flansmod.recoded.registry.FlansDamageTypes
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3

class VehicleGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)

    private fun GameTestHelper.spawnVehicle(id: String, def: VehicleDefinition, at: Vec3 = Vec3(2.5, 1.0, 2.5), yaw: Float = 0f, fuel: Int = 1000): DriveableEntity {
        val vehicleId = test(id)
        Vehicles.replace(Vehicles.all + (vehicleId to def))
        return DriveableEntity(level, vehicleId, absoluteVec(at), yaw).also {
            it.fuel = fuel
            level.addFreshEntity(it)
        }
    }

    // Slow, so a driving test never leaves its own test area.
    private val car = VehicleDefinition("Car", width = 1.5f, height = 1f, maxSpeed = 0.15, acceleration = 0.05, seats = listOf(Seat(), Seat(), Seat()))

    @GameTest
    fun vehiclePackIsLoaded(helper: GameTestHelper) {
        for (name in listOf("jeep", "humvee", "m1_abrams")) {
            helper.assertTrue(Vehicles[Identifier.fromNamespaceAndPath("flansvehicles", name)] != null, "vehicle $name should be loaded")
        }
        val tank = Vehicles[Identifier.fromNamespaceAndPath("flansvehicles", "m1_abrams")]!!
        helper.assertTrue(tank.type == VehicleType.TANK, "the Abrams is tracked")
        val cannon = Guns[tank.seats[0].gun]
        helper.assertTrue(cannon?.mounted == true, "the tank's cannon is a mounted gun")
        helper.succeed()
    }

    @GameTest(maxTicks = 60)
    fun drivesForwardAndUsesFuel(helper: GameTestHelper) {
        // yaw 0 faces +Z (south), into the test area.
        val vehicle = helper.spawnVehicle("car", car, fuel = 1000)
        val start = vehicle.position()
        vehicle.autopilot = DriveableEntity.Controls(1f, 0f, false)
        helper.runAfterDelay(20) {
            vehicle.autopilot = DriveableEntity.Controls(0f, 0f, true) // brake: a coasting car would roll into other tests
            val moved = vehicle.position().subtract(start)
            helper.assertTrue(moved.z > 1.5, "vehicle should drive forward, moved $moved")
            helper.assertTrue(kotlin.math.abs(moved.x) < 0.1, "vehicle should go straight, moved $moved")
            helper.assertTrue(vehicle.fuel < 1000, "driving should use fuel")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 40)
    fun emptyTankDoesNotDrive(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("dry", car, fuel = 0)
        val start = vehicle.position()
        vehicle.autopilot = DriveableEntity.Controls(1f, 1f, false)
        helper.runAfterDelay(20) {
            helper.assertTrue(vehicle.position().subtract(start).horizontalDistance() < 0.05, "no fuel, no movement, moved ${vehicle.position().subtract(start)}")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 40)
    fun refuelsFromCoal(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("refuel", car.copy(fuel = Fuel(capacity = 5000)), fuel = 0)
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.COAL, 2))
        vehicle.interact(player, InteractionHand.MAIN_HAND, vehicle.position())
        helper.assertTrue(vehicle.fuel == 1600, "one coal adds 1600 fuel, got ${vehicle.fuel}")
        helper.assertTrue(player.mainHandItem.count == 1, "one coal is used")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun tankTurnsOnTheSpotButCarNeedsToRoll(helper: GameTestHelper) {
        val tank = helper.spawnVehicle("tank", car.copy(type = VehicleType.TANK), at = Vec3(2.5, 1.0, 2.5))
        val parked = helper.spawnVehicle("parked_car", car, at = Vec3(6.5, 1.0, 2.5))
        for (v in listOf(tank, parked)) v.autopilot = DriveableEntity.Controls(0f, 1f, false)
        helper.runAfterDelay(10) {
            helper.assertTrue(kotlin.math.abs(tank.yRot) > 20, "tank should turn on the spot, yaw ${tank.yRot}")
            helper.assertTrue(kotlin.math.abs(parked.yRot) < 0.01, "a standing car cannot turn, yaw ${parked.yRot}")
            helper.succeed()
        }
    }

    @GameTest
    fun mobsTakePassengerSeatsAndKeepThem(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("bus", car)
        val first = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(4, 1, 4))
        val second = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(4, 1, 4))
        first.startRiding(vehicle, true, false)
        second.startRiding(vehicle, true, false)
        helper.assertTrue(vehicle.seatOf(first) == 1 && vehicle.seatOf(second) == 2, "mobs leave the driver's seat free")
        first.stopRiding()
        helper.assertTrue(vehicle.seatOf(second) == 2, "others keep their seat when someone leaves")
        helper.assertTrue(vehicle.controllingPassenger == null, "mobs do not drive")
        helper.succeed()
    }

    @GameTest
    fun armourReducesBulletsButNotArmourPiercingRounds(helper: GameTestHelper) {
        // No death explosion: it would reach mobs of neighbouring tests.
        val vehicle = helper.spawnVehicle("armoured", car.copy(health = 100f, armor = 0.75f, deathExplosion = null))
        fun source(key: net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>) =
            DamageSource(helper.level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key))
        vehicle.hurtServer(helper.level, source(FlansDamageTypes.GUN), 20f)
        helper.assertTrue(vehicle.health == 95f, "75% armour should stop 15 of 20 damage, health ${vehicle.health}")
        vehicle.hurtServer(helper.level, source(FlansDamageTypes.GUN_AP), 20f)
        helper.assertTrue(vehicle.health == 75f, "AP rounds ignore armour, health ${vehicle.health}")
        vehicle.hurtServer(helper.level, source(FlansDamageTypes.GUN_AP), 200f)
        helper.assertTrue(vehicle.isRemoved, "vehicle is destroyed at 0 health")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun mountedGunFiresFromTheMuzzleAndUsesSeatMagazine(helper: GameTestHelper) {
        val gunId = test("mounted_mg")
        val magId = test("mounted_box")
        AmmoTypes.replace(AmmoTypes.all + (test("mg_round") to AmmoDefinition("Round", caliber = "mg")))
        Magazines.replace(Magazines.all + (magId to MagazineDefinition("Box", caliber = "mg", capacity = 10)))
        Guns.replace(Guns.all + (gunId to GunDefinition("MG", mounted = true, damage = 6f, spread = 0f, velocity = 8.0, lifetimeTicks = 1, magazines = listOf(magId))))
        val def = car.copy(seats = listOf(Seat(gun = gunId, turret = true, pivot = listOf(0.0, 1.0, 0.0), muzzle = listOf(0.0, 0.0, 1.0))))
        val vehicle = helper.spawnVehicle("gun_car", def, at = Vec3(2.5, 1.0, 1.5))
        val target = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(2, 1, 7))
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.startRiding(vehicle, true, false)
        helper.assertTrue(vehicle.seatOf(player) == 0, "a player takes the gunner/driver seat")
        player.yRot = 0f
        player.yHeadRot = 0f
        player.xRot = -2f // the husk's chest is slightly above the muzzle
        vehicle.setMagazine(0, MagazineContents.full(magId))

        GunHandler.trigger(player)
        helper.assertTrue(vehicle.seatMagazines[0]?.rounds == 9, "a round is taken from the seat's magazine")
        helper.succeedWhen {
            helper.assertTrue(target.health < target.maxHealth, "the mounted gun should hit the husk in front")
            helper.assertTrue(vehicle.health == vehicle.definition!!.health, "your own vehicle is never hit by its gun")
        }
    }

    @GameTest
    fun pickingUpKeepsFuel(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("pickup", car, fuel = 777)
        val player = helper.makeMockServerPlayerInLevel()
        player.isShiftKeyDown = true
        vehicle.interact(player, InteractionHand.MAIN_HAND, vehicle.position())
        helper.assertTrue(vehicle.isRemoved, "sneak + empty hand picks the vehicle up")
        val item = player.inventory.nonEquipmentItems.firstOrNull { it.item is VehicleItem }
        helper.assertTrue(item?.get(com.flansmod.recoded.registry.FlansComponents.FUEL) == 777, "the item keeps the fuel")
        helper.succeed()
    }

    // ------------------------------------------------------------------------------------------------- parts & upgrades

    /** 2 wide, 4 long: a low hull with an open top, engine in front, two "wheels" (left/right strips) and a fuel tank. */
    private val partsCar = car.copy(
        health = 100f,
        parts = mapOf(
            "hull" to VehiclePart(listOf(-1.0, 0.0, -2.0, 1.0, 0.8, 1.0), role = PartRole.HULL),
            "engine" to VehiclePart(listOf(-1.0, 0.0, 1.0, 1.0, 0.8, 2.0), health = 20f, role = PartRole.ENGINE, coreDamage = 0.5f),
            "wheel_left" to VehiclePart(listOf(-1.4, 0.0, -1.8, -1.0, 0.6, 1.8), health = 10f, role = PartRole.PROPULSION, coreDamage = 0f),
            "wheel_right" to VehiclePart(listOf(1.0, 0.0, -1.8, 1.4, 0.6, 1.8), health = 10f, role = PartRole.PROPULSION, coreDamage = 0f),
            "fuel_tank" to VehiclePart(listOf(-0.5, 0.0, -2.2, 0.5, 0.5, -2.0), health = 5f, role = PartRole.FUEL_TANK),
        ),
        upgradeSlots = listOf("engine"),
    )

    private fun GameTestHelper.damageSource(key: net.minecraft.resources.ResourceKey<net.minecraft.world.damagesource.DamageType>, at: Vec3? = null) =
        level.registryAccess().lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(key).let { if (at == null) DamageSource(it) else DamageSource(it, at) }

    @GameTest
    fun hitBoxTurnsWithTheVehicle(helper: GameTestHelper) {
        val northSouth = helper.spawnVehicle("box_ns", partsCar, yaw = 0f)
        // Kept inside the test area: vehicle parts stop bullets, also those of neighbouring tests.
        val eastWest = helper.spawnVehicle("box_ew", partsCar, at = Vec3(4.5, 1.0, 6.0), yaw = 90f)
        val a = northSouth.boundingBox
        val b = eastWest.boundingBox
        helper.assertTrue(a.zsize > 4 && a.xsize < 3, "facing south the box is long along Z: $a")
        helper.assertTrue(b.xsize > 4 && b.zsize < 3, "facing west the box is long along X: $b")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun bulletsHitPartsAndPassThroughGaps(helper: GameTestHelper) {
        val gun = GunDefinition("Rifle", damage = 4f, spread = 0f, gravity = 0.0, velocity = 8.0, lifetimeTicks = 1) // no stray bullets in other tests
        val vehicle = helper.spawnVehicle("target_car", partsCar, at = Vec3(3.5, 1.0, 4.5), yaw = 90f) // long along X
        val behind = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(3, 1, 7))
        val shooter = helper.makeMockServerPlayerInLevel() // creative: shooting must still damage, not remove, the vehicle
        // Shots along +Z at the vehicle's flank (it faces west, so its right side is the near one), from 2 heights:
        // over the 0.8 high open top (misses it, hits the husk behind) and low into the near wheel strip.
        val start = helper.absoluteVec(Vec3(3.5, 0.0, 1.5))
        for (height in listOf(1.6, 0.3)) {
            com.flansmod.recoded.combat.Ballistics.fire(shooter, gun, null, Vec3(0.0, 0.0, 1.0), Vec3(start.x, vehicle.y + height, start.z))
        }
        helper.succeedWhen {
            helper.assertTrue(behind.health < behind.maxHealth, "a shot over the open top should hit the husk behind")
            val hitParts = vehicle.damage.keys - DriveableEntity.HULL
            helper.assertTrue(hitParts.any { it.startsWith("wheel_") }, "the low shot should hit the near wheel strip, damage ${vehicle.damage}")
        }
    }

    @GameTest(maxTicks = 60)
    fun brokenPartsDisableTheVehicle(helper: GameTestHelper) {
        val noEngine = helper.spawnVehicle("no_engine", partsCar, at = Vec3(2.0, 1.0, 2.5))
        val oneWheel = helper.spawnVehicle("one_wheel", partsCar, at = Vec3(6.0, 1.0, 2.5))
        val ap = helper.damageSource(FlansDamageTypes.GUN_AP)
        noEngine.hurtPart(helper.level, ap, 25f, "engine")
        oneWheel.hurtPart(helper.level, ap, 15f, "wheel_left")
        helper.assertTrue(!noEngine.engineWorks && noEngine.health == 87.5f, "engine broken, half of its 25 damage reaches the hull: ${noEngine.health}")
        helper.assertTrue(oneWheel.propulsion == 0.5f, "one of two wheels left")
        val starts = noEngine.position() to oneWheel.position()
        for (v in listOf(noEngine, oneWheel)) v.autopilot = DriveableEntity.Controls(1f, 0f, false)
        helper.runAfterDelay(30) {
            for (v in listOf(noEngine, oneWheel)) v.autopilot = DriveableEntity.Controls(0f, 0f, true)
            helper.assertTrue(noEngine.position().subtract(starts.first).horizontalDistance() < 0.05, "a broken engine cannot drive")
            val moved = oneWheel.position().subtract(starts.second).horizontalDistance()
            helper.assertTrue(moved > 0.5 && moved < 30 * 0.15 * 0.5 + 0.1, "half the wheels: at most half top speed, moved $moved")
            helper.succeed()
        }
    }

    @GameTest(maxTicks = 30)
    fun explosionsHitTheNearestPartAndHoledTanksLeak(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("leaky", partsCar, at = Vec3(3.5, 1.0, 3.5), fuel = 1000)
        val nearTank = vehicle.position().add(vehicle.toWorld(Vec3(0.0, 0.3, -2.5)))
        vehicle.hurtServer(helper.level, helper.damageSource(net.minecraft.world.damagesource.DamageTypes.EXPLOSION, nearTank), 10f)
        helper.assertTrue(vehicle.isBroken("fuel_tank"), "the explosion behind the vehicle should hole the fuel tank, damage ${vehicle.damage}")
        helper.runAfterDelay(10) {
            helper.assertTrue(vehicle.fuel < 1000 - 40, "a holed tank leaks, fuel ${vehicle.fuel}")
            helper.succeed()
        }
    }

    @GameTest
    fun brokenGunMountCannotFire(helper: GameTestHelper) {
        val gunId = test("mount_mg")
        val magId = test("mount_box")
        AmmoTypes.replace(AmmoTypes.all + (test("mount_round") to AmmoDefinition("Round", caliber = "mount")))
        Magazines.replace(Magazines.all + (magId to MagazineDefinition("Box", caliber = "mount", capacity = 10)))
        Guns.replace(Guns.all + (gunId to GunDefinition("MG", mounted = true, magazines = listOf(magId), velocity = 4.0, lifetimeTicks = 1)))
        val def = car.copy(seats = listOf(Seat(gun = gunId, turret = true)), parts = mapOf(
            "hull" to VehiclePart(listOf(-1.0, 0.0, -1.0, 1.0, 1.0, 1.0)),
            "mount" to VehiclePart(listOf(-0.3, 1.0, -0.3, 0.3, 1.5, 0.3), health = 5f, role = PartRole.WEAPON, seat = 0),
        ))
        val vehicle = helper.spawnVehicle("broken_mount", def)
        val player = helper.makeMockServerPlayerInLevel()
        player.startRiding(vehicle, true, false)
        vehicle.setMagazine(0, MagazineContents.full(magId))
        vehicle.hurtPart(helper.level, helper.damageSource(FlansDamageTypes.GUN_AP), 10f, "mount")
        GunHandler.trigger(player)
        helper.assertTrue(vehicle.seatMagazines[0]?.rounds == 10, "a destroyed gun mount does not fire")
        helper.succeed()
    }

    @GameTest
    fun upgradesChangeStatsAndStayOnTheItem(helper: GameTestHelper) {
        val tuning = test("tuning")
        val tankOnly = test("tank_kit")
        VehicleUpgrades.replace(VehicleUpgrades.all + mapOf(
            tuning to VehicleUpgradeDefinition("Tuning", slot = "engine", speedMultiplier = 2.0, healthMultiplier = 2f),
            tankOnly to VehicleUpgradeDefinition("Tank kit", slot = "engine", types = listOf(VehicleType.TANK)),
        ))
        val vehicle = helper.spawnVehicle("upgradable", partsCar)
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false

        player.setItemInHand(InteractionHand.MAIN_HAND, com.flansmod.recoded.item.VehicleUpgradeItem.stackFor(tankOnly))
        helper.assertFalse(vehicle.installUpgrade(player, InteractionHand.MAIN_HAND), "a tank-only upgrade does not fit a car")
        player.setItemInHand(InteractionHand.MAIN_HAND, com.flansmod.recoded.item.VehicleUpgradeItem.stackFor(tuning))
        helper.assertTrue(vehicle.installUpgrade(player, InteractionHand.MAIN_HAND), "the tuning kit fits the engine slot")
        helper.assertTrue(player.mainHandItem.isEmpty, "the upgrade item is used up")
        helper.assertTrue(vehicle.definition!!.maxSpeed == partsCar.maxSpeed * 2, "upgrades change the effective stats")
        helper.assertTrue(vehicle.health == 200f, "health multiplier raises the hull's health")

        vehicle.hurtPart(helper.level, helper.damageSource(FlansDamageTypes.GUN_AP), 30f, "hull")
        player.isShiftKeyDown = true
        vehicle.interact(player, InteractionHand.MAIN_HAND, vehicle.position())
        val item = player.inventory.nonEquipmentItems.first { it.item is VehicleItem }
        helper.assertTrue(item.get(com.flansmod.recoded.registry.FlansComponents.VEHICLE_UPGRADES) == mapOf("engine" to tuning), "the item keeps upgrades")
        helper.assertTrue(item.get(com.flansmod.recoded.registry.FlansComponents.VEHICLE_DAMAGE)?.get(DriveableEntity.HULL) == 30f, "the item keeps damage")
        helper.succeed()
    }

    @GameTest
    fun repairItemHealsHullAndParts(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("repairable", partsCar)
        vehicle.hurtPart(helper.level, helper.damageSource(FlansDamageTypes.GUN_AP), 15f, "wheel_left")
        vehicle.hurtPart(helper.level, helper.damageSource(FlansDamageTypes.GUN_AP), 40f, "hull")
        helper.assertTrue(vehicle.isBroken("wheel_left"), "wheel shot off")
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(Items.IRON_INGOT, 2))
        vehicle.interact(player, InteractionHand.MAIN_HAND, vehicle.position())
        helper.assertFalse(vehicle.isBroken("wheel_left"), "repair fixes the wheel")
        helper.assertTrue(vehicle.health == 85f, "repair heals the hull by 25, health ${vehicle.health}")
        helper.assertTrue(player.mainHandItem.count == 1, "one repair item is used")
        helper.succeed()
    }

    // ------------------------------------------------------------------------------------------------- crew & terrain

    @GameTest
    fun driverCannotShootHandGunsButPassengersCan(helper: GameTestHelper) {
        val gunId = test("passenger_pistol")
        val magId = test("passenger_mag")
        AmmoTypes.replace(AmmoTypes.all + (test("passenger_round") to AmmoDefinition("Round", caliber = "passenger")))
        Magazines.replace(Magazines.all + (magId to MagazineDefinition("Mag", caliber = "passenger", capacity = 5)))
        Guns.replace(Guns.all + (gunId to GunDefinition("Pistol", magazines = listOf(magId), velocity = 4.0, lifetimeTicks = 1)))
        val vehicle = helper.spawnVehicle("crew_car", car)
        val (driver, passenger) = List(2) {
            helper.makeMockServerPlayerInLevel().also { p ->
                p.setItemInHand(InteractionHand.MAIN_HAND, com.flansmod.recoded.item.GunItem.stackFor(gunId).apply {
                    set(com.flansmod.recoded.registry.FlansComponents.MAGAZINE, MagazineContents.full(magId))
                })
                p.startRiding(vehicle, true, false)
            }
        }
        helper.assertTrue(vehicle.seatOf(driver) == 0 && vehicle.seatOf(passenger) == 1, "first player drives")
        GunHandler.trigger(driver)
        GunHandler.trigger(passenger)
        helper.assertTrue(driver.mainHandItem.get(com.flansmod.recoded.registry.FlansComponents.MAGAZINE)?.rounds == 5, "the driver's hands stay on the wheel")
        helper.assertTrue(passenger.mainHandItem.get(com.flansmod.recoded.registry.FlansComponents.MAGAZINE)?.rounds == 4, "passengers shoot their own guns")
        helper.succeed()
    }

    @GameTest(maxTicks = 40)
    fun bodyTiltsOnAStep(helper: GameTestHelper) {
        // Front wheels on a 1-block step, rear wheels on the floor; the vehicle faces south (+Z).
        for (x in 0..4) for (z in 3..5) helper.setBlock(BlockPos(x, 1, z), net.minecraft.world.level.block.Blocks.STONE)
        val wheels = car.copy(width = 1.5f, height = 1f, parts = mapOf(
            "hull" to VehiclePart(listOf(-0.7, 0.3, -1.2, 0.7, 1.0, 1.2)),
            "front" to VehiclePart(listOf(-0.7, 0.0, 0.8, 0.7, 0.6, 1.2), role = PartRole.PROPULSION),
            "rear" to VehiclePart(listOf(-0.7, 0.0, -1.2, 0.7, 0.6, -0.8), role = PartRole.PROPULSION),
        ))
        val vehicle = helper.spawnVehicle("stepped", wheels, at = Vec3(2.5, 2.0, 2.2), yaw = 0f)
        helper.runAfterDelay(30) {
            helper.assertTrue(vehicle.bodyPitch > 15f, "nose up with the front wheels on the step, pitch ${vehicle.bodyPitch}")
            helper.assertTrue(vehicle.bodySink < -0.3f, "body sits lower than the stepped-up box, sink ${vehicle.bodySink}")
            helper.assertTrue(kotlin.math.abs(vehicle.bodyRoll) < 2f, "no roll on a straight step, roll ${vehicle.bodyRoll}")
            helper.succeed()
        }
    }
}
