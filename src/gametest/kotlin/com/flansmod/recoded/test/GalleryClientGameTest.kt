package com.flansmod.recoded.test

import com.flansmod.recoded.gun.AmmoTypes
import com.flansmod.recoded.gun.Guns
import com.flansmod.recoded.gun.Magazines
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import net.minecraft.server.MinecraftServer

/**
 * A gallery of every gun, magazine and round of the built-in packs, shown big on item displays (the `fixed` display
 * context, a side view) with their names, for judging the models. Screenshots `flansmod-gallery-*` in
 * build/run/clientGameTest/screenshots.
 */
class GalleryClientGameTest : FabricClientGameTest {
    private val packs = listOf("flansbasic", "flansww2")

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            server.runCommand("time set noon")
            server.runCommand("weather clear")
            server.runCommand("gamerule advance_time false")
            server.runCommand("gamemode spectator @a") // no gravity: the camera stays where it is put
            context.waitTicks(20)
            world.connection.waitForClientboundPackets()
            val base = server.compute { it.playerList.players.first().position() }
            context.runOnClient<RuntimeException> { mc -> if (!mc.gui.hud.isHidden) mc.gui.hud.toggle() }

            for (pack in packs) {
                val guns = context.client { Guns.all.filter { (id, g) -> id.namespace == pack && !g.mounted }.entries.sortedBy { it.value.name }.map { it.key to it.value.name } }
                // Guns are shown loaded (like creative stacks), so their magazine is visible.
                val loaded = context.client { guns.associate { (id, _) ->
                    id to com.flansmod.recoded.item.GunItem.stackFor(id).get(com.flansmod.recoded.registry.FlansComponents.MAGAZINE)?.let { m ->
                        ",flansmod:magazine={magazine:\"${m.magazine}\",ammo:\"${m.ammo}\",rounds:${m.rounds}}"
                    }.orEmpty()
                } }
                val mags = context.client { Magazines.all.filter { (id, m) -> id.namespace == pack && !m.internal }.entries.sortedBy { it.value.caliber }.map { it.key to it.value.name } }
                val rounds = context.client { AmmoTypes.all.filter { (id, _) -> id.namespace == pack }.entries.sortedBy { it.value.caliber }.map { it.key to it.value.name } }
                gallery(context, server, base, "$pack-guns", guns, perRow = 3, rows = 2, scale = 5f) { "flansmod:gun[flansmod:gun=\"$it\"${loaded[it]}]" }
                gallery(context, server, base, "$pack-magazines", mags, perRow = 5, rows = 3, scale = 3.2f) { "flansmod:magazine[flansmod:magazine={magazine:\"$it\"}]" }
                gallery(context, server, base, "$pack-rounds", rounds, perRow = 8, rows = 3, scale = 2f) { "flansmod:ammo[flansmod:ammo_type=\"$it\"]" }
            }
            uniforms(context, server, base)
            context.runOnClient<RuntimeException> { mc -> if (mc.gui.hud.isHidden) mc.gui.hud.toggle() }
        }
    }

    /** Every clothing set (equipment asset) on an armour stand, front and back. */
    private fun uniforms(context: ClientGameTestContext, server: TestServerContext, base: net.minecraft.world.phys.Vec3) {
        server.runCommand("kill @e[type=minecraft:item_display]")
        server.runCommand("kill @e[type=minecraft:text_display]")
        val sets = context.client { com.flansmod.recoded.gun.Clothing.all.entries.groupBy { it.value.asset }.toSortedMap(compareBy { it.toString() }) }
        val z = base.z + 6
        sets.entries.forEachIndexed { i, (asset, pieces) ->
            val x = base.x + (i - (sets.size - 1) / 2.0) * 1.6
            val equipment = pieces.joinToString(",") { (id, c) ->
                "${c.slot}:{id:\"flansmod:clothing\",count:1,components:{\"flansmod:clothing\":\"$id\",\"minecraft:equippable\":{slot:\"${c.slot}\",asset_id:\"$asset\"}}}"
            }
            server.runCommand("summon minecraft:armor_stand $x ${base.y + 1} $z {equipment:{$equipment},Rotation:[180f,0f],ShowArms:1b,NoGravity:1b}")
            server.runCommand("summon minecraft:text_display $x ${base.y + 3.2} $z {text:\"${asset.path}\",Rotation:[180f,0f],background:0}")
        }
        val width = sets.size * 1.6
        server.runCommand("tp @a ${base.x} ${base.y + 0.4} ${z - maxOf(4.0, width * 0.55)} 0 5")
        context.waitTicks(25)
        context.takeScreenshot("flansmod-gallery-uniforms-front")
        server.runCommand("tp @a ${base.x} ${base.y + 0.4} ${z + maxOf(4.0, width * 0.55)} 180 5")
        context.waitTicks(25)
        context.takeScreenshot("flansmod-gallery-uniforms-back")
    }

    /** Pages of [perRow] × [rows] items on a wall of item displays, one screenshot per page. */
    private fun gallery(
        context: ClientGameTestContext, server: TestServerContext, base: net.minecraft.world.phys.Vec3, name: String,
        items: List<Pair<Identifier, String>>, perRow: Int, rows: Int, scale: Float, item: (Identifier) -> String,
    ) {
        val spacing = scale * 1.1
        items.chunked(perRow * rows).forEachIndexed { page, chunk ->
            server.runCommand("kill @e[type=minecraft:item_display]")
            server.runCommand("kill @e[type=minecraft:text_display]")
            chunk.forEachIndexed { i, (id, label) ->
                val x = base.x + (i % perRow - (perRow - 1) / 2.0) * spacing
                val y = base.y + 2 + (rows - 1 - i / perRow) * spacing * 0.8
                val z = base.z + 12
                // Rotation 180: the display's front (the item's side view) faces the camera at the origin.
                server.runCommand("summon minecraft:item_display $x $y $z {item:{id:\"${item(id).substringBefore('[')}\",count:1,components:{${components(item(id))}}}," +
                    "item_display:\"fixed\",Rotation:[180f,0f],transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[${scale}f,${scale}f,${scale}f]}}")
                server.runCommand("summon minecraft:text_display $x ${y - spacing * 0.38} $z {text:\"${label.replace("\"", "'")}\",Rotation:[180f,0f],background:0,transformation:{left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],translation:[0f,0f,0f],scale:[0.8f,0.8f,0.8f]}}")
            }
            // Camera on the wall's centre, far enough for a 70° vertical / ~100° horizontal field of view.
            val height = (rows - 1) * spacing * 0.8 + spacing
            val width = perRow * spacing
            val distance = maxOf(height * 0.75, width * 0.42) + 1
            val centre = base.y + 2 + (rows - 1) * spacing * 0.4
            server.runCommand("tp @a ${base.x} ${centre - 1.62} ${base.z + 12 - distance} 0 0")
            context.waitTicks(25)
            context.takeScreenshot("flansmod-gallery-$name-$page")
        }
    }

    /** `flansmod:gun[a=b,c=d]` → `"a":b,"c":d` for the summon command's item components. */
    private fun components(item: String): String = item.substringAfter('[').removeSuffix("]")
        .split(Regex(",(?![^{]*})")).joinToString(",") { c -> val (k, v) = c.split("=", limit = 2); "\"$k\":$v" }

    private fun <T> ClientGameTestContext.client(f: (Minecraft) -> T): T = computeOnClient<T, RuntimeException>(f)
    private fun <T> TestServerContext.compute(f: (MinecraftServer) -> T): T = computeOnServer<T, RuntimeException>(f)
}
