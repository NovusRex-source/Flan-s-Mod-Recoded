package com.flansmod.recoded.client.hud

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.gun.Scope
import com.flansmod.recoded.item.definition
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity

/**
 * Scope view while fully aimed: draws the scope's overlay texture like vanilla's spyglass (square texture,
 * black bars at the sides) and provides the thermal highlight used by the glowing mixin.
 */
object ScopeOverlay {
    /** Aim progress above which the scope view replaces the gun view. */
    const val THRESHOLD = 0.9f

    /** The scope the local player is looking through right now, if any. */
    val activeScope: Scope?
        get() {
            if (!Minecraft.getInstance().options.cameraType.isFirstPerson || GunInput.aimProgress < THRESHOLD) return null
            return GunInput.aimedGun?.scope
        }

    /** Thermal optics: living entities in range glow (vanilla outline) while aiming. */
    fun highlights(entity: Entity): Boolean {
        val scope = activeScope ?: return false
        val player = Minecraft.getInstance().player ?: return false
        return scope.thermal && entity is LivingEntity && entity != player && entity.distanceToSqr(player) <= scope.thermalRange * scope.thermalRange
    }

    fun init() {
        HudElementRegistry.attachElementBefore(VanillaHudElements.CROSSHAIR, FlansMod.id("scope")) { graphics, _ ->
            val overlay = activeScope?.overlay ?: return@attachElementBefore
            val w = graphics.guiWidth()
            val h = graphics.guiHeight()
            val size = minOf(w, h)
            val x = (w - size) / 2
            val y = (h - size) / 2
            graphics.blit(RenderPipelines.GUI_TEXTURED, overlay, x, y, 0f, 0f, size, size, size, size)
            val black = 0xFF000000.toInt()
            graphics.fill(RenderPipelines.GUI, 0, 0, w, y, black)
            graphics.fill(RenderPipelines.GUI, 0, y + size, w, h, black)
            graphics.fill(RenderPipelines.GUI, 0, y, x, y + size, black)
            graphics.fill(RenderPipelines.GUI, x + size, y, w, y + size, black)
        }
    }
}
