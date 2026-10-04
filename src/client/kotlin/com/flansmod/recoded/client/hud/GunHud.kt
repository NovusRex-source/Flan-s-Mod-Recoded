package com.flansmod.recoded.client.hud

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.item.ammo
import com.flansmod.recoded.item.definition
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import com.flansmod.recoded.network.HitPayload
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.sounds.SoundEvents

/** Ammo counter next to the hotbar, and crosshair hiding while aiming. Uses Fabric's HUD API, no mixins. */
object GunHud {
    private const val MARKER_TICKS = 8
    private var markerTicks = 0
    private var markerColor = 0

    /** Shows a hit marker; called when the server reports a hit. */
    fun onHit(hit: HitPayload) {
        hitsReceived++
        markerTicks = MARKER_TICKS
        markerColor = when {
            hit.kill -> 0xFFFF3030.toInt()
            hit.headshot -> 0xFFFFC030.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        Minecraft.getInstance().player?.playSound(SoundEvents.ARROW_HIT_PLAYER, 0.4f, if (hit.headshot) 1.6f else 1.2f)
    }

    /** Total hits reported by the server this session (used by tests). */
    var hitsReceived = 0
        private set

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { if (markerTicks > 0) markerTicks-- }

        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, FlansMod.id("hit_marker")) { graphics, _ ->
            if (markerTicks <= 0) return@attachElementAfter
            val cx = graphics.guiWidth() / 2
            val cy = graphics.guiHeight() / 2
            // Four short diagonal strokes around the crosshair.
            for (i in 3..5) for ((dx, dy) in listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)) {
                val x = cx + dx * i
                val y = cy + dy * i
                graphics.fill(x, y, x + 1, y + 1, markerColor)
            }
        }

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
