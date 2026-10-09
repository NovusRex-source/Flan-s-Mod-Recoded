package com.flansmod.recoded.client.gamemode

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.gamemode.BattleMasterMenu
import com.flansmod.recoded.gamemode.BattleMode
import com.flansmod.recoded.gamemode.ShopEditorMenu
import com.flansmod.recoded.gamemode.BattleSpawnMenu
import com.flansmod.recoded.gamemode.BattleSettings
import com.flansmod.recoded.gamemode.BattleSettingsPayload
import com.flansmod.recoded.gamemode.BattleTeam
import com.flansmod.recoded.gamemode.TeamFlagMenu
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.renderer.Rect2i
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.player.Inventory

private const val DARK = 0xFF404040.toInt()

/** Screens with a button column beside the panel: its area, so JEI moves its item list out of the way. */
interface SideButtons {
    fun sideAreas(): List<Rect2i>
}

private fun AbstractContainerScreen<*>.widgetsRightOf(x: Int): List<Rect2i> =
    children().filterIsInstance<AbstractWidget>().filter { it.x >= x }.map { Rect2i(it.x, it.y, it.width, it.height) }

/**
 * Battle Master: teams with scores, members and bots, join/leave/enter, spectate; for its manager start/stop, settings
 * and each team's shop editor (button column on the right).
 */
class BattleMasterScreen(menu: BattleMasterMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<BattleMasterMenu>(menu, inventory, title, 200, 186), SideButtons {
    override fun sideAreas() = widgetsRightOf(leftPos + imageWidth)

    private val view get() = menu.view

    override fun init() {
        super.init()
        view.teams.forEachIndexed { i, team ->
            addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.battle.join", team.name)) { press(i) }
                .bounds(leftPos + 120, topPos + 40 + i * 22, 72, 18).build()).active = view.myTeam != team.name && !view.inBattle
        }
        val y = topPos + imageHeight - 26
        fun button(key: String, id: Int, visible: Boolean, x: Int, w: Int = 60) {
            if (visible) addRenderableWidget(Button.builder(Component.translatable(key)) { press(id) }.bounds(leftPos + x, y, w, 18).build())
        }
        button("gui.flansmod.battle.leave", BattleMasterMenu.LEAVE, view.myTeam != null && !view.inBattle, 8)
        button("gui.flansmod.battle.enter", BattleMasterMenu.ENTER, view.running && view.myTeam != null && !view.inBattle, 8)
        button(if (view.spectating) "gui.flansmod.battle.stop_spectating" else "gui.flansmod.battle.spectate", BattleMasterMenu.SPECTATE, !view.inBattle, 70, 80)

        // Manager column.
        var row = 0
        fun side(text: Component, active: Boolean = true, action: () -> Unit) {
            addRenderableWidget(Button.builder(text) { action() }.bounds(leftPos + imageWidth + 4, topPos + 4 + row++ * 20, 90, 18).build()).active = active
        }
        if (!view.canManage) return
        if (view.running) side(Component.translatable("gui.flansmod.battle.stop")) { press(BattleMasterMenu.STOP) }
        else side(Component.translatable("gui.flansmod.battle.start")) { press(BattleMasterMenu.START) }
        side(Component.translatable("gui.flansmod.battle.settings"), !view.running) {
            minecraft.gui.setScreen(BattleSettingsScreen.create(this, view.settings, BlockPos.of(view.pos)))
        }
        view.teams.forEachIndexed { i, team ->
            side(Component.translatable("gui.flansmod.battle.shop", team.name).withColor(team.rgb)) { press(BattleMasterMenu.SHOP + i) }
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
        val settings = view.settings
        val size = if (settings.teamSize > 0) Component.translatable("gui.flansmod.battle.team_size", settings.teamSize).string + if (settings.fillWithBots) " + " +
            Component.translatable("gui.flansmod.battle.bots").string else "" else ""
        graphics.text(font, Component.literal(Component.translatable("gui.flansmod.battle.mode.${settings.mode.name.lowercase()}").string + size), 8, 17, 0xFF2B4A80.toInt(), false)
        graphics.text(font, Component.translatable("gui.flansmod.battle.limits", settings.scoreLimit, settings.startMoney, settings.killReward), 8, 28, 0xFF606060.toInt(), false)
        view.teams.forEachIndexed { i, team ->
            val y = 40 + i * 22
            graphics.fill(8, y, 14, y + 6, 0xFF000000.toInt() or team.rgb)
            graphics.text(font, Component.literal("${team.name}  ${team.score}"), 18, y - 1, DARK, false)
            val flag = if (team.hasFlag) "" else " · " + Component.translatable("gui.flansmod.battle.no_flag").string
            val bots = if (team.bots > 0) " +${team.bots} " + Component.translatable("gui.flansmod.battle.bots").string else ""
            graphics.text(font, Component.literal(font.plainSubstrByWidth(team.members.joinToString(", ").ifEmpty { "-" } + bots + flag, 100)), 18, y + 9, 0xFF707070.toInt(), false)
        }
        val info = Component.translatable("gui.flansmod.battle.field", view.posts, view.border)
        graphics.text(font, info, 8, imageHeight - 50, 0xFF707070.toInt(), false)
        view.myTeam?.let { graphics.text(font, Component.translatable("gui.flansmod.battle.your_team", it), 8, imageHeight - 38, DARK, false) }
    }
}

/** Team flag post: owner, money, the shop page (click an item to buy it), claim/enter/leave, pages and manager tools. */
class TeamFlagScreen(menu: TeamFlagMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<TeamFlagMenu>(menu, inventory, title, 176, 186), SideButtons {
    override fun sideAreas() = widgetsRightOf(leftPos + imageWidth)

    private val view get() = menu.view

    init {
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()
        var row = 0
        fun side(key: String, id: Int, visible: Boolean) {
            if (visible) addRenderableWidget(Button.builder(Component.translatable(key)) { minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id) }
                .bounds(leftPos + imageWidth + 4, topPos + 4 + row++ * 18, 70, 14).build())
        }
        side("gui.flansmod.flag.claim", TeamFlagMenu.CLAIM, view.canClaim)
        side("gui.flansmod.battle.enter", TeamFlagMenu.ENTER, view.mine && view.running && !view.inBattle)
        side("gui.flansmod.flag.leave", TeamFlagMenu.LEAVE, view.inBattle)
        side(if (view.locked) "gui.flansmod.flag.unlock" else "gui.flansmod.flag.lock", TeamFlagMenu.LOCK, view.canManage)
        side(if (view.hill) "gui.flansmod.flag.unhill" else "gui.flansmod.flag.hill", TeamFlagMenu.HILL, view.canManage)
        side("gui.flansmod.flag.release", TeamFlagMenu.RELEASE, view.canManage && (view.team != null || view.hill))
        fun page(key: String, id: Int, x: Int) {
            if (view.pages > 1) addRenderableWidget(Button.builder(Component.translatable(key)) { minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id) }
                .bounds(leftPos + x, topPos + 85, 20, 14).build())
        }
        page("gui.flansmod.flag.prev", TeamFlagMenu.PREV, 126)
        page("gui.flansmod.flag.next", TeamFlagMenu.NEXT, 148)
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, FlansMod.id("textures/gui/team_flag.png"), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
        graphics.fill(leftPos + 8, topPos + 6, leftPos + 14, topPos + 12, 0xFF000000.toInt() or view.rgb)
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val name = when {
            view.hill -> Component.translatable("gui.flansmod.flag.hill_name", view.label, view.team ?: Component.translatable("gui.flansmod.flag.neutral").string)
            view.team != null -> Component.literal(view.team!!)
            else -> Component.translatable("gui.flansmod.flag.unclaimed")
        }
        val tags = listOfNotNull(view.battle, Component.translatable("gui.flansmod.flag.locked").string.takeIf { view.locked },
            Component.translatable("gui.flansmod.flag.stolen").string.takeIf { view.stolen })
        graphics.text(font, Component.literal(font.plainSubstrByWidth((listOf(name.string) + tags).joinToString(" · "), 150)), 18, 6, DARK, false)
        val line = when {
            view.inBattle -> Component.translatable("gui.flansmod.flag.money", menu.money)
            view.mine && view.running -> Component.translatable("gui.flansmod.flag.enter_hint")
            view.mine -> Component.translatable("gui.flansmod.flag.waiting")
            view.hill -> Component.translatable("gui.flansmod.flag.hill_hint")
            view.team != null -> Component.translatable("gui.flansmod.flag.not_yours")
            else -> Component.translatable("gui.flansmod.flag.claim_hint")
        }
        graphics.text(font, line, 8, 18, if (view.inBattle) 0xFF8A6D00.toInt() else 0xFF606060.toInt(), false)
        if (view.pages > 1) graphics.text(font, Component.literal("${menu.currentPage + 1}/${view.pages}"), 100, 88, DARK, false)
        graphics.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, DARK, false)
    }
}

/**
 * Shop editor: the team's shop as the flag post shows it. Drop items on slots (copies), click to select, price
 * buttons, remove/reset, team and page buttons on the right.
 */
class ShopEditorScreen(menu: ShopEditorMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<ShopEditorMenu>(menu, inventory, title, 176, 168), SideButtons {
    override fun sideAreas() = widgetsRightOf(leftPos + imageWidth)

    private val view get() = menu.view

    init {
        inventoryLabelY = imageHeight - 94
    }

    override fun init() {
        super.init()
        fun press(id: Int) = minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id)
        val x = leftPos + imageWidth + 4
        view.teams.forEachIndexed { i, team ->
            addRenderableWidget(Button.builder(Component.literal(team)) { press(ShopEditorMenu.TEAM + i) }
                .bounds(x + i % 2 * 46, topPos + 4 + i / 2 * 18, 44, 14).build()).active = i != view.team
        }
        val y = topPos + 10 + (view.teams.size + 1) / 2 * 18
        ShopEditorMenu.STEPS.forEachIndexed { i, step ->
            addRenderableWidget(Button.builder(Component.literal("%+d".format(step))) { press(ShopEditorMenu.PRICE + i) }
                .bounds(x + i % 3 * 31, y + i / 3 * 16, 29, 14).build())
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.shop.remove")) { press(ShopEditorMenu.REMOVE) }.bounds(x, y + 36, 91, 14).build())
        addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.shop.reset")) { press(ShopEditorMenu.RESET) }.bounds(x, y + 52, 91, 14).build())
        addRenderableWidget(Button.builder(Component.literal("<")) { press(ShopEditorMenu.PREV) }.bounds(leftPos + 126, topPos + 3, 20, 12).build())
        addRenderableWidget(Button.builder(Component.literal(">")) { press(ShopEditorMenu.NEXT) }.bounds(leftPos + 148, topPos + 3, 20, 12).build())
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        // Vanilla's chest texture, cut down to three rows like a single chest.
        val texture = Identifier.withDefaultNamespace("textures/gui/container/generic_54.png")
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, leftPos, topPos, 0f, 0f, imageWidth, 3 * 18 + 17, 256, 256)
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, leftPos, topPos + 3 * 18 + 17, 0f, 126f, imageWidth, 96, 256, 256)
        val selected = menu.selected
        if (selected >= 0) {
            val sx = leftPos + 7 + selected % 9 * 18
            val sy = topPos + 17 + selected / 9 * 18
            graphics.fill(sx, sy, sx + 18, sy + 1, GOLD); graphics.fill(sx, sy + 17, sx + 18, sy + 18, GOLD)
            graphics.fill(sx, sy, sx + 1, sy + 18, GOLD); graphics.fill(sx + 17, sy, sx + 18, sy + 18, GOLD)
        }
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.fill(8, 6, 14, 12, 0xFF000000.toInt() or view.rgb)
        graphics.text(font, title, 18, 5, DARK, false)
        graphics.text(font, Component.literal("${menu.page + 1}/${menu.pages}"), 100, 5, DARK, false)
        val price = if (menu.selected >= 0) Component.translatable("gui.flansmod.shop.price", menu.price) else Component.translatable("gui.flansmod.shop.select")
        graphics.text(font, price, 8, inventoryLabelY, if (menu.selected >= 0) 0xFF8A6D00.toInt() else 0xFF606060.toInt(), false)
        val hint = Component.translatable("gui.flansmod.shop.hint")
        graphics.text(font, hint, (imageWidth - font.width(hint)) / 2, imageHeight + 4, 0xFFFFFFFF.toInt())
    }

    private companion object {
        val GOLD = 0xFFFFC000.toInt()
    }
}

/**
 * Default spawn point: which team (and so which faction) respawns here when it has no flag post. Moderators (the
 * battle's manager, not fighting) pick the team; everyone else sees the assignment.
 */
class BattleSpawnScreen(menu: BattleSpawnMenu, inventory: Inventory, title: Component) :
    AbstractContainerScreen<BattleSpawnMenu>(menu, inventory, title, 176, 166) {
    private val view get() = menu.view

    override fun init() {
        super.init()
        if (!view.canModerate) return
        fun press(id: Int) = minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, id)
        view.teams.forEachIndexed { i, team ->
            val label = Component.literal(team.name + (team.faction?.let { " · $it" } ?: "")).withColor(team.rgb)
            addRenderableWidget(Button.builder(label) { press(i) }.bounds(leftPos + 8, topPos + 40 + i * 20, 160, 18).build()).active = view.team != team.name
        }
        addRenderableWidget(Button.builder(Component.translatable("gui.flansmod.spawn.clear")) { press(BattleSpawnMenu.CLEAR) }
            .bounds(leftPos + 8, topPos + imageHeight - 26, 160, 18).build()).active = view.team != null
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, FlansMod.id("textures/gui/battle_spawn.png"), leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        graphics.text(font, title, 8, 6, DARK, false)
        val team = view.teams.firstOrNull { it.name == view.team }
        val line = when {
            view.battle == null -> Component.translatable("gui.flansmod.spawn.no_battle")
            team != null -> Component.translatable("gui.flansmod.spawn.team", team.name, team.faction ?: "-")
            else -> Component.translatable("gui.flansmod.spawn.none")
        }
        graphics.text(font, Component.literal(font.plainSubstrByWidth(line.string, 160)), 8, 18, team?.let { 0xFF000000.toInt() or it.rgb } ?: 0xFF606060.toInt(), false)
        if (view.battle != null && !view.canModerate) graphics.text(font, Component.translatable("gui.flansmod.spawn.moderators"), 8, 30, 0xFF707070.toInt(), false)
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
        general.addEntry(entries.startEnumSelector(text("mode"), BattleMode::class.java, settings.mode)
            .setEnumNameProvider { Component.translatable("gui.flansmod.battle.mode.${it.name.lowercase()}") }.setTooltip(text("mode.tooltip"))
            .setSaveConsumer { edited = edited.copy(mode = it) }.build())
        general.addEntry(entries.startSelector(text("game_mode"), arrayOf("survival", "adventure"), settings.gameMode.lowercase())
            .setNameProvider { Component.translatable("gameMode.$it") }.setSaveConsumer { edited = edited.copy(gameMode = it) }.build())
        general.addEntry(entries.startStrList(text("teams"), settings.teams.map(BattleTeam::format))
            .setTooltip(text("teams.tooltip")).setSaveConsumer { list -> edited = edited.copy(teams = list.mapNotNull(BattleTeam::parse).distinctBy { it.name }.take(16)) }.build())
        general.addEntry(entries.startStrList(text("alliances"), settings.alliances).setTooltip(text("alliances.tooltip"))
            .setSaveConsumer { edited = edited.copy(alliances = it) }.build())
        general.addEntry(entries.startIntField(text("team_size"), settings.teamSize).setMin(0).setTooltip(text("zero_off"))
            .setSaveConsumer { edited = edited.copy(teamSize = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("fill_with_bots"), settings.fillWithBots).setTooltip(text("fill_with_bots.tooltip"))
            .setSaveConsumer { edited = edited.copy(fillWithBots = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("bot_respawn"), settings.botRespawn).setTooltip(text("bot_respawn.tooltip"))
            .setSaveConsumer { edited = edited.copy(botRespawn = it) }.build())
        general.addEntry(entries.startIntField(text("bot_respawn_seconds"), settings.botRespawnSeconds).setMin(0)
            .setSaveConsumer { edited = edited.copy(botRespawnSeconds = it) }.build())
        general.addEntry(entries.startIntField(text("countdown"), settings.countdownSeconds).setMin(0).setSaveConsumer { edited = edited.copy(countdownSeconds = it) }.build())
        general.addEntry(entries.startIntField(text("capture_seconds"), settings.captureSeconds).setMin(1).setTooltip(text("capture_seconds.tooltip"))
            .setSaveConsumer { edited = edited.copy(captureSeconds = it) }.build())
        general.addEntry(entries.startIntField(text("point_seconds"), settings.pointSeconds).setMin(1).setTooltip(text("point_seconds.tooltip"))
            .setSaveConsumer { edited = edited.copy(pointSeconds = it) }.build())
        general.addEntry(entries.startIntField(text("start_money"), settings.startMoney).setMin(0).setSaveConsumer { edited = edited.copy(startMoney = it) }.build())
        general.addEntry(entries.startIntField(text("kill_reward"), settings.killReward).setMin(0).setSaveConsumer { edited = edited.copy(killReward = it) }.build())
        general.addEntry(entries.startIntField(text("income"), settings.incomePerMinute).setMin(0).setSaveConsumer { edited = edited.copy(incomePerMinute = it) }.build())
        general.addEntry(entries.startIntField(text("score_limit"), settings.scoreLimit).setMin(0).setTooltip(text("score_limit.tooltip")).setSaveConsumer { edited = edited.copy(scoreLimit = it) }.build())
        general.addEntry(entries.startIntField(text("time_limit"), settings.timeLimitMinutes).setMin(0).setTooltip(text("zero_off")).setSaveConsumer { edited = edited.copy(timeLimitMinutes = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("block_damage"), settings.blockDamage).setTooltip(text("block_damage.tooltip"))
            .setSaveConsumer { edited = edited.copy(blockDamage = it) }.build())
        general.addEntry(entries.startBooleanToggle(text("border_wall"), settings.borderWall).setTooltip(text("border_wall.tooltip"))
            .setSaveConsumer { edited = edited.copy(borderWall = it) }.build())
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
