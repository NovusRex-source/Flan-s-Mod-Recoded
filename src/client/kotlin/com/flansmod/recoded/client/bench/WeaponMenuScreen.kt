package com.flansmod.recoded.client.bench

import com.flansmod.recoded.FlansMod
import com.flansmod.recoded.bench.WeaponMenu
import com.flansmod.recoded.gun.FireMode
import com.flansmod.recoded.item.definition
import com.flansmod.recoded.item.fireMode
import com.flansmod.recoded.item.loadedMagazine
import com.flansmod.recoded.item.shotDefinition
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory

/**
 * Weapon menu: attachment slots of the held gun (drag attachments in/out), fire-mode buttons and the
 * resulting stats. Fire-mode buttons use vanilla's menu-button mechanism ([WeaponMenu.clickMenuButton]).
 */
class WeaponMenuScreen(menu: WeaponMenu, private val inventory: Inventory, title: Component) :
    AbstractContainerScreen<WeaponMenu>(menu, inventory, title, 176, 166) {

    private val gun get() = inventory.player.mainHandItem

    override fun init() {
        super.init()
        val modes = gun.definition?.availableModes.orEmpty()
        // Left of the panel: the right side is where recipe viewers show their item list.
        val width = 58
        modes.forEachIndexed { i, mode ->
            val x = leftPos - width - 4
            val y = topPos + 4 + i * 22
            addRenderableWidget(Button.builder(modeName(mode)) { selectMode(mode) }.bounds(x, y, width, 20).build())
        }
    }

    private fun selectMode(mode: FireMode) {
        minecraft.gameMode?.handleInventoryButtonClick(menu.containerId, mode.ordinal)
    }

    private fun modeName(mode: FireMode) = Component.translatable("item.flansmod.gun.mode.${mode.name.lowercase()}")

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick)
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, leftPos, topPos, 0f, 0f, imageWidth, imageHeight, 256, 256)
        menu.slotNames.forEachIndexed { i, _ ->
            val x = leftPos + WeaponMenu.slotX(i, menu.slotNames.size) - 1
            val y = topPos + WeaponMenu.SLOT_Y - 1
            graphics.fill(x, y, x + 18, y + 18, 0xFF373737.toInt())
            graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF.toInt())
            graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B.toInt())
        }
    }

    override fun extractLabels(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        super.extractLabels(graphics, mouseX, mouseY)
        menu.slotNames.forEachIndexed { i, name ->
            val label = Component.translatableWithFallback("attachment.flansmod.$name", name)
            val center = WeaponMenu.slotX(i, menu.slotNames.size) + 8
            graphics.text(font, label.string, center - font.width(label) / 2, WeaponMenu.SLOT_Y - 11, 0xFF404040.toInt(), false)
        }
        if (menu.slotNames.isEmpty()) {
            val text = Component.translatable("gui.flansmod.weapon.no_slots")
            graphics.text(font, text.string, (imageWidth - font.width(text)) / 2, WeaponMenu.SLOT_Y + 4, 0xFF404040.toInt(), false)
        }
        // Current stats with attachments and loaded ammo.
        val shot = gun.shotDefinition ?: return
        val mag = gun.loadedMagazine
        val stats = "${shot.damage.format()} dmg · ${shot.rpm} rpm · ${mag?.rounds ?: 0}/${mag?.capacity ?: 0} · ${modeName(gun.fireMode).string}"
        graphics.text(font, stats, (imageWidth - font.width(stats)) / 2, 56, 0xFF404040.toInt(), false)
    }

    private fun Float.format() = if (this % 1f == 0f) toInt().toString() else "%.1f".format(this)

    private companion object {
        val TEXTURE = FlansMod.id("textures/gui/weapon_menu.png")
    }
}
