package com.flansmod.recoded.test

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Transform
import com.mojang.blaze3d.platform.InputConstants
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.CameraType
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.resources.Identifier
import java.io.File
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.gun.MagazineContents

/**
 * Dev-only visual probe (not part of the normal test run): renders display-transform variants and/or several guns
 * from the JSON file named by the FLANS_PROBE environment variable, one screenshot each. Run it by pointing the
 * `fabric-client-gametest` entrypoint at this class temporarily.
 */
class ProbeTest : FabricClientGameTest {
    @Serializable
    data class Probe(val gun: String, val view: String, val variants: Map<String, Map<String, Map<String, Transform>>>, val give: String = "", val guns: List<String> = emptyList())

    override fun runTest(context: ClientGameTestContext) {
        val probe = Content.JSON.decodeFromString(Probe.serializer(), File(System.getenv("FLANS_PROBE") ?: error("set FLANS_PROBE to a probe.json")).readText())
        val gun = Identifier.parse(probe.gun)
        context.worldBuilder().create().use { world ->
            world.server.runCommand("time set noon")
            world.server.runCommand("give @a flansmod:gun[flansmod:gun=\"$gun\"${probe.give}]")
            world.server.runCommand("execute at @a run summon minecraft:husk ^ ^ ^6 {NoAI:1b}")
            context.waitTicks(30)
            // Optional: one screenshot per gun (each gun's own model), e.g. to compare generated variants.
            for (entry in probe.guns) {
                // An entry may carry extra components: `ns:gun|flansmod:attachments={sight:"ns:red_dot"}`.
                val other = entry.substringBefore('|')
                val extra = entry.substringAfter('|', "").let { if (it.isEmpty()) "" else ",$it" }
                world.server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"$other\"$extra]")
                if (probe.view == "reload") {
                    // A full spare magazine in the inventory, then R; screenshots at 20/42/62% of the animation.
                    val (full, ticks) = context.computeOnClient<Pair<MagazineContents, Int>, RuntimeException> {
                        val id = Identifier.parse(other)
                        MagazineContents.full(GunItem.acceptedMagazines(id).first())!! to Guns[id]!!.reloadTicks
                    }
                    world.server.runCommand("give @a flansmod:magazine[flansmod:magazine={magazine:\"${full.magazine}\",ammo:\"${full.ammo}\",rounds:${full.rounds}}]")
                    context.waitTicks(15)
                    context.input.pressKey(InputConstants.KEY_R)
                    var waited = 0
                    for (percent in listOf(20, 42, 62)) {
                        val target = ticks * percent / 100
                        context.waitTicks((target - waited).coerceAtLeast(1))
                        waited = target
                        context.takeScreenshot("probe-reload-${other.substringAfter(':')}-$percent")
                    }
                    context.waitTicks(ticks)
                    continue
                }
                context.waitTicks(15)
                if (probe.view == "orbit") {
                    orbit(context, world.server, other.substringAfter(':'))
                    continue
                }
                when (probe.view) {
                    "ads" -> {
                        context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
                        context.waitTicks(12)
                    }
                    "side" -> {
                        context.input.pressKey { it.keyInventory }
                        context.waitTicks(2)
                        context.input.setCursorPos(0.0, 150.0)
                        context.waitTicks(5)
                    }
                    "third" -> {
                        context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
                        context.waitTicks(5)
                    }
                }
                context.takeScreenshot("probe-gun-${other.substringAfter(':')}${if (extra.isEmpty()) "" else "-" + extra.substringAfterLast(':').filter { it.isLetterOrDigit() || it == '_' }}")
                when (probe.view) {
                    "ads" -> {
                        context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)
                        context.waitTicks(5)
                    }
                    "side" -> context.input.pressKey(InputConstants.KEY_ESCAPE)
                    "third" -> context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
                }
            }
            for ((name, display) in probe.variants) {
                context.runOnClient<RuntimeException> {
                    val d = Guns[gun]!!
                    Guns.replace(Guns.all + (gun to d.copy(display = d.display + display["display"].orEmpty())))
                }
                when (probe.view) {
                    "side" -> {
                        context.input.pressKey { it.keyInventory }
                        context.waitTicks(2)
                        context.input.setCursorPos(0.0, 150.0)
                    }
                    "ads" -> context.input.holdMouse(InputConstants.MOUSE_BUTTON_RIGHT)
                    "third" -> context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.THIRD_PERSON_FRONT }
                }
                context.waitTicks(12)
                context.takeScreenshot("probe-$name")
                when (probe.view) {
                    "side" -> context.input.pressKey(InputConstants.KEY_ESCAPE)
                    "ads" -> context.input.releaseMouse(InputConstants.MOUSE_BUTTON_RIGHT)
                    "third" -> context.runOnClient<RuntimeException> { it.options.cameraType = CameraType.FIRST_PERSON }
                }
                context.waitTicks(3)
            }
        }
    }

    /**
     * Third-person pose from four sides. The local player is only ever drawn as the camera entity, so a client-only
     * dummy player holding the same item stands 3 blocks south (facing south) and the real player (HUD hidden, like F1)
     * is teleported around it: right side, left side, front quarter and from above.
     */
    private fun orbit(context: ClientGameTestContext, server: TestServerContext, name: String) {
        val at = context.computeOnClient<net.minecraft.world.phys.Vec3, RuntimeException> { mc ->
            val player = mc.player!!
            val dummy = net.minecraft.client.player.RemotePlayer(mc.level!!, com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "Probe"))
            dummy.id = -4242
            val pos = player.position().add(0.0, 0.0, 3.0)
            dummy.snapTo(pos.x, pos.y, pos.z, 0f, 0f)
            dummy.yHeadRot = 0f; dummy.yBodyRot = 0f
            dummy.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, player.mainHandItem.copy())
            mc.level!!.addEntity(dummy)
            if (!mc.gui.hud.isHidden) mc.gui.hud.toggle()
            pos
        }
        // Offsets from the dummy: x = west/east (its right is west), y up, z south.
        val views = mapOf("right" to Triple(-3.2, 1.3, 0.0), "left" to Triple(3.2, 1.3, 0.0), "front" to Triple(-1.8, 1.6, 2.8), "top" to Triple(0.0, 3.5, 0.6))
        for ((view, o) in views) {
            server.runCommand("tp @a ${at.x + o.first} ${at.y + o.second} ${at.z + o.third} facing ${at.x} ${at.y + 1.2} ${at.z}")
            context.waitTicks(5)
            context.takeScreenshot("probe-$name-$view")
        }
        context.runOnClient<RuntimeException> { mc ->
            mc.level!!.removeEntity(-4242, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED)
            if (mc.gui.hud.isHidden) mc.gui.hud.toggle()
        }
        server.runCommand("tp @a ${at.x} ${at.y} ${at.z - 3} 0 0")
    }
}
