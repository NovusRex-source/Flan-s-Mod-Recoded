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
        val vehicle = helper.spawnVehicle("armoured", car.copy(health = 100f, armor = 0.75f))
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
        Guns.replace(Guns.all + (gunId to GunDefinition("MG", mounted = true, damage = 6f, spread = 0f, lifetimeTicks = 2, magazines = listOf(magId))))
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
}
