package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gamemode.BattleStatus
import com.flansmod.recoded.gamemode.BattleStatusPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Battle HUD (top left): battle name and time left, every team's score (friends marked), your battle money.
 * Fed by [BattleStatusPayload] about once a second; disappears when no status arrives any more.
 */
object BattleHud {
    var status: BattleStatus? = null
        private set
    private var lastUpdate = 0L

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(BattleStatusPayload.TYPE) { payload, _ ->
            status = payload.status
            lastUpdate = System.currentTimeMillis()
        }
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, FlansMod.id("battle")) { g, _ ->
            val s = status ?: return@attachElementAfter
            if (s.running && System.currentTimeMillis() - lastUpdate > 5000) return@attachElementAfter
            val font = Minecraft.getInstance().font
            val lines = buildList {
                val time = if (s.secondsLeft >= 0) "  ${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}" else ""
                add(Component.literal(s.name + time) to 0xFFFFD27F.toInt())
                if (!s.running) add(Component.translatable("gui.flansmod.battle.lobby") to 0xFFAAAAAA.toInt())
                for (t in s.teams) {
                    val tag = if (t.name == s.team) " ◀" else if (t.friendly) " ✚" else ""
                    val limit = if (s.scoreLimit > 0) "/${s.scoreLimit}" else ""
                    add(Component.literal("${t.name}  ${t.score}$limit$tag") to (0xFF000000.toInt() or t.rgb))
                }
                if (s.inBattle) add(Component.translatable("gui.flansmod.flag.money", s.money) to 0xFFFFE070.toInt())
            }
            val width = lines.maxOf { font.width(it.first) } + 8
            g.fill(2, 2, 2 + width, 4 + lines.size * 10, 0x90000000.toInt())
            lines.forEachIndexed { i, (text, color) -> g.text(font, text, 6, 4 + i * 10, color) }
        }
    }
}
