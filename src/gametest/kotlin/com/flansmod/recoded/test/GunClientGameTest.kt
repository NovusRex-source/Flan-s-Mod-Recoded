package com.flansmod.recoded.test

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.client.hud.GunHud
import com.flansmod.recoded.client.input.GunInput
import net.minecraft.world.entity.ai.attributes.Attributes
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.attachments
import com.flansmod.recoded.item.gunId
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.server.MinecraftServer

/**
 * End-to-end check in a real client + integrated server: gun sync, rendering, firing via the mouse,
 * aiming and reloading via the key binding. Screenshots land in build/run/clientGameTest/screenshots.
 */
class GunClientGameTest : FabricClientGameTest {
    private val rifle = Identifier.fromNamespaceAndPath("example", "rifle")

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamemode survival @a")
            server.runCommand("give @a flansmod:gun[flansmod:gun=\"$rifle\",flansmod:ammo=30]")
            server.runCommand("give @a minecraft:iron_nugget 3")
            server.runCommand("execute at @a run summon minecraft:husk ^ ^ ^6 {NoAI:1b}")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()

            check(context.client { Guns[rifle] != null }) { "client never received gun definitions" }
            check(context.client { it.player!!.mainHandItem.gunId == rifle }) { "rifle not in hand" }
            context.takeScreenshot("flansmod-hipfire")

            // Display transforms in other perspectives.
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-thirdperson")
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
            context.input.pressKey { it.keyInventory }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-inventory")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.waitTicks(2)

            // Aim down sights (right mouse).
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(10)
            check(context.client { GunInput.aiming }) { "holding right mouse should aim" }
            world.connection.waitForServerboundPackets()
            val slowed = server.compute { it.playerList.players.first().getAttribute(Attributes.MOVEMENT_SPEED)!!.hasModifier(FlansMod.id("ads_slowdown")) }
            check(slowed) { "server should apply the aiming slowdown" }
            context.takeScreenshot("flansmod-ads")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)

            // Full-auto burst (left mouse): 10 ticks at 600 rpm = 5 shots.
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(3)
            context.takeScreenshot("flansmod-tracers")
            context.waitTicks(7)
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT)
            check(context.client { ShotEffects.shotsSeen } > 0) { "clients should receive shot effects" }
            context.waitTicks(5)
            check(context.client { GunHud.hitsReceived } > 0) { "hitting the husk should report a hit to the client" }
            val afterFiring = server.compute { it.playerList.players.first().mainHandItem.ammo }
            check(afterFiring in 1..28) { "expected several shots to be fired, ammo is $afterFiring" }
            context.takeScreenshot("flansmod-fired")

            // Reload (R): 40 ticks, consumes nuggets at 10 rounds each.
            context.input.pressKey(InputConstants.KEY_R)
            context.waitTicks(50)
            val afterReload = server.compute { it.playerList.players.first().mainHandItem.ammo }
            check(afterReload == 30) { "reload should refill to 30, ammo is $afterReload (was $afterFiring)" }
            context.takeScreenshot("flansmod-reloaded")

            // Single shot at a fresh target to capture the hit marker.
            server.runCommand("kill @e[type=minecraft:husk]")
            server.runCommand("execute at @a run summon minecraft:husk ^ ^ ^5 {NoAI:1b}")
            context.waitTicks(10)
            val hitsBefore = context.client { GunHud.hitsReceived }
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitFor({ GunHud.hitsReceived > hitsBefore }, 20)
            context.takeScreenshot("flansmod-hitmarker")

            // Attachments: red dot + suppressor via the attach key.
            server.runCommand("item replace entity @a weapon.offhand with flansmod:attachment[flansmod:attachment=\"example:red_dot\"]")
            context.waitTicks(2)
            context.input.pressKey(InputConstants.KEY_J)
            world.connection.waitForServerboundPackets()
            context.waitTicks(2)
            server.runCommand("item replace entity @a weapon.offhand with flansmod:attachment[flansmod:attachment=\"example:suppressor\"]")
            context.waitTicks(2)
            context.input.pressKey(InputConstants.KEY_J)
            context.waitTicks(5)
            val installed = server.compute { it.playerList.players.first().mainHandItem.attachments.keys }
            check(installed == setOf("sight", "barrel")) { "expected sight and barrel attachments, got $installed" }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
            context.input.pressKey { it.keyInventory }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-attachments")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }

            // The built-in pack has its own creative tab listing guns, ammo and attachments.
            server.runCommand("gamemode creative @a")
            context.waitTicks(5)
            context.input.pressKey { it.keyInventory }
            context.waitForScreen(CreativeModeInventoryScreen::class.java)
            val tabItems = context.client { mc ->
                val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(FlansMod.id("pack/basic"))
                    ?: error("no creative tab for the built-in pack")
                check((mc.gui.screen() as FabricCreativeModeInventoryScreen).setSelectedTab(tab)) { "could not select pack tab" }
                tab.displayItems.size
            }
            check(tabItems == 17) { "basic pack tab should list 5 guns + 4 ammo + 5 attachments + 3 grenades, has $tabItems" }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-pack-tab")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Basic pack guns in hand: scoped sniper (aimed) and shotgun (hip).
            server.runCommand("gamemode survival @a")
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:sniper\",flansmod:ammo=5,flansmod:attachments={sight:\"flansbasic:scope_4x\"}]")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-basic-sniper-hip")
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(10)
            context.takeScreenshot("flansmod-basic-sniper-ads")
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(3)
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            val sniperAmmo = server.compute { it.playerList.players.first().mainHandItem.ammo }
            check(sniperAmmo == 4) { "sniper should have fired once, ammo $sniperAmmo" }
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:shotgun\",flansmod:ammo=6]")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-basic-shotgun")

            // Throw a smoke grenade with right click.
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:grenade[flansmod:grenade=\"flansbasic:smoke\",minecraft:item_model=\"flansbasic:smoke\"] 2")
            context.waitTicks(5)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(10)
            context.takeScreenshot("flansmod-grenade-flying")
            context.waitTicks(70)
            val left = server.compute { it.playerList.players.first().mainHandItem.count }
            check(left == 1) { "throwing should use one grenade, $left left" }
            context.takeScreenshot("flansmod-grenade-smoke")
        }
    }

    // Kotlin cannot infer the exception type parameter of the Failable* interfaces.
    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
