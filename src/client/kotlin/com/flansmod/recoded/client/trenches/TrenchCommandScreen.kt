package com.flansmod.recoded.client.trenches

import com.flansmod.recoded.client.gamemode.BattleHud
import com.flansmod.recoded.client.gamemode.BattleMenuScreen
import com.flansmod.recoded.item.GunItem
import com.flansmod.recoded.trenches.TrenchCommandPayload
import com.flansmod.recoded.trenches.TrenchJobKind
import com.flansmod.recoded.trenches.TrenchView
import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier

/**
 * The Trenches command screen (key M during a Trenches battle): the lane from your headquarters (left) to the enemy's
 * (right) with who holds each trench, how many soldiers of each side are there (and still on their way), bunkers,
 * wire and engineering jobs. Click a trench (or A/D) to select it, then order its soldiers forward or back (W/S,
 * with Shift all of them), have engineers work there, or call a support on it; the bottom rows send squads (keys 1-8).
 * Usable from anywhere - fighting in the field or watching from above ("View from above").
 */
class TrenchCommandScreen : Screen(Component.translatable("gui.flansmod.trenches.title")) {
    private val status get() = BattleHud.current
    private val view: TrenchView? get() = status?.trench

    private var panelX = 0
    private var panelW = 0
    private val laneY = 18
    private val laneH = 50

    private val orderButtons = mutableListOf<Pair<Button, () -> Boolean>>()
    private val unitButtons = mutableListOf<Pair<Button, TrenchView.UnitView>>()
    private val supportButtons = mutableListOf<Pair<Button, String>>()
    private val jobButtons = mutableListOf<Pair<Button, TrenchJobKind>>()
    private var commandButton: Button? = null
    private var shownUnits = emptyList<String>()

    override fun isPauseScreen() = false

    /** Zones in display order: your headquarters on the left. */
    private fun displayOrder(v: TrenchView): List<Int> = v.zones.indices.toList().let { if (v.side == 1) it.reversed() else it }

    private val mySide get() = view?.side?.takeIf { it >= 0 } ?: 0

    override fun init() {
        val v = view ?: return onClose()
        panelW = (width - 16).coerceAtMost(470)
        panelX = (width - panelW) / 2
        orderButtons.clear(); unitButtons.clear(); supportButtons.clear(); jobButtons.clear()
        if (selected !in v.zones.indices) selected = front(v)
        shownUnits = v.units.map { it.id }

        fun row(y: Int, buttons: List<Button>) {
            val w = (panelW - (buttons.size - 1) * 3) / buttons.size.coerceAtLeast(1)
            buttons.forEachIndexed { i, b ->
                b.setRectangle(w, 18, panelX + i * (w + 3), y)
                addRenderableWidget(b)
            }
        }
        fun order(key: String, count: Int, tooltip: String) = Button.builder(Component.translatable(key)) { send(TrenchCommandPayload.ORDER, zone = selected, count = count) }
            .tooltip(Tooltip.create(Component.translatable(tooltip))).build().also { b -> orderButtons += b to { canMove(count) } }
        fun line(key: String, forward: Boolean) = Button.builder(Component.translatable(key)) { send(TrenchCommandPayload.ORDER_ALL, count = if (forward) 1 else -1) }
            .tooltip(Tooltip.create(Component.translatable("$key.tooltip"))).build().also { b -> orderButtons += b to { mine(v) > 0 } }

        var y = laneY + laneH + 14
        row(y, listOf(
            order("gui.flansmod.trenches.advance_one", 1, "gui.flansmod.trenches.advance.tooltip"),
            order("gui.flansmod.trenches.advance_three", 3, "gui.flansmod.trenches.advance.tooltip"),
            order("gui.flansmod.trenches.advance_all", TrenchCommandPayload.ALL, "gui.flansmod.trenches.advance.tooltip"),
            order("gui.flansmod.trenches.retreat_one", -1, "gui.flansmod.trenches.retreat.tooltip"),
            order("gui.flansmod.trenches.retreat_all", -TrenchCommandPayload.ALL, "gui.flansmod.trenches.retreat.tooltip"),
            line("gui.flansmod.trenches.line_forward", true),
            line("gui.flansmod.trenches.line_back", false),
        ))
        y += 21
        val second = buildList {
            TrenchJobKind.entries.forEachIndexed { i, kind ->
                val key = kind.name.lowercase()
                add(Button.builder(Component.empty()) { send(TrenchCommandPayload.JOB, key, selected) }
                    .tooltip(Tooltip.create(Component.translatable("gui.flansmod.trenches.job.$key.tooltip"))).build().also { jobButtons += it to kind })
            }
            for (s in v.supports) {
                add(Button.builder(Component.empty()) { send(TrenchCommandPayload.SUPPORT, s.id, selected) }
                    .tooltip(Tooltip.create(Component.literal(s.description))).build().also { supportButtons += it to s.id })
            }
        }
        row(y, second)
        y += 21
        // Squads: up to four per row.
        v.units.chunked(4).forEachIndexed { r, chunk ->
            row(y + r * 21, chunk.map { u ->
                Button.builder(Component.empty()) { send(TrenchCommandPayload.BUY, u.id) }
                    .tooltip(Tooltip.create(Component.literal("${u.name} (${u.size}) — ${u.description}"))).build().also { unitButtons += it to u }
            })
        }

        val footer = height - 22
        val take = Button.builder(Component.empty()) {
            val v2 = view ?: return@builder
            send(if (v2.commanders.getOrNull(mySide) == minecraft.player?.scoreboardName) TrenchCommandPayload.RELEASE_COMMAND else TrenchCommandPayload.TAKE_COMMAND)
        }.build().also { commandButton = it }
        row(footer, listOf(
            take,
            Button.builder(Component.translatable(if (status?.spectating == true) "gui.flansmod.trenches.view_back" else "gui.flansmod.trenches.view")) {
                send(TrenchCommandPayload.VIEW)
                onClose()
            }.tooltip(Tooltip.create(Component.translatable("gui.flansmod.trenches.view.tooltip"))).build().apply { active = status?.inBattle != true },
            Button.builder(Component.translatable("gui.flansmod.trenches.scoreboard")) { minecraft.gui.setScreen(BattleMenuScreen()) }.build(),
            Button.builder(Component.translatable("gui.done")) { onClose() }.build(),
        ))
        refresh()
    }

    private fun send(action: Int, id: String = "", zone: Int = 0, count: Int = 0) = ClientPlayNetworking.send(TrenchCommandPayload(action, id, zone, count))

    /** Your soldiers ordered to [zone]. */
    private fun mine(v: TrenchView, zone: Int? = null) =
        if (zone == null) v.zones.sumOf { it.units.getOrElse(mySide) { 0 } } else v.zones.getOrNull(zone)?.units?.getOrNull(mySide) ?: 0

    private fun canMove(count: Int): Boolean {
        val v = view ?: return false
        val step = if (mySide == 0) 1 else -1
        val target = selected + if (count > 0) step else -step
        return v.canCommand && target in v.zones.indices && mine(v, selected) > 0
    }

    /** Your most forward zone with soldiers (or your headquarters). */
    private fun front(v: TrenchView): Int {
        val order = displayOrder(v)
        return order.lastOrNull { (v.zones[it].units.getOrNull(mySide) ?: 0) > 0 } ?: order.first()
    }

    override fun tick() {
        val v = view ?: return onClose()
        if (v.units.map { it.id } != shownUnits) return rebuildWidgets()
        refresh()
    }

    /** Labels, prices, cooldowns and what can be done right now. */
    private fun refresh() {
        val v = view ?: return
        val funds = v.funds.getOrElse(mySide) { 0 }
        orderButtons.forEach { (b, can) -> b.active = v.canCommand && can() }
        val z = v.zones.getOrNull(selected)
        val heldByMe = z != null && !z.base && z.owner == mySide
        jobButtons.forEach { (b, kind) ->
            val cost = v.jobCosts.getOrElse(kind.ordinal) { 0 }
            b.message = Component.translatable("gui.flansmod.trenches.job.${kind.name.lowercase()}", cost)
            b.active = v.canCommand && heldByMe && funds >= cost
        }
        supportButtons.forEach { (b, id) ->
            val s = v.supports.firstOrNull { it.id == id } ?: return@forEach
            b.message = Component.literal(if (s.ready > 0) "${s.name} ${s.ready}s" else "${s.name} $${s.cost}")
            b.active = v.canCommand && s.ready == 0 && funds >= s.cost
        }
        val full = v.soldiers.getOrElse(mySide) { 0 }
        unitButtons.forEach { (b, shown) ->
            val u = v.units.firstOrNull { it.id == shown.id } ?: shown
            // Room on the left for the icon.
            b.message = Component.literal("    " + if (u.ready > 0) "${u.name} ${u.ready}s" else "${u.name} $${u.cost}")
            b.active = v.canCommand && u.ready == 0 && funds >= u.cost && full + u.size <= v.unitCap
        }
        val me = minecraft.player?.scoreboardName
        commandButton?.let {
            val commander = v.commanders.getOrNull(mySide)
            it.message = Component.translatable(if (commander == me) "gui.flansmod.trenches.release" else "gui.flansmod.trenches.take")
            it.active = v.side >= 0 && (commander == me || v.canCommand)
        }
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick)
        val v = view ?: return
        val s = status ?: return
        fun rgb(side: Int) = 0xFF000000.toInt() or BattleHud.teamRgb(s, v.teams.getOrNull(side))

        // Header: your side left, the enemy right.
        val enemy = 1 - mySide
        fun sideText(side: Int): String {
            val who = v.commanders.getOrNull(side) ?: if (v.ai.getOrElse(side) { false }) Component.translatable("gui.flansmod.trenches.ai").string else "-"
            return "${v.teams.getOrElse(side) { "?" }}  $${v.funds.getOrElse(side) { 0 }} (+${v.income.getOrElse(side) { 0 }}/s)  " +
                "${v.soldiers.getOrElse(side) { 0 }}/${v.unitCap}  · $who"
        }
        graphics.text(font, sideText(mySide), panelX, 5, rgb(mySide))
        val right = sideText(enemy)
        graphics.text(font, right, panelX + panelW - font.width(right), 5, rgb(enemy))
        val time = if (s.countdown > 0) "▶ ${s.countdown}" else if (s.secondsLeft >= 0) "${s.secondsLeft / 60}:${"%02d".format(s.secondsLeft % 60)}" else ""
        graphics.centeredText(font, time, width / 2, 5, 0xFFFFD27F.toInt())

        // The lane.
        val order = displayOrder(v)
        val gap = 7
        val boxW = (panelW - gap * (order.size - 1)) / order.size.coerceAtLeast(1)
        order.forEachIndexed { d, zi ->
            val z = v.zones[zi]
            val x = panelX + d * (boxW + gap)
            val fill = if (z.owner >= 0) (0x90000000.toInt() or (rgb(z.owner) and 0xFFFFFF)) else 0x90404040.toInt()
            graphics.fill(x, laneY, x + boxW, laneY + laneH, fill)
            graphics.outline(x, laneY, boxW, laneH, if (zi == selected) 0xFFFFFFFF.toInt() else 0xFF202020.toInt())
            val label = if (z.base) Component.translatable("gui.flansmod.trenches.hq").string else z.label
            graphics.centeredText(font, label + if (z.bunker) " ▣" else "", x + boxW / 2, laneY + 3, 0xFFFFFFFF.toInt())
            val own = z.units.getOrElse(mySide) { 0 }
            val ownMoving = z.moving.getOrElse(mySide) { 0 }
            val foe = z.units.getOrElse(enemy) { 0 }
            if (own > 0) graphics.centeredText(font, "▲ ${own - ownMoving}" + if (ownMoving > 0) " +$ownMoving" else "", x + boxW / 2, laneY + 15, 0xFFB0FFB0.toInt())
            if (foe > 0) graphics.centeredText(font, "▼ $foe", x + boxW / 2, laneY + 26, 0xFFFF9090.toInt())
            v.jobs.filter { it.zone == zi && it.side == mySide }.firstOrNull()?.let { j ->
                graphics.centeredText(font, "⚒ ${(j.progress * 100).toInt()}%", x + boxW / 2, laneY + 37, if (j.working) 0xFFFFE070.toInt() else 0xFFAAAAAA.toInt())
            }
            // Capture under way, or the headquarters being stormed.
            val progress = if (z.base) v.hq.getOrElse(if (zi == 0) 0 else 1) { 0f } else z.progress
            val by = if (z.base) (if (zi == 0) 1 else 0) else z.capturing
            if (progress > 0 && by >= 0) {
                graphics.fill(x + 1, laneY + laneH - 4, x + 1 + ((boxW - 2) * progress.coerceIn(0f, 1f)).toInt(), laneY + laneH - 1, rgb(by))
            }
        }
        // Barbed wire between trenches.
        for (zi in v.zones.indices) {
            if (!v.zones[zi].wireAhead || zi + 1 > v.zones.lastIndex) continue
            val d = minOf(order.indexOf(zi), order.indexOf(zi + 1))
            val x = panelX + d * (boxW + gap) + boxW + gap / 2
            graphics.centeredText(font, "✕", x, laneY + laneH / 2 - 4, 0xFFC0C0C0.toInt())
        }

        val z = v.zones.getOrNull(selected)
        if (z != null) {
            val owner = if (z.owner >= 0) v.teams[z.owner] else Component.translatable("gui.flansmod.flag.neutral").string
            val name = if (z.base) Component.translatable("gui.flansmod.trenches.hq_of", owner).string else Component.translatable("gui.flansmod.trenches.trench", z.label, owner).string
            val hint = if (!v.canCommand) Component.translatable(if (v.side < 0) "gui.flansmod.trenches.watching" else "gui.flansmod.trenches.other_commander").string
                else Component.translatable("gui.flansmod.trenches.keys").string
            graphics.text(font, name, panelX, laneY + laneH + 3, 0xFFFFFFFF.toInt())
            graphics.text(font, hint, panelX + panelW - font.width(hint), laneY + laneH + 3, 0xFFAAAAAA.toInt())
        }
        // Squad icons (the gun its first soldier carries).
        for ((b, u) in unitButtons) {
            val icon = u.icon?.let(Identifier::tryParse) ?: continue
            graphics.item(GunItem.stackFor(icon), b.x + 3, b.y + 1)
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val v = view
        if (v != null && event.y() in laneY.toDouble()..(laneY + laneH).toDouble()) {
            val order = displayOrder(v)
            val gap = 7
            val boxW = (panelW - gap * (order.size - 1)) / order.size.coerceAtLeast(1)
            val d = ((event.x() - panelX) / (boxW + gap)).toInt()
            if (d in order.indices) {
                selected = order[d]
                refresh()
                return true
            }
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val v = view ?: return super.keyPressed(event)
        if (BattleMenuScreen.KEY.matches(event)) return true.also { onClose() }
        val order = displayOrder(v)
        val all = minecraft.hasShiftDown()
        when (event.key()) {
            InputConstants.KEY_A, InputConstants.KEY_LEFT -> selected = order[(order.indexOf(selected) - 1).coerceAtLeast(0)]
            InputConstants.KEY_D, InputConstants.KEY_RIGHT -> selected = order[(order.indexOf(selected) + 1).coerceAtMost(order.lastIndex)]
            InputConstants.KEY_W, InputConstants.KEY_UP -> if (canMove(1)) send(TrenchCommandPayload.ORDER, zone = selected, count = if (all) TrenchCommandPayload.ALL else 1)
            InputConstants.KEY_S, InputConstants.KEY_DOWN -> if (canMove(-1)) send(TrenchCommandPayload.ORDER, zone = selected, count = if (all) -TrenchCommandPayload.ALL else -1)
            in InputConstants.KEY_1..InputConstants.KEY_9 -> v.units.getOrNull(event.key() - InputConstants.KEY_1)?.let { send(TrenchCommandPayload.BUY, it.id) }
            else -> return super.keyPressed(event)
        }
        refresh()
        return true
    }

    companion object {
        /** Selected zone (kept while the screen is closed). */
        private var selected = -1
    }
}
