package com.flansmod.recoded.client.utility

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gear.GearDefinition
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil

/**
 * Field map (right click with a map item): the terrain [GearDefinition.range] blocks around you from the loaded chunks,
 * north up, your position and heading, allied players (same team; nobody else, so it is no radar) and the waypoint.
 * Left click sets the waypoint (also shown on the compass), right click clears it.
 */
class FieldMapScreen(private val def: GearDefinition) : Screen(Component.translatable("gui.flansmod.map.title")) {
    private var map: TerrainMap? = null
    private var mapX = 0
    private var mapY = 0

    override fun isPauseScreen() = false

    override fun init() {
        val player = minecraft.player ?: return
        if (map == null) {
            val bpp = ceil(def.range * 2 / PIXELS).toInt().coerceAtLeast(1)
            map = TerrainMap(TEXTURE, player.blockPosition(), bpp, PIXELS).also { m -> minecraft.level?.let(m::build) }
        }
        mapX = (width - SIZE) / 2
        mapY = (height - SIZE) / 2 - 6
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val at = map?.toWorld(event.x(), event.y(), mapX, mapY, SIZE) ?: return super.mouseClicked(event, doubleClick)
        when (event.button()) {
            0 -> {
                val level = minecraft.level ?: return true
                UtilityClient.waypoint = BlockPos(at.first, level.getHeight(Heightmap.Types.WORLD_SURFACE, at.first, at.second), at.second)
                minecraft.player?.playSound(net.minecraft.sounds.SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.6f, 1.2f)
            }
            1 -> UtilityClient.waypoint = null
        }
        return true
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.fill(mapX - 6, mapY - 18, mapX + SIZE + 6, mapY + SIZE + 30, 0xE0C8B88A.toInt()) // paper
        graphics.outline(mapX - 6, mapY - 18, SIZE + 12, SIZE + 48, 0xFF5A4A30.toInt())
        map?.draw(graphics, mapX, mapY, SIZE)
        graphics.outline(mapX - 1, mapY - 1, SIZE + 2, SIZE + 2, 0xFF5A4A30.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val g = graphics
        val map = map ?: return
        val player = minecraft.player ?: return
        g.text(font, title, mapX + (SIZE - font.width(title)) / 2, mapY - 13, 0xFF3A2E1C.toInt(), false)
        g.text(font, "N", mapX + SIZE / 2 - 2, mapY + 2, 0xFFFFFFFF.toInt(), true)
        fun mark(x: Double, z: Double, size: Int, colour: Int) {
            val (sx, sy) = map.toScreen(x, z, mapX, mapY, SIZE)
            if (sx in mapX until mapX + SIZE && sy in mapY until mapY + SIZE) g.fill(sx - size / 2, sy - size / 2, sx - size / 2 + size, sy - size / 2 + size, colour)
        }
        // Allies (same team) only.
        minecraft.level?.players()?.filter { it != player && it.isAlliedTo(player) }?.forEach { mark(it.x, it.z, 4, 0xFF40C040.toInt()) }
        UtilityClient.waypoint?.let { w ->
            val (sx, sy) = map.toScreen(w.x + 0.5, w.z + 0.5, mapX, mapY, SIZE)
            g.fill(sx - 4, sy, sx + 5, sy + 1, 0xFFE02020.toInt())
            g.fill(sx, sy - 4, sx + 1, sy + 5, 0xFFE02020.toInt())
        }
        // You: a dot and a short line in the direction you face.
        val facing = Vec3.directionFromRotation(0f, player.getViewYRot(a))
        for (k in 1..4) mark(player.x + facing.x * k * map.blocksPerPixel * 1.5, player.z + facing.z * k * map.blocksPerPixel * 1.5, 2, 0xFFFFFFFF.toInt())
        mark(player.x, player.z, 5, 0xFF3060E0.toInt())

        // Below the map: your position, the waypoint, the cursor, how to use it.
        val lines = buildList {
            add(Component.translatable("gui.flansmod.map.position", player.blockX, player.blockY, player.blockZ, bearing(player.getViewYRot(a))))
            UtilityClient.waypoint?.let { w ->
                val to = Vec3.atBottomCenterOf(w)
                add(Component.translatable("gui.flansmod.map.waypoint", w.x, w.z, to.subtract(player.position()).horizontalDistance().toInt(), bearing(yawTo(player.position(), to))))
            }
            map.toWorld(mouseX.toDouble(), mouseY.toDouble(), mapX, mapY, SIZE)?.let { (x, z) ->
                add(Component.translatable("gui.flansmod.map.cursor", x, z, Vec3(x + 0.5, player.y, z + 0.5).subtract(player.position()).horizontalDistance().toInt()))
            } ?: add(Component.translatable("gui.flansmod.map.hint"))
        }
        lines.take(3).forEachIndexed { i, line -> g.text(font, line, mapX, mapY + SIZE + 3 + i * 9, 0xFF3A2E1C.toInt(), false) }
        super.extractRenderState(graphics, mouseX, mouseY, a)
    }

    override fun removed() {
        super.removed()
        map?.release()
    }

    companion object {
        private val TEXTURE = FlansMod.id("field_map")
        private const val PIXELS = 128
        private const val SIZE = 192

        /** Compass bearing (0 = north, clockwise) of a vanilla yaw. */
        fun bearing(yaw: Float) = "%03.0f".format(Mth.positiveModulo(yaw + 180f, 360f))

        /** Vanilla yaw from [from] towards [to]. */
        fun yawTo(from: Vec3, to: Vec3) = (Mth.atan2(-(to.x - from.x), to.z - from.z) * Mth.RAD_TO_DEG).toFloat()
    }
}
