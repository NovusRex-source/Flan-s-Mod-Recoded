package com.flansmod.recoded.test

import com.flansmod.recoded.gun.Content
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Transform
import com.mojang.blaze3d.platform.InputConstants
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.minecraft.client.CameraType
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
            for (other in probe.guns) {
                world.server.runCommand("item replace entity @a weapon.mainhand with flansmod:gun[flansmod:gun=\"$other\"]")
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
                context.takeScreenshot("probe-gun-${other.substringAfter(':')}")
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
}
