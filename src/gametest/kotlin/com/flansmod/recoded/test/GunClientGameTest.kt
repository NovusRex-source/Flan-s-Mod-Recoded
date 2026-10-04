package com.flansmod.recoded.test

import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.gunId
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.resources.Identifier
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
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

            // Aim down sights (right mouse).
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
            context.waitTicks(10)
            check(context.client { GunInput.aiming }) { "holding right mouse should aim" }
            context.takeScreenshot("flansmod-ads")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)

            // Full-auto burst (left mouse): 10 ticks at 600 rpm = 5 shots.
            context.input.holdMouseFor(InputConstants.MOUSE_BUTTON_LEFT, 10)
            context.waitTicks(5)
            val afterFiring = server.compute { it.playerList.players.first().mainHandItem.ammo }
            check(afterFiring in 1..28) { "expected several shots to be fired, ammo is $afterFiring" }
            context.takeScreenshot("flansmod-fired")

            // Reload (R): 40 ticks, consumes nuggets at 10 rounds each.
            context.input.pressKey(InputConstants.KEY_R)
            context.waitTicks(50)
            val afterReload = server.compute { it.playerList.players.first().mainHandItem.ammo }
            check(afterReload == 30) { "reload should refill to 30, ammo is $afterReload (was $afterFiring)" }
            context.takeScreenshot("flansmod-reloaded")
        }
    }

    // Kotlin cannot infer the exception type parameter of the Failable* interfaces.
    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
