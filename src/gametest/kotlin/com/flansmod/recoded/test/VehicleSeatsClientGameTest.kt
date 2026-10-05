package com.flansmod.recoded.test

import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.item.GunItem
import com.mojang.authlib.GameProfile
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * Seats, turrets and terrain tilt, seen from outside: client-only dummy players (holding pistols) fill every seat,
 * gunners look 40° to the vehicle's left and up, then vehicles stand half on a block (pitch) and with one track on a
 * block (roll). The local player is only drawn as the camera, hence the dummies. Screenshots in
 * build/run/clientGameTest/screenshots (`flansmod-seats-*`, `flansmod-tilt-*`).
 */
class VehicleSeatsClientGameTest : FabricClientGameTest {
    private val names = listOf("jeep", "humvee", "m1_abrams")

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { it.playerList.players.first().position() }

            // All three facing west (-X), 9 blocks apart along X.
            val ids = server.compute { s -> names.mapIndexed { i, name -> spawn(s, name, base.add(-9.0 + 9.0 * i, 0.0, 8.0), 90f) } }
            context.waitTicks(10)
            val dummies = context.computeOnClient<List<Int>, RuntimeException> { mc -> ids.flatMap { fillSeats(mc, it) } }
            context.runOnClient<RuntimeException> { mc -> if (!mc.gui.hud.isHidden) mc.gui.hud.toggle() }
            for ((i, name) in names.withIndex()) {
                val at = base.add(-9.0 + 9.0 * i, 0.0, 8.0)
                // Vehicle faces west: south is its left side.
                for ((view, offset) in listOf("left" to Vec3(0.5, 2.5, 7.0), "front" to Vec3(-6.0, 3.0, 4.5), "rear" to Vec3(6.0, 3.5, -4.5))) {
                    look(context, server, at.add(offset), at.add(0.0, 1.2, 0.0))
                    context.takeScreenshot("flansmod-seats-$name-$view")
                }
            }
            context.runOnClient<RuntimeException> { mc -> dummies.forEach { mc.level!!.removeEntity(it, Entity.RemovalReason.DISCARDED) } }

            // Terrain: the jeep with only its front wheels on a 1-block step, the tank with its left track on blocks.
            val stepAt = base.add(0.0, 0.0, 22.0)
            val bx = Math.floor(stepAt.x).toInt()
            val by = Math.floor(base.y).toInt()
            val bz = Math.floor(stepAt.z).toInt()
            server.runCommand("fill ${bx - 5} $by ${bz - 2} ${bx - 1} $by ${bz + 2} minecraft:stone")
            val tankAt = base.add(12.0, 0.0, 22.0)
            val tx = Math.floor(tankAt.x).toInt()
            val tz = Math.floor(tankAt.z).toInt()
            server.runCommand("fill ${tx - 3} $by ${tz + 1} ${tx + 3} $by ${tz + 2} minecraft:stone")
            server.compute { s ->
                spawn(s, "jeep", Vec3(bx + 0.5, by + 1.0, bz + 0.5), 90f)
                spawn(s, "m1_abrams", Vec3(tx + 0.5, by + 1.0, tz + 0.5), 90f)
            }
            context.waitTicks(30)
            look(context, server, Vec3(bx + 0.5, by + 2.5, bz + 7.5), Vec3(bx + 0.5, by + 1.0, bz + 0.5))
            context.takeScreenshot("flansmod-tilt-step-side")
            look(context, server, Vec3(tx - 8.5, by + 2.5, tz + 0.5), Vec3(tx + 0.5, by + 1.0, tz + 0.5))
            context.takeScreenshot("flansmod-tilt-track-front")
            context.runOnClient<RuntimeException> { mc -> if (mc.gui.hud.isHidden) mc.gui.hud.toggle() }
        }
    }

    private fun spawn(s: MinecraftServer, name: String, at: Vec3, yaw: Float) =
        DriveableEntity(s.overworld(), Identifier.fromNamespaceAndPath("flansvehicles", name), at, yaw).also { s.overworld().addFreshEntity(it) }.id

    /** One client-only dummy per seat, holding a pistol; seat order = riding order on the client. */
    private fun fillSeats(mc: Minecraft, vehicleId: Int): List<Int> {
        val level = mc.level!!
        val vehicle = level.getEntity(vehicleId) as DriveableEntity
        return vehicle.definition!!.seats.indices.map { seat ->
            RemotePlayer(level, GameProfile(UUID.randomUUID(), "Seat$seat")).apply {
                id = -10_000 - vehicleId * 10 - seat
                setItemInHand(InteractionHand.MAIN_HAND, GunItem.stackFor(Identifier.fromNamespaceAndPath("flansbasic", "glock17")))
                // 40° to the vehicle's left (it faces west, yaw 90) and 15° up.
                snapTo(vehicle.x, vehicle.y, vehicle.z, 50f, -15f)
                yHeadRot = 50f; yHeadRotO = 50f; yBodyRot = 50f
                level.addEntity(this)
                startRiding(vehicle, true, false)
            }.id
        }
    }

    /** Puts the (hidden-HUD) camera at [eye] looking at [target]. */
    private fun look(context: ClientGameTestContext, server: TestServerContext, eye: Vec3, target: Vec3) {
        server.runCommand("tp @a ${eye.x} ${eye.y - 1.62} ${eye.z} facing ${target.x} ${target.y} ${target.z}")
        context.waitTicks(4)
    }

    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
