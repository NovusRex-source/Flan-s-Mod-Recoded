package com.flansmod.recoded.test

import com.flansmod.recoded.client.movement.MovementClient
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.item.attachments
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Pose
import net.minecraft.world.level.block.Blocks

/**
 * Movement and gear on a real client: climbing a 2-block wall, going prone (Z) with a bipod MG, the sprint slide,
 * a backpack on the back and its screen (B), binoculars and a laser dot. Screenshots `flansmod-gear-*`.
 */
class GearMovementClientGameTest : FabricClientGameTest {
    private fun id(path: String) = Identifier.fromNamespaceAndPath("flansbasic", path)

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamerule send_command_feedback false")
            server.runCommand("gamemode survival @a")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { BlockPos.containing(it.playerList.players.first().position()) }

            // Climb: a 2-block wall straight ahead (south), room on top.
            server.runCommand("fill ${base.x - 2} ${base.y} ${base.z + 2} ${base.x + 2} ${base.y + 1} ${base.z + 4} minecraft:stone_bricks")
            server.runCommand("tp @a ${base.x + 0.5} ${base.y} ${base.z + 1.5} 0 0")
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { it.options.keyJump.isDown = true }
            context.waitTicks(2)
            context.runOnClient<RuntimeException> { it.options.keyJump.isDown = false }
            context.waitTicks(25)
            val climbed = context.client { it.player!!.y - base.y }
            check(climbed > 1.9) { "jumping at a 2-block wall should climb onto it, rose $climbed" }
            context.takeScreenshot("flansmod-gear-climbed")

            // Gear: rucksack (worn on the back), an M249 with bipod, binoculars, laser rifle.
            server.compute { s ->
                val player = s.playerList.players.first()
                com.flansmod.recoded.gear.GearSlots.setBack(player, GearItem.stackFor(id("rucksack")))
                val vest = com.flansmod.recoded.item.ClothingItem.stackFor(id("spec_ops_vest"))
                com.flansmod.recoded.gear.GearSlots.setPlates(vest, listOf(GearItem.stackFor(id("ceramic_plate")), GearItem.stackFor(id("steel_plate"))))
                player.setItemSlot(net.minecraft.world.entity.EquipmentSlot.CHEST, vest)
                player.inventory.setItem(10, GearItem.stackFor(id("soft_armor_insert")))
                player.inventory.setItem(0, GunItem.stackFor(id("m249")).apply { attachments = mapOf("underbarrel" to id("bipod")) })
                player.inventory.setItem(1, GearItem.stackFor(id("binoculars")))
                player.inventory.setItem(2, GunItem.stackFor(id("m4a1")).apply { attachments = mapOf("underbarrel" to id("laser_sight")) })
                player.inventory.selectedSlot = 0
            }
            server.runCommand("tp @a ${base.x + 0.5} ${base.y + 2} ${base.z + 3.5} 180 10")
            context.waitTicks(25)
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-gear-backpack-front")
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_BACK }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-gear-backpack-back")

            // Prone (Z): crawling pose, bipod set down.
            context.input.holdKeyFor(MovementClient.PRONE, 2)
            context.waitTicks(10)
            check(context.client { it.player!!.pose == Pose.SWIMMING }) { "Z should lay the player down" }
            context.takeScreenshot("flansmod-gear-prone")
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-gear-prone-hud")
            context.input.holdKeyFor(MovementClient.PRONE, 2)
            context.waitTicks(10)
            check(context.client { it.player!!.pose == Pose.STANDING }) { "Z again should stand up" }

            // Backpack screen (B).
            context.input.holdKeyFor(com.flansmod.recoded.client.gear.GearClient.BACKPACK, 2)
            context.waitForScreen(ContainerScreen::class.java)
            context.takeScreenshot("flansmod-gear-backpack-screen")
            context.input.pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE)
            context.waitTicks(5)

            // Inventory with the gear slots: two plate slots (the plate carrier has two) and the backpack slot.
            context.input.pressKey { it.keyInventory }
            context.waitForScreen(net.minecraft.client.gui.screens.inventory.InventoryScreen::class.java)
            context.takeScreenshot("flansmod-gear-inventory-slots")
            context.input.pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE)
            server.runCommand("gamemode creative @a")
            context.waitTicks(5)
            context.input.pressKey { it.keyInventory }
            context.waitForScreen(net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen::class.java)
            context.runOnClient<RuntimeException> { mc ->
                val screen = mc.gui.screen() as net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen
                check(screen.setSelectedTab(net.minecraft.core.registries.BuiltInRegistries.CREATIVE_MODE_TAB.getValue(net.minecraft.world.item.CreativeModeTabs.INVENTORY)!!)) { "inventory tab" }
            }
            context.waitTicks(3)
            context.takeScreenshot("flansmod-gear-creative-slots")
            context.input.pressKey(com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE)
            server.runCommand("gamemode survival @a")
            context.waitTicks(5)

            // Binoculars: zoom and overlay while used.
            context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = 1 }
            server.compute { s -> s.playerList.players.first().inventory.selectedSlot = 1 }
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.options.keyUse.isDown = true }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-gear-binoculars")
            context.runOnClient<RuntimeException> { it.options.keyUse.isDown = false }

            // Laser: a dot where the rifle points (a wall 6 blocks ahead).
            server.runCommand("fill ${base.x - 3} ${base.y + 2} ${base.z - 4} ${base.x + 3} ${base.y + 5} ${base.z - 4} minecraft:white_concrete")
            context.runOnClient<RuntimeException> { it.player!!.inventory.selectedSlot = 2 }
            server.compute { s -> s.playerList.players.first().inventory.selectedSlot = 2 }
            context.waitTicks(15)
            context.takeScreenshot("flansmod-gear-laser")
        }
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
