package com.flansmod.recoded.client.utility

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gear.GearType
import com.flansmod.recoded.gear.gear
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3

/**
 * Compass HUD (Fabric HUD API): while a compass is held in either hand, a heading strip at the top of the screen shows
 * the cardinal directions and 15° ticks around where you look, the heading in degrees, and the field map's waypoint
 * (marker and distance) when it is in front of you.
 */
object CompassHud {
    private const val WIDTH = 180
    /** Degrees shown across the strip. */
    private const val SPAN = 120f
    private val LABELS = mapOf(0 to "N", 45 to "NE", 90 to "E", 135 to "SE", 180 to "S", 225 to "SW", 270 to "W", 315 to "NW")

    fun init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, FlansMod.id("compass")) { g, delta ->
            val mc = Minecraft.getInstance()
            val player = mc.player ?: return@attachElementAfter
            if (listOf(player.mainHandItem, player.offhandItem).none { it.gear?.type == GearType.COMPASS }) return@attachElementAfter
            draw(g, mc, player.getViewYRot(delta.getGameTimeDeltaPartialTick(false)))
        }
    }

    private fun draw(g: GuiGraphicsExtractor, mc: Minecraft, yaw: Float) {
        val font = mc.font
        val heading = Mth.positiveModulo(yaw + 180f, 360f) // 0 = north
        val x0 = (g.guiWidth() - WIDTH) / 2
        val y = 4
        g.fill(x0, y, x0 + WIDTH, y + 20, 0x90101410.toInt())
        fun screenX(bearing: Float): Int? {
            val rel = Mth.wrapDegrees(bearing - heading)
            return if (kotlin.math.abs(rel) > SPAN / 2) null else (x0 + WIDTH / 2 + rel / SPAN * WIDTH).toInt()
        }
        for (b in 0 until 360 step 15) {
            val sx = screenX(b.toFloat()) ?: continue
            val label = LABELS[b]
            if (label != null) g.text(font, label, sx - font.width(label) / 2, y + 3, if (b == 0) 0xFFFF5050.toInt() else 0xFFFFFFFF.toInt(), true)
            else g.fill(sx, y + 4, sx + 1, y + (if (b % 45 == 0) 12 else 8), 0xFFB0B0B0.toInt())
        }
        // Centre needle and the heading.
        g.fill(x0 + WIDTH / 2, y, x0 + WIDTH / 2 + 1, y + 20, 0xFFFFE040.toInt())
        val text = "%03.0f°".format(heading)
        g.text(font, text, x0 + (WIDTH - font.width(text)) / 2, y + 22, 0xFFFFE040.toInt(), true)
        // Waypoint from the field map.
        val player = mc.player ?: return
        UtilityClient.waypoint?.let { w ->
            val to = Vec3.atBottomCenterOf(w)
            val bearing = Mth.positiveModulo(FieldMapScreen.yawTo(player.position(), to) + 180f, 360f)
            val distance = "${to.subtract(player.position()).horizontalDistance().toInt()} m"
            val sx = screenX(bearing)
            if (sx != null) {
                g.fill(sx - 2, y + 13, sx + 3, y + 18, 0xFFE02020.toInt())
                g.text(font, distance, sx - font.width(distance) / 2, y + 32, 0xFFE06060.toInt(), true)
            } else {
                // Behind you: an arrow at the edge it is closer to.
                val left = Mth.wrapDegrees(bearing - heading) < 0
                val arrow = if (left) "◀ $distance" else "$distance ▶"
                g.text(font, arrow, if (left) x0 + 2 else x0 + WIDTH - font.width(arrow) - 2, y + 22, 0xFFE06060.toInt(), true)
            }
        }
    }
}
