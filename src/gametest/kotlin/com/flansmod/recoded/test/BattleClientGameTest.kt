package com.flansmod.recoded.test

import com.flansmod.recoded.client.gamemode.BattleSettingsScreen
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.TeamFlagBlockEntity
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.registry.FlansBlocks
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.DoorBlock
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.phys.Vec3

/**
 * Fortifications, a battle (Battle Master, flag post, shop, settings, HUD) and laying a mortar with its impact marker.
 * Screenshots `flansmod-battle-*` in build/run/clientGameTest/screenshots.
 */
class BattleClientGameTest : FabricClientGameTest {
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

            // Fortifications in a row (facing the camera to the south).
            server.compute { s ->
                val level = s.overworld()
                Fortifications.ALL.forEachIndexed { i, block ->
                    val pos = base.offset(-7 + i * 2, 0, 6)
                    var state = block.defaultBlockState()
                    if (state.hasProperty(HorizontalDirectionalBlock.FACING)) state = state.setValue(HorizontalDirectionalBlock.FACING, Direction.NORTH)
                    level.setBlockAndUpdate(pos, state)
                    if (block is DoorBlock) level.setBlockAndUpdate(pos.above(), state.setValue(DoorBlock.HALF, net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER))
                }
            }
            server.runCommand("tp @a ${base.x + 9.5} ${base.y} ${base.z + 2.5} 30 15")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-battle-fortifications-left")
            server.runCommand("tp @a ${base.x - 3.5} ${base.y} ${base.z + 2.5} -30 15")
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-fortifications-right")

            // A battle: Battle Master, our team's flag post, start.
            val master = base.offset(0, 0, -6)
            val flag = base.offset(3, 0, -9)
            server.compute { s ->
                val level = s.overworld()
                val player = s.playerList.players.first()
                level.setBlockAndUpdate(master, FlansBlocks.BATTLE_MASTER.defaultBlockState())
                val bm = level.getBlockEntity(master) as BattleMasterBlockEntity
                bm.owner = player.uuid
                bm.state = bm.state.copy(settings = BattleSettings("Hill 107", listOf(BattleTeam("Axis", "dark_gray", Identifier.parse("flansww2:axis")),
                    BattleTeam("USA", "dark_green", Identifier.parse("flansww2:usa")), BattleTeam("UK", "gold", Identifier.parse("flansww2:uk"))), alliances = listOf("USA+UK")))
                Battles.join(player, bm, "USA")
                level.setBlockAndUpdate(flag, FlansBlocks.TEAM_FLAG.defaultBlockState())
                (level.getBlockEntity(flag) as TeamFlagBlockEntity).claim(player)
            }
            server.runCommand("tp @a ${master.x + 0.5} ${master.y} ${master.z - 2.5} 0 30")
            context.waitTicks(10)
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-master-lobby")
            val settings = server.compute { s -> (s.overworld().getBlockEntity(master) as BattleMasterBlockEntity).settings }
            context.runOnClient<RuntimeException> { mc -> mc.gui.setScreen(BattleSettingsScreen.create(mc.gui.screen()!!, settings, master)) }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-battle-settings")
            context.runOnClient<RuntimeException> { mc -> mc.gui.setScreen(null) }
            server.compute { s -> Battles.start(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, null) }
            context.waitTicks(30)
            context.takeScreenshot("flansmod-battle-started")
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(flag) as TeamFlagBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-flag-shop")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-master-running")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.compute { s -> Battles.end(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, "USA") }
            context.waitTicks(20)
            context.takeScreenshot("flansmod-battle-ended")

            // Mortar: laid with W/A, the predicted impact is marked.
            val mortar = server.compute { s ->
                val level = s.overworld()
                DriveableEntity(level, Identifier.fromNamespaceAndPath("flansvehicles", "m252"), Vec3.atBottomCenterOf(base.offset(0, 0, -16)), 180f).also {
                    level.addFreshEntity(it)
                    it.setMagazine(0, MagazineContents.full(Identifier.fromNamespaceAndPath("flansvehicles", "81mm_tube"), Identifier.fromNamespaceAndPath("flansvehicles", "81mm_mortar_he")))
                    s.playerList.players.first().startRiding(it, true, true)
                }.id
            }
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { mc -> mc.options.cameraType = CameraType.THIRD_PERSON_BACK; mc.player!!.xRot = 10f }
            context.runOnClient<RuntimeException> { mc -> mc.options.keyUp.isDown = true; mc.options.keyLeft.isDown = true }
            context.waitTicks(25)
            context.runOnClient<RuntimeException> { mc -> mc.options.keyUp.isDown = false; mc.options.keyLeft.isDown = false }
            context.waitTicks(10)
            context.runOnClient<RuntimeException> { mc ->
                mc.options.cameraType = CameraType.FIRST_PERSON
                mc.player!!.yRot = (mc.player!!.vehicle as DriveableEntity).yRot + 10f; mc.player!!.xRot = 3f // just beside the tube
            }
            context.waitTicks(80) // the "wins" title fades
            context.takeScreenshot("flansmod-battle-mortar-laid")
            check(context.client { com.flansmod.recoded.client.vehicle.ArtilleryClient.impact != null }) { "a loaded mortar should show its impact point" }
            context.runOnClient<RuntimeException> { mc -> mc.options.cameraType = CameraType.FIRST_PERSON }
            server.compute { s -> s.playerList.players.first().stopRiding(); s.overworld().getEntity(mortar)?.discard() }
        }
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
