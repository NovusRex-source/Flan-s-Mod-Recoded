package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gamemode.BattleMasterMenu
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleSettingsPayload
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.TeamFlagMenu
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

private const val DARK = 0xFF404040.toInt()

/** Battle Master: teams with scores and members, join/leave/enter, and for its manager start/stop/settings. */
class BattleMasterScreen(menu: BattleMasterMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<BattleMasterMenu>(menu, inventory, title, 200, 186) {
    private val view get() = menu.view

    override fun init() {
        super.init()
        view.teams.forEachIndexed { i, team ->
            addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.battle.join", team.name)) { press(i) }
                .bounds(leftPos + 120, topPos + 30 + i * 22, 72, 18).build()).active = view.myTeam != team.name && !view.inBattle
        }
        var y = topPos + imageHeight - 26
        fun button(key: String, id: Int, visible: Boolean, x: Int, w: Int = 60) {
            if (visible) addRenderableWidget(Button.builder(Component.translatable(key)) { press(id) }.bounds(leftPos + x, y, w, 18).build())
        }
        button("gui.flansmod.battle.leave", BattleMasterMenu.LEAVE, view.myTeam != null && !view.inBattle, 8)
        button("gui.flansmod.battle.enter", BattleMasterMenu.ENTER, view.running && view.myTeam != null && !view.inBattle, 8)
        button("gui.flansmod.battle.start", BattleMasterMenu.START, view.canManage && !view.running, 70)
        button("gui.flansmod.battle.stop", BattleMasterMenu.STOP, view.canManage && view.running, 70)
        if (view.canManage && !view.running) {
            addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.battle.settings")) {
                minecraft.gui.setScreen(BattleSettingsScreen.create(this, view.settings, BlockPos.of(view.pos)))
            }.bounds(leftPos + 132, y, 60, 18).build())
        }
    }

    private fun press(id: Int) = minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id)

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, FlansMod.id("textures/gui/battle_master.png"), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, Component.literal(view.settings.name), 8, 6, DARK, false)
        val state = when {
            view.running && view.secondsLeft >= 0 -> Component.translatable("gui.flansmod.battle.running_time", view.secondsLeft / 60, "%02d".format(view.secondsLeft % 60))
            view.running -> Component.translatable("gui.flansmod.battle.running")
            else -> Component.translatable("gui.flansmod.battle.lobby")
        }
        graphics.text(font, state, imageWidth - 8 - font.width(state), 6, if (view.running) 0xFF2E7D32.toInt() else DARK, false)
        graphics.text(font, Component.translatable("gui.flansmod.battle.limits", view.settings.scoreLimit, view.settings.startMoney, view.settings.killReward), 8, 17, 0xFF606060.toInt(), false)
        view.teams.forEachIndexed { i, team ->
            val y = 30 + i * 22
            graphics.fill(8, y, 14, y + 6, 0xFF000000.toInt() or team.rgb)
            graphics.text(font, Component.literal("${team.name}  ${team.score}"), 18, y - 1, DARK, false)
            val flag = if (team.hasFlag) "" else " · " + Component.translatable("gui.flansmod.battle.no_flag").string
            graphics.text(font, Component.literal(font.plainSubstrByWidth(team.members.joinToString(", ").ifEmpty { "-" } + flag, 100)), 18, y + 9, 0xFF707070.toInt(), false)
        }
        view.myTeam?.let { graphics.text(font, Component.translatable("gui.flansmod.battle.your_team", it), 8, imageHeight - 40, DARK, false) }
    }
}

/** Team flag post: team, money, the shop page (click an item to buy it), claim/enter/leave and page buttons. */
class TeamFlagScreen(menu: TeamFlagMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<TeamFlagMenu>(menu, inventory, title, 176, 186) {
    private val view get() = menu.view

    init {
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()
        fun button(key: String, id: Int, visible: Boolean, x: Int, y: Int, w: Int = 50) {
            if (visible) addRenderableWidget(Button.builder(Component.translatable(key)) { minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id) }
                .bounds(leftPos + x, topPos + y, w, 14).build())
        }
        button("gui.flansmod.flag.claim", TeamFlagMenu.CLAIM, view.canClaim, imageWidth + 4, 4, 60)
        button("gui.flansmod.battle.enter", TeamFlagMenu.ENTER, view.mine && view.running && !view.inBattle, imageWidth + 4, 22, 60)
        button("gui.flansmod.flag.leave", TeamFlagMenu.LEAVE, view.inBattle, imageWidth + 4, 22, 60)
        button("gui.flansmod.flag.prev", TeamFlagMenu.PREV, view.pages > 1, 126, 85, 20)
        button("gui.flansmod.flag.next", TeamFlagMenu.NEXT, view.pages > 1, 148, 85, 20)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, FlansMod.id("textures/gui/team_flag.png"), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
        graphics.fill(leftPos + 8, topPos + 6, leftPos + 14, topPos + 12, 0xFF000000.toInt() or view.rgb)
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val title = view.team?.let { Component.literal("$it · ${view.battle}") } ?: Component.translatable("gui.flansmod.flag.unclaimed")
        graphics.text(font, title, 18, 6, DARK, false)
        val line = when {
            view.inBattle -> Component.translatable("gui.flansmod.flag.money", menu.money)
            view.mine && view.running -> Component.translatable("gui.flansmod.flag.enter_hint")
            view.mine -> Component.translatable("gui.flansmod.flag.waiting")
            view.team != null -> Component.translatable("gui.flansmod.flag.not_yours")
            else -> Component.translatable("gui.flansmod.flag.claim_hint")
        }
        graphics.text(font, line, 8, 18, if (view.inBattle) 0xFF8A6D00.toInt() else 0xFF606060.toInt(), false)
        if (view.pages > 1) graphics.text(font, Component.literal("${menu.currentPage + 1}/${view.pages}"), 100, 88, DARK, false)
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, DARK, false)
    }
}

/** Battle settings, edited with Cloth Config; saving sends them to the server (manager only, outside a running battle). */
object BattleSettingsScreen {
    fun create(parent: Screen, settings: BattleSettings, pos: BlockPos): Screen {
        var edited = settings
        val builder = ConfigBuilder.create().setParentScreen(parent).setTitle(Component.translatable("gui.flansmod.battle.settings_title"))
        val entries = builder.entryBuilder()
        val general = builder.getOrCreateCategory(Component.translatable("gui.flansmod.battle.settings.general"))
        fun text(key: String) = Component.translatable("gui.flansmod.battle.settings.$key")
        general.addEntry(entries.startStrField(text("name"), settings.name).setSaveConsumer { edited = edited.copy(name = it) }.build())
        general.addEntry(entries.startStrList(text("teams"), settings.teams.map(BattleTeam::format))
            .setTooltip(text("teams.tooltip")).setSaveConsumer { list -> edited = edited.copy(teams = list.mapNotNull(BattleTeam::parse).distinctBy { it.name }.take(16)) }.build())
        general.addEntry(entries.startStrList(text("alliances"), settings.alliances).setTooltip(text("alliances.tooltip"))
            .setSaveConsumer { edited = edited.copy(alliances = it) }.build())
        general.addEntry(entries.startIntField(text("start_money"), settings.startMoney).setMin(0).setSaveConsumer { edited = edited.copy(startMoney = it) }.build())
        general.addEntry(entries.startIntField(text("kill_reward"), settings.killReward).setMin(0).setSaveConsumer { edited = edited.copy(killReward = it) }.build())
        general.addEntry(entries.startIntField(text("income"), settings.incomePerMinute).setMin(0).setSaveConsumer { edited = edited.copy(incomePerMinute = it) }.build())
        general.addEntry(entries.startIntField(text("score_limit"), settings.scoreLimit).setMin(0).setTooltip(text("zero_off")).setSaveConsumer { edited = edited.copy(scoreLimit = it) }.build())
        general.addEntry(entries.startIntField(text("time_limit"), settings.timeLimitMinutes).setMin(0).setTooltip(text("zero_off")).setSaveConsumer { edited = edited.copy(timeLimitMinutes = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("friendly_fire"), settings.friendlyFire).setSaveConsumer { edited = edited.copy(friendlyFire = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("keep_loadout"), settings.keepLoadout).setSaveConsumer { edited = edited.copy(keepLoadout = it) }.build())
        general.addEntry(entries.startIntField(text("respawn_protection"), settings.respawnProtection).setMin(0).setSaveConsumer { edited = edited.copy(respawnProtection = it) }.build())
        val shop = builder.getOrCreateCategory(Component.translatable("gui.flansmod.battle.settings.shop"))
        shop.addEntry(entries.startStrList(text("shop"), settings.shop).setTooltip(text("shop.tooltip")).setSaveConsumer { edited = edited.copy(shop = it) }.build())
        builder.setSavingRunnable {
            ClientPlayNetworking.send(BattleSettingsPayload(pos, edited.toJson()))
        }
        return builder.build()
    }
}
