package com.flansmod.recoded.test

import com.flansmod.recoded.client.gamemode.BattleSettingsScreen
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.fortification.Fortifications
import com.flansmod.recoded.gamemode.BattleMasterBlockEntity
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.Battles
import com.flansmod.recoded.gamemode.TeamFlagBlockEntity
import com.flansmod.recoded.gamemode.TeamFlagMenu
import com.flansmod.recoded.gamemode.BattleSpawnBlockEntity
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.ShopEditorMenu
import com.flansmod.recoded.gamemode.SoldierEntity
import com.flansmod.recoded.client.gamemode.BattleMenuScreen
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.item.StructureItem
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
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
 * Fortifications, a king-of-the-hill battle with bots (Battle Master, shop editor, settings, countdown, HUD, battle menu,
 * flag post shop, border), the structure kits and laying a mortar with its impact marker.
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

            // A battle: Battle Master, our team's flag post, a hill, border markers; king of the hill with bots.
            val master = base.offset(0, 0, -6)
            val flag = base.offset(3, 0, -9)
            val hill = base.offset(-4, 0, -12)
            val spawnPoint = base.offset(8, 0, -14)
            server.compute { s ->
                val level = s.overworld()
                val player = s.playerList.players.first()
                level.setBlockAndUpdate(master, FlansBlocks.BATTLE_MASTER.defaultBlockState())
                val bm = level.getBlockEntity(master) as BattleMasterBlockEntity
                bm.owner = player.uuid
                bm.state = bm.state.copy(settings = BattleSettings("Hill 107", listOf(BattleTeam("Axis", "dark_gray", Identifier.parse("flansww2:axis")),
                    BattleTeam("USA", "dark_green", Identifier.parse("flansww2:usa")), BattleTeam("UK", "gold", Identifier.parse("flansww2:uk"))), alliances = listOf("USA+UK"),
                    mode = BattleMode.KING_OF_THE_HILL, teamSize = 3, fillWithBots = true, countdownSeconds = 6, captureSeconds = 5, borderWall = true, blockDamage = false))
                Battles.join(player, bm, "USA")
                level.setBlockAndUpdate(flag, FlansBlocks.TEAM_FLAG.defaultBlockState())
                (level.getBlockEntity(flag) as TeamFlagBlockEntity).claim(player)
                level.setBlockAndUpdate(hill, FlansBlocks.TEAM_FLAG.defaultBlockState())
                (level.getBlockEntity(hill) as TeamFlagBlockEntity).manage(player, TeamFlagMenu.HILL)
                // UK has no flag post: a moderator gives it a default spawn point.
                level.setBlockAndUpdate(spawnPoint, FlansBlocks.BATTLE_SPAWN.defaultBlockState())
                (level.getBlockEntity(spawnPoint) as BattleSpawnBlockEntity).assign(player, "UK")
                for ((dx, dz) in listOf(-14 to -22, 14 to -22, -14 to 8, 14 to 8)) {
                    val marker = master.offset(dx, 0, dz)
                    level.setBlockAndUpdate(marker, FlansBlocks.BATTLE_BORDER.defaultBlockState())
                    bm.addBorder(marker)
                }
            }
            server.runCommand("tp @a ${spawnPoint.x + 0.5} ${spawnPoint.y} ${spawnPoint.z + 2.5} 180 40")
            context.waitTicks(10)
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(spawnPoint) as BattleSpawnBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-spawn-point")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.takeScreenshot("flansmod-battle-spawn-pad")
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

            // Shop editor: golden apples dropped on the first slot (a copy), then priced up.
            server.compute { s -> ShopEditorMenu.open(s.playerList.players.first(), s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, 1) }
            context.waitTicks(5)
            server.compute { s ->
                val player = s.playerList.players.first()
                val menu = player.containerMenu as ShopEditorMenu
                menu.setCarried(ItemStack(Items.GOLDEN_APPLE, 3))
                menu.clicked(0, 0, ContainerInput.PICKUP, player)
                menu.setCarried(ItemStack.EMPTY)
                menu.clickMenuButton(player, ShopEditorMenu.PRICE + 4)
                menu.broadcastChanges()
            }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-battle-shop-editor")
            context.input.pressKey(InputConstants.KEY_ESCAPE)

            // Start: countdown, bots deploy (the player is made immune so the test is not interrupted by a death).
            server.runCommand("effect give @a resistance infinite 4 true")
            server.compute { s -> Battles.start(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, null) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-countdown")
            server.runCommand("tp @a ${master.x + 0.5} ${master.y + 1} ${master.z + 9.5} 180 12")
            context.waitTicks(70)
            context.takeScreenshot("flansmod-battle-bots")
            check(server.compute { s -> (s.overworld().getBlockEntity(master) as BattleMasterBlockEntity).state.bots.size } >= 6) { "every team is topped up with bots" }
            context.waitTicks(40)
            context.runOnClient<RuntimeException> { mc -> mc.gui.setScreen(BattleMenuScreen()) }
            context.waitTicks(5)
            context.takeScreenshot("flansmod-battle-menu")
            context.runOnClient<RuntimeException> { mc -> mc.gui.setScreen(null) }
            server.runCommand("tp @a ${flag.x + 1.5} ${flag.y} ${flag.z + 0.5} 90 20") // menus close beyond 8 blocks
            context.waitTicks(5)
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(flag) as TeamFlagBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-flag-shop")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            server.runCommand("tp @a ${master.x + 0.5} ${master.y} ${master.z - 2.5} 0 30")
            context.waitTicks(5)
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-battle-master-running")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            // Out at the border: the wall (only fighters bump into it) and the red particle wall.
            check(server.compute { s -> (s.overworld().getBlockEntity(master) as BattleMasterBlockEntity).wall.size } > 100) { "the border wall is built" }
            server.runCommand("tp @a ${master.x + 4.5} ${master.y} ${master.z - 18.5} 160 10")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-battle-border")
            server.compute { s -> Battles.end(s.overworld().getBlockEntity(master) as BattleMasterBlockEntity, "USA") }
            server.runCommand("effect clear @a")
            context.waitTicks(20)
            context.takeScreenshot("flansmod-battle-ended")
            check(server.compute { s -> s.overworld().getEntitiesOfClass(SoldierEntity::class.java, net.minecraft.world.phys.AABB(master).inflate(64.0)).isEmpty() }) {
                "bots leave with the battle"
            }

            context.waitTicks(60) // the "wins" title fades
            // Structure kits, three per picture, placed facing south (away from the camera), seen from above.
            server.runCommand("gamemode spectator @a")
            // Airfield kits (runway, hangar) are too big for this grid; AircraftClientGameTest shows them.
            val ids = server.compute { Structures.all.filterValues { it.category != "airfield" }.keys.sortedBy { it.path } }
            ids.chunked(3).forEachIndexed { row, chunk ->
                val z = base.z + 30 + row * 30
                server.compute { s ->
                    chunk.forEachIndexed { i, id -> StructureItem.place(s.overworld(), id, Structures[id]!!, BlockPos(base.x - 14 + i * 14, base.y - 1, z), Direction.SOUTH) }
                }
                server.runCommand("tp @a ${base.x + 0.5} ${base.y + 9} ${z - 8.5} 0 32")
                context.waitTicks(30)
                context.takeScreenshot("flansmod-battle-structures-$row")
            }
            server.runCommand("gamemode survival @a")

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
