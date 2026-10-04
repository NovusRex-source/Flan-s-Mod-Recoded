package com.flansmod.recoded.client.hud

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.definition
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft

/** Ammo counter next to the hotbar, and crosshair hiding while aiming. Uses Fabric's HUD API, no mixins. */
object GunHud {
    fun init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, FlansMod.id("ammo")) { graphics, _ ->
            val mc = Minecraft.getInstance()
            val stack = mc.player?.mainHandItem ?: return@attachElementAfter
            val gun = stack.definition ?: return@attachElementAfter
            if (!FlansConfig.get.showAmmoHud || mc.gui.hud.isHidden) return@attachElementAfter

            val text = "${stack.ammo} / ${gun.magazine}"
            val color = if (stack.ammo == 0) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
            val x = graphics.guiWidth() / 2 + 91 + 8
            val y = graphics.guiHeight() - 15
            graphics.text(mc.font, text, x, y, color)
        }

        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR) { vanilla ->
            HudElement { graphics, delta ->
                if (!(GunInput.aiming && FlansConfig.get.hideCrosshairWhileAiming)) vanilla.extractRenderState(graphics, delta)
            }
        }
    }
}
