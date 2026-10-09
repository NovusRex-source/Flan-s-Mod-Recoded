package com.flansmod.recoded.test

import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Vehicles
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.CameraType
import com.flansmod.recoded.FlansMod
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.Minecraft
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.phys.Vec3

/**
 * Vehicles in a real client: models and procedural bones, driving with the movement keys (client-authoritative, synced
 * to the server like boats), the turret following the view and firing the tank cannon. Screenshots in
 * build/run/clientGameTest/screenshots.
 */
class VehicleClientGameTest : FabricClientGameTest {
    private fun vehicle(name: String) = Identifier.fromNamespaceAndPath("flansvehicles", name)

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamemode survival @a")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            check(context.client { Vehicles[vehicle("m1_abrams")] != null }) { "client never received vehicle definitions" }

            // Line-up seen from the side.
            val base = server.compute { it.playerList.players.first().position() }
            fun spawn(s: MinecraftServer, name: String, offset: Vec3, yaw: Float) = DriveableEntity(s.overworld(), vehicle(name), base.add(offset), yaw).also {
                it.fuel = 10000
                s.overworld().addFreshEntity(it)
            }
            val ids = server.compute { s ->
                listOf(spawn(s, "jeep", Vec3(-6.0, 0.0, 10.0), 90f), spawn(s, "humvee", Vec3(0.0, 0.0, 10.0), 90f), spawn(s, "m1_abrams", Vec3(7.0, 0.0, 10.0), 90f)).map { it.id }
            }
            server.runCommand("tp @a ${base.x} ${base.y + 1} ${base.z} 0 10")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-vehicles-lineup")

            // Upgrades show on the models; a shot-off wheel disappears and its part smokes.
            server.compute { s ->
                fun v(i: Int) = s.overworld().getEntity(ids[i]) as DriveableEntity
                fun up(name: String) = Identifier.fromNamespaceAndPath("flansvehicles", name)
                v(0).upgrades = mapOf("armor" to up("armor_kit"), "tank" to up("jerry_cans"), "engine" to up("engine_tuning"))
                v(1).upgrades = mapOf("armor" to up("armor_kit"))
                v(2).upgrades = mapOf("armor" to up("era_blocks"), "tank" to up("jerry_cans"))
                v(0).damage = mapOf("wheel_fl" to 100f, "wheel_fr" to 100f, DriveableEntity.HULL to 50f)
            }
            context.waitTicks(20)
            context.takeScreenshot("flansmod-vehicles-upgraded")
            server.runCommand("tp @a ${base.x - 9} ${base.y + 3} ${base.z + 17} facing ${base.x} ${base.y} ${base.z + 10}")
            context.waitTicks(10)
            context.takeScreenshot("flansmod-vehicles-front")

            // Vehicle items with their 3D icons in the vehicles creative tab.
            server.runCommand("gamemode creative @a")
            context.waitTicks(5)
            context.input.pressKey { it.keyInventory }
            context.waitForScreen(CreativeModeInventoryScreen::class.java)
            context.runOnClient<RuntimeException> { mc ->
                val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(com.flansmod.recoded.client.tab.TypeTabs.id("vehicles")) ?: error("no vehicles creative tab")
                check((mc.gui.screen() as FabricCreativeModeInventoryScreen).setSelectedTab(tab)) { "could not select the vehicles tab" }
            }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-vehicles-tab")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.runCommand("gamemode survival @a")

            // Vehicle menu (U while riding): upgrade slots, fuel slot and part health.
            server.compute { s -> s.playerList.players.first().startRiding(s.overworld().getEntity(ids[1])!!, true, true) }
            context.waitTicks(5)
            context.input.pressKey(InputConstants.KEY_U)
            context.waitForScreen(com.flansmod.recoded.client.vehicle.VehicleMenuScreen::class.java)
            context.takeScreenshot("flansmod-vehicles-menu")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)
            // The jeep's front wheels were shot off for the screenshot above: repair it before driving.
            server.compute { s -> (s.overworld().getEntity(ids[0]) as DriveableEntity).damage = emptyMap() }

            // Drive the jeep forward with W: the driver's client simulates and the server must follow.
            server.compute { s -> s.playerList.players.first().startRiding(s.overworld().getEntity(ids[0])!!, true, true) }
            context.waitTicks(5)
            check(context.client { it.player!!.vehicle is DriveableEntity }) { "player should sit in the jeep" }
            val before = server.compute { it.overworld().getEntity(ids[0])!!.position() }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_BACK }
            context.input.holdKey { it.keyUp }
            context.waitTicks(40)
            context.takeScreenshot("flansmod-vehicles-driving")
            context.input.releaseKey { it.keyUp }
            context.waitTicks(5)
            val after = server.compute { it.overworld().getEntity(ids[0])!!.position() }
            check(after.distanceTo(before) > 4) { "the jeep should have driven (server saw ${before} → ${after})" }
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // Tank: driver aims the turret with the view and fires the cannon.
            server.compute { s ->
                val tank = s.overworld().getEntity(ids[2]) as DriveableEntity
                tank.setMagazine(0, MagazineContents.full(Identifier.fromNamespaceAndPath("flansvehicles", "120mm_breech"), Identifier.fromNamespaceAndPath("flansvehicles", "120mm_apfsds")))
                s.playerList.players.first().startRiding(tank, true, true)
            }
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.player!!.yRot = it.player!!.yRot + 60f; it.player!!.xRot = -10f }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-vehicles-tank-turret")
            val shots = context.client { ShotEffects.shotsSeen }
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitFor({ ShotEffects.shotsSeen > shots }, 20)
            context.takeScreenshot("flansmod-vehicles-tank-fired")
            val rounds = server.compute { (it.overworld().getEntity(ids[2]) as DriveableEntity).seatMagazines[0]?.rounds }
            check(rounds == 0) { "the cannon should have used its shell, rounds $rounds" }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-vehicles-tank-firstperson")
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // Cargo: the Humvee's storage as a chest (from the vehicle menu's button, or sneak + right click with an item).
            server.compute { s ->
                val humvee = s.overworld().getEntity(ids[1]) as DriveableEntity
                humvee.storage.setItem(0, net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_INGOT, 16))
                humvee.storage.setItem(4, com.flansmod.recoded.fuel.FuelCanItem.stackFor(com.flansmod.recoded.fuel.FuelStack(com.flansmod.recoded.gun.FuelTypes.DIESEL, com.flansmod.recoded.fuel.FuelCanItem.CAPACITY)))
                s.playerList.players.first().startRiding(humvee, true, true)
                humvee.openStorage(s.playerList.players.first())
            }
            context.waitForScreen(net.minecraft.client.gui.screens.inventory.ContainerScreen::class.java)
            context.takeScreenshot("flansmod-vehicles-storage")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // Mortar: the artillery map (N) lays the gun onto a clicked point; the impact marker follows.
            val mortar = server.compute { s ->
                DriveableEntity(s.overworld(), vehicle("m252"), base.add(-4.0, 0.0, -8.0), 180f).also {
                    s.overworld().addFreshEntity(it)
                    it.setMagazine(0, MagazineContents.full(vehicle("81mm_tube"), vehicle("81mm_mortar_he")))
                    s.playerList.players.first().startRiding(it, true, true)
                }.id
            }
            context.waitTicks(10)
            context.input.pressKey(InputConstants.KEY_N)
            context.waitForScreen(com.flansmod.recoded.client.vehicle.ArtilleryMapScreen::class.java)
            val target = base.add(-4.0 + 30.0, 0.0, -8.0 - 40.0)
            context.runOnClient<RuntimeException> { mc ->
                (mc.gui.screen() as com.flansmod.recoded.client.vehicle.ArtilleryMapScreen).aimAt(target.x.toInt(), target.z.toInt())
            }
            context.waitTicks(60) // traverse and elevate onto the fire mission
            context.takeScreenshot("flansmod-artillery-map")
            val laid = context.client { mc -> (mc.player!!.vehicle as DriveableEntity).layTarget }
            check(laid != null) { "the target should be in range of the mortar" }
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.waitTicks(10)
            val impact = context.client { com.flansmod.recoded.client.vehicle.ArtilleryClient.impact }
            check(impact != null && impact.subtract(target).horizontalDistance() < 4.0) { "the predicted impact should be on the target: $impact vs $target" }
            check(server.compute { s -> s.overworld().getEntity(mortar)!!.yRot }.let { kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(it - laid!!.first)) < 1f }) {
                "the server should see the mortar laid"
            }
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // Sentry turret: unmanned, it finds the husk, loads a belt from its cargo and shoots.
            val husk = server.compute { s ->
                DriveableEntity(s.overworld(), vehicle("sentry_mg"), base.add(6.0, 0.0, -14.0), 0f).also {
                    it.owner = s.playerList.players.first().uuid
                    it.storage.setItem(0, com.flansmod.recoded.item.MagazineItem.stackFor(MagazineContents.full(vehicle("m60_belt_200"))!!))
                    s.overworld().addFreshEntity(it)
                }
                net.minecraft.world.entity.EntityTypes.HUSK.create(s.overworld(), net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!.also {
                    it.snapTo(base.x + 6.5, base.y.toDouble(), base.z - 26.5, 0f, 0f)
                    it.isNoAi = true
                    s.overworld().addFreshEntity(it)
                }.id
            }
            server.runCommand("tp @a ${base.x + 1.5} ${base.y + 2} ${base.z - 11.5} facing ${base.x + 6.5} ${base.y + 1} ${base.z - 20}")
            context.waitTicks(40)
            context.takeScreenshot("flansmod-sentry-turret")
            var waited = 0
            while (server.compute { s -> (s.overworld().getEntity(husk) as? net.minecraft.world.entity.LivingEntity)?.let { it.health >= it.maxHealth } == true }) {
                check(waited++ < 200) { "the sentry should shoot the husk" }
                context.waitTicks(1)
            }

            // Howitzer: laid from the artillery map onto a point ~70 blocks away, then fired.
            val m777 = server.compute { s ->
                DriveableEntity(s.overworld(), vehicle("m777"), base.add(-12.0, 0.0, 6.0), 180f).also {
                    s.overworld().addFreshEntity(it)
                    it.setMagazine(0, MagazineContents.full(vehicle("155mm_breech"), vehicle("155mm_howitzer_he")))
                    s.playerList.players.first().startRiding(it, true, true)
                }.id
            }
            context.waitTicks(10)
            context.input.pressKey(InputConstants.KEY_N)
            context.waitForScreen(com.flansmod.recoded.client.vehicle.ArtilleryMapScreen::class.java)
            val fireAt = base.add(-12.0 + 25.0, 0.0, 6.0 - 65.0)
            context.runOnClient<RuntimeException> { mc ->
                (mc.gui.screen() as com.flansmod.recoded.client.vehicle.ArtilleryMapScreen).aimAt(fireAt.x.toInt(), fireAt.z.toInt())
            }
            context.waitTicks(60)
            context.takeScreenshot("flansmod-howitzer-map")
            check(context.client { mc -> (mc.player!!.vehicle as DriveableEntity).layTarget?.second?.let { it < 45f } == true }) { "the howitzer should take the flat trajectory" }
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_BACK }
            context.waitTicks(10)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(8)
            context.takeScreenshot("flansmod-howitzer-fired")
            check(server.compute { s -> (s.overworld().getEntity(m777) as DriveableEntity).seatMagazines[0]?.rounds ?: 0 } == 0) { "the howitzer should have fired its shell" }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
        }
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
