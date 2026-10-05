package com.flansmod.recoded.test

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.client.hud.GunHud
import com.flansmod.recoded.client.input.GunInput
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.effect.MobEffects
import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Attachments
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.Magazines
import com.flansmod.recoded.item.loadedMagazine
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
            server.runCommand("give @a flansmod:gun[flansmod:gun=\"$rifle\",flansmod:magazine={magazine:\"example:rifle_mag\",ammo:\"example:rifle_round\",rounds:30}]")
            server.runCommand("give @a flansmod:magazine[flansmod:magazine={magazine:\"example:rifle_mag\",ammo:\"example:rifle_round\",rounds:30}]")
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
            check(afterReload == 30) { "reload should insert the full magazine, ammo is $afterReload (was $afterFiring)" }
            val returned = server.compute { s -> s.playerList.players.first().inventory.nonEquipmentItems.mapNotNull { it.loadedMagazine }.map { it.rounds } }
            check(afterFiring in returned) { "the old magazine ($afterFiring rounds) should be back in the inventory, found $returned" }
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
            val expected = context.client {
                listOf(Guns.all, Magazines.all, AmmoTypes.all, Attachments.all).sumOf { m -> m.keys.count { it.namespace == "flansbasic" } } +
                    Grenades.all.count { (id, g) -> id.namespace == "flansbasic" && g.throwable }
            }
            val tabItems = context.client { mc ->
                val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(FlansMod.id("pack/basic"))
                    ?: error("no creative tab for the built-in pack")
                check((mc.gui.screen() as FabricCreativeModeInventoryScreen).setSelectedTab(tab)) { "could not select pack tab" }
                tab.displayItems.size
            }
            check(tabItems == expected) { "basic pack tab should list all $expected pack items, has $tabItems" }
            context.waitTicks(2)
            context.takeScreenshot("flansmod-pack-tab")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Basic pack guns in hand: scoped sniper (aimed) and shotgun (hip).
            server.runCommand("gamemode survival @a")
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:awm\",flansmod:magazine={magazine:\"flansbasic:awm_5\",ammo:\"flansbasic:338\",rounds:5},flansmod:attachments={sight:\"flansbasic:sniper_scope\"}]")
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
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:m870\",flansmod:magazine={magazine:\"flansbasic:shell_holder_6\",ammo:\"flansbasic:12g\",rounds:6}]")
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

            // Night vision scope at midnight: the server grants night vision while aiming.
            server.runCommand("time set midnight")
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:m4a1\",flansmod:magazine={magazine:\"flansbasic:stanag_30\",ammo:\"flansbasic:556\",rounds:30},flansmod:attachments={sight:\"flansbasic:nv_scope\"}]")
            server.runCommand("execute at @a run summon minecraft:husk ^3 ^ ^14 {NoAI:1b}")
            server.runCommand("execute at @a run summon minecraft:husk ^-4 ^ ^18 {NoAI:1b}")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-night-unaided")
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(12)
            check(context.client { it.player!!.hasEffect(MobEffects.NIGHT_VISION) }) { "night vision scope should grant night vision" }
            context.takeScreenshot("flansmod-scope-night-vision")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(5)
            check(context.client { !it.player!!.hasEffect(MobEffects.NIGHT_VISION) }) { "night vision should end when aiming stops" }

            // Thermal scope: living mobs glow while aiming.
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:m4a1\",flansmod:magazine={magazine:\"flansbasic:stanag_30\",ammo:\"flansbasic:556\",rounds:30},flansmod:attachments={sight:\"flansbasic:thermal_scope\"}]")
            context.waitTicks(5)
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(12)
            val glowing = context.client { mc -> mc.level!!.entitiesForRendering().count { it is net.minecraft.world.entity.monster.zombie.Husk && mc.shouldEntityAppearGlowing(it) } }
            check(glowing > 0) { "thermal scope should outline mobs" }
            context.takeScreenshot("flansmod-scope-thermal")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)
        }
    }

    // Kotlin cannot infer the exception type parameter of the Failable* interfaces.
    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
