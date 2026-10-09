package com.flansmod.recoded.test

import com.flansmod.recoded.combat.GunHandler
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.entity.GrenadeEntity
import com.flansmod.recoded.entity.MineEntity
import com.flansmod.recoded.fuel.FuelCanItem
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.fuel.FuelSynthesizerBlockEntity
import com.flansmod.recoded.fuel.PetrolStationBlockEntity
import com.flansmod.recoded.fuel.fuel
import com.flansmod.recoded.gun.AmmoDefinition
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Fuel
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.gun.GrenadeDefinition
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.GunDefinition
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.MagazineDefinition
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.gun.PartRole
import com.flansmod.recoded.gun.Seat
import com.flansmod.recoded.gun.VehicleDefinition
import com.flansmod.recoded.gun.VehiclePart
import com.flansmod.recoded.gun.VehicleType
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.item.WrenchItem
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.registry.FlansItems
import net.fabricmc.fabric.api.gametest.v1.GameTest
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.gametest.framework.GameTestHelper
import net.minecraft.resources.Identifier
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/** Vehicle field equipment: wrench, fuel cans, fuel synthesizer, petrol station, mines and mortars. */
class UtilityGameTests {
    private fun test(path: String) = Identifier.fromNamespaceAndPath("test", path)

    private fun GameTestHelper.spawnVehicle(id: String, def: VehicleDefinition, at: Vec3 = Vec3(2.5, 1.0, 2.5), fuel: Int = 0): DriveableEntity {
        val vehicleId = test(id)
        Vehicles.replace(Vehicles.all + (vehicleId to def))
        return DriveableEntity(level, vehicleId, absoluteVec(at), 0f).also {
            it.fuel = fuel
            level.addFreshEntity(it)
        }
    }

    /** yaw 0 faces +Z; vehicle right is -X. A front and a rear wheel, plus the hull. */
    private val truck = VehicleDefinition("Truck", maxSpeed = 0.0, deathExplosion = null, fuel = Fuel(capacity = 4000, type = FuelTypes.DIESEL), parts = mapOf(
        "hull" to VehiclePart(listOf(-0.8, 0.3, -1.0, 0.8, 1.2, 1.0)),
        "wheel_front" to VehiclePart(listOf(-1.0, 0.0, 0.6, -0.8, 0.6, 1.0), health = 40f, role = PartRole.PROPULSION),
        "wheel_rear" to VehiclePart(listOf(-1.0, 0.0, -1.0, -0.8, 0.6, -0.6), health = 40f, role = PartRole.PROPULSION)))

    @GameTest
    fun wrenchRepairsThePartYouLookAt(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("wrench_truck", truck)
        vehicle.damage = mapOf("wheel_front" to 40f, "wheel_rear" to 20f)
        helper.assertTrue(vehicle.isBroken("wheel_front"), "the front wheel starts shot off")
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack(FlansItems.WRENCH))
        // Stand at the vehicle's right (-X) side and look at the front wheel.
        player.snapTo(vehicle.x - 3.0, vehicle.y, vehicle.z + 0.8, 0f, 0f)
        player.lookAt(EntityAnchorArgument.Anchor.EYES, vehicle.position().add(-0.9, 0.3, 0.8))
        helper.assertTrue(WrenchItem.repair(player, InteractionHand.MAIN_HAND, vehicle), "the wrench should repair")
        helper.assertTrue(vehicle.damage["wheel_front"] == 40f - WrenchItem.REPAIR && vehicle.damage["wheel_rear"] == 20f,
            "only the looked-at wheel is repaired: ${vehicle.damage}")
        helper.assertTrue(!vehicle.isBroken("wheel_front"), "a repaired wheel works again")
        helper.assertTrue(player.mainHandItem.damageValue == 1, "repairs cost wrench durability")
        helper.succeed()
    }

    @GameTest
    fun fuelCansOnlyFillVehiclesOfTheirFuel(helper: GameTestHelper) {
        val vehicle = helper.spawnVehicle("can_truck", truck)
        val player = helper.makeMockServerPlayerInLevel()
        player.abilities.instabuild = false
        player.setItemInHand(InteractionHand.MAIN_HAND, FuelCanItem.stackFor(FuelStack(FuelTypes.PETROL, 3000)))
        vehicle.interact(player, InteractionHand.MAIN_HAND, Vec3.ZERO)
        helper.assertTrue(vehicle.fuel == 0, "petrol does not go into a diesel vehicle")
        player.setItemInHand(InteractionHand.MAIN_HAND, FuelCanItem.stackFor(FuelStack(FuelTypes.DIESEL, FuelCanItem.CAPACITY)))
        vehicle.interact(player, InteractionHand.MAIN_HAND, Vec3.ZERO)
        helper.assertTrue(vehicle.fuel == 4000, "the tank fills up, got ${vehicle.fuel}")
        helper.assertTrue(player.mainHandItem.fuel?.amount == FuelCanItem.CAPACITY - 4000, "the rest stays in the can")
        helper.succeed()
    }

    @GameTest(maxTicks = 200)
    fun synthesizerMakesFuelFromCoalAndWaterAndFillsCans(helper: GameTestHelper) {
        val pos = BlockPos(1, 1, 1)
        helper.setBlock(pos, FlansBlocks.FUEL_SYNTHESIZER)
        val machine = helper.getBlockEntity(pos, FuelSynthesizerBlockEntity::class.java)
        machine.toggleMode() // petrol → diesel
        machine.setItem(FuelSynthesizerBlockEntity.COAL, ItemStack(Items.COAL, 2))
        machine.setItem(FuelSynthesizerBlockEntity.WATER, ItemStack(Items.WATER_BUCKET))
        helper.runAfterDelay(FuelSynthesizerBlockEntity.BATCH_TICKS * 2L + 10) {
            helper.assertTrue(machine.getItem(FuelSynthesizerBlockEntity.WATER).`is`(Items.BUCKET), "the water bucket is emptied into the tank")
            helper.assertTrue(machine.tank == FuelStack(FuelTypes.DIESEL, FuelSynthesizerBlockEntity.PER_COAL * 2), "two coal make diesel: ${machine.tank}")
            machine.setItem(FuelSynthesizerBlockEntity.CAN, FuelCanItem.stackFor(null))
            helper.runAfterDelay(2) {
                helper.assertTrue(machine.getItem(FuelSynthesizerBlockEntity.CAN).fuel == FuelStack(FuelTypes.DIESEL, FuelSynthesizerBlockEntity.PER_COAL * 2),
                    "an empty can in the can slot is filled")
                helper.assertTrue(machine.tank == null, "the tank is empty afterwards")
                helper.succeed()
            }
        }
    }

    @GameTest(maxTicks = 60)
    fun petrolStationFillsNearbyVehiclesOfItsFuel(helper: GameTestHelper) {
        val pos = BlockPos(1, 1, 1)
        helper.setBlock(pos, FlansBlocks.PETROL_STATION)
        val station = helper.getBlockEntity(pos, PetrolStationBlockEntity::class.java)
        station.tank = FuelStack(FuelTypes.DIESEL, 10000)
        val diesel = helper.spawnVehicle("station_diesel", truck, at = Vec3(3.5, 1.0, 1.5))
        val petrol = helper.spawnVehicle("station_petrol", truck.copy(fuel = truck.fuel.copy(type = FuelTypes.PETROL)), at = Vec3(3.5, 1.0, 4.5))
        // A synthesizer next to it pumps its fuel in.
        helper.setBlock(pos.west(), FlansBlocks.FUEL_SYNTHESIZER)
        helper.getBlockEntity(pos.west(), FuelSynthesizerBlockEntity::class.java).tank = FuelStack(FuelTypes.DIESEL, 1000)
        helper.runAfterDelay(30) {
            helper.assertTrue(diesel.fuel > 0, "the diesel vehicle is being filled")
            helper.assertTrue(petrol.fuel == 0, "a petrol vehicle gets no diesel")
            helper.assertTrue(helper.getBlockEntity(pos.west(), FuelSynthesizerBlockEntity::class.java).tank == null, "the synthesizer pumped into the station")
            helper.succeed()
        }
    }

    private fun mine(id: String, trigger: GrenadeDefinition.Mine.Trigger, vehicleDamage: Float = 0f): Identifier = test(id).also {
        Grenades.replace(Grenades.all + (it to GrenadeDefinition("Mine", explosion = GrenadeDefinition.Explosion(power = 0.5f),
            mine = GrenadeDefinition.Mine(trigger, armTicks = 5, radius = 1.4, vehicleDamage = vehicleDamage))))
    }

    @GameTest(maxTicks = 160)
    fun antiPersonnelMineGoesOffUnderAMob(helper: GameTestHelper) {
        val id = mine("ap_mine", GrenadeDefinition.Mine.Trigger.PERSONNEL)
        val mine = MineEntity(helper.level, GrenadeItem.stackFor(id), helper.absoluteVec(Vec3(2.5, 1.0, 2.5)), 0f, null)
        helper.level.addFreshEntity(mine)
        helper.runAfterDelay(10) {
            helper.assertTrue(!mine.isRemoved, "an armed mine waits for someone")
            val husk = helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(2, 1, 2))
            helper.succeedWhen {
                helper.assertTrue(mine.isRemoved, "stepping on it sets it off")
                helper.assertTrue(husk.health < husk.maxHealth, "the mob is hurt")
            }
        }
    }

    @GameTest(maxTicks = 160)
    fun antiTankMineIgnoresMobsButWrecksTheTrackAbove(helper: GameTestHelper) {
        val id = mine("at_mine", GrenadeDefinition.Mine.Trigger.VEHICLE, vehicleDamage = 100f)
        val mine = MineEntity(helper.level, GrenadeItem.stackFor(id), helper.absoluteVec(Vec3(1.6, 1.0, 3.3)), 0f, null)
        helper.level.addFreshEntity(mine)
        helper.spawnWithNoFreeWill(EntityTypes.HUSK, BlockPos(1, 1, 3))
        helper.runAfterDelay(15) {
            helper.assertTrue(!mine.isRemoved, "people do not set off anti-tank mines")
            // Rear wheel right above the mine. The vehicle's yaw is absolute while test areas may be rotated: put the mine
            // under where the wheel really is.
            val vehicle = helper.spawnVehicle("mine_truck", truck, at = Vec3(2.5, 1.0, 4.1))
            val wheel = vehicle.position().add(vehicle.toWorld(truck.parts["wheel_rear"]!!.center))
            mine.setPos(wheel.x, mine.y, wheel.z)
            helper.succeedWhen {
                // The test area's entities do not always tick: check the mine here too.
                if (!mine.isRemoved) mine.tick()
                helper.assertTrue(mine.isRemoved, "a vehicle sets it off")
                helper.assertTrue(vehicle.isBroken("wheel_rear"), "the wheel above the mine is destroyed: ${vehicle.damage}")
            }
        }
    }

    @GameTest(maxTicks = 60)
    fun mortarIsLaidWithTheMovementKeysAndFiresWhereItIsLaid(helper: GameTestHelper) {
        val gunId = test("mortar_tube")
        val magId = test("mortar_mag")
        val bomb = test("mortar_bomb")
        Grenades.replace(Grenades.all + (bomb to GrenadeDefinition("Bomb", contact = true, throwable = false, gravity = 0.05)))
        AmmoTypes.replace(AmmoTypes.all + (test("mortar_round") to AmmoDefinition("Bomb", caliber = "test_mortar", projectile = bomb)))
        Magazines.replace(Magazines.all + (magId to MagazineDefinition("Tube", caliber = "test_mortar", capacity = 1, internal = true)))
        Guns.replace(Guns.all + (gunId to GunDefinition("Mortar", mounted = true, velocity = 1.0, spread = 0f, magazines = listOf(magId))))
        val def = VehicleDefinition("Mortar", type = VehicleType.STATIC, maxSpeed = 0.0, fuel = Fuel(capacity = 0), deathExplosion = null,
            seats = listOf(Seat(gun = gunId, turret = true, pivot = listOf(0.0, 0.2, 0.0), muzzle = listOf(0.0, 0.0, 1.0), minPitch = 45f, maxPitch = 85f)))
        val mortar = helper.spawnVehicle("mortar", def)
        val start = mortar.position()
        // "W" and "A" held for 10 ticks (no rider: the server lays it), ticked here so the test does not depend on when
        // the test area's entities start ticking: tube raised, turned left, never moved.
        mortar.autopilot = DriveableEntity.Controls(1f, 1f, false)
        repeat(10) { mortar.tick() }
        mortar.autopilot = null
        helper.runAfterDelay(1) {
            val elevation = mortar.layElevation()
            helper.assertTrue(elevation > 47.5f && elevation < 52f, "W raises the tube about 0.4°/tick: $elevation")
            helper.assertTrue(mortar.yRot < -5f, "A turns the mortar: ${mortar.yRot}")
            helper.assertTrue(mortar.position().subtract(start).horizontalDistance() < 0.05, "an emplacement never drives: ${mortar.position().subtract(start)}")
            val player = helper.makeMockServerPlayerInLevel()
            player.abilities.instabuild = false
            player.startRiding(mortar, true, false)
            player.xRot = 30f; player.yRot = 90f // the gunner may look anywhere: the mortar fires where it is laid
            mortar.setMagazine(0, MagazineContents.full(magId, test("mortar_round")))
            GunHandler.trigger(player)
            helper.runAfterDelay(1) {
                val shells = helper.level.getEntitiesOfClass(GrenadeEntity::class.java, AABB.ofSize(start, 16.0, 16.0, 16.0))
                helper.assertTrue(shells.size == 1, "one bomb is fired, got ${shells.size}")
                val v = shells[0].deltaMovement
                val angle = Math.toDegrees(kotlin.math.atan2(v.y, v.horizontalDistance()))
                helper.assertTrue(kotlin.math.abs(angle - elevation) < 3, "the bomb leaves at the laid elevation $elevation, not the view: $angle")
                shells.forEach { it.discard() }
                helper.succeed()
            }
        }
    }
}
