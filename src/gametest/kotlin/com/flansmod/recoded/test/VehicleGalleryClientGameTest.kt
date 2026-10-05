package com.flansmod.recoded.test

import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.client.vehicle.VehicleClient
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gun.Vehicles
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.creativetab.v1.FabricCreativeModeInventoryScreen
import net.minecraft.client.CameraType
import com.flansmod.recoded.entity.MineEntity
import com.flansmod.recoded.fuel.FuelMachineBlock
import com.flansmod.recoded.fuel.FuelMachineBlockEntity
import com.flansmod.recoded.fuel.FuelStack
import com.flansmod.recoded.fuel.FuelSynthesizerBlockEntity
import com.flansmod.recoded.fuel.PetrolStationBlockEntity
import com.flansmod.recoded.gun.FuelTypes
import com.flansmod.recoded.gun.Grenades
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.item.GrenadeItem
import com.flansmod.recoded.registry.FlansBlocks
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Every vehicle of the built-in packs, one after another: outside views with a dummy crew (front-left and rear-right),
 * then the local player in each seat in first person (interior, windows, vehicle HUD) and, in gun seats, looking
 * through the gunner's sight (right click toggles it). Screenshots `flansmod-vehicle-<pack>-<id>-*` in
 * build/run/clientGameTest/screenshots.
 */
class VehicleGalleryClientGameTest : FabricClientGameTest {
    private val packs = listOf("flansvehicles", "flansww2")

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamerule send_command_feedback false") // no chat lines in the screenshots
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { it.playerList.players.first().position() }
            val at = base.add(0.0, 0.0, 12.0)

            for (pack in packs) {
                val ids = context.client { Vehicles.all.keys.filter { it.namespace == pack }.sortedBy { it.path } }
                for (id in ids) {
                    val name = "flansmod-vehicle-${pack.removePrefix("flans")}-${id.path}"
                    server.runCommand("gamemode spectator @a")
                    val vehicle = server.compute { s -> DriveableEntity(s.overworld(), id, at, 90f).also { s.overworld().addFreshEntity(it) }.id }
                    context.waitTicks(10)
                    val dummies = context.client { mc -> fillSeats(mc, vehicle) }
                    context.runOnClient<RuntimeException> { mc -> if (!mc.gui.hud.isHidden) mc.gui.hud.toggle() }
                    // Faces west (-X): south (+Z) is its left side.
                    look(context, server, at.add(-8.0, 3.5, 6.5), at.add(0.0, 1.2, 0.0))
                    context.takeScreenshot("$name-front")
                    look(context, server, at.add(7.5, 4.5, -6.5), at.add(0.0, 1.2, 0.0))
                    context.takeScreenshot("$name-rear")
                    context.runOnClient<RuntimeException> { mc ->
                        dummies.forEach { mc.level!!.removeEntity(it, Entity.RemovalReason.DISCARDED) }
                        if (mc.gui.hud.isHidden) mc.gui.hud.toggle()
                        mc.options.cameraType = CameraType.FIRST_PERSON
                    }

                    // Each seat in first person; gun seats also through the sight.
                    server.runCommand("gamemode creative @a")
                    server.runCommand("tp @a ${at.x} ${at.y} ${at.z + 3}")
                    context.waitTicks(5)
                    val seats = context.client { Vehicles[id]!!.seats.indices.toList() }
                    val gunSeats = context.client { Vehicles[id]!!.seats.withIndex().filter { it.value.gun != null }.map { it.index }.toSet() }
                    server.compute { s -> s.playerList.players.first().startRiding(s.overworld().getEntity(vehicle)!!, true, true) }
                    for (seat in seats.filter { it == 0 || it in gunSeats }) {
                        server.compute { s ->
                            val player = s.playerList.players.first()
                            val v = s.overworld().getEntity(vehicle) as DriveableEntity
                            repeat(seats.size) { if (v.seatOf(player) != seat) v.switchSeat(player) }
                        }
                        context.waitTicks(10)
                        context.runOnClient<RuntimeException> { mc -> mc.player!!.yRot = 75f; mc.player!!.xRot = 8f }
                        context.waitTicks(5)
                        context.takeScreenshot("$name-seat$seat")
                        if (seat in gunSeats) {
                            rightClick(context)
                            context.waitTicks(15)
                            check(context.client { VehicleClient.sighting }) { "$id seat $seat: right click should look through the gun sight" }
                            context.takeScreenshot("$name-seat$seat-sight")
                            rightClick(context)
                            context.waitTicks(10)
                            check(context.client { !VehicleClient.sighting && !GunInput.aiming }) { "$id seat $seat: right click again should leave the sight" }
                        }
                    }
                    server.compute { s ->
                        s.playerList.players.first().stopRiding()
                        s.overworld().getEntity(vehicle)?.discard()
                    }
                    context.waitTicks(5)
                }
            }
            context.runOnClient<RuntimeException> { mc -> mc.options.cameraType = CameraType.FIRST_PERSON }

            utilities(context, server, base)

            // Faction creative tabs (WW2): each lists its side's guns, ammunition, grenades, vehicles and uniforms.
            server.runCommand("gamemode creative @a")
            context.waitTicks(5)
            val tabs = context.client { BuiltInRegistries.CREATIVE_MODE_TAB.keySet().filter { it.namespace == "flansmod" && "faction_" in it.path }.sortedBy { it.path } }
            check(tabs.size == 4) { "expected 4 WW2 faction tabs, got $tabs" }
            context.input.pressKey { it.keyInventory }
            context.waitForScreen(CreativeModeInventoryScreen::class.java)
            for (tab in tabs) {
                context.runOnClient<RuntimeException> { mc ->
                    check((mc.gui.screen() as FabricCreativeModeInventoryScreen).setSelectedTab(BuiltInRegistries.CREATIVE_MODE_TAB.getValue(tab)!!)) { "could not select $tab" }
                }
                context.waitTicks(2)
                check(context.client { BuiltInRegistries.CREATIVE_MODE_TAB.getValue(tab)!!.displayItems.size >= 10 }) { "$tab lists too few items" }
                context.takeScreenshot("flansmod-faction-tab-${tab.path.substringAfterLast('/')}")
            }
            context.input.pressKey(InputConstants.KEY_ESCAPE)
        }
    }

    /**
     * Field equipment: petrol station with a synthesizer next to it, a diesel truck being filled, mines of every pack
     * laid in a row, both machine screens, and a loaded mortar's HUD (elevation and range).
     */
    private fun utilities(context: ClientGameTestContext, server: TestServerContext, base: Vec3) {
        server.runCommand("gamemode creative @a")
        val at = BlockPos.containing(base.add(0.0, 0.0, 10.0))
        server.compute { s ->
            val level = s.overworld()
            level.setBlockAndUpdate(at, FlansBlocks.PETROL_STATION.defaultBlockState().setValue(FuelMachineBlock.FACING, Direction.SOUTH))
            level.setBlockAndUpdate(at.west(), FlansBlocks.FUEL_SYNTHESIZER.defaultBlockState().setValue(FuelMachineBlock.FACING, Direction.SOUTH))
            (level.getBlockEntity(at) as PetrolStationBlockEntity).tank = FuelStack(FuelTypes.DIESEL, 40000)
            (level.getBlockEntity(at.west()) as FuelSynthesizerBlockEntity).apply {
                setItem(FuelSynthesizerBlockEntity.COAL, ItemStack(Items.COAL, 12))
                setItem(FuelSynthesizerBlockEntity.WATER, ItemStack(Items.WATER_BUCKET))
            }
            DriveableEntity(level, Identifier.fromNamespaceAndPath("flansvehicles", "m35"), Vec3.atBottomCenterOf(at).add(3.5, 0.0, 1.0), 0f).also { level.addFreshEntity(it) }
            val mines = Grenades.all.filter { it.value.mine != null }.keys.sortedBy { it.toString() }
            mines.forEachIndexed { i, id ->
                level.addFreshEntity(MineEntity(level, GrenadeItem.stackFor(id), Vec3.atBottomCenterOf(at).add(-4.0 + i * 0.9, 0.0, 4.0), 0f, null))
            }
        }
        server.runCommand("tp @a ${at.x + 0.5} ${at.y + 1.2} ${at.z + 7.5} 180 25")
        context.waitTicks(30)
        context.takeScreenshot("flansmod-utilities-scene")
        for ((name, pos) in listOf("petrol_station" to at, "fuel_synthesizer" to at.west())) {
            server.compute { s -> s.playerList.players.first().openMenu(s.overworld().getBlockEntity(pos) as FuelMachineBlockEntity) }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-utilities-$name-menu")
            context.input.pressKey(InputConstants.KEY_ESCAPE)
            context.waitTicks(2)
        }
        // A mortar with a bomb in the tube: the HUD shows elevation and range.
        val mortar = server.compute { s ->
            DriveableEntity(s.overworld(), Identifier.fromNamespaceAndPath("flansvehicles", "m252"), Vec3.atBottomCenterOf(at).add(0.0, 0.0, -4.0), 180f).also {
                s.overworld().addFreshEntity(it)
                it.setMagazine(0, MagazineContents.full(Identifier.fromNamespaceAndPath("flansvehicles", "81mm_tube"), Identifier.fromNamespaceAndPath("flansvehicles", "81mm_mortar_he")))
                s.playerList.players.first().startRiding(it, true, true)
            }.id
        }
        context.waitTicks(10)
        context.runOnClient<RuntimeException> { mc -> mc.options.cameraType = CameraType.THIRD_PERSON_BACK; mc.player!!.yRot = 180f; mc.player!!.xRot = -30f }
        context.waitTicks(10)
        context.takeScreenshot("flansmod-utilities-mortar-hud")
        context.runOnClient<RuntimeException> { mc -> mc.options.cameraType = CameraType.FIRST_PERSON }
        server.compute { s -> s.playerList.players.first().stopRiding(); s.overworld().getEntity(mortar)?.discard() }
    }

    /** One client-only dummy per seat, looking 20° to the vehicle's left. */
    private fun fillSeats(mc: Minecraft, vehicleId: Int): List<Int> {
        val level = mc.level!!
        val vehicle = level.getEntity(vehicleId) as DriveableEntity
        return vehicle.definition!!.seats.indices.map { seat ->
            RemotePlayer(level, GameProfile(UUID.randomUUID(), "Crew$seat")).apply {
                id = -20_000 - vehicleId * 10 - seat
                snapTo(vehicle.x, vehicle.y, vehicle.z, 70f, -5f)
                yHeadRot = 70f; yHeadRotO = 70f; yBodyRot = 70f
                level.addEntity(this)
                startRiding(vehicle, true, false)
            }.id
        }
    }

    /** The use key (right click) for two ticks; simulated mouse clicks only grab the test window's mouse. */
    private fun rightClick(context: ClientGameTestContext) {
        context.runOnClient<RuntimeException> { it.options.keyUse.isDown = true }
        context.waitTicks(2)
        context.runOnClient<RuntimeException> { it.options.keyUse.isDown = false }
    }

    private fun look(context: ClientGameTestContext, server: TestServerContext, eye: Vec3, target: Vec3) {
        server.runCommand("tp @a ${eye.x} ${eye.y - 1.62} ${eye.z} facing ${target.x} ${target.y} ${target.z}")
        context.waitTicks(4)
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
