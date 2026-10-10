package com.flansmod.recoded.test

import com.flansmod.recoded.client.gamemode.BattleHud
import com.flansmod.recoded.client.trenches.TrenchCommandScreen
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.registry.FlansBlocks
import com.flansmod.recoded.trenches.TrenchAiLevel
import com.flansmod.recoded.trenches.TrenchField
import com.flansmod.recoded.trenches.TrenchLayout
import com.flansmod.recoded.trenches.TrenchRules
import com.flansmod.recoded.trenches.TrenchSettings
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.server.MinecraftServer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier

/**
 * A Trenches battle in a real client: the field built by the Battle Master, the British side commanded through the
 * command screen (M, then keys), the Germans by the computer, watched from above and from inside a trench.
 * Screenshots `flansmod-trenches-*` in build/run/clientGameTest/screenshots.
 */
class TrenchClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamerule send_command_feedback false")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { BlockPos.containing(it.playerList.players.first().position()) }
            val master = base.offset(0, 0, 2)
            server.compute { s ->
                val level = s.overworld()
                val player = s.playerList.players.first()
                level.setBlockAndUpdate(master, FlansBlocks.BATTLE_MASTER.defaultBlockState())
                val bm = level.getBlockEntity(master) as BattleMasterBlockEntity
                bm.owner = player.uuid
                bm.state = bm.state.copy(settings = BattleSettings("Somme 1916", listOf(BattleTeam("British", "gold", Identifier.parse("flansww2:uk")),
                    BattleTeam("Germans", "dark_gray", Identifier.parse("flansww2:axis"))), countdownSeconds = 3, timeLimitMinutes = 0,
                    trenches = TrenchSettings(ai = TrenchAiLevel.NORMAL, startFunds = 1200)))
                Battles.join(player, bm, "British")
                check(TrenchField.build(bm, level, Direction.SOUTH)) { "the field is built" }
            }
            val layout = server.compute { s -> TrenchLayout.of(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity) } ?: error("a lane")
            val middle = layout.zones[layout.zones.size / 2].flag

            // The field from the side.
            server.runCommand("gamemode spectator @a")
            server.runCommand("tp @a ${middle.x + 38.5} ${middle.y + 26} ${middle.z + 0.5} 90 32")
            context.waitTicks(60)
            context.takeScreenshot("flansmod-trenches-field")
            server.runCommand("tp @a ${layout.zones[0].flag.x + 7.5} ${layout.zones[0].flag.y + 5} ${layout.zones[0].flag.z + 14.5} 150 25")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-trenches-headquarters")
            server.runCommand("gamemode creative @a")

            // Start; command the British through the screen: rifle squads and a machine gun team.
            server.compute { s -> Battles.start(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, null) }
            context.waitTicks(80)
            world.connection.waitForClientboundPackets()
            context.input.pressKey(InputConstants.KEY_M)
            context.waitTicks(10)
            check(context.onClient { it.gui.screen() is TrenchCommandScreen }) { "M opens the command screen in a Trenches battle" }
            context.input.pressKey(InputConstants.KEY_1)
            context.waitTicks(5)
            context.input.pressKey(InputConstants.KEY_2)
            context.waitTicks(15)
            context.takeScreenshot("flansmod-trenches-command")
            check(server.compute { s -> TrenchRules.units(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, s.overworld()).count { it.team == "British" } } >= 5) {
                "keys 1 and 2 sent a rifle squad and a machine gun team"
            }
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Keep sending squads and push forward now and then while the computer leads the Germans.
            repeat(12) { round ->
                server.compute { s ->
                    val bm = s.overworld().getBlockEntity(master) as BattleMasterBlockEntity
                    listOf("rifle_squad", "machine_gun_team", "assault_squad", "mortar_team").forEach {
                        TrenchRules.buy(bm, s.overworld(), "British", Identifier.fromNamespaceAndPath("flanstrenches", it))
                    }
                    if (round % 4 == 3) TrenchRules.orderAll(bm, s.overworld(), "British", forward = true)
                }
                context.waitTicks(100)
            }
            server.compute { s -> Battles.spectate(s.playerList.players.first(), s.overworld().getBlockEntity(master) as BattleMasterBlockEntity) }
            context.waitTicks(40)
            context.takeScreenshot("flansmod-trenches-above")
            context.input.pressKey(InputConstants.KEY_M)
            context.waitTicks(20)
            context.takeScreenshot("flansmod-trenches-command-battle")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            check(context.onClient { BattleHud.current?.trench?.zones?.any { z -> !z.base && z.owner >= 0 } == true }) { "trenches have been taken" }

            // Inside the first trench line.
            val first = layout.zones[1].flag
            server.runCommand("tp @a ${first.x - 6.5} ${first.y + 2.2} ${first.z + 0.5} -90 12")
            context.waitTicks(30)
            context.takeScreenshot("flansmod-trenches-trench")

            server.compute { s -> Battles.end(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, null) }
            context.waitTicks(20)
        }
    }

    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
    private fun <T> ClientGameTestContext.onClient(f: (net.minecraft.client.Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
}
