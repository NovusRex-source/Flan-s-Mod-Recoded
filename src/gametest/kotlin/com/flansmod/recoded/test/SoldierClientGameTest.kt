package com.flansmod.recoded.test

import com.flansmod.recoded.gamemode.SoldierAttitude
import com.flansmod.recoded.item.SoldierItem
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.GameType

/**
 * Soldiers spawned per faction outside battles: a line-up of every WW2 faction (inactive, so they stand for the
 * picture) and the spawn items' icons (faction colour, attitude badge). Screenshots `flansmod-soldiers-*`.
 */
class SoldierClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamerule send_command_feedback false")
            context.waitTicks(20)
            val base = server.compute { BlockPos.containing(it.playerList.players.first().position()) }
            val factions = listOf("axis", "usa", "uk", "ussr").map { Identifier.fromNamespaceAndPath("flansww2", it) }
            server.compute { s ->
                factions.forEachIndexed { i, f ->
                    repeat(2) { j -> SoldierItem.spawn(s.overworld(), base.offset(-3 + i * 2, 0, 5 + j * 2), f, SoldierAttitude.INACTIVE, 180f) }
                }
            }
            server.runCommand("tp @a ${base.x + 0.5} ${base.y + 1} ${base.z - 1.5} 0 15")
            context.waitTicks(40)
            context.takeScreenshot("flansmod-soldiers-lineup")

            // The spawn items in the hotbar and inventory: one row per attitude, any faction first.
            server.compute { s ->
                val player = s.playerList.players.first()
                player.setGameMode(GameType.SURVIVAL)
                val stacks = (listOf<Identifier?>(null) + factions).flatMap { f -> SoldierAttitude.entries.map { SoldierItem.stackFor(f, it) } }
                stacks.forEachIndexed { i, stack -> player.inventory.setItem(i, stack) }
            }
            context.waitTicks(5)
            context.input.pressKey(InputConstants.KEY_E)
            context.waitTicks(10)
            context.takeScreenshot("flansmod-soldiers-items")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
        }
    }

    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
