package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gamemode.BattleActionPayload
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * In-battle menu (key M): the scoreboard - every team with its fighters' kills, deaths and captures (bots marked) -
 * the flag posts and their owners, and the buttons that apply right now: leave the battle (at your flag post or while
 * waiting for a spawn), stop spectating, leave the team. Also draws the battle border as a red particle wall.
 */
class BattleMenuScreen : Screen(Component.translatable("gui.flansmod.battle.menu")) {
    private val status get() = BattleHud.current

    override fun init() {
        val s = status ?: return onClose()
        val y = height - 30
        val buttons = buildList {
            if (s.inBattle) add(Button.builder(Component.translatable("gui.flansmod.flag.leave")) { act(BattleActionPayload.LEAVE) }.tooltip(
                net.minecraft.client.gui.components.Tooltip.create(Component.translatable("gui.flansmod.battle.leave_hint"))).build().apply { active = s.nearFlag || s.waiting })
            if (s.spectating) add(Button.builder(Component.translatable("gui.flansmod.battle.stop_spectating")) { act(BattleActionPayload.STOP_SPECTATING) }.build())
            if (!s.inBattle && s.team.isNotEmpty()) add(Button.builder(Component.translatable("gui.flansmod.battle.leave")) { act(BattleActionPayload.QUIT_TEAM) }.build())
            add(Button.builder(Component.translatable("gui.done")) { onClose() }.build())
        }
        val width = 100
        var x = (this.width - buttons.size * (width + 4)) / 2
        for (b in buttons) {
            b.setRectangle(width, 20, x, y)
            addRenderableWidget(b)
            x += width + 4
        }
    }

    private fun act(action: Int) {
        ClientPlayNetworking.send(BattleActionPayload(action))
        onClose()
    }

    override fun isPauseScreen() = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val s = status ?: return
        val mode = Component.translatable("gui.flansmod.battle.mode.${s.mode.name.lowercase()}").string
        val time = when {
            s.countdown > 0 -> Component.translatable("gui.flansmod.battle.starts_in", s.countdown).string
            s.secondsLeft >= 0 -> "${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}"
            !s.running -> Component.translatable("gui.flansmod.battle.lobby").string
            else -> ""
        }
        graphics.text(font, Component.literal("${s.name} · $mode  $time"), (width - font.width("${s.name} · $mode  $time")) / 2, 12, 0xFFFFD27F.toInt())

        // One column per team: score, then its fighters.
        val columns = s.teams.size.coerceAtLeast(1)
        val columnWidth = ((width - 40) / columns).coerceAtMost(180)
        val left = (width - columnWidth * columns) / 2
        s.teams.forEachIndexed { i, team ->
            val x = left + i * columnWidth
            val color = 0xFF000000.toInt() or team.rgb
            graphics.fill(x + 2, 28, x + columnWidth - 2, 42, 0x80000000.toInt())
            val limit = if (s.scoreLimit > 0) "/${s.scoreLimit}" else ""
            graphics.text(font, Component.literal("${team.name}  ${team.score}$limit" + if (team.name == s.team) " ◀" else ""), x + 6, 31, color)
            graphics.text(font, Component.literal("K  D  C"), x + columnWidth - 6 - font.width("K  D  C"), 31, 0xFFAAAAAA.toInt())
            s.fighters.filter { it.team == team.name }.forEachIndexed { row, f ->
                val y = 46 + row * 11
                if (y > height - 110) return@forEachIndexed
                val name = (if (f.bot) "⚙ " else "") + f.name
                graphics.text(font, Component.literal(font.plainSubstrByWidth(name, columnWidth - 60)), x + 6, y, if (f.bot) 0xFFBBBBBB.toInt() else 0xFFFFFFFF.toInt())
                val numbers = "%d  %d  %d".format(f.kills, f.deaths, f.captures)
                graphics.text(font, Component.literal(numbers), x + columnWidth - 6 - font.width(numbers), y, 0xFFFFFFFF.toInt())
            }
        }

        // Flag posts.
        var y = height - 100
        if (s.posts.isNotEmpty()) graphics.text(font, Component.translatable("gui.flansmod.battle.posts"), left + 6, y, 0xFFFFD27F.toInt())
        for (post in s.posts) {
            y += 11
            if (y > height - 40) break
            graphics.text(font, BattleHud.postLine(s, post), left + 12, y, 0xFF000000.toInt() or BattleHud.teamRgb(s, post.team))
        }
        s.border?.let { b ->
            val text = Component.translatable("gui.flansmod.battle.border", b[2] - b[0], b[3] - b[1])
            graphics.text(font, text, width - left - 6 - font.width(text), height - 100, 0xFFFF7070.toInt())
        }
    }

    companion object {
        val KEY: KeyMapping = KeyMappingHelper.registerKeyMapping(
            KeyMapping("key.flansmod.battle_menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_M, KeyMapping.Category(FlansMod.id("flansmod"))),
        )

        private var particleTicks = 0

        fun init() {
            ClientTickEvents.END_CLIENT_TICK.register { mc ->
                while (KEY.consumeClick()) {
                    // Trenches: the command screen first (it links to this scoreboard).
                    if (BattleHud.current?.trench != null) mc.gui.setScreen(com.flansmod.recoded.client.trenches.TrenchCommandScreen())
                    else if (BattleHud.current != null) mc.gui.setScreen(BattleMenuScreen())
                    else mc.player?.sendOverlayMessage(Component.translatable("gui.flansmod.battle.not_in_battle"))
                }
                borderParticles(mc)
            }
        }

        /** A red wall of dust where the battle border is within 12 blocks. */
        private fun borderParticles(mc: Minecraft) {
            val s = BattleHud.current ?: return
            val b = s.border ?: return
            val player = mc.player ?: return
            if (!s.inBattle || ++particleTicks % 2 != 0) return
            val dust = DustParticleOptions(0xFF3030, 1.5f)
            val random = player.random
            fun spawn(x: Double, z: Double) {
                val y = player.y - 1 + random.nextDouble() * 4
                mc.particleEngine.createParticle(dust, x, y, z, 0.0, 0.0, 0.0)
            }
            val (minX, minZ, maxX, maxZ) = b.map(Int::toDouble)
            repeat(10) {
                val along = (random.nextDouble() - 0.5) * 16
                if (abs(player.x - minX) < 12) spawn(minX, (player.z + along).coerceIn(minZ, maxZ))
                if (abs(player.x - maxX) < 12) spawn(maxX, (player.z + along).coerceIn(minZ, maxZ))
                if (abs(player.z - minZ) < 12) spawn((player.x + along).coerceIn(minX, maxX), minZ)
                if (abs(player.z - maxZ) < 12) spawn((player.x + along).coerceIn(minX, maxX), maxZ)
            }
        }
    }
}
