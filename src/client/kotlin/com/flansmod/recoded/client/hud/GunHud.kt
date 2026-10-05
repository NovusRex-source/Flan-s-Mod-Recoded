package com.flansmod.recoded.client.hud

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.client.config.FlansConfig
import com.flansmod.recoded.client.input.GunInput
import com.flansmod.recoded.item.AmmoItem
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.item.fireMode
import com.flansmod.recoded.gun.FireMode
import net.minecraft.network.chat.Component
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
            stack.definition ?: return@attachElementAfter
            if (!FlansConfig.get.showAmmoHud || mc.gui.hud.isHidden) return@attachElementAfter

            val x = graphics.guiWidth() / 2 + 91 + 8
            val y = graphics.guiHeight() - 15
            val mag = stack.loadedMagazine
            if (mag == null) {
                graphics.text(mc.font, Component.translatable("hud.flansmod.no_magazine").string, x, y, 0xFFFF5555.toInt())
                return@attachElementAfter
            }
            val color = if (mag.isEmpty) 0xFFFF5555.toInt() else 0xFFFFFFFF.toInt()
            graphics.text(mc.font, "${mag.rounds} / ${mag.capacity}", x, y, color)
            val mode = stack.fireMode
            val modeText = Component.translatable("item.flansmod.gun.mode.${mode.name.lowercase()}").string.uppercase()
            graphics.text(mc.font, "[$modeText]", x + mc.font.width("${mag.rounds} / ${mag.capacity} "), y, if (mode == FireMode.SAFE) 0xFF55FF55.toInt() else 0xFFFFCC55.toInt())
            mag.ammo?.let { graphics.text(mc.font, AmmoItem.displayName(it).string, x, y - 10, 0xFFAAAAAA.toInt()) }
        }

        ScopeOverlay.init()

        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR) { vanilla ->
            HudElement { graphics, delta ->
                if (!(GunInput.aiming && FlansConfig.get.hideCrosshairWhileAiming)) vanilla.extractRenderState(graphics, delta)
            }
        }
    }
}
