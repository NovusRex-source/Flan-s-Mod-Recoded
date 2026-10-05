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
import com.flansmod.recoded.item.PartItem
import com.flansmod.recoded.bench.WeaponsBenchMenu
import com.flansmod.recoded.client.bench.WeaponsBenchScreen
import com.flansmod.recoded.client.compat.FlansJeiPlugin
import com.flansmod.recoded.client.bench.WeaponMenuScreen
import com.flansmod.recoded.item.ClothingItem
import com.flansmod.recoded.item.fireMode
import net.minecraft.world.entity.EquipmentSlot
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
                listOf(Guns.all, Magazines.all, AmmoTypes.all, Attachments.all, com.flansmod.recoded.gun.Parts.all, com.flansmod.recoded.gun.Clothing.all).sumOf { m -> m.keys.count { it.namespace == "flansbasic" } } +
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
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:grenade[flansmod:grenade=\"flansbasic:smoke\"] 2")
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

            // Weapons Bench: right click opens the 6x4 grid; AK-47 parts show the AK-47 as result.
            server.runCommand("time set noon")
            server.runCommand("item replace entity @a weapon.mainhand with minecraft:air")
            server.runCommand("execute at @a run setblock ^ ^ ^2 flansmod:weapons_bench")
            context.waitTicks(5)
            val benchPos = server.compute { s -> s.playerList.players.first().let { p -> net.minecraft.core.BlockPos.containing(p.position().add(p.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(2.0))) } }
            context.input.lookAt(benchPos)
            context.waitTicks(2)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitForScreen(WeaponsBenchScreen::class.java)
            server.runOnServer<RuntimeException> { s ->
                val menu = s.playerList.players.first().containerMenu as WeaponsBenchMenu
                // Grid index = column + row * 6: stock, receiver, gas system, barrel in row 0; grip, trigger below.
                val layout = mapOf(0 to "wood_stock", 1 to "rifle_receiver", 2 to "gas_system", 3 to "barrel", 7 to "wood_grip", 8 to "trigger_group")
                layout.forEach { (slot, part) -> menu.grid.setItem(slot, PartItem.stackFor(Identifier.fromNamespaceAndPath("flansbasic", part))) }
                menu.slotsChanged(menu.grid)
            }
            context.waitTicks(5)
            val shown = context.client { (it.gui.screen() as WeaponsBenchScreen).menu.slots[0].item.gunId }
            check(shown == Identifier.fromNamespaceAndPath("flansbasic", "ak47")) { "bench should offer the AK-47, shows $shown" }
            context.takeScreenshot("flansmod-weapons-bench")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // JEI (dev runtime): bench recipes arrive through Fabric's recipe sync and show on a 6x4 grid.
            context.waitTicks(5)
            val jeiRecipes = context.client { FlansJeiPlugin.runtime?.recipeManager?.createRecipeLookup(FlansJeiPlugin.TYPE)?.get()?.count() ?: -1 }
            val serverRecipes = server.compute { s -> s.recipeManager.recipes.count { it.value() is com.flansmod.recoded.bench.WeaponAssemblyRecipe } }
            check(jeiRecipes.toInt() == serverRecipes) { "JEI should know all $serverRecipes bench recipes, knows $jeiRecipes" }
            context.runOnClient<RuntimeException> { FlansJeiPlugin.runtime!!.recipesGui.showTypes(listOf(FlansJeiPlugin.TYPE)) }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-jei-bench")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Fire-mode selector (K) and weapon menu (U) with an M4A1.
            server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"flansbasic:m4a1\",flansmod:magazine={magazine:\"flansbasic:stanag_30\",ammo:\"flansbasic:556\",rounds:30},flansmod:attachments={sight:\"flansbasic:acog\"}]")
            context.waitTicks(5)
            val modeBefore = server.compute { it.playerList.players.first().mainHandItem.fireMode }
            context.input.pressKey(InputConstants.KEY_K)
            context.waitTicks(5)
            val modeAfter = server.compute { it.playerList.players.first().mainHandItem.fireMode }
            check(modeBefore != modeAfter) { "K should switch the fire mode ($modeBefore)" }
            context.takeScreenshot("flansmod-fire-mode-hud")
            context.input.pressKey(InputConstants.KEY_U)
            context.waitForScreen(WeaponMenuScreen::class.java)
            val menuSlots = context.client { (it.gui.screen() as WeaponMenuScreen).menu.slotNames }
            check(menuSlots == listOf("sight", "muzzle", "underbarrel")) { "M4A1 weapon menu slots: $menuSlots" }
            context.waitTicks(3)
            context.takeScreenshot("flansmod-weapon-menu")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Clothing: Spec Ops set on the player, army set on a husk.
            server.runCommand("fill ~-3 ~ ~-3 ~3 ~2 ~3 minecraft:air")
            server.runOnServer<RuntimeException> { s ->
                val player = s.playerList.players.first()
                fun clothing(id: String) = ClothingItem.stackFor(Identifier.fromNamespaceAndPath("flansbasic", id))
                player.setItemSlot(EquipmentSlot.HEAD, clothing("spec_ops_helmet"))
                player.setItemSlot(EquipmentSlot.CHEST, clothing("spec_ops_vest"))
                player.setItemSlot(EquipmentSlot.LEGS, clothing("spec_ops_pants"))
                player.setItemSlot(EquipmentSlot.FEET, clothing("spec_ops_boots"))
                val husk = net.minecraft.world.entity.EntityTypes.HUSK.create(player.level(), net.minecraft.world.entity.EntitySpawnReason.COMMAND)!!
                husk.setNoAi(true)
                husk.snapTo(player.position().add(player.lookAngle.multiply(1.0, 0.0, 1.0).normalize().scale(3.0)).add(player.lookAngle.cross(net.minecraft.world.phys.Vec3(0.0, 1.0, 0.0)).normalize().scale(1.5)), player.yRot + 180f, 0f)
                husk.setItemSlot(EquipmentSlot.HEAD, clothing("army_helmet"))
                husk.setItemSlot(EquipmentSlot.CHEST, clothing("army_jacket"))
                husk.setItemSlot(EquipmentSlot.LEGS, clothing("army_pants"))
                husk.setItemSlot(EquipmentSlot.FEET, clothing("army_boots"))
                player.level().addFreshEntity(husk)
            }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-clothing")
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }

            // Type tabs with 3D item models.
            server.runCommand("gamemode creative @a")
            context.waitTicks(5)
            for (type in listOf("ammo", "attachments", "parts")) {
                context.input.pressKey { it.keyInventory }
                context.waitForScreen(CreativeModeInventoryScreen::class.java)
                context.runOnClient<RuntimeException> { mc ->
                    val tab = BuiltInRegistries.CREATIVE_MODE_TAB.getValue(FlansMod.id("type/$type"))!!
                    check((mc.gui.screen() as FabricCreativeModeInventoryScreen).setSelectedTab(tab)) { "could not select $type tab" }
                }
                context.waitTicks(3)
                context.takeScreenshot("flansmod-tab-$type")
                context.input.pressKey(InputConstants.KEY_ESCAPE)
                context.waitTicks(2)
            }
        }
    }

    // Kotlin cannot infer the exception type parameter of the Failable* interfaces.
    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
