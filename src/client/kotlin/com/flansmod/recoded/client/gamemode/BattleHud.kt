package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.BattleStatus
import com.flansmod.recoded.gamemode.BattleStatusPayload
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component

/**
 * Battle HUD. Top left: battle name and time left (or the countdown), every team's score (yours marked, friends ✚,
 * teams without a spawn ✖), the flag posts with owner and capture progress, your battle money. Top centre: what
 * to do right now (carrying a flag, waiting for a spawn, spectating). Fed by [BattleStatusPayload] about once a
 * second; disappears when no status arrives any more.
 */
object BattleHud {
    var status: BattleStatus? = null
        private set
    private var lastUpdate = 0L

    /** The status, unless it went stale (no update for a while during a running battle). */
    val current: BattleStatus? get() = status?.takeIf { !it.running || System.currentTimeMillis() - lastUpdate <= 5000 }

    fun teamRgb(s: BattleStatus, team: String?) = s.teams.firstOrNull { it.name == team }?.rgb ?: 0xFFFFFF

    fun init() {
        ClientPlayNetworking.registerGlobalReceiver(BattleStatusPayload.TYPE) { payload, _ ->
            status = payload.status
            lastUpdate = System.currentTimeMillis()
        }
        // A battle belongs to its server/world: forget it when leaving (it showed up in the next world otherwise).
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> status = null }
        HudElementRegistry.attachElementAfter(VanillaHudElements.BOSS_BAR, FlansMod.id("battle")) { g, _ ->
            val s = current ?: return@attachElementAfter
            val mc = Minecraft.getInstance()
            if (mc.gui.screen() is BattleMenuScreen || mc.gui.screen() is com.flansmod.recoded.client.trenches.TrenchCommandScreen) return@attachElementAfter
            val font = mc.font
            val lines = buildList {
                val time = when {
                    s.countdown > 0 -> "  ▶ ${s.countdown}"
                    s.secondsLeft >= 0 -> "  ${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}"
                    else -> ""
                }
                add(Component.literal(s.name + time) to 0xFFFFD27F.toInt())
                if (!s.running) add(Component.translatable("gui.flansmod.battle.lobby") to 0xFFAAAAAA.toInt())
                for (t in s.teams) {
                    val tag = if (t.name == s.team) " ◀" else if (t.friendly) " ✚" else ""
                    val spawn = if (s.running && !t.hasSpawn) " ✖" else ""
                    val limit = if (s.scoreLimit > 0) "/${s.scoreLimit}" else ""
                    add(Component.literal("${t.name}  ${t.score}$limit$tag$spawn") to (0xFF000000.toInt() or t.rgb))
                }
                val trench = s.trench
                if (trench != null) {
                    // The lane in one line, your headquarters on the left; then funds and soldiers.
                    val zones = if (trench.side == 1) trench.zones.reversed() else trench.zones
                    val lane = Component.empty()
                    for (z in zones) lane.append(Component.literal(if (z.base) "■ " else if (z.bunker) "▣ " else "▮ ")
                        .withColor(if (z.owner >= 0) teamRgb(s, trench.teams[z.owner]) else 0x909090))
                    add(lane to 0xFFFFFFFF.toInt())
                    val side = trench.side.coerceAtLeast(0)
                    add(Component.translatable("gui.flansmod.trenches.hud_funds", trench.funds.getOrElse(side) { 0 }, trench.income.getOrElse(side) { 0 },
                        trench.soldiers.getOrElse(side) { 0 }) to 0xFFFFE070.toInt())
                    trench.hq.forEachIndexed { i, p ->
                        if (p > 0) add(Component.translatable("gui.flansmod.trenches.hud_hq", trench.teams[i], (p * 100).toInt()) to 0xFFFF7070.toInt())
                    }
                } else if (s.running && s.mode != BattleMode.TEAM_DEATHMATCH) for (post in s.posts.filter { s.mode != BattleMode.KING_OF_THE_HILL || it.hill }) {
                    add(postLine(s, post) to (0xFF000000.toInt() or teamRgb(s, post.team)))
                }
                if (s.inBattle) add(Component.translatable("gui.flansmod.flag.money", s.money) to 0xFFFFE070.toInt())
            }
            val width = lines.maxOf { font.width(it.first) } + 8
            g.fill(2, 2, 2 + width, 4 + lines.size * 10, 0x90000000.toInt())
            lines.forEachIndexed { i, (text, color) -> g.text(font, text, 6, 4 + i * 10, color) }

            val banner = when {
                s.carrying != null -> Component.translatable("gui.flansmod.battle.carrying", s.carrying!!) to 0xFFFFD27F.toInt()
                s.waiting -> Component.translatable("gui.flansmod.battle.waiting") to 0xFFFF7070.toInt()
                s.spectating && s.trench != null -> Component.translatable("gui.flansmod.trenches.spectating_hint") to 0xFFAAAAAA.toInt()
                s.spectating -> Component.translatable("gui.flansmod.battle.spectating_hint") to 0xFFAAAAAA.toInt()
                else -> null
            }
            banner?.let { (text, color) -> g.text(font, text, (g.guiWidth() - font.width(text)) / 2, 24, color) }
        }
    }

    /** `A ■ Red  [capturing Blue 45%]`, `Red base (stolen)`. */
    fun postLine(s: BattleStatus, post: BattleStatus.PostView): Component {
        val owner = post.team ?: Component.translatable("gui.flansmod.flag.neutral").string
        val name = if (post.hill) "${post.label} · $owner" else post.label.ifEmpty { owner }
        val extra = buildString {
            if (post.locked) append(" (").append(Component.translatable("gui.flansmod.flag.locked").string).append(")")
            if (post.stolen) append(" ").append(Component.translatable("gui.flansmod.flag.stolen").string)
            post.capturingTeam?.let { append("  ").append(Component.translatable("gui.flansmod.battle.capturing", it, (post.progress * 100).toInt()).string) }
        }
        return Component.literal("⚑ $name$extra")
    }
}
