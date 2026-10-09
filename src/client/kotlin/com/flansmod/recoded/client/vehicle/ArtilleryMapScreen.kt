package com.flansmod.recoded.client.vehicle

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.utility.TerrainMap
import com.flansmod.recoded.combat.Artillery
import com.flansmod.recoded.entity.DriveableEntity
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.util.Mth
import net.minecraft.world.level.Level
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Artillery map (map key while manning a mortar): a top-down map of the terrain around the emplacement, north up, with
 * its range rings, the direction it is laid, the predicted impact and the fire mission. Click a point, or type its
 * coordinates, and the gun lays itself there ([DriveableEntity.layTarget], solved with [Artillery.solve]); the laying
 * keys still work and cancel it. The map is drawn once when opened from the chunks the client has loaded.
 */
class ArtilleryMapScreen(private val vehicle: DriveableEntity) : Screen(Component.translatable("gui.flansmod.artillery.title")) {
    private var map: TerrainMap? = null
    private var maxRange = 0.0
    private var minRange = 0.0
    private var target: Vec3? = null
    private var message: Component? = null
    private var mapX = 0
    private var mapY = 0
    private lateinit var xBox: EditBox
    private lateinit var zBox: EditBox

    private val seat get() = minecraft.player?.let(vehicle::seatOf) ?: 0

    override fun isPauseScreen() = false

    override fun init() {
        val level = minecraft.level ?: return
        if (map == null) build(level)
        mapX = (width - SIZE - PANEL) / 2
        mapY = (height - SIZE) / 2
        val px = mapX + SIZE + 10
        xBox = addRenderableWidget(EditBox(font, px, mapY + 34, 46, 18, Component.literal("X")).apply {
            value = (target?.x ?: vehicle.x).toInt().toString()
        })
        zBox = addRenderableWidget(EditBox(font, px + 52, mapY + 34, 46, 18, Component.literal("Z")).apply {
            value = (target?.z ?: vehicle.z).toInt().toString()
        })
        addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.artillery.lay")) {
            val x = xBox.value.toIntOrNull()
            val z = zBox.value.toIntOrNull()
            if (x != null && z != null) aimAt(x, z)
        }.bounds(px, mapY + 58, PANEL - 12, 20).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.done")) { onClose() }.bounds(px, mapY + SIZE - 20, PANEL - 12, 20).build())
    }

    /** Range rings and the terrain image, scaled so the longest range fits. */
    private fun build(level: Level) {
        val round = Artillery.round(vehicle, seat)
        maxRange = round?.let { Artillery.maxRange(level, vehicle, seat, it) } ?: 0.0
        minRange = round?.let { r ->
            vehicle.seat(seat)?.let { s -> Artillery.impact(level, vehicle, seat, vehicle.yRot, s.maxPitch, r)?.subtract(vehicle.position())?.horizontalDistance() }
        } ?: 0.0
        val bpp = ceil(maxOf(maxRange, 48.0) * 2.3 / PIXELS).toInt().coerceAtLeast(1)
        map = TerrainMap(TEXTURE, vehicle.blockPosition(), bpp, PIXELS).also { it.build(level) }
    }

    /** Lays the gun on block column [x]/[z]: solves the fire mission and hands it to the emplacement. */
    fun aimAt(x: Int, z: Int) {
        val level = minecraft.level ?: return
        val at = Vec3(x + 0.5, level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z).toDouble(), z + 0.5)
        target = at
        if (::xBox.isInitialized) {
            xBox.value = x.toString()
            zBox.value = z.toString()
        }
        val round = Artillery.round(vehicle, seat) ?: return run { message = Component.translatable("gui.flansmod.artillery.no_round") }
        val solution = Artillery.solve(level, vehicle, seat, at, round)
        if (solution == null) {
            message = Component.translatable("gui.flansmod.artillery.out_of_range")
            return
        }
        vehicle.layTarget = solution
        message = Component.translatable("gui.flansmod.artillery.laying", "%.1f".format(solution.second), bearing(solution.first))
    }

    private fun bearing(yaw: Float) = "%03.0f".format(Mth.positiveModulo(yaw + 180f, 360f))

    override fun tick() {
        if (minecraft.player?.vehicle != vehicle || vehicle.isRemoved) onClose()
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val at = map?.toWorld(event.x(), event.y(), mapX, mapY, SIZE)
        if (event.button() == 0 && at != null) {
            aimAt(at.first, at.second) // the centre of the clicked map pixel
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    /** World position to screen coordinates on the map. */
    private fun screen(x: Double, z: Double): Pair<Int, Int> = map?.toScreen(x, z, mapX, mapY, SIZE) ?: (0 to 0)

    private fun onMap(sx: Int, sy: Int) = sx in mapX until mapX + SIZE && sy in mapY until mapY + SIZE

    private fun dot(g: GuiGraphicsExtractor, x: Double, z: Double, size: Int, colour: Int) {
        val (sx, sy) = screen(x, z)
        if (onMap(sx, sy)) g.fill(sx - size / 2, sy - size / 2, sx - size / 2 + size, sy - size / 2 + size, colour)
    }

    /** The panel and the terrain image, under the widgets. */
    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        super.extractBackground(graphics, mouseX, mouseY, a)
        graphics.fill(mapX - 2, mapY - 2, mapX + SIZE + PANEL, mapY + SIZE + 2, 0xC0101410.toInt())
        map?.draw(graphics, mapX, mapY, SIZE)
        graphics.outline(mapX - 1, mapY - 1, SIZE + 2, SIZE + 2, 0xFF4A5A40.toInt())
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
        val g = graphics
        g.text(font, "N", mapX + SIZE / 2 - 2, mapY + 2, 0xFFFFFFFF.toInt(), true)

        // Range rings (longest and shortest), the line the gun is laid along, the gun, impact and target.
        val pos = vehicle.position()
        for (k in 0 until 96) {
            val angle = k * Math.PI * 2 / 96
            if (maxRange > 0) dot(g, pos.x + cos(angle) * maxRange, pos.z + sin(angle) * maxRange, 2, 0xFFFFE040.toInt())
            if (minRange > 0) dot(g, pos.x + cos(angle) * minRange, pos.z + sin(angle) * minRange, 2, 0xFFE09030.toInt())
        }
        val facing = Vec3.directionFromRotation(0f, vehicle.getViewYRot(a))
        var d = 4.0
        while (d < maxOf(maxRange, 16.0)) {
            dot(g, pos.x + facing.x * d, pos.z + facing.z * d, 1, 0xFFFFFFFF.toInt())
            d += (map?.blocksPerPixel ?: 1) * 3.0
        }
        dot(g, pos.x, pos.z, 5, 0xFF60A0FF.toInt())
        ArtilleryClient.impact?.let { dot(g, it.x, it.z, 4, 0xFFFFE040.toInt()) }
        target?.let { t ->
            val (sx, sy) = screen(t.x, t.z)
            if (onMap(sx, sy)) {
                g.fill(sx - 4, sy, sx + 5, sy + 1, 0xFFFF3020.toInt())
                g.fill(sx, sy - 4, sx + 1, sy + 5, 0xFFFF3020.toInt())
            }
        }

        // Panel: title, coordinates, the laying and range, the cursor's position.
        val px = mapX + SIZE + 10
        g.text(font, title, px, mapY + 4, 0xFFE8E8D8.toInt(), false)
        g.text(font, Component.translatable("gui.flansmod.artillery.coordinates"), px, mapY + 22, 0xFF9AA090.toInt(), false)
        val lines = listOfNotNull(
            Component.translatable("gui.flansmod.artillery.range", minRange.toInt(), maxRange.toInt()),
            Component.translatable("gui.flansmod.artillery.laid", "%.1f".format(vehicle.layElevation(a)), bearing(vehicle.getViewYRot(a))),
            message,
        )
        lines.forEachIndexed { i, line -> g.text(font, font.plainSubstrByWidth(line.string, PANEL - 12), px, mapY + 86 + i * 11, 0xFFE8E8D8.toInt(), false) }
        map?.toWorld(mouseX.toDouble(), mouseY.toDouble(), mapX, mapY, SIZE)?.let { (wx, wz) ->
            val at = Vec3(wx + 0.5, pos.y, wz + 0.5)
            val text = Component.translatable("gui.flansmod.artillery.cursor", wx, wz, at.subtract(pos).horizontalDistance().toInt(), bearing(Artillery.bearing(pos, at)))
            g.text(font, text, mapX, mapY + SIZE + 5, 0xFFE8E8D8.toInt(), true)
        }
        super.extractRenderState(graphics, mouseX, mouseY, a) // widgets on top
    }

    override fun removed() {
        super.removed()
        map?.release()
    }

    private companion object {
        val TEXTURE = FlansMod.id("artillery_map")
        /** Map resolution (pixels) and size on screen. */
        const val PIXELS = 128
        const val SIZE = 192
        const val PANEL = 120
    }
}
