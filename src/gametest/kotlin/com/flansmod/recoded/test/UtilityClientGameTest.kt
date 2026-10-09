package com.flansmod.recoded.test

import com.flansmod.recoded.client.utility.FieldMapScreen
import com.flansmod.recoded.client.utility.UtilityClient
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.utility.Flashlights
import com.flansmod.recoded.utility.Utilities
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.level.block.Blocks

/**
 * Field utilities in a real client: the field map (right click) with a waypoint, the compass strip pointing to it,
 * and a flashlight lighting the ground at night. Screenshots in build/run/clientGameTest/screenshots.
 */
class UtilityClientGameTest : FabricClientGameTest {
    private fun gear(name: String) = Identifier.fromNamespaceAndPath("flansbasic", name)

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set midnight")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamemode survival @a")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { it.playerList.players.first().blockPosition() }
            // Something to find on the map: a small stone tower 30 blocks north-east.
            server.runCommand("fill ${base.x + 20} ${base.y} ${base.z - 22} ${base.x + 23} ${base.y + 6} ${base.z - 19} minecraft:stone_bricks")

            // Field map: right click opens it; a click sets the waypoint at the tower.
            server.compute { s -> s.playerList.players.first().setItemInHand(InteractionHand.MAIN_HAND, GearItem.stackFor(gear("field_map"))) }
            context.waitTicks(5)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitForScreen(FieldMapScreen::class.java)
            context.runOnClient<RuntimeException> { UtilityClient.waypoint = BlockPos(base.x + 21, base.y + 7, base.z - 20) }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-utility-map")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Compass in the off hand: the heading strip with the waypoint ahead.
            server.compute { s -> s.playerList.players.first().setItemInHand(InteractionHand.OFF_HAND, GearItem.stackFor(gear("lensatic_compass"))) }
            server.runCommand("tp @a ${base.x + 0.5} ${base.y} ${base.z + 0.5} facing ${base.x + 21} ${base.y + 2} ${base.z - 20}")
            context.waitTicks(10)
            context.takeScreenshot("flansmod-utility-compass")

            // Flashlight at night: switched on with a right click, it lights the ground where you point.
            server.compute { s -> s.playerList.players.first().setItemInHand(InteractionHand.MAIN_HAND, GearItem.stackFor(gear("flashlight"))) }
            server.runCommand("tp @a ${base.x + 0.5} ${base.y} ${base.z + 0.5} -90 35")
            context.waitTicks(5)
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(10)
            val light = server.compute { s -> s.playerList.players.first().let { p -> Flashlights.lightOf(p)?.takeIf { s.overworld().getBlockState(it).`is`(Blocks.LIGHT) } } }
            check(light != null) { "the switched-on flashlight should light the spot it points at" }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-utility-flashlight")
            check(context.client { mc -> mc.player!!.mainHandItem.getOrDefault(Utilities.ACTIVE, false) }) { "the flashlight should be on" }
            context.input.pressMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(5)
            check(server.compute { s -> s.overworld().getBlockState(light).isAir }) { "switching it off removes the light" }
        }
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
