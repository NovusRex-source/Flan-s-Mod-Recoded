package com.flansmod.recoded.test

import com.flansmod.recoded.client.fx.ShotEffects
import com.flansmod.recoded.entity.DriveableEntity
import com.flansmod.recoded.gear.GearItem
import com.flansmod.recoded.gear.GearSlots
import com.flansmod.recoded.gear.Parachutes
import com.flansmod.recoded.gun.MagazineContents
import com.flansmod.recoded.gun.Structures
import com.flansmod.recoded.gun.Vehicles
import com.flansmod.recoded.item.StructureItem
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
import net.minecraft.world.phys.Vec3

/**
 * Flying in a real client, by player input only (the pilot's client simulates the flight, the server follows): an
 * airfield from the structure kits, a Spitfire taking off from the runway, climbing and turning with the view, firing
 * its guns and dropping its bomb, the pilot bailing out with a parachute; a Huey climbing with jump and descending with
 * the sprint key; the ZU-23 firing flak; the Stuka's rear gunner. Screenshots in build/run/clientGameTest/screenshots.
 */
class AircraftClientGameTest : FabricClientGameTest {
    private fun id(ns: String, path: String) = Identifier.fromNamespaceAndPath(ns, path)

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamemode survival @a")
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            check(context.client { Vehicles[id("flansww2", "spitfire")] != null }) { "client never received the aircraft definitions" }

            val base = server.compute { it.playerList.players.first().blockPosition() }
            fun spawn(s: MinecraftServer, vid: Identifier, at: Vec3, yaw: Float) = DriveableEntity(s.overworld(), vid, at, yaw).also {
                it.fuel = 30000
                s.overworld().addFreshEntity(it)
            }

            // ---------------------------------------------------------------------------------------- airfield
            // Runway to the south of the player, hangar beside it (open side towards the player), aircraft parked.
            val ground = base.below()
            server.compute { s ->
                fun kit(name: String, at: BlockPos) = StructureItem.place(s.overworld(), id("flansvehicles", name), Structures[id("flansvehicles", name)]!!, at, Direction.SOUTH)
                check(kit("runway", ground)) { "runway template missing" }
                check(kit("hangar", ground.east(18).south(4))) { "hangar template missing" }
            }
            val spitfire = server.compute { s -> spawn(s, id("flansww2", "spitfire"), Vec3(base.x + 0.5, base.y.toDouble(), base.z + 5.5), 0f).id }
            val huey = server.compute { s -> spawn(s, id("flansvehicles", "uh1"), Vec3(base.x + 18.5, base.y.toDouble(), base.z - 9.5), 0f).uuid }
            server.compute { s -> spawn(s, id("flansww2", "ju87"), Vec3(base.x + 18.5, base.y.toDouble(), base.z + 7.5), 180f) }
            server.runCommand("gamemode spectator @a")
            server.runCommand("tp @a ${base.x - 14} ${base.y + 14} ${base.z - 12} facing ${base.x + 10} ${base.y} ${base.z + 16}")
            context.waitTicks(40)
            context.takeScreenshot("flansmod-aircraft-airfield")
            server.runCommand("tp @a ${base.x} ${base.y} ${base.z}")
            server.runCommand("gamemode survival @a")
            context.waitTicks(5)

            // ---------------------------------------------------------------------------------------- take-off
            server.compute { s ->
                val plane = s.overworld().getEntity(spitfire) as DriveableEntity
                plane.setMagazine(0, MagazineContents.full(id("flansww2", "303_wing_belts_300"), id("flansww2", "303")))
                plane.setMagazine(DriveableEntity.SECONDARY, MagazineContents.full(id("flansvehicles", "bomb_rack_1"), id("flansvehicles", "bomb_bomb_he")))
                val player = s.playerList.players.first()
                GearSlots.setBack(player, GearItem.stackFor(id("flansvehicles", "t10_parachute")))
                player.startRiding(plane, true, true)
            }
            context.waitTicks(5)
            check(context.client { it.player!!.vehicle is DriveableEntity }) { "the player should sit in the Spitfire" }
            context.runOnClient<RuntimeException> { mc ->
                mc.options.cameraType = CameraType.THIRD_PERSON_BACK
                mc.player!!.yRot = 0f; mc.player!!.xRot = 5f
            }
            val start = server.compute { (it.overworld().getEntity(spitfire) as DriveableEntity).position() }
            // Full throttle down the runway until the wings carry.
            context.input.holdKey { it.keyUp }
            context.waitFor({ mc -> ((mc.player?.vehicle as? DriveableEntity)?.flight?.velocity?.length() ?: 0.0) > 0.95 }, 300)
            context.takeScreenshot("flansmod-aircraft-takeoff-roll")
            // Pull up: look up and the nose follows.
            context.runOnClient<RuntimeException> { it.player!!.xRot = -25f }
            context.waitTicks(50)
            context.takeScreenshot("flansmod-aircraft-climb")
            val climbed = server.compute { (it.overworld().getEntity(spitfire) as DriveableEntity).y - start.y }
            check(climbed > 6) { "the Spitfire should have taken off and climbed (server saw ${"%.1f".format(climbed)} blocks)" }

            // Turn by looking to the side: the plane banks into the turn and follows.
            val yawBefore = server.compute { it.overworld().getEntity(spitfire)!!.yRot }
            context.runOnClient<RuntimeException> { it.player!!.xRot = -5f }
            repeat(30) {
                context.runOnClient<RuntimeException> { it.player!!.yRot += 3f }
                context.waitTicks(1)
            }
            context.takeScreenshot("flansmod-aircraft-banking")
            context.waitTicks(20)
            val turned = server.compute { kotlin.math.abs(net.minecraft.util.Mth.wrapDegrees(it.overworld().getEntity(spitfire)!!.yRot - yawBefore)) }
            check(turned > 30f) { "the plane should turn after the pilot's view (turned $turned°)" }

            // Guns along the nose, then the bomb (secondary weapon key).
            val shots = context.client { ShotEffects.shotsSeen }
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitFor({ ShotEffects.shotsSeen > shots + 3 }, 40)
            context.takeScreenshot("flansmod-aircraft-guns")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.input.pressKey(InputConstants.KEY_H)
            context.waitTicks(5)
            val bombs = server.compute { (it.overworld().getEntity(spitfire) as DriveableEntity).seatMagazines[DriveableEntity.SECONDARY]?.rounds }
            check(bombs == 0) { "the bomb should have been dropped, rack holds $bombs" }
            context.takeScreenshot("flansmod-aircraft-bomb")
            context.input.releaseKey { it.keyUp }

            // ---------------------------------------------------------------------------------------- bail out
            context.input.holdKey { it.keyShift }
            context.waitTicks(2)
            context.input.releaseKey { it.keyShift }
            context.waitFor({ mc -> mc.player!!.vehicle == null }, 20)
            context.waitFor({ mc -> mc.player!!.fallDistance > 4 }, 60)
            context.input.holdKey { it.keyJump }
            context.waitTicks(3)
            context.input.releaseKey { it.keyJump }
            check(server.compute { Parachutes.openParachute(it.playerList.players.first()) != null }) {
                val state = server.compute { s -> s.playerList.players.first().let { p ->
                    "fall=${p.fallDistance} ground=${p.onGround()} jump=${p.lastClientInput.jump()} vehicle=${p.vehicle} back=${GearSlots.back(p)} y=${p.y}" } }
                "the parachute should open with the jump key ($state)"
            }
            context.waitTicks(10)
            context.takeScreenshot("flansmod-aircraft-parachute")
            val sink = context.client { it.player!!.deltaMovement.y }
            check(sink > -0.3) { "the parachute should slow the fall, sinking $sink blocks per tick" }
            context.waitFor({ mc -> mc.player!!.onGround() }, 1200)
            context.waitTicks(5)
            val health = server.compute { it.playerList.players.first().health }
            check(health >= 20f) { "landing under the parachute should not hurt (health $health)" }
            check(server.compute { Parachutes.openParachute(it.playerList.players.first()) == null }) { "the parachute should close on landing" }
            server.compute { s -> s.overworld().getEntity(spitfire)?.discard() }

            // ---------------------------------------------------------------------------------------- helicopter
            server.runCommand("tp @a ${base.x + 21.5} ${base.y} ${base.z - 9.5}")
            // The flight went far: the airfield's entities load again a little after its chunks (with new entity ids, so
            // the Huey is looked up by its UUID).
            var waited = 0
            while (server.compute { it.overworld().getEntity(huey) } == null) {
                check(waited++ < 200) { "the Huey should still be at the airfield" }
                context.waitTicks(1)
            }
            server.compute { s -> s.playerList.players.first().startRiding(s.overworld().getEntity(huey)!!, true, true) }
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.player!!.yRot = 180f; it.player!!.xRot = 10f }
            val ground0 = server.compute { it.overworld().getEntity(huey)!!.y }
            context.input.holdKey { it.keyJump }
            context.waitTicks(80) // the rotor spools up, then it climbs
            context.input.releaseKey { it.keyJump }
            val climbedHeli = server.compute { it.overworld().getEntity(huey)!!.y } - ground0
            check(climbedHeli > 4) { "the Huey should climb with the jump key (climbed ${"%.1f".format(climbedHeli)})" }
            context.waitTicks(10)
            val hover = server.compute { it.overworld().getEntity(huey)!!.y }
            context.waitTicks(20)
            check(kotlin.math.abs(server.compute { it.overworld().getEntity(huey)!!.y } - hover) < 0.5) { "without input the Huey hovers" }
            context.takeScreenshot("flansmod-aircraft-helicopter")
            context.input.holdKey { it.keySprint }
            context.waitTicks(20)
            context.input.releaseKey { it.keySprint }
            check(server.compute { it.overworld().getEntity(huey)!!.y } < hover - 1) { "the sprint key should make the Huey descend" }
            context.input.holdKey { it.keySprint }
            context.waitFor({ mc -> (mc.player?.vehicle as? DriveableEntity)?.onGround() == true }, 200)
            context.input.releaseKey { it.keySprint }
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // ---------------------------------------------------------------------------------------- rear gunner
            val stuka = server.compute { s ->
                s.overworld().getEntitiesOfClass(DriveableEntity::class.java, net.minecraft.world.phys.AABB(base).inflate(40.0)) { it.vehicleId?.path == "ju87" }.first()
            }.id
            server.compute { s -> s.playerList.players.first().startRiding(s.overworld().getEntity(stuka)!!, true, true) }
            context.waitTicks(5)
            context.input.pressKey(InputConstants.KEY_Y) // from the pilot's seat to the rear gunner's
            context.waitTicks(5)
            check(server.compute { s -> (s.overworld().getEntity(stuka) as DriveableEntity).seatOf(s.playerList.players.first()) } == 1) {
                "Y should move the player to the rear gunner's seat"
            }
            context.runOnClient<RuntimeException> { it.player!!.yRot = 20f; it.player!!.xRot = -15f } // the Stuka faces north: look back
            context.waitTicks(10)
            context.takeScreenshot("flansmod-aircraft-rear-gunner")
            server.compute { s -> s.playerList.players.first().stopRiding() }
            context.waitTicks(5)

            // ---------------------------------------------------------------------------------------- AA gun
            val aa = server.compute { s ->
                spawn(s, id("flansvehicles", "zu23"), Vec3(base.x - 6.5, base.y.toDouble(), base.z - 6.5), 180f).also {
                    it.setMagazine(0, MagazineContents.full(id("flansvehicles", "zu23_box_50"), id("flansvehicles", "23x152_flak")))
                    s.playerList.players.first().startRiding(it, true, true)
                }.id
            }
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON; it.player!!.yRot = 150f; it.player!!.xRot = -40f }
            context.waitTicks(5)
            val aaShots = context.client { ShotEffects.shotsSeen }
            context.input.holdMouse(InputConstants.MOUSE_BUTTON_LEFT)
            context.waitTicks(17) // the first shells burst at the end of their fuse
            context.takeScreenshot("flansmod-aircraft-flak")
            context.input.releaseMouse(InputConstants.MOUSE_BUTTON_LEFT)
            check(context.client { ShotEffects.shotsSeen } > aaShots) { "the AA gun should fire" }
            val left = server.compute { (it.overworld().getEntity(aa) as DriveableEntity).seatMagazines[0]?.rounds ?: 0 }
            check(left < 50) { "the AA gun should use its magazine ($left left)" }
            context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
        }
    }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
